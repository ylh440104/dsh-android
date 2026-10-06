package com.deepseek.harness.tools

import com.deepseek.harness.api.ToolSpec
import org.json.JSONObject
import java.io.File

class ToolResult(val content: String, val isError: Boolean = false)

class ToolRegistry(private val workspace: File) {

    init {
        if (!workspace.exists()) workspace.mkdirs()
    }

    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            "list_dir",
            "列出工作区目录下的文件与子目录。参数 path 为相对工作区的路径，留空表示根目录。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().put("path", JSONObject().apply {
                    put("type", "string")
                    put("description", "相对工作区的目录路径，留空表示根目录")
                }))
            }
        ),
        ToolSpec(
            "read_file",
            "读取工作区中某个文本文件的内容。参数 path 为相对工作区的文件路径。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().put("path", JSONObject().apply {
                    put("type", "string")
                    put("description", "相对工作区的文件路径")
                }))
                put("required", org.json.JSONArray().put("path"))
            }
        ),
        ToolSpec(
            "write_file",
            "把文本内容写入工作区中的文件，文件不存在则创建，存在则覆盖。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "相对工作区的文件路径")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "要写入的完整文本内容")
                    })
                })
                put("required", org.json.JSONArray().put("path").put("content"))
            }
        ),
        ToolSpec(
            "delete_file",
            "删除工作区中的某个文件。参数 path 为相对工作区的文件路径。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().put("path", JSONObject().apply {
                    put("type", "string")
                    put("description", "相对工作区的文件路径")
                }))
                put("required", org.json.JSONArray().put("path"))
            }
        )
    )

    fun execute(name: String, inputJson: String): ToolResult {
        val input = try {
            if (inputJson.isBlank()) JSONObject() else JSONObject(inputJson)
        } catch (e: Exception) {
            return ToolResult("参数不是合法 JSON：${e.message}", true)
        }
        return try {
            when (name) {
                "list_dir" -> listDir(input.optString("path", ""))
                "read_file" -> readFile(input.optString("path", ""))
                "write_file" -> writeFile(input.optString("path", ""), input.optString("content", ""))
                "delete_file" -> deleteFile(input.optString("path", ""))
                else -> ToolResult("未知工具：$name", true)
            }
        } catch (e: Exception) {
            ToolResult("执行失败：${e.message}", true)
        }
    }

    private fun resolve(path: String): File? {
        val candidate = if (path.isBlank()) workspace else File(workspace, path)
        val canonical = candidate.canonicalFile
        val root = workspace.canonicalFile
        return if (canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)) canonical else null
    }

    private fun listDir(path: String): ToolResult {
        val dir = resolve(path) ?: return ToolResult("路径越界", true)
        if (!dir.exists()) return ToolResult("目录不存在：$path", true)
        if (!dir.isDirectory) return ToolResult("不是目录：$path", true)
        val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (entries.isEmpty()) return ToolResult("(空目录)")
        val sb = StringBuilder()
        for (entry in entries) {
            sb.append(if (entry.isDirectory) "[dir]  " else "[file] ")
            sb.append(entry.name)
            if (entry.isFile) sb.append("  (").append(entry.length()).append(" bytes)")
            sb.append("\n")
        }
        return ToolResult(sb.toString().trimEnd())
    }

    private fun readFile(path: String): ToolResult {
        val file = resolve(path) ?: return ToolResult("路径越界", true)
        if (!file.exists()) return ToolResult("文件不存在：$path", true)
        if (file.isDirectory) return ToolResult("这是目录，请用 list_dir", true)
        if (file.length() > MAX_READ_BYTES) return ToolResult("文件过大（${file.length()} 字节），超过读取上限", true)
        return ToolResult(file.readText())
    }

    private fun writeFile(path: String, content: String): ToolResult {
        val file = resolve(path) ?: return ToolResult("路径越界", true)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return ToolResult("已写入 ${file.relativeTo(workspace).path}（${content.toByteArray().size} 字节）")
    }

    private fun deleteFile(path: String): ToolResult {
        val file = resolve(path) ?: return ToolResult("路径越界", true)
        if (!file.exists()) return ToolResult("文件不存在：$path", true)
        if (file.isDirectory) return ToolResult("请勿删除目录", true)
        file.delete()
        return ToolResult("已删除 $path")
    }

    companion object {
        private const val MAX_READ_BYTES = 512L * 1024L
    }
}