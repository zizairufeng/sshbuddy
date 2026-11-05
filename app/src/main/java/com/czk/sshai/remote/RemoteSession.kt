package com.czk.sshai.remote

import android.graphics.Bitmap

/**
 * 远端会话的统一抽象。
 *
 * 为什么要有这层：协议事件（FreeRDP JNI 的 pointerFlags、Windows VK）和**操作**
 * 语义（「移动光标 / 点键 / 滚轮 / 敲键 / 打字符」）不是一回事。UI 只跟语义打交道，
 * 协议细节全部收在 Session 实现里。
 *
 * 操作逻辑只有一份（RemoteSurfaceView + RemoteControlDeck + RemoteScreen）。
 * v1.8.0 起 VNC 实现已移除，这里只剩 RDP 一种实现，但抽象保留 —— 以后加协议
 * 不用再动 UI。
 *
 * 按钮位编码与 RFB 的 button-mask 对齐（1=左 2=中 4=右），RDP 实现内部再映射成
 * PTR_FLAGS_BUTTON1/2/3 —— 因为 RDP 的 BUTTON2 是右键、BUTTON3 才是中键，很容易搞反。
 */
interface RemoteSession {

    /** 远端桌面宽度（像素），0 表示还没拿到 */
    val frameWidth: Int

    /** 远端桌面高度（像素），0 表示还没拿到 */
    val frameHeight: Int

    /**
     * 当前可绘制的帧位图。
     *
     * RDP 直接返回 native 侧写入的位图（零拷贝）。
     * 返回 null 表示还没有画面可画。调用方不得回收它。
     */
    fun frameBitmap(): Bitmap?

    fun start()
    fun close()

    /** 鼠标移动到远端坐标 [x]/[y]，不改变按键状态 */
    fun pointerMove(x: Int, y: Int)

    /**
     * 按下/抬起某个鼠标键。
     * [button] 用 [RemoteButtons] 的常量；[down] = true 按下，false 抬起。
     */
    fun pointerButton(x: Int, y: Int, button: Int, down: Boolean)

    /** 滚轮：[ticks] 格，[down] = true 向下滚 */
    fun pointerScroll(x: Int, y: Int, ticks: Int, down: Boolean)

    /** 按下 Android KeyEvent.keyCode 对应的键；返回 false 表示该键无映射、未发送 */
    fun keyDown(keyCode: Int): Boolean

    /** 抬起键 */
    fun keyUp(keyCode: Int): Boolean

    /** 输入一个 Unicode 字符（中文/emoji 走这条） */
    fun typeChar(codePoint: Int)

    /** Ctrl+Alt+Del（Windows 登录/锁屏界面用） */
    fun sendCtrlAltDel()

    /** 把文本送到远端剪贴板 */
    fun sendClipboard(text: String)

    /**
     * 是否需要本地绘制远端光标。
     *
     * RDP 的光标由服务端单独下发形状，必须按热点叠加到画面上；否则每次移动光标
     * 都要等一整个网络往返才能看到光标动，手感非常黏（指针卡顿的根因）。
     */
    val needsLocalCursor: Boolean

    /**
     * 当前远端光标形状（含热点）。返回 null 表示还没有形状信息，
     * 此时界面可以退化成画一个默认箭头。
     */
    fun remoteCursor(): RemoteCursor? = null
}

/** 远端光标位图 + 热点（热点是光标图像的哪个像素对齐到指针坐标） */
data class RemoteCursor(val bitmap: Bitmap, val hotX: Int, val hotY: Int)

/** 鼠标键位掩码，与 RFB button-mask 一致 */
object RemoteButtons {
    const val LEFT = 1
    const val MIDDLE = 2
    const val RIGHT = 4
}

/** 统一连接状态（两个协议共用） */
sealed interface RemoteUiState {
    data object Connecting : RemoteUiState
    data class Connected(val width: Int, val height: Int) : RemoteUiState

    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int,
        val delaySeconds: Int,
        val reason: String?,
    ) : RemoteUiState

    data class Failed(val message: String) : RemoteUiState
    data object Closed : RemoteUiState
}
