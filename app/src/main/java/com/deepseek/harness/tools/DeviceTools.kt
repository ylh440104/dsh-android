package com.deepseek.harness.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceTools(private val context: Context) {

    fun deviceInfo(): ToolOutcome {
        val sb = StringBuilder()
        sb.append("Device information:\n")
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n')
        sb.append("Brand: ").append(Build.BRAND).append('\n')
        sb.append("Model: ").append(Build.MODEL).append('\n')
        sb.append("Device: ").append(Build.DEVICE).append('\n')
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        sb.append("Bootloader: ").append(Build.BOOTLOADER).append('\n')
        sb.append("Locale: ").append(Locale.getDefault()).append('\n')
        sb.append("Timezone: ").append(java.util.TimeZone.getDefault().id).append('\n')

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append("\nMemory:\n")
            sb.append("Total: ").append(mi.totalMem / 1048576).append(" MB\n")
            sb.append("Available: ").append(mi.availMem / 1048576).append(" MB\n")
            sb.append("Low memory: ").append(mi.lowMemory).append('\n')
        }

        sb.append("\nStorage:\n")
        appendStorage(sb, "Internal", Environment.getDataDirectory())
        val external = Environment.getExternalStorageDirectory()
        if (external != null) appendStorage(sb, "External", external)

        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    private fun appendStorage(sb: StringBuilder, label: String, dir: File) {
        runCatching {
            val stat = StatFs(dir.path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            sb.append(label).append(": ")
                .append(free / 1073741824).append(" GB free / ")
                .append(total / 1073741824).append(" GB total\n")
        }
    }

    fun currentTime(format: String): ToolOutcome {
        val pattern = format.ifBlank { "yyyy-MM-dd HH:mm:ss" }
        val text = try {
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
        } catch (e: Exception) {
            return ToolOutcome.Err("Invalid format pattern: ${e.message}")
        }
        return ToolOutcome.Ok("Current time: $text\nEpoch millis: ${System.currentTimeMillis()}")
    }

    fun listApps(includeSystem: Boolean): ToolOutcome {
        val pm = context.packageManager
        val packages = if (includeSystem) {
            pm.getInstalledPackages(0)
        } else {
            pm.getInstalledPackages(0).filter { info ->
                (info.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0
            }
        }
        if (packages.isEmpty()) return ToolOutcome.Ok("No applications found")
        val sorted = packages.sortedBy { it.packageName }
        val sb = StringBuilder()
        sb.append("Installed applications (").append(sorted.size).append("):\n")
        for (info in sorted.take(MAX_APPS)) {
            val label = runCatching { pm.getApplicationLabel(info.applicationInfo!!).toString() }.getOrDefault("")
            sb.append(info.packageName)
            if (label.isNotEmpty()) sb.append("  (").append(label).append(")")
            sb.append("  v").append(info.versionName ?: "?")
            sb.append('\n')
        }
        if (sorted.size > MAX_APPS) sb.append("… and ").append(sorted.size - MAX_APPS).append(" more")
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun appInfo(packageName: String): ToolOutcome {
        if (packageName.isBlank()) return ToolOutcome.Err("package_name parameter is required")
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return ToolOutcome.Err("Application not installed: $packageName")
        }
        val appInfo = info.applicationInfo
        val sb = StringBuilder()
        sb.append("Package: ").append(info.packageName).append('\n')
        sb.append("Version: ").append(info.versionName ?: "?").append(" (code ").append(info.longVersionCode).append(")\n")
        if (appInfo != null) {
            sb.append("Label: ").append(runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault("")).append('\n')
            sb.append("System app: ").append(appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0).append('\n')
            sb.append("Enabled: ").append(appInfo.enabled).append('\n')
            sb.append("Source dir: ").append(appInfo.sourceDir).append('\n')
            sb.append("Data dir: ").append(appInfo.dataDir).append('\n')
        }
        sb.append("First install: ").append(Date(info.firstInstallTime)).append('\n')
        sb.append("Last update: ").append(Date(info.lastUpdateTime))
        return ToolOutcome.Ok(sb.toString())
    }

    fun startApp(packageName: String): ToolOutcome {
        if (packageName.isBlank()) return ToolOutcome.Err("package_name parameter is required")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolOutcome.Err("No launchable activity for $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolOutcome.Ok("Launched $packageName")
        } catch (e: Exception) {
            ToolOutcome.Err("Failed to launch: ${e.message}")
        }
    }

    fun stopApp(packageName: String): ToolOutcome {
        if (packageName.isBlank()) return ToolOutcome.Err("package_name parameter is required")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return ToolOutcome.Err("ActivityManager unavailable")
        return try {
            am.killBackgroundProcesses(packageName)
            ToolOutcome.Ok("Requested stop for $packageName")
        } catch (e: Exception) {
            ToolOutcome.Err("Failed to stop: ${e.message}")
        }
    }

    fun openUrl(url: String): ToolOutcome {
        if (url.isBlank()) return ToolOutcome.Err("url parameter is required")
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ToolOutcome.Ok("Opened $url")
        } catch (e: Exception) {
            ToolOutcome.Err("Cannot open url: ${e.message}")
        }
    }

    fun openFile(path: String, mimeType: String): ToolOutcome {
        if (path.isBlank()) return ToolOutcome.Err("path parameter is required")
        val file = File(PathGuard.normalize(path))
        if (!file.exists()) return ToolOutcome.Err("No such file: ${file.path}")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            ToolOutcome.Ok("Opened ${file.path}")
        } catch (e: Exception) {
            ToolOutcome.Err("No application can open this file: ${e.message}")
        }
    }

    fun getSystemSetting(namespace: String, key: String): ToolOutcome {
        if (key.isBlank()) return ToolOutcome.Err("key parameter is required")
        val value = when (namespace.lowercase()) {
            "global" -> Settings.Global.getString(context.contentResolver, key)
            "secure" -> Settings.Secure.getString(context.contentResolver, key)
            else -> Settings.System.getString(context.contentResolver, key)
        } ?: return ToolOutcome.Ok("Setting '$key' in '$namespace' is not set")
        return ToolOutcome.Ok("$namespace/$key = $value")
    }

    companion object {
        private const val MAX_APPS = 150
    }
}