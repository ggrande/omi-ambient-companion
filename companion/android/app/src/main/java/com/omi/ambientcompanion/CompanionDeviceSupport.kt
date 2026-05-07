package com.omi.ambientcompanion

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast

object CompanionDeviceSupport {
    const val REQUEST_ASSOCIATE = 8801

    fun hasFeature(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    }

    fun associationCount(context: Context): Int {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return 0
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.myAssociations.size
            } else {
                @Suppress("DEPRECATION")
                manager.associations.size
            }
        }.getOrDefault(0)
    }

    fun requestAssociation(activity: Activity) {
        val manager = activity.getSystemService(CompanionDeviceManager::class.java)
        if (manager == null || !hasFeature(activity)) {
            AuditLog(activity).record("companion_device_unavailable")
            Toast.makeText(activity, "Companion device setup is not available on this device.", Toast.LENGTH_LONG).show()
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        AuditLog(activity).record("companion_device_association_started")
        if (Build.VERSION.SDK_INT >= 33) {
            manager.associate(
                request,
                activity.mainExecutor,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) {
                        launchChooser(activity, intentSender)
                    }

                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        AuditLog(activity).record(
                            "companion_device_associated",
                            mapOf("association_id" to associationInfo.id),
                        )
                        Toast.makeText(activity, "Companion device associated.", Toast.LENGTH_SHORT).show()
                    }

                    override fun onFailure(error: CharSequence?) {
                        AuditLog(activity).record("companion_device_association_failed", mapOf("error" to error.toString()))
                        Toast.makeText(activity, "Companion association failed: ${error?.toString().orEmpty()}", Toast.LENGTH_LONG).show()
                    }
                },
            )
        } else {
            @Suppress("DEPRECATION")
            manager.associate(
                request,
                object : CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        launchChooser(activity, chooserLauncher)
                    }

                    override fun onFailure(error: CharSequence?) {
                        AuditLog(activity).record("companion_device_association_failed", mapOf("error" to error.toString()))
                        Toast.makeText(activity, "Companion association failed: ${error?.toString().orEmpty()}", Toast.LENGTH_LONG).show()
                    }
                },
                null,
            )
        }
    }

    fun onAssociationResult(context: Context, resultCode: Int) {
        AuditLog(context).record(
            "companion_device_association_result",
            mapOf("result_code" to resultCode, "associations" to associationCount(context)),
        )
    }

    private fun launchChooser(activity: Activity, intentSender: IntentSender) {
        try {
            activity.startIntentSenderForResult(intentSender, REQUEST_ASSOCIATE, null, 0, 0, 0)
        } catch (e: IntentSender.SendIntentException) {
            AuditLog(activity).record("companion_device_association_failed", mapOf("error" to e.javaClass.simpleName))
        }
    }
}
