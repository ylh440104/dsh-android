package com.deepseek.harness.api

import com.deepseek.harness.model.ChatMessage
import com.deepseek.harness.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

interface MessageSink {
    fun append(message: ChatMessage): Int
    fun update(index: Int, message: ChatMessage)
}

sealed class RunResult {
    object Ok : RunResult()
    data class QuotaExhausted(val message: String) : RunResult()
    data class Unauthorized(val message: String) : RunResult()
    data class Failed(val message: String) : RunResult()
}

class ChatEngine(
    private val inference: InferenceClient,
    private val tools: ToolRegistry
) {

    fun run(
        model: String,
        history: List<ChatMessage>,
        sink: MessageSink,
        token: String? = null,
        onFinished: () -> Unit
    ): RunResult {
        val messages = buildHistory(history)
        val specs = tools.specs()
        var round = 0

        while (round < MAX_TOOL_ROUNDS) {
            round++
            val textBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            val pendingTools = mutableListOf<StreamEvent.ToolUse>()
            var failure: StreamEvent.Failed? = null

            val index = sink.append(ChatMessage("assistant", ""))
            inference.stream(model, SYSTEM_PROMPT, messages, specs, MAX_TOKENS, true, token) { event ->
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
                    is StreamEvent.Failed -> failure = event
                    else -> {}
                }
            }

            val failed = failure
            if (failed != null) {
                sink.update(index, ChatMessage("assistant", failed.message, isError = true))
                onFinished()
                return when {
                    failed.quotaExhausted -> RunResult.QuotaExhausted(failed.message)
                    failed.unauthorized -> RunResult.Unauthorized(failed.message)
                    else -> RunResult.Failed(failed.message)
                }
            }

            if (pendingTools.isEmpty()) {
                if (textBuf.isEmpty() && reasoningBuf.isEmpty()) {
                    sink.update(index, ChatMessage("assistant", "模型未返回内容", isError = true))
                }
                onFinished()
                return RunResult.Ok
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
        return RunResult.Ok
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
            "你是 DeepSeek Harness 的 Android 助手，运行在用户的手机上，可以真实操作这台设备。\n" +
            "\n" +
            "路径规则：\n" +
            "- 相对路径和以 /workspace 开头的路径落在应用私有工作区\n" +
            "- 以 / 开头的其他绝对路径直接指向设备真实文件系统，例如 /sdcard/Download、/sdcard/Documents\n" +
            "- 系统目录 /system、/vendor、/proc、/sys、/data/system 等禁止改动\n" +
            "\n" +
            "文件工具：\n" +
            "- list_files / read_file / read_file_part：查看目录与文件，大文件用 read_file_part 分段读\n" +
            "- create_file：新建文件\n" +
            "- edit_file：用 old/new 精确替换，old 必须在文件中唯一出现，改文件优先用这个\n" +
            "- write_file：整文件覆写\n" +
            "- delete_file / make_directory / copy_file / move_file：增删目录与移动复制\n" +
            "- find_files：按名称模式查找\n" +
            "- file_exists / file_info：检查存在性与查看信息\n" +
            "\n" +
            "搜索工具：\n" +
            "- grep_code：正则搜索文件内容，返回匹配行与上下文\n" +
            "- grep_context：按自然语言意图找出最相关的代码行\n" +
            "\n" +
            "压缩工具：\n" +
            "- zip_files / unzip_files / list_zip：打包、解包、查看压缩包\n" +
            "\n" +
            "网络工具：\n" +
            "- http_request：发送 HTTP 请求，支持 GET/POST/PUT/PATCH/DELETE 与自定义头\n" +
            "- visit_web：抓取网页正文，可附带链接列表\n" +
            "- download_file：从 URL 下载文件到本地\n" +
            "\n" +
            "设备与系统工具：\n" +
            "- device_info：机型、系统版本、内存、存储\n" +
            "- current_time / date_convert：当前时间与日期格式转换\n" +
            "- list_apps / app_info / list_processes：应用与进程\n" +
            "- start_app / stop_app：启动或停止应用\n" +
            "- open_url / open_file：用系统默认应用打开链接或文件\n" +
            "- get_system_setting / modify_system_setting：读写系统设置\n" +
            "- battery_status / network_status / wifi_info / screen_info：电池、网络、屏幕状态\n" +
            "- get_device_location：读取最近定位\n" +
            "- volume / brightness：音量与屏幕亮度\n" +
            "- clipboard_get / clipboard_set：读写剪贴板\n" +
            "- toast / send_notification / vibrate：轻提示、通知、震动\n" +
            "- execute_intent / send_broadcast：发送 Intent 与广播\n" +
            "- install_apk / uninstall_app：打开安装器与卸载界面\n" +
            "\n" +
            "屏幕自动化工具（需要 Shizuku 提权）：\n" +
            "- tap / long_press / swipe / press_key / set_input_text：模拟点击、长按、滑动、按键与输入\n" +
            "- screenshot：截图并保存为 PNG\n" +
            "- screen_size / current_activity / list_windows：屏幕尺寸、当前前台界面、窗口列表\n" +
            "- launch_activity / force_stop / clear_app_data：启动组件、强停、清数据\n" +
            "- grant_permission：授予应用运行时权限\n" +
            "- pm_install / pm_uninstall：静默安装与卸载\n" +
            "\n" +
            "计算与文本工具：\n" +
            "- calculate：数学表达式求值，支持括号、幂、三角函数、log 等\n" +
            "- base64_encode / base64_decode / hash_text / uuid / random_number\n" +
            "- text_transform / regex_op / sort_lines / diff_text：文本处理与对比\n" +
            "- url_encode / url_decode / json_format / convert_unit：编码与格式化\n" +
            "\n" +
            "Shell：\n" +
            "- shell：在设备上执行 shell 命令。用户已启动并授权 Shizuku 时以提权身份执行，否则退回应用自身身份，结果里会标注 identity\n" +
            "\n" +
            "工作方式：先读再改，改动前确认目标；删除和覆写要谨慎；需要操作文件或执行命令时直接调用工具，不要只描述步骤。\n" +
            "回答使用简体中文，可以用 Markdown 排版，简洁准确。"
    }
}