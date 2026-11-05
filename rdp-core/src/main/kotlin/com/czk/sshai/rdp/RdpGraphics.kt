package com.czk.sshai.rdp

/**
 * 客户端能力声明 + 位图解码 + 输入事件构造。
 *
 * 这里刻意「声明得很穷」：
 *  - 不支持位图压缩（bitmapCompressionFlag = 0）→ 服务端发明文像素，解码简单可靠
 *  - 不支持 drawing orders / 位图缓存 / fastpath → 服务端只发 bitmap update
 * 代价是流量偏大，换来的是能在任何服务端上稳定出画。后续要做优化再从
 * [bitmapCompressionFlag] 和 RLE 解码入手，不影响其余代码。
 */
object RdpGraphics {

    private const val CAP_GENERAL = 1
    private const val CAP_BITMAP = 2
    private const val CAP_ORDER = 3
    private const val CAP_POINTER = 8
    private const val CAP_SHARE = 9
    private const val CAP_COLCACHE = 10
    private const val CAP_INPUT = 13
    private const val CAP_FONT = 14
    private const val CAP_BRUSHCACHE = 15
    private const val CAP_BMPCACHE2 = 19

    private const val PDU_CONFIRM_ACTIVE = 0x12       // TS type 2 | version 位 0x10
    private const val PDU_DATA = 0x17                 // TS type 7 | version 位 0x10

    private const val PDU2_UPDATE = 0x02
    private const val PDU2_INPUT = 0x1C
    private const val PDU2_SYNCHRONISE = 0x1F
    private const val PDU2_CONTROL = 0x14
    private const val PDU2_FONT2 = 0x27

    private const val UPDATE_TYPE_BITMAP = 0x0001
    private const val UPDATE_TYPE_ORDERS = 0x0000

    // 输入设备类型
    const val INPUT_SYNCHRONIZE = 0
    const val INPUT_SCANCODE = 4
    const val INPUT_MOUSE = 0x8001
    const val INPUT_UNICODE = 5

    private const val SOURCE_NAME = "SSHBUDDY"        // 恰好 8 字节

    /** 客户端 capability sets 拼装（不含 confirm active 头） */
    fun buildCaps(cfg: RdpConfig): ByteArray {
        val w = ByteWriter(256)
        val bpp = if (cfg.colorDepth <= 16) 16 else 24

        // ---- General ----
        w.u16le(CAP_GENERAL); w.u16le(0x18)
        w.u16le(0x01)                    // osMajorType = WINDOWS
        w.u16le(0x03)                    // osMinorType = WINDOWSNT
        w.u16le(0x0200)                  // protocolVersion
        w.u16le(0)
        w.u16le(0)                       // generalCompressionTypes
        w.u16le(0x040C)                  // NO_BITMAP_COMPRESSION_HDR|LONG_CREDENTIALS|AUTORECONNECT
        w.u16le(0); w.u16le(0); w.u16le(0)
        w.u8(1)                          // refreshRectSupport
        w.u8(1)                          // suppressOutputSupport

        // ---- Bitmap ----
        w.u16le(CAP_BITMAP); w.u16le(0x1C)
        w.u16le(bpp)                     // preferredBitsPerPixel
        w.u16le(1); w.u16le(1); w.u16le(1)
        w.u16le(cfg.width); w.u16le(cfg.height)
        w.u16le(0)
        w.u16le(1)                       // desktopResizeFlag
        w.u16le(0)                       // bitmapCompressionFlag = 不支持压缩
        w.u8(0); w.u8(0)                 // highColorFlags, drawingFlags
        w.u16le(1)                       // multipleRectangleSupport
        w.u16le(0)

        // ---- Order ----
        // 既然已经实现了 orders 解释器，就按真实能力声明。注意 orderSupport
        // 的索引**不是** order type，是 capability 自己的编号（MEMBLT=0x03 而
        // order type 是 13，LINETO=0x08 而 order type 是 9），填错服务端会
        // 认为我们不支持对应指令而不发/改发别的。
        w.u16le(CAP_ORDER); w.u16le(0x58)
        w.zeros(16)                      // terminalDescriptor
        w.zeros(4)                       // pad4OctetsA
        w.u16le(1)                       // desktopSaveXGranularity
        w.u16le(20)                      // desktopSaveYGranularity
        w.u16le(0)                       // pad2OctetsA
        w.u16le(1)                       // maximumOrderLevel
        w.u16le(0)                       // numberFonts
        w.u16le(0x002A)                  // NEGOTIATEORDERSUPPORT|ZEROBOUNDSDELTASSUPPORT|COLORINDEXSUPPORT
        val orderSupport = ByteArray(32)
        for (idx in intArrayOf(0x00, 0x01, 0x02, 0x03, 0x08, 0x0B, 0x14, 0x15, 0x16, 0x19, 0x1A)) {
            orderSupport[idx] = 1
        }
        w.bytes(orderSupport)
        w.u16le(0)                       // textFlags
        w.u16le(0)                       // orderSupportExFlags
        w.u32le(0)                       // pad4OctetsB
        w.u32le(230400)                  // desktopSaveSize
        w.u16le(0); w.u16le(0)
        w.u16le(1252)                    // textANSICodePage
        w.u16le(0)

        // ---- Bitmap cache rev2 ----
        // 没有这个声明，服务端不会填充位图缓存，MEMBLT 就永远画不出东西。
        w.u16le(CAP_BMPCACHE2); w.u16le(0x28)
        w.u16le(0)                       // cacheFlags（不启用持久缓存）
        w.u16be(3)                       // numCellCaches：16x16 / 32x32 / 64x64
        w.u32le(0x78)                    // cache0 = 16x16，120 项
        w.u32le(0x78)                    // cache1 = 32x32
        w.u32le(0x150)                   // cache2 = 64x64
        w.zeros(20)                      // 其余缓存不使用

        // ---- Pointer（新版，支持 32bpp 光标）----
        w.u16le(CAP_POINTER); w.u16le(0x0A)
        w.u16le(1)                       // colorPointerFlag
        w.u16le(20)                      // colorPointerCacheSize
        w.u16le(20)                      // pointerCacheSize

        // ---- Input ----
        w.u16le(CAP_INPUT); w.u16le(0x58)
        w.u16le(0x0001)                  // INPUT_FLAG_SCANCODES
        w.u16le(0)                       // pad2OctetsA
        w.u32le(0x0409)                  // keyboardLayout
        w.u32le(0x04)                    // keyboardType
        w.u32le(0)                       // keyboardSubType
        w.u32le(12)                      // keyboardFunctionKey
        w.zeros(64)                      // imeFileName

        // ---- Share ----
        w.u16le(CAP_SHARE); w.u16le(8)
        w.u16le(0); w.u16le(0)

        // ---- Colour cache ----
        w.u16le(CAP_COLCACHE); w.u16le(8)
        w.u16le(6); w.u16le(0)

        // ---- Font ----
        w.u16le(CAP_FONT); w.u16le(8)
        w.u16le(0); w.u16le(0)

        // ---- Brush cache ----
        w.u16le(CAP_BRUSHCACHE); w.u16le(8)
        w.u32le(1)
        return w.toByteArray()
    }

    fun buildConfirmActive(userId: Int, shareId: Int, caps: ByteArray): ByteArray {
        val capCount = countCaps(caps)
        val src = SOURCE_NAME.toByteArray(Charsets.US_ASCII)
        // 6(shareControlHeader) + 4(shareId) + 2(originatorId) + 2(lenSourceDescriptor)
        // + 2(lenCombinedCapabilities) + src + 2(numberCapabilities) + 2(pad2Octets)
        val total = 6 + 4 + 2 + 2 + 2 + src.size + 2 + 2 + caps.size
        val w = ByteWriter(total)
        w.u16le(total)                   // totalLength：含自身 6 字节头
        w.u16le(PDU_CONFIRM_ACTIVE)
        w.u16le(userId + 1001)           // pduSource = mcsUserId + MCS_USERCHANNEL_BASE
        w.u32le(shareId)
        w.u16le(0x03EA)                  // originatorId
        w.u16le(src.size)                // lengthSourceDescriptor
        w.u16le(caps.size)               // lengthCombinedCapabilities
        w.bytes(src)                     // sourceDescriptor
        w.u16le(capCount)                // numberCapabilities
        w.u16le(0)                       // pad2Octets
        w.bytes(caps)
        return w.toByteArray()
    }

    /** 扫描 caps 块，数出能力集个数（每个 = type(2) + len(2) + data） */
    fun countCaps(caps: ByteArray): Int {
        var off = 0
        var n = 0
        while (off + 4 <= caps.size) {
            val len = (caps[off + 2].toInt() and 0xFF) or ((caps[off + 3].toInt() and 0xFF) shl 8)
            if (len < 4 || off + len > caps.size) break
            off += len
            n++
        }
        return n
    }

    /** Share Data Header（18 字节）+ 具体数据 */
    fun buildShareData(pduType2: Int, payload: ByteArray, shareId: Int): ByteArray {
        val total = 18 + payload.size
        val w = ByteWriter(total)
        w.u16le(total)
        w.u16le(PDU_DATA)
        w.u16le(0)                       // mcsUserId，发送时由上层填
        w.u32le(shareId)
        w.u8(0)                          // pad
        w.u8(1)                          // streamId
        w.u16le(payload.size)            // uncompressedLength
        w.u8(pduType2)
        w.u8(0)                          // compressedType
        w.u16le(0)                       // compressedLength
        w.bytes(payload)
        return w.toByteArray()
    }

    fun buildSynchronize(): ByteArray {
        val w = ByteWriter(4)
        w.u16le(1); w.u16le(0x03EA)      // messageType, targetUser
        return w.toByteArray()
    }

    fun buildControl(action: Int): ByteArray {
        val w = ByteWriter(8)
        w.u16le(action); w.u16le(0x03EA); w.u32le(0)
        return w.toByteArray()
    }

    /**
     * TS_FONT_LIST_PDU：numberFonts(2) + totalNumFonts(2) + listFlags(2) + entrySize(2)。
     *
     * `listFlags = 0x0003`（FONTLIST_FIRST|FONTLIST_LAST）是**连接时序的开关**：
     * xrdp 收到它才把 `session->up_and_running` 置 1，进而创建窗口管理器、
     * 向 sesman 发起登录、开始推送画面。listFlags 填 0 会一直卡在「已连接但没有画面」。
     */
    fun buildFontList(): ByteArray {
        val w = ByteWriter(8)
        w.u16le(0)                       // numberFonts
        w.u16le(0)                       // totalNumFonts
        w.u16le(0x0003)                  // listFlags
        w.u16le(0x0004)                  // entrySize
        return w.toByteArray()
    }

    // ---------------- 位图 ----------------

    /**
     * 解析 PDUTYPE2_UPDATE 里的位图更新。
     * 数据布局：updateType(2) 已由调用方刨掉 → 这里从 numberOfRectangles 开始。
     */
    fun parseBitmapUpdate(r: ByteReader, fb: RdpFrameBuffer, listener: RdpListener) {
        val count = r.u16le()
        for (i in 0 until count) {
            if (r.remaining < 18) return
            val left = r.u16le()
            val top = r.u16le()
            val right = r.u16le()
            val bottom = r.u16le()
            val width = r.u16le()
            val height = r.u16le()
            val bpp = r.u16le()
            val flags = r.u16le()
            val len = r.u16le()
            val cx = right - left + 1
            val cy = bottom - top + 1
            if (width <= 0 || height <= 0 || cx <= 0 || cy <= 0) continue

            if (flags and 0x0001 == 0) {
                // 未压缩：逐行 bottom-up，无行对齐
                val bytesPerPixel = (bpp + 7) / 8
                val rowBytes = width * bytesPerPixel
                if (rowBytes > r.remaining) return
                for (y in 0 until height) {
                    val row = r.bytes(rowBytes)
                    val destY = top + (height - 1 - y)
                    if (destY < 0 || destY >= fb.height) continue
                    val argb = IntArray(width)
                    convertRow(row, argb, width, bytesPerPixel)
                    fb.putRow(left, destY, argb, width)
                }
                listener.onFrameUpdate(left, top, cx, cy)
                fb.markDirty()
            } else {
                // 压缩路径暂不支持（我们声明了不支持压缩，正常不会走到这里）
                val skip = if (flags and 0x0400 != 0) len else len
                if (skip > r.remaining) return
                r.skip(skip)
            }
        }
    }

    private fun convertRow(row: ByteArray, out: IntArray, width: Int, bytesPerPixel: Int) {
        when (bytesPerPixel) {
            2 -> {
                var i = 0
                var o = 0
                while (i + 1 < row.size && o < width) {
                    val v = (row[i].toInt() and 0xFF) or ((row[i + 1].toInt() and 0xFF) shl 8)
                    val r = (v ushr 11) and 0x1F
                    val g = (v ushr 5) and 0x3F
                    val b = v and 0x1F
                    out[o] = (0xFF shl 24) or ((r shl 3) shl 16) or ((g shl 2) shl 8) or (b shl 3)
                    i += 2; o++
                }
                while (o < width) { out[o] = 0xFF000000.toInt(); o++ }
            }
            3 -> {
                var i = 0
                var o = 0
                while (i + 2 < row.size && o < width) {
                    val b = row[i].toInt() and 0xFF
                    val g = row[i + 1].toInt() and 0xFF
                    val r = row[i + 2].toInt() and 0xFF
                    out[o] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    i += 3; o++
                }
                while (o < width) { out[o] = 0xFF000000.toInt(); o++ }
            }
            else -> {
                var i = 0
                var o = 0
                while (i + 3 < row.size && o < width) {
                    val b = row[i].toInt() and 0xFF
                    val g = row[i + 1].toInt() and 0xFF
                    val r = row[i + 2].toInt() and 0xFF
                    out[o] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    i += 4; o++
                }
                while (o < width) { out[o] = 0xFF000000.toInt(); o++ }
            }
        }
    }

    // ---------------- 输入 ----------------

    /**
     * 组合一个 TS_INPUT_PDU_DATA：numberEvents(2) + pad(2) + 事件。
     *
     * 注意：每个事件**必须占满 12 字节**（eventTime 4 + messageType 2 +
     * deviceFlags 2 + param1 2 + param2 2）。协议上同步事件只需要 6 字节，
     * 但 xrdp 的解析器对每个事件都硬性 `s_check_rem(12)`，少一个字节就
     * 报「TS_INPUT_EVENT Not enough bytes」并丢弃整包；Windows 则忽略多出的补零。
     */
    fun inputEvents(events: List<ByteArray>): ByteArray {
        val w = ByteWriter(4 + events.sumOf { it.size })
        w.u16le(events.size)             // numberOfEvents
        w.u16le(0)                       // pad2Octets
        for (e in events) w.bytes(e)
        return w.toByteArray()
    }

    fun mouseEvent(pointerFlags: Int, x: Int, y: Int): ByteArray {
        val w = ByteWriter(12)
        w.u32le(0)                       // eventTime
        w.u16le(INPUT_MOUSE)
        w.u16le(pointerFlags)
        w.u16le(x); w.u16le(y)
        return w.toByteArray()
    }

    fun scancodeEvent(scancode: Int, pressed: Boolean, extended: Boolean = false): ByteArray {
        val w = ByteWriter(12)
        w.u32le(0)
        w.u16le(INPUT_SCANCODE)
        var flags = 0
        if (!pressed) flags = flags or 0x0001     // KBDFLAGS_RELEASE
        if (extended) flags = flags or 0x0100     // KBDFLAGS_EXTENDED
        w.u16le(flags)
        // keyCode 就是原始扫描码，扩展位由 keyboardFlags 表达，不要在这里 or 0xE000
        w.u16le(scancode)
        w.u16le(0)                                // 补满 12 字节
        return w.toByteArray()
    }

    fun unicodeEvent(char: Int, pressed: Boolean): ByteArray {
        val w = ByteWriter(12)
        w.u32le(0)
        w.u16le(INPUT_UNICODE)
        w.u16le(if (pressed) 0 else 0x0001)
        w.u16le(char)
        w.u16le(0)                                // 补满 12 字节
        return w.toByteArray()
    }

    fun synchronizeEvent(): ByteArray {
        val w = ByteWriter(12)
        w.u32le(0)
        w.u16le(INPUT_SYNCHRONIZE)
        w.u16le(0)                                // toggleFlags
        w.u16le(0); w.u16le(0)                    // 补满 12 字节
        return w.toByteArray()
    }

    // 鼠标标志
    const val PTR_FLAGS_MOVE = 0x0800
    const val PTR_FLAGS_DOWN = 0x8000
    const val PTR_FLAGS_BUTTON1 = 0x1000
    const val PTR_FLAGS_BUTTON2 = 0x2000
    const val PTR_FLAGS_BUTTON3 = 0x4000
    const val PTR_FLAGS_WHEEL = 0x0200
    const val PTR_FLAGS_WHEEL_NEGATIVE = 0x0100

    const val PDU_TYPE_UPDATE = PDU2_UPDATE
    const val PDU_TYPE_INPUT = PDU2_INPUT
    const val PDU_TYPE_SYNC = PDU2_SYNCHRONISE
    const val PDU_TYPE_CONTROL = PDU2_CONTROL
    const val PDU_TYPE_FONT = PDU2_FONT2
    const val UPDATE_BITMAP = UPDATE_TYPE_BITMAP
    const val UPDATE_ORDERS = UPDATE_TYPE_ORDERS
}
