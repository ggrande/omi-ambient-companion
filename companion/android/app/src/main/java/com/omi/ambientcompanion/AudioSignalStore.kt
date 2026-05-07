package com.omi.ambientcompanion

import org.json.JSONObject
import java.time.Instant
import kotlin.math.roundToInt

object AudioSignalStore {
    @Volatile private var lastDbfs: Double = -120.0
    @Volatile private var lastZeroRatio: Double = 1.0
    @Volatile private var lastZeroCrossingHz: Double = 0.0
    @Volatile private var lastVoiceBandScore: Double = 0.0
    @Volatile private var lastVolumeTrendDb: Double = 0.0
    @Volatile private var lastChunkAtMs: Long = 0
    @Volatile private var lastSoundAtMs: Long = 0
    @Volatile private var lastSpeechAtMs: Long = 0
    @Volatile private var conversationActive: Boolean = false
    @Volatile private var likelySpeech: Boolean = false
    @Volatile private var samplesSeen: Long = 0

    @Synchronized
    fun update(result: VadFrameResult, vadActive: Boolean, activeSession: Boolean): List<String> {
        val now = System.currentTimeMillis()
        val wasSound = hasRecentSound(now)
        val wasConversation = conversationActive
        lastDbfs = result.dbfs
        lastZeroRatio = result.zeroRatio
        lastZeroCrossingHz = result.zeroCrossingHz
        lastVoiceBandScore = result.voiceBandScore
        lastVolumeTrendDb = result.volumeTrendDb
        lastChunkAtMs = now
        samplesSeen += 1
        val sound = result.dbfs > SOUND_DBFS_THRESHOLD && result.zeroRatio < 0.995
        likelySpeech = result.speech || vadActive
        conversationActive = activeSession || vadActive
        if (sound) lastSoundAtMs = now
        if (likelySpeech) lastSpeechAtMs = now

        val events = mutableListOf<String>()
        if (!wasSound && hasRecentSound(now)) events += "sound_detected"
        if (!wasConversation && conversationActive) events += "conversation_detected"
        if (wasConversation && !conversationActive) events += "conversation_ended"
        return events
    }

    fun snapshot(): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("dbfs", rounded(lastDbfs))
            .put("zero_ratio", rounded(lastZeroRatio))
            .put("zero_crossing_hz", rounded(lastZeroCrossingHz))
            .put("voice_band_score", rounded(lastVoiceBandScore))
            .put("volume_trend_db", rounded(lastVolumeTrendDb))
            .put("sound_detected", hasRecentSound(now))
            .put("likely_speech", hasRecentSpeech(now))
            .put("conversation_active", conversationActive)
            .put("last_chunk_ms_ago", age(now, lastChunkAtMs))
            .put("last_sound_ms_ago", age(now, lastSoundAtMs))
            .put("last_speech_ms_ago", age(now, lastSpeechAtMs))
            .put("samples_seen", samplesSeen)
            .put("updated_at", if (lastChunkAtMs > 0) Instant.ofEpochMilli(lastChunkAtMs).toString() else null)
    }

    fun label(): String {
        val s = snapshot()
        val dbfs = s.optDouble("dbfs", -120.0)
        val zero = s.optDouble("zero_ratio", 1.0)
        val zc = s.optDouble("zero_crossing_hz", 0.0)
        val voiceBand = s.optDouble("voice_band_score", 0.0)
        val trend = s.optDouble("volume_trend_db", 0.0)
        val sound = if (s.optBoolean("sound_detected")) "yes" else "no"
        val speech = if (s.optBoolean("likely_speech")) "yes" else "no"
        val conversation = if (s.optBoolean("conversation_active")) "yes" else "no"
        val chunkAge = s.optLong("last_chunk_ms_ago", -1)
        return "Sound: $sound | Speech: $speech | Conversation: $conversation\nSignal: ${dbfs} dBFS, trend=${trend}dB, zc=${zc}Hz, voiceBand=$voiceBand, zero=$zero, last audio=${if (chunkAge >= 0) "${chunkAge}ms ago" else "never"}"
    }

    private fun hasRecentSound(now: Long): Boolean = lastSoundAtMs > 0 && now - lastSoundAtMs <= RECENT_MS

    private fun hasRecentSpeech(now: Long): Boolean = lastSpeechAtMs > 0 && now - lastSpeechAtMs <= RECENT_MS

    private fun age(now: Long, timestamp: Long): Long = if (timestamp > 0) now - timestamp else -1

    private fun rounded(value: Double): Double = (value * 100.0).roundToInt() / 100.0

    private const val SOUND_DBFS_THRESHOLD = -68.0
    private const val RECENT_MS = 8_000L
}
