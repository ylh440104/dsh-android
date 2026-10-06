package com.deepseek.harness.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("dsh_credentials", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun saveToken(token: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(KEY_TOKEN, payload).apply()
    }

    fun loadToken(): String? {
        val payload = prefs.getString(KEY_TOKEN, null) ?: return null
        val parts = payload.split(":")
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE, null)
        if (existing != null) return existing
        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE, generated).apply()
        return generated
    }

    fun anonymousUserId(): String {
        val existing = prefs.getString(KEY_ANON_USER, null)
        if (existing != null) return existing
        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ANON_USER, generated).apply()
        return generated
    }

    fun saveModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model).apply()
    }

    fun loadModel(): String = prefs.getString(KEY_MODEL, "deepseek-flash") ?: "deepseek-flash"

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "dsh_harness_token_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_ANON_USER = "anon_user_id"
        private const val KEY_MODEL = "model"
    }
}