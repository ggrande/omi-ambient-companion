package com.omi.ambientcompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ArmedStatusActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_START -> {
                if (AppPrefs(context).micWatchConsentAccepted) {
                    AmbientForegroundMicService.start(context, "armed_notification_start")
                } else {
                    ArmedStatusNotifier.show(context, "Open app to accept microphone watch consent.")
                    AuditLog(context).record("mic_start_blocked_missing_consent", mapOf("source" to "armed_notification"))
                }
            }
            ACTION_SYNC -> AmbientForegroundMicService.command(context, AmbientForegroundMicService.ACTION_FLUSH_SYNC)
            ACTION_PAUSE -> {
                AmbientForegroundMicService.command(context, AmbientForegroundMicService.ACTION_PAUSE)
                ArmedStatusNotifier.show(context, "Paused. Mic is idle.")
            }
            ACTION_RESUME -> {
                ArmedStatusNotifier.show(context, "Armed for context triggers. Mic is idle.")
                AuditLog(context).record("armed_notification_resumed")
            }
            ACTION_STOP -> {
                AmbientForegroundMicService.command(context, AmbientForegroundMicService.ACTION_STOP)
                ArmedStatusNotifier.show(context, "Stopped. Mic is idle.")
            }
            ACTION_PRIVATE -> {
                AmbientForegroundMicService.command(context, AmbientForegroundMicService.ACTION_PRIVATE)
                ArmedStatusNotifier.show(context, "Private mode. Mic is idle.")
                AuditLog(context).record("private_mode_enabled", mapOf("source" to "armed_notification"))
            }
            ACTION_CLOSE -> {
                AppPrefs(context).armedStatusNotificationEnabled = false
                ArmedStatusNotifier.cancel(context)
                AuditLog(context).record("armed_notification_closed")
            }
        }
    }

    companion object {
        const val ACTION_START = "com.omi.ambientcompanion.ARMED_START"
        const val ACTION_SYNC = "com.omi.ambientcompanion.ARMED_SYNC"
        const val ACTION_PAUSE = "com.omi.ambientcompanion.ARMED_PAUSE"
        const val ACTION_RESUME = "com.omi.ambientcompanion.ARMED_RESUME"
        const val ACTION_STOP = "com.omi.ambientcompanion.ARMED_STOP"
        const val ACTION_PRIVATE = "com.omi.ambientcompanion.ARMED_PRIVATE"
        const val ACTION_CLOSE = "com.omi.ambientcompanion.ARMED_CLOSE"
    }
}
