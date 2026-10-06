package com.deepseek.harness.data

import android.content.Context

class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("dsh_credentials", Context.MODE_PRIVATE)

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

    fun legacyToken(): String? {
        val payload = prefs.getString(KEY_LEGACY_TOKEN, null) ?: return null
        return SecretBox.decrypt(payload)
    }

    fun clearLegacyToken() {
        prefs.edit().remove(KEY_LEGACY_TOKEN).apply()
    }

    companion object {
        private const val KEY_DEVICE = "device_id"
        private const val KEY_ANON_USER = "anon_user_id"
        private const val KEY_MODEL = "model"
        private const val KEY_LEGACY_TOKEN = "token"
    }
}