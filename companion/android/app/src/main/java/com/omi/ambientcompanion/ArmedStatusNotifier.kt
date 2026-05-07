package com.omi.ambientcompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object ArmedStatusNotifier {
    private const val CHANNEL_ID = "omi_ambient_companion_armed"
    const val NOTIFICATION_ID = 55043

    fun show(context: Context, text: String = "Armed for context triggers. Mic is idle.") {
        val appContext = context.applicationContext
        if (!AppPrefs(appContext).armedStatusNotificationEnabled) return
        createChannel(appContext)
        runCatching {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(appContext, text))
        }.onFailure {
            AuditLog(appContext).record("armed_notification_skipped", mapOf("reason" to it.javaClass.simpleName))
        }
    }

    fun cancel(context: Context) {
        runCatching {
            (context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
    }

    private fun buildNotification(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            40,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder
            .setContentTitle("Omi Ambient Companion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Start", action(context, ArmedStatusActionReceiver.ACTION_START, 1)).build())
            .addAction(Notification.Action.Builder(0, "Pause", action(context, ArmedStatusActionReceiver.ACTION_PAUSE, 2)).build())
            .addAction(Notification.Action.Builder(0, "Resume", action(context, ArmedStatusActionReceiver.ACTION_RESUME, 3)).build())
            .addAction(Notification.Action.Builder(0, "Stop", action(context, ArmedStatusActionReceiver.ACTION_STOP, 4)).build())
            .addAction(Notification.Action.Builder(0, "Close", action(context, ArmedStatusActionReceiver.ACTION_CLOSE, 5)).build())
            .build()
    }

    private fun action(context: Context, action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ArmedStatusActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Omi Ambient Armed",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Visible idle status while the companion is armed but not using the microphone."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
