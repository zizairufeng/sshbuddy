package com.czk.sshai.rdp

/**
 * RDP 连接参数。
 *
 * [security] 决定请求哪些安全协议：
 *  - [RdpSecurityMode.AUTO]  同时请求 NLA(hybrid) / TLS / 标准 RDP，由服务端挑
 *  - [RdpSecurityMode.NLA]   强制 CredSSP（Windows 10/11 默认要求）
 *  - [RdpSecurityMode.TLS]   只请求 TLS（xrdp / 关掉 NLA 的 Windows）
 *  - [RdpSecurityMode.RDP]   用最老的 RC4 标准安全层（老设备、调试用）
 */
data class RdpConfig(
    val host: String,
    val port: Int = 3389,
    val username: String,
    val password: String = "",
    val domain: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    /** 16 或 24/32。16 位在移动网络下明显省流量，画质足够看桌面。 */
    val colorDepth: Int = 16,
    val security: RdpSecurityMode = RdpSecurityMode.AUTO,
    val clientName: String = "SSHBuddy",
    /** 连接与握手超时 */
    val timeoutMs: Int = 20000,
    /** 把 RDP 流量走这条 SSH 隧道时用；为空表示直连 */
    val enableConsoleSession: Boolean = false,
)

enum class RdpSecurityMode { AUTO, NLA, TLS, RDP }

/** 服务端协商结果，UI 上可以显示「当前用的是哪种加密」。 */
data class RdpSessionInfo(
    val width: Int,
    val height: Int,
    val colorDepth: Int,
    val securityLabel: String,
    val serverProduct: String = "",
)

enum class RdpPointerShape { DEFAULT, HIDDEN, BITMAP }

/**
 * 回调接口：协议层线程里调用，实现方负责切到自己的线程。
 * 刻意做成同步回调，避免协议层依赖协程/Android。
 */
interface RdpListener {
    fun onConnected(info: RdpSessionInfo) {}
    /** 有矩形区域被重绘（像素已写入 [RdpFrameBuffer]） */
    fun onFrameUpdate(x: Int, y: Int, w: Int, h: Int) {}
    fun onPointer(x: Int, y: Int, visible: Boolean, shape: IntArray?, shapeW: Int, shapeH: Int, hotX: Int, hotY: Int) {}
    fun onClipboard(text: String) {}
    fun onLog(message: String) {}
    fun onError(error: Exception) {}
    fun onDisconnected(reason: String) {}
}

/**
 * 共享帧缓冲：协议线程写，UI 线程读。
 * 用 IntArray 存 ARGB_8888，避免每帧创建 Bitmap。
 */
class RdpFrameBuffer(val width: Int, val height: Int) {
    val pixels = IntArray(width * height) { 0xFF101010.toInt() }

    @Volatile
    var version: Long = 0
        private set

    @Volatile
    var lastUpdateMs: Long = 0
        private set

    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        val x0 = x.coerceIn(0, width)
        val y0 = y.coerceIn(0, height)
        val x1 = (x + w).coerceIn(0, width)
        val y1 = (y + h).coerceIn(0, height)
        for (yy in y0 until y1) {
            val base = yy * width
            java.util.Arrays.fill(pixels, base + x0, base + x1, argb)
        }
    }

    /** 把一行像素写进去（[row] 长度必须是 w） */
    fun putRow(x: Int, y: Int, row: IntArray, w: Int) {
        if (y < 0 || y >= height) return
        val base = y * width
        val n = minOf(w, width - x)
        if (n <= 0) return
        System.arraycopy(row, 0, pixels, base + x, n)
    }

    fun copyInto(dst: IntArray) {
        System.arraycopy(pixels, 0, dst, 0, pixels.size)
    }

    fun markDirty() {
        version++
        lastUpdateMs = System.currentTimeMillis()
    }
}
