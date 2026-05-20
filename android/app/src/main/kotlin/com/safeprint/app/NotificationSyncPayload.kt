package com.safeprint.app

internal object NotificationSyncPayload {
    // Only upload app-owned fields so replay syncs cannot mutate server-owned claim metadata.
    private val appOwnedFieldNames = listOf(
        "documentId",
        "packageName",
        "title",
        "rawText",
        "amount",
        "number",
        "isParsed",
        "isGcashSource",
        "timestampEpochMs",
        "parseCategory",
        "parseHint"
    )

    fun toFirestorePayload(payload: Map<String, Any>): Map<String, Any> {
        val firestorePayload = linkedMapOf<String, Any>()
        appOwnedFieldNames.forEach { key ->
            val value = payload[key] ?: return@forEach
            firestorePayload[key] = value
        }
        return firestorePayload
    }

    fun uploadSignature(payload: Map<String, Any>): String {
        return appOwnedFieldNames.joinToString(separator = "|") { key ->
            "$key=${payload[key]?.toString().orEmpty()}"
        }
    }
}