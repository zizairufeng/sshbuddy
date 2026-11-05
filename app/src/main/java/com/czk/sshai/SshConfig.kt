package com.czk.sshai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 一台主机（服务器）的连接信息 + 该主机自己的 AI 配置。
 * 主流客户端（Termius / JuiceSSH / TermAI）都是「主机列表 + 每台独立凭据」的模型，
 * 这里按同一思路设计：AI 配置挂在主机上，不同机器可以用不同模型。
 */
data class SshConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val useKey: Boolean = false,
    val password: String = "",
    val keyPem: String = "",
    val passphrase: String = "",
    // —— 协议相关 ——
    // 注意：这些字段必须可空/可为 0。Gson 反序列化不走构造函数的默认值，
    // 旧版 JSON 里没有这些键时会被填成 null / 0，非空类型会在运行时炸掉。
    val protocol: String? = null,        // null 或 "ssh" = SSH；"rdp" = 远程桌面
    val domain: String? = null,          // RDP 域，可空
    val rdpPort: Int = 0,                // 0 = 用默认 3389
    val rdpSecurity: String? = null,     // null/"auto" | "nla" | "tls" | "rdp"
    val rdpWidth: Int = 0,               // 0 = 跟随设备屏幕
    val rdpHeight: Int = 0,
    // —— 以下 AI 配置是全局的，保存时只取第一台主机的值 ——
    val aiBaseUrl: String = "https://api.deepseek.com/v1",
    val aiKey: String = "",
    val aiModel: String = "deepseek-chat",
) {
    val isRdp: Boolean get() = protocol == "rdp"

    /** RDP 实际端口 */
    val effectiveRdpPort: Int get() = if (rdpPort > 0) rdpPort else 3389

    /** 连接必需项齐了没有 */
    val ready: Boolean
        get() = host.isNotBlank() && when {
            isRdp -> user.isNotBlank() && password.isNotBlank()
            else -> user.isNotBlank() && (if (useKey) keyPem.isNotBlank() else password.isNotBlank())
        }

    val aiReady: Boolean
        get() = aiKey.isNotBlank() && aiBaseUrl.isNotBlank()

    /** 列表里显示的名字：没起名就用 user@host */
    val label: String
        get() = name.ifBlank { if (host.isBlank()) "未命名主机" else "$user@$host" }

    /** 副标题：ip:port + 认证方式 */
    val subtitle: String
        get() = when {
            isRdp -> "$host:$effectiveRdpPort  ·  RDP 远程桌面"
            else -> "$host:$port  ·  ${if (useKey) "密钥" else "密码"}"
        }

    /** 搜索用的小写关键字串 */
    val searchKey: String
        get() = "$name $host $user ${if (isRdp) effectiveRdpPort else port}".lowercase()

    /**
     * 别让 data class 自动生成的 toString 把密码/私钥/API Key 带进日志或崩溃报告。
     */
    override fun toString(): String =
        "SshConfig(id=$id, name='$name', host='$host', port=$port, user='$user', " +
            "protocol=${protocol ?: "ssh"}, rdpPort=$rdpPort)"
}

/** 全局偏好：AI 配置 + 终端字号 */
data class AppPrefs(
    val aiBaseUrl: String = "https://api.deepseek.com/v1",
    val aiKey: String = "",
    val aiModel: String = "deepseek-chat",
    val fontSize: Float = 12.5f,
    val keyBarVisible: Boolean = true,
    val autoReconnect: Boolean = true,
)

/**
 * 本地存储。全部走 SharedPreferences（本机私有目录，不联网）。
 * 主机列表序列化成 JSON 后用 [SecureStore] 做 AES-GCM 加密；
 * 旧版本留下的明文数据会在读取时识别，并在下一次保存时自动迁移。
 */
object Prefs {
    private const val NAME = "sshai.prefs"
    private const val K_HOSTS = "hosts.v2"
    private const val K_LEGACY_MIGRATED = "hosts.migrated"
    private const val K_AI_URL = "aiBaseUrl"
    private const val K_AI_KEY = "aiKey"
    private const val K_AI_MODEL = "aiModel"
    private const val K_FONT = "fontSize"
    private const val K_KEYBAR = "keyBarVisible"
    private const val K_AUTO_RECONNECT = "autoReconnect"
    private const val K_RDP_INPUT_MODE = "rdpInputMode"
    private const val K_RDP_DECK = "rdpDeckVisible"
    private const val K_RDP_DECK_RATIO = "rdpDeckRatio"
    private const val K_RDP_PTR_SPEED = "rdpPointerSpeedIdx"

    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<SshConfig>>() {}.type

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 读主机列表；首次运行会把 v1 的单配置迁移成第一条 */
    fun loadHosts(c: Context): List<SshConfig> {
        val p = sp(c)
        migrateLegacy(c, p)
        val stored = p.getString(K_HOSTS, null)
        if (stored.isNullOrBlank()) return emptyList()
        val json = SecureStore.decrypt(stored)
        if (json.isBlank()) return emptyList()
        val list: MutableList<SshConfig>? = runCatching { gson.fromJson<MutableList<SshConfig>>(json, listType) }.getOrNull()
        val all = (list ?: emptyList()).map { it.copy(id = it.id.ifBlank { UUID.randomUUID().toString() }) }
        // v1.8.0 移除了 VNC 协议：旧版本存下的 VNC 主机已经没有可以连接的实现，
        // 直接在这里丢掉并回写一次，免得列表里留一条点了就崩/必败的条目。
        val hosts = all.filterNot { it.protocol == "vnc" }
        val droppedVnc = all.size - hosts.size
        // 从旧版明文升级：立刻重写为密文（失败时 SecureStore 会退回明文，不会丢配置）。
        if ((droppedVnc > 0 || !SecureStore.isEncrypted(stored)) && hosts.isNotEmpty()) saveHosts(c, hosts)
        if (droppedVnc > 0 && hosts.isEmpty()) saveHosts(c, emptyList())
        return hosts
    }

    fun saveHosts(c: Context, hosts: List<SshConfig>) {
        sp(c).edit().putString(K_HOSTS, SecureStore.encrypt(gson.toJson(hosts))).apply()
    }

    fun upsertHost(c: Context, host: SshConfig): List<SshConfig> {
        val list = loadHosts(c).toMutableList()
        val i = list.indexOfFirst { it.id == host.id }
        if (i >= 0) list[i] = host else list.add(host)
        saveHosts(c, list)
        return list
    }

    fun deleteHost(c: Context, id: String): List<SshConfig> {
        val list = loadHosts(c).filterNot { it.id == id }
        saveHosts(c, list)
        return list
    }

    fun loadPrefs(c: Context): AppPrefs {
        val p = sp(c)
        val storedKey = p.getString(K_AI_KEY, "") ?: ""
        val aiKey = SecureStore.decrypt(storedKey)
        // 旧版明文 Key 顺手迁移成密文。
        if (storedKey.isNotEmpty() && !SecureStore.isEncrypted(storedKey)) {
            p.edit().putString(K_AI_KEY, SecureStore.encrypt(aiKey)).apply()
        }
        return AppPrefs(
            aiBaseUrl = p.getString(K_AI_URL, null) ?: "https://api.deepseek.com/v1",
            aiKey = aiKey,
            aiModel = p.getString(K_AI_MODEL, null) ?: "deepseek-chat",
            fontSize = p.getFloat(K_FONT, 12.5f),
            keyBarVisible = p.getBoolean(K_KEYBAR, true),
            autoReconnect = p.getBoolean(K_AUTO_RECONNECT, true),
        )
    }

    fun savePrefs(c: Context, prefs: AppPrefs) {
        sp(c).edit().apply {
            putString(K_AI_URL, prefs.aiBaseUrl)
            putString(K_AI_KEY, SecureStore.encrypt(prefs.aiKey))
            putString(K_AI_MODEL, prefs.aiModel)
            putFloat(K_FONT, prefs.fontSize)
            putBoolean(K_KEYBAR, prefs.keyBarVisible)
            putBoolean(K_AUTO_RECONNECT, prefs.autoReconnect)
        }.apply()
    }

    /** RDP 输入模式：pointer（触控板）或 touch（手指直接点） */
    fun loadRdpInputMode(c: Context): String =
        sp(c).getString(K_RDP_INPUT_MODE, "pointer") ?: "pointer"

    fun saveRdpInputMode(c: Context, mode: String) {
        sp(c).edit().putString(K_RDP_INPUT_MODE, mode).apply()
    }

    /**
     * 底部触摸板/按键控制台是否展开。
     * 默认展开：新用户第一次进远程桌面最需要它；老用户收起过一次之后就记住。
     */
    fun loadRdpDeckVisible(c: Context): Boolean = sp(c).getBoolean(K_RDP_DECK, true)

    fun saveRdpDeckVisible(c: Context, visible: Boolean) {
        sp(c).edit().putBoolean(K_RDP_DECK, visible).apply()
    }

    /**
     * 控制台占屏幕高度的比例，0 表示没设置过（由界面按横竖屏取默认值）。
     * 拖动分隔条后记住具体值，下次进入沿用。
     */
    fun loadRdpDeckRatio(c: Context): Float = sp(c).getFloat(K_RDP_DECK_RATIO, 0f)

    fun saveRdpDeckRatio(c: Context, ratio: Float) {
        sp(c).edit().putFloat(K_RDP_DECK_RATIO, ratio).apply()
    }

    /** 触摸板指针速度档位：0 慢 / 1 标准 / 2 快 */
    fun loadRdpPointerSpeedIdx(c: Context): Int = sp(c).getInt(K_RDP_PTR_SPEED, 1)

    fun saveRdpPointerSpeedIdx(c: Context, idx: Int) {
        sp(c).edit().putInt(K_RDP_PTR_SPEED, idx).apply()
    }

    /** v1 只有一个配置，升级到 v1.1 时自动搬成第一条主机，用户不用重新填 */
    private fun migrateLegacy(c: Context, p: android.content.SharedPreferences) {
        if (p.getBoolean(K_LEGACY_MIGRATED, false)) return
        val host = p.getString("host", "") ?: ""
        if (host.isNotBlank()) {
            val legacy = SshConfig(
                name = "我原来的主机",
                host = host,
                port = p.getInt("port", 22),
                user = p.getString("user", "") ?: "",
                useKey = p.getBoolean("useKey", false),
                password = p.getString("password", "") ?: "",
                keyPem = p.getString("keyPem", "") ?: "",
                passphrase = p.getString("passphrase", "") ?: "",
            )
            p.edit().putString(K_HOSTS, gson.toJson(listOf(legacy))).apply()
        }
        p.edit().putBoolean(K_LEGACY_MIGRATED, true).apply()
    }

    /** AI 配置取一份全局值（写回每台主机的副本里，便于 AI 面板直接用） */
    fun withPrefs(host: SshConfig, prefs: AppPrefs): SshConfig =
        host.copy(aiBaseUrl = prefs.aiBaseUrl, aiKey = prefs.aiKey, aiModel = prefs.aiModel)
}
