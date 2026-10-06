package com.deepseek.harness.tools

import android.content.Context
import com.deepseek.harness.api.ToolSpec
import com.deepseek.harness.shell.ShizukuManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ToolResult(val content: String, val isError: Boolean = false)

class ToolRegistry(context: Context, workspace: File) {

    private val files = FileTools(workspace)
    private val search = SearchTools(workspace)
    private val archive = ArchiveTools(workspace)
    private val http = HttpTools()
    private val device = DeviceTools(context)

    fun specs(): List<ToolSpec> = fileSpecs() + searchSpecs() + archiveSpecs() + netSpecs() + deviceSpecs() + shellSpecs()

    fun execute(name: String, inputJson: String): ToolResult {
        val input = try {
            if (inputJson.isBlank()) JSONObject() else JSONObject(inputJson)
        } catch (e: Exception) {
            return ToolResult("Arguments are not valid JSON: ${e.message}", true)
        }
        val outcome = try {
            dispatch(name, input)
        } catch (e: Exception) {
            ToolOutcome.Err("Tool '$name' threw: ${e.message}")
        }
        return when (outcome) {
            is ToolOutcome.Ok -> ToolResult(outcome.text, false)
            is ToolOutcome.Err -> ToolResult(outcome.message, true)
        }
    }

    private fun dispatch(name: String, input: JSONObject): ToolOutcome = when (name) {
        "list_files" -> files.listFiles(input.optString("path", ""))
        "read_file" -> files.readFile(input.optString("path", ""), 1, Int.MAX_VALUE)
        "read_file_part" -> {
            val start = input.optInt("start_line", 1).coerceAtLeast(1)
            files.readFile(input.optString("path", ""), start, input.optInt("end_line", start + 199))
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
        "copy_file" -> files.copyFile(input.optString("source", ""), input.optString("destination", ""))
        "move_file" -> files.moveFile(input.optString("source", ""), input.optString("destination", ""))

        "grep_code" -> search.grepCode(
            input.optString("path", ""),
            input.optString("pattern", ""),
            input.optString("file_pattern", "*"),
            input.optBoolean("case_insensitive", false),
            input.optInt("context_lines", 3),
            input.optInt("max_results", 100)
        )
        "grep_context" -> search.grepContext(
            input.optString("path", ""),
            input.optString("intent", ""),
            input.optInt("max_results", 20)
        )

        "zip_files" -> archive.zipFiles(
            input.optJSONArray("sources")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            input.optString("destination", "")
        )
        "unzip_files" -> archive.unzipFiles(input.optString("source", ""), input.optString("destination", ""))
        "list_zip" -> archive.listZip(input.optString("source", ""))

        "http_request" -> http.request(
            input.optString("url", ""),
            input.optString("method", ""),
            input.optString("headers", ""),
            input.optString("body", ""),
            input.optString("body_type", "text")
        )

        "device_info" -> device.deviceInfo()
        "current_time" -> device.currentTime(input.optString("format", ""))
        "list_apps" -> device.listApps(input.optBoolean("include_system", false))
        "app_info" -> device.appInfo(input.optString("package_name", ""))
        "start_app" -> device.startApp(input.optString("package_name", ""))
        "stop_app" -> device.stopApp(input.optString("package_name", ""))
        "open_url" -> device.openUrl(input.optString("url", ""))
        "open_file" -> device.openFile(input.optString("path", ""), input.optString("mime_type", ""))
        "get_system_setting" -> device.getSystemSetting(input.optString("namespace", "system"), input.optString("key", ""))

        "shell" -> runShell(input.optString("command", ""))
        else -> ToolOutcome.Err("Unknown tool: $name")
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
        return if (result.exitCode == 0) ToolOutcome.Ok(sb.toString().trimEnd())
        else ToolOutcome.Err(sb.toString().trimEnd())
    }

    private fun fileSpecs(): List<ToolSpec> = listOf(
        spec("list_files", "List files and subdirectories in a directory.",
            "path" to stringProp("directory path, for example /sdcard/Download", required = true)),
        spec("read_file", "Read a text file. Content is returned with line numbers.",
            "path" to stringProp("file path", required = true)),
        spec("read_file_part", "Read a slice of a text file by line range. Use this for large files.",
            "path" to stringProp("file path", required = true),
            "start_line" to intProp("first line, 1-indexed, default 1"),
            "end_line" to intProp("last line, inclusive, default start_line + 199")),
        spec("create_file", "Create a new file. Fails if it already exists.",
            "path" to stringProp("file path", required = true),
            "content" to stringProp("full file content", required = true)),
        spec("edit_file", "Replace an exact snippet in an existing file. The 'old' text must appear exactly once.",
            "path" to stringProp("file path", required = true),
            "old" to stringProp("exact existing text to replace", required = true),
            "new" to stringProp("replacement text", required = true)),
        spec("write_file", "Overwrite a file, creating it if missing. Prefer create_file and edit_file.",
            "path" to stringProp("file path", required = true),
            "content" to stringProp("full new content", required = true)),
        spec("delete_file", "Delete a file or directory.",
            "path" to stringProp("target path", required = true),
            "recursive" to boolProp("true to delete a directory with contents, default false")),
        spec("make_directory", "Create a directory.",
            "path" to stringProp("directory path", required = true),
            "create_parents" to boolProp("true to create missing parents, default false")),
        spec("find_files", "Search for files by name pattern such as *.txt.",
            "path" to stringProp("directory to search, empty for workspace root"),
            "pattern" to stringProp("name pattern, for example *.txt", required = true),
            "max_depth" to intProp("maximum depth, -1 for unlimited"),
            "case_insensitive" to boolProp("true to ignore case, default false")),
        spec("file_exists", "Check whether a file or directory exists.",
            "path" to stringProp("path to check", required = true)),
        spec("file_info", "Show size, type and modification time.",
            "path" to stringProp("path to inspect", required = true)),
        spec("copy_file", "Copy a file or directory.",
            "source" to stringProp("source path", required = true),
            "destination" to stringProp("destination path", required = true)),
        spec("move_file", "Move or rename a file or directory.",
            "source" to stringProp("source path", required = true),
            "destination" to stringProp("destination path", required = true))
    )

    private fun searchSpecs(): List<ToolSpec> = listOf(
        spec("grep_code", "Search file contents with a regular expression. Returns matching lines with context.",
            "path" to stringProp("directory to search", required = true),
            "pattern" to stringProp("regular expression", required = true),
            "file_pattern" to stringProp("only search files matching this glob, default *"),
            "case_insensitive" to boolProp("true to ignore case, default false"),
            "context_lines" to intProp("lines of context around each match, default 3"),
            "max_results" to intProp("maximum matches to return, default 100")),
        spec("grep_context", "Find the lines most relevant to a natural language intent, ranked by keyword score.",
            "path" to stringProp("directory to search", required = true),
            "intent" to stringProp("what you are looking for, in natural language", required = true),
            "max_results" to intProp("maximum results, default 20"))
    )

    private fun archiveSpecs(): List<ToolSpec> = listOf(
        spec("zip_files", "Compress files or directories into a zip archive.",
            "sources" to arrayProp("list of paths to compress", required = true),
            "destination" to stringProp("output .zip path", required = true)),
        spec("unzip_files", "Extract a zip archive.",
            "source" to stringProp("the .zip path", required = true),
            "destination" to stringProp("output directory, empty for the archive's folder")),
        spec("list_zip", "List the entries inside a zip archive.",
            "source" to stringProp("the .zip path", required = true))
    )

    private fun netSpecs(): List<ToolSpec> = listOf(
        spec("http_request", "Send an HTTP request and return the response body.",
            "url" to stringProp("full URL", required = true),
            "method" to stringProp("GET, POST, PUT, PATCH or DELETE; default GET when body is empty"),
            "headers" to stringProp("request headers as a JSON object string"),
            "body" to stringProp("request body"),
            "body_type" to stringProp("json, form, xml or text; default text"))
    )

    private fun deviceSpecs(): List<ToolSpec> = listOf(
        spec("device_info", "Show device model, Android version, memory and storage."),
        spec("current_time", "Get the current date and time.",
            "format" to stringProp("SimpleDateFormat pattern, default yyyy-MM-dd HH:mm:ss")),
        spec("list_apps", "List installed applications.",
            "include_system" to boolProp("include system apps, default false")),
        spec("app_info", "Show details of an installed application.",
            "package_name" to stringProp("package name", required = true)),
        spec("start_app", "Launch an application by package name.",
            "package_name" to stringProp("package name", required = true)),
        spec("stop_app", "Stop an application's background processes.",
            "package_name" to stringProp("package name", required = true)),
        spec("open_url", "Open a URL in the default browser.",
            "url" to stringProp("the URL to open", required = true)),
        spec("open_file", "Open a file with the system's default application.",
            "path" to stringProp("file path", required = true),
            "mime_type" to stringProp("MIME type, for example text/plain or image/png")),
        spec("get_system_setting", "Read an Android system setting.",
            "namespace" to stringProp("system, secure or global; default system"),
            "key" to stringProp("setting key", required = true))
    )

    private fun shellSpecs(): List<ToolSpec> = listOf(
        spec("shell", "Run a shell command on the device. Uses Shizuku when available and authorized, otherwise falls back to the app's own identity. Returns stdout, stderr and the exit code.",
            "command" to stringProp("the shell command, for example ls -la /sdcard/Download", required = true))
    )

    private fun spec(name: String, description: String, vararg props: Pair<String, JSONObject>): ToolSpec =
        buildSpec(name, description, props.toList())

    private fun buildSpec(name: String, description: String, props: List<Pair<String, JSONObject>>): ToolSpec {
        val properties = JSONObject()
        val required = JSONArray()
        for ((key, value) in props) {
            properties.put(key, value)
            if (value.optBoolean(REQUIRED_KEY, false)) required.put(key)
            value.remove(REQUIRED_KEY)
        }
        val schema = JSONObject().put("type", "object").put("properties", properties)
        if (required.length() > 0) schema.put("required", required)
        return ToolSpec(name, description, schema)
    }

    private fun stringProp(description: String, required: Boolean = false): JSONObject =
        JSONObject().put("type", "string").put("description", description).put(REQUIRED_KEY, required)

    private fun intProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun boolProp(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun arrayProp(description: String, required: Boolean = false): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("description", description)
            .put("items", JSONObject().put("type", "string"))
            .put(REQUIRED_KEY, required)

    companion object {
        private const val REQUIRED_KEY = "__required"
    }
}