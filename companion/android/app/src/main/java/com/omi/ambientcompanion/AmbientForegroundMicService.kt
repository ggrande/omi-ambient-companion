package com.omi.ambientcompanion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AmbientForegroundMicService : Service() {
    private lateinit var spoolStore: CaptureSpoolStore
    private lateinit var sessionStore: CaptureSessionStore
    private lateinit var audit: AuditLog
    private lateinit var pluginClient: PluginClient
    private lateinit var communicationMonitor: CommunicationStateMonitor
    private lateinit var prefs: AppPrefs
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val capturing = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val privateMode = AtomicBoolean(false)
    private var vad = VadWatchEngine()
    private var lastHealth = HealthEvent(AmbientHealthState.IDLE_CONTEXT_WATCH, "created")
    private var speechSessionActive = false
    private var lastAudioAt = 0L
    private var lastPlacementGateLogMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var policyLoop: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        prefs = AppPrefs(this)
        spoolStore = CaptureSpoolStore(this)
        sessionStore = CaptureSessionStore(this)
        audit = AuditLog(this)
        pluginClient = PluginClient(this)
        communicationMonitor = CommunicationStateMonitor(this) { health -> updateHealth(health) }
        DevicePlacementMonitor.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            audit.record("service_restart_ignored", mapOf("reason" to "null_intent"))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START -> startCapture(intent?.getStringExtra(EXTRA_REASON) ?: "manual")
            ACTION_PAUSE -> pauseCapture()
            ACTION_RESUME -> resumeCapture()
            ACTION_STOP -> stopCapture(stopSelf = true)
            ACTION_PRIVATE -> enterPrivateMode()
            ACTION_FLUSH_SYNC -> flushCurrentSegmentAndSync("command")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture(stopSelf = false)
        super.onDestroy()
    }

    private fun startCapture(reason: String) {
        if (capturing.get()) return
        configureVadFromPrefs()
        ArmedStatusNotifier.cancel(this)
        startForeground(NOTIFICATION_ID, buildNotification(if (prefs.sampledVadEnabled) "Sampled VAD" else "VAD watch"))
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateHealth(HealthEvent(AmbientHealthState.PERMISSION_MISSING, "record_audio_missing"))
            return
        }
        prefs.explicitSessionStarted = true
        val preMicContext = RollingContextStore(this).summary()
        sessionStore.start(reason, preMicContext)
        ContextSignals.lastTriggerReason = reason
        paused.set(false)
        privateMode.set(false)
        capturing.set(true)
        updateHealth(HealthEvent(if (prefs.sampledVadEnabled) AmbientHealthState.SAMPLED_VAD else AmbientHealthState.VAD_WATCH, reason, ContextSignals.foregroundPackage))
        communicationMonitor.start()
        startPolicyLoop()
        captureThread = thread(name = "ambient-vad-capture") { captureLoop() }
        audit.record("capture_started", mapOf("reason" to reason, "pre_mic_context" to preMicContext.take(240)))
        pluginClient.sendTelemetry("capture_started", lastHealth, ContextSignals.snapshot())
    }

    private fun captureLoop() {
        while (capturing.get()) {
            if (paused.get() || privateMode.get()) {
                Thread.sleep(250)
                continue
            }
            if (!DevicePlacementMonitor.recordingAllowed(prefs)) {
                handlePlacementGateBlocked()
                sleepResponsive(1_000L)
                continue
            }

            val localRecorder = if (prefs.sampledVadEnabled && !speechSessionActive) {
                updateHealth(HealthEvent(AmbientHealthState.SAMPLED_VAD, "waiting_for_sample_window", ContextSignals.foregroundPackage))
                updateNotification("Sampled VAD")
                sampleForSpeech() ?: run {
                    sleepResponsive(prefs.sampledVadIntervalMs)
                    null
                }
            } else {
                openRecorder()
            } ?: continue

            activeReadLoop(localRecorder, exitAfterSampledSpeech = prefs.sampledVadEnabled)
            runCatching { localRecorder.stop() }
            localRecorder.release()
            recorder = null
        }
    }

    private fun sampleForSpeech(): AudioRecord? {
        val localRecorder = openRecorder() ?: return null
        updateNotification("Checking for speech")
        val buffer = ByteArray(FRAME_BYTES)
        val deadline = System.currentTimeMillis() + prefs.sampledVadWindowMs
        while (capturing.get() && !paused.get() && !privateMode.get() && System.currentTimeMillis() < deadline) {
            val read = localRecorder.read(buffer, 0, buffer.size)
            if (read <= 0) {
                updateHealth(HealthEvent(AmbientHealthState.RECOVERY_NEEDED, "sample_audio_read_$read", ContextSignals.foregroundPackage))
                continue
            }
            lastAudioAt = System.currentTimeMillis()
            handleAudioChunk(buffer.copyOf(read))
            if (speechSessionActive) return localRecorder
        }
        runCatching { localRecorder.stop() }
        localRecorder.release()
        recorder = null
        return null
    }

    private fun activeReadLoop(localRecorder: AudioRecord, exitAfterSampledSpeech: Boolean) {
        val buffer = ByteArray(FRAME_BYTES)
        while (capturing.get() && !paused.get() && !privateMode.get()) {
            val wasActive = speechSessionActive
            val read = localRecorder.read(buffer, 0, buffer.size)
            if (read <= 0) {
                updateHealth(HealthEvent(AmbientHealthState.RECOVERY_NEEDED, "audio_read_$read", ContextSignals.foregroundPackage))
                Thread.sleep(50)
                continue
            }
            lastAudioAt = System.currentTimeMillis()
            handleAudioChunk(buffer.copyOf(read))
            if (exitAfterSampledSpeech && wasActive && !speechSessionActive) break
            if (System.currentTimeMillis() - lastAudioAt > 30_000) updateHealth(HealthEvent(AmbientHealthState.RECOVERY_NEEDED, "no_audio_chunks_received"))
        }
    }

    private fun handleAudioChunk(chunk: ByteArray) {
        if (!DevicePlacementMonitor.recordingAllowed(prefs)) {
            handlePlacementGateBlocked()
            return
        }
        val result = vad.accept(chunk)
        val signalEvents = AudioSignalStore.update(result, vad.activeSpeech, speechSessionActive)
        signalEvents.forEach { event ->
            audit.record(event, mapOf("dbfs" to result.dbfs, "zero_ratio" to result.zeroRatio))
            if (event == "sound_detected" || event == "conversation_detected") {
                updateNotification(if (event == "conversation_detected") "Conversation detected" else "Sound detected")
            }
        }
        communicationMonitor.evaluate()
        if (!speechSessionActive && vad.activeSpeech) {
            speechSessionActive = true
            acquireWakeLock()
            spoolStore.startSession()
            vad.drainPreRoll().forEach { spoolStore.writeChunk(it) }
            updateHealth(HealthEvent(AmbientHealthState.SPEECH_DETECTED, "vad_triggered", ContextSignals.foregroundPackage, result.dbfs, result.zeroRatio))
            updateNotification("Speech detected")
            pluginClient.sendTelemetry("speech_detected", lastHealth, ContextSignals.snapshot())
        }
        if (speechSessionActive) {
            maybeRollActiveSegment()
            val write = spoolStore.writeChunk(chunk)
            if (!write.ok) {
                updateHealth(HealthEvent(AmbientHealthState.STORAGE_LIMIT_REACHED, write.reason, ContextSignals.foregroundPackage))
                pauseCapture()
            } else if (result.speech) {
                updateHealth(HealthEvent(AmbientHealthState.AUDIO_OK, "speech_audio", ContextSignals.foregroundPackage, result.dbfs, result.zeroRatio))
                prefs.lastSyncLabel = "Recording segment; sync after close or tap Sync"
            }
        }
        if (speechSessionActive && !vad.activeSpeech) {
            speechSessionActive = false
            spoolStore.closeSession()
            releaseWakeLock()
            updateHealth(HealthEvent(if (prefs.sampledVadEnabled) AmbientHealthState.SAMPLED_VAD else AmbientHealthState.VAD_WATCH, "silence_timeout", ContextSignals.foregroundPackage, result.dbfs, result.zeroRatio))
            updateNotification(if (prefs.sampledVadEnabled) "Sampled VAD" else "VAD watch")
            LocalSttWorker(applicationContext).drainSpoolForLocalTranscripts()
            SyncWorker.drainAsync(applicationContext)
        }
    }

    private fun maybeRollActiveSegment() {
        if (!speechSessionActive || !spoolStore.hasOpenSession()) return
        val maxMs = prefs.maxActiveSegmentSeconds.toLong() * 1000L
        if (spoolStore.currentSessionAgeMs() < maxMs) return
        val bytes = spoolStore.currentSessionBytes()
        spoolStore.closeSession()
        audit.record("spool_session_rolled", mapOf("reason" to "max_active_segment_seconds", "bytes" to bytes))
        prefs.lastSyncLabel = "Segment closed; upload queued"
        LocalSttWorker(applicationContext).drainSpoolForLocalTranscripts()
        SyncWorker.drainAsync(applicationContext, force = true)
        spoolStore.startSession()
        updateNotification("Recording; previous segment syncing")
    }

    private fun handlePlacementGateBlocked() {
        if (speechSessionActive || spoolStore.hasOpenSession()) {
            spoolStore.closeSession("placement_gate")
            speechSessionActive = false
            releaseWakeLock()
            LocalSttWorker(applicationContext).drainSpoolForLocalTranscripts()
            SyncWorker.drainAsync(applicationContext, force = true)
        }
        val now = System.currentTimeMillis()
        if (now - lastPlacementGateLogMs > 30_000L) {
            audit.record("capture_waiting_for_placement", mapOf("placement" to DevicePlacementMonitor.label(prefs)))
            lastPlacementGateLogMs = now
        }
        prefs.lastSyncLabel = "Waiting for placement gate"
        updateHealth(HealthEvent(AmbientHealthState.IDLE_CONTEXT_WATCH, "placement_gate", ContextSignals.foregroundPackage))
        updateNotification(DevicePlacementMonitor.label(prefs).take(80))
    }

    private fun flushCurrentSegmentAndSync(reason: String) {
        val wasOpen = speechSessionActive && spoolStore.hasOpenSession()
        if (wasOpen) {
            val bytes = spoolStore.currentSessionBytes()
            spoolStore.closeSession()
            speechSessionActive = false
            audit.record("spool_flush_requested", mapOf("reason" to reason, "bytes" to bytes))
            prefs.lastSyncLabel = "Flushed active segment; sync queued"
            updateNotification("Syncing flushed segment")
        } else {
            audit.record("sync_requested", mapOf("reason" to reason, "active_segment" to false))
            prefs.lastSyncLabel = "Sync requested"
        }
        LocalSttWorker(applicationContext).drainSpoolForLocalTranscripts()
        SyncWorker.drainAsync(applicationContext, force = true)
    }

    private fun openRecorder(): AudioRecord? {
        recorder?.let { return it }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferBytes = maxOf(minBuffer, FRAME_BYTES * 4)
        val localRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferBytes,
        )
        return try {
            localRecorder.startRecording()
            recorder = localRecorder
            localRecorder
        } catch (e: Throwable) {
            updateHealth(HealthEvent(AmbientHealthState.RECOVERY_NEEDED, "audio_record_start_failed:${e.javaClass.simpleName}"))
            runCatching { localRecorder.release() }
            null
        }
    }

    private fun sleepResponsive(totalMs: Long) {
        var slept = 0L
        while (capturing.get() && !paused.get() && !privateMode.get() && slept < totalMs) {
            val step = minOf(500L, totalMs - slept)
            Thread.sleep(step)
            slept += step
        }
    }

    private fun pauseCapture() {
        paused.set(true)
        sessionStore.update("paused", AmbientHealthState.VAD_WATCH)
        if (speechSessionActive) spoolStore.closeSession()
        speechSessionActive = false
        releaseWakeLock()
        updateHealth(HealthEvent(AmbientHealthState.VAD_WATCH, "paused"))
        updateNotification("Paused")
        audit.record("capture_paused")
        pluginClient.sendTelemetry("capture_paused", lastHealth)
    }

    private fun resumeCapture() {
        paused.set(false)
        privateMode.set(false)
        sessionStore.update("running", AmbientHealthState.VAD_WATCH)
        updateHealth(HealthEvent(AmbientHealthState.VAD_WATCH, "resumed"))
        updateNotification("VAD watch")
        audit.record("capture_resumed")
        pluginClient.sendTelemetry("capture_resumed", lastHealth)
    }

    private fun enterPrivateMode() {
        privateMode.set(true)
        paused.set(true)
        sessionStore.finish("private")
        if (speechSessionActive) spoolStore.closeSession("private")
        speechSessionActive = false
        releaseWakeLock()
        updateHealth(HealthEvent(AmbientHealthState.PRIVATE_MODE, "private_mode"))
        updateNotification("Private mode")
        audit.record("private_mode_enabled")
        pluginClient.sendTelemetry("private_mode_enabled", lastHealth)
    }

    private fun stopCapture(stopSelf: Boolean) {
        if (!capturing.getAndSet(false)) {
            if (stopSelf) stopSelf()
            return
        }
        if (speechSessionActive) spoolStore.closeSession()
        speechSessionActive = false
        releaseWakeLock()
        communicationMonitor.stop()
        stopPolicyLoop()
        recorder = null
        sessionStore.finish("stopped")
        updateHealth(HealthEvent(AmbientHealthState.IDLE_CONTEXT_WATCH, "stopped"))
        audit.record("capture_stopped")
        pluginClient.sendTelemetry("capture_stopped", lastHealth)
        SyncWorker.drainAsync(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        ArmedStatusNotifier.show(this)
        DevicePlacementMonitor.stop()
        if (stopSelf) stopSelf()
    }

    private fun startPolicyLoop() {
        if (prefs.policyUrl.isBlank() || SecureStore(this).getSecret("device_token").isBlank()) {
            audit.record("policy_loop_skipped", mapOf("reason" to "controller_not_configured"))
            return
        }
        policyLoop?.let { mainHandler.removeCallbacks(it) }
        policyLoop = Runnable {
            val result = pluginClient.fetchPolicy()
            if (result.accepted) {
                configureVadFromPrefs()
                audit.record("policy_applied")
            } else {
                audit.record("policy_rejected", mapOf("reason" to result.reason))
                pluginClient.sendTelemetry("policy_rejected", lastHealth, org.json.JSONObject().put("reason", result.reason))
            }
            policyLoop?.let { mainHandler.postDelayed(it, 60_000) }
        }
        policyLoop?.run()
    }

    private fun stopPolicyLoop() {
        policyLoop?.let { mainHandler.removeCallbacks(it) }
        policyLoop = null
    }

    private fun configureVadFromPrefs() {
        vad = VadWatchEngine(
            rmsSpeechDbfsThreshold = prefs.rmsSilenceDbfsThreshold.toDouble(),
            zeroRatioSilenceThreshold = prefs.zeroFrameThreshold.toDouble(),
            silenceFramesToEnd = (prefs.silenceDetectionSeconds * 1000 / 30).coerceAtLeast(10),
        )
    }

    private fun updateHealth(event: HealthEvent) {
        lastHealth = event
        lastState = event.state
        DiagnosticsStore(this).write("health:${event.state.name}")
        if (event.state == AmbientHealthState.AUDIO_SILENCED_BY_SYSTEM ||
            event.state == AmbientHealthState.COMMUNICATION_MODE_DEGRADED
        ) {
            FallbackSegmentQueue(this).enqueue(
                FallbackSegment(
                    text = "[${event.reason}]",
                    source = FallbackSource.GAP_MARKER,
                    start = Instant.now(),
                    end = Instant.now(),
                    healthState = event.state,
                    rawAudioAvailable = false,
                    foregroundApp = ContextSignals.foregroundPackage,
                )
            )
        }
        sendBroadcast(Intent(ACTION_HEALTH_CHANGED).putExtra("health", event.toJson().toString()))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmiAmbient:activeCapture")
            .apply { acquire(10 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Omi Ambient Companion", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("Omi Ambient Companion")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setUsesChronometer(capturing.get())
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Pause", serviceIntent(ACTION_PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(0, "Resume", serviceIntent(ACTION_RESUME, 2)).build())
            .addAction(Notification.Action.Builder(0, "Sync", serviceIntent(ACTION_FLUSH_SYNC, 3)).build())
            .addAction(Notification.Action.Builder(0, "Stop", serviceIntent(ACTION_STOP, 4)).build())
            .addAction(Notification.Action.Builder(0, "Private", serviceIntent(ACTION_PRIVATE, 5)).build())
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AmbientForegroundMicService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_BYTES = 960 // 30 ms @ 16 kHz mono PCM16
        private const val CHANNEL_ID = "omi_ambient_companion"
        private const val NOTIFICATION_ID = 55042
        const val ACTION_HEALTH_CHANGED = "com.omi.ambientcompanion.HEALTH_CHANGED"
        const val ACTION_START = "com.omi.ambientcompanion.START"
        const val ACTION_PAUSE = "com.omi.ambientcompanion.PAUSE"
        const val ACTION_RESUME = "com.omi.ambientcompanion.RESUME"
        const val ACTION_STOP = "com.omi.ambientcompanion.STOP"
        const val ACTION_PRIVATE = "com.omi.ambientcompanion.PRIVATE"
        const val ACTION_FLUSH_SYNC = "com.omi.ambientcompanion.FLUSH_SYNC"
        private const val EXTRA_REASON = "reason"
        @Volatile private var lastState: AmbientHealthState = AmbientHealthState.IDLE_CONTEXT_WATCH

        fun start(context: Context, reason: String = "manual") {
            val prefs = AppPrefs(context)
            val userInitiated = reason.startsWith("manual") || reason == "armed_notification_start"
            val companionAssociated = CompanionDeviceSupport.associationCount(context) > 0
            if (!prefs.micWatchConsentAccepted) {
                ArmedStatusNotifier.show(context, "Open app to accept microphone watch consent.")
                AuditLog(context).record("mic_start_blocked_missing_consent", mapOf("reason" to reason))
                return
            }
            DevicePlacementMonitor.start(context)
            if (!DevicePlacementMonitor.recordingAllowed(prefs)) {
                ArmedStatusNotifier.show(context, "Waiting for placement. ${DevicePlacementMonitor.label(prefs)}")
                AuditLog(context).record("mic_start_blocked_placement", mapOf("reason" to reason, "placement" to DevicePlacementMonitor.label(prefs)))
                return
            }
            if (!userInitiated && !prefs.continuousMicWatchEnabled) {
                ArmedStatusNotifier.show(context, "Context detected. Mic is idle.")
                AuditLog(context).record("mic_auto_start_blocked", mapOf("reason" to reason))
                return
            }
            if (!userInitiated && !prefs.appInForeground && !companionAssociated) {
                ArmedStatusNotifier.show(context, "Context detected. Open app or start from notification to use mic.")
                AuditLog(context).record("mic_auto_start_blocked_background", mapOf("reason" to reason))
                return
            }
            val intent = Intent(context, AmbientForegroundMicService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REASON, reason)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure {
                ArmedStatusNotifier.show(context, "Mic start blocked by Android. Open app and press Start.")
                AuditLog(context).record(
                    "mic_start_failed",
                    mapOf("reason" to reason, "error" to it.javaClass.simpleName, "companion_associated" to companionAssociated),
                )
            }
        }

        fun command(context: Context, action: String) {
            if (action == ACTION_FLUSH_SYNC && lastState == AmbientHealthState.IDLE_CONTEXT_WATCH) {
                val prefs = AppPrefs(context)
                prefs.nextSyncAfterMs = 0
                prefs.lastSyncLabel = "Sync requested"
                AuditLog(context).record("sync_requested", mapOf("source" to "idle_command"))
                SyncWorker.drainAsync(context, force = true)
                return
            }
            if (action == ACTION_STOP || action == ACTION_PAUSE || action == ACTION_PRIVATE) {
                if (lastState == AmbientHealthState.IDLE_CONTEXT_WATCH) {
                    if (action == ACTION_PRIVATE) {
                        ArmedStatusNotifier.show(context, "Private mode. Mic is idle.")
                        AuditLog(context).record("private_mode_enabled", mapOf("source" to "idle_command"))
                    }
                    return
                }
                context.startService(Intent(context, AmbientForegroundMicService::class.java).setAction(action))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, AmbientForegroundMicService::class.java).setAction(action))
            } else {
                context.startService(Intent(context, AmbientForegroundMicService::class.java).setAction(action))
            }
        }

        fun lastHealthState(): AmbientHealthState = lastState
    }
}
