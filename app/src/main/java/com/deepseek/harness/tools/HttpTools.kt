package com.deepseek.harness.tools

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HttpTools {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun request(
        url: String,
        method: String,
        headers: String,
        body: String,
        bodyType: String
    ): ToolOutcome {
        if (url.isBlank()) return ToolOutcome.Err("url parameter is required")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolOutcome.Err("url must start with http:// or https://")
        }
        val builder = Request.Builder().url(url)
        if (headers.isNotBlank()) {
            val parsed = try {
                JSONObject(headers)
            } catch (e: Exception) {
                return ToolOutcome.Err("headers must be a JSON object: ${e.message}")
            }
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                runCatching { builder.header(key, parsed.optString(key, "")) }
            }
        }
        val mediaType = when (bodyType.lowercase()) {
            "json" -> "application/json; charset=utf-8"
            "form" -> "application/x-www-form-urlencoded"
            "xml" -> "application/xml; charset=utf-8"
            else -> "text/plain; charset=utf-8"
        }.toMediaType()
        val verb = method.uppercase().ifBlank { if (body.isBlank()) "GET" else "POST" }
        when (verb) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "PUT" -> builder.put(body.toRequestBody(mediaType))
            "PATCH" -> builder.patch(body.toRequestBody(mediaType))
            else -> builder.post(body.toRequestBody(mediaType))
        }
        return try {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val sb = StringBuilder()
                sb.append("HTTP ").append(response.code).append(' ').append(response.message).append('\n')
                sb.append("url: ").append(response.request.url).append('\n')
                val contentType = response.header("content-type")
                if (contentType != null) sb.append("content-type: ").append(contentType).append('\n')
                sb.append("bytes: ").append(text.toByteArray().size).append("\n\n")
                sb.append(if (text.length > MAX_BODY) text.take(MAX_BODY) + "\n…(truncated)" else text)
                if (response.isSuccessful) ToolOutcome.Ok(sb.toString().trimEnd())
                else ToolOutcome.Err(sb.toString().trimEnd())
            }
        } catch (e: Exception) {
            ToolOutcome.Err("Request failed: ${e.message}")
        }
    }

    companion object {
        private const val MAX_BODY = 120_000
    }
}