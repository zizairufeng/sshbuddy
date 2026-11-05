package com.czk.sshai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-256-GCM 密钥加密本机落盘数据。
 *
 * 之前主机密码、私钥口令、RDP 密码、AI Key 都是明文 JSON / SharedPreferences，
 * 只要拿到应用私有目录（root、调试备份、部分厂商换机工具）就能直接读走。
 * 现在统一加一层信封加密：密钥由系统 Keystore 保管，不随数据文件导出。
 *
 * 格式：`enc1:` + Base64(12 字节 IV + 密文 + GCM tag)。
 * 没有前缀的值按旧版明文读取，并在下次保存时自动迁移成密文。
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val PREFIX = "enc1:"
    private const val KEY_ALIAS = "sshai.vault.v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /** 加密；空串原样返回。Keystore 不可用时退回明文并记录错误，保证 App 仍能保存配置。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + body.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(body, 0, packed, iv.size, body.size)
            PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.e(TAG, "加密失败，暂时以明文保存", t)
            plain
        }
    }

    /** 解密；无前缀视为旧版明文。密文损坏时返回空串，避免把密文当成密码去连接。 */
    fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val packed = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES),
            )
            String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "解密失败（密钥可能已被系统清除）", t)
            ""
        }
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
        cachedKey = key
        return key
    }
}
