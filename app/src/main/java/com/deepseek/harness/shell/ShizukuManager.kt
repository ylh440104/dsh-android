package com.deepseek.harness.shell

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int) {
    val ok: Boolean get() = exitCode == 0
}

object ShizukuManager {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4001
    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        pingBinder()
    } catch (e: Exception) {
        false
    }

    fun isRunning(): Boolean = pingBinder()

    private fun pingBinder(): Boolean = try {
        if (Shizuku.pingBinder()) {
            true
        } else {
            val binder = Shizuku.getBinder()
            binder != null && binder.isBinderAlive
        }
    } catch (e: Exception) {
        false
    }

    fun hasPermission(): Boolean = try {
        if (!isRunning()) false else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isRunning()) {
            onResult(false)
            return
        }
        if (hasPermission()) {
            onResult(true)
            return
        }
        try {
            permissionListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
        } catch (e: Exception) {
        }
        val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == REQUEST_CODE) {
                val granted = result == PackageManager.PERMISSION_GRANTED
                try {
                    permissionListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
                } catch (e: Exception) {
                }
                permissionListener = null
                onResult(granted)
            }
        }
        permissionListener = listener
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            permissionListener = null
            onResult(false)
        }
    }

    fun execute(command: String, timeoutMs: Long = 30000L): ShellResult {
        if (!isRunning()) return ShellResult("", "Shizuku 服务未运行，请先启动 Shizuku", -1)
        if (!hasPermission()) return ShellResult("", "Shizuku 权限未授予", -1)
        val parts = parseCommand(command)
        if (parts.isEmpty()) return ShellResult("", "命令为空", -1)
        return try {
            val process = Shizuku.newProcess(parts.toTypedArray(), null, null)
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            ShellResult(stdout, stderr, exit)
        } catch (e: Exception) {
            ShellResult("", "命令执行失败：${e.message}", -1)
        }
    }

    private fun parseCommand(command: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (ch in command) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' -> escaped = true
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        parts.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }
}