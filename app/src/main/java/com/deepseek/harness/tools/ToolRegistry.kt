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
    private val compute = ComputeTools()
    private val system = SystemActionTools(context)
    private val ui = UiTools(context)
    private val web = WebTools()

    fun specs(): List<ToolSpec> =
        fileSpecs() + searchSpecs() + archiveSpecs() + netSpecs() + webSpecs() +
            deviceSpecs() + systemSpecs() + uiSpecs() + computeSpecs() + shellSpecs()

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

        "visit_web" -> web.visitWeb(
            input.optString("url", ""),
            input.optBoolean("include_links", false),
            input.optInt("max_chars", 40000)
        )
        "download_file" -> web.downloadFile(input.optString("url", ""), input.optString("destination", ""))

        "clipboard_get" -> system.clipboardGet()
        "clipboard_set" -> system.clipboardSet(input.optString("text", ""))
        "toast" -> system.toast(input.optString("message", ""))
        "send_notification" -> system.sendNotification(input.optString("title", ""), input.optString("message", ""))
        "vibrate" -> system.vibrate(input.optLong("duration_ms", 300))
        "battery_status" -> system.batteryStatus()
        "network_status" -> system.networkStatus()
        "wifi_info" -> system.wifiInfo()
        "get_device_location" -> system.location()
        "volume" -> system.volume(input.optString("action", "get"), input.optInt("level", 0))
        "brightness" -> system.brightness(input.optString("action", "get"), input.optInt("level", 0))
        "modify_system_setting" -> system.modifySystemSetting(
            input.optString("namespace", "system"),
            input.optString("key", ""),
            input.optString("value", "")
        )
        "execute_intent" -> system.executeIntent(
            input.optString("action", ""),
            input.optString("uri", ""),
            input.optString("package_name", ""),
            input.optString("extras", "")
        )
        "send_broadcast" -> system.sendBroadcast(input.optString("action", ""), input.optString("extras", ""))
        "install_apk" -> system.installApp(input.optString("path", ""))
        "uninstall_app" -> system.uninstallApp(input.optString("package_name", ""))
        "list_processes" -> system.listProcesses()
        "screen_info" -> system.screenInfo()

        "tap" -> ui.tap(input.optInt("x", -1), input.optInt("y", -1))
        "long_press" -> ui.longPress(input.optInt("x", -1), input.optInt("y", -1), input.optLong("duration_ms", 800))
        "swipe" -> ui.swipe(
            input.optInt("x1", -1), input.optInt("y1", -1),
            input.optInt("x2", -1), input.optInt("y2", -1),
            input.optLong("duration_ms", 300)
        )
        "press_key" -> ui.pressKey(input.optString("key", ""))
        "set_input_text" -> ui.inputText(input.optString("text", ""))
        "screenshot" -> ui.screenshot(input.optString("path", ""))
        "screen_size" -> ui.screenSize()
        "current_activity" -> ui.currentActivity()
        "list_windows" -> ui.listWindows()
        "launch_activity" -> ui.launchActivity(input.optString("component", ""))
        "force_stop" -> ui.forceStop(input.optString("package_name", ""))
        "clear_app_data" -> ui.clearAppData(input.optString("package_name", ""))
        "grant_permission" -> ui.grantPermission(input.optString("package_name", ""), input.optString("permission", ""))
        "pm_install" -> ui.installApk(input.optString("path", ""))
        "pm_uninstall" -> ui.uninstallPackage(input.optString("package_name", ""))

        "calculate" -> compute.calculate(input.optString("expression", ""), input.optInt("precision", 6))
        "base64_encode" -> compute.base64Encode(input.optString("text", ""))
        "base64_decode" -> compute.base64Decode(input.optString("text", ""))
        "hash_text" -> compute.hash(input.optString("text", ""), input.optString("algorithm", "sha256"))
        "uuid" -> compute.uuid(input.optInt("count", 1))
        "random_number" -> compute.randomNumber(
            input.optLong("min", 0L),
            input.optLong("max", 100L),
            input.optInt("count", 1)
        )
        "text_transform" -> compute.textTransform(
            input.optString("text", ""),
            input.optString("operation", ""),
            input.optString("argument", "")
        )
        "regex_op" -> compute.regexOp(
            input.optString("text", ""),
            input.optString("pattern", ""),
            input.optString("operation", "match"),
            input.optString("replacement", "")
        )
        "sort_lines" -> compute.sortLines(
            input.optString("text", ""),
            input.optString("order", "asc"),
            input.optBoolean("dedupe", false)
        )
        "diff_text" -> compute.diffText(input.optString("left", ""), input.optString("right", ""))
        "url_encode" -> compute.urlEncode(input.optString("text", ""))
        "url_decode" -> compute.urlDecode(input.optString("text", ""))
        "json_format" -> compute.jsonFormat(input.optString("text", ""), input.optInt("indent", 2))
        "date_convert" -> compute.dateConvert(
            input.optString("input", ""),
            input.optString("from_pattern", ""),
            input.optString("to_pattern", "")
        )
        "convert_unit" -> compute.convertUnit(
            input.optDouble("value", 0.0),
            input.optString("from", ""),
            input.optString("to", "")
        )

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

    private fun webSpecs(): List<ToolSpec> = listOf(
        spec("visit_web", "Fetch a web page and return its readable text, plus optional links.",
            "url" to stringProp("page URL", required = true),
            "include_links" to boolProp("also list the page's links, default false"),
            "max_chars" to intProp("maximum characters of text to return, default 40000")),
        spec("download_file", "Download a file from a URL to local storage.",
            "url" to stringProp("file URL", required = true),
            "destination" to stringProp("local save path", required = true))
    )

    private fun systemSpecs(): List<ToolSpec> = listOf(
        spec("clipboard_get", "Read the current clipboard text."),
        spec("clipboard_set", "Write text to the clipboard.",
            "text" to stringProp("text to copy", required = true)),
        spec("toast", "Show a short toast message on screen.",
            "message" to stringProp("message to show", required = true)),
        spec("send_notification", "Post a system notification.",
            "title" to stringProp("notification title"),
            "message" to stringProp("notification body", required = true)),
        spec("vibrate", "Vibrate the device.",
            "duration_ms" to intProp("vibration length in milliseconds, default 300")),
        spec("battery_status", "Show battery level, charging state, current and temperature."),
        spec("network_status", "Show active network transports and capabilities."),
        spec("wifi_info", "Show Wifi state, SSID, link speed and IP address."),
        spec("get_device_location", "Read the last known location from enabled providers."),
        spec("volume", "Read or change media volume.",
            "action" to stringProp("get, up, down, mute, unmute or set; default get"),
            "level" to intProp("target level for action=set")),
        spec("brightness", "Read or change screen brightness. Changing it needs WRITE_SETTINGS.",
            "action" to stringProp("get or set; default get"),
            "level" to intProp("brightness 1-255 for action=set")),
        spec("modify_system_setting", "Write an Android system setting. Needs WRITE_SETTINGS.",
            "namespace" to stringProp("system, secure or global; default system"),
            "key" to stringProp("setting key", required = true),
            "value" to stringProp("new value", required = true)),
        spec("execute_intent", "Dispatch an Android intent.",
            "action" to stringProp("intent action, for example android.intent.action.VIEW", required = true),
            "uri" to stringProp("optional data URI"),
            "package_name" to stringProp("optional target package"),
            "extras" to stringProp("optional extras as a JSON object of strings")),
        spec("send_broadcast", "Send a broadcast intent.",
            "action" to stringProp("broadcast action", required = true),
            "extras" to stringProp("optional extras as a JSON object of strings")),
        spec("install_apk", "Open the system installer for an APK file.",
            "path" to stringProp("path to the .apk file", required = true)),
        spec("uninstall_app", "Open the system uninstall prompt for an app.",
            "package_name" to stringProp("package name", required = true)),
        spec("list_processes", "List running processes with pid and importance."),
        spec("screen_info", "Show screen size, density, orientation and timeout.")
    )

    private fun uiSpecs(): List<ToolSpec> = listOf(
        spec("tap", "Tap the screen at a coordinate. Needs Shizuku.",
            "x" to intProp("x coordinate in pixels"),
            "y" to intProp("y coordinate in pixels")),
        spec("long_press", "Long press the screen at a coordinate. Needs Shizuku.",
            "x" to intProp("x coordinate in pixels"),
            "y" to intProp("y coordinate in pixels"),
            "duration_ms" to intProp("press duration, default 800")),
        spec("swipe", "Swipe between two points. Needs Shizuku.",
            "x1" to intProp("start x"),
            "y1" to intProp("start y"),
            "x2" to intProp("end x"),
            "y2" to intProp("end y"),
            "duration_ms" to intProp("swipe duration, default 300")),
        spec("press_key", "Press a hardware or navigation key. Needs Shizuku.",
            "key" to stringProp("back, home, menu, enter, delete, tab, escape, power, volume_up, volume_down, camera, up, down, left, right, center, app_switch, notification, wakeup, sleep or a numeric keycode", required = true)),
        spec("set_input_text", "Type text into the focused input field. Needs Shizuku.",
            "text" to stringProp("text to type", required = true)),
        spec("screenshot", "Capture a screenshot to a PNG file. Needs Shizuku.",
            "path" to stringProp("save path, defaults to the app's external files directory")),
        spec("screen_size", "Show the display resolution and density."),
        spec("current_activity", "Show the currently focused activity and package. Needs Shizuku."),
        spec("list_windows", "List visible windows. Needs Shizuku."),
        spec("launch_activity", "Start a specific activity component. Needs Shizuku.",
            "component" to stringProp("component such as com.example/.MainActivity", required = true)),
        spec("force_stop", "Force stop an application. Needs Shizuku.",
            "package_name" to stringProp("package name", required = true)),
        spec("clear_app_data", "Clear all data of an application. Needs Shizuku.",
            "package_name" to stringProp("package name", required = true)),
        spec("grant_permission", "Grant a runtime permission to an app. Needs Shizuku.",
            "package_name" to stringProp("package name", required = true),
            "permission" to stringProp("permission such as android.permission.CAMERA", required = true)),
        spec("pm_install", "Install an APK silently through the package manager. Needs Shizuku.",
            "path" to stringProp("path to the .apk file", required = true)),
        spec("pm_uninstall", "Uninstall a package silently. Needs Shizuku.",
            "package_name" to stringProp("package name", required = true))
    )

    private fun computeSpecs(): List<ToolSpec> = listOf(
        spec("calculate", "Evaluate a math expression. Supports + - * / % ^, parentheses, pi, e, sqrt, abs, sin, cos, tan, ln, log, floor, ceil, round, min, max and pow.",
            "expression" to stringProp("expression such as (2+3)*4^2 or sqrt(144)", required = true),
            "precision" to intProp("decimal places, 0-12, default 6")),
        spec("base64_encode", "Base64 encode text.",
            "text" to stringProp("text to encode", required = true)),
        spec("base64_decode", "Base64 decode text.",
            "text" to stringProp("base64 input", required = true)),
        spec("hash_text", "Compute a hash of text.",
            "text" to stringProp("input text", required = true),
            "algorithm" to stringProp("md5, sha1, sha224, sha256, sha384 or sha512; default sha256")),
        spec("uuid", "Generate random UUIDs.",
            "count" to intProp("how many to generate, 1-50, default 1")),
        spec("random_number", "Generate random integers in a range.",
            "min" to intProp("inclusive lower bound, default 0"),
            "max" to intProp("inclusive upper bound, default 100"),
            "count" to intProp("how many to generate, 1-200, default 1")),
        spec("text_transform", "Apply a text operation such as upper, lower, reverse, replace, split or join.",
            "text" to stringProp("input text", required = true),
            "operation" to stringProp("upper, lower, capitalize, trim, reverse, length, lines, words, chars, replace, split, join, strip_html, escape_json, collapse_spaces or dedent", required = true),
            "argument" to stringProp("extra argument; for replace use old=>new")),
        spec("regex_op", "Run a regular expression operation over text.",
            "text" to stringProp("input text", required = true),
            "pattern" to stringProp("regular expression", required = true),
            "operation" to stringProp("match, find_all, replace, replace_first, split or test; default match"),
            "replacement" to stringProp("replacement text for replace operations")),
        spec("sort_lines", "Sort the lines of a text block.",
            "text" to stringProp("input text", required = true),
            "order" to stringProp("asc, desc, numeric or length; default asc"),
            "dedupe" to boolProp("remove duplicate lines, default false")),
        spec("diff_text", "Compare two texts line by line.",
            "left" to stringProp("first text", required = true),
            "right" to stringProp("second text", required = true)),
        spec("url_encode", "Percent-encode text for use in a URL.",
            "text" to stringProp("text to encode", required = true)),
        spec("url_decode", "Decode percent-encoded URL text.",
            "text" to stringProp("encoded text", required = true)),
        spec("json_format", "Pretty print or compact a JSON string.",
            "text" to stringProp("JSON input", required = true),
            "indent" to intProp("indent width; 0 compacts the JSON, default 2")),
        spec("date_convert", "Reformat a date string between patterns.",
            "input" to stringProp("date text; empty means now"),
            "from_pattern" to stringProp("source SimpleDateFormat pattern, default yyyy-MM-dd HH:mm:ss"),
            "to_pattern" to stringProp("target SimpleDateFormat pattern, default yyyy-MM-dd HH:mm:ss")),
        spec("convert_unit", "Convert between units of length, mass, temperature, volume or data size.",
            "value" to intProp("numeric value to convert"),
            "from" to stringProp("source unit such as km, kg, c, l, mb", required = true),
            "to" to stringProp("target unit such as mile, lb, f, gal, gb", required = true))
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