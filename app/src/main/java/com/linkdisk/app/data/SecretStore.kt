package com.linkdisk.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        SECRETS_PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun getString(key: String): String? {
        val encoded = preferences.getString(key, null) ?: return null
        return try {
            decrypt(encoded)
        } catch (error: Exception) {
            throw IllegalStateException("无法解密已保存的敏感配置", error)
        }
    }

    fun putString(key: String, value: String) {
        val encoded = try {
            encrypt(value)
        } catch (error: Exception) {
            throw IllegalStateException("无法加密敏感配置", error)
        }
        check(preferences.edit().putString(key, encoded).commit()) {
            "无法保存敏感配置"
        }
    }

    fun remove(key: String) {
        check(preferences.edit().remove(key).commit()) { "无法删除敏感配置" }
    }

    private fun secretKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), SecureRandom())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(2 + iv.size + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size >= 2) { "密文格式无效" }
        require(payload[0] == FORMAT_VERSION) { "密文版本不受支持" }
        val ivSize = payload[1].toInt() and 0xff
        require(ivSize == GCM_IV_SIZE_BYTES && payload.size > 2 + ivSize) { "密文格式无效" }

        val iv = payload.copyOfRange(2, 2 + ivSize)
        val ciphertext = payload.copyOfRange(2 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private companion object {
        const val SECRETS_PREFS_NAME = "linkdisk_secrets"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.linkdisk.app.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_SIZE_BITS = 256
        const val GCM_IV_SIZE_BYTES = 12
        const val GCM_TAG_SIZE_BITS = 128
        const val FORMAT_VERSION: Byte = 1
        val KEY_LOCK = Any()
    }
}
