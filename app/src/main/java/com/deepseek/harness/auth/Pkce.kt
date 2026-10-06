package com.deepseek.harness.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object Pkce {

    private val random = SecureRandom()

    fun verifier(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return base64Url(digest.digest(verifier.toByteArray(Charsets.US_ASCII)))
    }

    fun state(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}