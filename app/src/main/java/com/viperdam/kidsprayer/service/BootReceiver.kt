package com.viperdam.kidsprayer.service

import android.content.BroadcastReceiver
import android.content.Context
import androidx.work.workDataOf
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import android.app.admin.DevicePolicyManager
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.viperdam.kidsprayer.security.DeviceAdminReceiver
import com.viperdam.kidsprayer.service.LockScreenMonitorService
import com.viperdam.kidsprayer.utils.PermissionHelper
import com.viperdam.kidsprayer.service.PrayerScheduler
import com.viperdam.kidsprayer.service.AdSettingsCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val INITIAL_DELAY_MINUTES = 1L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent?.action == Intent.ACTION_TIME_CHANGED || 
            intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {

            Log.d(TAG, "Received action: ${intent.action}")
            
            // Don't start services during direct boot (before SIM PIN)
            if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                Log.d(TAG, "Device in direct boot, delaying service start")
                return
            }

            // Apply child-directed ad settings immediately at boot
            applyAdContentRatingG(context)
            
            // Schedule periodic checks of child-directed ad settings
            scheduleAdSettingsCheck(context)

            // Start monitoring for adhan-related volume button presses
            registerVolumeButtonMonitoring(context)

            // Start the lock screen service with a delay to ensure system is ready
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(Intent(context, LockScreenMonitorService::class.java))
                    } else {
                        context.startService(Intent(context, LockScreenMonitorService::class.java))
                    }
                    Log.d(TAG, "Started LockScreenMonitorService after boot")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting service after boot: ${e.message}")
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }, TimeUnit.MINUTES.toMillis(INITIAL_DELAY_MINUTES))

            // Handle time change and boot completion
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d(TAG, "Initializing after boot/update")

                    // First ensure device admin is active
                    if (!DeviceAdminReceiver.isAdminActive(context)) {
                        Log.d(TAG, "Device admin not active after boot, requesting privileges")
                        Handler(Looper.getMainLooper()).post {
                            DeviceAdminReceiver.requestAdminPrivileges(context)
                        }
                    } else {
                        // Re-configure lock task settings
                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val componentName = DeviceAdminReceiver.getComponentName(context)
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            try {
                                dpm.setLockTaskPackages(componentName, arrayOf(context.packageName))
                                Log.d(TAG, "Lock task packages reset after boot")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to set lock task packages after boot: ${e.message}")
                                FirebaseCrashlytics.getInstance().recordException(e)
                            }
                        }
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            try {
                                dpm.setLockTaskFeatures(componentName, DeviceAdminReceiver.LOCK_TASK_FEATURES)
                                Log.d(TAG, "Lock task features reset after boot")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to set lock task features after boot: ${e.message}")
                                FirebaseCrashlytics.getInstance().recordException(e)
                            }
                        }
                    }

                    // Reset lock state
                    val prefs = context.getSharedPreferences("KidsPrayerPrefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("KEY_WAS_LOCKED", false)
                        .putInt("pin_attempts", 0)
                        .apply()
                    
                    // Schedule periodic child-directed ad settings checks
                    scheduleAdSettingsCheck(context)
                    
                    // Cancel existing work
                    WorkManager.getInstance(context).cancelUniqueWork("PrayerWork")
                    
                    // Schedule prayer work with fresh state
                    if (PermissionHelper.isPermissionGranted(context, android.Manifest.permission.RECEIVE_BOOT_COMPLETED)) {
                        val scheduler = PrayerScheduler(context)
                        scheduler.schedulePrayerWork()
                        Log.d(TAG, "Prayer scheduling initiated")
                    } else {
                        Log.w(TAG, "RECEIVE_BOOT_COMPLETED permission not granted")
                    }

                    // Schedule service monitoring
                    val initialDelay = if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                        INITIAL_DELAY_MINUTES
                    } else {
                        0L // No delay for other actions
                    }

                    Log.d(TAG, "Scheduling service monitoring with ${initialDelay}m delay")
                    
                    // Schedule one-time work for immediate service check with a bootCompleted flag
                    val monitorRequest = OneTimeWorkRequestBuilder<ServiceMonitorWorker>()
                        .setInitialDelay(initialDelay, TimeUnit.MINUTES)
                        .setInputData(workDataOf("bootCompleted" to (intent.action == Intent.ACTION_BOOT_COMPLETED)))
                        .setBackoffCriteria(
                            BackoffPolicy.LINEAR,
                            WorkRequest.MIN_BACKOFF_MILLIS,
                            TimeUnit.MILLISECONDS)
                        .build()

                    WorkManager.getInstance(context).enqueue(monitorRequest)
                    
                    // Schedule periodic monitoring
                    ServiceMonitorWorker.enqueuePeriodicWork(context)

                    // Deferred service start: LockScreenMonitorService will now be started via ServiceMonitorWorker after ensuring app is in foreground or user interaction.

                    // Check and request device admin if needed
                    if (!DeviceAdminReceiver.isAdminActive(context)) {
                        Log.d(TAG, "Device admin not active, will be requested when app opens")
                    }
                    
                    Log.d(TAG, "Boot initialization completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during resynchronization: ${e.message}")
                    FirebaseCrashlytics.getInstance().recordException(e)
                    
                    // Retry only for boot completion
                    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            onReceive(context, intent)
                        }, 60000) // Retry after 1 minute
                    }
                }
            }
        } else {
            Log.d(TAG, "Received unexpected intent: ${intent?.action}")
        }
    }
    
    /**
     * Apply G-rated content settings at boot time
     */
    private fun applyAdContentRatingG(context: Context) {
        try {
            // Apply G-rated content settings at boot time
            val requestConfiguration = MobileAds.getRequestConfiguration().toBuilder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .build()
                
            MobileAds.setRequestConfiguration(requestConfiguration)
            
            // Mark enforcement in shared preferences for tracking
            context.getSharedPreferences("ad_settings_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("child_directed_enforced", true)
                .putLong("last_enforced_time", System.currentTimeMillis())
                .putBoolean("boot_enforcement_complete", true)
                .apply()
            
            // Send broadcast to notify components
            val enforcementIntent = Intent("com.viperdam.kidsprayer.ENFORCE_CHILD_DIRECTED")
            context.sendBroadcast(enforcementIntent)
            
            Log.d(TAG, "Applied ad settings with MAX_AD_CONTENT_RATING_G at boot")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying ad settings at boot: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            
            // Schedule a retry
            scheduleAdSettingsRetry(context)
        }
    }
    
    /**
     * Schedule a retry in case the initial enforcement failed
     */
    private fun scheduleAdSettingsRetry(context: Context) {
        try {
            // Schedule an immediate check of child-directed ad settings
            val workRequest = OneTimeWorkRequestBuilder<AdSettingsCheckWorker>()
                .setInitialDelay(5, TimeUnit.MINUTES) // Retry after 5 minutes
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
                
            WorkManager.getInstance(context).enqueue(workRequest)
            
            Log.d(TAG, "Scheduled retry for child-directed ad settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling ad settings retry: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
    
    /**
     * Schedule periodic checks of child-directed ad settings
     * to ensure they're always applied
     */
    private fun scheduleAdSettingsCheck(context: Context) {
        try {
            // Schedule a periodic check of child-directed ad settings
            val workRequest = OneTimeWorkRequestBuilder<AdSettingsCheckWorker>()
                .setInitialDelay(30, TimeUnit.MINUTES) // First check after 30 minutes
                .build()
                
            WorkManager.getInstance(context).enqueue(workRequest)
            
            // Also schedule a periodic work
            val periodicWorkRequest = androidx.work.PeriodicWorkRequestBuilder<AdSettingsCheckWorker>(
                2, TimeUnit.HOURS // Check every 2 hours
            ).build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "child_directed_ad_settings_check",
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE, 
                periodicWorkRequest
            )
            
            Log.d(TAG, "Scheduled periodic checks for child-directed ad settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling ad settings checks: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    /**
     * Register the volume button monitoring to ensure adhan can be stopped by volume buttons
     * regardless of which app is in the foreground
     */
    private fun registerVolumeButtonMonitoring(context: Context) {
        try {
            // Check if adhan is enabled globally
            val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
            val isAdhanGloballyEnabled = prefs.getBoolean("enable_adhan", true)
            
            if (isAdhanGloballyEnabled) {
                // Only add a handler for system-wide events if adhan is enabled globally
                Log.d(TAG, "Adhan is enabled, setting up volume button monitoring")
                
                // Check if an adhan might be playing (for recovery after unexpected reboot)
                val pendingIntent = Intent(context, PrayerReceiver::class.java).apply {
                    action = PrayerReceiver.STOP_ADHAN_ACTION
                }
                context.sendBroadcast(pendingIntent)
                
                // Ensure any playing adhans are stopped
                PrayerReceiver.stopAdhan(context, "boot_completed")
            } else {
                Log.d(TAG, "Adhan is globally disabled, skipping volume button monitoring setup")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up volume button monitoring: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
}
