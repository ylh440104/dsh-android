package com.deepseek.harness.tools

import android.content.Context
import com.deepseek.harness.shell.ShizukuManager
import java.io.File

class UiTools(private val context: Context) {

    fun tap(x: Int, y: Int): ToolOutcome {
        val result = ShizukuManager.execute("input tap $x $y")
        return shellOutcome(result, "Tapped ($x, $y)")
    }

    fun longPress(x: Int, y: Int, durationMs: Long): ToolOutcome {
        val ms = durationMs.coerceIn(300L, 10_000L)
        val result = ShizukuManager.execute("input swipe $x $y $x $y $ms")
        return shellOutcome(result, "Long pressed ($x, $y) for ${ms}ms")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ToolOutcome {
        val ms = durationMs.coerceIn(50L, 10_000L)
        val result = ShizukuManager.execute("input swipe $x1 $y1 $x2 $y2 $ms")
        return shellOutcome(result, "Swiped ($x1, $y1) -> ($x2, $y2) in ${ms}ms")
    }

    fun pressKey(key: String): ToolOutcome {
        val code = keyCodeOf(key)
            ?: return ToolOutcome.Err("Unknown key '$key'. Use back, home, menu, enter, delete, tab, escape, power, volume_up, volume_down, camera or a numeric keycode")
        val result = ShizukuManager.execute("input keyevent $code")
        return shellOutcome(result, "Pressed $key")
    }

    fun inputText(text: String): ToolOutcome {
        if (text.isEmpty()) return ToolOutcome.Err("text parameter is required")
        val escaped = text
            .replace("\\", "\\\\")
            .replace(" ", "%s")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace(";", "\\;")
            .replace("|", "\\|")
            .replace("*", "\\*")
            .replace("$", "\\$")
            .replace("`", "\\`")
        val result = ShizukuManager.execute("input text '$escaped'")
        return shellOutcome(result, "Typed ${text.length} character(s)")
    }

    fun screenshot(path: String): ToolOutcome {
        val target = if (path.isBlank()) {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "screenshot_${System.currentTimeMillis()}.png")
        } else {
            File(PathGuard.normalize(path))
        }
        target.parentFile?.mkdirs()
        val remote = "/sdcard/.dsh_shot_${System.currentTimeMillis()}.png"
        val capture = ShizukuManager.execute("screencap -p $remote")
        if (capture.exitCode != 0) {
            return ToolOutcome.Err("screencap failed: ${capture.stderr.ifBlank { capture.stdout }}")
        }
        val copy = ShizukuManager.execute("cp $remote ${target.absolutePath}")
        ShizukuManager.execute("rm -f $remote")
        if (copy.exitCode != 0) {
            return ToolOutcome.Err("Could not save screenshot to ${target.absolutePath}: ${copy.stderr}")
        }
        if (!target.exists()) {
            return ToolOutcome.Err("Screenshot was captured but not written to ${target.absolutePath}")
        }
        return ToolOutcome.Ok("Screenshot saved: ${target.absolutePath} (${target.length()} bytes)")
    }

    fun screenSize(): ToolOutcome {
        val result = ShizukuManager.execute("wm size")
        val density = ShizukuManager.execute("wm density")
        val sb = StringBuilder()
        if (result.exitCode == 0) {
            sb.append(result.stdout.trim()).append('\n')
        } else {
            val metrics = context.resources.displayMetrics
            sb.append("Physical size: ").append(metrics.widthPixels).append('x').append(metrics.heightPixels).append('\n')
        }
        if (density.exitCode == 0) sb.append(density.stdout.trim())
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun currentActivity(): ToolOutcome {
        val attempts = listOf(
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -5",
            "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -5"
        )
        for (command in attempts) {
            val result = ShizukuManager.execute(command)
            if (result.exitCode == 0 && result.stdout.isNotBlank()) {
                return ToolOutcome.Ok(result.stdout.trim())
            }
        }
        return ToolOutcome.Err("Could not determine the current activity; Shizuku may be unavailable")
    }

    fun listWindows(): ToolOutcome {
        val result = ShizukuManager.execute("dumpsys window windows | grep -E 'Window #|mOwnerUid' | head -40")
        if (result.exitCode != 0 || result.stdout.isBlank()) {
            return ToolOutcome.Err("Could not list windows; Shizuku may be unavailable")
        }
        return ToolOutcome.Ok(result.stdout.trim())
    }

    fun launchActivity(component: String): ToolOutcome {
        if (component.isBlank()) return ToolOutcome.Err("component parameter is required")
        val result = ShizukuManager.execute("am start -n $component")
        return shellOutcome(result, "Started $component")
    }

    fun forceStop(packageName: String): ToolOutcome {
        if (packageName.isBlank()) return ToolOutcome.Err("package_name parameter is required")
        val result = ShizukuManager.execute("am force-stop $packageName")
        return shellOutcome(result, "Force stopped $packageName")
    }

    fun clearAppData(packageName: String): ToolOutcome {
        if (packageName.isBlank()) return ToolOutcome.Err("package_name parameter is required")
        val result = ShizukuManager.execute("pm clear $packageName")
        return shellOutcome(result, "Cleared data for $packageName")
    }

    fun grantPermission(packageName: String, permission: String): ToolOutcome {
        if (packageName.isBlank() || permission.isBlank()) {
            return ToolOutcome.Err("package_name and permission are both required")
        }
        val result = ShizukuManager.execute("pm grant $packageName $permission")
        return shellOutcome(result, "Granted $permission to $packageName")
    }

    fun installApk(path: String): ToolOutcome {
        if (path.isBlank()) return ToolOutcome.Err("path parameter is required")
        val file = File(PathGuard.normalize(path))
        if (!file.exists()) return ToolOutcome.Err("No such file: ${file.path}")
        val result = ShizukuManager.execute("pm install -r -t '${file.absolutePath}'")
        return shellOutcome(result, "Installed ${file.name}")
    }

    fun uninstallPackage(packageName: String): ToolOutcome {
        if (packageName.isBlank()) return ToolOutcome.Err("package_name parameter is required")
        val result = ShizukuManager.execute("pm uninstall $packageName")
        return shellOutcome(result, "Uninstalled $packageName")
    }

    private fun shellOutcome(result: com.deepseek.harness.shell.ShellResult, successMessage: String): ToolOutcome {
        if (result.exitCode == 0) {
            val extra = result.stdout.trim()
            return ToolOutcome.Ok(if (extra.isEmpty()) successMessage else "$successMessage\n$extra")
        }
        val detail = result.stderr.ifBlank { result.stdout }.trim()
        val hint = if (result.elevated) "" else " (Shizuku is not active, so this ran with the app's own limited identity)"
        return ToolOutcome.Err("$successMessage failed$hint: ${detail.ifEmpty { "exit code ${result.exitCode}" }}")
    }

    private fun keyCodeOf(key: String): String? = when (key.lowercase().trim()) {
        "back" -> "KEYCODE_BACK"
        "home" -> "KEYCODE_HOME"
        "menu" -> "KEYCODE_MENU"
        "enter" -> "KEYCODE_ENTER"
        "delete", "del", "backspace" -> "KEYCODE_DEL"
        "tab" -> "KEYCODE_TAB"
        "escape", "esc" -> "KEYCODE_ESCAPE"
        "power" -> "KEYCODE_POWER"
        "volume_up" -> "KEYCODE_VOLUME_UP"
        "volume_down" -> "KEYCODE_VOLUME_DOWN"
        "camera" -> "KEYCODE_CAMERA"
        "up" -> "KEYCODE_DPAD_UP"
        "down" -> "KEYCODE_DPAD_DOWN"
        "left" -> "KEYCODE_DPAD_LEFT"
        "right" -> "KEYCODE_DPAD_RIGHT"
        "center", "ok" -> "KEYCODE_DPAD_CENTER"
        "app_switch", "recents" -> "KEYCODE_APP_SWITCH"
        "notification" -> "KEYCODE_NOTIFICATION"
        "wakeup" -> "KEYCODE_WAKEUP"
        "sleep" -> "KEYCODE_SLEEP"
        else -> key.trim().takeIf { it.matches(Regex("\\d+")) }
    }
}