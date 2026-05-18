package com.safeprint.app

import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.flutter.plugin.common.EventChannel
import java.util.ArrayDeque

object NotificationEventStreamHandler : EventChannel.StreamHandler {
    private var sink: EventChannel.EventSink? = null
    private val pendingEvents: ArrayDeque<Map<String, Any>> = ArrayDeque()

    @Synchronized
    fun publish(event: Map<String, Any>) {
        val localSink = sink
        if (localSink != null) {
            localSink.success(event)
            return
        }

        pendingEvents.addLast(event)
        while (pendingEvents.size > 100) {
            pendingEvents.removeFirst()
        }
    }

    @Synchronized
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
        while (pendingEvents.isNotEmpty()) {
            events?.success(pendingEvents.removeFirst())
        }
    }

    @Synchronized
    override fun onCancel(arguments: Any?) {
        sink = null
    }
}

class NotificationCaptureService : NotificationListenerService() {
    private fun saveNotificationLocally(payload: Map<String, Any>) {
        val prefs: SharedPreferences = getSharedPreferences(
            "gcash_notifications",
            Context.MODE_PRIVATE
        )
        try {
            val json = mapToJson(payload)
            val timestamp = System.currentTimeMillis()
            prefs.edit().putString("notification_$timestamp", json).apply()
        } catch (e: Exception) {
            // Silently fail; still try to publish to event channel
        }
    }

    private fun mapToJson(map: Map<String, Any>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            val valueStr = when (v) {
                is String -> "\"${v.replace("\"", "\\\"")}\"" 
                is Number -> v.toString()
                is Boolean -> v.toString()
                else -> "\"$v\""
            }
            "\"$k\":$valueStr"
        }
        return "{$entries}"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return

        val extras = sbn.notification.extras
        val title = extras?.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras?.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras?.getCharSequence("android.bigText")?.toString().orEmpty()
        val subText = extras?.getCharSequence("android.subText")?.toString().orEmpty()
        val tickerText = sbn.notification.tickerText?.toString().orEmpty()
        val textLines = extras?.getCharSequenceArray("android.textLines")
            ?.joinToString(" | ") { it.toString().trim() }
            .orEmpty()
        val messages = extras?.getParcelableArray("android.messages")
            ?.mapNotNull { parcelable ->
                val bundle = parcelable as? android.os.Bundle ?: return@mapNotNull null
                bundle.getCharSequence("text")?.toString()?.trim().takeIf { !it.isNullOrBlank() }
            }
            ?.joinToString(" | ")
            .orEmpty()

        val mergedText = listOf(title, bigText, text, subText, textLines, messages, tickerText)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(" | ")
            .trim()

        val sourceLooksLikeGcash = isGcashPackage(packageName)
        val contentLooksLikeGcash = mergedText.contains("gcash", ignoreCase = true)
        if (!sourceLooksLikeGcash && !contentLooksLikeGcash) {
            return
        }

        val rawText = mergedText

        if (rawText.isBlank()) {
            return
        }

        val parsedEntries = parseGcashText(rawText)

        if (parsedEntries.isEmpty()) {
            val payload = mapOf(
                "packageName" to packageName,
                "title" to title,
                "rawText" to rawText,
                "amount" to "",
                "number" to "",
                "isParsed" to false,
                "isGcashSource" to sourceLooksLikeGcash,
                "timestampEpochMs" to sbn.postTime
            )

            saveNotificationLocally(payload)
            NotificationEventStreamHandler.publish(payload)
            return
        }

        parsedEntries.forEachIndexed { index, parsed ->
            val payload = mapOf(
                "packageName" to packageName,
                "title" to title,
                "rawText" to rawText,
                "amount" to parsed.amount,
                "number" to parsed.number,
                "isParsed" to true,
                "isGcashSource" to sourceLooksLikeGcash,
                "timestampEpochMs" to sbn.postTime + index
            )

            // Save locally first (works even if app is closed)
            saveNotificationLocally(payload)
            // Also publish to event channel (for real-time updates when app is open)
            NotificationEventStreamHandler.publish(payload)
        }
    }

    private fun isGcashPackage(packageName: String): Boolean {
        val knownPackages = setOf(
            "com.globe.gcash.android",
            "com.globe.gcash"
        )

        if (knownPackages.contains(packageName)) {
            return true
        }

        return packageName.contains("gcash", ignoreCase = true)
    }

    private fun parseGcashText(rawText: String): List<ParsedNotification> {
        val normalized = rawText.replace("\n", " ").trim()

        val entries = mutableListOf<ParsedNotification>()

        val directPattern = Regex(
            pattern = """you\s+received\s+(.+?)\s+from\s+(.+?)\s+([+0-9][0-9*\-\s]{5,})""",
            option = RegexOption.IGNORE_CASE
        )

        directPattern.findAll(normalized).forEach { match ->
            val amount = match.groupValues[1].trim()
            val number = normalizeNumber(match.groupValues[3])
            if (amount.isNotBlank() && number.isNotBlank()) {
                entries.add(ParsedNotification(amount = amount, number = number))
            }
        }

        if (entries.isNotEmpty()) {
            return entries
        }

        val amountPattern = Regex(
            pattern = """(?:PHP|Php|php|P|₱)\s?[0-9][0-9,]*(?:\.[0-9]{1,2})?""",
            option = RegexOption.IGNORE_CASE
        )
        val numberPattern = Regex(
            pattern = """(?:\+63|09)[0-9\-\s*]{8,}"""
        )

        val amounts = amountPattern.findAll(normalized).map { it.value.trim() }.toList()
        val numbers = numberPattern.findAll(normalized)
            .map { normalizeNumber(it.value) }
            .toList()

        if (amounts.isEmpty() || numbers.isEmpty()) {
            return emptyList()
        }

        val pairCount = minOf(amounts.size, numbers.size)
        for (index in 0 until pairCount) {
            val amount = amounts[index]
            val number = numbers[index]
            if (amount.isNotBlank() && number.isNotBlank()) {
                entries.add(ParsedNotification(amount = amount, number = number))
            }
        }

        return entries
    }

    private fun normalizeNumber(value: String): String {
        return value
            .replace(" ", "")
            .replace("-", "")
            .trim()
    }

    private data class ParsedNotification(
        val amount: String,
        val number: String
    )
}
