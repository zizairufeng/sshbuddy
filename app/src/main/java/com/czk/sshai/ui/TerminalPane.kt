package com.czk.sshai.ui

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.czk.sshai.SshTerminalClient
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/**
 * Termux TerminalView 的 Compose 外壳。
 *
 * 终端内容由 TerminalView 直接绘制（ANSI/VT、UTF-8、滚动回看、长按选择、双指缩放），
 * Compose 只负责挂载视图、切换页签、同步字号和 Ctrl/Alt 粘滞状态。
 */
@Composable
fun TerminalPane(
    session: TerminalSession?,
    fontSize: Float,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    modifier: Modifier = Modifier,
    onModifiersConsumed: () -> Unit = {},
    onFontSizeDelta: (Int) -> Unit = {},
    keyboardSignal: Int = 0,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val viewRef = remember { mutableStateOf<TerminalView?>(null) }
    val consumed by rememberUpdatedState(onModifiersConsumed)
    val sizeDelta by rememberUpdatedState(onFontSizeDelta)

    val client = remember {
        SshTerminalViewClient(
            viewProvider = { viewRef.value },
            onModifiersConsumed = { consumed() },
            onFontSizeDelta = { delta -> sizeDelta(delta) },
        )
    }

    val textSizePx = remember(fontSize, density) {
        with(density) { fontSize.sp.toPx() }.roundToInt().coerceIn(18, 96)
    }

    AndroidView(
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                // 和 Compose 的深色终端配色一致；实际绘制颜色来自 TerminalTheme。
                setBackgroundColor(0xFF080C11.toInt())
                setTerminalViewClient(client)
                client.lastTextSizePx = textSizePx
                setTextSize(textSizePx)
                isFocusable = true
                isFocusableInTouchMode = true
                isHapticFeedbackEnabled = true
                viewRef.value = this
            }
        },
        update = { view ->
            client.ctrlArmed = ctrlArmed
            client.altArmed = altArmed
            if (client.lastTextSizePx != textSizePx) {
                client.lastTextSizePx = textSizePx
                view.setTextSize(textSizePx)
            }
            if (view.currentSession !== session) {
                (view.currentSession?.client as? SshTerminalClient)?.attachedView = null
                view.attachSession(session)
                view.onScreenUpdated()
            }
            (session?.client as? SshTerminalClient)?.attachedView = view
        },
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF080C11)),
    )

    // 工具栏上的「键盘」按钮：通过递增信号触发。
    LaunchedEffect(keyboardSignal) {
        if (keyboardSignal > 0) client.showKeyboard()
    }

    DisposableEffect(Unit) {
        onDispose {
            val view = viewRef.value
            (view?.currentSession?.client as? SshTerminalClient)?.attachedView = null
            viewRef.value = null
        }
    }
}

private class SshTerminalViewClient(
    private val viewProvider: () -> TerminalView?,
    private val onModifiersConsumed: () -> Unit,
    private val onFontSizeDelta: (Int) -> Unit,
) : TerminalViewClient {

    @Volatile var ctrlArmed = false
    @Volatile var altArmed = false
    @Volatile var lastTextSizePx = 0

    fun showKeyboard() {
        val view = viewProvider() ?: return
        view.requestFocus()
        view.post {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            onFontSizeDelta(if (scale > 1f) 1 else -1)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        // 双击/滚动不会走到这里；单击终端就聚焦并弹键盘。
        showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = ctrlArmed

    override fun readAltKey(): Boolean = altArmed

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // 组合键只吃一个字符：TerminalView 已经在调用前读过修饰键状态。
        if (ctrlArmed || altArmed) {
            ctrlArmed = false
            altArmed = false
            onModifiersConsumed()
        }
        return false
    }

    override fun onEmulatorSet() = Unit

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
