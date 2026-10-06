package com.deepseek.harness.shell

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val elevated: Boolean = false
) {
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

    fun execute(command: String): ShellResult {
        val parts = parseCommand(command)
        if (parts.isEmpty()) return ShellResult("", "命令为空", -1)
        if (isRunning() && hasPermission()) {
            runElevated(parts)?.let { return it }
        }
        return runLocal(parts)
    }

    private fun runElevated(parts: List<String>): ShellResult? = try {
        val service = Shizuku::class.java
            .getDeclaredMethod("requireService")
            .apply { isAccessible = true }
            .invoke(null) as? IShizukuService
        if (service == null) {
            null
        } else {
            val process = service.newProcess(parts.toTypedArray(), null, null)
            if (process == null) {
                null
            } else {
                val stdout = readFd(process.inputStream)
                val stderr = readFd(process.errorStream)
                val exit = process.waitFor()
                runCatching { process.destroy() }
                ShellResult(stdout, stderr, exit, elevated = true)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun readFd(pfd: ParcelFileDescriptor?): String {
        if (pfd == null) return ""
        return try {
            FileInputStream(pfd.fileDescriptor).use { input ->
                BufferedReader(InputStreamReader(input)).use { it.readText() }
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun runLocal(parts: List<String>): ShellResult = try {
        val process = ProcessBuilder(parts).start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        ShellResult(stdout, stderr, process.waitFor(), elevated = false)
    } catch (e: Exception) {
        ShellResult("", "命令执行失败：${e.message}", -1, elevated = false)
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