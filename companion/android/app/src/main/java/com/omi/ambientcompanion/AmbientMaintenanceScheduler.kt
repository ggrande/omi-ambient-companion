package com.omi.ambientcompanion

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle

object AmbientMaintenanceScheduler {
    private const val JOB_ID = 711042
    private const val PERIOD_MS = 15 * 60 * 1000L

    fun schedule(context: Context, reason: String = "app") {
        val appContext = context.applicationContext
        val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return
        val audit = AuditLog(appContext)
        val result = runCatching {
            scheduler.schedule(buildJob(appContext, reason, persisted = true))
        }.recoverCatching { error ->
            audit.record("maintenance_persisted_schedule_failed", mapOf("reason" to reason, "error" to error.toString().take(240)))
            scheduler.schedule(buildJob(appContext, reason, persisted = false))
        }.getOrElse { error ->
            audit.record("maintenance_schedule_failed", mapOf("reason" to reason, "error" to error.toString().take(240)))
            JobScheduler.RESULT_FAILURE
        }
        audit.record("maintenance_scheduled", mapOf("reason" to reason, "result" to result))
    }

    fun isScheduled(context: Context): Boolean {
        val scheduler = context.applicationContext.getSystemService(JobScheduler::class.java) ?: return false
        return scheduler.allPendingJobs.any { it.id == JOB_ID }
    }

    private fun buildJob(context: Context, reason: String, persisted: Boolean): JobInfo {
        return JobInfo.Builder(JOB_ID, ComponentName(context, AmbientMaintenanceJobService::class.java))
            .setPersisted(persisted)
            .setPeriodic(PERIOD_MS)
            .setExtras(PersistableBundle().apply { putString("reason", reason) })
            .build()
    }
}
