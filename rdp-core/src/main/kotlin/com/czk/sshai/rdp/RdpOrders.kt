package com.czk.sshai.rdp

/**
 * MS-RDPEGDI 绘图指令（Drawing Orders）解释器。
 *
 * 为什么必须有这个：xrdp 画桌面时**无条件使用 orders**，完全不看客户端
 * 有没有在 capability set 里声明 orderSupport（我把 orderSupport 全置 0
 * 试过，它照样只发 orders）。只有 Windows 在客户端不支持时才退回原始位图。
 * 所以想在 Linux 桌面上看到画面，就必须把 orders 实现出来。
 *
 * 解析逻辑逐字段移植自 rdesktop 的 `orders.c`，包括几个不写就会错的点：
 *
 *  - **delta 模式**：flags 带 `ORDER_DELTA(0x10)` 时，坐标只传 1 字节差值，
 *    必须累加到上一次的值上。所以每种 order 的字段都要**持久保存**，
 *    这里用 `Array<OrderState>` 按 orderType 索引来承载。
 *  - **present 位图的字节数**随 order 类型不同（1/2/3 字节），并且会被
 *    `SMALL`/`TINY` 标志削减。
 *  - **bounds 是闭区间**，且 `LASTBOUNDS` 表示复用上一次的 bounds。
 *  - **secondary order 的 length 要 +13，且从 order_flags 那个字节起算**。
 *
 * 绘制直接落在 [RdpFrameBuffer] 的 ARGB 数组上，不做中间图层。
 */
class RdpOrders(private val fb: RdpFrameBuffer, private val listener: RdpListener) {

    // ---------------- 常量 ----------------

    private companion object {
        const val ORDER_STANDARD = 0x01
        const val ORDER_SECONDARY = 0x02
        const val ORDER_BOUNDS = 0x04
        const val ORDER_CHANGE = 0x08
        const val ORDER_DELTA = 0x10
        const val ORDER_LASTBOUNDS = 0x20
        const val ORDER_SMALL = 0x40
        const val ORDER_TINY = 0x80

        const val T_DESTBLT = 0
        const val T_PATBLT = 1
        const val T_SCREENBLT = 2
        const val T_LINE = 9
        const val T_RECT = 10
        const val T_DESKSAVE = 11
        const val T_MEMBLT = 13
        const val T_TRIBLT = 14
        const val T_POLYGON = 20
        const val T_POLYGON2 = 21
        const val T_POLYLINE = 22
        const val T_ELLIPSE = 25
        const val T_ELLIPSE2 = 26
        const val T_TEXT2 = 27
        const val MAX_TYPE = 32

        const val S_RAW_BMPCACHE = 0
        const val S_COLCACHE = 1
        const val S_BMPCACHE = 2
        const val S_FONTCACHE = 3
        const val S_RAW_BMPCACHE2 = 4
        const val S_BMPCACHE2 = 5
        const val S_BRUSHCACHE = 7

        // bmpcache2 的 flags 掩码
        const val ID_MASK = 0x0007
        const val MODE_MASK = 0x0038
        const val MODE_SHIFT = 3
        const val SQUARE = 0x0080
        const val PERSIST = 0x0100
        const val LONG_FORMAT = 0x80
        const val BUFSIZE_MASK = 0x3FFF

        // 单个缓存项上限，防止异常数据把内存吃光
        const val MAX_CACHE_ENTRIES = 4096
    }

    // ---------------- 持久状态 ----------------

    private class Brush {
        var xOrigin = 0
        var yOrigin = 0
        var style = 0                 // 0 = BS_SOLID
        val pattern = IntArray(8)     // 每行一个 8 位掩码，MSB 为最左
    }

    private class Pen {
        var style = 0
        var width = 0
        var colour = 0
    }

    /** 一种 order 的持久字段；delta 模式靠它累加 */
    private class OrderState {
        var x = 0
        var y = 0
        var cx = 0
        var cy = 0
        var opcode = 0
        var bg = 0
        var fg = 0
        var srcx = 0
        var srcy = 0
        var cacheId = 0
        var colourTable = 0
        var cacheIdx = 0
        var mixMode = 0
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        var colour = 0
        var offset = 0
        var fillMode = 0
        var font = 0
        var flags = 0
        var npoints = 0
        var unknown = 0
        val brush = Brush()
        val pen = Pen()
    }

    private val states = Array(MAX_TYPE) { OrderState() }

    private var orderType = 0
    private val bounds = IntArray(4)   // left, top, right, bottom（闭区间）

    // 当前裁剪区（闭区间）
    private var clipL = 0
    private var clipT = 0
    private var clipR = -1
    private var clipB = -1

    // 位图缓存：key = (cacheId shl 16) or idx
    private class CachedBitmap(val width: Int, val height: Int, val argb: IntArray)
    private val bitmapCache = HashMap<Int, CachedBitmap>()

    // brush 缓存：key = (colourCode shl 8) or idx，值为 8x8 ARGB
    private val brushCache = HashMap<Int, IntArray>()

    // desktop save 槽
    private val deskSlots = HashMap<Int, IntArray>()

    // 屏幕拷贝用的临时缓冲，避免每次分配
    private var scratch = IntArray(0)

    // 诊断计数
    private var statOrders = 0L
    private var statMemblt = 0L
    private var statUnknown = 0L

    init {
        resetClip()
    }

    /** 供上层打日志用 */
    fun stats(): String = "orders=$statOrders memblt=$statMemblt 缓存=" +
        "${bitmapCache.size} 未处理=$statUnknown"

    // ---------------- 入口 ----------------

    /**
     * 处理一条 `UPDATETYPE_ORDERS` 更新。
     *
     * @param data 整个 PDUTYPE2_UPDATE 的 payload
     * @param offset updateType(2) 之后的位置
     */
    fun processUpdate(data: ByteArray, offset: Int) {
        val r = ByteReader(data, offset)
        if (r.remaining < 6) return
        r.skip(2)                       // pad
        val numOrders = r.u16le()
        r.skip(2)                       // pad
        resetClip()
        var processed = 0
        while (processed < numOrders && r.remaining > 0) {
            val flags = r.u8()
            if (flags and ORDER_STANDARD == 0) {
                listener.onLog("orders：controlFlags=0x${flags.toString(16)} 缺 STANDARD 位，中止")
                return
            }
            if (flags and ORDER_SECONDARY != 0) {
                processSecondary(r)
            } else {
                processPrimary(r, flags)
            }
            processed++
            statOrders++
        }
    }

    private fun processPrimary(r: ByteReader, flags: Int) {
        if (flags and ORDER_CHANGE != 0) {
            orderType = r.u8()
            if (orderType >= MAX_TYPE) {
                statUnknown++
                throw RdpProtocolException("未知 order type=$orderType")
            }
        }
        val st = states[orderType]
        val presentBytes = when (orderType) {
            T_TRIBLT, T_TEXT2 -> 3
            T_PATBLT, T_MEMBLT, T_LINE, T_POLYGON2, T_ELLIPSE2 -> 2
            else -> 1
        }
        val present = readPresent(r, flags, presentBytes)
        val delta = flags and ORDER_DELTA != 0

        var savedClip = false
        if (flags and ORDER_BOUNDS != 0) {
            if (flags and ORDER_LASTBOUNDS == 0) parseBounds(r)
            setClip(bounds[0], bounds[1], bounds[2], bounds[3])
            savedClip = true
        }

        when (orderType) {
            T_DESTBLT -> destBlt(r, st, present, delta)
            T_PATBLT -> patBltOrder(r, st, present, delta)
            T_SCREENBLT -> screenBltOrder(r, st, present, delta)
            T_LINE -> lineOrder(r, st, present, delta)
            T_RECT -> rectOrder(r, st, present, delta)
            T_DESKSAVE -> deskSaveOrder(r, st, present, delta)
            T_MEMBLT -> memBltOrder(r, st, present, delta)
            T_TRIBLT -> tribltOrder(r, st, present, delta)
            T_POLYGON -> polygonOrder(r, st, present, delta)
            T_POLYGON2 -> polygon2Order(r, st, present, delta)
            T_POLYLINE -> polylineOrder(r, st, present, delta)
            T_ELLIPSE2 -> ellipse2Order(r, st, present, delta)
            T_TEXT2 -> skipText2(r, st, present)
            else -> {
                statUnknown++
                listener.onLog("未实现的 order type=$orderType，本次更新中止")
                if (savedClip) resetClip()
                throw RdpProtocolException("skip")
            }
        }
        if (savedClip) resetClip()
    }

    // ---------------- 头部小工具 ----------------

    private fun readPresent(r: ByteReader, flags: Int, size0: Int): Int {
        var size = size0
        if (flags and ORDER_SMALL != 0) size--
        if (flags and ORDER_TINY != 0) size = if (size < 2) 0 else size - 2
        var present = 0
        for (i in 0 until size) present = present or (r.u8() shl (i * 8))
        return present
    }

    private fun parseBounds(r: ByteReader) {
        val present = r.u8()
        bounds[0] = when {
            present and 0x01 != 0 -> r.u16le()
            present and 0x10 != 0 -> bounds[0] + r.u8().toByte().toInt()
            else -> bounds[0]
        }
        bounds[1] = when {
            present and 0x02 != 0 -> r.u16le()
            present and 0x20 != 0 -> bounds[1] + r.u8().toByte().toInt()
            else -> bounds[1]
        }
        bounds[2] = when {
            present and 0x04 != 0 -> r.u16le()
            present and 0x40 != 0 -> bounds[2] + r.u8().toByte().toInt()
            else -> bounds[2]
        }
        bounds[3] = when {
            present and 0x08 != 0 -> r.u16le()
            present and 0x80 != 0 -> bounds[3] + r.u8().toByte().toInt()
            else -> bounds[3]
        }
    }

    private fun coord(r: ByteReader, cur: Int, delta: Boolean): Int =
        if (delta) cur + r.u8().toByte().toInt() else r.u16le()

    /** COLORREF → ARGB */
    private fun colorRef(c: Int): Int =
        (0xFF shl 24) or ((c and 0xFF) shl 16) or (((c ushr 8) and 0xFF) shl 8) or ((c ushr 16) and 0xFF)

    private fun setClip(l: Int, t: Int, r: Int, b: Int) {
        clipL = maxOf(0, l)
        clipT = maxOf(0, t)
        clipR = minOf(fb.width - 1, r)
        clipB = minOf(fb.height - 1, b)
    }

    private fun resetClip() {
        clipL = 0; clipT = 0; clipR = fb.width - 1; clipB = fb.height - 1
    }

    // ---------------- 绘制原语 ----------------

    /** 按 ROP2 把 src/dst 混成一个像素（只算 RGB，alpha 固定不透明） */
    private fun ropPixel(src: Int, dst: Int, rop2: Int): Int {
        val s = src and 0xFFFFFF
        val d = dst and 0xFFFFFF
        val v = when (rop2 and 0xF) {
            0 -> 0                                   // BLACK
            1 -> (d or s).inv()                      // NOTMERGEPEN
            2 -> s.inv() and d                       // MASKNOTPEN
            3 -> s.inv()                             // NOTCOPYPEN
            4 -> s and d.inv()                       // MASKPENNOT
            5 -> d.inv()                             // NOT
            6 -> s xor d                             // XORPEN
            7 -> (s and d).inv()                     // NOTMASKPEN
            8 -> s and d                             // MASKPEN
            9 -> (s xor d).inv()                     // NOTXORPEN
            10 -> d                                  // NOP
            11 -> s.inv() or d                       // MERGENOTPEN
            12 -> s                                  // COPYPEN
            13 -> s or d.inv()                       // MERGEPENNOT
            14 -> s or d                             // MERGEPEN
            else -> 0xFFFFFF                         // WHITE
        }
        return (0xFF shl 24) or (v and 0xFFFFFF)
    }

    /** 纯色填充（带裁剪 + ROP） */
    private fun fillRectRop(x: Int, y: Int, w: Int, h: Int, argb: Int, rop2: Int) {
        if (w <= 0 || h <= 0) return
        val l = maxOf(x, clipL)
        val t = maxOf(y, clipT)
        val r = minOf(x + w - 1, clipR)
        val b = minOf(y + h - 1, clipB)
        if (l > r || t > b) return
        if (rop2 == 12) {                       // COPY：走快速路径
            fb.fillRect(l, t, r - l + 1, b - t + 1, argb)
            return
        }
        if (rop2 == 0) {
            fb.fillRect(l, t, r - l + 1, b - t + 1, 0xFF000000.toInt())
            return
        }
        if (rop2 == 15) {
            fb.fillRect(l, t, r - l + 1, b - t + 1, 0xFFFFFFFF.toInt())
            return
        }
        val p = fb.pixels
        for (yy in t..b) {
            val base = yy * fb.width
            for (xx in l..r) p[base + xx] = ropPixel(argb, p[base + xx], rop2)
        }
    }

    /** 从位图缓存往屏幕贴，带 ROP */
    private fun blitBitmap(
        dstX: Int, dstY: Int, w: Int, h: Int,
        bmp: CachedBitmap, srcX: Int, srcY: Int, rop2: Int,
    ) {
        if (w <= 0 || h <= 0) return
        val l = maxOf(dstX, clipL)
        val t = maxOf(dstY, clipT)
        val r = minOf(dstX + w - 1, clipR)
        val b = minOf(dstY + h - 1, clipB)
        if (l > r || t > b) return
        val p = fb.pixels
        for (yy in t..b) {
            val sy = srcY + (yy - dstY)
            if (sy < 0 || sy >= bmp.height) continue
            val sBase = sy * bmp.width
            val dBase = yy * fb.width
            for (xx in l..r) {
                val sx = srcX + (xx - dstX)
                if (sx < 0 || sx >= bmp.width) continue
                val s = bmp.argb[sBase + sx]
                if (rop2 == 12) {
                    p[dBase + xx] = (0xFF shl 24) or (s and 0xFFFFFF)
                } else {
                    p[dBase + xx] = ropPixel(s, p[dBase + xx], rop2)
                }
            }
        }
    }

    /** 屏幕上就地拷贝（SCRBLT），先整体取出源再写回，避免重叠踩踏 */
    private fun screenCopy(x: Int, y: Int, w: Int, h: Int, srcX: Int, srcY: Int, rop2: Int) {
        if (w <= 0 || h <= 0) return
        val need = w * h
        if (scratch.size < need) scratch = IntArray(need)
        val p = fb.pixels
        for (row in 0 until h) {
            val sy = srcY + row
            val sBase = sy * fb.width
            val o = row * w
            for (col in 0 until w) {
                val sx = srcX + col
                scratch[o + col] = if (sy in 0 until fb.height && sx in 0 until fb.width) p[sBase + sx]
                else 0xFF101010.toInt()
            }
        }
        val l = maxOf(x, clipL)
        val t = maxOf(y, clipT)
        val r = minOf(x + w - 1, clipR)
        val b = minOf(y + h - 1, clipB)
        if (l > r || t > b) return
        for (yy in t..b) {
            val dBase = yy * fb.width
            val o = (yy - y) * w
            for (xx in l..r) {
                val s = scratch[o + (xx - x)]
                p[dBase + xx] = if (rop2 == 12) s else ropPixel(s, p[dBase + xx], rop2)
            }
        }
    }

    private fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int, width: Int, rop2: Int) {
        var x0 = x1; var y0 = y1
        val dx = kotlin.math.abs(x2 - x0)
        val dy = -kotlin.math.abs(y2 - y0)
        val sx = if (x0 < x2) 1 else -1
        val sy = if (y0 < y2) 1 else -1
        var err = dx + dy
        val half = maxOf(0, width / 2)
        var guard = 0
        while (true) {
            if (width <= 1) {
                if (x0 in clipL..clipR && y0 in clipT..clipB) {
                    val idx = y0 * fb.width + x0
                    fb.pixels[idx] = if (rop2 == 12) argb else ropPixel(argb, fb.pixels[idx], rop2)
                }
            } else {
                fillRectRop(x0 - half, y0 - half, width, width, argb, rop2)
            }
            if (x0 == x2 && y0 == y2) break
            if (++guard > 100_000) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }

    // ---------------- 各类 order ----------------

    private fun destBlt(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x01 != 0) st.x = coord(r, st.x, delta)
        if (present and 0x02 != 0) st.y = coord(r, st.y, delta)
        if (present and 0x04 != 0) st.cx = coord(r, st.cx, delta)
        if (present and 0x08 != 0) st.cy = coord(r, st.cy, delta)
        if (present and 0x10 != 0) st.opcode = r.u8()
        // opcode 是 ROP3，低 4 位即 ROP2
        fillRectRop(st.x, st.y, st.cx, st.cy, colorRef(st.fg), st.opcode and 0xF)
    }

    private fun patBltOrder(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x0001 != 0) st.x = coord(r, st.x, delta)
        if (present and 0x0002 != 0) st.y = coord(r, st.y, delta)
        if (present and 0x0004 != 0) st.cx = coord(r, st.cx, delta)
        if (present and 0x0008 != 0) st.cy = coord(r, st.cy, delta)
        if (present and 0x0010 != 0) st.opcode = r.u8()
        if (present and 0x0020 != 0) st.bg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        if (present and 0x0040 != 0) st.fg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        parseBrush(r, st.brush, present ushr 7)

        val rop2 = patRop2(st.opcode)
        val fg = colorRef(st.fg)
        val bg = colorRef(st.bg)
        val solid = st.brush.style == 0 ||
            (st.brush.pattern[0] == 0 && st.brush.pattern[1] == 0 &&
                st.brush.pattern[2] == 0 && st.brush.pattern[3] == 0 &&
                st.brush.pattern[4] == 0 && st.brush.pattern[5] == 0 &&
                st.brush.pattern[6] == 0 && st.brush.pattern[7] == 0)
        if (solid) {
            fillRectRop(st.x, st.y, st.cx, st.cy, fg, rop2)
            return
        }
        // 8x8 hatch 图案：bit=1 用 fg，bit=0 用 bg
        if (st.cx <= 0 || st.cy <= 0) return
        val l = maxOf(st.x, clipL)
        val t = maxOf(st.y, clipT)
        val rr = minOf(st.x + st.cx - 1, clipR)
        val bb = minOf(st.y + st.cy - 1, clipB)
        if (l > rr || t > bb) return
        for (yy in t..bb) {
            val pat = st.brush.pattern[(yy - st.y + st.brush.yOrigin) and 7]
            val dBase = yy * fb.width
            for (xx in l..rr) {
                val bit = (pat ushr (7 - ((xx - st.x + st.brush.xOrigin) and 7))) and 1
                val src = if (bit != 0) fg else bg
                fb.pixels[dBase + xx] = if (rop2 == 12) src else ropPixel(src, fb.pixels[dBase + xx], rop2)
            }
        }
    }

    private fun screenBltOrder(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x0001 != 0) st.x = coord(r, st.x, delta)
        if (present and 0x0002 != 0) st.y = coord(r, st.y, delta)
        if (present and 0x0004 != 0) st.cx = coord(r, st.cx, delta)
        if (present and 0x0008 != 0) st.cy = coord(r, st.cy, delta)
        if (present and 0x0010 != 0) st.opcode = r.u8()
        if (present and 0x0020 != 0) st.srcx = coord(r, st.srcx, delta)
        if (present and 0x0040 != 0) st.srcy = coord(r, st.srcy, delta)
        screenCopy(st.x, st.y, st.cx, st.cy, st.srcx, st.srcy, st.opcode and 0xF)
    }

    private fun lineOrder(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x0001 != 0) st.mixMode = r.u16le()
        if (present and 0x0002 != 0) st.x = coord(r, st.x, delta)          // startx
        if (present and 0x0004 != 0) st.y = coord(r, st.y, delta)          // starty
        if (present and 0x0008 != 0) st.cx = coord(r, st.cx, delta)        // endx
        if (present and 0x0010 != 0) st.cy = coord(r, st.cy, delta)        // endy
        if (present and 0x0020 != 0) st.bg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        if (present and 0x0040 != 0) st.opcode = r.u8()
        parsePen(r, st.pen, present ushr 7)

        // opcode 是 ROP2（1..16），转成 0..15
        val rop2 = (st.opcode - 1) and 0xF
        val w = maxOf(1, st.pen.width)
        drawLine(st.x, st.y, st.cx, st.cy, colorRef(st.pen.colour), w, rop2)
    }

    private fun rectOrder(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x01 != 0) st.x = coord(r, st.x, delta)
        if (present and 0x02 != 0) st.y = coord(r, st.y, delta)
        if (present and 0x04 != 0) st.cx = coord(r, st.cx, delta)
        if (present and 0x08 != 0) st.cy = coord(r, st.cy, delta)
        // 颜色分 3 个字节单独传输，保留未变更的通道
        if (present and 0x10 != 0) st.colour = (st.colour and 0xFFFF00.inv()) or r.u8()
        if (present and 0x20 != 0) st.colour = (st.colour and 0xFF00FF.inv()) or (r.u8() shl 8)
        if (present and 0x40 != 0) st.colour = (st.colour and 0x00FFFF.inv()) or (r.u8() shl 16)
        fillRectRop(st.x, st.y, st.cx, st.cy, colorRef(st.colour), 12)
    }

    private fun deskSaveOrder(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x01 != 0) st.offset = r.u32le().toInt()
        if (present and 0x02 != 0) st.left = coord(r, st.left, delta)
        if (present and 0x04 != 0) st.top = coord(r, st.top, delta)
        if (present and 0x08 != 0) st.right = coord(r, st.right, delta)
        if (present and 0x10 != 0) st.bottom = coord(r, st.bottom, delta)
        if (present and 0x20 != 0) st.opcode = r.u8()      // action: 0=save,1=restore
        val w = st.right - st.left + 1
        val h = st.bottom - st.top + 1
        if (w <= 0 || h <= 0) return
        if (st.opcode == 0) {
            val buf = IntArray(w * h)
            for (row in 0 until h) {
                val sy = st.top + row
                if (sy < 0 || sy >= fb.height) continue
                System.arraycopy(fb.pixels, sy * fb.width + maxOf(0, st.left), buf, row * w, w)
            }
            deskSlots[st.offset] = buf
        } else {
            val buf = deskSlots[st.offset] ?: return
            for (row in 0 until h) {
                val dy = st.top + row
                if (dy < 0 || dy >= fb.height) continue
                System.arraycopy(buf, row * w, fb.pixels, dy * fb.width + maxOf(0, st.left), w)
            }
        }
    }

    private fun memBltOrder(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x0001 != 0) {
            st.cacheId = r.u8()
            st.colourTable = r.u8()
        }
        if (present and 0x0002 != 0) st.x = coord(r, st.x, delta)
        if (present and 0x0004 != 0) st.y = coord(r, st.y, delta)
        if (present and 0x0008 != 0) st.cx = coord(r, st.cx, delta)
        if (present and 0x0010 != 0) st.cy = coord(r, st.cy, delta)
        if (present and 0x0020 != 0) st.opcode = r.u8()
        if (present and 0x0040 != 0) st.srcx = coord(r, st.srcx, delta)
        if (present and 0x0080 != 0) st.srcy = coord(r, st.srcy, delta)
        if (present and 0x0100 != 0) st.cacheIdx = r.u16le()

        val bmp = bitmapCache[cacheKey(st.cacheId, st.cacheIdx)] ?: return
        blitBitmap(st.x, st.y, st.cx, st.cy, bmp, st.srcx, st.srcy, st.opcode and 0xF)
        statMemblt++
    }

    private fun tribltOrder(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x000001 != 0) {
            st.cacheId = r.u8()
            st.colourTable = r.u8()
        }
        if (present and 0x000002 != 0) st.x = coord(r, st.x, delta)
        if (present and 0x000004 != 0) st.y = coord(r, st.y, delta)
        if (present and 0x000008 != 0) st.cx = coord(r, st.cx, delta)
        if (present and 0x000010 != 0) st.cy = coord(r, st.cy, delta)
        if (present and 0x000020 != 0) st.opcode = r.u8()
        if (present and 0x000040 != 0) st.srcx = coord(r, st.srcx, delta)
        if (present and 0x000080 != 0) st.srcy = coord(r, st.srcy, delta)
        if (present and 0x000100 != 0) st.bg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        if (present and 0x000200 != 0) st.fg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        parseBrush(r, st.brush, present ushr 10)
        if (present and 0x008000 != 0) st.cacheIdx = r.u16le()
        if (present and 0x010000 != 0) st.unknown = r.u16le()

        // TRIBLT 是「图案 + 位图」三元光栅操作，退化时按位图贴图处理
        val bmp = bitmapCache[cacheKey(st.cacheId, st.cacheIdx)] ?: return
        blitBitmap(st.x, st.y, st.cx, st.cy, bmp, st.srcx, st.srcy, st.opcode and 0xF)
    }

    private fun polygonOrder(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x01 != 0) st.x = coord(r, st.x, delta)
        if (present and 0x02 != 0) st.y = coord(r, st.y, delta)
        if (present and 0x04 != 0) st.opcode = r.u8()
        if (present and 0x08 != 0) st.fillMode = r.u8()
        if (present and 0x10 != 0) st.fg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        if (present and 0x20 != 0) st.npoints = r.u8()
        if (present and 0x40 != 0) {
            val size = r.u8()
            val d = r.bytes(size)
            drawPolygon(st.x, st.y, st.npoints, d, st.fillMode, colorRef(st.fg))
        }
    }

    private fun polygon2Order(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x0001 != 0) st.x = coord(r, st.x, delta)
        if (present and 0x0002 != 0) st.y = coord(r, st.y, delta)
        if (present and 0x0004 != 0) st.opcode = r.u8()
        if (present and 0x0008 != 0) st.fillMode = r.u8()
        if (present and 0x0010 != 0) st.bg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        if (present and 0x0020 != 0) st.fg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        parseBrush(r, st.brush, present ushr 6)
        if (present and 0x0200 != 0) st.npoints = r.u8()
        if (present and 0x0400 != 0) {
            val size = r.u8()
            val d = r.bytes(size)
            drawPolygon(st.x, st.y, st.npoints, d, st.fillMode, colorRef(st.fg))
        }
    }

    private fun polylineOrder(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x01 != 0) st.x = coord(r, st.x, delta)
        if (present and 0x02 != 0) st.y = coord(r, st.y, delta)
        if (present and 0x04 != 0) st.opcode = r.u8()
        if (present and 0x08 != 0) st.bg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        if (present and 0x10 != 0) st.fg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        if (present and 0x20 != 0) st.npoints = r.u8()
        parsePen(r, st.pen, present ushr 6)
        if (present and 0x0400 != 0) {
            val size = r.u8()
            val d = r.bytes(size)
            drawPolyline(st.x, st.y, st.npoints, d, colorRef(st.fg))
        }
    }

    private fun ellipse2Order(r: ByteReader, st: OrderState, present: Int, delta: Boolean) {
        if (present and 0x0001 != 0) st.left = coord(r, st.left, delta)
        if (present and 0x0002 != 0) st.top = coord(r, st.top, delta)
        if (present and 0x0004 != 0) st.right = coord(r, st.right, delta)
        if (present and 0x0008 != 0) st.bottom = coord(r, st.bottom, delta)
        if (present and 0x0010 != 0) st.opcode = r.u8()
        if (present and 0x0020 != 0) st.fillMode = r.u8()
        if (present and 0x0040 != 0) st.bg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        if (present and 0x0080 != 0) st.fg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        parseBrush(r, st.brush, present ushr 8)
        drawEllipse(st.left, st.top, st.right, st.bottom, colorRef(st.fg))
    }

    /** TEXT2 依赖 glyph 缓存，这里只把字段读掉保证后续 order 不错位 */
    private fun skipText2(r: ByteReader, st: OrderState, present: Int) {
        if (present and 0x000001 != 0) st.font = r.u8()
        if (present and 0x000002 != 0) st.flags = r.u8()
        if (present and 0x000004 != 0) st.opcode = r.u8()
        if (present and 0x000008 != 0) st.mixMode = r.u8()
        if (present and 0x000010 != 0) st.fg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        if (present and 0x000020 != 0) st.bg = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
        if (present and 0x000040 != 0) st.left = r.u16le()
        if (present and 0x000080 != 0) st.top = r.u16le()
        if (present and 0x000100 != 0) st.right = r.u16le()
        if (present and 0x000200 != 0) r.u16le()
        if (present and 0x000400 != 0) r.u16le()
        if (present and 0x000800 != 0) r.u16le()
        if (present and 0x001000 != 0) r.u16le()
        if (present and 0x002000 != 0) r.u16le()
        parseBrush(r, st.brush, present ushr 14)
        if (present and 0x080000 != 0) st.x = r.u16le()
        if (present and 0x100000 != 0) st.y = r.u16le()
        if (present and 0x200000 != 0) {
            val len = r.u8()
            r.skip(minOf(len, r.remaining))
        }
    }

    // ---------------- brush / pen ----------------

    private fun parseBrush(r: ByteReader, brush: Brush, present: Int) {
        if (present and 1 != 0) brush.xOrigin = r.u8()
        if (present and 2 != 0) brush.yOrigin = r.u8()
        if (present and 4 != 0) brush.style = r.u8()
        if (present and 8 != 0) brush.pattern[0] = r.u8()
        if (present and 16 != 0) {
            // 后 7 行一次性传，按行存成掩码
            for (i in 1..7) brush.pattern[i] = r.u8()
        }
    }

    private fun parsePen(r: ByteReader, pen: Pen, present: Int) {
        if (present and 1 != 0) pen.style = r.u8()
        if (present and 2 != 0) pen.width = r.u8()
        if (present and 4 != 0) pen.colour = r.u8() or (r.u8() shl 8) or (r.u8() shl 16)
    }

    /**
     * PATBLT 的 opcode 是 ROP3，要折成 ROP2。
     * 公式 `(rop & 3) | ((rop & 0x30) >> 2)` 对 0xCC 会算出 BLACK（错的），
     * 所以对几个常见 ROP3 直接给准确映射。
     */
    private fun patRop2(rop3: Int): Int = when (rop3) {
        0xCC, 0xF0 -> 0xC        // SRCCOPY / PATCOPY
        0x5A -> 0x6              // PATINVERT (XOR)
        0xFB -> 0xF              // WHITENESS
        0xC0 -> 0x0              // BLACKNESS
        else -> (rop3 and 0x3) or ((rop3 and 0x30) ushr 2)
    }

    // ---------------- 多边形 / 椭圆 ----------------

    private fun drawPolygon(x0: Int, y0: Int, npoints: Int, data: ByteArray, fillMode: Int, argb: Int) {
        val pts = decodePolygonPoints(x0, y0, npoints, data) ?: return
        fillPolygon(pts, argb)
    }

    private fun drawPolyline(x0: Int, y0: Int, npoints: Int, data: ByteArray, argb: Int) {
        val pts = decodePolygonPoints(x0, y0, npoints, data) ?: return
        var i = 2
        while (i + 1 < pts.size) {
            drawLine(pts[i - 2], pts[i - 1], pts[i], pts[i + 1], argb, 1, 12)
            i += 2
        }
    }

    /** 把 RDP 的 delta 编码点串解成绝对坐标 [x0,y0,x1,y1,...] */
    private fun decodePolygonPoints(x0: Int, y0: Int, npoints: Int, data: ByteArray): IntArray? {
        if (npoints <= 0) return null
        var dataPos = (npoints - 1) / 4 + 1
        var index = 0
        var flags = 0
        val xs = IntArray(npoints + 1)
        val ys = IntArray(npoints + 1)
        xs[0] = x0
        ys[0] = y0
        var next = 1
        while (next <= npoints && next < 256 && dataPos < data.size) {
            if ((next - 1) % 4 == 0) {
                if (index >= data.size) break
                flags = data[index++].toInt() and 0xFF
            }
            if (flags and 0x80 == 0) {
                val v = readDelta(data, dataPos)
                if (v == null) break
                dataPos = v.second
                xs[next] = v.first
            } else {
                xs[next] = 0
            }
            if (flags and 0x40 == 0) {
                val v = readDelta(data, dataPos)
                if (v == null) break
                dataPos = v.second
                ys[next] = v.first
            } else {
                ys[next] = 0
            }
            flags = (flags shl 2) and 0xFF
            next++
        }
        // 累加成绝对坐标
        for (i in 1..npoints) {
            xs[i] += xs[i - 1]
            ys[i] += ys[i - 1]
        }
        val out = IntArray(npoints * 2)
        for (i in 0 until npoints) {
            out[i * 2] = xs[i]
            out[i * 2 + 1] = ys[i]
        }
        return out
    }

    /** RDP 的点坐标 delta 编码：单字节 or 双字节 */
    private fun readDelta(data: ByteArray, pos: Int): Pair<Int, Int>? {
        if (pos >= data.size) return null
        var v = data[pos].toInt() and 0xFF
        var p = pos + 1
        if (v and 0x80 != 0) {
            if (p >= data.size) return null
            val lo = data[p].toInt() and 0xFF
            p++
            v = ((v and 0x3F) shl 8) or lo
            if (v and 0x2000 != 0) v = v or 0xFC00.inv()   // 符号扩展到 16 位
            v = v.toShort().toInt()
        } else {
            if (v and 0x40 != 0) v = (v or 0x80).toByte().toInt()
            else v = v and 0x3F
        }
        return Pair(v, p)
    }

    private fun fillPolygon(pts: IntArray, argb: Int) {
        if (pts.size < 6) return
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (i in pts.indices step 2) {
            if (pts[i + 1] < minY) minY = pts[i + 1]
            if (pts[i + 1] > maxY) maxY = pts[i + 1]
        }
        minY = maxOf(minY, clipT)
        maxY = minOf(maxY, clipB)
        if (minY > maxY) return
        val n = pts.size / 2
        val xs = IntArray(n)
        for (yy in minY..maxY) {
            var cnt = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                val y1 = pts[i * 2 + 1]
                val y2 = pts[j * 2 + 1]
                if ((y1 <= yy && y2 > yy) || (y2 <= yy && y1 > yy)) {
                    val x1 = pts[i * 2]
                    val x2 = pts[j * 2]
                    xs[cnt++] = x1 + (yy - y1).toLong().let { d ->
                        ((x2 - x1).toLong() * d / (y2 - y1)).toInt()
                    }
                }
            }
            if (cnt < 2) continue
            java.util.Arrays.sort(xs, 0, cnt)
            var k = 0
            while (k + 1 < cnt) {
                val a = maxOf(xs[k], clipL)
                val b = minOf(xs[k + 1], clipR)
                if (a <= b) {
                    val base = yy * fb.width
                    for (xx in a..b) fb.pixels[base + xx] = argb
                }
                k += 2
            }
        }
    }

    private fun drawEllipse(left: Int, top: Int, right: Int, bottom: Int, argb: Int) {
        val cx = (left + right) / 2.0
        val cy = (top + bottom) / 2.0
        val rx = (right - left) / 2.0
        val ry = (bottom - top) / 2.0
        if (rx <= 0 || ry <= 0) return
        val steps = maxOf(24, (rx + ry).toInt())
        var px = cx + rx
        var py = cy
        for (i in 1..steps) {
            val a = 2 * Math.PI * i / steps
            val nx = cx + rx * Math.cos(a)
            val ny = cy + ry * Math.sin(a)
            drawLine(px.toInt(), py.toInt(), nx.toInt(), ny.toInt(), argb, 1, 12)
            px = nx; py = ny
        }
    }

    // ---------------- 二级 order（位图缓存） ----------------

    private fun processSecondary(r: ByteReader) {
        val orderStart = r.pos          // order_flags 之后的位置
        val length = r.u16le()
        val flags = r.u16le()
        val type = r.u8()
        // 规范：整条二级 order 长度 = length + 13，且从 order_flags 起算
        val next = orderStart + length + 12
        val payloadEnd = minOf(next, r.limit).coerceAtLeast(r.pos)

        try {
            when (type) {
                S_RAW_BMPCACHE -> rawBmpCache(r)
                S_COLCACHE -> colourCache(r)
                S_BMPCACHE -> bmpCacheV1(r)
                S_RAW_BMPCACHE2 -> bmpCacheV2(r, flags, compressed = false)
                S_BMPCACHE2 -> bmpCacheV2(r, flags, compressed = true)
                S_BRUSHCACHE -> brushCacheOrder(r, flags)
                S_FONTCACHE -> { /* 字体缓存，TEXT2 用；我们不走这条路 */ }
                else -> {
                    statUnknown++
                    listener.onLog("未实现的二级 order type=$type，跳过 $length 字节")
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            listener.onLog("二级 order type=$type 数据不足，已跳过")
        } catch (e: RdpProtocolException) {
            listener.onLog("二级 order type=$type 解析失败：${e.message}")
        }
        // 不管解析成败都对齐到下一条 order 的起点
        r.pos = payloadEnd
    }

    private fun cacheKey(cacheId: Int, idx: Int): Int = (cacheId shl 16) or (idx and 0xFFFF)

    private fun putBitmap(cacheId: Int, idx: Int, bmp: CachedBitmap) {
        if (bitmapCache.size > MAX_CACHE_ENTRIES) bitmapCache.clear()
        bitmapCache[cacheKey(cacheId, idx)] = bmp
    }

    /** 从 bottom-up 原始字节 + 每像素字节数，生成 ARGB 位图 */
    private fun toArgb(raw: ByteArray, width: Int, height: Int, bytesPerPixel: Int): IntArray {
        val argb = IntArray(width * height)
        var o = 0
        for (row in 0 until height) {
            val srcRow = height - 1 - row          // bottom-up → top-down
            var p = srcRow * width * bytesPerPixel
            when (bytesPerPixel) {
                2 -> {
                    var x = 0
                    while (x < width) {
                        val v = (raw[p].toInt() and 0xFF) or ((raw[p + 1].toInt() and 0xFF) shl 8)
                        val r = (v ushr 11) and 0x1F
                        val g = (v ushr 5) and 0x3F
                        val b = v and 0x1F
                        argb[o + x] = (0xFF shl 24) or ((r shl 3) shl 16) or ((g shl 2) shl 8) or (b shl 3)
                        p += 2; x++
                    }
                }
                3 -> {
                    var x = 0
                    while (x < width) {
                        argb[o + x] = (0xFF shl 24) or
                            ((raw[p + 2].toInt() and 0xFF) shl 16) or
                            ((raw[p + 1].toInt() and 0xFF) shl 8) or
                            (raw[p].toInt() and 0xFF)
                        p += 3; x++
                    }
                }
                else -> {
                    var x = 0
                    while (x < width) {
                        argb[o + x] = (0xFF shl 24) or
                            ((raw[p + 2].toInt() and 0xFF) shl 16) or
                            ((raw[p + 1].toInt() and 0xFF) shl 8) or
                            (raw[p].toInt() and 0xFF)
                        p += 4; x++
                    }
                }
            }
            o += width
        }
        return argb
    }

    private fun rawBmpCache(r: ByteReader) {
        val cacheId = r.u8()
        r.u8()                                   // pad
        val width = r.u8()
        val height = r.u8()
        val bpp = r.u8()
        val bytesPerPixel = (bpp + 7) / 8
        val bufSize = r.u16le()
        val idx = r.u16le()
        if (width <= 0 || height <= 0 || bytesPerPixel !in 1..4) return
        val data = r.bytes(minOf(bufSize, r.remaining))
        if (data.size < width * height * bytesPerPixel) return
        putBitmap(cacheId, idx, CachedBitmap(width, height, toArgb(data, width, height, bytesPerPixel)))
    }

    private fun colourCache(r: ByteReader) {
        val cacheId = r.u8()
        val n = r.u16le()
        // 我们固定 16/32bpp，调色板用不上，读掉即可
        val skip = minOf(n * 4, r.remaining)
        r.skip(skip)
        if (cacheId != 0) {
            // 需要调色板的是 8bpp，我们从不请求这个色深
        }
    }

    private fun bmpCacheV1(r: ByteReader) {
        val cacheId = r.u8()
        r.u8()                                   // pad
        val width = r.u8()
        val height = r.u8()
        val bpp = r.u8()
        val bytesPerPixel = (bpp + 7) / 8
        val bufSize = r.u16le()
        val idx = r.u16le()
        if (width <= 0 || height <= 0 || bytesPerPixel !in 1..4) return
        val size = minOf(bufSize, r.remaining)
        val raw = RdpRle.decompress(r.data, r.pos, size, width, height, bytesPerPixel) ?: run {
            r.skip(size)
            return
        }
        r.skip(size)
        putBitmap(cacheId, idx, CachedBitmap(width, height, toArgb(raw, width, height, bytesPerPixel)))
    }

    private fun bmpCacheV2(r: ByteReader, flags: Int, compressed: Boolean) {
        val cacheId = flags and ID_MASK
        val bytesPerPixel = ((flags and MODE_MASK) ushr MODE_SHIFT) - 2
        if (flags and PERSIST != 0) r.skip(8)                 // bitmap_id
        val width: Int
        val height: Int
        if (flags and SQUARE != 0) {
            width = r.u8(); height = width
        } else {
            width = r.u8(); height = r.u8()
        }
        var bufSize = r.u16be()
        bufSize = bufSize and BUFSIZE_MASK
        var idx = r.u8()
        if (idx and LONG_FORMAT != 0) {
            val low = r.u8()
            idx = ((idx xor LONG_FORMAT) shl 8) + low
        }
        if (width <= 0 || height <= 0 || bytesPerPixel !in 1..4) {
            r.skip(minOf(bufSize, r.remaining))
            return
        }
        val size = minOf(bufSize, r.remaining)
        val raw: ByteArray?
        if (compressed) {
            raw = RdpRle.decompress(r.data, r.pos, size, width, height, bytesPerPixel)
            r.skip(size)
        } else {
            val need = width * height * bytesPerPixel
            if (size < need) {
                r.skip(size)
                return
            }
            raw = r.bytes(need)
            if (size > need) r.skip(size - need)
        }
        if (raw == null) {
            listener.onLog("位图缓存解压失败 ${width}x${height} ${bytesPerPixel}Bpp idx=$idx")
            return
        }
        putBitmap(cacheId, idx, CachedBitmap(width, height, toArgb(raw, width, height, bytesPerPixel)))
    }

    private fun brushCacheOrder(r: ByteReader, flags: Int) {
        val idx = r.u8()
        val colourCode = r.u8()
        val width = r.u8()
        val height = r.u8()
        val type = r.u8()
        val size = r.u8()
        if (width != 8 || height != 8) {
            r.skip(minOf(size, r.remaining))
            return
        }
        if (colourCode == 1) {
            // 单色 8x8，bottom-up 传 8 字节
            val rows = IntArray(8)
            val buf = ByteArray(8)
            for (i in 7 downTo 0) {
                if (r.remaining <= 0) break
                buf[i] = r.u8().toByte()
            }
            for (i in 0 until 8) rows[i] = buf[i].toInt() and 0xFF
            brushCache[(colourCode shl 8) or idx] = rows
        } else if (colourCode in 3..6) {
            val bpp = colourCode - 2
            val need = 8 * 8 * bpp
            if (size == 16 + 4 * bpp) {
                // 压缩格式：16 字节位掩码 + 4*bpp 字节调色板
                if (r.remaining < 16 + 4 * bpp) return
                r.skip(16 + 4 * bpp)
            } else {
                r.skip(minOf(need, r.remaining))
            }
            // 图案画笔极少用，暂不展开成 ARGB
        } else {
            r.skip(minOf(size, r.remaining))
        }
    }
}
