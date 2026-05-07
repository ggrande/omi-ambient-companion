package com.omi.ambientcompanion

import android.content.Context
import org.json.JSONObject
import java.io.File

data class CaptureActivityBucket(val pending: Int, val synced: Int)

class CaptureActivityStore(context: Context) {
    private val file = File(context.filesDir, "capture_activity.jsonl")

    @Synchronized
    fun recordPending(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        append(sessionId, "pending")
    }

    @Synchronized
    fun markSynced(sessionIds: Collection<String>) {
        markStatus(sessionIds, "synced")
    }

    @Synchronized
    fun markFiltered(sessionIds: Collection<String>) {
        markStatus(sessionIds, "filtered")
    }

    private fun markStatus(sessionIds: Collection<String>, status: String) {
        if (sessionIds.isEmpty()) return
        val wanted = sessionIds.toSet()
        val items = readItems().map {
            if (wanted.contains(it.optString("session_id"))) it.put("status", status) else it
        }
        writeItems(items)
    }

    fun buckets(minutes: Int = 15): List<CaptureActivityBucket> {
        val nowMinute = System.currentTimeMillis() / 60_000L
        val items = readItems()
        return (minutes - 1 downTo 0).map { offset ->
            val minute = nowMinute - offset
            val inMinute = items.filter { it.optLong("timestamp_ms") / 60_000L == minute }
            CaptureActivityBucket(
                pending = inMinute.count { it.optString("status") == "pending" },
                synced = inMinute.count { it.optString("status") == "synced" },
            )
        }
    }

    private fun append(sessionId: String, status: String) {
        prune()
        file.appendText(
            JSONObject()
                .put("session_id", sessionId)
                .put("timestamp_ms", System.currentTimeMillis())
                .put("status", status)
                .toString() + "\n",
        )
    }

    private fun prune() {
        if (!file.exists()) return
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        writeItems(readItems().filter { it.optLong("timestamp_ms") >= cutoff })
    }

    private fun readItems(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
    }

    private fun writeItems(items: List<JSONObject>) {
        file.writeText(items.joinToString("\n") { it.toString() } + if (items.isNotEmpty()) "\n" else "")
    }
}
