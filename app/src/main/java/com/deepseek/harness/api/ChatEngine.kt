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
            inference.stream(model, SYSTEM_PROMPT, messages, specs, MAX_TOKENS, true) { event ->
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
                    is StreamEvent.Retrying -> sink.update(
                        index,
                        ChatMessage("assistant", textBuf.toString(), reasoningBuf.toString(), isRetrying = true)
                    )
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
        private const val MAX_TOOL_ROUNDS = 12
        private const val MAX_TOKENS = 8192
        private const val SYSTEM_PROMPT =
            "你是 DeepSeek Harness 的 Android 助手，运行在用户的手机上。\n" +
            "\n" +
            "路径规则：\n" +
            "- 相对路径和以 /workspace 开头的路径都落在应用私有工作区里\n" +
            "- 以 / 开头的其他绝对路径直接指向设备真实文件系统，例如 /sdcard/Download、/sdcard/Documents\n" +
            "- 系统目录 /system、/vendor、/proc、/sys、/data/system 等禁止改动\n" +
            "\n" +
            "可用工具：\n" +
            "- list_files：列出目录内容\n" +
            "- read_file：读取文本文件，返回带行号的内容\n" +
            "- read_file_part：按行范围读取，大文件用这个\n" +
            "- create_file：新建文件，已存在则失败\n" +
            "- edit_file：用 old/new 精确替换已有内容，old 必须在文件中唯一出现\n" +
            "- write_file：整文件覆写\n" +
            "- delete_file：删除文件或目录，删目录需 recursive=true\n" +
            "- make_directory：创建目录\n" +
            "- find_files：按名称模式查找，如 *.txt\n" +
            "- file_exists / file_info：检查存在性与查看文件信息\n" +
            "- shell：通过 Shizuku 以提权身份执行 shell 命令，需要用户已启动 Shizuku 并授权\n" +
            "\n" +
            "修改文件时优先用 edit_file 而不是 write_file，先读再改，old 必须与文件内容完全一致。\n" +
            "操作 /sdcard 等真实路径前先确认目标，删除和覆写要谨慎。\n" +
            "回答使用简体中文，简洁准确。需要操作文件或执行命令时直接调用工具，不要只描述步骤。"
    }
}