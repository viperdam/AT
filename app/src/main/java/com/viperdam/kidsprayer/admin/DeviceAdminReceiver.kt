package com.viperdam.kidsprayer.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

class PrayerDeviceAdmin : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "PrayerDeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        try {
            Log.d(TAG, "Device admin enabled")
            context.getSharedPreferences("device_admin_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_admin_active", true)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onEnabled: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        try {
            Log.d(TAG, "Device admin disabled")
            context.getSharedPreferences("device_admin_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_admin_active", false)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDisabled: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling device admin will prevent the app from properly tracking prayers. Are you sure?"
    }
}
