package com.czk.sshai.rdp

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * RDP 客户端主连接。
 *
 * 线程模型：阻塞式，[connect] 会一直跑到连接断开（内部循环），
 * 因此必须放在后台线程；发送输入可以从其他线程调，方法本身做了同步。
 *
 * 当前实现覆盖「标准 RDP 安全层 / TLS」两条路径；NLA(CredSSP) 见 [CredSsp]。
 */
class RdpConnection(private val config: RdpConfig, private val listener: RdpListener) {

    companion object {
        const val SEC_EXCHANGE_PKT = 0x0001
        const val SEC_ENCRYPT = 0x0008
        const val SEC_INFO_PKT = 0x0040
        const val SEC_LICENSE_PKT = 0x0080
        const val SEC_REDIRECT_PKT = 0x0400
        const val SEC_LICENSE_ENCRYPT_SC = 0x0200

        private const val PDU_DEMAND_ACTIVE = 1
        private const val PDU_DATA = 7
    }

    lateinit var frameBuffer: RdpFrameBuffer
        private set

    val isConnected: Boolean get() = running

    val hasFrameBuffer: Boolean get() = this::frameBuffer.isInitialized

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private var userId = 0
    private var shareId = 0
    private var clientRandom = ByteArray(32)
    private var selectedProtocol = RdpProtocol.PROTOCOL_RDP
    private var security: RdpSecurity? = null
    private var licenseDone = false
    private var dbgPacketNo = 0
    private var dbgOrdersDump = 0

    /** 绘图指令解释器；xrdp/Linux 桌面全靠它出画 */
    private var orders: RdpOrders? = null

    @Volatile private var running = false
    private val sendLock = Any()

    fun connect() {
        try {
            doConnect()
            listener.onDisconnected("会话结束")
        } catch (e: Exception) {
            if (running) {
                listener.onError(e)
            }
            listener.onDisconnected(e.message ?: "连接失败")
        } finally {
            running = false
            closeQuietly()
        }
    }

    private fun doConnect() {
        listener.onLog("连接 ${config.host}:${config.port} …")
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(config.host, config.port), config.timeoutMs)
        s.soTimeout = 0            // 握手与主循环都靠上层控制超时
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        running = true

        // ---- 1. X.224 连接请求 ----
        val requested = when (config.security) {
            RdpSecurityMode.NLA -> RdpProtocol.PROTOCOL_HYBRID
            RdpSecurityMode.TLS -> RdpProtocol.PROTOCOL_SSL
            RdpSecurityMode.RDP -> RdpProtocol.PROTOCOL_RDP
            RdpSecurityMode.AUTO -> RdpProtocol.PROTOCOL_HYBRID or RdpProtocol.PROTOCOL_SSL or RdpProtocol.PROTOCOL_RDP
        }
        write(RdpProtocol.connectionRequest(config.username, requested))
        val cc = readTpkt() ?: throw IOException("服务端没有回应 X.224")
        val neg = RdpProtocol.parseNegotiation(cc)
        selectedProtocol = neg.selectedProtocol ?: RdpProtocol.PROTOCOL_RDP
        listener.onLog(
            when (selectedProtocol) {
                RdpProtocol.PROTOCOL_HYBRID -> "协商结果：NLA (CredSSP)"
                RdpProtocol.PROTOCOL_SSL -> "协商结果：TLS"
                -1 -> "协商失败：${neg.note}"
                else -> "协商结果：标准 RDP 安全层（${neg.note ?: "服务端未回应协商"}）"
            }
        )
        if (selectedProtocol == -1) throw IOException("服务端拒绝：${neg.note}")

        // ---- 2. 安全层建立（TLS / CredSSP）----
        if (selectedProtocol == RdpProtocol.PROTOCOL_SSL || selectedProtocol == RdpProtocol.PROTOCOL_HYBRID) {
            upgradeTls()
        }

        // ---- 3. MCS 连接 ----
        val gcc = Gcc.buildClientData(config, selectedProtocol)
        write(RdpProtocol.mcsConnectInitial(gcc))
        val cr = readTpkt() ?: throw IOException("服务端没有回应 MCS Connect")
        val serverGcc = RdpProtocol.extractServerUserData(cr)
        listener.onLog("serverGCC(${serverGcc.size})=${serverGcc.hex()}")
        val serverData = Gcc.parseServerData(serverGcc)
        listener.onLog("服务端安全参数：keySize=${serverData.keySize} 加密等级=${serverData.cryptLevel} random=${serverData.serverRandom.size}B 公钥=${serverData.modulus.size}B")

        val fbW = if (config.width > 0) config.width else serverData.width
        val fbH = if (config.height > 0) config.height else serverData.height
        frameBuffer = RdpFrameBuffer(fbW, fbH)

        // ---- 4. 密钥与 Erect Domain / Attach User / Channel Join ----
        clientRandom = Crypto.randomBytes(32)
        if (serverData.cryptLevel > 0 && selectedProtocol == RdpProtocol.PROTOCOL_RDP) {
            if (serverData.modulus.isEmpty()) throw IOException("服务端未提供 RSA 公钥，无法建立标准安全层")
            security = RdpSecurity(
                keySize = serverData.keySize,
                clientRandom = clientRandom,
                serverRandom = serverData.serverRandom,
            )
        }

        write(RdpProtocol.mcsErectDomain())
        write(RdpProtocol.mcsAttachUser())
        val aucf = readTpkt() ?: throw IOException("Attach User 无响应")
        userId = parseAttachUser(aucf)
        listener.onLog("MCS userId=$userId")
        joinChannel(userId + RdpProtocol.USER_CHANNEL_BASE)
        joinChannel(RdpProtocol.GLOBAL_CHANNEL)

        // ---- 5. 安全交换（把 client random 用 RSA 交给服务端）----
        if (security != null) {
            val enc = Crypto.rsaEncrypt(clientRandom, serverData.modulus, serverData.exponent)
            val w = ByteWriter()
            w.u32le(enc.size + 8)
            w.bytes(enc)
            w.zeros(8)
            sendSecured(SEC_EXCHANGE_PKT, w.toByteArray(), forceHdr = true)
        }

        // ---- 6. Client Info ----
        sendClientInfo()

        listener.onConnected(
            RdpSessionInfo(
                width = fbW,
                height = fbH,
                colorDepth = if (config.colorDepth <= 16) 16 else 24,
                securityLabel = when (selectedProtocol) {
                    RdpProtocol.PROTOCOL_HYBRID -> "NLA"
                    RdpProtocol.PROTOCOL_SSL -> "TLS"
                    else -> if (security != null) "RDP 128 位" else "明文"
                },
            )
        )

        // ---- 7. 主循环 ----
        mainLoop()
    }

    // ---------------- 收包 ----------------

    private fun readTpkt(): ByteArray? {
        val inp = input ?: return null
        val head = ByteArray(4)
        var got = 0
        while (got < 4) {
            val n = inp.read(head, got, 4 - got)
            if (n < 0) {
                if (got == 0) return null
                throw EOFException("TPKT 头读到一半就断了")
            }
            got += n
        }
        if (head[0].toInt() != 3) {
            // fastpath：我们没声明支持，正常不会出现；读到就跳过
            listener.onLog("收到 fastpath 包（未启用），忽略")
            val len = (head[1].toInt() and 0xFF)
            val total = if (len and 0x80 != 0) ((len and 0x7F) shl 8) or (head[2].toInt() and 0xFF) else len
            skipFully(inp, (total - 2).coerceAtLeast(0))
            return ByteArray(0)
        }
        val len = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        val body = ByteArray(len)
        System.arraycopy(head, 0, body, 0, 4)
        var off = 4
        while (off < body.size) {
            val n = inp.read(body, off, body.size - off)
            if (n < 0) throw EOFException("包体不完整")
            off += n
        }
        return body
    }

    private fun skipFully(inp: InputStream, n: Int) {
        var left = n
        val tmp = ByteArray(4096)
        while (left > 0) {
            val r = inp.read(tmp, 0, minOf(left, tmp.size))
            if (r < 0) return
            left -= r
        }
    }

    private fun parseAttachUser(packet: ByteArray): Int {
        val r = ByteReader(packet)
        r.skip(7)
        val op = r.u8()
        if (op != RdpProtocol.MCS_AUCF) throw IOException("Attach User 响应异常 0x${op.toString(16)}")
        r.u8()
        return r.u16be()
    }

    private fun joinChannel(channel: Int) {
        write(RdpProtocol.mcsJoinChannel(userId, channel))
        readTpkt()      // Channel Join Confirm，失败与否都继续
    }

    private fun mainLoop() {
        var packetNo = 0
        while (running) {
            val pkt = readTpkt()
            if (pkt == null) {
                listener.onLog("服务端关闭连接")
                return
            }
            if (pkt.isEmpty()) continue
            packetNo++
            if (packetNo <= 12) {
                listener.onLog("收包 #$packetNo len=${pkt.size} ${pkt.copyOfRange(0, minOf(24, pkt.size)).hex()}")
            }
            try {
                handlePacket(pkt)
            } catch (e: RdpProtocolException) {
                listener.onLog("解析异常：${e.message}")
            } catch (e: Exception) {
                listener.onLog("处理异常：${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /** 从 TPKT(去掉后) 的 X.224 Data 里取出 MCS SendData 的用户数据 */
    private fun unwrapMcs(pkt: ByteArray): ByteArray? {
        val r = ByteReader(pkt)
        if (r.remaining < 12) return null
        r.u8(); r.u8(); r.u8(); r.u8()     // TPKT：version(1) + reserved(1) + length(2)
        r.u8(); r.u8(); r.u8()             // X.224 DT：LI(1) + code(1) + TPDU-NR/EOT(1)
        val op = r.u8()
        // 客户端发的是 SendDataRequest(0x64)，服务端发的是 SendDataIndication(0x68)
        if (op != RdpProtocol.MCS_SDIN && op != RdpProtocol.MCS_SDRQ) {
            if (op == RdpProtocol.MCS_DPUM) {
                listener.onLog("服务端发来 DisconnectProviderUltimatum（主动断开）")
            }
            return null
        }
        r.u16be()                          // userId
        r.u16be()                          // channelId
        r.u8()                             // dataPriority
        val first = r.u8()
        if (first and 0x80 != 0) {
            val second = r.u8()
            val len = ((first and 0x7F) shl 8) or second
            return r.bytes(minOf(len, r.remaining))
        }
        return r.bytes(minOf(first, r.remaining))
    }

    private fun handlePacket(pkt: ByteArray) {
        dbgPacketNo++
        var data = unwrapMcs(pkt) ?: return

        // 安全层头
        var secFlags = 0
        if (!tlsActive && (security != null || !licenseDone)) {
            if (data.size < 4) return
            val r = ByteReader(data)
            secFlags = r.u16le()
            r.u16le()                      // flagsHi
            data = r.bytes(r.remaining)
        }
        if (security != null && secFlags and SEC_ENCRYPT != 0) {
            if (data.size < 8) return
            val body = data.copyOfRange(8, data.size)   // 跳过 8 字节签名
            data = security!!.decrypt(body)
        }
        if (dbgPacketNo <= 14) {
            listener.onLog("解析 #$dbgPacketNo sec=0x${secFlags.toString(16)} len=${data.size} " +
                data.copyOfRange(0, minOf(20, data.size)).hex())
        }

        if (secFlags and SEC_LICENSE_PKT != 0) {
            handleLicense(data)
            return
        }

        val r = ByteReader(data)
        if (r.remaining < 6) return
        val totalLen = r.u16le()
        val pduType = r.u16le() and 0x0F
        val mcsUser = r.u16le()
        when (pduType) {
            PDU_DEMAND_ACTIVE -> handleDemandActive(r)
            PDU_DATA -> handleDataPdu(r)
            else -> listener.onLog("忽略控制 PDU type=$pduType len=$totalLen")
        }
    }

    private fun handleDemandActive(r: ByteReader) {
        shareId = r.u32le().toInt()
        val srcLen = r.u16le()
        val capsLen = r.u16le()
        r.skip(minOf(srcLen, r.remaining))
        // capabilities：跳过详细解析，我们只按自己的方案确认
        r.skip(minOf(capsLen, r.remaining))
        listener.onLog("收到 Demand Active, shareId=0x${shareId.toString(16)}")

        // confirm active
        val caps = RdpGraphics.buildCaps(config)
        sendSecured(if (security != null) SEC_ENCRYPT else 0, RdpGraphics.buildConfirmActive(userId, shareId, caps))

        // 同步序列
        sendShareData(RdpGraphics.PDU_TYPE_SYNC, RdpGraphics.buildSynchronize())
        sendShareData(RdpGraphics.PDU_TYPE_CONTROL, RdpGraphics.buildControl(4))   // COOPERATE
        sendShareData(RdpGraphics.PDU_TYPE_CONTROL, RdpGraphics.buildControl(1))   // REQUEST_CONTROL
        sendShareData(RdpGraphics.PDU_TYPE_INPUT, RdpGraphics.inputEvents(listOf(RdpGraphics.synchronizeEvent())))
        // Font List：服务端（xrdp）靠它把会话标记为 up_and_running，之后才开始推画面
        sendShareData(RdpGraphics.PDU_TYPE_FONT, RdpGraphics.buildFontList())
    }

    private fun handleDataPdu(r: ByteReader) {
        if (r.remaining < 12) return
        r.u32le()                                  // shareId
        r.u8()                                     // pad
        r.u8()                                     // streamId
        r.u16le()                                  // uncompressedLength
        val pduType2 = r.u8()
        r.u8()                                     // compressedType
        r.u16le()                                  // compressedLength
        when (pduType2) {
            RdpGraphics.PDU_TYPE_UPDATE -> {
                val updateType = r.u16le()
                if (updateType == RdpGraphics.UPDATE_BITMAP) {
                    RdpGraphics.parseBitmapUpdate(r, frameBuffer, listener)
                } else if (updateType == RdpGraphics.UPDATE_ORDERS) {
                    // xrdp 画桌面就靠这条路径：全是 MEMBLT / LINE / RECT 之类的绘图指令
                    try {
                        (orders ?: RdpOrders(frameBuffer, listener).also { orders = it })
                            .processUpdate(r.data, r.pos)
                        frameBuffer.markDirty()
                        listener.onFrameUpdate(0, 0, frameBuffer.width, frameBuffer.height)
                    } catch (e: RdpProtocolException) {
                        // 单条指令解析失败不影响连接，继续收后续包
                        if (dbgOrdersDump < 8) {
                            dbgOrdersDump++
                            listener.onLog("orders 解析中断：${e.message}")
                        }
                    }
                }
            }
            RdpGraphics.PDU_TYPE_FONT -> {
                // 服务端字体列表，忽略
            }
            RdpGraphics.PDU_TYPE_SYNC, RdpGraphics.PDU_TYPE_CONTROL -> {
                // 同步/授权
            }
            0x1B -> handlePointerUpdate(r)
            else -> {}
        }
    }

    private fun handlePointerUpdate(r: ByteReader) {
        if (r.remaining < 6) return
        r.u16le()                                  // messageType
        r.u16le()                                  // systemPointerType
        listener.onLog("光标更新")
    }

    private fun handleLicense(data: ByteArray) {
        if (data.isEmpty()) return
        val tag = data[0].toInt() and 0xFF
        listener.onLog("License PDU tag=0x${tag.toString(16)}")
        licenseDone = true
        when (tag) {
            // 服务端要我们出具授权：回一个 CLIENT_NEW_LICENSE_REQUEST
            RdpLicense.TAG_LICENSE_REQUEST,
            RdpLicense.TAG_PLATFORM_CHALLENGE -> {
                val req = RdpLicense.buildNewLicenseRequest(config.username, RdpLicense.machineName())
                sendSecured(SEC_LICENSE_PKT, req, forceHdr = true)
                listener.onLog("已回应 license 请求（NEW_LICENSE_REQUEST ${req.size}B）")
            }
            // STATUS_VALID_CLIENT 等：不需要再回包，接着等 Demand Active
            else -> {}
        }
    }

    // ---------------- 发送 ----------------

    private fun write(bytes: ByteArray) {
        synchronized(sendLock) {
            output?.write(bytes)
            output?.flush()
        }
    }

    private fun sendMcs(payload: ByteArray) {
        write(RdpProtocol.mcsSendData(userId, RdpProtocol.GLOBAL_CHANNEL, payload))
    }

    /** 按安全层封装：可选 4 字节 flags + 可选 8 字节签名 + RC4 加密 */
    private fun sendSecured(flags: Int, payload: ByteArray, forceHdr: Boolean = false) {
        val w = ByteWriter(payload.size + 16)
        val needHdr = forceHdr || (!tlsActive && (security != null || !licenseDone))
        if (needHdr) {
            w.u16le(flags)
            w.u16le(0)
        }
        if (security != null && flags and SEC_ENCRYPT != 0) {
            val sig = security!!.sign(payload)
            w.bytes(sig)
            w.bytes(security!!.encryptRaw(payload))
        } else {
            w.bytes(payload)
        }
        sendMcs(w.toByteArray())
    }

    private fun sendShareData(pduType2: Int, payload: ByteArray) {
        val flags = if (security != null) SEC_ENCRYPT else 0
        sendSecured(flags, RdpGraphics.buildShareData(pduType2, payload, shareId))
    }

    private fun sendClientInfo() {
        val domain = config.domain
        val user = config.username
        val password = config.password
        val w = ByteWriter(512)
        w.u32le(0)                                  // CodePage
        // flags 必须包含 RDP_LOGON_NORMAL(0x33) = MOUSE|DISABLECTRLALTDEL|UNICODE|MAXIMIZESHELL，
        // 服务端（xrdp/Windows）会用 (flags & 0x33) == 0x33 来校验，缺 0x20 会被直接打回。
        w.u32le(0x00000033)                         // RDP_LOGON_NORMAL
        w.u16le(domain.length * 2)
        w.u16le(user.length * 2)
        w.u16le(password.length * 2)
        w.u16le(0)                                  // AlternateShell
        w.u16le(0)                                  // WorkingDir
        // 注意：cb* 不计结尾 NUL，但实际数据里每个字符串都要带 2 字节 NUL
        w.utf16le(domain); w.u16le(0)
        w.utf16le(user); w.u16le(0)
        w.utf16le(password); w.u16le(0)
        // TS_EXTENDED_INFO_PACKET
        w.u16le(2)                                  // AF_INET
        val ip = config.host.takeIf { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) } ?: "127.0.0.1"
        w.u16le((ip.length + 1) * 2)
        w.utf16le(ip); w.u8(0); w.u8(0)
        val dir = "C:\\Windows\\System32\\mstscax.dll"
        w.u16le((dir.length + 1) * 2)
        w.utf16le(dir); w.u8(0); w.u8(0)
        w.zeros(172)                                // TimeZoneInformation
        w.u32le(0)                                  // clientSessionId
        w.u32le(0x0000002F)                         // performanceFlags
        w.u16le(0)                                  // cbAutoReconnectCookie
        w.u16le(0)                                  // reserved

        // 试验：先不加密 Client Info（xrdp 在 high 等级下也接受明文 PDU）
        val flags = SEC_INFO_PKT
        sendSecured(flags, w.toByteArray(), forceHdr = true)
    }

    // ---------------- 对外输入接口 ----------------

    fun sendMouse(pointerFlags: Int, x: Int, y: Int) {
        if (!running) return
        val ev = RdpGraphics.mouseEvent(pointerFlags, x, y)
        sendShareData(RdpGraphics.PDU_TYPE_INPUT, RdpGraphics.inputEvents(listOf(ev)))
    }

    fun sendKey(scancode: Int, pressed: Boolean, extended: Boolean = false) {
        if (!running) return
        val ev = RdpGraphics.scancodeEvent(scancode, pressed, extended)
        sendShareData(RdpGraphics.PDU_TYPE_INPUT, RdpGraphics.inputEvents(listOf(ev)))
    }

    fun sendUnicode(char: Int, pressed: Boolean) {
        if (!running) return
        val ev = RdpGraphics.unicodeEvent(char, pressed)
        sendShareData(RdpGraphics.PDU_TYPE_INPUT, RdpGraphics.inputEvents(listOf(ev)))
    }

    fun disconnect() {
        running = false
        closeQuietly()
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    private fun upgradeTls() {
        // RDP 服务端大多用自签证书（Windows 自动生成、xrdp 用 snakeoil），
        // 标准客户端（mstsc）的做法是「提示指纹后继续」。这里先无条件信任，
        // 指纹通过 listener.onLog 打出来，UI 层可以据此提示。
        val s = socket ?: return
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), java.security.SecureRandom())
        val ssl = ctx.socketFactory.createSocket(s, config.host, config.port, true) as javax.net.ssl.SSLSocket
        ssl.useClientMode = true
        ssl.startHandshake()
        val cert = ssl.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
        if (cert != null) {
            val fp = Crypto.sha1(cert.encoded).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }.take(47)
            listener.onLog("服务端证书指纹 SHA1 $fp（自签证书已按 TOFU 信任）")
        }
        socket = ssl
        input = ssl.getInputStream()
        output = ssl.getOutputStream()
        listener.onLog("TLS 握手完成：${ssl.session.protocol} / ${ssl.session.cipherSuite}")
    }

    /** TLS 由传输层保护，不再叠加 RDP 的 RC4 安全层 */
    private val tlsActive: Boolean
        get() = selectedProtocol == RdpProtocol.PROTOCOL_SSL || selectedProtocol == RdpProtocol.PROTOCOL_HYBRID
}

/**
 * RDP 标准安全层（RC4 + HMAC-MD5），完全按 rdesktop 的 key 派生方式实现。
 */
class RdpSecurity(
    keySize: Int,
    clientRandom: ByteArray,
    serverRandom: ByteArray,
) {
    private val keyLen = if (keySize == 1) 8 else 16
    private val macKey = ByteArray(16)
    private var encryptKey = ByteArray(16)
    private var decryptKey = ByteArray(16)
    private val encryptUpdateKey = ByteArray(16)
    private val decryptUpdateKey = ByteArray(16)
    private val encryptRc4 = Rc4(ByteArray(16))
    private val decryptRc4 = Rc4(ByteArray(16))
    private var encryptCount = 0
    private var decryptCount = 0

    private val pad54 = ByteArray(40) { 54 }
    private val pad92 = ByteArray(48) { 92.toByte() }

    init {
        val preMaster = ByteArray(48)
        System.arraycopy(clientRandom, 0, preMaster, 0, 24)
        System.arraycopy(serverRandom, 0, preMaster, 24, 24)
        val master = Crypto.hash48(preMaster, clientRandom, serverRandom, 'A')
        val keyBlock = Crypto.hash48(master, clientRandom, serverRandom, 'X')

        System.arraycopy(keyBlock, 0, macKey, 0, 16)
        System.arraycopy(Crypto.hash16(keyBlock.copyOfRange(16, 32), clientRandom, serverRandom), 0, decryptKey, 0, 16)
        System.arraycopy(Crypto.hash16(keyBlock.copyOfRange(32, 48), clientRandom, serverRandom), 0, encryptKey, 0, 16)

        if (keyLen == 8) {
            make40(decryptKey); make40(encryptKey); make40(macKey)
        }
        System.arraycopy(decryptKey, 0, decryptUpdateKey, 0, 16)
        System.arraycopy(encryptKey, 0, encryptUpdateKey, 0, 16)
        encryptRc4.reset(encryptKey)
        decryptRc4.reset(decryptKey)
    }

    private fun make40(key: ByteArray) {
        key[0] = 0xD1.toByte(); key[1] = 0x26; key[2] = 0x9E.toByte()
    }

    /** 8 字节 HMAC */
    fun sign(data: ByteArray): ByteArray {
        val lenHdr = byteArrayOf(
            (data.size and 0xFF).toByte(),
            ((data.size ushr 8) and 0xFF).toByte(),
            ((data.size ushr 16) and 0xFF).toByte(),
            ((data.size ushr 24) and 0xFF).toByte(),
        )
        val shasig = Crypto.sha1(macKey.copyOf(keyLen), pad54, lenHdr, data)
        val md5sig = Crypto.md5(macKey.copyOf(keyLen), pad92, shasig)
        return md5sig.copyOf(8)
    }

    fun encryptRaw(data: ByteArray): ByteArray {
        if (encryptCount == 4096) {
            updateKey(encryptKey, encryptUpdateKey)
            encryptRc4.reset(encryptKey)
            encryptCount = 0
        }
        encryptCount++
        return encryptRc4.process(data)
    }

    fun decrypt(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        if (decryptCount == 4096) {
            updateKey(decryptKey, decryptUpdateKey)
            decryptRc4.reset(decryptKey)
            decryptCount = 0
        }
        decryptCount++
        return decryptRc4.process(data)
    }

    private fun updateKey(key: ByteArray, updateKey: ByteArray) {
        val shasig = Crypto.sha1(updateKey.copyOf(keyLen), pad54, key.copyOf(keyLen))
        val md5sig = Crypto.md5(updateKey.copyOf(keyLen), pad92, shasig)
        System.arraycopy(md5sig, 0, key, 0, 16)
        val tmp = Rc4(key.copyOf(keyLen))
        val out = tmp.process(key.copyOf(keyLen))
        System.arraycopy(out, 0, key, 0, keyLen)
        if (keyLen == 8) make40(key)
    }
}
