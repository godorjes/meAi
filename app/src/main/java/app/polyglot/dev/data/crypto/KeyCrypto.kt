package app.polyglot.dev.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的本机加密保存。
 *
 * 加密密钥由 Android Keystore 生成并保护（别名与算法与历史版本一致，不重建、不替换），
 * 密文（IV + 密文，Base64）由调用方存进数据库，与所属配置在同一事务提交。
 * 明文只在内存中出现，不写日志、不上传任何非用户配置地址。
 *
 * 旧的 SharedPreferences 密文只作为迁移来源保留：[legacyCiphertext] / [removeLegacyCiphertext]
 * 迁移完成后逐条清理，Keystore 密钥本身不动。
 */
class KeyCrypto(context: Context) {

    private val prefs = context.getSharedPreferences("polyglot_secure", Context.MODE_PRIVATE)
    private val keyAlias = "polyglot_api_key_wrap"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** 加密为可直接入库的密文；失败抛异常，调用方不得在此之后再改动配置 */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    /** 解密；密文损坏或 Keystore 密钥不可用时抛异常，不返回空值冒充成功 */
    fun decrypt(payload: String): String {
        val raw = Base64.decode(payload, Base64.NO_WRAP)
        require(raw.size > 12) { "密文长度不足" }
        val iv = raw.copyOfRange(0, 12)
        val encrypted = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    /** 解密失败返回 null（用于判断一次写入是否可用），不吞掉成功路径 */
    fun decryptOrNull(payload: String): String? = runCatching { decrypt(payload) }.getOrNull()

    // ---------- 历史 SharedPreferences 密文（仅迁移用） ----------

    fun legacyCiphertext(configId: String): String? = prefs.getString("key_$configId", null)

    fun removeLegacyCiphertext(configId: String) {
        prefs.edit().remove("key_$configId").commit()
    }

    /** 迁移前的旧键名清单，只返回键名，不返回密文 */
    fun legacyConfigIds(): List<String> =
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }

    private companion object {
        const val KEY_PREFIX = "key_"
    }
}
