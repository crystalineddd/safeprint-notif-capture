package com.safeprint.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import io.flutter.plugin.common.EventChannel
import org.json.JSONObject
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
    companion object {
        private const val prefsName = "gcash_notifications"
        private const val captureEnabledKey = "capture_enabled"
        private const val notificationKeyPrefix = "capture_"
        private const val notificationChannelId = "capture_listener_status"
        private const val notificationId = 4041
        private const val stopAction = "com.safeprint.app.action.STOP_CAPTURE"
        private const val startAction = "com.safeprint.app.action.START_CAPTURE"

        fun isCaptureEnabled(context: Context): Boolean {
            return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .getBoolean(captureEnabledKey, true)
        }

        fun setCaptureEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(captureEnabledKey, enabled)
                .apply()
        }

        fun loadSavedNotifications(context: Context): List<Map<String, Any>> {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val notifications = mutableListOf<Map<String, Any>>()

            prefs.all.forEach { (key, value) ->
                if ((key.startsWith(notificationKeyPrefix) || key.startsWith("notification_")) && value is String) {
                    try {
                        val payload = jsonToMap(JSONObject(value))
                        val packageName = payload["packageName"]?.toString().orEmpty()
                        if (isGcashPackageName(packageName)) {
                            notifications.add(payload)
                        }
                    } catch (_: Exception) {
                        // Skip malformed entries.
                    }
                }
            }

            notifications.sortByDescending { (it["timestampEpochMs"] as? Number)?.toLong() ?: 0L }
            return notifications.take(100)
        }

        fun syncSavedNotificationsToFirebase(context: Context) {
            cleanupUnparsedNotificationsInFirebase(context.applicationContext)
            loadSavedNotifications(context).forEach { payload ->
                uploadNotificationToFirebase(context.applicationContext, payload)
            }
        }

        fun requestListenerResume(context: Context) {
            setCaptureEnabled(context, true)
            requestRebind(ComponentName(context, NotificationCaptureService::class.java))
        }

        private fun uploadNotificationToFirebase(context: Context, payload: Map<String, Any>) {
            val packageName = payload["packageName"]?.toString().orEmpty()
            if (!isGcashPackageName(packageName)) {
                return
            }

            val documentId = payload["documentId"]?.toString().orEmpty()
            if (documentId.isBlank()) {
                return
            }

            val isParsed = payload["isParsed"] as? Boolean ?: false
            if (!isParsed) {
                deleteNotificationFromFirebase(context, documentId)
                return
            }

            try {
                FirebaseApp.initializeApp(context)
            } catch (_: Exception) {
                // Firebase may already be initialized.
            }

            val firestorePayload = payload.toMutableMap<String, Any>()
            firestorePayload["capturedAt"] = FieldValue.serverTimestamp()

            FirebaseFirestore.getInstance()
                .collection("gcash_notifications")
                .document(documentId)
                .set(firestorePayload)
        }

        private fun deleteNotificationFromFirebase(context: Context, documentId: String) {
            if (documentId.isBlank()) {
                return
            }

            try {
                FirebaseApp.initializeApp(context)
            } catch (_: Exception) {
                // Firebase may already be initialized.
            }

            FirebaseFirestore.getInstance()
                .collection("gcash_notifications")
                .document(documentId)
                .delete()
        }

        private fun cleanupUnparsedNotificationsInFirebase(context: Context) {
            try {
                FirebaseApp.initializeApp(context)
            } catch (_: Exception) {
                // Firebase may already be initialized.
            }

            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("gcash_notifications")
                .whereEqualTo("isParsed", false)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.isEmpty) {
                        return@addOnSuccessListener
                    }

                    val batch = firestore.batch()
                    snapshot.documents.forEach { document ->
                        batch.delete(document.reference)
                    }
                    batch.commit()
                }
        }

        private fun jsonToMap(jsonObject: JSONObject): Map<String, Any> {
            val map = mutableMapOf<String, Any>()
            val iterator = jsonObject.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                val value = jsonObject.get(key)
                if (value != JSONObject.NULL) {
                    map[key] = value
                }
            }
            return map
        }

        private fun isGcashPackageName(packageName: String): Boolean {
            return packageName == "com.globe.gcash.android" ||
                packageName == "com.globe.gcash"
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseApp.initializeApp(this)
        } catch (_: Exception) {
            // Firebase may already be initialized.
        }
        ensureNotificationChannel()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (isCaptureEnabled(this)) {
            startForeground(notificationId, buildStatusNotification())
            syncSavedNotificationsToFirebase(applicationContext)
        } else {
            stopForegroundCompat()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            stopAction -> {
                setCaptureEnabled(this, false)
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            startAction -> {
                setCaptureEnabled(this, true)
                ensureNotificationChannel()
                startForeground(notificationId, buildStatusNotification())
                requestRebind(ComponentName(this, NotificationCaptureService::class.java))
                return START_STICKY
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun saveNotificationLocally(payload: Map<String, Any>) {
        val prefs: SharedPreferences = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        try {
            val documentId = payload["documentId"]?.toString()?.takeIf { it.isNotBlank() }
                ?: "${System.currentTimeMillis()}"
            val json = JSONObject(payload).toString()
            prefs.edit().putString("$notificationKeyPrefix$documentId", json).apply()
        } catch (_: Exception) {
            // Silently fail; still try to publish to event channel
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isCaptureEnabled(this)) {
            return
        }

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
        if (!sourceLooksLikeGcash) {
            return
        }

        val rawText = mergedText

        if (rawText.isBlank()) {
            return
        }

        val parseResult = parseGcashText(rawText)

        if (parseResult.entries.isEmpty()) {
            val payload = mapOf(
                "documentId" to buildDocumentId(packageName, sbn.postTime, 0, rawText),
                "packageName" to packageName,
                "title" to title,
                "rawText" to rawText,
                "amount" to "",
                "number" to "",
                "isParsed" to false,
                "isGcashSource" to sourceLooksLikeGcash,
                "timestampEpochMs" to sbn.postTime,
                "parseCategory" to parseResult.category,
                "parseHint" to parseResult.hint
            )

            persistAndPublish(payload)
            return
        }

        parseResult.entries.forEachIndexed { index, parsed ->
            val payload = mapOf(
                "documentId" to buildDocumentId(packageName, sbn.postTime, index, rawText),
                "packageName" to packageName,
                "title" to title,
                "rawText" to rawText,
                "amount" to parsed.amount,
                "number" to parsed.number,
                "isParsed" to true,
                "isGcashSource" to sourceLooksLikeGcash,
                "timestampEpochMs" to sbn.postTime + index,
                "parseCategory" to parseResult.category,
                "parseHint" to parseResult.hint
            )

            persistAndPublish(payload)
        }
    }

    private fun persistAndPublish(payload: Map<String, Any>) {
        saveNotificationLocally(payload)
        uploadNotificationToFirebase(applicationContext, payload)
        NotificationEventStreamHandler.publish(payload)
    }

    private fun isGcashPackage(packageName: String): Boolean {
        val knownPackages = setOf(
            "com.globe.gcash.android",
            "com.globe.gcash"
        )

        return knownPackages.contains(packageName) && packageName != this.packageName
    }

    private fun parseGcashText(rawText: String): ParseResult {
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
            return ParseResult(
                entries = entries,
                category = "payment",
                hint = "Parsed as a GCash payment receipt."
            )
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
            return ParseResult(
                entries = emptyList(),
                category = classifyUnparsedNotification(normalized, amounts.isNotEmpty(), numbers.isNotEmpty()),
                hint = buildParseHint(normalized, amounts.isNotEmpty(), numbers.isNotEmpty())
            )
        }

        val pairCount = minOf(amounts.size, numbers.size)
        for (index in 0 until pairCount) {
            val amount = amounts[index]
            val number = numbers[index]
            if (amount.isNotBlank() && number.isNotBlank()) {
                entries.add(ParsedNotification(amount = amount, number = number))
            }
        }

        return ParseResult(
            entries = entries,
            category = "payment",
            hint = "Parsed as a GCash payment receipt."
        )
    }

    private fun classifyUnparsedNotification(
        rawText: String,
        hasAmount: Boolean,
        hasNumber: Boolean
    ): String {
        val promoKeywords = listOf(
            "promo",
            "deal",
            "offer",
            "discount",
            "voucher",
            "reminder",
            "advisory",
            "maintenance",
            "reward"
        )

        if (promoKeywords.any { rawText.contains(it, ignoreCase = true) }) {
            return "promo_notification"
        }

        if (!hasAmount && !hasNumber) {
            return "missing_amount_and_number"
        }

        if (!hasAmount) {
            return "missing_amount"
        }

        if (!hasNumber) {
            return "missing_number"
        }

        return "unrecognized_gcash_format"
    }

    private fun buildParseHint(rawText: String, hasAmount: Boolean, hasNumber: Boolean): String {
        return when (classifyUnparsedNotification(rawText, hasAmount, hasNumber)) {
            "promo_notification" -> "Captured a GCash promo/advisory notification, not a payment receipt."
            "missing_amount_and_number" -> "Captured a GCash notification, but no payment amount or mobile number was found."
            "missing_amount" -> "Captured a GCash notification, but no payment amount was found."
            "missing_number" -> "Captured a GCash notification, but no sender mobile number was found."
            else -> "Captured a GCash notification, but its format did not match the current payment parser."
        }
    }

    private fun buildDocumentId(
        packageName: String,
        timestampEpochMs: Long,
        index: Int,
        rawText: String
    ): String {
        val packageSegment = packageName.replace(".", "_")
        val textHash = Integer.toUnsignedLong(rawText.hashCode())
        return "${packageSegment}_${timestampEpochMs}_${index}_$textHash"
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            notificationChannelId,
            "Capture listener status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether SafePrint is actively listening for GCash notifications."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildStatusNotification() = NotificationCompat.Builder(this, notificationChannelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
        .setContentTitle("SafePrint is listening")
        .setContentText("Watching for GCash payment notifications in the background.")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(buildOpenAppPendingIntent())
        .addAction(0, "Stop", buildStopPendingIntent())
        .build()

    private fun buildOpenAppPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildStopPendingIntent(): PendingIntent {
        val intent = Intent(this, NotificationCaptureService::class.java).apply {
            action = stopAction
        }
        return PendingIntent.getService(
            this,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(notificationId)
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

    private data class ParseResult(
        val entries: List<ParsedNotification>,
        val category: String,
        val hint: String
    )
}
