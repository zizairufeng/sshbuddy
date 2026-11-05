package com.czk.sshai.remote

import android.content.Context
import com.czk.sshai.SshConfig
import com.czk.sshai.rdp.RdpSession

/**
 * 一个已建立的远端连接：把协议的 Events 回调归一成 [RemoteConnectionListener]，
 * 把协议会话暴露成 [RemoteSession]。
 *
 * 这样 RemoteActivity 只需要「创建一个连接 + 传给统一界面」，不需要关心
 * 连接管理、重连策略、修饰键处理。
 * （v1.8.0 起 VNC 协议已移除，这里只剩 RDP 一种实现。）
 */
interface RemoteConnection {
    val session: RemoteSession
    fun start()
    fun close()
}

/** 连接事件（两个协议归一后的并集；协议不支持的回调不会触发） */
interface RemoteConnectionListener {
    fun onReady(width: Int, height: Int)

    /** 画面有更新，UI 应重绘 */
    fun onFrame()

    fun onFailed(message: String)

    fun onClosed()

    /** 远端光标形状（仅 RDP）；pixels 为 null 表示隐藏光标 */
    fun onCursor(pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int) {}

    /** 远端切回系统默认光标（仅 RDP） */
    fun onCursorDefault() {}

    /** 远端剪贴板内容（仅 RDP 主动推送） */
    fun onClipboard(text: String) {}
}

object RemoteConnections {

    fun create(ctx: Context, host: SshConfig, l: RemoteConnectionListener): RemoteConnection =
        RdpConnection(ctx, host, l)

    // —————————————————————————————————————————————————————————— RDP

    private class RdpConnection(
        ctx: Context,
        host: SshConfig,
        private val l: RemoteConnectionListener,
    ) : RemoteConnection {

        override val session: RemoteSession = RdpSession(ctx, host, object : RdpSession.Events {
            override fun onReady(width: Int, height: Int) = l.onReady(width, height)
            override fun onFrameDirty(x: Int, y: Int, w: Int, h: Int) = l.onFrame()
            override fun onResized(width: Int, height: Int, bpp: Int) = l.onReady(width, height)
            override fun onFailed(message: String) = l.onFailed(message)
            override fun onClosed() = l.onClosed()

            // 自签证书一律接受：这是个人的局域网/内网穿透场景，没有可验证的
            // 证书链，拦下来只会让连接永远失败。
            override fun onCertificate(host: String, fingerprint: String, changed: Boolean) = true

            override fun onCursor(
                pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int,
            ) = l.onCursor(pixels, width, height, hotX, hotY)

            override fun onCursorDefault() = l.onCursorDefault()
            override fun onClipboard(text: String) = l.onClipboard(text)
        })

        override fun start() = session.start()
        override fun close() = session.close()
    }
}
