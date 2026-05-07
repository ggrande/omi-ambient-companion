package com.omi.ambientcompanion

import java.time.Duration
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

    fun evaluate(segment: FallbackSegment): ConversationQualityDecision {
        val durationMs = Duration.between(segment.start, segment.end).toMillis().coerceAtLeast(0L)
        return evaluate(segment.text, segment.source, durationMs)
    }

    fun evaluate(text: String, source: FallbackSource? = null, durationMs: Long? = null): ConversationQualityDecision {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        val lower = normalized.lowercase(Locale.US)
        if (normalized.isBlank()) return ConversationQualityDecision(false, "blank", 0.0)
        if (source == FallbackSource.GAP_MARKER) return ConversationQualityDecision(false, "gap_marker_not_conversation", 0.0)

        val hasIntent = intentPhrases.any { lower.contains(it) }
        val duration = durationMs?.coerceAtLeast(0L)
        if (systemJunkPhrases.any { lower.contains(it) }) {
            return ConversationQualityDecision(false, "system_ui_text", 0.05)
        }
        if (!hasIntent && mediaPhrases.any { lower.contains(it) }) {
            return ConversationQualityDecision(false, "likely_media_or_tv", 0.1)
        }
        if (!hasIntent && duration != null && duration < MIN_FALLBACK_DURATION_MS) {
            return ConversationQualityDecision(false, "too_short_duration_without_intent", 0.15)
        }
        if (
            !hasIntent &&
            duration != null &&
            source in setOf(FallbackSource.ACCESSIBILITY_CAPTION, FallbackSource.LIVE_CAPTION_NOTIFICATION) &&
            duration < MIN_CAPTION_DURATION_MS
        ) {
            return ConversationQualityDecision(false, "caption_duration_too_short_without_intent", 0.18)
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

    private const val MIN_FALLBACK_DURATION_MS = 1_200L
    private const val MIN_CAPTION_DURATION_MS = 2_500L
}
