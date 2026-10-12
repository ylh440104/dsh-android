package com.deepseek.harness.api

import com.deepseek.harness.model.baseUrlCandidates
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ProviderProbe(
    val baseUrl: String,
    val models: List<String>
)

class CustomProviderClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetchModels(rawBaseUrl: String, apiKey: String): ProviderProbe {
        val candidates = baseUrlCandidates(rawBaseUrl)
        if (candidates.isEmpty()) {
            throw PlatformException("protocol", "请填写接口地址")
        }
        var lastError: PlatformException? = null
        for (base in candidates) {
            try {
                val models = requestModels(base, apiKey)
                if (models.isNotEmpty()) return ProviderProbe(base, models)
                lastError = PlatformException("protocol", "$base 没有返回任何模型")
            } catch (e: PlatformException) {
                lastError = e
            }
        }
        throw lastError ?: PlatformException("protocol", "无法获取模型列表")
    }

    private fun requestModels(base: String, apiKey: String): List<String> {
        val builder = Request.Builder()
            .url("$base/models")
            .header("Accept", "application/json")
            .get()
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val text = try {
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 401 || response.code == 403 ->
                        throw PlatformException("expired", "API Key 被拒绝")
                    response.code == 404 ->
                        throw PlatformException("protocol", "$base 没有 /models 接口")
                    response.code !in 200..299 ->
                        throw PlatformException("network", "模型列表请求失败：HTTP ${response.code}")
                    else -> body
                }
            }
        } catch (e: PlatformException) {
            throw e
        } catch (e: Exception) {
            throw PlatformException("network", "网络请求失败：${e.message}")
        }

        return parseModels(text)
    }

    private fun parseModels(text: String): List<String> {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw PlatformException("protocol", "模型列表响应不是 JSON")
        }
        val array = root.optJSONArray("data")
            ?: root.optJSONArray("models")
            ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optString("id", "")
            if (id.isNotEmpty()) out.add(id)
        }
        return out.distinct().sorted()
    }

    fun testChat(base: String, apiKey: String, model: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", org.json.JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", "ping")))
            .put("max_tokens", 1)
            .put("stream", false)
        val builder = Request.Builder()
            .url("$base/chat/completions")
            .header("content-type", "application/json")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return try {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code in 200..299) {
                    "连通，模型 $model 可用"
                } else {
                    val detail = runCatching {
                        JSONObject(text).optJSONObject("error")?.optString("message", "").orEmpty()
                    }.getOrDefault("")
                    throw PlatformException("network", "HTTP ${response.code}${if (detail.isEmpty()) "" else "：$detail"}")
                }
            }
        } catch (e: PlatformException) {
            throw e
        } catch (e: Exception) {
            throw PlatformException("network", "网络请求失败：${e.message}")
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
