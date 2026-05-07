package com.omi.ambientcompanion

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

class AmbientMaintenanceJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        thread(name = "ambient-maintenance") {
            val prefs = AppPrefs(applicationContext)
            val audit = AuditLog(applicationContext)
            prefs.lastMaintenanceAtMs = System.currentTimeMillis()
            audit.record("maintenance_started", mapOf("reason" to params?.extras?.getString("reason")))
            runCatching {
                DiagnosticsStore(applicationContext).write("maintenance")
                LocalSttWorker(applicationContext).drainSpoolForLocalTranscripts()
                SyncWorker.drain(applicationContext)
            }.onFailure {
                audit.record("maintenance_failed", mapOf("error" to it.javaClass.simpleName))
            }
            audit.record("maintenance_finished", mapOf("sync" to prefs.lastSyncLabel))
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        AuditLog(applicationContext).record("maintenance_stopped")
        return true
    }
}
