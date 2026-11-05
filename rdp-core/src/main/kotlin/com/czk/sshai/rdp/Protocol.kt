package com.czk.sshai.rdp

/**
 * 传输/连接层：TPKT(ISO 8073) + X.224 + T.125(MCS) + T.124(GCC)。
 * 这三层都是固定结构的字节拼装，全部集中在这里，便于和 rdesktop/规范逐字段对照。
 */

object RdpProtocol {
    // ---- X.224 ----
    const val X224_CR = 0xE0          // Connection Request
    const val X224_CC = 0xD0          // Connection Confirm
    const val X224_DT = 0xF0          // Data

    // ---- RDP 协商 ----
    const val NEG_REQ = 0x01
    const val NEG_RSP = 0x02
    const val NEG_FAILURE = 0x03

    const val PROTOCOL_RDP = 0x00000000
    const val PROTOCOL_SSL = 0x00000001
    const val PROTOCOL_HYBRID = 0x00000002
    const val PROTOCOL_RDP_AND_SSL = 0x00000003
    const val PROTOCOL_HYBRID_EX = 0x00000008

    // ---- MCS ----
    const val MCS_EDRQ = 0x04
    const val MCS_AURQ = 0x28
    const val MCS_AUCF = 0x2E
    const val MCS_CJRQ = 0x38
    const val MCS_CJCF = 0x3E
    const val MCS_SDRQ = 0x64
    /** SendDataIndication：服务端 → 客户端方向，和 SDRQ 只差一个 bit，别混用 */
    const val MCS_SDIN = 0x68
    /** DisconnectProviderUltimatum：服务端主动断开 */
    const val MCS_DPUM = 0x21
    const val GLOBAL_CHANNEL = 1003
    const val USER_CHANNEL_BASE = 1001

    // ---- GCC user data 类型 ----
    const val CS_CORE = 0xC001
    const val CS_SECURITY = 0xC002
    const val CS_NET = 0xC003
    const val CS_CLUSTER = 0xC004
    const val SC_CORE = 0xC001
    const val SC_SECURITY = 0xC002
    const val SC_NET = 0xC003

    /** TPKT 头 + X.224 Data 头，得到可直接发出去的慢速通道帧 */
    fun tpktData(payload: ByteArray): ByteArray {
        val total = payload.size + 7
        val w = ByteWriter(total)
        w.u8(3); w.u8(0); w.u16be(total)
        w.u8(2); w.u8(0xF0); w.u8(0x80)   // X.224 DT, EOT
        w.bytes(payload)
        return w.toByteArray()
    }

    /** TPKT + X.224 Connection Request（含 Cookie 与 RDP 协商请求） */
    fun connectionRequest(user: String, requestedProtocols: Int): ByteArray {
        val cookie = "Cookie: mstshash=$user\r\n".toByteArray(Charsets.US_ASCII)
        val payloadLen = 6 + cookie.size + 8      // X.224 头 + Cookie + 协商请求
        val w = ByteWriter(payloadLen + 5)
        w.u8(3); w.u8(0); w.u16be(payloadLen + 5) // TPKT 总长 = LI(1) + payload
        w.u8(payloadLen)          // LI：从 code 到包尾
        w.u8(X224_CR)
        w.u16le(0)                // dst-ref
        w.u16le(0)                // src-ref
        w.u8(0)                   // class 0
        w.bytes(cookie)
        w.u8(NEG_REQ); w.u8(0); w.u16le(8); w.u32le(requestedProtocols)
        return w.toByteArray()
    }

    /** 解析服务端 X.224 Connection Confirm 里的协商结果；返回 null 表示服务端不支持协商 */
    fun parseNegotiation(packet: ByteArray): NegotiationResult {
        // TPKT(4) + X.224 头(7)：li、code、dst-ref(2)、src-ref(2)、class
        val negStart = 11
        if (packet.size <= negStart) {
            return NegotiationResult(null, "服务端未协商（${packet.size} 字节）")
        }
        val nr = ByteReader(packet, negStart)
        val type = nr.u8()
        if (type == NEG_FAILURE) {
            nr.u8(); nr.u16le(); nr.u32le()
            return NegotiationResult(-1, "服务端拒绝协商")
        }
        if (type != NEG_RSP) return NegotiationResult(null, "未知协商类型 $type")
        nr.u8()                          // flags
        nr.u16le()                       // length
        val selected = nr.u32le().toInt()
        return NegotiationResult(selected, null)
    }

    data class NegotiationResult(val selectedProtocol: Int?, val note: String?)

    // ---------------- BER 基础 ----------------

    private fun berLength(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), ((n ushr 8) and 0xFF).toByte(), (n and 0xFF).toByte())
    }

    private fun berInteger(v: Int): ByteArray {
        var n = 4
        while (n > 1 && (v ushr (8 * (n - 1)) and 0xFF) == 0) n--
        val body = ByteArray(n)
        for (k in 0 until n) body[k] = ((v ushr (8 * (n - 1 - k))) and 0xFF).toByte()
        val w = ByteWriter()
        w.u8(0x02); w.bytes(berLength(body.size)); w.bytes(body)
        return w.toByteArray()
    }

    private fun berOctetString(b: ByteArray): ByteArray {
        val w = ByteWriter()
        w.u8(0x04); w.bytes(berLength(b.size)); w.bytes(b)
        return w.toByteArray()
    }

    private fun domainParams(maxChannels: Int, maxUsers: Int, maxTokens: Int, maxPdu: Int): ByteArray {
        val w = ByteWriter()
        w.bytes(berInteger(maxChannels))
        w.bytes(berInteger(maxUsers))
        w.bytes(berInteger(maxTokens))
        w.bytes(berInteger(1))      // numPriorities
        w.bytes(berInteger(0))      // minThroughput
        w.bytes(berInteger(1))      // maxHeight
        w.bytes(berInteger(maxPdu))
        w.bytes(berInteger(2))      // protocolVersion
        val body = w.toByteArray()
        val out = ByteWriter()
        out.u8(0x30); out.bytes(berLength(body.size)); out.bytes(body)
        return out.toByteArray()
    }

    /** MCS Connect Initial（T.125 [APPLICATION 101]），返回完整 TPKT 帧 */
    fun mcsConnectInitial(gccUserData: ByteArray): ByteArray {
        val w = ByteWriter(1024)
        w.bytes(berOctetString(byteArrayOf(1)))     // callingDomainSelector
        w.bytes(berOctetString(byteArrayOf(1)))     // calledDomainSelector
        w.u8(0x01); w.u8(0x01); w.u8(0xFF)          // upwardFlag = TRUE (BOOLEAN)
        w.bytes(domainParams(34, 2, 0, 0xFFFF))
        w.bytes(domainParams(1, 1, 1, 0x420))
        w.bytes(domainParams(0xFFFF, 0xFC17, 0xFFFF, 0xFFFF))
        w.bytes(berOctetString(gccUserData))
        val body = w.toByteArray()

        val framed = ByteWriter(body.size + 8)
        framed.u8(0x7F); framed.u8(0x65)
        framed.bytes(berLength(body.size))
        framed.bytes(body)
        return tpktData(framed.toByteArray())
    }

    /** 从 MCS Connect Response 里取出 server GCC user data（跳过 result / connectId / domainParams） */
    fun extractServerUserData(packet: ByteArray): ByteArray {
        val r = ByteReader(packet)
        r.skip(4)                       // TPKT
        r.u8(); r.u8(); r.u8()          // X.224 DT 头（02 F0 80）
        if (r.u8() != 0x7F || r.u8() != 0x66) throw RdpProtocolException("不是 MCS Connect Response")
        skipBerLength(r)
        // result: 0x0A 01 00
        if (r.u8() != 0x0A) throw RdpProtocolException("connect response 缺 result")
        val resLen = skipBerLength(r)
        val result = r.u8()
        if (result != 0) throw RdpProtocolException("MCS connect 失败，result=$result")
        if (resLen > 1) r.skip(resLen - 1)
        // connectedCallId: INTEGER
        if (r.u8() != 0x02) throw RdpProtocolException("connect response 缺 connectId")
        val cidLen = skipBerLength(r)
        r.skip(cidLen)
        // domainParameters: SEQUENCE，整段跳过
        if (r.u8() != 0x30) throw RdpProtocolException("connect response 缺 domainParams")
        val dpLen = skipBerLength(r)
        r.skip(dpLen)
        // userData: OCTET STRING
        if (r.u8() != 0x04) throw RdpProtocolException("connect response 缺 userData")
        val udLen = skipBerLength(r)
        return r.bytes(minOf(udLen, r.remaining))
    }

    private fun skipBerLength(r: ByteReader): Int {
        val first = r.u8()
        if (first and 0x80 == 0) return first
        val n = first and 0x7F
        var v = 0
        repeat(n) { v = (v shl 8) or r.u8() }
        return v
    }

    // ---------------- MCS 控制 PDU（T.125 PER） ----------------

    fun mcsErectDomain(): ByteArray = tpktData(byteArrayOf(0x04, 0x01, 0x00, 0x01, 0x01))

    fun mcsAttachUser(): ByteArray = tpktData(byteArrayOf(0x28.toByte()))

    fun mcsJoinChannel(userId: Int, channelId: Int): ByteArray {
        val w = ByteWriter()
        w.u8(MCS_CJRQ); w.u16be(userId); w.u16be(channelId)
        return tpktData(w.toByteArray())
    }

    fun mcsSendData(userId: Int, channelId: Int, data: ByteArray): ByteArray {
        val w = ByteWriter(data.size + 12)
        w.u8(MCS_SDRQ)
        w.u16be(userId)
        w.u16be(channelId)
        w.u8(0x70)                       // dataPriority + segmentation(0b011 = 不分段)
        perLength(w, data.size)
        w.bytes(data)
        return tpktData(w.toByteArray())
    }

    /** T.125 PER 长度编码 */
    private fun perLength(w: ByteWriter, len: Int) {
        when {
            len < 0x80 -> w.u8(len)
            len < 0x4000 -> { w.u8(0x80 or (len ushr 8)); w.u8(len and 0xFF) }
            else -> { w.u8(0xC0 or (len ushr 16)); w.u8((len ushr 8) and 0xFF); w.u8(len and 0xFF) }
        }
    }

    /** 解析 MCS 控制 PDU：返回 (类型, 参数) */
    fun parseMcsControl(packet: ByteArray): Pair<Int, Int>? {
        val r = ByteReader(packet)
        if (r.remaining < 8) return null
        r.skip(7)                        // TPKT(4) + X224 DT(3)
        val op = r.u8() ushr 2
        return when (op) {
            MCS_AUCF -> {
                r.u8()                   // result
                Pair(MCS_AUCF, r.u16be())
            }
            MCS_CJCF -> {
                r.u8(); r.u16be()        // result, userId
                Pair(MCS_CJCF, r.u16be())
            }
            else -> Pair(op, -1)
        }
    }
}

/**
 * GCC（T.124）用户数据：客户端能力声明 + 服务端能力解析。
 */
object Gcc {

    private const val RDP_VERSION_5 = 0x00080004
    private const val COLOR_DEPTH_24BPP = 0xCA01
    private const val SAS_SEQUENCE_DEL = 0xAA03
    private const val HIGH_COLOR_16BPP = 16
    private const val HIGH_COLOR_24BPP = 24

    /** 客户端 TS_UD 链 */
    fun buildClientData(cfg: RdpConfig, selectedProtocol: Int, channels: List<String> = emptyList()): ByteArray {
        val ud = ByteWriter(512)
        val depth = if (cfg.colorDepth <= 16) HIGH_COLOR_16BPP else HIGH_COLOR_24BPP

        // ---- TS_UD_CS_CORE ----
        ud.u16le(RdpProtocol.CS_CORE)
        ud.u16le(216)
        ud.u32le(RDP_VERSION_5)
        ud.u16le(cfg.width)
        ud.u16le(cfg.height)
        ud.u16le(if (depth == HIGH_COLOR_16BPP) 0xCA01 else 0xCA01)   // legacy colorDepth 占位
        ud.u16le(SAS_SEQUENCE_DEL)
        ud.u32le(0x0409)                  // keyboardLayout: en-US
        ud.u32le(2600)                    // clientBuild
        ud.utf16Fixed(cfg.clientName, 16) // clientName（32 字节）
        ud.u32le(4)                       // keyboardType （注意 rdesktop 也用 4 字节，共占位 12）
        ud.u32le(0)                       // keyboardSubType
        ud.u32le(12)                      // keyboardFunctionKey
        ud.zeros(64)                      // imeFileName
        ud.u16le(0xCA01)                  // postBeta2ColorDepth
        ud.u16le(1)                       // clientProductId
        ud.u32le(0)                       // serialNumber
        ud.u16le(depth)                   // highColorDepth
        ud.u16le(if (depth == HIGH_COLOR_16BPP) 0x0002 else 0x000F)  // supportedColorDepths
        var caps = 0x0001                 // SUPPORT_ERRINFO_PDU
        if (depth >= HIGH_COLOR_24BPP) caps = caps or 0x0002          // WANT_32BPP_SESSION
        ud.u16le(caps)
        ud.zeros(64)                      // clientDigProductId
        ud.u8(0)                          // connectionType
        ud.u8(0)                          // pad1Octet
        ud.u32le(selectedProtocol)        // serverSelectedProtocol

        // ---- TS_UD_CS_CLUSTER ----
        ud.u16le(RdpProtocol.CS_CLUSTER)
        ud.u16le(12)
        ud.u32le(0x0D)                    // REDIRECTION_SUPPORTED | VERSION4<<2
        ud.u32le(0)                       // RedirectedSessionId

        // ---- TS_UD_CS_SEC ----
        ud.u16le(RdpProtocol.CS_SECURITY)
        ud.u16le(12)
        ud.u32le(0x03)                    // 40bit | 128bit
        ud.u32le(0)

        // ---- TS_UD_CS_NET（只有真正要开虚拟通道时才发）----
        if (channels.isNotEmpty()) {
            ud.u16le(RdpProtocol.CS_NET)
            ud.u16le(8 + channels.size * 12)
            ud.u32le(channels.size)
            for (name in channels) {
                val nm = name.padEnd(8, '\u0000').take(8).toByteArray(Charsets.US_ASCII)
                ud.bytes(nm)
                ud.u32be(0x80800000.toInt())  // CHANNEL_OPTION_INITIALIZED | COMPRESS_RDP
            }
        }

        val userData = ud.toByteArray()
        val gccLen = 22 + userData.size     // 6(OID) + 2(len) + 14(固定头) + userData

        val w = ByteWriter(gccLen)
        w.u16be(5)                        // OID 长度
        w.u16be(0x14)
        w.u8(0x7C)
        w.u16be(1)
        w.u16be(gccLen or 0x8000)          // ConferenceCreateRequest 剩余长度
        w.u16be(8)
        w.u16be(16)
        w.u8(0)
        w.u16le(0xC001)
        w.u8(0)
        w.u32le(0x61637544)               // "Duca" OEM ID
        w.u16be((gccLen - 14) or 0x8000)
        w.bytes(userData)
        return w.toByteArray()
    }

    data class ServerData(
        val width: Int,
        val height: Int,
        val colorDepth: Int,
        val rdpVersion: Int,
        val keySize: Int,
        val cryptLevel: Int,
        val serverRandom: ByteArray,
        val modulus: ByteArray,
        val exponent: ByteArray,
        val channelIds: List<Int>,
        val serverProduct: String,
    )

    fun parseServerData(data: ByteArray): ServerData {
        // 服务端 GCC 是 T.124 PER 编码，前缀长度随实现而变。
        // 先尝试标准 TLV 链，失败则直接按 RDP4 公钥魔数 "RSA1" 定位
        // —— 这个魔数是协议固定值，比猜偏移可靠得多。
        for (skip in 0..minOf(48, maxOf(0, data.size - 8))) {
            for (mode in 0 until 4) {
                val res = tryParseServerChain(data, skip, mode)
                if (res != null && res.modulus.isNotEmpty()) return res
            }
        }
        return parseByRsaMagic(data)
    }

    private fun indexOfMagic(data: ByteArray, magic: ByteArray): Int {
        outer@ for (i in 0..data.size - magic.size) {
            for (j in magic.indices) if (data[i + j] != magic[j]) continue@outer
            return i
        }
        return -1
    }

    /**
     * 直接按 "RSA1" 定位服务端公钥。
     * 布局（MS-RDPBCGR 2.2.1.4.3.1.1）：
     *   serverRandom(32) serverCertLen(4) flags(4) unknown(8)
     *   tag(2) len(2) "RSA1" keyLen(4) bitLen(4) dataLen(4) exponent(4) modulus(keyLen-8)
     */
    private fun parseByRsaMagic(data: ByteArray): ServerData {
        val rsa = indexOfMagic(data, byteArrayOf(0x52, 0x53, 0x41, 0x31))
        if (rsa < 20) throw RdpProtocolException("服务端数据里找不到 RSA 公钥")
        val keyLen = leU32(data, rsa + 4)
        val modLen = keyLen - 8
        if (modLen <= 0 || rsa + 20 + modLen > data.size) throw RdpProtocolException("RSA 公钥长度异常 $keyLen")
        val exponent = data.copyOfRange(rsa + 16, rsa + 20)
        val modulus = data.copyOfRange(rsa + 20, rsa + 20 + modLen)
        // cert 起点 = rsa - 16（flags 4 + unknown 8 + tag/len 4），
        // serverRandom 紧挨在 cert 之前 32 字节。
        val certStart = rsa - 16
        val random = if (certStart - 32 >= 0) {
            data.copyOfRange(certStart - 32, certStart)
        } else ByteArray(32)
        return ServerData(
            width = 0, height = 0, colorDepth = 0, rdpVersion = 0,
            keySize = 2, cryptLevel = 3,
            serverRandom = random, modulus = modulus, exponent = exponent,
            channelIds = emptyList(), serverProduct = "",
        )
    }

    private fun leU32(d: ByteArray, at: Int): Int {
        if (at + 4 > d.size) return 0
        return (d[at].toInt() and 0xFF) or
            ((d[at + 1].toInt() and 0xFF) shl 8) or
            ((d[at + 2].toInt() and 0xFF) shl 16) or
            ((d[at + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * mode: bit0 = tag 大端, bit1 = length 大端
     * 只有整条链都解释得通、且包含 SC_SECURITY 或 SC_CORE 才认为命中。
     */
    private fun tryParseServerChain(data: ByteArray, start: Int, mode: Int): ServerData? {
        val tagBe = mode and 1 != 0
        val lenBe = mode and 2 != 0
        val r = ByteReader(data, start)
        var keySize = 0
        var cryptLevel = -1
        var random = ByteArray(0)
        var modulus = ByteArray(0)
        var exponent = byteArrayOf(0x01, 0x00, 0x01, 0x00)
        var found = 0
        var guard = 0

        while (r.remaining >= 4 && guard++ < 16) {
            val tag = if (tagBe) r.u16be() else r.u16le()
            val len = if (lenBe) r.u16be() else r.u16le()
            if (tag !in 0xC001..0xC008) return null
            if (len < 4 || len - 4 > r.remaining) return null
            val end = r.pos + len - 4
            when (tag) {
                RdpProtocol.SC_CORE -> {
                    // 服务端 SC_CORE：version(4) clientRequestedProtocols(4) earlyCapabilityFlags(4)
                    found++
                }
                RdpProtocol.SC_SECURITY -> {
                    if (len - 4 < 16) return null
                    keySize = r.u32le().toInt()
                    cryptLevel = r.u32le().toInt()
                    val randLen = r.u32le().toInt()
                    val certLen = r.u32le().toInt()
                    if (randLen < 0 || randLen > r.remaining) return null
                    random = r.bytes(randLen)
                    if (certLen < 0 || certLen > r.remaining) return null
                    val cert = r.bytes(certLen)
                    parseServerCert(cert)?.let { modulus = it.first; exponent = it.second }
                    found++
                }
            }
            r.pos = end
        }
        if (found == 0) return null
        if (cryptLevel >= 0 && modulus.isEmpty()) {
            // 有安全数据但没挖出 RSA 公钥：可能是 X.509 证书链，仍算解析成功
            // （TLS 模式下用不到 RSA）
        }
        return ServerData(
            width = 0, height = 0, colorDepth = 0, rdpVersion = 0,
            keySize = if (keySize == 0) 2 else keySize,
            cryptLevel = if (cryptLevel < 0) 0 else cryptLevel,
            serverRandom = random,
            modulus = modulus, exponent = exponent,
            channelIds = emptyList(), serverProduct = "",
        )
    }

    /** cert 里挖 RSA 公钥：RDP4 专有结构（tag 0x0006）。X.509 情况返回 null。 */
    private fun parseServerCert(cert: ByteArray): Pair<ByteArray, ByteArray>? {
        val r = ByteReader(cert)
        if (r.remaining < 4) return null
        val flags = r.u32le()
        if (flags and 1L == 0L) return null      // X.509，交给 TLS 处理
        r.skip(minOf(8, r.remaining))
        while (r.remaining >= 4) {
            val tag = r.u16le()
            val len = r.u16le()
            val end = r.pos + len
            if (tag == 0x0006 && r.remaining >= 16) {
                val magic = r.u32le()
                val modLen = r.u32le().toInt() - 8
                r.skip(minOf(8, r.remaining))
                val exp = r.bytes(4)
                if (modLen > 0 && modLen <= r.remaining) {
                    val mod = r.bytes(modLen)
                    return Pair(mod, exp)
                }
            }
            if (end <= r.limit) r.pos = end else break
        }
        return null
    }
}
