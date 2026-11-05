package com.czk.sshai

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest

/**
 * SSH 主机密钥 TOFU（首次使用即信任）仓库。
 *
 * 旧版本使用 StrictHostKeyChecking=no，等同完全关闭校验，任何中间人都能
 * 静默接管连接。这里改为：首次连接记录 SHA-256 指纹，以后指纹变化直接拒绝。
 * 记录的是公钥指纹，不是密钥，放普通 SharedPreferences 即可。
 */
class KnownHostsStore(context: Context) : HostKeyRepository {

    private val sp = context.applicationContext.getSharedPreferences("sshai.known_hosts", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mapType = object : TypeToken<MutableMap<String, String>>() {}.type

    // 指纹失配的说明按线程记录：多台主机同时重连时，全局字段会被别的连接
    // 清掉或覆盖，导致真正的“主机密钥已变化”被误报成普通网络错误。
    private val mismatch = ThreadLocal<String?>()

    val lastMismatch: String?
        get() = mismatch.get()

    private fun load(): MutableMap<String, String> {
        val raw = sp.getString(KEY, null) ?: return mutableMapOf()
        return runCatching { gson.fromJson<MutableMap<String, String>>(raw, mapType) }.getOrNull()
            ?: mutableMapOf()
    }

    private fun save(map: Map<String, String>) {
        sp.edit().putString(KEY, gson.toJson(map)).apply()
    }

    override fun check(host: String?, key: ByteArray?): Int {
        if (host.isNullOrBlank() || key == null || key.isEmpty()) return HostKeyRepository.NOT_INCLUDED
        val fp = fingerprint(key)
        synchronized(this) {
            val map = load()
            val known = map[host]
            if (known == null) {
                // TOFU：第一次见到这台主机时记录，后续必须完全一致。
                map[host] = fp
                save(map)
                mismatch.remove()
                Log.i(TAG, "已记录主机密钥 $host $fp")
                return HostKeyRepository.OK
            }
            if (known == fp) {
                mismatch.remove()
                return HostKeyRepository.OK
            }
            val message = "主机 $host 的 SSH 指纹已变化。\n期望 $known\n实际 $fp"
            mismatch.set(message)
            Log.w(TAG, message)
            return HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {
        if (hostkey == null) return
        val host = hostkey.host ?: return
        val key = hostkey.key ?: return
        // JSch 的 HostKey.getKey() 是 Base64 文本；check() 拿到的是原始 key blob，
        // 指纹必须基于解码后的字节，两边才能对上。
        val raw = runCatching { Base64.decode(key, Base64.DEFAULT) }.getOrNull() ?: return
        synchronized(this) {
            val map = load()
            map[host] = fingerprint(raw)
            save(map)
        }
    }

    override fun remove(host: String?, type: String?) {
        if (host == null) return
        synchronized(this) {
            val map = load()
            map.remove(host)
            save(map)
        }
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        if (host == null) return
        synchronized(this) {
            val map = load()
            if (key == null || map[host] == fingerprint(key)) map.remove(host)
            save(map)
        }
    }

    override fun getKnownHostsRepositoryID(): String = TAG

    override fun getHostKey(): Array<HostKey>? = null

    override fun getHostKey(host: String?, type: String?): Array<HostKey>? = null

    fun resetAll() {
        synchronized(this) {
            sp.edit().remove(KEY).apply()
            mismatch.remove()
        }
    }

    /** 新一次连接前清掉上次的失配结论，避免把别的失败误判成指纹变化。 */
    fun clearMismatch() {
        mismatch.remove()
    }

    companion object {
        private const val TAG = "SsHBuddyKnownHosts"
        private const val KEY = "known.v1"

        fun fingerprint(key: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
        }
    }
}
