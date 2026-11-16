package com.openguard.core.security

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.openguard.OpenGuardApp
import com.openguard.R
import com.openguard.core.location.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Device admin receiver. Used ONLY for lockNow() (Lock + Lost Mode
 * commands). No password policy: password control has been dead since
 * Android 9-11 and is deliberately not requested.
 */
class OpenGuardDeviceAdmin : DeviceAdminReceiver() {
    override fun onDisabled(context: Context, intent: Intent) {
        // Admin was revoked: record it as a security event and try to
        // upload it right away. goAsync keeps the receiver alive until
        // the (suspend) insert finishes.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as OpenGuardApp
                SecurityEvents.record(app.database, "admin_disabled", confirmed = true)
                UploadWorker.enqueueOneTime(context)
            } finally {
                pending.finish()
            }
        }
    }
}

object DeviceAdminManager {

    fun componentName(context: Context): ComponentName =
        ComponentName(context, OpenGuardDeviceAdmin::class.java)

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(componentName(context))
    }

    /** Opens the system device-admin activation screen with an explanation. */
    fun requestAdmin(activity: Activity) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName(activity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                activity.getString(R.string.device_admin_explanation),
            )
        }
        activity.startActivity(intent)
    }

    /** Locks the phone now. False when admin is not active (or on error). */
    fun lockNow(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isAdminActive(componentName(context))) return false
            dpm.lockNow()
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
