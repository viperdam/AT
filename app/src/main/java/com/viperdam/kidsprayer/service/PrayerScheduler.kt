package com.viperdam.kidsprayer.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.viperdam.kidsprayer.model.Prayer
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.state.LockScreenStateManager
import com.viperdam.kidsprayer.prayer.PrayerTimeCalculator
import com.viperdam.kidsprayer.prayer.LocationManager
import com.viperdam.kidsprayer.service.PrayerWorker
import com.viperdam.kidsprayer.time.TimeSourceManager
import com.viperdam.kidsprayer.ui.settings.PrayerSettings
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import com.viperdam.kidsprayer.util.PrayerSettingsManager
import kotlinx.coroutines.flow.update

class PrayerScheduler(private val context: Context) {
    private val scheduledPrayers = mutableSetOf<Int>()
    private val completionManager = PrayerCompletionManager.getInstance(context)
    private val locationManager = LocationManager(context)
    private val prayerTimeCalculator = PrayerTimeCalculator() // Create a single instance
    private val scope = CoroutineScope(Dispatchers.Main)
    private val alarmManager: AlarmManager by lazy { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }
    private var prayerSettings: PrayerSettings? = null
    private var lockScreenListener: LockScreenListener? = null
    
    // New variables to track scheduling state
    private var lastScheduleTime = 0L
    private val SCHEDULE_DEBOUNCE_TIME = 5 * 60 * 1000L // 5 minutes
    private val timeSourceManager = TimeSourceManager.getInstance(context)

    companion object {
        private const val TAG = "PrayerScheduler"
        private const val SCHEDULE_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours (fixed interval)
        private const val LOCK_SCREEN_DELAY = 0L // No delay for lock screen
        private const val DEFAULT_NOTIFICATION_ADVANCE_MINUTES = 15
        private const val TIME_CHANGE_THRESHOLD = 5 * 60 * 1000L // 5 minutes
        private const val MISSED_PRAYER_THRESHOLD = 60 * 60 * 1000L // 1 hour
        private const val PRE_ADHAN_SETUP_TIME = 60 * 1000L // 1 minute before adhan
        const val PRAYER_UNLOCK_ACTION: String = "PRAYER_UNLOCK_ACTION"
        const val PRE_ADHAN_SETUP_ACTION: String = "PRE_ADHAN_SETUP_ACTION"
        var isLockscreenEnabled: Boolean = true
        var lockScreenActive: Boolean = false
        
        // Define request code base constants
        private const val LOCK_SCREEN_REQUEST_CODE_BASE = 1000
        private const val NOTIFICATION_REQUEST_CODE_BASE = 2000
        private const val ADVANCE_NOTIFICATION_REQUEST_CODE_BASE = 3000
        private const val ADHAN_REQUEST_CODE_BASE = 4000
        
        // Track scheduled prayer events to prevent duplicates
        private val scheduledEvents = mutableMapOf<String, Long>()
        
        private fun getScheduleKey(prayerName: String, action: String): String {
            return "${prayerName}_${action}"
        }
        
        private fun isEventScheduled(prayerName: String, action: String, time: Long): Boolean {
            val key = getScheduleKey(prayerName, action)
            val scheduledTime = scheduledEvents[key] ?: 0L
            return scheduledTime == time
        }
        
        private fun markEventScheduled(prayerName: String, action: String, time: Long) {
            val key = getScheduleKey(prayerName, action)
            scheduledEvents[key] = time
        }
        
        private fun clearScheduledEvent(prayerName: String, action: String) {
            val key = getScheduleKey(prayerName, action)
            scheduledEvents.remove(key)
        }

        @Suppress("UNUSED_PARAMETER")
        fun setActiveLockScreen(context: Context, prayerName: String, prayerTime: Long) {
            // No-op implementation (legacy)
        }

        @Suppress("UNUSED_PARAMETER")
        fun clearActiveLockScreen(context: Context) {
            lockScreenActive = false
            
            // Get a reference to our PrayerLockStateManager
            try {
                // Use application context to get services
                val appContext = context.applicationContext
                
                // Get the state manager through DI if app context is available
                val lockStateManager = when {
                    appContext is com.viperdam.kidsprayer.PrayerApp -> {
                        try {
                            // Try to get it through the app's dependency container
                            com.viperdam.kidsprayer.PrayerApp.getInstance().lockStateManager
                        } catch (e: Exception) {
                            // Fall back to direct instantiation
                            com.viperdam.kidsprayer.state.PrayerLockStateManager(
                                appContext, 
                                PrayerCompletionManager.getInstance(context)
                            )
                        }
                    }
                    else -> {
                        // Direct instantiation as fallback
                        com.viperdam.kidsprayer.state.PrayerLockStateManager(
                            appContext, 
                            PrayerCompletionManager.getInstance(context)
                        )
                    }
                }
                
                // Get the active prayer name before clearing
                val (activePrayerName, _) = lockStateManager.getActivePrayer()
                
                // Special handling for Isha prayer
                val isIsha = activePrayerName?.equals("isha", ignoreCase = true) == true
                if (isIsha) {
                    Log.i(TAG, "ISHA PRAYER CLEAR: Clearing active lock screen for Isha prayer specifically")
                }
                
                // Clear the active lock in our state manager
                if (activePrayerName != null) {
                    lockStateManager.clearActiveLock(activePrayerName)
                    Log.d(TAG, "Cleared active lock screen for $activePrayerName")
                    
                    // For Isha, verify that the clear was successful
                    if (isIsha) {
                        // Double check that Isha was actually cleared
                        if (lockStateManager.isLockScreenActiveForPrayer("isha")) {
                            Log.w(TAG, "ISHA PRAYER CLEAR: Lock screen still active after clear attempt! Forcing clear again...")
                            lockStateManager.clearActiveLock("isha")
                            
                            // Triple check - extreme caution for Isha
                            if (lockStateManager.isLockScreenActiveForPrayer("isha")) {
                                Log.e(TAG, "ISHA PRAYER CLEAR: Lock still active after second attempt! Using force method.")
                                // Direct reflection to force clear state (last resort)
                                try {
                                    val field = lockStateManager::class.java.getDeclaredField("_lockState")
                                    field.isAccessible = true
                                    // Use proper suppression for the unchecked cast
                                    @Suppress("UNCHECKED_CAST")
                                    val stateFlow = field.get(lockStateManager) as? kotlinx.coroutines.flow.MutableStateFlow<Any>
                                    // Use null-safe operator and verify non-null value before proceeding
                                    stateFlow?.let { flow ->
                                        val currentState = flow.value
                                        val copyMethod = currentState::class.java.getMethod("copy", Boolean::class.java, Boolean::class.java, Long::class.java)
                                        val newState = copyMethod.invoke(currentState, false, false, System.currentTimeMillis())
                                        if (newState != null) {
                                            // Use update function for atomic update (reverted)
                                            flow.update { newState as Any }
                                            Log.i(TAG, "ISHA PRAYER CLEAR: Used force method to clear Isha lock screen")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error using reflection to force clear Isha", e)
                                }
                            }
                        } else {
                            Log.i(TAG, "ISHA PRAYER CLEAR: Lock screen cleared successfully for Isha")
                        }
                    }
                }
                
                // For backward compatibility
                val lockScreenStateManager = LockScreenStateManager.getInstance(context)
                CoroutineScope(Dispatchers.Main).launch {
                    lockScreenStateManager.reset()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing active lock screen", e)
            }
        }

        fun schedulePrayer(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<PrayerWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "PrayerWork",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }

        /**
         * Schedule activation of volume button service before adhan.
         * This ensures the service is ready to listen for volume presses when adhan starts.
         */
        fun schedulePreAdhanSetup(context: Context, prayerName: String, prayerTime: Long) {
            try {
                // Check if adhan is enabled for this prayer
                val prayerSettings = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
                val isAdhanEnabled = prayerSettings.getBoolean("${prayerName.lowercase()}_adhan", true)
                val isAdhanGloballyEnabled = prayerSettings.getBoolean("enable_adhan", true)
                
                if (!isAdhanEnabled || !isAdhanGloballyEnabled) {
                    Log.d(TAG, "Adhan is disabled for $prayerName or globally, not scheduling pre-adhan setup")
                    return
                }
                
                // Calculate time to activate (1 minute before adhan)
                val setupTime = prayerTime - PRE_ADHAN_SETUP_TIME
                
                // Skip if the setup time is in the past
                if (setupTime <= System.currentTimeMillis()) {
                    Log.d(TAG, "Pre-adhan setup time for $prayerName has already passed")
                    return
                }
                
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, PrayerReceiver::class.java).apply {
                    action = PrayerReceiver.PRE_ADHAN_SETUP_ACTION
                    putExtra("prayer_name", prayerName)
                }
                
                // Create unique request code based on prayer name and time
                val requestCode = (prayerName.hashCode() + prayerTime).toInt() and 0xfffffff
                
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                )
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        setupTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        setupTime,
                        pendingIntent
                    )
                }
                
                Log.d(TAG, "Scheduled pre-adhan setup for $prayerName at ${Date(setupTime)}")
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling pre-adhan setup for $prayerName", e)
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("prayer_settings", Context.MODE_PRIVATE)
    private val lockScreenStateManager = LockScreenStateManager.getInstance(context)
    private val _currentPrayerState = MutableStateFlow<String?>(null)
    val currentPrayerState: StateFlow<String?> = _currentPrayerState

    init {
        // Register for time changes
        timeSourceManager.addListener(object : TimeSourceManager.TimeChangeListener {
            override fun onTimeChanged(significantChange: Boolean) {
                if (significantChange) {
                    Log.d(TAG, "Significant time change detected, rescheduling prayers")
                    CoroutineScope(Dispatchers.IO).launch {
                        checkAndUpdateSchedule(true)
                    }
                }
            }
        })
    }

    fun updatePrayerSettings(settings: PrayerSettings) {
        this.prayerSettings = settings
    }

    fun setLockScreenListener(listener: LockScreenListener) {
        this.lockScreenListener = listener
    }

    private fun getUniqueRequestCode(prayer: Prayer, baseCode: Int): Int {
        // Combining the prayer name and time hash codes to reduce collision risk
        return baseCode + prayer.name.hashCode()
    }

    fun schedulePrayerWork() {
        try {
            Log.d("PrayerScheduler", "Scheduling prayer work.")
            val workRequest = PeriodicWorkRequestBuilder<PrayerWorker>(30, TimeUnit.MINUTES)
                .setInitialDelay(0, TimeUnit.MINUTES)  // Start immediately
                .build()
            
            // Use UPDATE to preserve running workers and original enqueue time
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "PrayerWork", 
                ExistingPeriodicWorkPolicy.UPDATE,  // Using UPDATE as recommended
                workRequest
            )
            
            // Schedule an immediate one-time work to check current state
            val immediateCheck = OneTimeWorkRequestBuilder<PrayerWorker>()
                .build()
            WorkManager.getInstance(context).enqueue(immediateCheck)
            
            Log.d("PrayerScheduler", "Scheduled periodic prayer worker and immediate check.")
        } catch (e: Exception) {
            Log.e("PrayerScheduler", "Error scheduling prayer work", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    // Calculate a hash based on current settings to detect changes
    private fun calculateSettingsHash(prayerName: String): Int {
        return (isPrayerEnabled(prayerName).toString() +
                isNotificationEnabled(prayerName).toString() +
                isAdhanEnabled(prayerName).toString() +
                isLockEnabled(prayerName).toString()).hashCode()
    }

    // Store previous hash values for comparison
    private val settingsHashMap = mutableMapOf<String, Int>()

    // Check if settings have changed since last check
    private fun hasSettingsChanged(prayerName: String): Boolean {
        val currentHash = calculateSettingsHash(prayerName)
        val previousHash = settingsHashMap[prayerName]
        val hasChanged = previousHash == null || previousHash != currentHash
        
        // Update stored hash value
        if (hasChanged) {
            settingsHashMap[prayerName] = currentHash
        }
        
        return hasChanged
    }

    fun handleDeviceBoot() {
        Log.d(TAG, "Handling device boot")
        scope.launch {
            try {
                // Check for active lock screen from before device shutdown
                // Removed SharedPreferences usage

                // Clear active lock screen state
                clearActiveLockScreen(context)

                // Reschedule prayers
                checkAndUpdateSchedule()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling device boot: ${e.message}")
            }
        }
    }

    private fun getRakaatCount(prayerName: String): Int {
        return when (prayerName.lowercase()) {
            "fajr" -> 2
            "dhuhr" -> 4
            "asr" -> 4
            "maghrib" -> 3
            "isha" -> 4
            else -> 4
        }
    }

    fun handlePrayerComplete(prayerName: String) {
        scope.launch {
            try {
                val calendar = Calendar.getInstance()
                val currentDate = "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH)}-${calendar.get(Calendar.DAY_OF_MONTH)}"
                
                // Removed SharedPreferences usage

                // Clear lock screen state
                clearActiveLockScreen(context)

                // Reschedule remaining prayers
                checkAndUpdateSchedule()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling prayer completion: ${e.message}")
            }
        }
    }

    /**
     * Check and update prayer schedules, with option to force rescheduling
     */
    suspend fun checkAndUpdateSchedule(forceReschedule: Boolean = false) {
        try {
            val location = locationManager.getLastLocation()
            if (location != null) {
                // Use the consistent time source
                val currentTime = timeSourceManager.getCurrentTime()
                
                // Force recalculation if requested
                val prayers = prayerTimeCalculator.calculatePrayerTimes(location, forceReschedule)
                
                if (prayers.isNotEmpty()) {
                    // Only cancel and reschedule if we're forcing it or it's been a while
                    if (forceReschedule || currentTime - lastScheduleTime > SCHEDULE_DEBOUNCE_TIME) {
                        Log.d(TAG, "Rescheduling prayers at ${Date(currentTime)}")
                        cancelAllNotifications()
                        scheduleAllPrayers(prayers)
                        lastScheduleTime = currentTime
                    }
                }
            } else {
                Log.w(TAG, "No location available for prayer scheduling")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating prayer schedule", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private fun calculateLockWindowEnd(currentPrayer: Prayer, nextPrayer: Prayer?): Long {
        return nextPrayer?.let { it.time - 15 * 60 * 1000L } ?: currentPrayer.time + 60 * 60 * 1000L // fallback if no next prayer
    }

    private suspend fun handleTimeOrDateChange(currentTime: Long, currentDate: String) {
        try {
            // Stop any playing adhan first
            PrayerReceiver.stopAdhan()
            
            // Cancel all existing schedules
            cancelAllSchedules()
            
            // Clear scheduled prayers list and active lock screen
            scheduledPrayers.clear()
            clearActiveLockScreen(context)
            saveScheduledPrayers()
            
            // Get all prayer times for today
            val location = locationManager.getLastLocation()
            val prayerTimes = prayerTimeCalculator.calculatePrayerTimes(location)
            val previousDate = currentDate

            // If date has changed, clear previous day's data
            if (currentDate != previousDate) {
                completionManager.clearOldCompletions()
                Log.d(TAG, "Date changed from $previousDate to $currentDate, cleared completion data")
            }
            
            // Process each prayer
            var foundNextPrayer = false
            prayerTimes.forEach { prayer ->
                val timeDiff = currentTime - prayer.time
                
                if (timeDiff > MISSED_PRAYER_THRESHOLD) { 
                    // Prayer was more than threshold time ago - mark as missed and skip
                    if (!completionManager.isPrayerComplete(prayer.name)) {
                        Log.d(TAG, "Prayer time has passed by more than threshold: ${prayer.name}")
                        completionManager.markPrayerMissed(prayer.name)
                        // Make sure to clear any active lock screen for missed prayers
                        clearActiveLockScreen(context)
                    }
                } else if (timeDiff > 5 * 60 * 1000) { 
                    // Prayer was 5+ minutes ago but within threshold - mark as missed but allow lock screen
                    if (!completionManager.isPrayerComplete(prayer.name)) {
                        Log.d(TAG, "Prayer time has passed by more than 5 minutes: ${prayer.name}")
                        completionManager.markPrayerMissed(prayer.name)
                        clearActiveLockScreen(context)
                    }
                } else if (timeDiff > 0 && timeDiff <= 5 * 60 * 1000) {
                    // Prayer is less than 5 minutes past - show notifications and lock screen
                    if (isPrayerEnabled(prayer.name)) {
                        if (isLockEnabled(prayer.name) && !foundNextPrayer) {
                            scheduleLockScreenIfEnabled(prayer)
                            foundNextPrayer = true
                        }
                        if (isAdhanEnabled(prayer.name)) {
                            scheduleAdhanIfEnabled(prayer)
                        }
                    }
                } else { // Future prayer
                    if (isPrayerEnabled(prayer.name)) {
                        // Schedule all enabled features
                        if (isLockEnabled(prayer.name) && !foundNextPrayer) {
                            scheduleLockScreenIfEnabled(prayer)
                            foundNextPrayer = true
                        }
                        if (isNotificationEnabled(prayer.name)) {
                            scheduleAdvanceNotificationIfEnabled(prayer)
                        }
                        if (isAdhanEnabled(prayer.name)) {
                            scheduleAdhanIfEnabled(prayer)
                        }
                        
                        // Update scheduled prayers list
                        updateScheduledPrayersList(prayer)
                        Log.d(TAG, "Scheduled future prayer: ${prayer.name} at " + java.util.Date(prayer.time))
                    }
                }
            }
            
            // Save the updated schedule
            saveScheduledPrayers()
            
            // Update tracking info
            // Removed SharedPreferences usage
            
            Log.d(TAG, "Time/date change handled. Current date: $currentDate, Time: " + java.util.Date(currentTime))
        } catch (e: Exception) {
            Log.e(TAG, "Error handling time/date change: ${e.message}")
        }

        // New logic: On time or date change, if lockscreen is enabled and current time is within the prayer's lock window, activate the lockscreen
        if (PrayerScheduler.isLockscreenEnabled) {
            val now = System.currentTimeMillis()
            val currentPrayer = getCurrentPrayer()
            val nextPrayer = getNextPrayer()
            if (currentPrayer != null) {
                val lockWindowEnd = calculateLockWindowEnd(currentPrayer, nextPrayer)
                if (now >= currentPrayer.time && now < lockWindowEnd) {
                    if (!isLockScreenActive()) {
                        Log.d(TAG, "Activating lockscreen - device turned on inside prayer window for ${'$'}{currentPrayer.name}")
                        setLockScreenForPrayer(currentPrayer)
                    }
                }
            }
        }
    }

    private fun getCurrentPrayer(): Prayer? {
        // Implement logic to get the current prayer
        return null
    }

    private fun getNextPrayer(): Prayer? {
        // Implement logic to get the next prayer
        return null
    }

    private fun isLockScreenActive(): Boolean {
        // TODO: Implement actual logic to determine if lockscreen is currently active
        return false
    }

    private fun setLockScreenForPrayer(prayer: Prayer) {
        // Implement logic to set the lock screen for the given prayer
    }

    fun schedulePrayer(prayer: Prayer) {
        try {
            // Use TimeSourceManager for consistent time
            val currentTime = timeSourceManager.getCurrentTime()
            
            // Skip if prayer time has passed
            if (prayer.time < currentTime) {
                Log.d(TAG, "Prayer time has already passed, skipping scheduling")
                return
            }

            // Check if prayer is enabled
            if (!isPrayerEnabled(prayer.name)) {
                Log.d(TAG, "Prayer ${prayer.name} is disabled, skipping scheduling")
                return
            }

            // Cancel any existing schedules for this prayer
            cancelPrayer(prayer)

            // Schedule only enabled features
            if (isLockEnabled(prayer.name)) {
                scheduleLockScreenIfEnabled(prayer)
            }
            if (isNotificationEnabled(prayer.name)) {
                scheduleAdvanceNotificationIfEnabled(prayer)
            }
            if (isAdhanEnabled(prayer.name)) {
                scheduleAdhanIfEnabled(prayer)
            }

            // Save this prayer as scheduled
            updateScheduledPrayersList(prayer)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling prayer ${prayer.name}: ${e.message}")
            e.printStackTrace()
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private fun scheduleLockScreenIfEnabled(prayer: Prayer) {
        val settingsManager = PrayerSettingsManager.getInstance(context)
        
        // First check if enabled (double check)
        if (!settingsManager.isLockEnabled(prayer.name)) {
            Log.d(TAG, "Lock screen disabled for ${prayer.name}, skipping")
            return
        }
        
        // Define a unique request code for this prayer and action type
        val requestCode = getUniqueRequestCode(prayer, LOCK_SCREEN_REQUEST_CODE_BASE)
        
        // Cancel any existing intent first
        val existingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, PrayerReceiver::class.java).apply {
                action = PrayerReceiver.PRAYER_LOCK_ACTION
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
        )
        
        existingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "Cancelled existing lock screen alarm for ${prayer.name}")
        }
        
        // Create new intent with prayer data
        val intent = Intent(context, PrayerReceiver::class.java).apply {
            action = PrayerReceiver.PRAYER_LOCK_ACTION
            putExtra("prayer_name", prayer.name)
            putExtra("rakaat_count", prayer.rakaatCount)
            putExtra("prayer_time", prayer.time)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
        
        setAlarm(prayer.time, pendingIntent)
        Log.d(TAG, "Scheduled lock screen for ${prayer.name} at ${java.util.Date(prayer.time)}")
    }

    private fun scheduleAdvanceNotificationIfEnabled(prayer: Prayer) {
        val settingsManager = PrayerSettingsManager.getInstance(context)

        // Check if advance notification is enabled (Uses manager - Good)
        if (!settingsManager.isAdvanceNotificationEnabled(prayer.name)) {
            Log.d(TAG, "Advance notification disabled for ${prayer.name}, skipping")
            return
        }

        // Get advance notification time (minutes before prayer)
        // Prioritize per-prayer setting, then global, then default
        val sharedPrefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        val prayerSpecificKey = "${prayer.name.lowercase()}_advance_minutes" // Assumed key for per-prayer offset
        val globalKey = "advance_notification_minutes"

        val advanceMinutes = when {
            // Check if per-prayer key exists and read it
            sharedPrefs.contains(prayerSpecificKey) -> {
                sharedPrefs.getInt(prayerSpecificKey, DEFAULT_NOTIFICATION_ADVANCE_MINUTES)
            }
            // Otherwise, check if global key exists and read it
            sharedPrefs.contains(globalKey) -> {
                 sharedPrefs.getInt(globalKey, DEFAULT_NOTIFICATION_ADVANCE_MINUTES)
            }
            // Otherwise, use the hardcoded default
            else -> {
                DEFAULT_NOTIFICATION_ADVANCE_MINUTES
            }
        }
        Log.d(TAG, "Using advance minutes for ${prayer.name}: $advanceMinutes (loaded from ${ if (sharedPrefs.contains(prayerSpecificKey)) "prayer key" else if (sharedPrefs.contains(globalKey)) "global key" else "default"})")

        // Calculate the time for advance notification
        val advanceNotificationTime = prayer.time - (advanceMinutes * 60 * 1000L) // Use the determined advanceMinutes

        // Skip if advance time is in the past
        if (advanceNotificationTime <= System.currentTimeMillis()) {
            Log.d(TAG, "Advance notification time for ${prayer.name} is in the past ($advanceMinutes min before), skipping")
            return
        }

        // Define a unique request code for advance notification
        val requestCode = getUniqueRequestCode(prayer, ADVANCE_NOTIFICATION_REQUEST_CODE_BASE)

        // Cancel any existing intent first
        val existingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, PrayerReceiver::class.java).apply {
                // Action is only needed for matching, not for data extraction here
                action = PrayerReceiver.PRAYER_NOTIFICATION_ACTION
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
        )

        existingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "Cancelled existing advance notification alarm for ${prayer.name}")
        }

        // Create intent for advance notification
        val intent = Intent(context, PrayerReceiver::class.java).apply {
            action = PrayerReceiver.PRAYER_NOTIFICATION_ACTION
            putExtra("prayer_name", prayer.name)
            putExtra("rakaat_count", prayer.rakaatCount)
            putExtra("prayer_time", prayer.time)
            putExtra("is_advance_notification", true) // Correctly set
            putExtra("advance_minutes", advanceMinutes) // Pass the determined value
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )

        setAlarm(advanceNotificationTime, pendingIntent)
        Log.d(TAG, "Scheduled advance notification for ${prayer.name} at ${java.util.Date(advanceNotificationTime)} ($advanceMinutes minutes before prayer)")
    }

    private fun scheduleAdhanIfEnabled(prayer: Prayer) {
        val settingsManager = PrayerSettingsManager.getInstance(context)
        
        // First check if enabled (double check)
        if (!settingsManager.isAdhanEnabled(prayer.name)) {
            Log.d(TAG, "Adhan disabled for ${prayer.name}, skipping")
            return
        }
        
        // Define a unique request code for this prayer and action type
        val requestCode = getUniqueRequestCode(prayer, ADHAN_REQUEST_CODE_BASE)
        
        // Cancel any existing intent first
        val existingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, PrayerReceiver::class.java).apply {
                action = PrayerReceiver.PRAYER_ADHAN_ACTION
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
        )
        
        existingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "Cancelled existing adhan alarm for ${prayer.name}")
        }
        
        // Also schedule Pre-Adhan setup for volume restoration
        schedulePreAdhanSetup(prayer)
        
        // Create new intent with prayer data
        val intent = Intent(context, PrayerReceiver::class.java).apply {
            action = PrayerReceiver.PRAYER_ADHAN_ACTION
            putExtra("prayer_name", prayer.name)
            putExtra("rakaat_count", prayer.rakaatCount)
            putExtra("prayer_time", prayer.time)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
        
        setAlarm(prayer.time, pendingIntent)
        Log.d(TAG, "Scheduled adhan for ${prayer.name} at ${java.util.Date(prayer.time)}")
    }

    private fun cancelAllSchedules() {
        val prayerNames = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")
        prayerNames.forEach { name ->
            // Cancel lock screen intent
            val lockIntent = Intent(context, PrayerReceiver::class.java).apply {
                action = PrayerReceiver.PRAYER_LOCK_ACTION
                putExtra("prayer_name", name)
            }
            val lockPendingIntent = PendingIntent.getBroadcast(
                context,
                getUniqueRequestCode(Prayer(name, 0, 0),
                1000),
                lockIntent,
                PendingIntent.FLAG_NO_CREATE or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            lockPendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
            }

            // Cancel notification intent
            val notificationIntent = Intent(context, PrayerReceiver::class.java).apply {
                action = PrayerReceiver.PRAYER_NOTIFICATION_ACTION
                putExtra("prayer_name", name)
            }
            val notificationPendingIntent = PendingIntent.getBroadcast(
                context,
                getUniqueRequestCode(Prayer(name, 0, 0),
                2000),
                notificationIntent,
                PendingIntent.FLAG_NO_CREATE or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            notificationPendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
            }

            // Cancel adhan intent
            val adhanIntent = Intent(context, PrayerReceiver::class.java).apply {
                action = PrayerReceiver.PRAYER_ADHAN_ACTION
                putExtra("prayer_name", name)
            }
            val adhanPendingIntent = PendingIntent.getBroadcast(
                context,
                getUniqueRequestCode(Prayer(name, 0, 0),
                3000),
                adhanIntent,
                PendingIntent.FLAG_NO_CREATE or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            adhanPendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
        Log.d(TAG, "Cancelled all existing schedules")
    }

    fun schedulePrayerNotification(name: String, time: Long, rakaatCount: Int) {
        Log.d(TAG, "schedulePrayerNotification called for prayer: $name at time: $time with rakaat count: $rakaatCount")
        try {
            val prayer = Prayer(name, time, rakaatCount)
            val currentTime = System.currentTimeMillis()
            
            // Skip if prayer time has passed
            if (time < currentTime) {
                Log.d(TAG, "Skipping passed prayer time for $name")
                return
            }
            
            // Check for settings changes
            if (hasSettingsChanged(name)) {
                Log.d(TAG, "Settings changed for $name, cancelling existing schedules")
                cancelPrayer(prayer)
            }

            // Verify prayer is enabled
            if (!isPrayerEnabled(name)) {
                Log.d(TAG, "Prayer $name is disabled, skipping schedule")
                return
            }

            // Check if adhan is enabled for this prayer
            val isAdhanEnabled = isAdhanEnabled(name)
            if (isAdhanEnabled) {
                // Schedule pre-adhan setup for volume button control
                schedulePreAdhanSetup(context, name, time)
                Log.d(TAG, "Scheduled pre-adhan setup for $name at ${time - PRE_ADHAN_SETUP_TIME}")
            }

            // Schedule lock screen if enabled (exactly at prayer time)
            if (isLockEnabled(name)) {
                val lockIntent = Intent(context, PrayerReceiver::class.java).apply {
                    action = PrayerReceiver.PRAYER_LOCK_ACTION
                    putExtra("prayer_name", name)
                    putExtra("rakaat_count", rakaatCount)
                    putExtra("prayer_time", time)
                }
                
                val lockPendingIntent = PendingIntent.getBroadcast(
                    context,
                    getUniqueRequestCode(prayer, 1000),
                    lockIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )

                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(time, lockPendingIntent),
                    lockPendingIntent
                )
                Log.d(TAG, "Scheduled lock screen for $name at ${'$'}{time}")
                
                // Schedule unlock callback
                val lockWindowEnd = calculateLockWindowEnd(prayer, null)
                val unlockIntent = Intent(context, PrayerReceiver::class.java).apply {
                    action = PRAYER_UNLOCK_ACTION
                    putExtra("prayer_name", name)
                }
                val unlockPendingIntent = PendingIntent.getBroadcast(
                    context,
                    getUniqueRequestCode(prayer, 4000),
                    unlockIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(lockWindowEnd, unlockPendingIntent),
                    unlockPendingIntent
                )
            }

            // Schedule notification if enabled (using saved advance time setting)
            if (isNotificationEnabled(name)) {
                val advanceMinutes = getNotificationAdvanceTime()
                val notificationTime = time - (advanceMinutes * 60 * 1000L)

                if (notificationTime > currentTime) {
                    val notificationIntent = Intent(context, PrayerReceiver::class.java).apply {
                        action = PrayerReceiver.PRAYER_NOTIFICATION_ACTION
                        putExtra("prayer_name", name)
                        putExtra("prayer_time", time)
                        putExtra("is_advance_notification", true)
                        putExtra("advance_minutes", advanceMinutes)
                    }
                    
                    val notificationPendingIntent = PendingIntent.getBroadcast(
                        context,
                        getUniqueRequestCode(prayer, 2000),
                        notificationIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                    )

                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(notificationTime, notificationPendingIntent),
                        notificationPendingIntent
                    )
                    Log.d(TAG, "Scheduled notification for $name at ${'$'}{notificationTime}")
                }
            }

            // Schedule Adhan if enabled
            if (isAdhanEnabled) {
                val adhanIntent = Intent(context, PrayerReceiver::class.java).apply {
                    action = PrayerReceiver.PRAYER_ADHAN_ACTION
                    putExtra("prayer_name", name)
                    putExtra("prayer_time", time)
                }
                
                val adhanPendingIntent = PendingIntent.getBroadcast(
                    context,
                    getUniqueRequestCode(prayer, 3000),
                    adhanIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )

                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(time, adhanPendingIntent),
                    adhanPendingIntent
                )
                Log.d(TAG, "Scheduled Adhan for $name at ${'$'}{time}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling prayer $name: ${'$'}{e.message}")
            e.printStackTrace()
        }
    }

    private fun saveScheduledPrayers() {
        val scheduledPrayersJson = JSONArray(scheduledPrayers.map { it.toString() })
        // Removed SharedPreferences usage
    }

    private fun scheduleActualPrayerTime(prayer: Prayer) {
        try {
            if (prayer.time <= System.currentTimeMillis()) {
                Log.d(TAG, "Prayer time has already passed for ${'$'}{prayer.name}")
                return
            }

            // Check if prayer is enabled
            if (!isPrayerEnabled(prayer.name)) {
                Log.d(TAG, "Prayer ${'$'}{prayer.name} is disabled, skipping scheduling")
                return
            }

            // Cancel any existing schedules for this prayer
            cancelPrayer(prayer)

            // Schedule only enabled features
            if (isLockEnabled(prayer.name)) {
                scheduleLockScreenIfEnabled(prayer)
            }
            if (isNotificationEnabled(prayer.name)) {
                scheduleAdvanceNotificationIfEnabled(prayer)
            }
            if (isAdhanEnabled(prayer.name)) {
                scheduleAdhanIfEnabled(prayer)
            }

            // Save this prayer as scheduled
            updateScheduledPrayersList(prayer)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling actual prayer time for ${'$'}{prayer.name}: ${'$'}{e.message}")
            e.printStackTrace()
        }
    }

    private fun updateScheduledPrayersList(prayer: Prayer) {
        // Removed SharedPreferences usage
    }

    private fun updateLastScheduleTime() {
        // Removed SharedPreferences usage
    }

    private fun getLastScheduleTime(): Long {
        // Removed SharedPreferences usage
        return 0
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isPrayerEnabled(prayerName: String): Boolean {
        // Use Settings Manager for consistency
        // Note: PrayerSettingsManager doesn't have a specific 'isPrayerEnabled' 
        // Assuming 'notification enabled' or 'any feature enabled' implies prayer is considered enabled for scheduling
        // Let's check if *any* feature is enabled for this prayer.
        val settingsManager = PrayerSettingsManager.getInstance(context)
        return settingsManager.isNotificationEnabled(prayerName) || 
               settingsManager.isAdhanEnabled(prayerName) || 
               settingsManager.isLockEnabled(prayerName)
        // Old code: 
        // val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        // return prefs.getBoolean("${prayerName.lowercase()}_enabled", true)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isNotificationEnabled(prayerName: String): Boolean {
        // Use Settings Manager for consistency
        val settingsManager = PrayerSettingsManager.getInstance(context)
        return settingsManager.isNotificationEnabled(prayerName)
        // Old code:
        // val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        // return prefs.getBoolean("${prayerName.lowercase()}_notification", true)
    }

    private fun isLockEnabled(prayerName: String): Boolean {
        // Use Settings Manager for consistency
        val settingsManager = PrayerSettingsManager.getInstance(context)
        return settingsManager.isLockEnabled(prayerName)
        // Old code:
        // val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        // return prefs.getBoolean("enable_lock", true) && 
        //        prefs.getBoolean("${prayerName.lowercase()}_lock", true)
    }

    private fun isAdhanEnabled(prayerName: String): Boolean {
        // Use Settings Manager for consistency
        val settingsManager = PrayerSettingsManager.getInstance(context)
        return settingsManager.isAdhanEnabled(prayerName)
        // Old code:
        // val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        // val isAdhanGloballyEnabled = prefs.getBoolean("enable_adhan", true)
        // val isPrayerAdhanEnabled = prefs.getBoolean("${prayerName.lowercase()}_adhan", true)
        // return isAdhanGloballyEnabled && isPrayerAdhanEnabled
    }

    interface LockScreenListener {
        fun unlockAndReset()
    }

    // Restored methods for notification and alarm management

    private fun setAlarm(time: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(time, pendingIntent),
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    time,
                    pendingIntent
                )
            }
            Log.d(TAG, "Alarm set for " + java.util.Date(time))
        } catch (e: Exception) {
            Log.e(TAG, "Error setting alarm: ${'$'}{e.message}")
            e.printStackTrace()
        }
    }

    private fun getNotificationAdvanceTime(): Int {
        val sharedPrefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        return when {
            // First try the new settings key (matches SettingsViewModel)
            sharedPrefs.contains("notification_time") -> {
                sharedPrefs.getInt("notification_time", DEFAULT_NOTIFICATION_ADVANCE_MINUTES)
            }
            // Then try the older global key
            sharedPrefs.contains("advance_notification_minutes") -> {
                sharedPrefs.getInt("advance_notification_minutes", DEFAULT_NOTIFICATION_ADVANCE_MINUTES)
            }
            // Finally use default
            else -> {
                DEFAULT_NOTIFICATION_ADVANCE_MINUTES
            }
        }
    }

    fun cancelPrayer(prayer: Prayer) {
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            // Cancel the notification PendingIntent
            val notificationIntent = Intent(context, PrayerReceiver::class.java).apply {
                action = PrayerReceiver.PRAYER_NOTIFICATION_ACTION  // Ensure this action is defined appropriately in PrayerReceiver
                putExtra("prayer_name", prayer.name)
                putExtra("rakaat_count", prayer.rakaatCount)
            }
            val notificationPendingIntent = PendingIntent.getBroadcast(
                context,
                getUniqueRequestCode(prayer, 1000),
                notificationIntent,
                flags
            )
            alarmManager.cancel(notificationPendingIntent)
            notificationPendingIntent.cancel()
            
            // Cancel the lock PendingIntent
            val lockIntent = Intent(context, PrayerReceiver::class.java).apply {
                action = PrayerReceiver.PRAYER_LOCK_ACTION
                putExtra("prayer_name", prayer.name)
                putExtra("rakaat_count", prayer.rakaatCount)
            }
            val lockPendingIntent = PendingIntent.getBroadcast(
                context,
                getUniqueRequestCode(prayer, 1),
                lockIntent,
                flags
            )
            alarmManager.cancel(lockPendingIntent)
            lockPendingIntent.cancel()
            
            scheduledPrayers.remove(prayer.hashCode())
            Log.d(TAG, "Successfully cancelled prayer ${'$'}{prayer.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling prayer ${'$'}{prayer.name}: ${'$'}{e.message}")
            e.printStackTrace()
        }
    }

    fun cancelAllNotifications() {
        try {
            // Cancel all existing alarms by clearing the scheduled prayers list
            scheduledPrayers.clear()
            
            // Clear the event tracking
            scheduledEvents.clear()
            
            // Cancel all pending intents
            cancelPrayer("fajr")
            cancelPrayer("dhuhr")
            cancelPrayer("asr")
            cancelPrayer("maghrib")
            cancelPrayer("isha")
            
            Log.d(TAG, "Cancelled all notifications and pending alarms")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling notifications: ${'$'}{e.message}")
        }
    }

    fun scheduleAllPrayers(prayerTimes: List<Prayer>) {
        scope.launch {
            try {
                // Use TimeSourceManager for consistent time
                val currentTime = timeSourceManager.getCurrentTime()
                
                // Avoid redundant scheduling
                if (currentTime - lastScheduleTime < SCHEDULE_DEBOUNCE_TIME) {
                    Log.d(TAG, "Skipping prayer scheduling, last scheduled ${(currentTime - lastScheduleTime)/1000} seconds ago")
                    return@launch
                }
                
                lastScheduleTime = currentTime
                
                Log.d(TAG, "Scheduling ${prayerTimes.size} prayers")
                
                cancelAllNotifications() // Clear existing notifications first
                prayerTimes.forEach { prayer ->
                    if (!completionManager.isPrayerComplete(prayer.name)) {
                        schedulePrayer(prayer)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling prayers: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    suspend fun checkForMissedPrayers() {
        try {
            val currentTime = System.currentTimeMillis()
            val location = locationManager.getLastLocation()
            val prayers = prayerTimeCalculator.calculatePrayerTimes(location)
            
            for (prayer in prayers) {
                if (!completionManager.isPrayerComplete(prayer.name)) {
                    val timeDifference = currentTime - prayer.time
                    if (timeDifference > MISSED_PRAYER_THRESHOLD && timeDifference < SCHEDULE_INTERVAL) {
                        Log.d(TAG, "Found missed prayer: ${prayer.name}")
                        
                        // Lock screen for missed prayer
                        val lockScreenManager = LockScreenStateManager.getInstance(context)
                        lockScreenManager.initialize(
                            prayerName = prayer.name,
                            rakaatCount = prayer.rakaatCount,
                            isMissedPrayer = true
                        )
                        
                        // Start lock service
                        val intent = Intent(context, LockService::class.java).apply {
                            putExtra("prayer_name", prayer.name)
                            putExtra("is_missed_prayer", true)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        
                        break // Handle one missed prayer at a time
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for missed prayers", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private suspend fun calculateNextPrayer(): String? {
        val now = timeSourceManager.getCurrentTime()
        val location = locationManager.getLastLocation()
        return prayerTimeCalculator.calculatePrayerTimes(location)
            .filter { !completionManager.isPrayerComplete(it.name) }
            .minByOrNull { abs(it.time - now) }
            ?.name
    }

    private fun scheduleLockScreen(prayer: Prayer) {
        if (!isLockscreenEnabled) {
            Log.d(TAG, "Lock screens are globally disabled, skipping for ${prayer.name}")
            return
        }
        
        try {
            // Use TimeSourceManager for consistent time
            val currentTime = timeSourceManager.getCurrentTime()
            
            // Enhanced validation of prayer time
            if (prayer.time <= 0) {
                Log.e(TAG, "Invalid prayer time for ${prayer.name}: ${prayer.time}")
                return
            }
            
            // Create an adjusted prayer object if needed
            val adjustedPrayer = when {
                // Validate that prayer time is not too far in the past
                prayer.time < currentTime - (60 * 60 * 1000) -> { // more than 1 hour ago
                    Log.d(TAG, "Skipping lock screen scheduling for ${prayer.name} because time has passed (more than 1 hour ago).")
                    return
                }
                // If prayer time is slightly in the past (within the last hour), use current time + 10 seconds
                prayer.time < currentTime -> {
                    Log.d(TAG, "Prayer time ${prayer.time} for ${prayer.name} is in the past. Using adjusted time.")
                    Prayer(prayer.name, currentTime + (10 * 1000), prayer.rakaatCount) // 10 seconds in the future
                }
                else -> prayer // Use the original prayer object
            }

            if (!isLockEnabled(adjustedPrayer.name)) {
                Log.d(TAG, "Lock screen disabled for ${adjustedPrayer.name}, skipping lock screen scheduling")
                return
            }

            // Get the lock state manager
            val appContext = context.applicationContext
            
            // Get the state manager through DI if app context is available
            val lockStateManager = when {
                appContext is com.viperdam.kidsprayer.PrayerApp -> {
                    try {
                        // Try to get it through the app's dependency container
                        com.viperdam.kidsprayer.PrayerApp.getInstance().lockStateManager
                    } catch (e: Exception) {
                        // Fall back to direct instantiation
                        com.viperdam.kidsprayer.state.PrayerLockStateManager(
                            appContext,
                            PrayerCompletionManager.getInstance(context)
                        )
                    }
                }
                else -> {
                    // Direct instantiation as fallback
                    com.viperdam.kidsprayer.state.PrayerLockStateManager(
                        appContext,
                        PrayerCompletionManager.getInstance(context)
                    )
                }
            }
            
            // Get the current state from the lock state manager
            val currentState = lockStateManager.lockState.value
            
            // Check if the lock screen for the original prayer instance is already active
            if (currentState.isLockActive && 
                currentState.activePrayerName == prayer.name && 
                currentState.activePrayerTime == prayer.time) {
                Log.d(TAG, "Lock screen for original prayer ${prayer.name} at ${java.util.Date(prayer.time)} is already active. Skipping reschedule.")
                return // Avoid clearing and rescheduling the already active lock
            }
            
            // Reset lock screen state before scheduling new one - now use our new state manager
            clearActiveLockScreen(context)

            // Only schedule if the prayer time is in the future
            if (adjustedPrayer.time > System.currentTimeMillis()) {
                // Set active lock screen in our state manager
                lockStateManager.setActiveLock(adjustedPrayer)

                val intent = Intent(context, PrayerReceiver::class.java).apply {
                    action = PrayerReceiver.PRAYER_LOCK_ACTION
                    putExtra("prayer_name", adjustedPrayer.name)
                    putExtra("rakaat_count", adjustedPrayer.rakaatCount)
                    putExtra("prayer_time", adjustedPrayer.time)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    getUniqueRequestCode(adjustedPrayer, 1000),
                    intent,
                    flags
                )
                
                Log.d(TAG, "Scheduling exact lock screen for ${adjustedPrayer.name} at ${java.util.Date(adjustedPrayer.time)}")
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, adjustedPrayer.time, pendingIntent)
                
                // Schedule unlock callback
                val lockWindowEnd = calculateLockWindowEnd(adjustedPrayer, null)
                
                // Update state manager with unlock schedule
                lockStateManager.scheduleAutomaticUnlock(adjustedPrayer.name, lockWindowEnd)
                
                // Also set the actual alarm
                val unlockIntent = Intent(context, PrayerReceiver::class.java).apply {
                    action = PRAYER_UNLOCK_ACTION
                    putExtra("prayer_name", adjustedPrayer.name)
                    putExtra("rakaat_count", adjustedPrayer.rakaatCount)
                    putExtra("prayer_time", adjustedPrayer.time)
                    putExtra("unlock_time", lockWindowEnd)
                }
                
                val unlockPendingIntent = PendingIntent.getBroadcast(
                    context,
                    getUniqueRequestCode(adjustedPrayer, 2000),
                    unlockIntent,
                    flags
                )
                
                Log.d(TAG, "Scheduling automatic unlock for ${adjustedPrayer.name} at ${java.util.Date(lockWindowEnd)}")
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, lockWindowEnd, unlockPendingIntent)
            } else {
                Log.w(TAG, "Adjusted prayer time for ${adjustedPrayer.name} is still in the past, not scheduling")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling lock screen", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    /**
     * Schedules all events for the provided prayer based on settings
     */
    fun schedulePrayerEvents(prayer: Prayer) {
        val settingsManager = PrayerSettingsManager.getInstance(context)
        
        // Only schedule enabled features to avoid unnecessary alarms
        val anyFeatureEnabled = settingsManager.isLockEnabled(prayer.name) || 
                               settingsManager.isAdhanEnabled(prayer.name) || 
                               settingsManager.isNotificationEnabled(prayer.name)
        
        if (!anyFeatureEnabled) {
            Log.d(TAG, "All features disabled for ${prayer.name}, skipping scheduling")
            return
        }
        
        if (settingsManager.isLockEnabled(prayer.name)) {
            scheduleLockScreenIfEnabled(prayer)
        } else {
            Log.d(TAG, "Lock screen disabled for ${prayer.name}, not scheduling")
        }
        
        if (settingsManager.isAdhanEnabled(prayer.name)) {
            scheduleAdhanIfEnabled(prayer)
        } else {
            Log.d(TAG, "Adhan disabled for ${prayer.name}, not scheduling")
        }
        
        if (settingsManager.isNotificationEnabled(prayer.name)) {
            scheduleNotificationIfEnabled(prayer)
        } else {
            Log.d(TAG, "Notification disabled for ${prayer.name}, not scheduling")
        }
        
        // If advance notification is enabled, schedule it
        if (settingsManager.isAdvanceNotificationEnabled(prayer.name)) {
            scheduleAdvanceNotificationIfEnabled(prayer)
        }
    }

    /**
     * Schedules the notification for the prayer if enabled
     */
    fun scheduleNotificationIfEnabled(prayer: Prayer) {
        val settingsManager = PrayerSettingsManager.getInstance(context)
        
        // First check if enabled (double check)
        if (!settingsManager.isNotificationEnabled(prayer.name)) {
            Log.d(TAG, "Notification disabled for ${prayer.name}, skipping")
            return
        }
        
        // Define a unique request code for this prayer and action type
        val requestCode = getUniqueRequestCode(prayer, NOTIFICATION_REQUEST_CODE_BASE)
        
        // Cancel any existing intent first
        val existingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, PrayerReceiver::class.java).apply {
                action = PrayerReceiver.PRAYER_NOTIFICATION_ACTION
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
        )
        
        existingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "Cancelled existing notification alarm for ${prayer.name}")
        }
        
        // Create new intent with prayer data
        val intent = Intent(context, PrayerReceiver::class.java).apply {
            action = PrayerReceiver.PRAYER_NOTIFICATION_ACTION
            putExtra("prayer_name", prayer.name)
            putExtra("rakaat_count", prayer.rakaatCount)
            putExtra("prayer_time", prayer.time)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
        
        setAlarm(prayer.time, pendingIntent)
        Log.d(TAG, "Scheduled notification for ${prayer.name} at ${java.util.Date(prayer.time)}")
    }

    // Create helper method to create Prayer objects from strings
    private fun createPrayer(prayerName: String): Prayer {
        val rakaatCount = when (prayerName.lowercase()) {
            "fajr" -> 2
            "dhuhr" -> 4
            "asr" -> 4
            "maghrib" -> 3
            "isha" -> 4
            else -> 4 // Default
        }
        return Prayer(prayerName, System.currentTimeMillis(), rakaatCount)
    }

    // Helper to cancel a prayer by name
    private fun cancelPrayer(prayerName: String) {
        try {
            val prayer = createPrayer(prayerName)
            cancelPrayer(prayer)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling prayer by name: $prayerName", e)
        }
    }

    // Add method to schedule pre-adhan setup for a prayer
    private fun schedulePreAdhanSetup(prayer: Prayer) {
        // Call the companion object method with the prayer data
        schedulePreAdhanSetup(context, prayer.name, prayer.time)
    }

    /**
     * Get the current day's prayer times
     * This is a non-suspending function for cases where we need immediate access to prayer times
     * It will use cached values if available, and calculate new ones if needed
     * 
     * @return List of Prayer objects for the current day
     */
    fun getCurrentPrayerTimes(): List<Prayer> {
        val cached = getCachedPrayerTimes()
        if (cached.isNotEmpty()) {
            return cached
        }
        
        // No cached times, calculate synchronously (this is not ideal but necessary for some calls)
        val location = locationManager.getLastLocationSync() ?: return emptyList()
        return prayerTimeCalculator.calculatePrayerTimes(location)
    }
    
    /**
     * Get cached prayer times if available
     */
    private fun getCachedPrayerTimes(): List<Prayer> {
        val prefs = context.getSharedPreferences("prayer_scheduler_prefs", Context.MODE_PRIVATE)
        val cachedJson = prefs.getString("cached_prayer_times", null) ?: return emptyList()
        
        try {
            val prayers = mutableListOf<Prayer>()
            val jsonArray = JSONArray(cachedJson)
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.getString("name")
                val time = obj.getLong("time")
                val rakaatCount = obj.getInt("rakaatCount")
                
                prayers.add(Prayer(name, time, rakaatCount))
            }
            
            // Verify these are for today
            val timeManager = TimeSourceManager.getInstance(context)
            val today = Calendar.getInstance().apply { timeInMillis = timeManager.getCurrentTime() }
            val todayStart = Calendar.getInstance().apply {
                timeInMillis = timeManager.getCurrentTime()
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            
            // Only use if prayers are for today
            if (prayers.any { it.time >= todayStart }) {
                return prayers
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cached prayer times", e)
        }
        
        return emptyList()
    }
    
    /**
     * Cache prayer times for quick access
     */
    private fun cachePrayerTimes(prayers: List<Prayer>) {
        try {
            val jsonArray = JSONArray()
            
            for (prayer in prayers) {
                val obj = JSONObject().apply {
                    put("name", prayer.name)
                    put("time", prayer.time)
                    put("rakaatCount", prayer.rakaatCount)
                }
                jsonArray.put(obj)
            }
            
            context.getSharedPreferences("prayer_scheduler_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("cached_prayer_times", jsonArray.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error caching prayer times", e)
        }
    }
    
    init {
        // Listen for time changes
        TimeSourceManager.getInstance(context).addListener(object : TimeSourceManager.TimeChangeListener {
            override fun onTimeChanged(significantChange: Boolean) {
                if (significantChange) {
                    Log.d(TAG, "Significant time change detected, clearing prayer cache")
                    clearPrayerCache()
                    scope.launch {
                        try {
                            checkAndUpdateSchedule(forceReschedule = true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating schedule after time change", e)
                        }
                    }
                }
            }
        })
    }
    
    /**
     * Clear the prayer cache after significant time changes
     */
    private fun clearPrayerCache() {
        context.getSharedPreferences("prayer_scheduler_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove("cached_prayer_times")
            .apply()
    }

    // Example of potential update, assuming setCurrentPrayer is used to modify _currentPrayerState
    // Replace any direct assignments like _currentPrayerState.value = newPrayer
    // with _currentPrayerState.update { newPrayer }
    private fun setCurrentPrayer(prayerName: String?) {
        _currentPrayerState.update { prayerName }
    }
}
