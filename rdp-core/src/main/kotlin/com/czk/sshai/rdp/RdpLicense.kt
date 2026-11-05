package com.czk.sshai.rdp

/**
 * RDP 授权（Licensing）握手，参考 [MS-RDPELE]。
 *
 * 连接时序里这一步是强制的：服务端在收到 Client Info 后发一个
 * SERVER_LICENSE_REQUEST（xrdp 发的是内置常量 `g_lic1`，322 字节，带
 * "Microsoft Corporation" 与 RSA1 公钥），客户端必须回一个
 * CLIENT_NEW_LICENSE_REQUEST，服务端才会发 Demand Active。
 *
 * 授权层的密钥交换没有实际安全意义（微软自己的文档也这么描述），
 * rdesktop / aardwolf 都用全 0 的 clientRandom 与 premaster，服务端照收，
 * 所以这里保持同样的做法，避免自造轮子引入不兼容。
 */
internal object RdpLicense {

    /** LICENSE_PREAMBLE.bMsgType */
    const val TAG_LICENSE_REQUEST = 0x01
    const val TAG_PLATFORM_CHALLENGE = 0x02
    const val TAG_NEW_LICENSE = 0x03
    const val TAG_LICENSE_INFO = 0x12
    const val TAG_NEW_LICENSE_REQUEST = 0x13
    const val TAG_PLATFORM_CHALLENGE_RESPONSE = 0x15
    const val TAG_ERROR_ALERT = 0xFF

    /** LICENSE_BINARY_BLOB.wBlobType */
    private const val BB_CLIENT_USER_NAME_BLOB = 0x000F
    private const val BB_CLIENT_MACHINE_NAME_BLOB = 0x0010

    private const val PREAMBLE_VERSION_3_0 = 0x03
    private const val KEY_EXCHANGE_ALG_RSA = 1
    private const val RSA_MODULUS_SIZE = 64        // 授权用的是 512 位临时密钥
    private const val RSA_PADDING_SIZE = 8

    /** 本机名，用作 CLIENT_MACHINE_NAME（服务端会在日志/会话里显示） */
    fun machineName(): String = try {
        java.net.InetAddress.getLocalHost().hostName.ifBlank { "android" }
    } catch (_: Throwable) {
        "android"
    }

    /**
     * 构造 CLIENT_NEW_LICENSE_REQUEST。
     *
     * 布局（与 rdesktop `licence_send_new_licence_request` 逐字节一致）：
     * ```
     * bMsgType(1)=0x13 | version(1)=3 | wMsgSize(2)      <- LICENSE_PREAMBLE
     * dwVersion(4)=KEY_EXCHANGE_ALG_RSA | 未用(2) | 0xFF01(2)
     * clientRandom(32) | wBlobType(2)=2 | wBlobLen(2)=72
     * 加密后的 premaster(64) | padding(8)
     * [0x000F + len + 用户名]  [0x0010 + len + 机器名]
     * ```
     * wMsgSize 语义：整条授权消息长度，**含** 4 字节 preamble。
     */
    fun buildNewLicenseRequest(user: String, machine: String): ByteArray {
        val userBytes = ansiZ(user.ifBlank { "user" })
        val hostBytes = ansiZ(machine.ifBlank { machineName() })

        // 24 = preamble(4) + dwVersion(4) + 未用(2) + 0xFF01(2) + wBlobType(2) + wBlobLen(2) + 8 字节固定尾部
        val total = 24 + 32 + RSA_MODULUS_SIZE + RSA_PADDING_SIZE + userBytes.size + hostBytes.size

        val w = ByteWriter(total + 8)
        w.u8(TAG_NEW_LICENSE_REQUEST)
        w.u8(PREAMBLE_VERSION_3_0)
        w.u16le(total)
        w.u32le(KEY_EXCHANGE_ALG_RSA)
        w.u16be(0)                                  // 未使用
        w.u16le(0xFF01)                             // 证书版本标记
        w.zeros(32)                                 // clientRandom（rdesktop 全 0）
        w.u16le(2)                                  // wBlobType
        w.u16le(RSA_MODULUS_SIZE + RSA_PADDING_SIZE)
        w.zeros(RSA_MODULUS_SIZE)                   // 加密后的 premaster（全 0，服务端不校验）
        w.zeros(RSA_PADDING_SIZE)
        w.u16le(BB_CLIENT_USER_NAME_BLOB); w.u16le(userBytes.size); w.bytes(userBytes)
        w.u16le(BB_CLIENT_MACHINE_NAME_BLOB); w.u16le(hostBytes.size); w.bytes(hostBytes)
        return w.toByteArray()
    }

    /** 服务端 LICENSE_REQUEST 里的 32 字节 ServerRandom（preamble 之后紧跟） */
    fun serverRandom(data: ByteArray): ByteArray? {
        if (data.size < 4 + 32) return null
        return data.copyOfRange(4, 36)
    }

    private fun ansiZ(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        return raw + 0
    }
}
