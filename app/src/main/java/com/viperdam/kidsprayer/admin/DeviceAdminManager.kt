package com.viperdam.kidsprayer.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceAdminManager @Inject constructor(private val context: Context) {
    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, PrayerDeviceAdmin::class.java)

    companion object {
        private const val TAG = "DeviceAdminManager"
    }

    fun isAdminActive(): Boolean {
        return try {
            devicePolicyManager.isAdminActive(adminComponent)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking admin status: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    fun requestAdminPrivileges(): Boolean {
        return try {
            if (!isAdminActive()) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                        "Device admin access is required to properly track prayers and prevent unauthorized access.")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } else {
                false // Already active
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting admin privileges: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    fun removeAdmin() {
        try {
            if (isAdminActive()) {
                devicePolicyManager.removeActiveAdmin(adminComponent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing admin: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    fun lockScreen() {
        try {
            if (isAdminActive()) {
                devicePolicyManager.lockNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error locking screen: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
}
