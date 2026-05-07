package com.omi.ambientcompanion

import java.util.Locale

data class ConversationQualityDecision(
    val allow: Boolean,
    val reason: String,
    val score: Double,
)

object ConversationQualityFilter {
    private val intentPhrases = listOf(
        "remind me",
        "don't let me forget",
        "do not let me forget",
        "i need to",
        "i'll ",
        "i will ",
        "follow up",
        "call ",
        "email ",
        "send ",
        "make sure",
    )

    private val systemJunkPhrases = listOf(
        "omi ambient companion",
        "caption fallback active",
        "mic idle",
        "vad watch",
        "sampled vad",
        "screen audio",
        "battery unrestricted",
        "notification listener",
        "over your mobile data limit",
        "silent notification",
        "foreground service",
        "accessibility service",
        "android system",
        "swiftkey",
        "gboard",
    )

    private val mediaPhrases = listOf(
        "official trailer",
        "like and subscribe",
        "subscribe to",
        "season ",
        "episode ",
        "previously on",
        "netflix",
        "hulu",
        "spotify",
        "youtube",
        "playing from",
        "lyrics",
        "album",
        "song by",
        "copyright",
        "all rights reserved",
    )

    fun evaluate(text: String, source: FallbackSource? = null): ConversationQualityDecision {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        val lower = normalized.lowercase(Locale.US)
        if (normalized.isBlank()) return ConversationQualityDecision(false, "blank", 0.0)
        if (source == FallbackSource.GAP_MARKER) return ConversationQualityDecision(false, "gap_marker_not_conversation", 0.0)

        val hasIntent = intentPhrases.any { lower.contains(it) }
        if (systemJunkPhrases.any { lower.contains(it) }) {
            return ConversationQualityDecision(false, "system_ui_text", 0.05)
        }
        if (!hasIntent && mediaPhrases.any { lower.contains(it) }) {
            return ConversationQualityDecision(false, "likely_media_or_tv", 0.1)
        }

        val words = lower.split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }
        val uniqueRatio = if (words.isEmpty()) 0.0 else words.toSet().size.toDouble() / words.size.toDouble()
        if (!hasIntent && normalized.length < 24) {
            return ConversationQualityDecision(false, "too_short_without_intent", 0.2)
        }
        if (!hasIntent && words.size < 5) {
            return ConversationQualityDecision(false, "too_few_words_without_intent", 0.25)
        }
        if (!hasIntent && words.size >= 8 && uniqueRatio < 0.35) {
            return ConversationQualityDecision(false, "repetitive_text", 0.25)
        }

        val score = when {
            hasIntent -> 0.95
            source == FallbackSource.LOCAL_STT -> 0.75
            source == FallbackSource.ACCESSIBILITY_CAPTION || source == FallbackSource.LIVE_CAPTION_NOTIFICATION -> 0.6
            else -> 0.5
        }
        return ConversationQualityDecision(true, "conversation_candidate", score)
    }
}
