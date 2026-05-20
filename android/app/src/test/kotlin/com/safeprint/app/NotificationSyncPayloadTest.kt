package com.safeprint.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSyncPayloadTest {
    @Test
    fun `toFirestorePayload keeps only app-owned fields`() {
        val payload = mapOf<String, Any>(
            "documentId" to "doc-1",
            "packageName" to "com.globe.gcash.android",
            "rawText" to "You have received money in GCash!",
            "amount" to "P100.00",
            "claimed_by_cid" to "server-cid",
            "claimed_by_intent_id" to "intent-1",
            "claimed_at" to "2026-05-20T09:15:00Z"
        )

        val firestorePayload = NotificationSyncPayload.toFirestorePayload(payload)

        assertEquals("doc-1", firestorePayload["documentId"])
        assertEquals("P100.00", firestorePayload["amount"])
        assertFalse(firestorePayload.containsKey("claimed_by_cid"))
        assertFalse(firestorePayload.containsKey("claimed_by_intent_id"))
        assertFalse(firestorePayload.containsKey("claimed_at"))
    }

    @Test
    fun `uploadSignature is stable for app-owned fields`() {
        val first = linkedMapOf<String, Any>(
            "documentId" to "doc-1",
            "packageName" to "com.globe.gcash.android",
            "amount" to "P100.00"
        )
        val second = linkedMapOf<String, Any>(
            "amount" to "P100.00",
            "packageName" to "com.globe.gcash.android",
            "documentId" to "doc-1"
        )

        assertEquals(
            NotificationSyncPayload.uploadSignature(first),
            NotificationSyncPayload.uploadSignature(second)
        )
        assertTrue(NotificationSyncPayload.uploadSignature(first).contains("documentId=doc-1"))
    }
}