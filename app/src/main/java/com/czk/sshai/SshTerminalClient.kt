package com.czk.sshai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

/**
 * 把 Termux 终端回调接回 Android 视图/剪贴板。
 *
 * TerminalSession 只负责屏幕状态；这里负责让已挂载的 TerminalView 重绘，
 * 以及把 OSC 52 复制、长按粘贴等操作落到系统剪贴板。
 */
class SshTerminalClient(private val appContext: Context) : TerminalSessionClient {

    @Volatile
    var attachedView: TerminalView? = null

    @Volatile
    var lastTitle: String? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        attachedView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        lastTitle = changedSession.emulator.title
    }

    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        if (text.isEmpty()) return
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { cm.setPrimaryClip(ClipData.newPlainText("terminal", text)) }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(appContext)?.toString()
        if (text.isNullOrEmpty()) return
        // 必须走 emulator.paste：它会去掉 ESC/C1、把换行统一成 \r，
        // 并在远端开启 bracketed paste（DECSET 2004）时加 200~/201~ 包裹。
        // 直接 session.write 会让多行粘贴在 bash/vim 里被当成逐条命令执行。
        session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        attachedView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "terminal error", e)
    }
}
