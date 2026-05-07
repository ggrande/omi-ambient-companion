package com.omi.ambientcompanion

import java.time.Instant

object AmbientSyncFilenames {
    private const val MIN_VALID_BACKEND_TIMESTAMP_SECONDS = 1_704_067_200L // 2024-01-01T00:00:00Z
    private const val SERVER_CLOCK_SKEW_GUARD_SECONDS = 30L

    fun omiPcm16Bin(meta: SpoolMetadata): String {
        return "audio_phone_pcm16_16000_1_fs960_${safeBackendTimestampSeconds(meta.startedAt)}.bin"
    }

    private fun safeBackendTimestampSeconds(startedAt: Instant): Long {
        val latestSafe = (Instant.now().epochSecond - SERVER_CLOCK_SKEW_GUARD_SECONDS)
            .coerceAtLeast(MIN_VALID_BACKEND_TIMESTAMP_SECONDS)
        val started = startedAt.epochSecond
        if (started < MIN_VALID_BACKEND_TIMESTAMP_SECONDS) return latestSafe
        return started.coerceAtMost(latestSafe)
    }
}
