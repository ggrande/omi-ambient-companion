package com.omi.ambientcompanion

import android.content.Context
import org.json.JSONObject
import java.time.Instant

object ContextSignals {
    @Volatile var foregroundPackage: String? = null
    @Volatile var lastTriggerReason: String = "manual"
    @Volatile var captionFallbackActive: Boolean = false
    @Volatile var lastNotificationAtMs: Long = 0
    @Volatile var lastRouteChangeAtMs: Long = 0
    @Volatile var lastHighRiskAtMs: Long = 0
    private var lastCaptionText: String = ""

    val highRiskPackages = setOf(
        "com.microsoft.teams",
        "us.zoom.videomeetings",
        "com.google.android.apps.meetings",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.slack",
    )

    fun updateForeground(context: Context, packageName: String?) {
        if (packageName.isNullOrBlank()) return
        foregroundPackage = packageName
        if (packageName in highRiskPackages) {
            lastHighRiskAtMs = System.currentTimeMillis()
            lastTriggerReason = "high_risk_foreground:$packageName"
            AuditLog(context).record("high_risk_app_active", mapOf("package" to packageName))
            maybeStartMicFromContext(context, "accessibility_high_risk_app", "Meeting/call app detected. Mic idle.")
        }
    }

    fun enqueueCaption(context: Context, text: String, source: FallbackSource) {
        val prefs = AppPrefs(context)
        if (!prefs.allowCaptionFallback) return
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.length < 3 || normalized == lastCaptionText) return
        var decision = ConversationQualityFilter.evaluate(normalized, source)
        if (prefs.junkFilterEnabled) {
            if (!decision.allow) {
                RollingContextStore(context).record(source.apiName(), normalized, decision.reason, foregroundPackage, false)
                AuditLog(context).record(
                    "fallback_segment_rejected_junk",
                    mapOf("source" to source.name, "reason" to decision.reason, "text" to normalized.take(80)),
                )
                return
            }
        } else {
            decision = ConversationQualityFilter.evaluate(normalized, source)
        }
        lastCaptionText = normalized
        captionFallbackActive = true
        lastTriggerReason = source.name.lowercase()
        val health = AmbientForegroundMicService.lastHealthState()
        RollingContextStore(context).record(source.apiName(), normalized, decision.reason, foregroundPackage, decision.allow)
        FallbackSegmentQueue(context).enqueue(
            FallbackSegment(
                text = normalized,
                source = source,
                start = Instant.now(),
                end = Instant.now(),
                healthState = health,
                rawAudioAvailable = health == AmbientHealthState.AUDIO_OK || health == AmbientHealthState.SPEECH_DETECTED,
                foregroundApp = foregroundPackage,
            )
        )
        AuditLog(context).record("fallback_segment_queued", mapOf("source" to source.name, "foreground" to foregroundPackage))
        maybeStartMicFromContext(context, "caption_fallback", "Caption fallback active. Mic idle.")
    }

    fun triggerFromNotification(context: Context, packageName: String?, title: String, text: String) {
        triggerFromNotification(context, packageName, title, text, "", "")
    }

    fun triggerFromNotification(context: Context, packageName: String?, title: String, text: String, subText: String, bigText: String) {
        if (packageName == context.packageName) {
            AuditLog(context).record("notification_self_ignored", mapOf("title" to title.take(80)))
            return
        }
        val trigger = NotificationClassifier.classify(packageName, title, text, subText, bigText)
        if (!trigger.shouldStartCapture) return
        lastNotificationAtMs = System.currentTimeMillis()
        lastTriggerReason = trigger.reason
        RollingContextStore(context).record(
            "notification",
            listOf(title, text, subText, bigText).joinToString(" "),
            trigger.reason,
            packageName,
            trigger.shouldQueueCaption,
        )
        AuditLog(context).record("notification_trigger", mapOf("package" to packageName, "title" to title.take(80)))
        if (trigger.shouldQueueCaption) {
            enqueueCaption(context, text.ifBlank { title }, FallbackSource.LIVE_CAPTION_NOTIFICATION)
        } else {
            maybeStartMicFromContext(context, "notification_context", "Relevant notification detected. Mic idle.")
        }
    }

    fun triggerFromAudioRoute(context: Context, reason: String) {
        lastRouteChangeAtMs = System.currentTimeMillis()
        lastTriggerReason = reason
        AuditLog(context).record("audio_route_trigger", mapOf("reason" to reason))
        maybeStartMicFromContext(context, reason, "Audio route changed. Mic idle.")
    }

    private fun maybeStartMicFromContext(context: Context, reason: String, idleText: String) {
        val prefs = AppPrefs(context)
        DevicePlacementMonitor.start(context)
        if (!DevicePlacementMonitor.recordingAllowed(prefs)) {
            AuditLog(context).record("context_trigger_waiting_for_placement", mapOf("reason" to reason))
            ArmedStatusNotifier.show(context, "Context detected. ${DevicePlacementMonitor.label(prefs)}")
            return
        }
        if (prefs.continuousMicWatchEnabled && prefs.micWatchConsentAccepted) {
            AmbientForegroundMicService.start(context, reason)
        } else {
            AuditLog(context).record("context_trigger_armed_only", mapOf("reason" to reason))
            ArmedStatusNotifier.show(context, idleText)
        }
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("foreground_package", foregroundPackage)
        .put("last_trigger_reason", lastTriggerReason)
        .put("caption_fallback_active", captionFallbackActive)
        .put("last_notification_at_ms", lastNotificationAtMs)
        .put("last_route_change_at_ms", lastRouteChangeAtMs)
        .put("last_high_risk_at_ms", lastHighRiskAtMs)

    private fun FallbackSource.apiName(): String = when (this) {
        FallbackSource.LOCAL_STT -> "local_stt"
        FallbackSource.ACCESSIBILITY_CAPTION -> "accessibility_caption"
        FallbackSource.LIVE_CAPTION_NOTIFICATION -> "live_caption"
        FallbackSource.SOUND_NOTIFICATION -> "sound_notification"
        FallbackSource.GAP_MARKER -> "gap_marker"
    }
}
