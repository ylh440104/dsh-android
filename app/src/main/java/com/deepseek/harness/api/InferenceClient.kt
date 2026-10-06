package com.deepseek.harness.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class StreamEvent {
    data class Text(val delta: String) : StreamEvent()
    data class Reasoning(val delta: String) : StreamEvent()
    data class ToolUse(val id: String, val name: String, val input: String) : StreamEvent()
    data class Usage(val inputTokens: Int, val outputTokens: Int) : StreamEvent()
    data class Retrying(val attempt: Int) : StreamEvent()
    data class Failed(val message: String) : StreamEvent()
    object Done : StreamEvent()
}

data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

class InferenceClient(
    private val origin: String = PlatformClient.INFERENCE_ORIGIN,
    private val tokenProvider: () -> String?,
    private val userIdProvider: () -> String,
    private val sessionIdProvider: () -> Long
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun stream(
        model: String,
        system: String,
        messages: JSONArray,
        tools: List<ToolSpec>,
        maxTokens: Int,
        reasoning: Boolean,
        onEvent: (StreamEvent) -> Unit
    ) {
        val token = tokenProvider()
        if (token.isNullOrEmpty()) {
            onEvent(StreamEvent.Failed("未登录，请先登录 DeepSeek 账号"))
            return
        }
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("max_tokens", maxTokens)
            .put("messages", messages)
        if (system.isNotEmpty()) body.put("system", system)
        if (reasoning) {
            body.put("thinking", JSONObject().put("type", "enabled"))
            body.put("output_config", JSONObject().put("effort", "high"))
        } else {
            body.put("thinking", JSONObject().put("type", "disabled"))
        }
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            for (t in tools) {
                arr.put(JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("input_schema", t.schema))
            }
            body.put("tools", arr)
        }

        val req = Request.Builder()
            .url("$origin/anthropic/v1/messages")
            .header("x-dsh-auth-token", token)
            .header("anthropic-version", "2023-06-01")
            .header("accept", "text/event-stream")
            .header("content-type", "application/json")
            .header("user-agent", PlatformClient.USER_AGENT)
            .header("x-deepseek-harness-user-id", userIdProvider())
            .header("x-deepseek-harness-session-id", sessionIdProvider().toString())
            .post(body.toString().toRequestBody(JSON))
            .build()

        var attempt = 0
        while (true) {
            var retry = false
            var delay = 0L
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val text = resp.body?.string().orEmpty()
                        if (isRetryable(resp.code) && attempt < MAX_RETRIES) {
                            retry = true
                            delay = backoffMs(attempt, resp.header("retry-after"))
                        } else {
                            onEvent(StreamEvent.Failed(describeError(resp.code, text)))
                            return
                        }
                    } else {
                        val source = resp.body?.source()
                        if (source == null) {
                            onEvent(StreamEvent.Failed("响应为空"))
                            return
                        }
                        readStream(source, onEvent)
                        return
                    }
                }
            } catch (e: Exception) {
                if (attempt < MAX_RETRIES) {
                    retry = true
                    delay = backoffMs(attempt, null)
                } else {
                    onEvent(StreamEvent.Failed("网络错误：${e.message}"))
                    return
                }
            }
            if (!retry) return
            attempt++
            if (attempt > 1) onEvent(StreamEvent.Retrying(attempt))
            Thread.sleep(delay)
        }
    }

    private fun isRetryable(code: Int): Boolean = code == 429 || code == 408 || code >= 500

    private fun backoffMs(attempt: Int, retryAfter: String?): Long {
        val fromHeader = retryAfter?.trim()?.toLongOrNull()
        if (fromHeader != null) return (fromHeader * 1000L).coerceAtMost(MAX_DELAY_MS)
        val base = INITIAL_DELAY_MS * (1L shl attempt.coerceAtMost(6))
        val capped = base.coerceAtMost(MAX_DELAY_MS)
        val jitter = (capped * JITTER_RATIO * (Math.random() * 2 - 1)).toLong()
        return (capped + jitter).coerceAtLeast(100L)
    }

    private fun readStream(source: BufferedSource, onEvent: (StreamEvent) -> Unit) {
        val toolIds = mutableMapOf<Int, String>()
        val toolNames = mutableMapOf<Int, String>()
        val toolJson = mutableMapOf<Int, StringBuilder>()

        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            val obj = try {
                JSONObject(payload)
            } catch (e: Exception) {
                continue
            }
            when (obj.optString("type", "")) {
                "content_block_start" -> {
                    val index = obj.optInt("index", 0)
                    val block = obj.optJSONObject("content_block") ?: continue
                    if (block.optString("type") == "tool_use") {
                        toolIds[index] = block.optString("id", "")
                        toolNames[index] = block.optString("name", "")
                        toolJson[index] = StringBuilder()
                    }
                }
                "content_block_delta" -> {
                    val index = obj.optInt("index", 0)
                    val delta = obj.optJSONObject("delta") ?: continue
                    when (delta.optString("type", "")) {
                        "text_delta" -> onEvent(StreamEvent.Text(delta.optString("text", "")))
                        "thinking_delta" -> onEvent(StreamEvent.Reasoning(delta.optString("thinking", "")))
                        "input_json_delta" -> toolJson[index]?.append(delta.optString("partial_json", ""))
                    }
                }
                "content_block_stop" -> {
                    val index = obj.optInt("index", 0)
                    val name = toolNames[index]
                    if (name != null) {
                        onEvent(StreamEvent.ToolUse(toolIds[index].orEmpty(), name, toolJson[index]?.toString().orEmpty()))
                        toolIds.remove(index)
                        toolNames.remove(index)
                        toolJson.remove(index)
                    }
                }
                "message_delta" -> {
                    val usage = obj.optJSONObject("usage")
                    if (usage != null) {
                        onEvent(StreamEvent.Usage(0, usage.optInt("output_tokens", 0)))
                    }
                }
                "message_stop" -> {
                    onEvent(StreamEvent.Done)
                    return
                }
                "error" -> {
                    val err = obj.optJSONObject("error")
                    onEvent(StreamEvent.Failed(err?.optString("message", "服务返回错误") ?: "服务返回错误"))
                    return
                }
            }
        }
        onEvent(StreamEvent.Done)
    }

    private fun describeError(code: Int, text: String): String {
        val detail = try {
            val obj = JSONObject(text)
            val err = obj.optJSONObject("error")
            err?.optString("message", "").orEmpty()
        } catch (e: Exception) {
            ""
        }
        val prefix = when (code) {
            401 -> "登录已失效"
            402 -> "余额不足"
            429 -> "请求被限流"
            else -> "服务返回 HTTP $code"
        }
        return when {
            detail.isNotEmpty() -> "$prefix：$detail"
            text.isNotBlank() -> "$prefix：${text.take(300)}"
            else -> prefix
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RETRIES = 5
        private const val INITIAL_DELAY_MS = 500L
        private const val MAX_DELAY_MS = 10000L
        private const val JITTER_RATIO = 0.1
    }
}