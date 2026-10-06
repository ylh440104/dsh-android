package com.deepseek.harness.api

import com.deepseek.harness.model.Account
import com.deepseek.harness.model.Balance
import com.deepseek.harness.model.parseAccount
import com.deepseek.harness.model.parseBalance
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PlatformException(val code: String, message: String) : Exception(message)

class PlatformClient(private val origin: String = PLATFORM_ORIGIN) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun authInit(
        codeChallenge: String,
        state: String,
        redirectUri: String,
        locale: String,
        loginSource: String
    ): AuthInit {
        val body = JSONObject()
            .put("code_challenge", codeChallenge)
            .put("code_challenge_method", "S256")
            .put("state", state)
            .put("redirect_uri", redirectUri)
            .put("locale", locale)
            .put("login_source", loginSource)
        val data = post("$origin/auth-api/v0/dsh/auth_init", body, null)
        val url = data.optString("authorize_url", "")
        val id = data.optString("authorize_id", "")
        val expires = data.optLong("expires_in", 0L)
        if (url.isEmpty() || id.isEmpty() || expires <= 0L) throw PlatformException("protocol", "auth_init 响应异常")
        return AuthInit(url, id, expires)
    }

    fun authExchange(
        code: String,
        codeVerifier: String,
        redirectUri: String,
        deviceId: String,
        deviceModel: String,
        osVersion: String
    ): AuthResult {
        val body = JSONObject()
            .put("code", code)
            .put("code_verifier", codeVerifier)
            .put("redirect_uri", redirectUri)
            .put("device_id", deviceId)
            .put("device_model", deviceModel)
            .put("os_version", osVersion)
        val data = post("$origin/auth-api/v0/dsh/auth_exchange", body, null)
        val token = data.optString("token", "")
        if (token.isEmpty()) throw PlatformException("protocol", "auth_exchange 未返回 token")
        val user = data.optJSONObject("user")
        val account = user?.let { runCatching { parseAccount(it) }.getOrNull() }
        return AuthResult(token, account)
    }

    fun authCancel(authorizeId: String, codeVerifier: String) {
        runCatching {
            val body = JSONObject()
                .put("authorize_id", authorizeId)
                .put("code_verifier", codeVerifier)
            post("$origin/auth-api/v0/dsh/auth_cancel", body, null)
        }
    }

    fun fetchAccount(token: String): Account {
        val data = get("$origin/auth-api/v0/users/current", token)
        return parseAccount(data)
    }

    fun fetchBalance(token: String): Balance {
        val data = get("$origin/api/v0/users/get_user_summary", token)
        return parseBalance(data)
    }

    fun logout(token: String) {
        runCatching {
            val req = Request.Builder()
                .url("$origin/auth-api/v0/users/logout")
                .header("x-dsh-auth-token", token)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            http.newCall(req).execute().use { it.body?.string() }
        }
    }

    private fun post(url: String, body: JSONObject, token: String?): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
        if (token != null) builder.header("x-dsh-auth-token", token)
        return execute(builder.build())
    }

    private fun get(url: String, token: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("x-dsh-auth-token", token)
            .get()
            .build()
        return execute(req)
    }

    private fun execute(req: Request): JSONObject {
        val response = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw PlatformException("network", "网络请求失败：${e.message}")
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw PlatformException("expired", "登录已失效")
            if (!resp.isSuccessful) throw PlatformException("network", "服务返回 HTTP ${resp.code}")
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw PlatformException("protocol", "响应解析失败")
            }
            if (root.optInt("code", -1) == 40003) throw PlatformException("expired", "登录已失效")
            val data = root.optJSONObject("data")
                ?: throw PlatformException("protocol", "响应缺少 data")
            if (data.optInt("biz_code", -1) != 0) throw PlatformException("protocol", "业务错误 ${data.optInt("biz_code", -1)}")
            return data.optJSONObject("biz_data")
                ?: throw PlatformException("protocol", "响应缺少 biz_data")
        }
    }

    companion object {
        const val PLATFORM_ORIGIN = "https://platform.deepseek.com"
        const val INFERENCE_ORIGIN = "https://api.deepseek.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class AuthInit(val authorizeUrl: String, val authorizeId: String, val expiresInSeconds: Long)

data class AuthResult(val token: String, val account: Account?)