package com.czk.sshai

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Properties

/**
 * 一条交互式 SSH shell。
 *
 * 和旧实现最大的区别：这里不再把字节流拼成字符串、也不再自己清洗 ANSI。
 * 原始字节直接交给 Termux 的终端模拟器（VT/UTF-8/宽字符/滚动回看都由它处理），
 * 这样 vim / htop / tmux / 进度条才能正确显示。
 *
 * 写方向使用一个有界队列 + 单独的写协程：UI 线程只负责入队，
 * 慢网络不会卡住输入，也不会因为并发写 OutputStream 把数据写乱。
 */
class SshSession(private val knownHosts: KnownHostsStore) {

    enum class EndReason { MANUAL, EOF, ERROR }

    data class End(val reason: EndReason, val message: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inputQueue = Channel<ByteArray>(capacity = 256)
    private val endDeferred = CompletableDeferred<End>()

    @Volatile private var jschSession: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closing = false
    // 期望的 PTY 尺寸。TerminalView 可能在 channel 还没连上时就回调尺寸变化，
    // 那时候直接调用 setPtySize 会失败，所以先记下来，连上后再补发。
    @Volatile private var ptyCols = 0
    @Volatile private var ptyRows = 0
    @Volatile private var readerJob: Job? = null
    @Volatile private var writerJob: Job? = null

    /** 会话结束（正常 EOF、连接错误或主动断开）时完成一次，重连循环据此决策。 */
    val ended: CompletableDeferred<End> get() = endDeferred

    suspend fun awaitEnd(): End = endDeferred.await()

    val isOpen: Boolean
        get() = !closing && jschSession?.isConnected == true && shell?.isConnected == true

    /**
     * 建立连接并启动收发。失败时抛出异常，由 ViewModel 展示并决定是否重连。
     * [onBytes] 在 IO 线程回调，必须自行切回主线程再喂给终端。
     */
    suspend fun open(
        cfg: SshConfig,
        cols: Int,
        rows: Int,
        onBytes: (ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            if (closing) throw CancellationException("连接已取消")

            val jsch = JSch()
            // TOFU：首次记录指纹，之后指纹不一致直接拒绝连接（中间人无法静默接管）。
            jsch.hostKeyRepository = knownHosts
            if (cfg.useKey) {
                val passphrase = cfg.passphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
                jsch.addIdentity(
                    "sshai-key-${cfg.id}",
                    cfg.keyPem.toByteArray(Charsets.UTF_8),
                    null,
                    passphrase,
                )
            }

            val s = jsch.getSession(cfg.user.trim(), cfg.host.trim(), cfg.port)
            if (!cfg.useKey) s.setPassword(cfg.password)
            s.userInfo = object : UserInfo {
                override fun getPassphrase(): String? = cfg.passphrase.takeIf { it.isNotBlank() }
                override fun getPassword(): String? = if (cfg.useKey) null else cfg.password
                override fun promptPassword(message: String?): Boolean = false
                override fun promptPassphrase(message: String?): Boolean = false
                override fun promptYesNo(message: String?): Boolean = true
                override fun showMessage(message: String?) = Unit
            }

            val p = Properties()
            p["StrictHostKeyChecking"] = "yes"
            p["PreferredAuthentications"] = "publickey,keyboard-interactive,password"
            p["ServerAliveInterval"] = "15000"
            p["ServerAliveCountMax"] = "3"
            s.setConfig(p)
            // 无线网 / 4G 切换时靠心跳及时发现死连接（与 Termius 的 keep-alive 同理）。
            s.serverAliveInterval = 15000
            s.serverAliveCountMax = 3

            knownHosts.clearMismatch()
            // 先发布 session 引用再握手：用户在连接过程中按取消/断开时，
            // close() 才能真正把还在 connect(20s) 里的 socket 拆掉，而不是泄漏一条半开连接。
            jschSession = s
            try {
                s.connect(20_000)
            } catch (e: JSchException) {
                val mismatch = knownHosts.lastMismatch
                if (mismatch != null) throw HostKeyMismatchException(mismatch, e)
                // 双保险：即使线程本地记录被异常路径清掉，JSch 自己的报错也能识别。
                if (e.message?.contains("HostKey has been changed", ignoreCase = true) == true) {
                    throw HostKeyMismatchException(
                        "主机 ${cfg.host.trim()} 的 SSH 指纹已变化，与服务端当前密钥不一致。",
                        e,
                    )
                }
                throw e
            }

            if (closing) throw CancellationException("连接已取消")

            val ch = s.openChannel("shell") as ChannelShell
            shell = ch
            // 真实窗口尺寸交给远端，vim/htop 第一次启动就按手机屏幕排版。
            ptyCols = cols.coerceAtLeast(20)
            ptyRows = rows.coerceAtLeast(5)
            ch.setPtyType("xterm-256color", ptyCols, ptyRows, 0, 0)
            val input = ch.inputStream
            val out = ch.outputStream
            ch.connect(15_000)
            if (closing) throw CancellationException("连接已取消")

            // connect 期间尺寸可能又变过（软键盘弹出/旋转），用最后记录的值补发一次。
            runCatching { ch.setPtySize(ptyCols, ptyRows, 0, 0) }

            output = out
            startPumps(input, out, onBytes)
        } catch (e: Throwable) {
            if (e is CancellationException) closing = true
            disposeTransport()
            throw e
        }
    }

    /** 键盘输入。返回 false 表示会话已经不可写（UI 可以提示用户重连）。 */
    fun write(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || closing || output == null) return false
        // 大段粘贴拆成小块入队：一次 trySend 失败就等于整段输入静默丢失。
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + WRITE_CHUNK_BYTES, bytes.size)
            if (!inputQueue.trySend(bytes.copyOfRange(offset, end)).isSuccess) return false
            offset = end
        }
        return true
    }

    fun write(text: String): Boolean = write(text.toByteArray(Charsets.UTF_8))

    /** 窗口尺寸变化：让远端重新排版，不再把 120x40 写死。 */
    fun resize(cols: Int, rows: Int) {
        // 无论 channel 是否已连上都要记录，open() 会在 connect 后统一补发。
        ptyCols = cols.coerceAtLeast(20)
        ptyRows = rows.coerceAtLeast(5)
        val ch = shell ?: return
        if (!ch.isConnected) return
        val c = ptyCols
        val r = ptyRows
        // 发送 window-change 走网络；放到 IO 线程，避免布局时卡主线程。
        scope.launch { runCatching { ch.setPtySize(c, r, 0, 0) } }
    }

    /** 主动断开，并把结束原因标记为 MANUAL（避免重连循环把用户操作当成掉线）。 */
    fun close() {
        closing = true
        endDeferred.complete(End(EndReason.MANUAL))
        inputQueue.close()
        readerJob?.cancel()
        writerJob?.cancel()
        disposeTransport()
        scope.cancel()
    }

    // ───────────────────────── 内部 ─────────────────────────

    private fun startPumps(input: java.io.InputStream, out: OutputStream, onBytes: (ByteArray) -> Unit) {
        readerJob = scope.launch {
            val buf = ByteArray(16 * 1024)
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) {
                        finish(EndReason.EOF)
                        return@launch
                    }
                    if (n > 0) onBytes(buf.copyOf(n))
                }
            } catch (e: Throwable) {
                if (!closing) finish(EndReason.ERROR, e.message ?: "读取失败")
            }
        }
        writerJob = scope.launch {
            try {
                for (chunk in inputQueue) {
                    out.write(chunk)
                    out.flush()
                }
            } catch (e: Throwable) {
                if (!closing) finish(EndReason.ERROR, e.message ?: "发送失败")
            }
        }
    }

    private fun finish(reason: EndReason, message: String? = null) {
        if (!endDeferred.complete(End(reason, message))) return
        inputQueue.close()
        // 让阻塞中的 read 立即返回；真正的资源释放由 ViewModel 调用 close()。
        runCatching { shell?.disconnect() }
        runCatching { jschSession?.disconnect() }
    }

    private fun disposeTransport() {
        runCatching { shell?.disconnect() }
        runCatching { jschSession?.disconnect() }
        shell = null
        jschSession = null
        output = null
    }

    class HostKeyMismatchException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val WRITE_CHUNK_BYTES = 32 * 1024
    }
}
