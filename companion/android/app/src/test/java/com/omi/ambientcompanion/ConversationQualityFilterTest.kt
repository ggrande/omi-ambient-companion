package com.omi.ambientcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ConversationQualityFilterTest {
    @Test
    fun rejectsCompanionNotificationText() {
        val decision = ConversationQualityFilter.evaluate(
            "Omi Ambient Companion Caption fallback active. Mic idle.",
            FallbackSource.LIVE_CAPTION_NOTIFICATION,
        )

        assertFalse(decision.allow)
    }

    @Test
    fun rejectsLikelyMediaCaptionsWithoutIntent() {
        val decision = ConversationQualityFilter.evaluate(
            "Previously on season four episode three official trailer",
            FallbackSource.ACCESSIBILITY_CAPTION,
        )

        assertFalse(decision.allow)
    }

    @Test
    fun allowsExplicitUserIntentEvenWhenShort() {
        val decision = ConversationQualityFilter.evaluate(
            "Remind me to call Sam",
            FallbackSource.LOCAL_STT,
        )

        assertTrue(decision.allow)
    }

    @Test
    fun allowsConversationLengthLocalStt() {
        val decision = ConversationQualityFilter.evaluate(
            "I talked with Morgan about the proposal and we need to follow up tomorrow morning",
            FallbackSource.LOCAL_STT,
        )

        assertTrue(decision.allow)
    }

    @Test
    fun rejectsVeryShortCaptionWithoutIntent() {
        val decision = ConversationQualityFilter.evaluate(
            FallbackSegment(
                text = "meeting starts now",
                source = FallbackSource.ACCESSIBILITY_CAPTION,
                start = Instant.parse("2026-05-02T00:00:00Z"),
                end = Instant.parse("2026-05-02T00:00:01Z"),
                healthState = AmbientHealthState.CAPTION_FALLBACK_ACTIVE,
                rawAudioAvailable = false,
            ),
        )

        assertFalse(decision.allow)
    }

    @Test
    fun allowsShortFallbackWithExplicitIntent() {
        val decision = ConversationQualityFilter.evaluate(
            FallbackSegment(
                text = "Remind me to call Sam",
                source = FallbackSource.LOCAL_STT,
                start = Instant.parse("2026-05-02T00:00:00Z"),
                end = Instant.parse("2026-05-02T00:00:00.700Z"),
                healthState = AmbientHealthState.LOCAL_STT_ACTIVE,
                rawAudioAvailable = false,
            ),
        )

        assertTrue(decision.allow)
    }
}
