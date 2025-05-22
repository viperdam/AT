package com.viperdam.kidsprayer.service

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.work.*
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.viperdam.kidsprayer.prayer.PrayerStateChecker
import com.viperdam.kidsprayer.security.DeviceAdminReceiver
import com.viperdam.kidsprayer.ui.lock.LockScreenActivity
import com.viperdam.kidsprayer.utils.PrayerValidator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import kotlin.math.abs
import java.util.concurrent.TimeUnit
import java.util.Date
import javax.inject.Inject

@HiltWorker
class ServiceMonitorWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val powerManager: PowerManager,
    private val usageStatsManager: UsageStatsManager?,
    private val prayerStateChecker: PrayerStateChecker
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ServiceMonitorWorker"
        private const val WORK_NAME = "service_monitor_work"
        private const val INITIAL_DELAY_MS = 15000L
        private const val RETRY_DELAY_MS = 5000L
        private const val MAX_RETRIES = 3
        private const val RECOVERY_DELAY = 2000L
        private const val MAX_RECOVERY_ATTEMPTS = 5
        private const val PIN_COOLDOWN_MS = 30000L
        private const val SERVICE_CHECK_DELAY = 1000L
        private const val SERVICE_CHECK_RETRIES = 3

        fun enqueuePeriodicWork(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresDeviceIdle(false)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<ServiceMonitorWorker>(
                    3, TimeUnit.MINUTES,
                    1, TimeUnit.MINUTES
                )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
                
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("lock_screen_prefs", Context.MODE_PRIVATE)
    }

    private val settingsPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Add initial delay to allow system to stabilize
            delay(INITIAL_DELAY_MS)
            
            if (!isInitialized()) {
                return@withContext Result.failure()
            }

            // Check for prayers and manage lock screen state
            prayerStateChecker.checkAndQueuePrayers()

            // Only check if we're near a prayer time for additional actions
            val now = System.currentTimeMillis()
            val nextPrayerTime = runBlocking { getNextPrayerTime() }
            
            if (nextPrayerTime != null) {
                val timeDiff = abs(nextPrayerTime - now)
                
                if (timeDiff <= TimeUnit.MINUTES.toMillis(10)) {
                    if (!shouldContinueProtection()) {
                        return@withContext Result.success()
                    }

                    // Ensure service is running if needed
                    if (!isServiceRunning()) {
                        restartService()
                    }

                    // Check PIN state after prayer check
                    checkAndUpdatePinState()
                }
            } else {
                // If no next prayer, check if lock screen was active before reboot
                val activePrayer = prefs.getString("active_lock_screen", null)
                if (activePrayer != null) {
                    if (!isServiceRunning()) {
                        restartService()
                    }
                    checkAndUpdatePinState()
                }
            }

            return@withContext Result.success()
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            return@withContext if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun scheduleNextWork() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(false)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<ServiceMonitorWorker>()
                .setConstraints(constraints)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private fun isInitialized(): Boolean {
        return try {
            true // These checks are not needed as they are non-null by construction
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    private suspend fun shouldContinueProtection(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val isEnabled = settingsPrefs.getBoolean("prayer_protection_enabled", true)
                val isAdminActive = DeviceAdminReceiver.isAdminActive(context)
                val canDrawOverlays = Settings.canDrawOverlays(context)
                
                if (!isEnabled) {
                    return@withContext false
                }
                
                if (!isAdminActive) {
                    return@withContext false
                }

                if (!canDrawOverlays) {
                    return@withContext false
                }
                
                return@withContext true
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                return@withContext false
            }
        }
    }

    private suspend fun restartService() {
        withContext(Dispatchers.IO) {
            // If this is not a boot event and the app is not in the foreground, defer service start
            if (!inputData.getBoolean("bootCompleted", false) && !isAppInForeground()) {
                Log.d(TAG, "App not in foreground and not boot event; deferring service start.")
                return@withContext
            }
            try {
                var attempts = 0
                while (attempts < MAX_RECOVERY_ATTEMPTS && !isServiceRunning()) {
                    try {
                        val serviceIntent = Intent(context, LockService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }

                    delay(RECOVERY_DELAY)
                    attempts++
                }

                if (!isServiceRunning()) {
                    reportServiceFailure()
                }
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        return try {
            // Modern way to check if our service is running
            val intent = Intent(context, LockService::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            } else {
                @Suppress("DEPRECATION")
                PendingIntent.FLAG_NO_CREATE
            }
            
            val pendingIntent = PendingIntent.getService(
                context,
                0,
                intent,
                flags
            )
            pendingIntent != null
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    private suspend fun checkAndUpdatePinState() {
        withContext(Dispatchers.IO) {
            try {
                val pinVerified = prefs.getBoolean("pin_verified", false)
                val pinAttempts = prefs.getInt("pin_attempts", 0)
                val lastAttempt = prefs.getLong("last_pin_attempt", 0)
                
                if (System.currentTimeMillis() - lastAttempt > PIN_COOLDOWN_MS) {
                    prefs.edit().putInt("pin_attempts", 0).apply()
                }
                
                if (!pinVerified && pinAttempts < 3) {
                    // Check if lock screen is enabled for the next prayer
                    val nextPrayerData = runBlocking { getNextPrayerData() }
                    if (nextPrayerData != null) {
                        val (nextPrayerName, nextPrayerTime) = nextPrayerData
                        val isLockEnabled = settingsPrefs.getBoolean("${nextPrayerName.lowercase()}_lock", true)
                        
                        // Only launch the lock screen if the prayer time has arrived
                        val currentTime = System.currentTimeMillis()
                        if (isLockEnabled && currentTime >= nextPrayerTime) {
                            Log.d(TAG, "Current time $currentTime is at or after prayer time $nextPrayerTime for $nextPrayerName, launching lock screen")
                            // launchLockScreen now internally fetches the prayer name
                            launchLockScreen()
                        } else if (isLockEnabled) {
                            Log.d(TAG, "Current time $currentTime is before prayer time $nextPrayerTime for $nextPrayerName, not launching lock screen yet")
                        }
                    }
                }
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun launchLockScreen() {
        try {
            // Get the next prayer data (name and time) before launching lock screen
            val nextPrayerData = runBlocking { getNextPrayerData() }
            if (nextPrayerData == null) {
                Log.w(TAG, "No valid prayer data available, not launching lock screen")
                return
            }
            
            val (nextPrayerName, nextPrayerTime) = nextPrayerData
            val currentTime = System.currentTimeMillis()
            
            // Only proceed if current time is at or after the prayer time
            if (currentTime < nextPrayerTime) {
                Log.d(TAG, "Current time ${Date(currentTime)} is before prayer time ${Date(nextPrayerTime)} for $nextPrayerName, not launching lock screen yet")
                return
            }
            
            // Validate prayer name using PrayerValidator
            if (!PrayerValidator.isValidPrayerName(nextPrayerName)) {
                Log.e(TAG, "Invalid prayer name: '$nextPrayerName', cannot show lockscreen")
                PrayerValidator.markInvalidPrayerData(context, "Invalid prayer name from ServiceMonitorWorker")
                return
            }
            
            val lockIntent = Intent(context, LockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("prayer_name", nextPrayerName)
                putExtra("prayer_time", nextPrayerTime)
            }
            
            // Enhance the intent with PrayerValidator
            PrayerValidator.enhanceLockScreenIntent(lockIntent)
            
            Log.d(TAG, "Launching lock screen for prayer: $nextPrayerName at time: ${Date(nextPrayerTime)}")
            context.startActivity(lockIntent)
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private suspend fun getNextPrayerName(): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Get location and calculate prayer times
                val location = prayerStateChecker.getLocationManager().getLastLocation() ?: return@withContext null
                val prayers = prayerStateChecker.getPrayerTimeCalculator().calculatePrayerTimes(location)

                if (prayers.isEmpty()) {
                    return@withContext null
                }

                val now = System.currentTimeMillis()

                // Find the next prayer that hasn't been completed
                return@withContext prayers.firstOrNull { prayer ->
                    prayer.time > now && !prayerStateChecker.isPrayerComplete(prayer.name)
                }?.name
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                return@withContext null
            }
        }
    }

    private fun reportServiceFailure() {
        try {
            prefs.edit().apply {
                putBoolean("lock_active", false)
                putInt("recovery_attempts", 0)
                apply()
            }
            
            FirebaseCrashlytics.getInstance().log("Service failed to restart after multiple attempts")
            FirebaseCrashlytics.getInstance().recordException(
                Exception("Service restart failure")
            )
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isLockScreenForeground(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && hasUsageStatsPermission()) {
                return checkForegroundWithUsageStats()
            }
            return checkForegroundWithActivityManager()
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            return false
        }
    }

    private fun checkForegroundWithUsageStats(): Boolean {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 // Last second

        val usageEvents = usageStatsManager?.queryEvents(startTime, endTime)
        if (usageEvents != null) {
            val event = UsageEvents.Event()
            var lastForegroundPackage: String? = null
            var lastForegroundClass: String? = null

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val moveToForegroundEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_FOREGROUND
                }

                if (event.eventType == moveToForegroundEvent) {
                    lastForegroundPackage = event.packageName
                    lastForegroundClass = event.className
                }
            }

            return lastForegroundPackage == context.packageName &&
                   lastForegroundClass?.contains("LockScreenActivity") == true
        }
        return false
    }

    private fun checkForegroundWithActivityManager(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return activityManager.appTasks
                .firstOrNull()
                ?.taskInfo
                ?.topActivity
                ?.className
                ?.contains("LockScreenActivity") == true
        } else {
            @Suppress("DEPRECATION")
            return activityManager.getRunningTasks(1)
                .firstOrNull()
                ?.topActivity
                ?.className
                ?.contains("LockScreenActivity") == true
        }
    }

    private suspend fun getNextPrayerTime(): Long? {
        try {
            // Get location and calculate prayer times
            val location = prayerStateChecker.getLocationManager().getLastLocation() ?: return null
            val prayers = prayerStateChecker.getPrayerTimeCalculator().calculatePrayerTimes(location)
            
            if (prayers.isEmpty()) {
                return null
            }

            val now = System.currentTimeMillis()
            
            // Find the next prayer that hasn't been completed
            return prayers.firstOrNull { prayer -> 
                prayer.time > now && !prayerStateChecker.isPrayerComplete(prayer.name)
            }?.time
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            return null
        }
    }

    // Helper function to check if the app is in the foreground
    private fun isAppInForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    // Add a new method to get both prayer name and time
    private suspend fun getNextPrayerData(): Pair<String, Long>? {
        return withContext(Dispatchers.IO) {
            try {
                // Get location and calculate prayer times
                val location = prayerStateChecker.getLocationManager().getLastLocation() ?: return@withContext null
                val prayers = prayerStateChecker.getPrayerTimeCalculator().calculatePrayerTimes(location)

                if (prayers.isEmpty()) {
                    return@withContext null
                }

                val now = System.currentTimeMillis()

                // Find the next prayer that hasn't been completed
                val nextPrayer = prayers.firstOrNull { prayer ->
                    prayer.time > now && !prayerStateChecker.isPrayerComplete(prayer.name)
                }
                
                return@withContext nextPrayer?.let { Pair(it.name, it.time) }
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                return@withContext null
            }
        }
    }
}
