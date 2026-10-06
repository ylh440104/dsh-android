package com.deepseek.harness.api

import com.deepseek.harness.model.ChatMessage
import com.deepseek.harness.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

interface MessageSink {
    fun append(message: ChatMessage): Int
    fun update(index: Int, message: ChatMessage)
}

class ChatEngine(
    private val inference: InferenceClient,
    private val tools: ToolRegistry
) {

    fun run(model: String, history: List<ChatMessage>, sink: MessageSink, onFinished: () -> Unit) {
        val messages = buildHistory(history)
        val specs = tools.specs()
        var round = 0

        while (round < MAX_TOOL_ROUNDS) {
            round++
            val textBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            val pendingTools = mutableListOf<StreamEvent.ToolUse>()
            var failure: String? = null

            val index = sink.append(ChatMessage("assistant", ""))
            inference.stream(model, SYSTEM_PROMPT, messages, specs, MAX_TOKENS) { event ->
                when (event) {
                    is StreamEvent.Text -> {
                        textBuf.append(event.delta)
                        sink.update(index, ChatMessage("assistant", textBuf.toString(), reasoningBuf.toString()))
                    }
                    is StreamEvent.Reasoning -> {
                        reasoningBuf.append(event.delta)
                        sink.update(index, ChatMessage("assistant", textBuf.toString(), reasoningBuf.toString()))
                    }
                    is StreamEvent.ToolUse -> pendingTools.add(event)
                    is StreamEvent.Failed -> failure = event.message
                    else -> {}
                }
            }

            if (failure != null) {
                sink.update(index, ChatMessage("assistant", failure!!, isError = true))
                onFinished()
                return
            }

            if (pendingTools.isEmpty()) {
                if (textBuf.isEmpty() && reasoningBuf.isEmpty()) {
                    sink.update(index, ChatMessage("assistant", "模型未返回内容", isError = true))
                }
                onFinished()
                return
            }

            val assistantBlocks = JSONArray()
            if (textBuf.isNotEmpty()) {
                assistantBlocks.put(JSONObject().put("type", "text").put("text", textBuf.toString()))
            }
            for (tool in pendingTools) {
                assistantBlocks.put(JSONObject()
                    .put("type", "tool_use")
                    .put("id", tool.id)
                    .put("name", tool.name)
                    .put("input", parseInput(tool.input)))
            }
            messages.put(JSONObject().put("role", "assistant").put("content", assistantBlocks))

            val results = JSONArray()
            for (tool in pendingTools) {
                val toolIndex = sink.append(ChatMessage(
                    "tool",
                    tool.name,
                    toolName = tool.name,
                    toolInput = tool.input
                ))
                val result = tools.execute(tool.name, tool.input)
                sink.update(toolIndex, ChatMessage(
                    "tool",
                    tool.name,
                    toolName = tool.name,
                    toolInput = tool.input,
                    isError = result.isError
                ).copy(text = result.content))
                results.put(JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", tool.id)
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "text")
                        .put("text", result.content)))
                    .put("is_error", result.isError))
            }
            messages.put(JSONObject().put("role", "user").put("content", results))
        }

        sink.append(ChatMessage("assistant", "已达到工具调用轮次上限，请继续追问。", isError = true))
        onFinished()
    }

    private fun buildHistory(history: List<ChatMessage>): JSONArray {
        val messages = JSONArray()
        for (m in history) {
            when (m.role) {
                "user" -> messages.put(JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "text")
                        .put("text", m.text))))
                "assistant" -> {
                    if (m.text.isNotEmpty()) {
                        messages.put(JSONObject()
                            .put("role", "assistant")
                            .put("content", JSONArray().put(JSONObject()
                                .put("type", "text")
                                .put("text", m.text))))
                    }
                }
            }
        }
        return messages
    }

    private fun parseInput(raw: String): JSONObject = try {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (e: Exception) {
        JSONObject()
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 8
        private const val MAX_TOKENS = 8192
        private const val SYSTEM_PROMPT =
            "你是 DeepSeek Harness 的 Android 助手，运行在用户的手机上。\n" +
            "你可以调用工具在受限工作区里读写文件：list_dir、read_file、write_file、delete_file。\n" +
            "所有路径都相对于工作区根目录，禁止使用绝对路径或 .. 跳出工作区。\n" +
            "回答使用简体中文，简洁准确。需要操作文件时直接调用工具，不要只描述步骤。"
    }
}