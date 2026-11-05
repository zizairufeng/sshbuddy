package com.czk.sshai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.czk.sshai.remote.RemoteConnection
import com.czk.sshai.remote.RemoteConnectionListener
import com.czk.sshai.remote.RemoteConnections
import com.czk.sshai.remote.RemoteUiState
import com.czk.sshai.ui.RemoteInputMode
import com.czk.sshai.ui.RemoteScreen
import com.czk.sshai.ui.RemoteSurfaceView
import com.czk.sshai.ui.SshaiTheme

/**
 * **唯一的**远端会话界面（v1.8.0 起只剩 RDP；VNC 已移除）。
 *
 * 协议差异只留在 [RemoteConnections] 里（谁去建会话、Events 有哪些回调），
 * 重连策略、修饰键粘滞、沉浸式、键盘唤起、手势说明都只有这一份。
 */
class RemoteActivity : ComponentActivity() {

    companion object {
        const val EXTRA_HOST_ID = "hostId"

        private const val TAG = "RemoteActivity"
        private const val MAX_RECONNECT = 5
        private const val MAX_INITIAL_RECONNECT = 2
        private val RECONNECT_DELAYS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)

        /** FreeRDP 把"没有具体错误"汇报成 Success.，不能原样展示 */
        private val SUCCESS_ERROR = Regex("(?i)^\\s*(\\[0x0+\\]\\s*)?(success|ok)\\.?\\s*$")

        /** 这类错误重连也没用，直接停下让用户去改配置 */
        private val FATAL_MARKERS = listOf(
            // RDP
            "logon", "password", "credential", "authentication", "access denied",
            "account locked", "account expired", "account disabled", "no or missing",
            "credssp", "certificate expired", "不支持的参数", "参数解析失败",
            "登录失败", "密码错误", "凭据", "账户", "证书",
            "拒绝连接", "refused",
        )

        fun intent(ctx: Context, hostId: String): Intent =
            Intent(ctx, RemoteActivity::class.java).putExtra(EXTRA_HOST_ID, hostId)
    }

    private var conn: RemoteConnection? = null

    // 帧/光标回调来自网络或 native 线程，赋值在主线程，必须可见。
    @Volatile
    private var surface: RemoteSurfaceView? = null

    private var hostLabel by mutableStateOf("")
    private var connState by mutableStateOf<RemoteUiState>(RemoteUiState.Connecting)
    private var toolbarVisible by mutableStateOf(true)
    private var numpadVisible by mutableStateOf(false)
    private var inputMode by mutableStateOf(RemoteInputMode.POINTER)
    private var deckVisible by mutableStateOf(true)
    private var deckRatio by mutableStateOf(0f)
    private var speedIdx by mutableStateOf(1)

    private val speedSteps = floatArrayOf(0.65f, 1.0f, 1.6f)
    private val speedNames = arrayOf("慢", "标准", "快")
    private val speedLabel: String get() = speedNames[speedIdx.coerceIn(0, 2)]

    /**
     * 当前按住不放的修饰键（Android keycode）。
     * 做成「点一下锁定、再点一下松开」：手机上没法一边按住 Ctrl 一边点 C，
     * 只能把修饰键变成粘滞键。
     */
    private var modifiers by mutableStateOf<Set<Int>>(emptySet())

    // 断线自愈：按 2/4/8/15/30 秒重建连接，最多 5 次（首次连接最多 2 次）
    private val ui = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var reconnectTask: Runnable? = null
    private var manualExit = false
    private var connectedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        val hostId = intent.getStringExtra(EXTRA_HOST_ID)
        val host = Prefs.loadHosts(this).find { it.id == hostId }
        if (host == null) {
            finish()
            return
        }
        hostLabel = host.label
        // 这些偏好按「操作习惯」存，不分协议：同一个人对触控板灵敏度和控制台高度的
        // 喜好跟连的是 Windows 还是 Linux 无关。
        deckVisible = Prefs.loadRdpDeckVisible(this)
        deckRatio = Prefs.loadRdpDeckRatio(this)
        speedIdx = Prefs.loadRdpPointerSpeedIdx(this).coerceIn(0, 2)
        inputMode = if (Prefs.loadRdpInputMode(this) == "touch") {
            RemoteInputMode.TOUCH
        } else {
            RemoteInputMode.POINTER
        }

        val c = RemoteConnections.create(this, host, object : RemoteConnectionListener {
            override fun onReady(width: Int, height: Int) = runOnUiThread {
                if (manualExit) return@runOnUiThread
                connectedOnce = true
                reconnectAttempt = 0
                cancelReconnect()
                connState = RemoteUiState.Connected(width, height)
                surface?.onResize()
            }

            override fun onFrame() {
                surface?.onFrame()
            }

            override fun onFailed(message: String) = runOnUiThread {
                if (manualExit) return@runOnUiThread
                val reason = friendlyError(message)
                Log.w(TAG, "连接失败: $reason")
                scheduleReconnect(reason)
            }

            override fun onClosed() = runOnUiThread {
                if (manualExit) return@runOnUiThread
                if (connState is RemoteUiState.Connected || connState is RemoteUiState.Connecting) {
                    Log.i(TAG, "会话被远端结束")
                    scheduleReconnect("远端已结束会话")
                }
            }

            override fun onCursor(
                pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int,
            ) {
                if (pixels == null || width <= 0 || height <= 0) {
                    surface?.hideRemoteCursor()
                } else {
                    surface?.setRemoteCursor(pixels, width, height, hotX, hotY)
                }
            }

            override fun onCursorDefault() {
                // 默认箭头也要画：服务端只是说"用系统光标"，并不代表隐藏光标。
                surface?.setDefaultCursor()
            }

            override fun onClipboard(text: String) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                runOnUiThread {
                    runCatching { cm?.setPrimaryClip(ClipData.newPlainText("remote", text)) }
                }
            }
        })
        conn = c

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 小键盘是临时浮层，返回键先收起它，避免误退出整个会话。
                if (numpadVisible) {
                    numpadVisible = false
                    return
                }
                closeAndFinish()
            }
        })

        setContent {
            SshaiTheme {
                RemoteScreen(
                    state = connState,
                    title = hostLabel,
                    toolbarVisible = toolbarVisible,
                    inputMode = inputMode,
                    numpadVisible = numpadVisible,
                    deckVisible = deckVisible,
                    deckRatio = deckRatio,
                    onDeckRatioChange = { r ->
                        deckRatio = r
                        Prefs.saveRdpDeckRatio(this, r)
                    },
                    pointerSpeedLabel = speedLabel,
                    onCycleSpeed = { cycleSpeed() },
                    onShowApps = { showAppLauncher() },
                    modifiers = modifiers,
                    onSurfaceReady = { v ->
                        surface = v
                        // 渲染线程和触摸事件都通过这个引用取位图/发指针事件；
                        // 漏赋值会表现为「已连接但永远黑屏、点按无反应」。
                        v.session = c.session
                        v.inputMode = inputMode
                        v.pointerSpeed = speedSteps[speedIdx.coerceIn(0, 2)]
                        c.start()
                    },
                    onToggleToolbar = { toolbarVisible = !toolbarVisible },
                    onToggleInputMode = { toggleInputMode() },
                    onKeyboard = { toggleKeyboard() },
                    onToggleNumpad = { toggleNumpad() },
                    onToggleDeck = { toggleDeck() },
                    onNumpadKey = { tapKey(it) },
                    onToggleModifier = { toggleModifier(it) },
                    onKeyTap = { tapKey(it) },
                    onMovePointer = { dx, dy -> surface?.movePointerBy(dx, dy) },
                    onLeftClick = { surface?.clickLeft() },
                    onRightClick = { surface?.clickRight() },
                    onMiddleClick = { surface?.clickMiddle() },
                    onScroll = { ticks, down -> surface?.scrollBy(ticks, down) },
                    onHoldLeft = { surface?.holdLeftButton() },
                    onReleaseLeft = { surface?.releaseLeftButton() },
                    onCtrlAltDel = { conn?.session?.sendCtrlAltDel() },
                    onPasteClipboard = { pasteClipboardToRemote() },
                    onResetZoom = { surface?.resetZoom() },
                    onDisconnect = { closeAndFinish() },
                    onReconnect = { manualReconnect() },
                )
            }
        }
    }

    /**
     * 指针模式 = 手机当触控板（适合点小按钮）；
     * 触控模式 = 手指直接点远端桌面（适合浏览、拖拽）。
     * 和向日葵一样随时切换，不用断开重连。
     */
    private fun toggleInputMode() {
        inputMode = if (inputMode == RemoteInputMode.POINTER) {
            RemoteInputMode.TOUCH
        } else {
            RemoteInputMode.POINTER
        }
        surface?.inputMode = inputMode
        Prefs.saveRdpInputMode(
            this,
            if (inputMode == RemoteInputMode.TOUCH) "touch" else "pointer",
        )
        val hint = if (inputMode == RemoteInputMode.POINTER) {
            "指针模式：滑动移光标，轻点左键，双指轻点右键，长按后拖动"
        } else {
            "触控模式：点按左键，拖动=拖拽，长按右键，双指轻点右键"
        }
        Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
    }

    private fun toggleKeyboard() {
        // 软键盘和小键盘互斥，避免两套键盘同时盖住远端画面。
        numpadVisible = false
        val v = surface ?: return
        // SurfaceView 默认不参与 Compose 的焦点管理，工具栏按钮点击后焦点还在按钮上；
        // 不先 requestFocus()，输入法会把文本送回按钮所在的窗口而不是远端视图。
        v.requestFocus()
        v.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            // SHOW_IMPLICIT 与官方 aFreeRDP 一致；SHOW_FORCED 在部分 ROM 上会把
            // 输入法顶到全屏/抽取模式，反而盖住画面。
            imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * 数字小键盘。打开时收起系统输入法，按键直接走远端键盘通道发送，
     * 不需要焦点停在 SurfaceView 上。
     */
    private fun toggleNumpad() {
        numpadVisible = !numpadVisible
        if (!numpadVisible) return
        val v = surface ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    /**
     * 展开/收起底部控制台。
     * 布局变化会让 SurfaceView 重新测量，surfaceChanged 里会重新算适应比例，
     * 所以这里不用手动 onResize()。
     */
    private fun toggleDeck() {
        deckVisible = !deckVisible
        Prefs.saveRdpDeckVisible(this, deckVisible)
    }

    /** 触摸板速度三档循环：慢 → 标准 → 快 */
    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speedSteps.size
        surface?.pointerSpeed = speedSteps[speedIdx]
        Prefs.saveRdpPointerSpeedIdx(this, speedIdx)
    }

    /**
     * 沉浸式全屏：把状态栏隐藏掉。
     * 远端桌面最上面就是窗口标题栏，状态栏压在上面等于白丢一行画面；
     * 需要看时间/通知时从顶部边缘下滑即可临时唤出（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）。
     */
    private fun enterImmersive() {
        runCatching {
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 输入法收起、切回前台时有些 ROM 会把状态栏还回来，重新隐藏一次
        if (hasFocus) enterImmersive()
    }

    /**
     * 修饰键粘滞：点一下按住（高亮），再点一下松开。
     * 手机上没法同时按住 Ctrl 和 C，粘滞键是唯一可行的替代。
     */
    private fun toggleModifier(keyCode: Int) {
        val s = conn?.session
        val set = modifiers.toMutableSet()
        if (set.remove(keyCode)) {
            s?.keyUp(keyCode)
        } else {
            // 只有成功发到远端才算「按住」，否则界面会显示高亮但远端没收到
            if (s == null || s.keyDown(keyCode)) set.add(keyCode)
        }
        modifiers = set
    }

    /**
     * 打开远端应用列表。
     *
     * Linux（GNOME）：发一次完整的 Super 按下+抬起 → 活动视图 + 应用搜索框。
     * Windows：发 Win 键 → 开始菜单。
     *
     * 注意不能复用控制台上的「Win」粘滞键：那个只发按下（等下一个键凑组合），
     * 而 GNOME 是等 Super **抬起**才弹出活动视图，单点它等于没按。
     */
    private fun showAppLauncher() {
        val s = conn?.session ?: return
        // 还锁着修饰键的话，Super 会变成组合键（Super+Ctrl 之类），先全部松开
        releaseModifiers()
        surface?.releaseLeftButton()
        if (!s.keyDown(KeyEvent.KEYCODE_META_LEFT)) {
            Toast.makeText(this, "当前系统没有映射 Win/Super 键", Toast.LENGTH_SHORT).show()
            return
        }
        s.keyUp(KeyEvent.KEYCODE_META_LEFT)
    }

    /** 普通键：按下 + 抬起。已锁定的修饰键保持按下，天然组合出 Ctrl+C / Alt+Tab。 */
    private fun tapKey(keyCode: Int) {
        val s = conn?.session ?: return
        // 按下成功才补发抬起，避免把未映射的键留在远端按下状态。
        if (s.keyDown(keyCode)) s.keyUp(keyCode)
    }

    /** 会话结束或离开页面前把所有修饰键松开，避免远端一直按着 Ctrl */
    private fun releaseModifiers() {
        val held = modifiers
        if (held.isEmpty()) return
        modifiers = emptySet()
        val s = conn?.session ?: return
        held.forEach { s.keyUp(it) }
    }

    /**
     * 重建连接前清理本地输入状态。
     * 新会话里没有任何键是按下的，但本地还记着「Ctrl 高亮 / 左键按住」，
     * 不清掉会导致重连后光标一直拖着、按一下键就变成组合键。
     */
    private fun resetInputState() {
        modifiers = emptySet()
        surface?.releaseLeftButton()
    }

    override fun onPause() {
        // 切到后台时松开所有按住的键：回来时远端不会处于「Ctrl 一直被按住」的状态
        releaseModifiers()
        surface?.releaseLeftButton()
        super.onPause()
    }

    /**
     * 把本机剪贴板内容送到远端。
     * 远端复制到本机由各协议的回调自动处理；这个方向需要用户主动触发，
     * 避免每次复制密码之类的内容都无感同步到远端。
     */
    private fun pasteClipboardToRemote() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show()
            return
        }
        conn?.session?.sendClipboard(text)
        Toast.makeText(this, "已发送剪贴板（${text.length} 个字符）", Toast.LENGTH_SHORT).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = conn?.session
        if (s != null && event.keyCode != KeyEvent.KEYCODE_BACK) {
            // 过滤掉系统键（音量、电源等），其余都送给远端
            if (isSystemKey(event.keyCode)) return super.dispatchKeyEvent(event)
            // 没有映射的键不要吞掉，交回系统处理（例如外接键盘上的浏览器键）
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (s.keyDown(event.keyCode)) return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (s.keyUp(event.keyCode)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isSystemKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_HEADSETHOOK,
        -> true
        else -> false
    }

    /**
     * 断线后的兜底重连。
     *
     * 按 2/4/8/15/30 秒重建连接，最多 5 次（首次连接最多 2 次）。
     * 密码/账户类错误直接停下，避免用错误密码反复撞服务器。
     */
    private fun scheduleReconnect(reason: String) {
        if (manualExit || isFinishing || isDestroyed) return
        // 同一次断线可能同时收到 failure/closed，只保留一个待执行的重连。
        if (reconnectTask != null) return
        if (isFatalError(reason)) {
            connState = RemoteUiState.Failed(reason)
            return
        }
        val max = if (connectedOnce) MAX_RECONNECT else MAX_INITIAL_RECONNECT
        if (reconnectAttempt >= max) {
            connState = RemoteUiState.Failed("自动重连 $reconnectAttempt 次仍未成功。\n$reason")
            return
        }
        val c = conn ?: return
        reconnectAttempt++
        val delayMs = RECONNECT_DELAYS[(reconnectAttempt - 1).coerceAtMost(RECONNECT_DELAYS.lastIndex)]
        cancelReconnect()
        connState = RemoteUiState.Reconnecting(
            attempt = reconnectAttempt,
            maxAttempts = max,
            delaySeconds = (delayMs / 1000).toInt(),
            reason = reason,
        )
        val task = Runnable {
            reconnectTask = null
            if (manualExit || isFinishing || isDestroyed) return@Runnable
            Log.i(TAG, "自动重连第 $reconnectAttempt 次")
            connState = RemoteUiState.Connecting
            resetInputState()
            c.close()
            c.start()
        }
        reconnectTask = task
        ui.postDelayed(task, delayMs)
    }

    private fun cancelReconnect() {
        reconnectTask?.let { ui.removeCallbacks(it) }
        reconnectTask = null
    }

    private fun manualReconnect() {
        if (manualExit) return
        cancelReconnect()
        reconnectAttempt = 0
        val c = conn ?: return
        connState = RemoteUiState.Connecting
        resetInputState()
        c.close()
        c.start()
    }

    private fun closeAndFinish() {
        manualExit = true
        cancelReconnect()
        ui.removeCallbacksAndMessages(null)
        finish()
    }

    /** FreeRDP 断网时 lastError 常常是 "Success."，直接展示会让人误以为连接成功。 */
    private fun friendlyError(message: String?): String {
        val text = message?.trim().orEmpty()
        if (text.isEmpty() || SUCCESS_ERROR.matches(text)) {
            return "连接已断开，可能是网络中断或远端会话结束"
        }
        return text.lineSequence().firstOrNull()?.take(300).orEmpty()
    }

    private fun isFatalError(message: String): Boolean {
        val m = message.lowercase()
        return FATAL_MARKERS.any { m.contains(it) }
    }

    override fun onDestroy() {
        manualExit = true
        cancelReconnect()
        ui.removeCallbacksAndMessages(null)
        conn?.close()
        conn = null
        super.onDestroy()
    }
}
