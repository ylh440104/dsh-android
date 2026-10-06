package com.deepseek.harness.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.io.File

class SystemActionTools(private val context: Context) {

    fun clipboardGet(): ToolOutcome {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolOutcome.Err("Clipboard service unavailable")
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) return ToolOutcome.Ok("Clipboard is empty")
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val text = clip.getItemAt(i).coerceToText(context).toString()
            if (text.isNotEmpty()) sb.append(text).append('\n')
        }
        if (sb.isEmpty()) return ToolOutcome.Ok("Clipboard holds no text")
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun clipboardSet(text: String): ToolOutcome {
        if (text.isEmpty()) return ToolOutcome.Err("text parameter is required")
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolOutcome.Err("Clipboard service unavailable")
        manager.setPrimaryClip(ClipData.newPlainText("dsh", text))
        return ToolOutcome.Ok("Copied ${text.length} character(s) to the clipboard")
    }

    fun toast(message: String): ToolOutcome {
        if (message.isBlank()) return ToolOutcome.Err("message parameter is required")
        return try {
            android.os.Handler(context.mainLooper).post {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
            ToolOutcome.Ok("Toast shown: $message")
        } catch (e: Exception) {
            ToolOutcome.Err("Failed to show toast: ${e.message}")
        }
    }

    fun sendNotification(title: String, message: String): ToolOutcome {
        if (message.isBlank()) return ToolOutcome.Err("message parameter is required")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ToolOutcome.Err("Notification service unavailable")
        val channelId = "dsh_agent"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Harness", NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.ifBlank { "DeepSeek Harness" })
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(nextNotificationId(), notification)
            ToolOutcome.Ok("Notification posted")
        } catch (e: Exception) {
            ToolOutcome.Err("Failed to post notification: ${e.message}")
        }
    }

    fun vibrate(durationMs: Long): ToolOutcome {
        val ms = durationMs.coerceIn(1L, 10_000L)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return ToolOutcome.Err("Vibrator unavailable")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
            ToolOutcome.Ok("Vibrated for ${ms}ms")
        } catch (e: Exception) {
            ToolOutcome.Err("Vibration failed: ${e.message}")
        }
    }

    fun batteryStatus(): ToolOutcome {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return ToolOutcome.Err("Battery service unavailable")
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sb = StringBuilder()
        sb.append("Level: ").append(level).append("%\n")
        val status = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        sb.append("Status: ").append(
            when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                else -> "unknown"
            }
        ).append('\n')
        val charging = manager.isCharging
        sb.append("Plugged in: ").append(charging).append('\n')
        val currentNow = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (currentNow != Int.MIN_VALUE) {
            sb.append("Current: ").append(currentNow / 1000).append(" mA\n")
        }
        val temp = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = temp?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (temperature > 0) sb.append("Temperature: ").append(temperature / 10.0).append(" °C\n")
        val voltage = temp?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        if (voltage > 0) sb.append("Voltage: ").append(voltage).append(" mV")
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun networkStatus(): ToolOutcome {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ToolOutcome.Err("Connectivity service unavailable")
        val sb = StringBuilder()
        val network = manager.activeNetwork
        if (network == null) {
            return ToolOutcome.Ok("No active network")
        }
        val caps = manager.getNetworkCapabilities(network)
        if (caps == null) {
            return ToolOutcome.Ok("Active network has no capabilities")
        }
        sb.append("Wifi: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).append('\n')
        sb.append("Cellular: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).append('\n')
        sb.append("Ethernet: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).append('\n')
        sb.append("VPN: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).append('\n')
        sb.append("Internet: ").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).append('\n')
        sb.append("Validated: ").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).append('\n')
        sb.append("Downstream: ").append(caps.linkDownstreamBandwidthKbps).append(" kbps\n")
        sb.append("Upstream: ").append(caps.linkUpstreamBandwidthKbps).append(" kbps")
        return ToolOutcome.Ok(sb.toString())
    }

    fun wifiInfo(): ToolOutcome {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ToolOutcome.Err("Wifi service unavailable")
        @Suppress("DEPRECATION")
        val enabled = manager.isWifiEnabled
        val sb = StringBuilder()
        sb.append("Wifi enabled: ").append(enabled).append('\n')
        @Suppress("DEPRECATION")
        val info = manager.connectionInfo
        if (info != null) {
            val ssid = info.ssid?.trim('"')
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") {
                sb.append("SSID: ").append(ssid).append('\n')
            }
            sb.append("Link speed: ").append(info.linkSpeed).append(" Mbps\n")
            val rssi = info.rssi
            if (rssi != 0) sb.append("RSSI: ").append(rssi).append(" dBm\n")
            sb.append("IP: ").append(intToIp(info.ipAddress))
        }
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    private fun intToIp(value: Int): String {
        if (value == 0) return "unknown"
        return "${value and 0xff}.${value shr 8 and 0xff}.${value shr 16 and 0xff}.${value shr 24 and 0xff}"
    }

    fun location(): ToolOutcome {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ToolOutcome.Err("Location service unavailable")
        val providers = manager.getProviders(true)
        if (providers.isEmpty()) return ToolOutcome.Ok("No location provider is enabled")
        val sb = StringBuilder()
        for (provider in providers) {
            val last = try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            }
            sb.append(provider).append(": ")
            if (last == null) {
                sb.append("no cached fix")
            } else {
                sb.append("lat ").append(last.latitude)
                    .append(", lon ").append(last.longitude)
                    .append(", accuracy ").append(last.accuracy).append("m")
                sb.append(", time ").append(java.util.Date(last.time))
            }
            sb.append('\n')
        }
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun volume(action: String, level: Int): ToolOutcome {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolOutcome.Err("Audio service unavailable")
        val stream = AudioManager.STREAM_MUSIC
        val max = manager.getStreamMaxVolume(stream)
        val current = manager.getStreamVolume(stream)
        when (action.lowercase()) {
            "up" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "set" -> {
                val target = level.coerceIn(0, max)
                manager.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
            }
            "get" -> Unit
            else -> return ToolOutcome.Err("Unknown action '$action'. Use get, up, down, mute, unmute or set")
        }
        val after = manager.getStreamVolume(stream)
        val percent = if (max > 0) after * 100 / max else 0
        return ToolOutcome.Ok("Media volume: $after/$max ($percent%)")
    }

    fun brightness(action: String, level: Int): ToolOutcome {
        val resolver = context.contentResolver
        return try {
            when (action.lowercase()) {
                "get" -> {
                    val value = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                    if (value < 0) ToolOutcome.Err("Cannot read screen brightness")
                    else ToolOutcome.Ok("Screen brightness: $value/255")
                }
                "set" -> {
                    val target = level.coerceIn(1, 255)
                    val ok = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
                    if (ok) ToolOutcome.Ok("Screen brightness set to $target/255")
                    else ToolOutcome.Err("Failed to write brightness; the app may lack WRITE_SETTINGS permission")
                }
                else -> ToolOutcome.Err("Unknown action '$action'. Use get or set")
            }
        } catch (e: Exception) {
            ToolOutcome.Err("Brightness operation failed: ${e.message}")
        }
    }

    fun modifySystemSetting(namespace: String, key: String, value: String): ToolOutcome {
        if (key.isBlank()) return ToolOutcome.Err("key parameter is required")
        val resolver = context.contentResolver
        val ok = try {
            when (namespace.lowercase()) {
                "global" -> Settings.Global.putString(resolver, key, value)
                "secure" -> Settings.Secure.putString(resolver, key, value)
                else -> Settings.System.putString(resolver, key, value)
            }
        } catch (e: Exception) {
            return ToolOutcome.Err("Failed to write setting: ${e.message}")
        }
        return if (ok) ToolOutcome.Ok("$namespace/$key set to '$value'")
        else ToolOutcome.Err("Write rejected; the app probably lacks WRITE_SETTINGS permission")
    }

    fun executeIntent(action: String, uri: String, packageName: String, extras: String): ToolOutcome {
        if (action.isBlank()) return ToolOutcome.Err("action parameter is required")
        val intent = Intent(action)
        if (uri.isNotBlank()) intent.data = Uri.parse(uri)
        if (packageName.isNotBlank()) intent.setPackage(packageName)
        if (extras.isNotBlank()) {
            val parsed = try {
                org.json.JSONObject(extras)
            } catch (e: Exception) {
                return ToolOutcome.Err("extras must be a JSON object: ${e.message}")
            }
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                intent.putExtra(key, parsed.optString(key, ""))
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolOutcome.Ok("Intent dispatched: $action")
        } catch (e: Exception) {
            ToolOutcome.Err("Cannot dispatch intent: ${e.message}")
        }
    }

    fun sendBroadcast(action: String, extras: String): ToolOutcome {
        if (action.isBlank()) return ToolOutcome.Err("action parameter is required")
        val intent = Intent(action)
        if (extras.isNotBlank()) {
            val parsed = try {
                org.json.JSONObject(extras)
            } catch (e: Exception) {
                return ToolOutcome.Err("extras must be a JSON object: ${e.message}")
            }
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                intent.putExtra(key, parsed.optString(key, ""))
            }
        }
        return try {
            context.sendBroadcast(intent)
            ToolOutcome.Ok("Broadcast sent: $action")
        } catch (e: Exception) {
            ToolOutcome.Err("Cannot send broadcast: ${e.message}")
        }
    }

    fun installApp(path: String): ToolOutcome {
        if (path.isBlank()) return ToolOutcome.Err("path parameter is required")
        val file = File(PathGuard.normalize(path))
        if (!file.exists()) return ToolOutcome.Err("No such file: ${file.path}")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            ToolOutcome.Ok("Installer opened for ${file.name}; confirm on screen to finish")
        } catch (e: Exception) {
            ToolOutcome.Err("Cannot open installer: ${e.message}")
        }
    }

    fun uninstallApp(packageName: String): ToolOutcome {
        if (packageName.isBlank()) return ToolOutcome.Err("package_name parameter is required")
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolOutcome.Ok("Uninstall prompt opened for $packageName; confirm on screen to finish")
        } catch (e: Exception) {
            ToolOutcome.Err("Cannot open uninstaller: ${e.message}")
        }
    }

    fun listProcesses(): ToolOutcome {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return ToolOutcome.Err("ActivityManager unavailable")
        val processes = am.runningAppProcesses
        if (processes.isNullOrEmpty()) return ToolOutcome.Ok("No process information available")
        val sorted = processes.sortedBy { it.processName }
        val sb = StringBuilder()
        sb.append("Running processes (").append(sorted.size).append("):\n")
        for (process in sorted) {
            sb.append(process.processName).append("  pid=").append(process.pid)
            sb.append("  importance=").append(process.importance).append('\n')
        }
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    fun screenInfo(): ToolOutcome {
        val metrics = context.resources.displayMetrics
        val sb = StringBuilder()
        sb.append("Width: ").append(metrics.widthPixels).append(" px\n")
        sb.append("Height: ").append(metrics.heightPixels).append(" px\n")
        sb.append("Density: ").append(metrics.densityDpi).append(" dpi\n")
        sb.append("Density scale: ").append(metrics.density).append('\n')
        sb.append("Scaled density: ").append(metrics.scaledDensity).append('\n')
        sb.append("Orientation: ")
            .append(if (metrics.widthPixels > metrics.heightPixels) "landscape" else "portrait")
            .append('\n')
        sb.append("Screen timeout: ")
            .append(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1))
            .append(" ms")
        return ToolOutcome.Ok(sb.toString())
    }

    private fun nextNotificationId(): Int = (System.currentTimeMillis() % 100000).toInt()
}