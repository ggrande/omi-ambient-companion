package com.omi.ambientcompanion

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlin.concurrent.thread

object SyncWorker {
    private var running = false

    @Synchronized
    fun drainAsync(context: Context, force: Boolean = false) {
        if (running) return
        running = true
        thread(name = "ambient-sync") {
            try {
                drain(context.applicationContext, force)
            } finally {
                running = false
            }
        }
    }

    fun drain(context: Context, force: Boolean = false) {
        val prefs = AppPrefs(context)
        if (!networkAvailable(context)) {
            prefs.lastSyncLabel = "Network unavailable; buffering locally"
            return
        }
        val audit = AuditLog(context)
        val now = System.currentTimeMillis()
        if (force) {
            prefs.nextSyncAfterMs = 0
            prefs.syncFailureCount = 0
            audit.record("sync_backoff_cleared", mapOf("reason" to "manual_or_segment_flush"))
        } else if (prefs.nextSyncAfterMs > now) {
            prefs.lastSyncLabel = "Backoff active; retry in ${(prefs.nextSyncAfterMs - now) / 1000}s"
            audit.record("sync_backoff_active", mapOf("retry_after_ms" to (prefs.nextSyncAfterMs - now)))
            return
        }
        val client = PluginClient(context)
        val omiAuth = OmiAuthClient(context)
        val fallbackQueue = FallbackSegmentQueue(context)
        val spool = CaptureSpoolStore(context)
        var attempted = false
        var succeeded = false
        var terminalLabel: String? = null
        val pendingSegments = filterPendingFallbackSegments(fallbackQueue, prefs, audit)
        val pluginReady = prefs.fallbackSegmentsUrl.isNotBlank() && SecureStore(context).getSecret("device_token").isNotBlank()
        if (pendingSegments.isNotEmpty() && pluginReady) {
            attempted = true
            if (client.uploadFallbackSegments(pendingSegments)) {
                fallbackQueue.clearUploaded(pendingSegments.map { it.id }.toSet())
                audit.record("fallback_segments_uploaded", mapOf("count" to pendingSegments.size))
                succeeded = true
            }
        } else if (pendingSegments.any { it.shouldUploadDirectlyToOmi() }) {
            val directOmiFallbackSegments = pendingSegments
                .filter { it.shouldUploadDirectlyToOmi() }
                .sortedBy { it.start }
                .take(500)
            attempted = true
            if (omiAuth.uploadFallbackSegments(directOmiFallbackSegments)) {
                val uploadedIds = directOmiFallbackSegments.map { it.id }.toSet()
                fallbackQueue.clearUploaded(uploadedIds)
                markSpoolCoveredByFallback(context, spool, directOmiFallbackSegments, prefs)
                succeeded = true
            }
        } else if (pendingSegments.isNotEmpty()) {
            audit.record("fallback_segments_waiting_controller", mapOf("count" to pendingSegments.size))
        }
        LocalSttWorker(context).drainSpoolForLocalTranscripts()
        val uploaded = mutableListOf<String>()
        val uploadedSessions = mutableListOf<String>()
        val pendingSpool = spool.list("pending")
        if (prefs.allowAudioUpload) {
            val uploadableSpool = filterUploadableAudioSessions(context, spool, pendingSpool, prefs, audit)
            val filteredAudioCount = pendingSpool.size - uploadableSpool.size
            if (uploadableSpool.isNotEmpty()) {
                prefs.lastSyncLabel = "Uploading ${uploadableSpool.size} audio segment(s)"
            } else if (filteredAudioCount > 0) {
                terminalLabel = "Filtered $filteredAudioCount short audio session(s); nothing long enough to upload"
            }
            uploadableSpool.forEach { meta ->
                attempted = true
                val omiResult = omiAuth.uploadAudioFile(meta, spool.readPlainChunks(meta))
                val uploadedToOmi = omiResult.success && omiResult.hasServerConversationSignal()
                if (omiResult.success && !omiResult.hasServerConversationSignal()) {
                    terminalLabel = "Omi raw upload found no server speech; waiting for local/caption fallback"
                    prefs.lastSyncLabel = terminalLabel
                    audit.record(
                        "omi_audio_sync_no_server_segments",
                        mapOf("session_id" to meta.sessionId, "job_id" to omiResult.jobId, "status" to omiResult.status),
                    )
                    omiAuth.traceSyncResult(omiResult, "no_segments")
                    LocalSttWorker(context).drainSpoolForLocalTranscripts()
                    succeeded = true
                    return@forEach
                }
                val uploadedToController = if (uploadedToOmi) false else client.uploadAudioFile(meta, spool.readPlainChunks(meta))
                if (uploadedToOmi || uploadedToController) {
                    uploaded.add(meta.filePath)
                    uploadedSessions.add(meta.sessionId)
                    succeeded = true
                    if (uploadedToOmi) {
                        prefs.lastSyncLabel = "Omi upload accepted; tracing job result"
                        omiAuth.traceSyncResult(omiResult, "immediate")
                        Thread.sleep(TRACE_AFTER_UPLOAD_DELAY_MS)
                        omiAuth.traceSyncResult(omiResult, "delayed")
                    }
                    audit.record(
                        "spool_audio_uploaded",
                        mapOf(
                            "session_id" to meta.sessionId,
                            "bytes" to meta.bytes,
                            "destination" to if (uploadedToOmi) "omi_sync_local_files" else "controller_audio_spool",
                        ),
                    )
                }
            }
        } else {
            audit.record("spool_audio_upload_skipped", mapOf("reason" to "policy_or_local_setting_disabled"))
        }
        if (uploaded.isNotEmpty()) {
            spool.markStatus(uploaded, "synced")
            CaptureActivityStore(context).markSynced(uploadedSessions)
            if (prefs.deleteSyncedAudio) spool.deleteByStatus("synced")
        }
        if (attempted && succeeded) {
            prefs.syncFailureCount = 0
            prefs.nextSyncAfterMs = 0
            prefs.lastSyncLabel = terminalLabel ?: "Last sync succeeded"
        } else if (attempted) {
            prefs.lastSyncLabel = "Last sync failed; retry scheduled"
            scheduleBackoff(prefs, audit)
        } else {
            prefs.lastSyncLabel = terminalLabel ?: "Nothing pending to sync"
        }
    }

    private fun scheduleBackoff(prefs: AppPrefs, audit: AuditLog) {
        val failures = (prefs.syncFailureCount + 1).coerceAtMost(8)
        val delayMs = (30_000L * (1 shl (failures - 1))).coerceAtMost(30 * 60_000L)
        prefs.syncFailureCount = failures
        prefs.nextSyncAfterMs = System.currentTimeMillis() + delayMs
        audit.record("sync_backoff_scheduled", mapOf("failures" to failures, "delay_ms" to delayMs))
    }

    private fun networkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private const val TRACE_AFTER_UPLOAD_DELAY_MS = 20_000L

    private fun filterPendingFallbackSegments(
        queue: FallbackSegmentQueue,
        prefs: AppPrefs,
        audit: AuditLog,
    ): List<FallbackSegment> {
        val pending = queue.pending()
        if (!prefs.junkFilterEnabled) return pending
        val decisions = pending.map { it to ConversationQualityFilter.evaluate(it) }
        val rejected = decisions.filter { !it.second.allow }
        if (rejected.isNotEmpty()) {
            queue.clearUploaded(rejected.map { it.first.id }.toSet())
            val reasons = rejected.groupingBy { it.second.reason }.eachCount()
            audit.record(
                "fallback_segments_junk_removed",
                mapOf("count" to rejected.size, "reasons" to reasons.entries.joinToString(",") { "${it.key}:${it.value}" }),
            )
        }
        return decisions.filter { it.second.allow }.map { it.first }
    }

    private fun filterUploadableAudioSessions(
        context: Context,
        spool: CaptureSpoolStore,
        pending: List<SpoolMetadata>,
        prefs: AppPrefs,
        audit: AuditLog,
    ): List<SpoolMetadata> {
        if (!prefs.junkFilterEnabled || pending.isEmpty()) return pending
        val minSeconds = prefs.minAudioUploadSeconds
        val rejected = pending.filter { it.durationEstimateSeconds < minSeconds.toDouble() }
        if (rejected.isEmpty()) return pending

        spool.markStatus(rejected.map { it.filePath }, "filtered_short")
        CaptureActivityStore(context).markFiltered(rejected.map { it.sessionId })
        audit.record(
            "spool_audio_filtered_short",
            mapOf(
                "count" to rejected.size,
                "min_seconds" to minSeconds,
                "max_rejected_duration" to (rejected.maxOfOrNull { it.durationEstimateSeconds } ?: 0.0),
            ),
        )
        prefs.lastOmiSyncTrace = "Filtered short audio: ${rejected.size} below ${minSeconds}s"
        return pending - rejected.toSet()
    }

    private fun FallbackSegment.shouldUploadDirectlyToOmi(): Boolean {
        return text.isNotBlank() && (!rawAudioAvailable || source == FallbackSource.LOCAL_STT)
    }

    private fun markSpoolCoveredByFallback(
        context: Context,
        spool: CaptureSpoolStore,
        segments: List<FallbackSegment>,
        prefs: AppPrefs,
    ) {
        val matched = spool.list("pending").filter { meta ->
            segments.any { segment -> overlaps(meta, segment) }
        }
        if (matched.isEmpty()) return
        val paths = matched.map { it.filePath }
        spool.markStatus(paths, "synced")
        CaptureActivityStore(context).markSynced(matched.map { it.sessionId })
        AuditLog(context).record("spool_audio_synced_by_fallback", mapOf("count" to matched.size))
        if (prefs.deleteSyncedAudio) spool.deleteByStatus("synced")
    }

    private fun overlaps(meta: SpoolMetadata, segment: FallbackSegment): Boolean {
        val metaStart = meta.startedAt.toEpochMilli() - 5000L
        val metaEnd = meta.startedAt.toEpochMilli() + (meta.durationEstimateSeconds * 1000).toLong() + 5000L
        return segment.end.toEpochMilli() >= metaStart && segment.start.toEpochMilli() <= metaEnd
    }
}
