package com.viperdam.kidsprayer.service

import android.content.Context
import android.content.Intent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.ads.MobileAds
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.viperdam.kidsprayer.PrayerApp
import com.viperdam.kidsprayer.state.LockScreenStateManager
import com.viperdam.kidsprayer.security.DeviceAdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class StateResetWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "StateResetWorker"
        const val STATE_RESET_ACTION = "com.viperdam.kidsprayer.STATE_RESET"
        private const val TARGET_HOUR = 11
        private const val TARGET_MINUTE = 30

        fun calculateInitialDelay(context: Context): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, TARGET_HOUR)
                set(Calendar.MINUTE, TARGET_MINUTE)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (target.before(now)) {
                target.add(Calendar.DAY_OF_MONTH, 1)
            }

            return target.timeInMillis - now.timeInMillis
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting daily state reset")
            
            // Verify permissions first
            if (!verifyAndEnsurePermissions()) {
                Log.e(TAG, "Failed to verify permissions, will retry later")
                return@withContext Result.retry()
            }
            
            // Clear app data while preserving permissions and settings
            clearAppData()
            
            // Reset lock screen state
            resetLockScreenState()
            
            // Reset ad state
            resetAdState()
            
            // Broadcast reset completion
            context.sendBroadcast(Intent(STATE_RESET_ACTION))
            
            Log.d(TAG, "Daily state reset completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during daily state reset", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            Result.retry()
        }
    }

    private fun verifyAndEnsurePermissions(): Boolean {
        try {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
            
            // Check if we have device admin permission
            if (!devicePolicyManager.isAdminActive(componentName)) {
                Log.e(TAG, "Device admin permission is not active")
                return false
            }

            // Verify lock task permission
            if (!devicePolicyManager.isLockTaskPermitted(context.packageName)) {
                Log.e(TAG, "Lock task permission is not granted")
                return false
            }

            Log.d(TAG, "All permissions verified successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying permissions", e)
            return false
        }
    }

    private suspend fun clearAppData() {
        withContext(Dispatchers.IO) {
            try {
                // Get all shared preferences files
                val prefsDir = context.filesDir.parent?.let { File(it, "shared_prefs") }
                prefsDir?.listFiles()?.forEach { file: File ->
                    // Skip permission-related and settings preferences
                    if (!file.name.contains("device_admin") && 
                        !file.name.contains("permissions") && 
                        !file.name.contains("admin_policies") &&
                        !file.name.contains("prayer_settings") &&
                        !file.name.contains("user_preferences")) {
                        
                        // Clear non-protected preferences
                        val prefName = file.name.replace(".xml", "")
                        context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .apply()
                    }
                }
                
                // Clear app cache except for settings and permissions
                context.cacheDir.listFiles()?.forEach { file: File ->
                    if (file.name != "permissions_cache" && 
                        !file.name.contains("settings_cache") &&
                        !file.name.contains("preferences_cache")) {
                        file.deleteRecursively()
                    }
                }
                
                Log.d(TAG, "App data cleared successfully while preserving permissions and settings")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing app data", e)
                throw e
            }
        }
    }

    private suspend fun resetLockScreenState() {
        withContext(Dispatchers.IO) {
            try {
                val lockScreenManager = LockScreenStateManager.getInstance(context)
                lockScreenManager.reset(preservePermissions = true, preserveSettings = true)
                Log.d(TAG, "Lock screen state reset successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting lock screen state", e)
                throw e
            }
        }
    }

    private suspend fun resetAdState() {
        withContext(Dispatchers.Main) {
            try {
                // Reinitialize ads
                MobileAds.initialize(context) { initializationStatus ->
                    val statusMap = initializationStatus.adapterStatusMap
                    for ((adapter, status) in statusMap) {
                        Log.d(TAG, "Ad Adapter: $adapter Status: ${status.initializationState}")
                    }
                }
                
                // Force reload ads in PrayerApp
                (context.applicationContext as? PrayerApp)?.reloadAds()
                
                Log.d(TAG, "Ad state reset successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting ad state", e)
                throw e
            }
        }
    }
}
