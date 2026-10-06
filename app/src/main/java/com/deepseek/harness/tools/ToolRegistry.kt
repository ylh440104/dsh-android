package com.deepseek.harness.tools

import com.deepseek.harness.api.ToolSpec
import com.deepseek.harness.shell.ShizukuManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ToolResult(val content: String, val isError: Boolean = false)

class ToolRegistry(workspace: File) {

    private val files = FileTools(workspace)

    fun specs(): List<ToolSpec> = listOf(
        spec(
            "list_files",
            "List files and subdirectories in a directory. Path is relative to the workspace root; leave empty for the root.",
            props = listOf(
                "path" to stringProp("directory path relative to the workspace root, empty for root", required = true)
            )
        ),
        spec(
            "read_file",
            "Read a text file. Content is returned with line numbers. Large files should be read with read_file_part instead.",
            props = listOf(
                "path" to stringProp("file path relative to the workspace root", required = true)
            )
        ),
        spec(
            "read_file_part",
            "Read a slice of a text file by line range. Use this for large files.",
            props = listOf(
                "path" to stringProp("file path relative to the workspace root", required = true),
                "start_line" to intProp("first line to read, 1-indexed, defaults to 1"),
                "end_line" to intProp("last line to read, 1-indexed, inclusive, defaults to start_line + 199")
            )
        ),
        spec(
            "create_file",
            "Create a new file with the given content. Fails if the file already exists.",
            props = listOf(
                "path" to stringProp("file path relative to the workspace root", required = true),
                "content" to stringProp("full file content", required = true)
            )
        ),
        spec(
            "edit_file",
            "Edit an existing file by replacing an exact snippet. The 'old' text must appear exactly once. Read the file first and copy the exact text.",
            props = listOf(
                "path" to stringProp("file path relative to the workspace root", required = true),
                "old" to stringProp("the exact existing text to replace", required = true),
                "new" to stringProp("the replacement text", required = true)
            )
        ),
        spec(
            "write_file",
            "Overwrite a file with new content, creating it if missing. Prefer create_file and edit_file when possible.",
            props = listOf(
                "path" to stringProp("file path relative to the workspace root", required = true),
                "content" to stringProp("full new content", required = true)
            )
        ),
        spec(
            "delete_file",
            "Delete a file or directory inside the workspace.",
            props = listOf(
                "path" to stringProp("target path relative to the workspace root", required = true),
                "recursive" to boolProp("set true to delete a directory and its contents, default false")
            )
        ),
        spec(
            "make_directory",
            "Create a directory inside the workspace.",
            props = listOf(
                "path" to stringProp("directory path relative to the workspace root", required = true),
                "create_parents" to boolProp("set true to create missing parent directories, default false")
            )
        ),
        spec(
            "find_files",
            "Search for files by name pattern such as *.txt. Supports * and ? wildcards.",
            props = listOf(
                "path" to stringProp("directory to search in, empty for the workspace root"),
                "pattern" to stringProp("name pattern, for example *.txt", required = true),
                "max_depth" to intProp("maximum subdirectory depth, -1 for unlimited"),
                "case_insensitive" to boolProp("set true to ignore case, default false")
            )
        ),
        spec(
            "file_exists",
            "Check whether a file or directory exists.",
            props = listOf(
                "path" to stringProp("path relative to the workspace root", required = true)
            )
        ),
        spec(
            "file_info",
            "Show size, type and modification time of a file or directory.",
            props = listOf(
                "path" to stringProp("path relative to the workspace root", required = true)
            )
        ),
        spec(
            "shell",
            "Run a shell command on the device through Shizuku with elevated privileges. Requires the user to have started Shizuku and granted this app permission. Returns stdout, stderr and the exit code.",
            props = listOf(
                "command" to stringProp("the shell command to run, for example 'ls -la /sdcard/Download'", required = true)
            )
        )
    )

    fun execute(name: String, inputJson: String): ToolResult {
        val input = try {
            if (inputJson.isBlank()) JSONObject() else JSONObject(inputJson)
        } catch (e: Exception) {
            return ToolResult("Arguments are not valid JSON: ${e.message}", true)
        }
        val outcome = when (name) {
            "list_files" -> files.listFiles(input.optString("path", ""))
            "read_file" -> files.readFile(input.optString("path", ""), 1, Int.MAX_VALUE)
            "read_file_part" -> {
                val start = input.optInt("start_line", 1).coerceAtLeast(1)
                val end = input.optInt("end_line", start + 199)
                files.readFile(input.optString("path", ""), start, end)
            }
            "create_file" -> files.createFile(input.optString("path", ""), input.optString("content", ""))
            "edit_file" -> files.applyEdit(input.optString("path", ""), input.optString("old", ""), input.optString("new", ""))
            "write_file" -> files.writeFile(input.optString("path", ""), input.optString("content", ""))
            "delete_file" -> files.deleteFile(input.optString("path", ""), input.optBoolean("recursive", false))
            "make_directory" -> files.makeDirectory(input.optString("path", ""), input.optBoolean("create_parents", false))
            "find_files" -> files.findFiles(
                input.optString("path", ""),
                input.optString("pattern", ""),
                if (input.has("max_depth")) input.optInt("max_depth", -1) else -1,
                input.optBoolean("case_insensitive", false)
            )
            "file_exists" -> files.fileExists(input.optString("path", ""))
            "file_info" -> files.fileInfo(input.optString("path", ""))
            "shell" -> runShell(input.optString("command", ""))
            else -> ToolOutcome.Err("Unknown tool: $name")
        }
        return when (outcome) {
            is ToolOutcome.Ok -> ToolResult(outcome.text, false)
            is ToolOutcome.Err -> ToolResult(outcome.message, true)
        }
    }

    private fun runShell(command: String): ToolOutcome {
        if (command.isBlank()) return ToolOutcome.Err("command parameter is required")
        val result = ShizukuManager.execute(command)
        val sb = StringBuilder()
        sb.append("$ ").append(command).append('\n')
        if (result.stdout.isNotBlank()) sb.append(result.stdout.trimEnd()).append('\n')
        if (result.stderr.isNotBlank()) sb.append("[stderr] ").append(result.stderr.trimEnd()).append('\n')
        sb.append("[exit] ").append(result.exitCode)
        sb.append("  [identity] ").append(if (result.elevated) "shizuku" else "app")
        return if (result.exitCode == 0) {
            ToolOutcome.Ok(sb.toString().trimEnd())
        } else {
            ToolOutcome.Err(sb.toString().trimEnd())
        }
    }

    private fun spec(name: String, description: String, props: List<Pair<String, JSONObject>>): ToolSpec {
        val properties = JSONObject()
        val required = JSONArray()
        for ((key, value) in props) {
            properties.put(key, value)
            if (value.optBoolean("__required", false)) required.put(key)
            value.remove("__required")
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
        if (required.length() > 0) schema.put("required", required)
        return ToolSpec(name, description, schema)
    }

    private fun stringProp(description: String, required: Boolean = false): JSONObject =
        JSONObject().put("type", "string").put("description", description).put("__required", required)

    private fun intProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun boolProp(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)
}