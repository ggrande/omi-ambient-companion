package com.omi.ambientcompanion

import android.content.Context
import java.util.UUID

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ambient_companion", Context.MODE_PRIVATE)

    var pluginBaseUrl: String
        get() = prefs.getString("plugin_base_url", "") ?: ""
        set(value) = prefs.edit().putString("plugin_base_url", value.trimEnd('/')).apply()

    var omiUserId: String
        get() = prefs.getString("omi_user_id", "") ?: ""
        set(value) = prefs.edit().putString("omi_user_id", value).apply()

    var omiAuthUid: String
        get() = prefs.getString("omi_auth_uid", "") ?: ""
        set(value) = prefs.edit().putString("omi_auth_uid", value).apply()

    var omiAuthEmail: String
        get() = prefs.getString("omi_auth_email", "") ?: ""
        set(value) = prefs.edit().putString("omi_auth_email", value).apply()

    var omiAuthState: String
        get() = prefs.getString("omi_auth_state", "") ?: ""
        set(value) = prefs.edit().putString("omi_auth_state", value).apply()

    var omiTokenExpiresAtMs: Long
        get() = prefs.getLong("omi_token_expires_at_ms", 0)
        set(value) = prefs.edit().putLong("omi_token_expires_at_ms", value).apply()

    var appInstallId: String
        get() {
            val existing = prefs.getString("app_install_id", "") ?: ""
            if (existing.isNotBlank()) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString("app_install_id", created).apply()
            return created
        }
        set(value) = prefs.edit().putString("app_install_id", value).apply()

    var deviceId: String
        get() = prefs.getString("device_id", "") ?: ""
        set(value) = prefs.edit().putString("device_id", value).apply()

    var controllerKeyId: String
        get() = prefs.getString("controller_key_id", "") ?: ""
        set(value) = prefs.edit().putString("controller_key_id", value).apply()

    var controllerPublicKey: String
        get() = prefs.getString("controller_public_key", "") ?: ""
        set(value) = prefs.edit().putString("controller_public_key", value).apply()

    var policyUrl: String
        get() = prefs.getString("policy_url", "") ?: ""
        set(value) = prefs.edit().putString("policy_url", value).apply()

    var telemetryUrl: String
        get() = prefs.getString("telemetry_url", "") ?: ""
        set(value) = prefs.edit().putString("telemetry_url", value).apply()

    var fallbackSegmentsUrl: String
        get() = prefs.getString("fallback_segments_url", "") ?: ""
        set(value) = prefs.edit().putString("fallback_segments_url", value).apply()

    var audioSpoolUrl: String
        get() = prefs.getString("audio_spool_url", "") ?: ""
        set(value) = prefs.edit().putString("audio_spool_url", value).apply()

    var lastAcceptedSequence: Long
        get() = prefs.getLong("last_accepted_sequence", 0)
        set(value) = prefs.edit().putLong("last_accepted_sequence", value).apply()

    var explicitSessionStarted: Boolean
        get() = prefs.getBoolean("explicit_session_started", false)
        set(value) = prefs.edit().putBoolean("explicit_session_started", value).apply()

    var maxStorageMb: Int
        get() = prefs.getInt("max_storage_mb", 1024)
        set(value) = prefs.edit().putInt("max_storage_mb", value).apply()

    var minFreeStorageMb: Int
        get() = prefs.getInt("min_free_storage_mb", 512)
        set(value) = prefs.edit().putInt("min_free_storage_mb", value).apply()

    var deleteSyncedAudio: Boolean
        get() = prefs.getBoolean("delete_synced_audio", true)
        set(value) = prefs.edit().putBoolean("delete_synced_audio", value).apply()

    var silenceDetectionSeconds: Int
        get() = prefs.getInt("silence_detection_seconds", 12)
        set(value) = prefs.edit().putInt("silence_detection_seconds", value).apply()

    var rmsSilenceDbfsThreshold: Float
        get() = prefs.getFloat("rms_silence_dbfs_threshold", -60f)
        set(value) = prefs.edit().putFloat("rms_silence_dbfs_threshold", value).apply()

    var zeroFrameThreshold: Float
        get() = prefs.getFloat("zero_frame_threshold", 0.98f)
        set(value) = prefs.edit().putFloat("zero_frame_threshold", value).apply()

    var allowAudioUpload: Boolean
        get() = prefs.getBoolean("allow_audio_upload", true)
        set(value) = prefs.edit().putBoolean("allow_audio_upload", value).apply()

    var allowCaptionFallback: Boolean
        get() = prefs.getBoolean("allow_caption_fallback", true)
        set(value) = prefs.edit().putBoolean("allow_caption_fallback", value).apply()

    var allowLocalSttFallback: Boolean
        get() = prefs.getBoolean("allow_local_stt_fallback", true)
        set(value) = prefs.edit().putBoolean("allow_local_stt_fallback", value).apply()

    var syncFailureCount: Int
        get() = prefs.getInt("sync_failure_count", 0)
        set(value) = prefs.edit().putInt("sync_failure_count", value).apply()

    var nextSyncAfterMs: Long
        get() = prefs.getLong("next_sync_after_ms", 0)
        set(value) = prefs.edit().putLong("next_sync_after_ms", value).apply()

    var setupIntroSeen: Boolean
        get() = prefs.getBoolean("setup_intro_seen", false)
        set(value) = prefs.edit().putBoolean("setup_intro_seen", value).apply()

    var armedStatusNotificationEnabled: Boolean
        get() = prefs.getBoolean("armed_status_notification_enabled", true)
        set(value) = prefs.edit().putBoolean("armed_status_notification_enabled", value).apply()

    var micWatchConsentAccepted: Boolean
        get() = prefs.getBoolean("mic_watch_consent_accepted", false)
        set(value) = prefs.edit().putBoolean("mic_watch_consent_accepted", value).apply()

    var continuousMicWatchEnabled: Boolean
        get() = prefs.getBoolean("continuous_mic_watch_enabled", false)
        set(value) = prefs.edit().putBoolean("continuous_mic_watch_enabled", value).apply()

    var sampledVadEnabled: Boolean
        get() = prefs.getBoolean("sampled_vad_enabled", true)
        set(value) = prefs.edit().putBoolean("sampled_vad_enabled", value).apply()

    var sampledVadIntervalMs: Long
        get() = prefs.getLong("sampled_vad_interval_ms", 15_000L)
        set(value) = prefs.edit().putLong("sampled_vad_interval_ms", value.coerceIn(5_000L, 120_000L)).apply()

    var sampledVadWindowMs: Long
        get() = prefs.getLong("sampled_vad_window_ms", 1_500L)
        set(value) = prefs.edit().putLong("sampled_vad_window_ms", value.coerceIn(500L, 10_000L)).apply()

    var maxActiveSegmentSeconds: Int
        get() = prefs.getInt("max_active_segment_seconds", 60)
        set(value) = prefs.edit().putInt("max_active_segment_seconds", value.coerceIn(15, 600)).apply()

    var junkFilterEnabled: Boolean
        get() = prefs.getBoolean("junk_filter_enabled", true)
        set(value) = prefs.edit().putBoolean("junk_filter_enabled", value).apply()

    var preferOmiUserVoice: Boolean
        get() = prefs.getBoolean("prefer_omi_user_voice", true)
        set(value) = prefs.edit().putBoolean("prefer_omi_user_voice", value).apply()

    var deskOnlyRecordingEnabled: Boolean
        get() = prefs.getBoolean("desk_only_recording_enabled", false)
        set(value) = prefs.edit().putBoolean("desk_only_recording_enabled", value).apply()

    var faceDownDeskOnlyEnabled: Boolean
        get() = prefs.getBoolean("face_down_desk_only_enabled", false)
        set(value) = prefs.edit().putBoolean("face_down_desk_only_enabled", value).apply()

    var omiHasSpeechProfile: Boolean
        get() = prefs.getBoolean("omi_has_speech_profile", false)
        set(value) = prefs.edit().putBoolean("omi_has_speech_profile", value).apply()

    var omiSpeechProfileCheckedAtMs: Long
        get() = prefs.getLong("omi_speech_profile_checked_at_ms", 0)
        set(value) = prefs.edit().putLong("omi_speech_profile_checked_at_ms", value).apply()

    var lastSyncLabel: String
        get() = prefs.getString("last_sync_label", "Not synced yet") ?: "Not synced yet"
        set(value) = prefs.edit().putString("last_sync_label", value.take(180)).apply()

    var lastOmiSyncTrace: String
        get() = prefs.getString("last_omi_sync_trace", "No Omi job trace yet") ?: "No Omi job trace yet"
        set(value) = prefs.edit().putString("last_omi_sync_trace", value.take(240)).apply()

    var lastMaintenanceAtMs: Long
        get() = prefs.getLong("last_maintenance_at_ms", 0)
        set(value) = prefs.edit().putLong("last_maintenance_at_ms", value).apply()

    var appInForeground: Boolean
        get() = prefs.getBoolean("app_in_foreground", false)
        set(value) = prefs.edit().putBoolean("app_in_foreground", value).apply()
}
