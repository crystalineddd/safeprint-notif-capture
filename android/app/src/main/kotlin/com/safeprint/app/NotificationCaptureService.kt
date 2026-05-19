package com.safeprint.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import io.flutter.plugin.common.EventChannel
import java.util.ArrayDeque

private const val TAG = "NotificationCaptureSvc"

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
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return

        val extras = sbn.notification.extras
        val text = extras?.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras?.getCharSequence("android.bigText")?.toString().orEmpty()
        val title = extras?.getCharSequence("android.title")?.toString().orEmpty()
        val subText = extras?.getCharSequence("android.subText")?.toString().orEmpty()
        val tickerText = sbn.notification.tickerText?.toString().orEmpty()
        val textLines = extras?.getCharSequenceArray("android.textLines")
            ?.map { it?.toString().orEmpty().trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val mergedText = buildList {
            addAll(listOf(title, bigText, text, subText, tickerText))
            addAll(textLines)
        }
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

        val parsed = parseGcashText(rawText)

        val payload = mapOf(
            "packageName" to packageName,
            "title" to title,
            "rawText" to rawText,
            "amount" to (parsed?.amount ?: ""),
            "number" to (parsed?.number ?: ""),
            "isParsed" to (parsed != null),
            "isGcashSource" to sourceLooksLikeGcash,
            "timestampEpochMs" to sbn.postTime
        )

        NotificationEventStreamHandler.publish(payload)
        persistNotification(payload)
    }

    private fun persistNotification(payload: Map<String, Any>) {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }

            FirebaseFirestore.getInstance()
                .collection("gcash_notifications")
                .add(
                    mapOf(
                        "amount" to (payload["amount"] ?: ""),
                        "number" to (payload["number"] ?: ""),
                        "rawText" to (payload["rawText"] ?: ""),
                        "capturedAt" to FieldValue.serverTimestamp(),
                    )
                )
                .addOnFailureListener { error ->
                    Log.e(TAG, "Failed to store notification", error)
                }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to initialize Firebase for notification storage", error)
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

    private fun parseGcashText(rawText: String): ParsedNotification? {
        val normalized = rawText.replace("\n", " ").trim()

        val directPattern = Regex(
            pattern = """you\s+received\s+(.+?)\s+from\s+(.+?)\s+([+0-9][0-9*\-\s]{5,})""",
            option = RegexOption.IGNORE_CASE
        )
        val directMatch = directPattern.find(normalized)
        if (directMatch != null) {
            val amount = directMatch.groupValues[1].trim()
            val number = normalizeNumber(directMatch.groupValues[3])
            if (amount.isNotBlank() && number.isNotBlank()) {
                return ParsedNotification(amount = amount, number = number)
            }
        }

        val amountPattern = Regex(
            pattern = """(?:PHP|Php|php|P|₱)\s?[0-9][0-9,]*(?:\.[0-9]{1,2})?"""
        )
        val numberPattern = Regex(
            pattern = """(?:\+63|09)[0-9\-\s*]{8,}"""
        )

        val amount = amountPattern.find(normalized)?.value?.trim().orEmpty()
        val number = numberPattern.findAll(normalized)
            .lastOrNull()
            ?.value
            ?.let { normalizeNumber(it) }
            .orEmpty()

        if (amount.isBlank() || number.isBlank()) {
            return null
        }

        return ParsedNotification(amount = amount, number = number)
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
