package com.czk.sshai.rdp

/**
 * RDP 位图 RLE 解压（MS-RDPEGDI 的 interleaved RLE）。
 *
 * 直接移植自 rdesktop 的 `bitmap.c`（bitmap_decompress1/2/3/4），因为这套算法
 * 有几个非常反直觉的细节，凭文档实现必错：
 *
 *  - **从最后一行往上画**。解码器拿到的是 bottom-up 数据，第一行解码结果要写到
 *    缓冲区最底部，`prevline` 指的是「已经画好的下一行」。
 *  - **REPEAT 在行尾会中断**，剩余的 count 留到下一行继续。所以不能简单地
 *    按 count 连续写。
 *  - **同一 opcode 连续出现两次 Fill 时要插入一个 mix 像素**（`insertmix`），
 *    这是漏掉就会产生整片错位花纹的坑。
 *  - 32bpp 走的是**四平面分离压缩**（process_plane），平面顺序是 X/R/G/B，
 *    每个平面独立做 RLE，第一行用绝对值、后续行用相对差值。
 *
 * 输出统一为 **bottom-up 的原始字节**（和 rdesktop 内部表示一致），
 * 由调用方负责翻成 ARGB。
 */
internal object RdpRle {

    /** 输入游标，越界抛异常由 [decompress] 统一兜住 */
    private class Cursor(val data: ByteArray, var pos: Int, val end: Int) {
        fun cval(): Int {
            if (pos >= end) throw IndexOutOfBoundsException("RLE 数据读越界")
            return data[pos++].toInt() and 0xFF
        }
    }

    /**
     * @param bytesPerPixel 1/2/3/4（16bpp 传 2，24bpp 传 3，32bpp 传 4）
     * @return bottom-up 原始像素字节（长度 = width*height*bytesPerPixel），失败返回 null
     */
    fun decompress(
        input: ByteArray,
        offset: Int,
        size: Int,
        width: Int,
        height: Int,
        bytesPerPixel: Int,
    ): ByteArray? {
        if (width <= 0 || height <= 0 || size <= 0) return null
        // 防御：一张 8192x8192x4 就是 256MB，恶意/异常服务端能把 App 撑爆
        val total = width.toLong() * height.toLong() * bytesPerPixel
        if (total > 64L * 1024 * 1024) return null
        val end = minOf(offset + size, input.size)
        if (offset < 0 || offset >= end) return null
        return try {
            when (bytesPerPixel) {
                1 -> decompressN(input, offset, end, width, height, 1)
                2 -> decompressN(input, offset, end, width, height, 2)
                3 -> decompressN(input, offset, end, width, height, 3)
                4 -> decompress4(input, offset, end, width, height)
                else -> null
            }
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    // ---------------- 1/2/3 字节像素：同一套 opcode 逻辑 ----------------

    private fun readPx(out: ByteArray, at: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = v or ((out[at + i].toInt() and 0xFF) shl (i * 8))
        return v
    }

    private fun writePx(out: ByteArray, at: Int, v: Int, n: Int) {
        var x = v
        for (i in 0 until n) {
            out[at + i] = (x and 0xFF).toByte()
            x = x ushr 8
        }
    }

    private fun decompressN(
        inp: ByteArray,
        offset: Int,
        end: Int,
        width: Int,
        height: Int,
        n: Int,
    ): ByteArray? {
        val out = ByteArray(width * height * n)
        val cur = Cursor(inp, offset, end)

        var x = width
        var row = height                 // 下一行的行号（第一次 x>=width 时变成 height-1）
        var line = -1                    // 当前行起始字节偏移
        var prevLine = -1                // 已画好的下一行起始偏移（-1 = 不存在）
        var lastOpcode = -1
        var insertMix = false
        var biColour = false
        var colour1 = 0
        var colour2 = 0
        val allOnes = (1 shl (8 * n)) - 1
        var mix = allOnes
        var mask = 0
        var fomMask = 0
        var mixMask = 0

        while (cur.pos < end) {
            fomMask = 0
            var code = cur.cval()
            var opcode = code ushr 4
            var count: Int
            var offsetBase: Int

            when (opcode) {
                0xc, 0xd, 0xe -> {
                    opcode -= 6
                    count = code and 0xf
                    offsetBase = 16
                }
                0xf -> {
                    opcode = code and 0xf
                    if (opcode < 9) {
                        count = cur.cval()
                        count = count or (cur.cval() shl 8)
                    } else {
                        count = if (opcode < 0xb) 8 else 1
                    }
                    offsetBase = 0
                }
                else -> {
                    opcode = opcode shr 1
                    count = code and 0x1f
                    offsetBase = 32
                }
            }

            if (offsetBase != 0) {
                val fillOrMix = (opcode == 2 || opcode == 7)
                if (count == 0) {
                    count = if (fillOrMix) cur.cval() + 1 else cur.cval() + offsetBase
                } else if (fillOrMix) {
                    count = count shl 3
                }
            }

            // 前置数据
            when (opcode) {
                0 -> if (lastOpcode == opcode && !(x == width && prevLine < 0)) insertMix = true
                8 -> { colour1 = curPx(cur, n); colour2 = curPx(cur, n) }
                3 -> colour2 = curPx(cur, n)
                6, 7 -> { mix = curPx(cur, n); opcode -= 5 }
                9 -> { mask = 0x03; opcode = 0x02; fomMask = 3 }
                0x0a -> { mask = 0x05; opcode = 0x02; fomMask = 5 }
            }
            lastOpcode = opcode
            mixMask = 0

            // 输出
            while (count > 0) {
                if (x >= width) {
                    if (row <= 0) return null
                    x = 0
                    row--
                    prevLine = line
                    line = row * width * n
                }
                val p = line + x * n
                when (opcode) {
                    0 -> {   // Fill
                        if (insertMix) {
                            val base = if (prevLine < 0) 0 else readPx(out, prevLine + x * n, n)
                            writePx(out, p, base xor mix, n)
                            insertMix = false
                            count--
                            x++
                            if (count <= 0) continue
                        }
                        if (x >= width) continue
                        val v = if (prevLine < 0) 0 else readPx(out, prevLine + x * n, n)
                        writePx(out, p, v, n)
                        count--; x++
                    }
                    1 -> {   // Mix
                        val base = if (prevLine < 0) 0 else readPx(out, prevLine + x * n, n)
                        writePx(out, p, base xor mix, n)
                        count--; x++
                    }
                    2 -> {   // FillOrMix
                        mixMask = mixMask shl 1
                        if (mixMask == 0) {
                            mask = if (fomMask != 0) fomMask else cur.cval()
                            mixMask = 1
                        }
                        val base = if (prevLine < 0) 0 else readPx(out, prevLine + x * n, n)
                        val v = if (mask and mixMask != 0) (base xor mix) else base
                        writePx(out, p, v, n)
                        count--; x++
                    }
                    3 -> {   // Colour
                        writePx(out, p, colour2, n)
                        count--; x++
                    }
                    4 -> {   // Copy
                        writePx(out, p, curPx(cur, n), n)
                        count--; x++
                    }
                    8 -> {   // Bicolour
                        if (biColour) {
                            writePx(out, p, colour2, n)
                            biColour = false
                        } else {
                            writePx(out, p, colour1, n)
                            biColour = true
                            count++      // 一个输入像素产出两个输出像素
                        }
                        count--; x++
                    }
                    0xd -> { // White
                        writePx(out, p, allOnes, n)
                        count--; x++
                    }
                    0xe -> { // Black
                        writePx(out, p, 0, n)
                        count--; x++
                    }
                    else -> return null
                }
            }
        }
        return out
    }

    private fun curPx(cur: Cursor, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = v or (cur.cval() shl (i * 8))
        return v
    }

    // ---------------- 32bpp：四平面分离压缩 ----------------

    private fun decompress4(
        inp: ByteArray,
        offset: Int,
        end: Int,
        width: Int,
        height: Int,
    ): ByteArray? {
        val out = ByteArray(width * height * 4)
        val cur = Cursor(inp, offset, end)
        if (cur.cval() != 0x10) return null
        // 平面顺序：先写 offset 3（未用字节），再 R、G、B
        processPlane(cur, width, height, out, 3)
        processPlane(cur, width, height, out, 2)
        processPlane(cur, width, height, out, 1)
        processPlane(cur, width, height, out, 0)
        return out
    }

    /** 移植 process_plane：单通道 RLE，第一行绝对值、之后相对下一行的差值 */
    private fun processPlane(
        cur: Cursor,
        width: Int,
        height: Int,
        out: ByteArray,
        chOff: Int,
    ) {
        var row = height
        var lastLine = -1
        while (row > 0) {
            row--
            val thisLine = row * width * 4
            var colour = 0
            var x = 0
            while (x < width) {
                val code = cur.cval()
                var replen = code and 0xf
                var collen = (code ushr 4) and 0xf
                val revcode = (replen shl 4) or collen
                if (revcode in 16..47) {
                    replen = revcode
                    collen = 0
                }
                if (lastLine < 0) {
                    // 第一行：绝对值
                    while (x < width && collen > 0) {
                        colour = cur.cval()
                        out[thisLine + x * 4 + chOff] = colour.toByte()
                        x++; collen--
                    }
                    while (x < width && replen > 0) {
                        out[thisLine + x * 4 + chOff] = colour.toByte()
                        x++; replen--
                    }
                } else {
                    // 后续行：colour 保存的是「相对下一行的差值」，不是最终值
                    while (x < width && collen > 0) {
                        val raw = cur.cval()
                        colour = if (raw and 1 != 0) -((raw ushr 1) + 1) else (raw ushr 1)
                        val v = (out[lastLine + x * 4 + chOff].toInt() and 0xFF) + colour
                        out[thisLine + x * 4 + chOff] = (v and 0xFF).toByte()
                        x++; collen--
                    }
                    while (x < width && replen > 0) {
                        val v = (out[lastLine + x * 4 + chOff].toInt() and 0xFF) + colour
                        out[thisLine + x * 4 + chOff] = (v and 0xFF).toByte()
                        x++; replen--
                    }
                }
            }
            lastLine = thisLine
        }
    }
}
