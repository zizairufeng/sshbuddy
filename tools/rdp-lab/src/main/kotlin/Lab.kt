import com.czk.sshai.rdp.RdpConfig
import com.czk.sshai.rdp.RdpConnection
import com.czk.sshai.rdp.RdpFrameBuffer
import com.czk.sshai.rdp.RdpListener
import com.czk.sshai.rdp.RdpSecurityMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 端到端联调：连本机 xrdp，跑一次完整握手 + 收画面，存 PNG。
 * 用法： gradle run --args="host port user pass [seconds]"
 */
fun main(args: Array<String>) {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 3390
    val user = args.getOrNull(2) ?: "rdptest"
    // 口令必须显式传入，不设默认值：本仓库公开，写死的默认口令等于把测试账号送人
    val pass = args.getOrNull(3) ?: System.getenv("RDP_LAB_PASS")
        ?: run { System.err.println("缺少口令：args[3] 或环境变量 RDP_LAB_PASS"); return }
    val seconds = args.getOrNull(4)?.toIntOrNull() ?: 6
    val modeArg = args.getOrNull(5) ?: "AUTO"
    val mode = when (modeArg.uppercase()) {
        "RDP" -> RdpSecurityMode.RDP
        "TLS" -> RdpSecurityMode.TLS
        "NLA" -> RdpSecurityMode.NLA
        else -> RdpSecurityMode.AUTO
    }

    val cfg = RdpConfig(
        host = host,
        port = port,
        username = user,
        password = pass,
        width = 1024,
        height = 768,
        colorDepth = 16,
        security = mode,
        timeoutMs = 15000,
    )

    var updates = 0
    var lastArea = 0
    val listener = object : RdpListener {
        override fun onLog(message: String) = println("[LOG] $message")
        override fun onConnected(info: com.czk.sshai.rdp.RdpSessionInfo) =
            println("[OK] 已连接 ${info.width}x${info.height} ${info.securityLabel}")

        override fun onFrameUpdate(x: Int, y: Int, w: Int, h: Int) {
            updates++
            lastArea = w * h
            if (updates <= 5 || updates % 20 == 0) println("[FRAME] #$updates rect=($x,$y ${w}x$h)")
        }

        override fun onPointer(x: Int, y: Int, visible: Boolean, shape: IntArray?, shapeW: Int, shapeH: Int, hotX: Int, hotY: Int) {
            println("[PTR] ($x,$y) visible=$visible")
        }

        override fun onError(error: Exception) {
            println("[ERR] ${error.javaClass.simpleName}: ${error.message}")
            error.printStackTrace()
        }

        override fun onDisconnected(reason: String) = println("[BYE] $reason")
    }

    val conn = RdpConnection(cfg, listener)

    val saver = Thread {
        Thread.sleep(seconds * 1000L)
        println("[DUMP] 收到 $updates 次更新，最后区域 $lastArea 像素")
        try {
            // 输出到当前工作目录下的 fixtures/，不写死绝对路径
            if (conn.hasFrameBuffer) savePng(conn.frameBuffer, "fixtures/lab-frame.png")
        } catch (e: Exception) {
            println("[DUMP] 存图失败：${e.message}")
        }
        conn.disconnect()
    }
    saver.isDaemon = true
    saver.start()

    conn.connect()
    println("[END] 连接结束")
}

fun savePng(fb: RdpFrameBuffer, path: String) {
    val img = BufferedImage(fb.width, fb.height, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, fb.width, fb.height, fb.pixels, 0, fb.width)
    ImageIO.write(img, "png", File(path))
    println("[DUMP] 已保存 $path")
}
