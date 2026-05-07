package com.omi.ambientcompanion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: AppPrefs
    private lateinit var status: TextView
    private lateinit var audit: TextView
    private lateinit var storage: TextView
    private lateinit var diagnostics: TextView
    private lateinit var preflight: TextView
    private lateinit var omiAuthStatus: TextView
    private lateinit var signal: TextView
    private lateinit var chartRow: LinearLayout
    private val uiHandler = Handler(Looper.getMainLooper())
    private val liveRefresh = object : Runnable {
        override fun run() {
            refreshLiveUi("live")
            uiHandler.postDelayed(this, LIVE_REFRESH_MS)
        }
    }

    private val healthReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra("health") ?: return
            if (::status.isInitialized) status.text = prettyHealth(JSONObject(json))
            refreshAudit()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            prefs = AppPrefs(this)
            AmbientMaintenanceScheduler.schedule(this, "ui")
            DevicePlacementMonitor.start(this)
            setContentView(buildUi())
            handleOauthCallback(intent)
            handleSetupLink(intent)
            if (!prefs.setupIntroSeen) {
                window.decorView.post { showSetupDialog() }
            } else {
                ArmedStatusNotifier.show(this)
            }
        }.onFailure { error ->
            runCatching { AuditLog(this).record("main_activity_startup_failed", mapOf("error" to error.toString().take(240))) }
            setContentView(buildRecoveryUi(error))
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::prefs.isInitialized) return
        handleOauthCallback(intent)
        handleSetupLink(intent)
    }

    override fun onStart() {
        super.onStart()
        if (::prefs.isInitialized) prefs.appInForeground = true
    }

    override fun onStop() {
        if (::prefs.isInitialized) prefs.appInForeground = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(healthReceiver, IntentFilter(AmbientForegroundMicService.ACTION_HEALTH_CHANGED), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(healthReceiver, IntentFilter(AmbientForegroundMicService.ACTION_HEALTH_CHANGED))
        }
        refreshLiveUi("resume")
        uiHandler.removeCallbacks(liveRefresh)
        uiHandler.post(liveRefresh)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(liveRefresh)
        runCatching { unregisterReceiver(healthReceiver) }
        super.onPause()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
            setBackgroundColor(0xff050507.toInt())
        }
        root.addView(text("Omi Ambient Companion", 26, bold = true))
        root.addView(text("Direct Omi audio sync with local capture, VAD, captions, and optional plugin control.", 14))

        status = text("Status: ${AmbientForegroundMicService.lastHealthState().name}", 16, bold = true)
        root.addView(status)
        signal = text(AudioSignalStore.label(), 14, bold = true)
        root.addView(signal)
        omiAuthStatus = text(omiAuthLabel(), 12, bold = true)
        root.addView(omiAuthStatus)
        root.addView(row(
            button("Sign in with Omi") { signInWithOmi() },
            button("Sign out Omi") { signOutOmi() },
        ))
        root.addView(row(
            button("Start") { startFullCapture() },
            button("Sync") { AmbientForegroundMicService.command(this, AmbientForegroundMicService.ACTION_FLUSH_SYNC) },
        ))
        root.addView(row(
            button("Pause") { AmbientForegroundMicService.command(this, AmbientForegroundMicService.ACTION_PAUSE) },
            button("Stop") { AmbientForegroundMicService.command(this, AmbientForegroundMicService.ACTION_STOP) },
            button("Private") { AmbientForegroundMicService.command(this, AmbientForegroundMicService.ACTION_PRIVATE) },
        ))
        root.addView(button("Permissions & setup") { showSetupDialog() })
        root.addView(button("Advanced settings") { showAdvancedDialog() })
        root.addView(text("Last 15 minutes", 18, bold = true))
        chartRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, 6, 0, 6)
        }
        root.addView(chartRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        preflight = text("", 12)
        root.addView(text("Preflight", 18, bold = true))
        root.addView(preflight)
        storage = text("", 12)
        root.addView(storage)
        audit = text("", 12)
        root.addView(text("Recent log", 18, bold = true))
        root.addView(audit)
        diagnostics = text("", 12)
        refreshPreflight()
        refreshStorage()
        refreshDiagnostics()
        return ScrollView(this).apply { addView(root) }
    }

    private fun buildRecoveryUi(error: Throwable): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
            setBackgroundColor(0xff050507.toInt())
        }
        root.addView(text("Omi Ambient Companion", 26, bold = true))
        root.addView(text("Startup recovery", 18, bold = true))
        root.addView(text("The normal dashboard hit an error, but this recovery screen is still running.", 14))
        root.addView(text(error.toString().take(500), 12))
        root.addView(button("Share diagnostics") { shareDiagnostics() })
        root.addView(button("Open app info") { openAppInfo() })
        root.addView(button("Delete pending audio") {
            CaptureSpoolStore(this).deleteByStatus("pending")
            AuditLog(this).record("spool_deleted", mapOf("status" to "pending", "source" to "recovery_ui"))
        })
        root.addView(text(AuditLog(this).tail(20).joinToString("\n"), 12))
        return ScrollView(this).apply { addView(root) }
    }

    @Deprecated("Deprecated by platform, but sufficient for this simple personal native activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST && resultCode == RESULT_OK && data != null) {
            MediaProjectionSessionService.start(this, resultCode, data)
        } else if (requestCode == CompanionDeviceSupport.REQUEST_ASSOCIATE) {
            CompanionDeviceSupport.onAssociationResult(this, resultCode)
            refreshPreflight()
            refreshAudit()
        }
    }

    private fun registerDevice(pluginBaseUrl: String, omiUserId: String) {
        prefs.pluginBaseUrl = pluginBaseUrl
        prefs.omiUserId = omiUserId
        thread {
            val ok = PluginClient(this).registerDevice(prefs.pluginBaseUrl, prefs.omiUserId)
            runOnUiThread {
                status.text = if (ok) "Registered controller: ${prefs.controllerKeyId}" else "Registration failed"
                refreshPreflight()
                refreshAudit()
            }
        }
    }

    private fun signInWithOmi() {
        runCatching {
            startActivity(OmiAuthClient(this).buildSignInIntent("google"))
        }.onFailure {
            AuditLog(this).record("omi_auth_open_failed", mapOf("error" to (it.message ?: it.toString())))
            refreshPreflight()
        }
    }

    private fun signOutOmi() {
        OmiAuthClient(this).signOut()
        refreshPreflight()
        refreshAudit()
    }

    private fun handleOauthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "omi-ambient-companion" || uri.host != "auth" || uri.path != "/callback") return
        thread {
            val ok = OmiAuthClient(this).handleCallback(uri)
            runOnUiThread {
                status.text = if (ok) "Signed in to Omi: ${prefs.omiAuthUid}" else "Omi sign-in failed"
                refreshPreflight()
                refreshAudit()
            }
        }
    }

    private fun startFullCapture() {
        requestRuntimePermissions()
        if (!prefs.micWatchConsentAccepted) {
            showMicWatchConsentDialog(startAfterAccept = true)
            return
        }
        AmbientForegroundMicService.start(this, "manual_start")
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_CONNECT
        requestPermissions(permissions.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPreflight()
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure { openAppInfo() }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }

    private fun openOmiPluginPage() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://h.omi.me/apps/ambient_second_brain_controller")))
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST)
    }

    private fun showSetupDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            setBackgroundColor(0xff050507.toInt())
            addView(text(setupChecklist(), 12, bold = true))
            addView(text("Enable the Android permissions that keep capture reliable. Accessibility and notification access are used for context and caption fallbacks.", 14))
            addView(text("Direct Omi sync only needs Omi sign-in and microphone permission. Accessibility, notification listener, and unrestricted battery make context triggers and fallbacks much more reliable.", 12))
            addView(text("By default the companion is armed without using the microphone. Continuous mic watch only starts after you explicitly accept it.", 12, bold = true))
            addView(button("Sign in with Omi") { signInWithOmi() })
            addView(button("Microphone permission popup") { requestRuntimePermissions() })
            addView(button("Notification settings") { openNotificationSettings() })
            addView(button("Accessibility service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            addView(button("Notification listener") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            addView(button("Battery unrestricted") { openBatterySettings() })
            addView(button("Pair companion device") { CompanionDeviceSupport.requestAssociation(this@MainActivity) })
            addView(button("Accept mic watch consent") { showMicWatchConsentDialog(startAfterAccept = false) })
            addView(button("App info") { openAppInfo() })
            addView(button("Return to Omi") { openOmiPluginPage() })
        }
        AlertDialog.Builder(this)
            .setTitle("Permissions & setup")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Done") { _, _ ->
                prefs.setupIntroSeen = true
                ArmedStatusNotifier.show(this)
                refreshPreflight()
                refreshAudit()
            }
            .show()
    }

    private fun showAdvancedDialog() {
        val pluginField = field("Plugin base URL", prefs.pluginBaseUrl)
        val userField = field("Omi user id", prefs.omiUserId)
        val diagnosticText = text(DiagnosticsStore(this).read(), 12)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            setBackgroundColor(0xff050507.toInt())
            addView(text("Optional controller plugin", 16, bold = true))
            addView(pluginField)
            addView(userField)
            addView(row(
                button("Register plugin") { registerDevice(pluginField.text.toString(), userField.text.toString()) },
                button("Refresh policy") { thread { PluginClient(this@MainActivity).fetchPolicy() } },
            ))
            addView(text("Capture tools", 16, bold = true))
            addView(text("Idle notification: ${if (prefs.armedStatusNotificationEnabled) "on" else "off"}", 12))
            addView(text("Sampled VAD: ${if (prefs.sampledVadEnabled) "on (${prefs.sampledVadWindowMs}ms every ${prefs.sampledVadIntervalMs / 1000}s)" else "off"}", 12))
            addView(text("Continuous mic watch: ${if (prefs.continuousMicWatchEnabled) "on" else "off"}", 12))
            addView(text("Junk filter: ${if (prefs.junkFilterEnabled) "on" else "off"}", 12))
            addView(text(DevicePlacementMonitor.label(prefs), 12))
            addView(text("Minimum raw audio upload: ${prefs.minAudioUploadSeconds}s", 12))
            addView(text("Auto segment rollover: every ${prefs.maxActiveSegmentSeconds}s while speech/noise stays active", 12))
            addView(text("Companion associations: ${CompanionDeviceSupport.associationCount(this@MainActivity)}", 12))
            addView(row(
                button("Screen audio") { requestMediaProjection() },
                button("Stop screen audio") { MediaProjectionSessionService.stop(this@MainActivity) },
            ))
            addView(row(
                button("Toggle idle notice") {
                    prefs.armedStatusNotificationEnabled = !prefs.armedStatusNotificationEnabled
                    if (prefs.armedStatusNotificationEnabled) ArmedStatusNotifier.show(this@MainActivity) else ArmedStatusNotifier.cancel(this@MainActivity)
                    AuditLog(this@MainActivity).record("armed_notification_setting_changed", mapOf("enabled" to prefs.armedStatusNotificationEnabled))
                },
                button("Toggle mic watch") {
                    if (!prefs.micWatchConsentAccepted) {
                        showMicWatchConsentDialog(startAfterAccept = false)
                    } else {
                        prefs.continuousMicWatchEnabled = !prefs.continuousMicWatchEnabled
                        AuditLog(this@MainActivity).record("continuous_mic_watch_changed", mapOf("enabled" to prefs.continuousMicWatchEnabled))
                    }
                },
            ))
            addView(row(
                button("Toggle sampled VAD") {
                    prefs.sampledVadEnabled = !prefs.sampledVadEnabled
                    AuditLog(this@MainActivity).record("sampled_vad_changed", mapOf("enabled" to prefs.sampledVadEnabled))
                },
                button("Toggle junk filter") {
                    prefs.junkFilterEnabled = !prefs.junkFilterEnabled
                    AuditLog(this@MainActivity).record("junk_filter_changed", mapOf("enabled" to prefs.junkFilterEnabled))
                },
            ))
            addView(row(
                button("Desk gate") {
                    prefs.deskOnlyRecordingEnabled = !prefs.deskOnlyRecordingEnabled
                    if (!prefs.deskOnlyRecordingEnabled) prefs.faceDownDeskOnlyEnabled = false
                    DevicePlacementMonitor.start(this@MainActivity)
                    AuditLog(this@MainActivity).record(
                        "placement_gate_changed",
                        mapOf("desk_only" to prefs.deskOnlyRecordingEnabled, "face_down" to prefs.faceDownDeskOnlyEnabled),
                    )
                },
                button("Face-down gate") {
                    prefs.deskOnlyRecordingEnabled = true
                    prefs.faceDownDeskOnlyEnabled = !prefs.faceDownDeskOnlyEnabled
                    DevicePlacementMonitor.start(this@MainActivity)
                    AuditLog(this@MainActivity).record(
                        "placement_gate_changed",
                        mapOf("desk_only" to prefs.deskOnlyRecordingEnabled, "face_down" to prefs.faceDownDeskOnlyEnabled),
                    )
                },
            ))
            addView(row(
                button("Check voice profile") {
                    thread {
                        val result = OmiAuthClient(this@MainActivity).refreshSpeechProfileStatus()
                        runOnUiThread {
                            status.text = "Omi trained voice profile: ${result?.let { if (it) "found" else "not found" } ?: "unknown"}"
                            refreshPreflight()
                            refreshAudit()
                        }
                    }
                },
                button("Max reliability") {
                    prefs.continuousMicWatchEnabled = true
                    prefs.sampledVadEnabled = false
                    prefs.sampledVadIntervalMs = 10_000L
                    prefs.sampledVadWindowMs = 2_000L
                    AuditLog(this@MainActivity).record("capture_profile_set", mapOf("profile" to "max_reliability"))
                },
            ))
            addView(row(
                button("Faster checks") {
                    prefs.sampledVadEnabled = true
                    prefs.sampledVadIntervalMs = 10_000L
                    prefs.sampledVadWindowMs = 2_000L
                    AuditLog(this@MainActivity).record("sampled_vad_profile_changed", mapOf("profile" to "faster"))
                },
                button("Battery checks") {
                    prefs.sampledVadEnabled = true
                    prefs.sampledVadIntervalMs = 30_000L
                    prefs.sampledVadWindowMs = 1_000L
                    AuditLog(this@MainActivity).record("sampled_vad_profile_changed", mapOf("profile" to "battery"))
                },
            ))
            addView(row(
                button("Segments 30s") {
                    prefs.maxActiveSegmentSeconds = 30
                    AuditLog(this@MainActivity).record("segment_rollover_changed", mapOf("seconds" to prefs.maxActiveSegmentSeconds))
                },
                button("Segments 60s") {
                    prefs.maxActiveSegmentSeconds = 60
                    AuditLog(this@MainActivity).record("segment_rollover_changed", mapOf("seconds" to prefs.maxActiveSegmentSeconds))
                },
                button("Segments 2m") {
                    prefs.maxActiveSegmentSeconds = 120
                    AuditLog(this@MainActivity).record("segment_rollover_changed", mapOf("seconds" to prefs.maxActiveSegmentSeconds))
                },
            ))
            addView(row(
                button("Min audio 4s") {
                    prefs.minAudioUploadSeconds = 4
                    AuditLog(this@MainActivity).record("min_audio_upload_changed", mapOf("seconds" to prefs.minAudioUploadSeconds))
                },
                button("Min audio 8s") {
                    prefs.minAudioUploadSeconds = 8
                    AuditLog(this@MainActivity).record("min_audio_upload_changed", mapOf("seconds" to prefs.minAudioUploadSeconds))
                },
                button("Min audio 12s") {
                    prefs.minAudioUploadSeconds = 12
                    AuditLog(this@MainActivity).record("min_audio_upload_changed", mapOf("seconds" to prefs.minAudioUploadSeconds))
                },
            ))
            addView(button("Pair companion device") { CompanionDeviceSupport.requestAssociation(this@MainActivity) })
            addView(text("Storage", 16, bold = true))
            addView(row(
                button("Delete synced") { deleteSpool("synced") },
                button("Delete pending") { deleteSpool("pending") },
                button("Delete all") { deleteSpool(null) },
            ))
            addView(text("Diagnostics", 16, bold = true))
            addView(row(
                button("Refresh") {
                    refreshPreflight()
                    refreshDiagnostics()
                    refreshAudit()
                    diagnosticText.text = DiagnosticsStore(this@MainActivity).read()
                },
                button("Share") { shareDiagnostics() },
            ))
            addView(diagnosticText)
        }
        AlertDialog.Builder(this)
            .setTitle("Advanced settings")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Done") { _, _ ->
                refreshPreflight()
                refreshAudit()
            }
            .show()
    }

    private fun refreshAudit() {
        runCatching {
            if (::audit.isInitialized) audit.text = AuditLog(this).tail(8).joinToString("\n")
        }.onFailure { recordUiRefreshFailure("audit", it) }
    }

    private fun refreshStorage() {
        runCatching {
            if (::storage.isInitialized) {
                val currentSession = CaptureSessionStore(this).current()
                val spool = CaptureSpoolStore(this).stats()
                val fallback = FallbackSegmentQueue(this).stats()
                storage.text = buildString {
                    appendLine("Sync: ${prefs.lastSyncLabel}")
                    appendLine("Omi trace: ${prefs.lastOmiSyncTrace}")
                    appendLine(AudioSystemSignals.label(this@MainActivity))
                    appendLine(
                        "Audio spool: ${spool["pending_count"]} pending, ${spool["synced_count"]} synced, " +
                            "${spool["filtered_short_count"]} filtered short, " +
                            "${formatBytes((spool["bytes"] as? Number)?.toLong() ?: 0L)}, oldest pending ${spool["oldest_pending_seconds"]}s",
                    )
                    appendLine("Fallback text: ${fallback["pending_count"]} pending, sources=${fallback["sources"]}")
                    appendLine("Session: ${currentSession?.optString("status", "idle") ?: "idle"} ${currentSession?.optString("reason", "") ?: ""}")
                    appendLine("Foreground: ${ContextSignals.foregroundPackage.orEmpty().ifBlank { "unknown" }}")
                }.trim()
            }
            if (::signal.isInitialized) signal.text = AudioSignalStore.label()
            refreshActivityChart()
        }.onFailure { recordUiRefreshFailure("storage", it) }
    }

    private fun refreshActivityChart() {
        runCatching {
            if (!::chartRow.isInitialized) return
            val buckets = CaptureActivityStore(this).buckets(15)
            val maxCount = buckets.maxOfOrNull { it.pending + it.synced }?.coerceAtLeast(1) ?: 1
            chartRow.removeAllViews()
            buckets.forEach { bucket ->
                val column = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM
                    setPadding(2, 0, 2, 0)
                }
                val pending = bucket.pending
                val synced = bucket.synced
                val syncedHeight = dp(44 * synced / maxCount).coerceAtLeast(if (synced > 0) dp(4) else 0)
                val pendingHeight = dp(44 * pending / maxCount).coerceAtLeast(if (pending > 0) dp(4) else 0)
                val spacerHeight = (dp(48) - syncedHeight - pendingHeight).coerceAtLeast(0)
                column.addView(View(this@MainActivity), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, spacerHeight))
                if (syncedHeight > 0) column.addView(View(this@MainActivity).apply { setBackgroundColor(0xff2ecc71.toInt()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, syncedHeight))
                if (pendingHeight > 0) column.addView(View(this@MainActivity).apply { setBackgroundColor(0xff666666.toInt()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, pendingHeight))
                if (pendingHeight == 0 && syncedHeight == 0) column.addView(View(this@MainActivity).apply { setBackgroundColor(0xff202026.toInt()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))
                chartRow.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
        }.onFailure { recordUiRefreshFailure("activity_chart", it) }
    }

    private fun handleSetupLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "omi-ambient-companion" || uri.host != "setup") return
        val plugin = uri.getQueryParameter("plugin_base_url").orEmpty()
        val user = uri.getQueryParameter("omi_user_id").orEmpty()
        if (plugin.isNotBlank()) prefs.pluginBaseUrl = plugin
        if (user.isNotBlank()) prefs.omiUserId = user
        AuditLog(this).record("setup_link_opened", mapOf("plugin_configured" to plugin.isNotBlank(), "user_configured" to user.isNotBlank()))
        refreshPreflight()
        window.decorView.post { showSetupDialog() }
    }

    private fun refreshPreflight() {
        if (!::preflight.isInitialized) return
        runCatching {
            val secure = SecureStore(this)
            val requiredChecks = listOf(
            "Omi user id" to prefs.omiUserId.isNotBlank(),
            "Omi auth token" to OmiAuthClient(this).isSignedIn(),
            "Mic watch consent" to prefs.micWatchConsentAccepted,
            "Microphone permission" to hasPermission(Manifest.permission.RECORD_AUDIO),
            "Notifications permission" to (Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)),
            "Bluetooth route permission" to (Build.VERSION.SDK_INT < 31 || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)),
            "Accessibility enabled" to isAccessibilityEnabled(),
            "Notification listener enabled" to isNotificationListenerEnabled(),
            "Battery unrestricted/exempt" to isBatteryExempt(),
            "Companion device support" to CompanionDeviceSupport.hasFeature(this),
            "Maintenance job scheduled" to AmbientMaintenanceScheduler.isScheduled(this),
        )
            val optionalChecks = listOf(
            "Companion association" to (CompanionDeviceSupport.associationCount(this) > 0),
            "Plugin URL" to prefs.pluginBaseUrl.isNotBlank(),
            "Plugin device token" to secure.getSecret("device_token").isNotBlank(),
            "Plugin pinned key" to (prefs.controllerKeyId.isNotBlank() && prefs.controllerPublicKey.isNotBlank()),
        )
            preflight.text = buildString {
                appendLine("Direct Omi sync")
                append(requiredChecks.joinToString("\n") { (label, ok) -> "${if (ok) "OK" else "MISSING"} - $label" })
                appendLine()
                appendLine("Mic mode: ${if (prefs.continuousMicWatchEnabled) "continuous watch can auto-start from context" else "manual only; context triggers stay armed/idle"}")
                appendLine("Sampled VAD: ${if (prefs.sampledVadEnabled) "${prefs.sampledVadWindowMs}ms checks every ${prefs.sampledVadIntervalMs / 1000}s" else "off"}")
                appendLine("Auto segment rollover: ${prefs.maxActiveSegmentSeconds}s")
                appendLine("Minimum raw audio upload: ${prefs.minAudioUploadSeconds}s")
                appendLine("Junk filter: ${if (prefs.junkFilterEnabled) "on" else "off"}")
                appendLine(DevicePlacementMonitor.label(prefs))
                appendLine("Omi trained voice profile: ${speechProfileLabel()}")
                appendLine("Sync: ${prefs.lastSyncLabel}")
                appendLine("Omi trace: ${prefs.lastOmiSyncTrace}")
                appendLine(AudioSignalStore.label())
                appendLine(AudioSystemSignals.label(this@MainActivity))
                appendLine("Maintenance: ${if (prefs.lastMaintenanceAtMs > 0) "${(System.currentTimeMillis() - prefs.lastMaintenanceAtMs) / 1000}s ago" else "waiting"}")
                appendLine("Companion mode: ${CompanionDeviceSupport.associationCount(this@MainActivity)} associated device(s)")
                appendLine()
                appendLine("Optional controller plugin")
                append(optionalChecks.joinToString("\n") { (label, ok) -> "${if (ok) "OK" else "SKIPPED"} - $label" })
            }
            if (::omiAuthStatus.isInitialized) omiAuthStatus.text = omiAuthLabel()
        }.onFailure { recordUiRefreshFailure("preflight", it) }
    }

    private fun omiAuthLabel(): String {
        val uid = prefs.omiAuthUid
        val email = prefs.omiAuthEmail
        return if (uid.isBlank()) {
            "Omi auth: not signed in. Audio will sync to controller only."
        } else {
            "Omi auth: signed in as ${email.ifBlank { uid }}. Audio sync will use Omi first."
        }
    }

    private fun refreshDiagnostics() {
        runCatching {
            if (::diagnostics.isInitialized) {
                DiagnosticsStore(this).write("ui_refresh")
                diagnostics.text = DiagnosticsStore(this).read()
            }
        }.onFailure { recordUiRefreshFailure("diagnostics", it) }
    }

    private fun speechProfileLabel(): String {
        if (!prefs.preferOmiUserVoice) return "not prioritized"
        if (prefs.omiSpeechProfileCheckedAtMs <= 0) return "unknown; tap Advanced > Check voice profile"
        val ageMinutes = ((System.currentTimeMillis() - prefs.omiSpeechProfileCheckedAtMs) / 60_000L).coerceAtLeast(0)
        return "${if (prefs.omiHasSpeechProfile) "available" else "missing"} (${ageMinutes}m ago)"
    }

    private fun refreshLiveUi(reason: String) {
        runCatching {
            if (::status.isInitialized) status.text = "Status: ${AmbientForegroundMicService.lastHealthState().name}"
            refreshPreflight()
            refreshStorage()
            refreshAudit()
            if (reason != "live") refreshDiagnostics()
        }.onFailure { recordUiRefreshFailure("live:$reason", it) }
    }

    private fun recordUiRefreshFailure(area: String, error: Throwable) {
        runCatching {
            AuditLog(this).record(
                "ui_refresh_failed",
                mapOf("area" to area, "error" to error.javaClass.simpleName, "message" to (error.message ?: "").take(160)),
            )
        }
    }

    private fun shareDiagnostics() {
        DiagnosticsStore(this).write("share")
        val text = DiagnosticsStore(this).read()
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Omi Ambient diagnostics", text))
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Omi Ambient Companion diagnostics")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, "Share diagnostics"))
    }

    private fun hasPermission(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(this, AccessibilityContextService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) || it.contains(packageName, ignoreCase = true) }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { it.contains(packageName, ignoreCase = true) }
    }

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun deleteSpool(status: String?) {
        CaptureSpoolStore(this).deleteByStatus(status)
        AuditLog(this).record("spool_deleted", mapOf("status" to (status ?: "all")))
        refreshAudit()
    }

    private fun prettyHealth(json: JSONObject): String {
        return "Status: ${json.optString("state")} (${json.optString("reason")})\nForeground: ${json.optString("foreground_app")}"
    }

    private fun text(value: String, size: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(0xffffffff.toInt())
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 10)
        }
    }

    private fun field(hint: String, value: String): EditText {
        return EditText(this).apply {
            setHint(hint)
            setText(value)
            textSize = 14f
            setTextColor(0xffffffff.toInt())
            setHintTextColor(0xff888888.toInt())
            setSingleLine(true)
        }
    }

    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(0xffffffff.toInt())
            setBackgroundColor(0xff5f5f64.toInt())
            setOnClickListener { action() }
        }
    }

    private fun row(vararg views: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }
    }

    companion object {
        private const val MEDIA_PROJECTION_REQUEST = 7304
        private const val LIVE_REFRESH_MS = 1_000L
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L * 1024L) return "${bytes / 1024L} KB"
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun setupChecklist(): String {
        val items = listOf(
            "Omi sign-in" to OmiAuthClient(this).isSignedIn(),
            "Notifications" to (Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)),
            "Mic permission" to hasPermission(Manifest.permission.RECORD_AUDIO),
            "Mic watch consent" to prefs.micWatchConsentAccepted,
            "Accessibility context" to isAccessibilityEnabled(),
            "Notification listener" to isNotificationListenerEnabled(),
            "Battery unrestricted" to isBatteryExempt(),
            "Companion device" to (CompanionDeviceSupport.associationCount(this) > 0),
        )
        return buildString {
            appendLine("Setup checklist")
            items.forEach { (label, ok) -> appendLine("${if (ok) "OK" else "TODO"} - $label") }
        }.trim()
    }

    private fun showMicWatchConsentDialog(startAfterAccept: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("Microphone watch consent")
            .setMessage(
                "When microphone capture is running, Android will show the normal microphone privacy indicator and a persistent Omi Ambient notification. " +
                    "This can make it harder to notice if another app is also using the microphone, because Android may show one shared mic indicator. " +
                    "By default, Omi Ambient stays armed with a non-mic notification and uses accessibility, notification, caption, and route context without opening the mic. " +
                    "Accept only if you want Omi Ambient to start microphone watch when you press Start, and optionally from context triggers if continuous mic watch is enabled."
            )
            .setPositiveButton("Accept") { _, _ ->
                prefs.micWatchConsentAccepted = true
                prefs.continuousMicWatchEnabled = true
                prefs.sampledVadEnabled = false
                AuditLog(this).record("mic_watch_consent_accepted")
                AuditLog(this).record("capture_profile_set", mapOf("profile" to "max_reliability"))
                if (startAfterAccept) AmbientForegroundMicService.start(this, "manual_start_after_consent")
                refreshPreflight()
            }
            .setNegativeButton("Cancel") { _, _ ->
                AuditLog(this).record("mic_watch_consent_declined")
                refreshPreflight()
            }
            .show()
    }
}
