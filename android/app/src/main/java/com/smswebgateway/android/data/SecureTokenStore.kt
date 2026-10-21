package com.smswebgateway.android.data

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
 * 设备令牌存储：AES-256-GCM，密钥保存在 AndroidKeyStore（不可导出），
 * 密文（IV || ciphertext，Base64）保存在 SharedPreferences。
 * 解密失败（密钥失效等）时视为未保存，调用方会回到「未绑定」。
 */
class SecureTokenStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("secure_store", Context.MODE_PRIVATE)
    @Volatile private var cached: String? = null
    private val lock = Any()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun save(token: String) = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ct
        sp.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
        cached = token
    }

    fun get(): String? = synchronized(lock) {
        cached?.let { return it }
        val b64 = sp.getString(KEY_BLOB, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_LEN)
            val ct = blob.copyOfRange(IV_LEN, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8).also { cached = it }
        } catch (e: Exception) {
            null
        }
    }

    fun hasToken(): Boolean = get() != null

    fun clear() = synchronized(lock) {
        cached = null
        sp.edit().clear().commit()
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "smsgate_device_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BLOB = "device_token"
        const val IV_LEN = 12
    }
}
