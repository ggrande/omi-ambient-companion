package com.omi.ambientcompanion

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

class DiagnosticsStore(context: Context) {
    private val file = File(context.filesDir, "ambient_diagnostics.json")
    private val appContext = context.applicationContext

    fun write(reason: String) {
        val spoolStats = CaptureSpoolStore(appContext).stats()
        val fallbackStats = FallbackSegmentQueue(appContext).stats()
        val currentSession = CaptureSessionStore(appContext).current()
        val prefs = AppPrefs(appContext)
        val json = JSONObject()
            .put("generated_at", Instant.now().toString())
            .put("reason", reason)
            .put("last_health_state", AmbientForegroundMicService.lastHealthState().name)
            .put("last_sync", prefs.lastSyncLabel)
            .put("last_maintenance_at_ms", prefs.lastMaintenanceAtMs)
            .put("audio_signal", AudioSignalStore.snapshot())
            .put("audio_system", AudioSystemSignals.snapshot(appContext))
            .put(
                "sampled_vad",
                JSONObject()
                    .put("enabled", prefs.sampledVadEnabled)
                    .put("window_ms", prefs.sampledVadWindowMs)
                    .put("interval_ms", prefs.sampledVadIntervalMs),
            )
            .put(
                "local_speech_recognition",
                JSONObject()
                    .put("enabled", prefs.allowLocalSttFallback)
                    .put("android_on_device_only", true),
            )
            .put("context", ContextSignals.snapshot())
            .put("spool", JSONObject(spoolStats))
            .put("fallback_segments", JSONObject(fallbackStats))
            .put("current_session", currentSession)
        file.writeText(json.toString(2))
    }

    fun read(): String {
        if (!file.exists()) write("read_missing")
        return file.readText()
    }
}
