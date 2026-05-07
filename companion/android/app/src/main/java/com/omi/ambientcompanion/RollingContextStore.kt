package com.omi.ambientcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class RollingContextStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = AppPrefs(appContext)
    private val file = File(appContext.filesDir, "rolling_context.jsonl")

    @Synchronized
    fun record(
        source: String,
        text: String,
        reason: String,
        foregroundPackage: String?,
        conversationCandidate: Boolean,
    ) {
        val normalized = normalize(text)
        if (normalized.length < 3) return
        val now = Instant.now()
        val kept = recentJson(now).toMutableList()
        if (kept.lastOrNull()?.optString("text") == normalized) return
        kept += JSONObject()
            .put("timestamp", now.toString())
            .put("source", source)
            .put("reason", reason)
            .put("foreground_package", foregroundPackage)
            .put("conversation_candidate", conversationCandidate)
            .put("text", normalized.take(MAX_TEXT_CHARS))
        writeItems(kept.takeLast(MAX_CONTEXT_ITEMS))
    }

    @Synchronized
    fun recent(limit: Int = 40): JSONArray {
        val arr = JSONArray()
        recentJson().takeLast(limit).forEach { arr.put(it) }
        return arr
    }

    @Synchronized
    fun summary(limit: Int = 8): String {
        val items = recentJson()
            .filter { it.optBoolean("conversation_candidate", false) }
            .takeLast(limit)
        if (items.isEmpty()) return "none"
        return items.joinToString(" | ") {
            "${it.optString("source")}:${it.optString("text").take(80)}"
        }
    }

    @Synchronized
    fun stats(): JSONObject {
        val items = recentJson()
        return JSONObject()
            .put("window_seconds", prefs.rollingContextWindowSeconds)
            .put("count", items.size)
            .put("conversation_candidates", items.count { it.optBoolean("conversation_candidate", false) })
            .put("sources", JSONObject(items.groupingBy { it.optString("source", "unknown") }.eachCount()))
            .put("recent", recent(8))
    }

    private fun recentJson(now: Instant = Instant.now()): List<JSONObject> {
        if (!file.exists()) return emptyList()
        val cutoff = now.minusSeconds(prefs.rollingContextWindowSeconds.toLong())
        return file.readLines()
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .filter { item ->
                val timestamp = runCatching { Instant.parse(item.optString("timestamp")) }.getOrNull()
                timestamp != null && !timestamp.isBefore(cutoff)
            }
    }

    private fun writeItems(items: List<JSONObject>) {
        file.parentFile?.mkdirs()
        file.writeText(items.joinToString("\n") { it.toString() } + if (items.isEmpty()) "" else "\n")
    }

    companion object {
        private const val MAX_CONTEXT_ITEMS = 250
        private const val MAX_TEXT_CHARS = 500

        fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
    }
}
