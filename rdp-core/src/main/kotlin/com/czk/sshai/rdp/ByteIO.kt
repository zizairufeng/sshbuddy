package com.czk.sshai.rdp

/**
 * 小端/大端混合的字节写入器。
 * RDP 协议里 little-endian 占绝大多数（PDU 结构体），大端只用于 TPKT 长度、
 * T.125 PER 字段和 HMAC 长度前缀，所以两种都要有。
 */
class ByteWriter(initial: Int = 512) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) {
            var cap = buf.size
            while (cap < size + n) cap *= 2
            buf = buf.copyOf(cap)
        }
    }

    fun u8(v: Int) = apply { ensure(1); buf[size++] = (v and 0xFF).toByte() }

    fun u16le(v: Int) = apply { u8(v and 0xFF); u8((v ushr 8) and 0xFF) }

    fun u16be(v: Int) = apply { u8((v ushr 8) and 0xFF); u8(v and 0xFF) }

    fun u32le(v: Int) = apply { u16le(v and 0xFFFF); u16le((v ushr 16) and 0xFFFF) }

    fun u32be(v: Int) = apply { u16be((v ushr 16) and 0xFFFF); u16be(v and 0xFFFF) }

    fun bytes(b: ByteArray) = apply {
        ensure(b.size)
        System.arraycopy(b, 0, buf, size, b.size)
        size += b.size
    }

    fun zeros(n: Int) = apply { ensure(n); size += n }

    /** UTF-16LE，不含结尾 NUL */
    fun utf16le(s: String) = apply {
        for (c in s) { u8(c.code and 0xFF); u8((c.code ushr 8) and 0xFF) }
    }

    /** UTF-16LE，尾部补 NUL 直到占满 [chars] 个字符（2*chars 字节） */
    fun utf16Fixed(s: String, chars: Int) = apply {
        var written = 0
        for (c in s) {
            if (written >= chars) break
            u8(c.code and 0xFF); u8((c.code ushr 8) and 0xFF); written++
        }
        while (written < chars) { u8(0); u8(0); written++ }
    }

    fun overwrite16le(at: Int, v: Int) {
        buf[at] = (v and 0xFF).toByte()
        buf[at + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** 小端读的游标，越界直接抛 [RdpProtocolException]，避免静默读到脏数据。 */
class ByteReader(val data: ByteArray, var pos: Int = 0, val limit: Int = data.size) {

    val remaining: Int get() = limit - pos

    private fun need(n: Int) {
        if (pos + n > limit) throw RdpProtocolException("读越界：需要 $n 字节，剩余 $remaining")
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun u16le(): Int {
        need(2)
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u16be(): Int {
        need(2)
        val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun u32le(): Long {
        need(4)
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        pos += 4
        return v
    }

    fun u32be(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        pos += 4
        return v
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun skip(n: Int) {
        need(n)
        pos += n
    }

    fun peekU8(at: Int): Int = if (at < limit) data[at].toInt() and 0xFF else -1
}

class RdpProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 字节数组十六进制，日志用 */
fun ByteArray.hex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
    return sb.toString()
}

fun ByteArray.hexDump(limit: Int = 256): String {
    val n = minOf(size, limit)
    val sb = StringBuilder()
    var i = 0
    while (i < n) {
        sb.append("%04x  ".format(i))
        val end = minOf(i + 16, n)
        for (j in i until end) sb.append("%02x ".format(this[j].toInt() and 0xFF))
        for (j in end until i + 16) sb.append("   ")
        sb.append(" |")
        for (j in i until end) {
            val c = this[j].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        sb.append("|\n")
        i += 16
    }
    if (size > limit) sb.append("... (${size - limit} 字节未显示)\n")
    return sb.toString()
}
