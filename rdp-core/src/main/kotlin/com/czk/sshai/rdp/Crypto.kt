package com.czk.sshai.rdp

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** RC4 流密码（RDP 标准安全层用它加密 PDU）。有状态，必须按同一顺序加/解密。 */
class Rc4(key: ByteArray) {
    private val s = IntArray(256)
    private var i = 0
    private var j = 0

    init {
        reset(key)
    }

    fun reset(key: ByteArray) {
        for (n in 0 until 256) s[n] = n
        var jj = 0
        for (n in 0 until 256) {
            jj = (jj + s[n] + (key[n % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[n]; s[n] = s[jj]; s[jj] = t
        }
        i = 0; j = 0
    }

    fun process(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (n in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            val k = s[(s[i] + s[j]) and 0xFF]
            out[n] = (data[n].toInt() xor k).toByte()
        }
        return out
    }

    fun process(data: ByteArray, offset: Int, length: Int): ByteArray =
        process(data.copyOfRange(offset, offset + length))
}

/** MD4：JDK 不提供，NTLM 的 NT hash 必须要，自己实现（RFC 1320）。 */
object Md4 {
    fun digest(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8
        val padLen = ((55 - input.size) % 64 + 64) % 64
        val msg = ByteArray(input.size + padLen + 8)
        System.arraycopy(input, 0, msg, 0, input.size)
        msg[input.size] = 0x80.toByte()
        var n = msg.size - 8
        for (k in 0 until 8) msg[n + k] = ((bitLen ushr (8 * k)) and 0xFF).toByte()

        var a = 0x67452301
        var b = 0xEFCDAB89.toInt()
        var c = 0x98BADCFE.toInt()
        var d = 0x10325476

        val x = IntArray(16)
        var off = 0
        while (off < msg.size) {
            for (k in 0 until 16) {
                x[k] = (msg[off + 4 * k].toInt() and 0xFF) or
                    ((msg[off + 4 * k + 1].toInt() and 0xFF) shl 8) or
                    ((msg[off + 4 * k + 2].toInt() and 0xFF) shl 16) or
                    ((msg[off + 4 * k + 3].toInt() and 0xFF) shl 24)
            }
            val aa = a; val bb = b; val cc = c; val dd = d
            fun f(u: Int, v: Int, w: Int) = (u and v) or (u.inv() and w)
            fun g(u: Int, v: Int, w: Int) = (u and v) or (u and w) or (v and w)
            fun h(u: Int, v: Int, w: Int) = u xor v xor w
            fun rol(v: Int, s: Int) = (v shl s) or (v ushr (32 - s))

            for (k in 0 until 16) {
                val idx = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)[k]
                val sft = intArrayOf(3, 7, 11, 19)[k % 4]
                val tmp = rol(a + f(b, c, d) + x[idx], sft)
                a = d; d = c; c = b; b = tmp
            }
            for (k in 0 until 16) {
                val idx = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)[k]
                val sft = intArrayOf(3, 5, 9, 13)[k % 4]
                val tmp = rol(a + g(b, c, d) + x[idx] + 0x5A827999, sft)
                a = d; d = c; c = b; b = tmp
            }
            for (k in 0 until 16) {
                val idx = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)[k]
                val sft = intArrayOf(3, 9, 11, 15)[k % 4]
                val tmp = rol(a + h(b, c, d) + x[idx] + 0x6ED9EBA1, sft)
                a = d; d = c; c = b; b = tmp
            }
            a += aa; b += bb; c += cc; d += dd
            off += 64
        }
        val out = ByteArray(16)
        val vals = intArrayOf(a, b, c, d)
        for (k in 0 until 4) for (m in 0 until 4) out[4 * k + m] = ((vals[k] ushr (8 * m)) and 0xFF).toByte()
        return out
    }
}

object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun md5(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun sha1(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun ntHash(password: String): ByteArray = Md4.digest(password.toByteArray(Charsets.UTF_16LE))

    /** RDP 的 48 字节密钥派生（SSLv3 变体，用 'A'/'X' 作为 salt 前缀）。 */
    fun hash48(input: ByteArray, salt1: ByteArray, salt2: ByteArray, salt: Char): ByteArray {
        require(input.size == 48)
        val out = ByteArray(48)
        for (i in 0 until 3) {
            val pad = ByteArray(i + 1) { (salt.code + i).toByte() }
            val shasig = sha1(pad, input, salt1, salt2)
            val md5sig = md5(input, shasig)
            System.arraycopy(md5sig, 0, out, i * 16, 16)
        }
        return out
    }

    fun hash16(input: ByteArray, salt1: ByteArray, salt2: ByteArray): ByteArray =
        md5(input, salt1, salt2)

    /**
     * RDP 的 RSA 加密（MS-RDPBCGR 5.3.4）。
     * 注意两点与常见 RSA 不同：模数/明文/密文全部按**小端**解释，
     * 且**不做 PKCS#1 填充**（明文就是 client random 原值）。
     */
    fun rsaEncrypt(secret: ByteArray, modulusLe: ByteArray, exponentLe: ByteArray): ByteArray {
        val m = BigInteger(1, modulusLe.reversedArray())
        val e = BigInteger(1, exponentLe.reversedArray())
        val s = BigInteger(1, secret.reversedArray())
        if (s >= m) throw RdpProtocolException("client random 超出模数范围")
        val c = s.modPow(e, m)
        val be = c.toByteArray()          // 大端，可能带符号前导 0
        val out = ByteArray(modulusLe.size)
        var i = be.size - 1
        var o = 0
        while (i >= 0 && o < out.size) { out[o++] = be[i--] }
        return out
    }

    fun constTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }
}
