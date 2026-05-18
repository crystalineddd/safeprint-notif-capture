package com.safeprint.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val methodChannelName = "gcash_capture/methods"
    private val eventChannelName = "gcash_capture/events"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openNotificationAccessSettings" -> {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        startActivity(intent)
                        result.success(true)
                    }

                    "isNotificationAccessGranted" -> {
                        result.success(hasNotificationListenerAccess())
                    }

                    "loadSavedNotifications" -> {
                        result.success(loadSavedNotifications())
                    }

                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(NotificationEventStreamHandler)
    }

    private fun hasNotificationListenerAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabled.contains(packageName)
    }

    private fun loadSavedNotifications(): List<Map<String, Any>> {
        val prefs = getSharedPreferences("gcash_notifications", Context.MODE_PRIVATE)
        val notifications = mutableListOf<Map<String, Any>>()

        prefs.all.forEach { (key, value) ->
            if (key.startsWith("notification_") && value is String) {
                try {
                    val map = parseJson(value)
                    notifications.add(map)
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }
        }

        // Sort by timestamp, newest first
        notifications.sortByDescending { (it["timestampEpochMs"] as? Number)?.toLong() ?: 0L }
        return notifications.take(50) // Return last 50
    }

    private fun parseJson(json: String): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val content = json.trim().removeSurrounding("{", "}")
        val pairs = parseJsonPairs(content)
        
        pairs.forEach { (key, value) ->
            when {
                value.startsWith("\"") && value.endsWith("\"") -> 
                    map[key] = value.substring(1, value.length - 1)
                        .replace("\\\"", "\"")
                value == "true" -> map[key] = true
                value == "false" -> map[key] = false
                value.toLongOrNull() != null -> map[key] = value.toLong()
                value.toDoubleOrNull() != null -> map[key] = value.toDouble()
                else -> map[key] = value
            }
        }
        return map
    }

    private fun parseJsonPairs(content: String): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        var current = 0
        var inString = false
        var keyStart = -1
        var colonPos = -1

        while (current < content.length) {
            val char = content[current]
            
            when {
                char == '"' && (current == 0 || content[current - 1] != '\\') -> 
                    inString = !inString
                char == ':' && !inString && keyStart >= 0 && colonPos < 0 ->
                    colonPos = current
                char == ',' && !inString && keyStart >= 0 && colonPos >= 0 -> {
                    val key = content.substring(keyStart, colonPos)
                        .trim()
                        .removeSurrounding("\"")
                    val value = content.substring(colonPos + 1, current).trim()
                    pairs.add(key to value)
                    keyStart = -1
                    colonPos = -1
                }
                !char.isWhitespace() && keyStart < 0 && !inString ->
                    keyStart = current
            }
            current++
        }

        if (keyStart >= 0 && colonPos >= 0) {
            val key = content.substring(keyStart, colonPos)
                .trim()
                .removeSurrounding("\"")
            val value = content.substring(colonPos + 1).trim()
            pairs.add(key to value)
        }

        return pairs
    }
}
