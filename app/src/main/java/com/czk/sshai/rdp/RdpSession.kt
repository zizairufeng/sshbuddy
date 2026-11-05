package com.czk.sshai.rdp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.KeyEvent
import com.czk.sshai.SshConfig
import com.czk.sshai.remote.RemoteButtons
import com.czk.sshai.remote.RemoteSession
import com.freerdp.freerdpcore.services.LibFreeRDP
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一层薄封装：管理 FreeRDP native 实例的生命周期、把 JNI 回调翻译成
 * 给 Compose 用的状态回调。
 *
 * 渲染模型和 aFreeRDP 一致：维护一张与远端桌面同尺寸的 ARGB_8888 Bitmap，
 * native 侧报脏矩形，我们把该矩形从 GDI 缓冲拷进 Bitmap，再通知 UI 重绘这块。
 */
class RdpSession(
    private val context: Context,
    private val cfg: SshConfig,
    private val events: Events,
) : LibFreeRDP.Listener, RemoteSession {

    interface Events {
        /** 连接建立，远端桌面尺寸已知 */
        fun onReady(width: Int, height: Int)

        /** 某块区域有新内容（位图已更新），坐标为远端桌面像素 */
        fun onFrameDirty(x: Int, y: Int, w: Int, h: Int)

        /** 远端分辨率变化 */
        fun onResized(width: Int, height: Int, bpp: Int)

        fun onFailed(message: String)

        fun onClosed()

        /** 证书确认，返回 true 表示信任并继续 */
        fun onCertificate(host: String, fingerprint: String, changed: Boolean): Boolean

        /** 远端光标位图（ARGB 像素、宽高、热点）；null 表示隐藏光标 */
        fun onCursor(pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int)

        /** 恢复系统默认光标 */
        fun onCursorDefault()

        /** 远端剪贴板内容 */
        fun onClipboard(text: String)
    }

    @Volatile
    private var inst: Long = 0

    @Volatile
    var screenBitmap: Bitmap? = null
        private set

    @Volatile
    var desktopWidth: Int = 0
        private set

    @Volatile
    var desktopHeight: Int = 0
        private set

    @Volatile
    var connected: Boolean = false
        private set

    private val lock = Any()
    // 每个 native 实例一个闩锁：重连时旧实例的 OnDisconnected 只能唤醒它自己的
    // 清理线程，不能提前放行新实例的释放流程。
    private val closeLatches = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CountDownLatch>()
    @Volatile private var closing = false
    // 回退输入只提示一次，避免 Ctrl 之类的组合键每敲一下就刷一条日志。
    private val fallbackWarned = AtomicBoolean(false)

    override fun start() {
        if (inst != 0L) return
        closing = false
        val args = buildArgs()
        Log.i(TAG, "连接参数: " + args.joinToString(" ") { redactArg(it) })
        val instance = LibFreeRDP.newInstance(context.applicationContext)
        if (instance == 0L) {
            events.onFailed("初始化 FreeRDP 失败")
            return
        }
        closeLatches[instance] = java.util.concurrent.CountDownLatch(1)
        inst = instance
        LibFreeRDP.setListener(instance, this)
        if (!LibFreeRDP.setArguments(instance, args)) {
            // 解析失败时逐条探测，把具体哪个参数不被当前构建接受报出来，
            // 免得以后只剩一句"参数解析失败"没法定位。
            val bad = firstRejectedArg(args)
            events.onFailed(if (bad != null) "不支持的参数：$bad" else "参数解析失败")
            close()
            return
        }
        if (!LibFreeRDP.connect(instance)) {
            events.onFailed("发起连接失败")
            close()
        }
    }

    override fun close() {
        val i = inst
        val wasConnected = connected
        inst = 0
        connected = false
        closing = true
        // 先把当前位图摘下来：重连时 start() 会立刻建新位图，旧实例的清理线程
        // 如果晚点再 recycle 字段里的对象，会把新连接的画面一起回收掉（黑屏）。
        val oldBitmap = synchronized(lock) {
            screenBitmap.also { screenBitmap = null }
        }
        val latch = closeLatches[i]
        if (i == 0L) {
            oldBitmap?.recycle()
            return
        }
        // native 的 freerdp_free 要求工作线程已经退出，否则会 use-after-free。
        // 已连上的会话断开可能要等网络超时，这里给 10 秒；真的等不到就宁可不释放
        // 这个 native 实例（少量内存泄漏），也不能在 native 线程仍运行时 free 掉。
        // 没连上的实例不会启动工作线程，等 1.5 秒足够，且避免反复失败时泄漏。
        Thread({
            runCatching { LibFreeRDP.disconnect(i) }
            val waitMs = if (wasConnected) CLOSE_WAIT_CONNECTED_MS else CLOSE_WAIT_IDLE_MS
            val released = runCatching {
                latch?.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: true
            }.getOrDefault(true)
            runCatching { LibFreeRDP.setListener(i, null) }
            if (released || !wasConnected) {
                runCatching { LibFreeRDP.freeInstance(i) }
            } else {
                Log.w(TAG, "native 线程在 ${waitMs}ms 内未退出，跳过 freeInstance 以避免 use-after-free")
            }
            closeLatches.remove(i)
            runCatching { oldBitmap?.recycle() }
        }, "rdp-close").start()
    }

    // ------------------------------------------------------------- 输入

    /** 鼠标事件；x/y 是远端桌面坐标 */
    fun sendPointer(x: Int, y: Int, flags: Int) {
        val i = inst
        if (i != 0L) LibFreeRDP.sendCursor(i, x, y, flags)
    }

    /** Android KeyEvent keyCode；这里转成 Windows VK 后再交给 native。 */
    fun sendKey(keyCode: Int, down: Boolean): Boolean {
        val i = inst
        if (i == 0L) return false
        val vk = RdpKeyMapper.windowsVk(keyCode)
        if (vk == 0) return false
        return LibFreeRDP.sendKey(i, vk, down)
    }

    /**
     * 输入一个字符。
     *
     * 连接参数里已经带 `/kbd:unicode`，正常情况下直接发 Unicode 事件，中文/日文/
     * emoji 都能输入。保留按虚拟键码回退的路径：如果某个 FreeRDP 构建或配置没有
     * 打开 Unicode 输入，isUnicodeInputSupported() 会返回 false，此时绝不能继续发
     * Unicode —— native 的 android_process_event() 会把「发送失败」当成致命错误，
     * 直接跳出整个事件循环并回调 OnConnectionFailure（表现为键盘一敲就断线）。
     */
    fun typeCharacter(codePoint: Int) {
        val i = inst
        if (i == 0L) return
        when (codePoint) {
            '\n'.code, '\r'.code -> tapKey(i, KeyEvent.KEYCODE_ENTER)
            '\b'.code -> tapKey(i, KeyEvent.KEYCODE_DEL)
            '\t'.code -> tapKey(i, KeyEvent.KEYCODE_TAB)
            else -> if (LibFreeRDP.isUnicodeInputSupported(i)) {
                sendUnicode(instance = i, codePoint = codePoint)
            } else {
                typeCharacterByKeycode(i, codePoint)
            }
        }
    }

    /**
     * RDP 的 Unicode 键盘事件一次只带一个 UTF-16 单元。emoji 等补充平面字符
     * 要拆成代理对依次发送，否则会被 native 截断成错误字符。
     */
    private fun sendUnicode(instance: Long, codePoint: Int) {
        if (Character.isSupplementaryCodePoint(codePoint)) {
            for (unit in Character.toChars(codePoint)) {
                val code = unit.code
                LibFreeRDP.sendUnicodeKey(instance, code, true)
                LibFreeRDP.sendUnicodeKey(instance, code, false)
            }
        } else {
            LibFreeRDP.sendUnicodeKey(instance, codePoint, true)
            LibFreeRDP.sendUnicodeKey(instance, codePoint, false)
        }
    }

    private fun tapKey(instance: Long, keyCode: Int) {
        val vk = RdpKeyMapper.windowsVk(keyCode)
        if (vk == 0) return
        LibFreeRDP.sendKey(instance, vk, true)
        LibFreeRDP.sendKey(instance, vk, false)
    }

    /**
     * Unicode 不可用时的兜底：把字符拆成「Shift + 虚拟键」。
     * KeyCharacterMap 用的是 Android 自带的虚拟键盘布局，ASCII 全覆盖；
     * 中文等字符没有对应键码，只能放弃（此时服务端本来也无法接收 Unicode）。
     */
    private fun typeCharacterByKeycode(instance: Long, codePoint: Int) {
        val events = RdpKeyMapper.eventsForChar(codePoint)
        if (events == null) {
            if (fallbackWarned.compareAndSet(false, true)) {
                Log.w(TAG, "服务端不支持 Unicode 输入，字符 U+%04X 无法用键码发送".format(codePoint))
            }
            return
        }
        for (event in events) {
            val vk = RdpKeyMapper.windowsVk(event.keyCode)
            if (vk != 0) {
                LibFreeRDP.sendKey(instance, vk, event.action == KeyEvent.ACTION_DOWN)
            }
        }
    }

    override fun sendClipboard(text: String) {
        val i = inst
        if (i != 0L) LibFreeRDP.sendClipboard(i, text)
    }

    /** Windows 登录/锁屏界面必需的 Ctrl+Alt+Del。 */
    override fun sendCtrlAltDel() {
        val i = inst
        if (i == 0L) return
        val ctrl = 0xA2 // VK_LCONTROL
        val alt = 0xA4 // VK_LMENU
        val del = 0x2E or 0x100 // VK_DELETE | VK_EXT_KEY
        LibFreeRDP.sendKey(i, ctrl, true)
        LibFreeRDP.sendKey(i, alt, true)
        LibFreeRDP.sendKey(i, del, true)
        LibFreeRDP.sendKey(i, del, false)
        LibFreeRDP.sendKey(i, alt, false)
        LibFreeRDP.sendKey(i, ctrl, false)
    }

    // ---------------------------------------------- RemoteSession 统一接口
    //
    // RDP 的 pointerFlags 是「状态式」的：一次事件里既要带按钮位、又要带 DOWN 位，
    // 而 RFB 的 button-mask 是「当前按住了哪些键」。统一层用后者（更简单），
    // 在这里翻译成前者 —— 按下时补 PTR_FLAGS_DOWN，抬起时只留按钮位。

    /** 当前按住的鼠标键（统一位：1=左 2=中 4=右） */
    private var btnMask = 0

    override val frameWidth: Int get() = desktopWidth

    override val frameHeight: Int get() = desktopHeight

    override fun frameBitmap(): Bitmap? = screenBitmap

    /** RDP 的光标由服务端单独给形状，必须本地叠加 */
    override val needsLocalCursor: Boolean get() = true

    private fun flagsFor(mask: Int): Int {
        var f = LibFreeRDP.PTR_FLAGS_MOVE
        // 注意 RDP 的命名：BUTTON1=左、BUTTON2=右、BUTTON3=中
        if (mask and RemoteButtons.LEFT != 0) f = f or LibFreeRDP.PTR_FLAGS_BUTTON1
        if (mask and RemoteButtons.MIDDLE != 0) f = f or LibFreeRDP.PTR_FLAGS_BUTTON3
        if (mask and RemoteButtons.RIGHT != 0) f = f or LibFreeRDP.PTR_FLAGS_BUTTON2
        return f
    }

    override fun pointerMove(x: Int, y: Int) {
        sendPointer(x, y, flagsFor(btnMask))
    }

    override fun pointerButton(x: Int, y: Int, button: Int, down: Boolean) {
        btnMask = if (down) btnMask or button else btnMask and button.inv()
        var flags = flagsFor(btnMask)
        // 「刚按下」这一条事件要额外带 DOWN；抬起事件只带按钮位（表示该键已松开）
        if (down) flags = flags or LibFreeRDP.PTR_FLAGS_DOWN
        sendPointer(x, y, flags)
    }

    override fun pointerScroll(x: Int, y: Int, ticks: Int, down: Boolean) {
        // FreeRDP 每次指针事件只带一个 8 位滚轮增量，一次滚多格要拆成多条
        repeat(ticks.coerceIn(1, 8)) {
            var flags = LibFreeRDP.PTR_FLAGS_MOVE or LibFreeRDP.PTR_FLAGS_WHEEL or 120
            if (down) flags = flags or LibFreeRDP.PTR_FLAGS_WHEEL_NEGATIVE
            sendPointer(x, y, flags)
        }
    }

    override fun keyDown(keyCode: Int): Boolean = sendKey(keyCode, true)

    override fun keyUp(keyCode: Int): Boolean = sendKey(keyCode, false)

    override fun typeChar(codePoint: Int) = typeCharacter(codePoint)

    // ---------------------------------------------- LibFreeRDP.Listener

    override fun onPreConnect(inst: Long) = Unit

    override fun onConnected(inst: Long) {
        if (inst != this.inst) return
        connected = true
        val w = synchronized(lock) { desktopWidth }
        val h = synchronized(lock) { desktopHeight }
        if (w > 0 && h > 0) events.onReady(w, h)
    }

    override fun onConnectionFailure(inst: Long) {
        if (inst != this.inst) return
        val detail = runCatching { LibFreeRDP.lastError(inst) }.getOrNull()
        events.onFailed(detail?.takeIf { it.isNotBlank() } ?: "连接失败")
    }

    override fun onDisconnecting(inst: Long) = Unit

    override fun onDisconnected(inst: Long) {
        closeLatches[inst]?.countDown()
        // 重连时旧实例的回调可能晚到，不能把新会话的状态改掉。
        if (inst != this.inst) return
        connected = false
        if (!closing) events.onClosed()
    }

    override fun onSettingsChanged(inst: Long, width: Int, height: Int, bpp: Int) {
        if (inst != this.inst) return
        ensureBitmap(width, height)
        events.onReady(width, height)
    }

    override fun onGraphicsResize(inst: Long, width: Int, height: Int, bpp: Int) {
        if (inst != this.inst) return
        ensureBitmap(width, height)
        events.onResized(width, height, bpp)
    }

    override fun onGraphicsUpdate(inst: Long, x: Int, y: Int, width: Int, height: Int) {
        if (inst != this.inst) return
        val bmp = synchronized(lock) { screenBitmap } ?: return
        if (bmp.isRecycled) return
        // native 直接写这块内存，坐标就是远端桌面坐标
        val ok = LibFreeRDP.updateGraphics(inst, bmp, x, y, width, height)
        if (ok) events.onFrameDirty(x, y, width, height)
    }

    override fun onVerifyCertificate(
        inst: Long, host: String, port: Long, commonName: String?, subject: String?,
        issuer: String?, fingerprint: String?, flags: Long,
    ): Int {
        if (inst != this.inst) return 1
        val fp = fingerprint.orEmpty()
        val changed = (flags and LibFreeRDP.VERIFY_CERT_FLAG_CHANGED) != 0L
        val trust = events.onCertificate(host, fp, changed)
        return if (trust) 1 else 0
    }

    override fun onVerifyChangedCertificate(
        inst: Long, host: String, port: Long, commonName: String?, subject: String?,
        issuer: String?, fingerprint: String?, oldSubject: String?, oldIssuer: String?,
        oldFingerprint: String?, flags: Long,
    ): Int = onVerifyCertificate(inst, host, port, commonName, subject, issuer, fingerprint, flags)

    override fun onPointerSet(
        inst: Long, pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int,
    ) {
        if (inst != this.inst) return
        events.onCursor(pixels, width, height, hotX, hotY)
    }

    override fun onPointerSetNull(inst: Long) {
        if (inst == this.inst) events.onCursor(null, 0, 0, 0, 0)
    }

    override fun onPointerSetDefault(inst: Long) {
        if (inst == this.inst) events.onCursorDefault()
    }

    override fun onRemoteClipboardChanged(inst: Long, data: String?) {
        if (inst == this.inst && !data.isNullOrEmpty()) events.onClipboard(data)
    }

    // ------------------------------------------------------------- 内部

    /**
     * 把参数逐条丢给一个临时实例解析，返回第一条被拒绝的参数。
     * 单个参数能解析通过、整条却失败时可能还有组合问题，那时返回 null。
     */
    private fun firstRejectedArg(args: Array<String>): String? {
        for (i in 1 until args.size) {
            val probe = LibFreeRDP.newInstance(context.applicationContext)
            if (probe == 0L) return null
            val ok = try {
                LibFreeRDP.setArguments(probe, arrayOf(args[0], args[i]))
            } catch (t: Throwable) {
                Log.w(TAG, "探测参数失败: " + redactArg(args[i]), t)
                true
            } finally {
                runCatching { LibFreeRDP.freeInstance(probe) }
            }
            if (!ok) {
                // 不能把 /p:密码 原样写进日志或显示在错误框里。
                Log.w(TAG, "该参数不被当前 FreeRDP 构建接受: " + redactArg(args[i]))
                return redactArg(args[i])
            }
        }
        return null
    }

    private fun redactArg(arg: String): String = when {
        arg.startsWith("/p:") -> "/p:***"
        else -> arg
    }

    private fun ensureBitmap(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(lock) {
            desktopWidth = width
            desktopHeight = height
            val cur = screenBitmap
            if (cur == null || cur.width != width || cur.height != height) {
                cur?.recycle()
                screenBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
        }
    }

    private fun buildArgs(): Array<String> {
        val args = ArrayList<String>()
        args.add("SSHBuddy")
        // 软件 GDI：Android 端没有硬件 GDI 后端
        args.add("/gdi:sw")
        // 用户可能把主机填成 "1.2.3.4:3389"（照着别处的连接串抄过来很常见）。
        // FreeRDP 的 /v: 不接受带端口的写法，拼成 /v:1.2.3.4:3389 会直接解析失败 →
        // 表现为「参数解析失败」，用户根本看不出是自己多写了端口。这里先拆开。
        val rawHost = cfg.host.trim()
        val hostOnly: String
        val hostPort: Int?
        val m = HOST_PORT.find(rawHost)
        if (m != null) {
            hostOnly = m.groupValues[1]
            hostPort = m.groupValues[2].toIntOrNull()
        } else {
            hostOnly = rawHost
            hostPort = null
        }
        args.add("/v:" + hostOnly)
        args.add("/port:" + (hostPort ?: cfg.effectiveRdpPort))
        args.add("/u:" + cfg.user.trim())
        if (cfg.password.isNotEmpty()) args.add("/p:" + cfg.password)
        val domain = cfg.domain?.trim().orEmpty()
        if (domain.isNotEmpty()) args.add("/d:" + domain)
        val w = cfg.rdpWidth
        val h = cfg.rdpHeight
        if (w > 0 && h > 0) args.add("/size:${w}x$h")
        args.add("/bpp:32")
        args.add("/client-hostname:SSHBuddy")
        // FreeRDP 3.x 的命令行解析器默认把 Unicode 输入关掉（cmdline.c: "Disable
        // unicode input unless requested"），不开这个开关时 isUnicodeInputSupported()
        // 恒为 false，软键盘发中文会被 native 当成断线。这里显式打开；万一某个构建
        // 不接受该参数，RdpSession 仍会回退到虚拟键码路径。
        args.add("/kbd:unicode")
        args.add("/network:auto")
        args.add("/dynamic-resolution")
        // 网络闪断时先让 FreeRDP 自己重连：native 实例不销毁，重连成功可继续原会话，
        // 比 Java 层重建实例更快，也不会丢掉远端已经打开的应用状态。
        // 超过次数后 native 才会回调 onConnectionFailure，再由 Activity 兜底重建。
        args.add("/auto-reconnect")
        args.add("/auto-reconnect-max-retries:5")
        // 注意：FreeRDP 3.x 的 /sec 只接受 rdp|tls|nla|ext|aad，传 /sec:auto 会被
        // 判为 unknown protocol security 直接导致整条命令行解析失败（3.32 已确认）。
        // 自动协商就是不传该参数，交由 FreeRDP 默认的协商顺序处理。
        when (cfg.rdpSecurity) {
            "nla" -> args.add("/sec:nla")
            "tls" -> args.add("/sec:tls")
            "rdp" -> args.add("/sec:rdp")
            else -> Unit
        }
        // 自签证书环境遵循 TOFU：与官方 aFreeRDP 一致，直接放行。
        // （FreeRDP 在 ignore 模式下不再回调 OnVerifyCertificate，所以要走指纹提示
        //  需要改成 deny 并自己实现信任库，这里先保证可用。）
        args.add("/cert:ignore")
        // AVC444 的 token 只在「构建时编入了 H.264」的 FreeRDP 里存在。宿主机上同版本
        // 3.32.1（未编 H.264）实测：/gfx:AVC444 会让整条命令行解析失败
        // （Command line parsing failed at 'gfx' value 'AVC444' [-1000]），
        // 而 /gfx、/gfx:RFX 都能通过。一旦换用带 H.264 的 native 构建，
        // 无脑加这个参数就会 100% 连不上，所以先探测再决定加不加。
        if (LibFreeRDP.hasH264() && gfxAvc444Accepted()) args.add("/gfx:AVC444")
        return args.toTypedArray()
    }

    /** 当前 native 构建是否接受 /gfx:AVC444；用临时实例探一次并缓存。 */
    private fun gfxAvc444Accepted(): Boolean {
        gfxProbe?.let { return it }
        val verdict = runCatching {
            val probe = LibFreeRDP.newInstance(context.applicationContext)
            if (probe == 0L) return@runCatching false
            try {
                LibFreeRDP.setArguments(probe, arrayOf("probe", "/gfx:AVC444"))
            } finally {
                runCatching { LibFreeRDP.freeInstance(probe) }
            }
        }.getOrDefault(false)
        gfxProbe = verdict
        if (!verdict) Log.w(TAG, "native 不接受 /gfx:AVC444，跳过该参数")
        return verdict
    }

    companion object {
        private const val TAG = "RdpSession"
        private const val CLOSE_WAIT_CONNECTED_MS = 10_000L
        private const val CLOSE_WAIT_IDLE_MS = 1_500L

        /** host 里若带 ":端口"，拆出来单独走 /port: */
        private val HOST_PORT = Regex("^\\[?([^\\[\\]]+?)\\]?:(\\d{1,5})$")

        /** /gfx:AVC444 是否可用：null = 还没探测过 */
        @Volatile
        private var gfxProbe: Boolean? = null
    }
}
