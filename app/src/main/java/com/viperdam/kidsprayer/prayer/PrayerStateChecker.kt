package com.viperdam.kidsprayer.prayer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.viperdam.kidsprayer.model.Prayer
import com.viperdam.kidsprayer.service.LockScreenService
import com.viperdam.kidsprayer.service.PrayerScheduler
import com.viperdam.kidsprayer.ui.lock.LockScreenActivity
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.utils.PrayerValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

import com.viperdam.kidsprayer.prayer.PrayerCompletionManager.CompletionType
import com.viperdam.kidsprayer.util.PrayerSettingsManager

@Singleton
class PrayerStateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prayerTimeCalculator: PrayerTimeCalculator,
    private val completionManager: PrayerCompletionManager,
    private val locationManager: com.viperdam.kidsprayer.prayer.LocationManager
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val prayerQueue = mutableListOf<QueuedPrayer>()
    private var isProcessingQueue = false
    private var lastTimeZone = locationManager.getTimeZone().id
    private var lastStateChange = 0L
    private val upcomingPrayers = mutableListOf<String>()

    companion object {
        private const val TAG = "PrayerStateChecker"
        private const val PREFS_NAME = "prayer_state_prefs"
        private const val KEY_ACTIVE_PRAYER = "active_prayer"
        private const val KEY_QUEUED_PRAYERS = "queued_prayers"
        private const val KEY_NEXT_PRAYER_TIME = "next_prayer_time"
        private const val KEY_SHOWING_AD = "showing_ad"
        private const val KEY_LAST_AD_START = "last_ad_start"
        private const val KEY_LAST_AD_CLOSE_TIME = "last_ad_close_time"
        private const val KEY_STATE_VALID = "state_valid"
        private const val KEY_LAST_STATE_CHANGE = "last_state_change"
        private const val KEY_LAST_TIMEZONE = "last_timezone"
        private const val KEY_LAST_DATE = "last_date"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_PENDING_FAST_TRANSITION = "pending_fast_transition"
        private const val KEY_SHOULD_SHOW_AD = "should_show_ad"
        private const val KEY_IS_UNLOCK_PENDING = "is_unlock_pending"
        private const val KEY_FIRST_TIME_LAUNCH = "first_time_launch"
        private const val KEY_LAST_PRAYER_COMPLETION = "last_prayer_completion"
        private const val KEY_LAST_COMPLETED_PRAYER = "last_completed_prayer"
        private const val KEY_COOLDOWN_UNTIL = "cooldown_until"

        private const val MIN_STATE_CHANGE_INTERVAL = 1000L // 1 second
        private const val CURRENT_PRAYER_WINDOW_MS = 20 * 60 * 1000L // 20 minutes
        private const val NEXT_PRAYER_THRESHOLD_MS = 20 * 60 * 1000L // 20 minutes before next prayer
        private const val UPCOMING_PRAYER_THRESHOLD_MS = 60 * 1000L // 1 minute
        private const val AD_TIMEOUT_MS = 2 * 60 * 1000L // 2 minutes
        private const val AD_STATE_TIMEOUT_MS = 60 * 1000L // 1 minute
        private const val ERROR_RECOVERY_ATTEMPTS = 3
        private const val QUICK_TRANSITION_WINDOW_MS = 500L
        private const val PRAYER_QUEUE_LIMIT = 10
        private const val MAX_QUEUE_SIZE = 5
        private const val TRANSITION_THRESHOLD = 1000L // 1 second
        private const val MIN_AD_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes between ads
        private const val PRAYER_COOLDOWN_MS = 30 * 60 * 1000L // 30 minutes cooldown after prayer completion
        private const val UNWANTED_TRIGGER_PREVENTION_MS = 3 * 60 * 1000L // 3 minutes prevention after completion

        // Prayer intent extras
        private const val EXTRA_PRAYER_NAME = "prayer_name"
        private const val EXTRA_RAKAAT_COUNT = "rakaat_count"
    }

    private data class QueuedPrayer(
        val name: String,
        val rakaatCount: Int,
        val time: Long = System.currentTimeMillis()
    )

    init {
        checkDateTransition()
        // Initialize last timezone
        prefs.edit().putString(KEY_LAST_TIMEZONE, lastTimeZone).apply()
    }

    private fun checkDateTransition() {
        val currentDate = LocalDate.now().format(dateFormatter)
        val lastDate = prefs.getString(KEY_LAST_DATE, "") ?: ""

        if (lastDate != currentDate) {
            Log.d(TAG, "New day detected. Clearing previous states")
            clearAllStates()
            prefs.edit().putString(KEY_LAST_DATE, currentDate).apply()
        }

        // Check for timezone changes
        val currentTimeZone = locationManager.getTimeZone().id
        val savedTimeZone = prefs.getString(KEY_LAST_TIMEZONE, currentTimeZone) ?: currentTimeZone

        if (savedTimeZone != currentTimeZone) {
            Log.d(TAG, "Timezone changed from $savedTimeZone to $currentTimeZone")
            clearAllStates()
            prefs.edit().putString(KEY_LAST_TIMEZONE, currentTimeZone).apply()
            lastTimeZone = currentTimeZone
        }
    }

    private fun clearAllStates() {
        prefs.edit().apply {
            remove(KEY_ACTIVE_PRAYER)
            remove(KEY_QUEUED_PRAYERS)
            remove(KEY_NEXT_PRAYER_TIME)
            remove(KEY_LAST_STATE_CHANGE)
            putBoolean(KEY_SHOWING_AD, false)
            putBoolean(KEY_STATE_VALID, true)
            putBoolean(KEY_PENDING_FAST_TRANSITION, false)
            remove(KEY_LAST_ERROR)
            apply()
        }
        
        // Also clear all prayer unlocked flags
        val receiverPrefs = context.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
        val editor = receiverPrefs.edit()
        
        // Get all keys and remove those ending with "_unlocked"
        receiverPrefs.all.keys
            .filter { it.endsWith("_unlocked") }
            .forEach { editor.remove(it) }
            
        editor.apply()
    }

    private fun validateState(): Boolean {
        try {
            val isShowingAd = prefs.getBoolean(KEY_SHOWING_AD, false)
            val lastStateChange = prefs.getLong(KEY_LAST_STATE_CHANGE, 0)

            if (isShowingAd && System.currentTimeMillis() - lastStateChange > AD_TIMEOUT_MS) {
                Log.d(TAG, "Resetting stuck ad state")
                prefs.edit().putBoolean(KEY_SHOWING_AD, false).apply()
                return true
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "State validation failed: ${e.message}")
            return false
        }
    }

    private fun recoverFromInvalidState() {
        Log.d(TAG, "Attempting to recover from invalid state")
        clearAllStates()
        prefs.edit().putBoolean(KEY_STATE_VALID, true).apply()
    }

    private fun isSameDay(time1: Long, time2: Long): Boolean {
        val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = time1 }
        val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = time2 }
        return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
               cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun isFirstTimeLaunch(): Boolean {
        return prefs.getBoolean(KEY_FIRST_TIME_LAUNCH, true)
    }

    private fun markFirstLaunchComplete() {
        prefs.edit().putBoolean(KEY_FIRST_TIME_LAUNCH, false).apply()
    }

    private fun isInCooldownPeriod(): Boolean {
        val cooldownUntil = prefs.getLong(KEY_COOLDOWN_UNTIL, 0)
        return System.currentTimeMillis() < cooldownUntil
    }

    private fun startCooldownPeriod() {
        prefs.edit()
            .putLong(KEY_COOLDOWN_UNTIL, System.currentTimeMillis() + PRAYER_COOLDOWN_MS)
            .apply()
    }

   private fun shouldShowLockScreen(prayerName: String): Boolean {
        // Special handling for Test Prayer - always allow it to show
        if (prayerName.equals("Test Prayer", ignoreCase = true) || prayerName.equals("test", ignoreCase = true)) {
            Log.d(TAG, "Test prayer lock screen requested, bypassing normal checks")
            
            // Still check if we're already showing a lock screen to prevent duplicates
            val lockScreenActive = prefs.getBoolean("lock_screen_active", false) 
            if (lockScreenActive) {
                Log.d(TAG, "Lock screen already active, not showing another for test prayer")
                return false
            }
            
            return true
        }
    
        // First check if lock screen is enabled in settings for this prayer
        val prayerSettings = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE) // Corrected preference file name
        val isLockEnabled = prayerSettings.getBoolean("${prayerName.lowercase()}_lock", true)
        if (!isLockEnabled) {
           Log.d(TAG, "Lock screen disabled in settings for $prayerName")
           return false
        }
    
        // Check if we're already actively showing a lock screen
        val lockScreenActive = prefs.getBoolean("lock_screen_active", false)
        if (lockScreenActive) {
            // Don't trigger another one if one is already active
            Log.d(TAG, "Lock screen already active, not showing another for $prayerName")
            return false
        }

        // Don't show generic prayer time or invalid names
        if (prayerName.equals("Prayer Time", ignoreCase = true) || 
            !isValidPrayerName(prayerName)) {
            Log.d(TAG, "Invalid or generic prayer name: $prayerName, skipping lock screen")
            return false
        }

        // Don't show if prayer is already completed
        if (completionManager.isPrayerComplete(prayerName)) {
            Log.d(TAG, "Prayer $prayerName already completed, skipping lock screen")
            return false
        }

        // Check if this is the same prayer that was just completed
        val lastCompletedPrayer = prefs.getString(KEY_LAST_COMPLETED_PRAYER, "")
        val lastCompletionTime = prefs.getLong(KEY_LAST_PRAYER_COMPLETION, 0)
        if (prayerName == lastCompletedPrayer && 
            System.currentTimeMillis() - lastCompletionTime < UNWANTED_TRIGGER_PREVENTION_MS) {
            Log.d(TAG, "Prayer $prayerName was just completed, preventing unwanted trigger")
            return false
        }

        // Don't show if we're in the middle of showing an ad
        val isShowingAd = prefs.getBoolean(KEY_SHOWING_AD, false)
        if (isShowingAd) {
            Log.d(TAG, "Ad is showing, skipping lock screen")
            return false
        }

        // Prevent rapid state changes
        val lastStateChange = prefs.getLong(KEY_LAST_STATE_CHANGE, 0)
        if (System.currentTimeMillis() - lastStateChange < MIN_STATE_CHANGE_INTERVAL) {
            Log.d(TAG, "Too soon since last state change, skipping lock screen")
            return false
        }

        // Add cooldown period after unlock to prevent immediate reappearance
        val lastUnlockTime = prefs.getLong("last_unlock_time", 0)
        // Increased cooldown from 5 to 15 minutes to prevent frequent reappearances
        val unlockCooldown = 15 * 60 * 1000L // 15 minutes cooldown
        if (System.currentTimeMillis() - lastUnlockTime < unlockCooldown) {
            Log.d(TAG, "Within unlock cooldown period (${(System.currentTimeMillis() - lastUnlockTime) / 1000 / 60} minutes since unlock), skipping lock screen")
            return false
        }
        
        // Add cooldown for PIN verification without full unlock
        val lastPinVerifyTime = prefs.getLong("last_pin_verify_time", 0)
        val pinCooldown = 5 * 60 * 1000L // 5 minutes cooldown after PIN entry
        if (System.currentTimeMillis() - lastPinVerifyTime < pinCooldown) {
            Log.d(TAG, "Within PIN verification cooldown period, skipping lock screen")
            return false
        }

        return true
    }

    private fun isValidPrayerName(prayerName: String): Boolean {
        return when (prayerName.lowercase()) {
            "fajr", "dhuhr", "asr", "maghrib", "isha", "test", "test prayer" -> true
            else -> false
        }
    }

    public suspend fun checkAndQueuePrayers() = withContext(Dispatchers.Default) {
        try {
            // Check for stuck ads first
            if (isAdStuck()) {
                Log.w(TAG, "Detected stuck ad, recovering")
                recoverStuckAd()
            }

            // Validate state before proceeding
            if (!validateState()) {
                Log.w(TAG, "Invalid state detected, attempting recovery")
                recoverFromInvalidState()
            }

            checkDateTransition()

            // Check if there's already an active prayer or ad showing
            val isShowingAd = prefs.getBoolean(KEY_SHOWING_AD, false)
            if (isShowingAd) {
                Log.d(TAG, "Ad is active, skipping check")
                return@withContext
            }

            // Get location and calculate prayer times
            var location = locationManager.getLastLocation()
            var retryCount = 0
            val maxRetries = 3
            
            while (location == null && retryCount < maxRetries) {
                Log.w(TAG, "No location available, retrying (attempt ${retryCount + 1}/$maxRetries)")
                delay(1000) // Wait 1 second before retry
                location = locationManager.getLastLocation()
                retryCount++
            }
            
            if (location == null) {
                Log.e(TAG, "Failed to get location after $maxRetries retries")
                return@withContext
            }

            val prayers = prayerTimeCalculator.calculatePrayerTimes(location)
            if (prayers.isEmpty()) {
                Log.w(TAG, "No prayers calculated")
                return@withContext
            }

            val currentPrayers = mutableListOf<Prayer>()
            val upcomingPrayers = mutableListOf<Prayer>()
            val now = System.currentTimeMillis()

            // Handle first-time launch
            val isFirstLaunch = isFirstTimeLaunch()
            if (isFirstLaunch) {
                Log.d(TAG, "First time launch detected - marking past prayers as missed")
                prayers.forEach { prayer ->
                    if (prayer.time < now) {
                        Log.d(TAG, "Marking past prayer ${prayer.name} as missed (first launch)")
                        completionManager.markPrayerMissed(prayer.name)
                    }
                }
                markFirstLaunchComplete()
            }

            // Process all prayers
            prayers.forEachIndexed { index, prayer ->
                if (!completionManager.isPrayerComplete(prayer.name)) {
                    val nextPrayer = if (index < prayers.size - 1) prayers[index + 1] else null
                    val timeDiff = now - prayer.time
                    
                    when {
                        timeDiff > 0 -> {
                            // Prayer time has passed
                            val status = completionManager.getPrayerCompletionStatus(prayer, nextPrayer)
                            if (status.completionType == CompletionType.PRAYER_MISSED) {
                                // Only mark as missed if we're well past the prayer window
                                val windowEndTime = when (prayer.name) {
                                    "Isha" -> {
                                        val calendar = Calendar.getInstance()
                                        calendar.timeInMillis = prayer.time
                                        calendar.set(Calendar.HOUR_OF_DAY, 23)
                                        calendar.set(Calendar.MINUTE, 55)
                                        calendar.timeInMillis
                                    }
                                    else -> nextPrayer?.time?.minus(5 * 60 * 1000) ?: (prayer.time + 2 * 60 * 60 * 1000)
                                }
                                
                                // Define effectiveWindowEnd
                                val effectiveWindowEnd = windowEndTime
                                
                                if (now > effectiveWindowEnd) {
                                    Log.d(TAG, "Prayer ${prayer.name} is missed - outside prayer window")
                                    completionManager.markPrayerMissed(prayer.name)
                                    PrayerScheduler.clearActiveLockScreen(context)
                                } else {
                                    // Still within prayer window
                                    Log.d(TAG, "Starting lock screen for current prayer ${prayer.name}")
                                    currentPrayers.add(prayer)
                                    startLockScreenForPrayer(prayer.name, prayer.rakaatCount)
                                }
                            } else {
                                // Prayer is still within its window
                                Log.d(TAG, "Starting lock screen for current prayer ${prayer.name}")
                                currentPrayers.add(prayer)
                                startLockScreenForPrayer(prayer.name, prayer.rakaatCount)
                            }
                        }
                        //-timeDiff <= UPCOMING_PRAYER_THRESHOLD_MS -> {
                        //    // Prayer is within 1 minute of starting
                        //    Log.d(TAG, "Starting lock screen for upcoming prayer ${prayer.name}")
                        //    currentPrayers.add(prayer)
                        //    startLockScreenForPrayer(prayer.name, prayer.rakaatCount)
                        //}
                        else -> {
                            // Future prayer
                            Log.d(TAG, "Scheduling ${prayer.name} for future")
                            upcomingPrayers.add(prayer)
                            schedulePrayerIfNeeded(prayer)
                        }
                    }
                } else {
                    Log.d(TAG, "Prayer ${prayer.name} is already completed or missed")
                }
            }

            // Save the prayer queue state
            if (upcomingPrayers.isNotEmpty()) {
                Log.d(TAG, "Saving upcoming prayers: ${upcomingPrayers.joinToString { it.name }}")
                savePrayerQueue(upcomingPrayers.map { Pair(it.name, it.rakaatCount) })
            } else {
                Log.d(TAG, "No upcoming prayers to save")
                savePrayerQueue(emptyList())
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking prayers", e)
            e.printStackTrace()
        }
    }

    private fun schedulePrayerIfNeeded(prayer: Prayer) {
        val settingsManager = PrayerSettingsManager.getInstance(context)
        
        // Check if any features are enabled for this prayer
        val anyFeatureEnabled = settingsManager.isLockEnabled(prayer.name) || 
                               settingsManager.isAdhanEnabled(prayer.name) || 
                               settingsManager.isNotificationEnabled(prayer.name)
        
        if (!anyFeatureEnabled) {
            Log.d(TAG, "All features disabled for ${prayer.name}, skipping scheduling")
            return
        }
        
        // Rest of the method remains the same
        if (prayer.time > System.currentTimeMillis()) {
            Log.d(TAG, "Scheduling ${prayer.name} for future")
            
            // Schedule the prayer event
            val scheduler = PrayerScheduler(context)
            scheduler.schedulePrayerEvents(prayer)
            
            // Add to upcoming prayers
            upcomingPrayers.add(prayer.name)
        } else {
            // Check if it's within the valid completion window
            if (isPrayerTimeWithinValidWindow(prayer)) {
                Log.d(TAG, "${prayer.name} is within valid window, scheduling immediately")
                
                // If in window, schedule immediately
                val scheduler = PrayerScheduler(context)
                scheduler.schedulePrayerEvents(prayer)
                
                // Also add to upcoming prayers
                upcomingPrayers.add(prayer.name)
            } else {
                Log.d(TAG, "Prayer ${prayer.name} is already completed or missed")
            }
        }
    }

    fun clearPrayerState() {
        // Get the current active prayer before clearing
        val currentPrayer = prefs.getString(KEY_ACTIVE_PRAYER, null)
        val isPrayerComplete = prefs.getBoolean("is_prayer_complete", false)
        
        // Don't completely clear the state if this is a completed prayer
        if (isPrayerComplete && !currentPrayer.isNullOrEmpty() && currentPrayer != "Test Prayer" && currentPrayer != "Prayer Time") {
            Log.d(TAG, "Preserving completed prayer info for: $currentPrayer")
            prefs.edit()
                .remove(KEY_SHOWING_AD)
                .putBoolean(KEY_STATE_VALID, true)
                .putString(KEY_ACTIVE_PRAYER, currentPrayer)
                .putBoolean("is_prayer_complete", true)
                .putBoolean("is_test_prayer", false)
                .apply()
        } else {
            // Complete clearing for non-completed or test prayers
            prefs.edit()
                .remove(KEY_ACTIVE_PRAYER)
                .putBoolean(KEY_SHOWING_AD, false)
                .putBoolean(KEY_STATE_VALID, true)
                .apply()
        }

        // Process next prayer in queue if available
        processNextPrayer()
    }

    private fun startLockScreenForPrayer(prayerName: String, rakaatCount: Int) {
        if (!shouldShowLockScreen(prayerName)) {
            Log.d(TAG, "Lock screen conditions not met for prayer: $prayerName")
            return
        }

        try {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastStateChange < MIN_STATE_CHANGE_INTERVAL) {
                return
            }
            lastStateChange = currentTime
            
            // Validate prayer name before launching
            if (!PrayerValidator.isValidPrayerName(prayerName)) {
                Log.w(TAG, "Cannot launch lock screen with invalid prayer name: $prayerName")
                PrayerValidator.markInvalidPrayerData(context, "Invalid name in PrayerStateChecker")
                return
            }

            // Start lock screen service
            val serviceIntent = Intent(context, LockScreenService::class.java).apply {
                putExtra(EXTRA_PRAYER_NAME, prayerName)
                putExtra(EXTRA_RAKAAT_COUNT, rakaatCount)
            }
            context.startService(serviceIntent)

            // Start lock screen activity
            val activityIntent = Intent(context, LockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_PRAYER_NAME, prayerName)
                putExtra(EXTRA_RAKAAT_COUNT, rakaatCount)
            }
            
            // Use PrayerValidator to enhance the intent with any missing data
            val enhancedIntent = PrayerValidator.enhanceLockScreenIntent(activityIntent)
            context.startActivity(enhancedIntent)

            Log.d(TAG, "Started lock screen for prayer: $prayerName")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting lock screen: ${e.message}")
            clearPrayerState()
        }
    }

    private fun savePrayerQueue(prayers: List<Pair<String, Int>>) {
        // Save prayer queue to prefs
        prefs.edit().putString(KEY_QUEUED_PRAYERS, prayers.joinToString { "${it.first},${it.second}" }).apply()
    }

    fun markPrayerShowingAd(prayerName: String) {
        try {
            val currentTime = System.currentTimeMillis()
            
            // First check if we're in a valid state to show an ad
            val lastAdCloseTime = prefs.getLong(KEY_LAST_AD_CLOSE_TIME, 0)
            if (currentTime - lastAdCloseTime < MIN_AD_INTERVAL_MS) {
                Log.d(TAG, "Skipping ad due to minimum interval not met")
                onAdCompleted(prayerName, false)
                return
            }

            // Update all relevant ad states
            prefs.edit()
                .putString(KEY_ACTIVE_PRAYER, prayerName)
                .putBoolean(KEY_SHOWING_AD, true)
                .putLong(KEY_LAST_STATE_CHANGE, currentTime)
                .putLong(KEY_LAST_AD_START, currentTime)
                .putBoolean(KEY_STATE_VALID, true)
                .apply()

            Log.d(TAG, "Marked ad showing for prayer: $prayerName")
        } catch (e: Exception) {
            Log.e(TAG, "Error marking prayer ad state: ${e.message}")
            clearAllStates()
        }
    }

    fun onLockScreenUnlocked(prayerName: String) {
        val activePrayer = prayerQueue.firstOrNull()
        val lastStateChange = prefs.getLong(KEY_LAST_STATE_CHANGE, 0L)
        val isPendingTransition = System.currentTimeMillis() - lastStateChange < TRANSITION_THRESHOLD

        if (activePrayer?.name == prayerName) {
            prefs.edit()
                .putLong(KEY_LAST_STATE_CHANGE, System.currentTimeMillis())
                .putString(KEY_ACTIVE_PRAYER, prayerName)
                .putBoolean(KEY_SHOWING_AD, false)
                .putBoolean(KEY_STATE_VALID, true)
                .putBoolean(KEY_IS_UNLOCK_PENDING, true) // Set unlock pending flag
                .apply()
            
            // Check if ad should be shown
            val currentTime = System.currentTimeMillis()
            val lastAdCloseTime = prefs.getLong(KEY_LAST_AD_CLOSE_TIME, 0)
            if (currentTime - lastAdCloseTime >= MIN_AD_INTERVAL_MS) {
                prefs.edit().putBoolean(KEY_SHOULD_SHOW_AD, true).apply()
            } else {
                prefs.edit().putBoolean(KEY_SHOULD_SHOW_AD, false).apply()
            }

            // Mark prayer as completed
            completionManager.markPrayerComplete(prayerName, PrayerCompletionManager.CompletionType.PRAYER_PERFORMED)

            if (!isPendingTransition) {
                // Only process if we're not in a transition period
                processNextPrayer(prayerName)
            }
        }
    }

    fun onAdClosed(prayerName: String) {
        try {
            if (!validateState()) {
                Log.w(TAG, "Invalid state detected during ad close")
                recoverFromInvalidState()
            }

            // Update last ad close time and clear both flags
            val currentTime = System.currentTimeMillis()
            prefs.edit()
                .putLong(KEY_LAST_STATE_CHANGE, currentTime)
                .putLong(KEY_LAST_AD_CLOSE_TIME, currentTime)
                .putBoolean(KEY_SHOWING_AD, false)
                .putBoolean(KEY_STATE_VALID, true)
                .putBoolean(KEY_PENDING_FAST_TRANSITION, false)
                .putBoolean(KEY_SHOULD_SHOW_AD, false)
                .apply()

            Log.d(TAG, "Ad closed for prayer: $prayerName, next ad available in ${MIN_AD_INTERVAL_MS/1000} seconds")

            // Process next prayer immediately if available
            val isUnlockPending = prefs.getBoolean(KEY_IS_UNLOCK_PENDING, false)
            if (!isUnlockPending) {
                synchronized(prayerQueue) {
                    if (!prayerQueue.isEmpty() && !isProcessingQueue) {
                        val nextPrayer = prayerQueue.removeFirstOrNull()
                        if (nextPrayer != null) {
                            isProcessingQueue = true
                            startLockScreenForPrayer(nextPrayer.name, nextPrayer.rakaatCount)
                            isProcessingQueue = false
                        }
                    }
                }
            } else {
                Log.d(TAG, "Skipping next prayer processing due to pending unlock")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ad close: ${e.message}")
        }
    }

    fun onAdCompleted(prayerName: String, isPendingTransition: Boolean = false) {
        try {
            startCooldownPeriod()
            completionManager.markPrayerComplete(prayerName, PrayerCompletionManager.CompletionType.PRAYER_PERFORMED)

            if (!isPendingTransition) {
                // Only process if we're not in a transition period
                processNextPrayer()
            }

            Log.d(TAG, "Ad completed for prayer: $prayerName")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ad completion: ${e.message}")
            clearAllStates()
        }
    }

    fun onPrayerUnlocked(prayerName: String, isPendingTransition: Boolean = false) {
        Log.d(TAG, "Prayer unlocked: $prayerName")
        
        // Save the unlock time to prevent immediate reappearance
        prefs.edit().putLong("last_unlock_time", System.currentTimeMillis()).apply()
        
        // Mark this prayer as having been unlocked, so it won't be marked as missed
        val receiverPrefs = context.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
        receiverPrefs.edit()
            .putBoolean("${prayerName.lowercase()}_unlocked", true)
            .apply()
        
        if (!isPendingTransition) {
            clearPrayerState()
        }
        
        // Process next prayer if available
        processNextPrayer()
    }

    fun onUnlockTimeUpdated() {
        try {
            val activePrayer = prefs.getString(KEY_ACTIVE_PRAYER, null)
            
            if (activePrayer != null) {
                // Update state
                prefs.edit()
                    .putString(KEY_ACTIVE_PRAYER, activePrayer)
                    .putBoolean(KEY_SHOWING_AD, false)
                    .putBoolean(KEY_STATE_VALID, true)
                    .putLong(KEY_LAST_STATE_CHANGE, System.currentTimeMillis())
                    .apply()
                
                Log.d(TAG, "Updated unlock time for prayer: $activePrayer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating unlock time: ${e.message}")
            clearAllStates()
        }
    }

    private fun isAdStuck(): Boolean {
        try {
            val isShowingAd = prefs.getBoolean(KEY_SHOWING_AD, false)
            val isUnlockPending = prefs.getBoolean(KEY_IS_UNLOCK_PENDING, false)
            if (!isShowingAd) return false

            val lastStateChange = prefs.getLong(KEY_LAST_STATE_CHANGE, 0)
            val lastAdStart = prefs.getLong(KEY_LAST_AD_START, 0)

            if (lastStateChange == 0L && lastAdStart == 0L) return false

            val timeSinceStateChange = System.currentTimeMillis() - lastStateChange
            val timeSinceAdStart = System.currentTimeMillis() - lastAdStart

            // Consider ad stuck if either timeout is exceeded
            return timeSinceStateChange > AD_TIMEOUT_MS || 
                   timeSinceAdStart > AD_TIMEOUT_MS ||
                   (isUnlockPending && timeSinceStateChange > AD_STATE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ad stuck state: ${e.message}")
            return false
        }
    }

    private fun recoverStuckAd() {
        try {
            val prayerName = prefs.getString(KEY_ACTIVE_PRAYER, null)
            Log.d(TAG, "Recovering stuck ad for prayer: $prayerName")

            val currentTime = System.currentTimeMillis()

            // Clear all ad-related states
            prefs.edit().apply {
                putBoolean(KEY_SHOWING_AD, false)
                putBoolean(KEY_SHOULD_SHOW_AD, false)
                putBoolean(KEY_IS_UNLOCK_PENDING, false)
                putLong(KEY_LAST_STATE_CHANGE, currentTime)
                putLong(KEY_LAST_AD_START, 0)
                putLong(KEY_LAST_AD_CLOSE_TIME, currentTime)
                putBoolean(KEY_STATE_VALID, true)
                putBoolean(KEY_PENDING_FAST_TRANSITION, false)
                apply()
            }

            // If we have a prayer name, mark it as complete to prevent getting stuck
            prayerName?.let {
                completionManager.markPrayerComplete(it, PrayerCompletionManager.CompletionType.PRAYER_PERFORMED)
            }

            // Process next prayer if available
            processNextPrayer()
        } catch (e: Exception) {
            Log.e(TAG, "Error recovering stuck ad: ${e.message}")
            clearAllStates() // Fallback to clearing all states if recovery fails
        }
    }

    private fun checkAdState(): Boolean {
        try {
            val isShowingAd = prefs.getBoolean(KEY_SHOWING_AD, false)
            if (!isShowingAd) return true

            val lastStateChange = prefs.getLong(KEY_LAST_STATE_CHANGE, 0L)
            if (lastStateChange == 0L) return true

            return System.currentTimeMillis() - lastStateChange <= AD_TIMEOUT_MS
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ad state: ${e.message}")
            return true
        }
    }

    private fun processNextPrayer(prayerName: String? = null) {
        synchronized(prayerQueue) {
            if (prayerQueue.isEmpty() || isProcessingQueue) return

            isProcessingQueue = true
            val nextPrayer = prayerQueue.removeFirstOrNull() ?: return

            if (prayerName != null && nextPrayer.name != prayerName) {
                // Put it back if it's not the correct prayer
                prayerQueue.add(0, nextPrayer)
                return
            }

            prefs.edit()
                .putString(KEY_ACTIVE_PRAYER, nextPrayer.name)
                .putBoolean(KEY_PENDING_FAST_TRANSITION, true)
                .apply()

            startLockScreenForPrayer(nextPrayer.name, nextPrayer.rakaatCount)
            isProcessingQueue = false
        }
    }

    fun onPrayerCompleted(prayerName: String) {
        try {
            startCooldownPeriod()
            completionManager.markPrayerComplete(prayerName, PrayerCompletionManager.CompletionType.PRAYER_PERFORMED)
            
            // Mark this prayer as having been unlocked, so it won't be marked as missed
            val receiverPrefs = context.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            receiverPrefs.edit()
                .putBoolean("${prayerName.lowercase()}_unlocked", true)
                .apply()
            
            prefs.edit()
                .putLong(KEY_LAST_PRAYER_COMPLETION, System.currentTimeMillis())
                .putString(KEY_LAST_COMPLETED_PRAYER, prayerName)
                .putBoolean(KEY_IS_UNLOCK_PENDING, false)
                .putBoolean(KEY_SHOWING_AD, false)
                .putString(KEY_ACTIVE_PRAYER, prayerName)
                .putBoolean("is_prayer_complete", true)
                .apply()

            // Preserve the prayer name when clearing other prayer state
            preserveCompletedPrayer(prayerName)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPrayerCompleted: ${e.message}")
        }
    }

    // Add new method to preserve completed prayer information
    private fun preserveCompletedPrayer(prayerName: String) {
        // Clear most prayer state but keep the name and completion status
        prefs.edit()
            .remove(KEY_SHOWING_AD)
            .remove(KEY_IS_UNLOCK_PENDING)
            .putBoolean(KEY_STATE_VALID, true)
            .putString(KEY_ACTIVE_PRAYER, prayerName)
            .putBoolean("is_prayer_complete", true)
            .apply()
    }

    fun saveAdState(shouldShowAd: Boolean) {
        prefs.edit().apply {
            putBoolean(KEY_SHOULD_SHOW_AD, shouldShowAd)
            putLong(KEY_LAST_STATE_CHANGE, System.currentTimeMillis())
            apply()
        }
    }

    fun shouldShowAdOnResume(): Boolean {
        try {
            // Check both unlock pending and should show ad flags
            val isUnlockPending = prefs.getBoolean(KEY_IS_UNLOCK_PENDING, false)
            val shouldShowAd = prefs.getBoolean(KEY_SHOULD_SHOW_AD, false)
            
            if (isUnlockPending && shouldShowAd) {
                // Only clear the unlock pending flag, keep should show ad flag
                prefs.edit().putBoolean(KEY_IS_UNLOCK_PENDING, false).apply()
                Log.d(TAG, "Showing ad after lock screen unlock")
                return true
            }

            Log.d(TAG, "No pending lock screen unlock, skipping ad")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ad state on resume: ${e.message}")
            return false
        }
    }

    fun getLocationManager(): com.viperdam.kidsprayer.prayer.LocationManager {
        return locationManager
    }

    fun getPrayerTimeCalculator(): PrayerTimeCalculator {
        return prayerTimeCalculator
    }

    fun isPrayerComplete(prayerName: String): Boolean {
        return completionManager.isPrayerComplete(prayerName)
    }

    /**
     * Marks a prayer as auto-missed when its window ends and the lockscreen is still active
     */
    private fun markPrayerAsAutoMissed(prayerName: String) {
        try {
            // Mark the prayer as missed
            completionManager.markPrayerMissed(prayerName)
            
            // Update receiver preferences
            val receiverPrefs = context.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            receiverPrefs.edit()
                .putBoolean("lock_screen_active", false)
                .putBoolean("is_unlocked", true)
                .putLong("last_unlock_time", System.currentTimeMillis())
                .putBoolean("auto_missed", true)
                .putString("last_missed_prayer", prayerName)
                .apply()
            
            // Send broadcast to notify any active lock screens
            val intent = Intent("com.viperdam.kidsprayer.AUTO_UNLOCK")
            intent.putExtra("prayer_name", prayerName)
            intent.putExtra("auto_missed", true)
            context.sendBroadcast(intent)
            
            // Clear any active lock screens
            PrayerScheduler.clearActiveLockScreen(context)
            
            Log.d(TAG, "Prayer $prayerName auto-marked as missed at window end")
        } catch (e: Exception) {
            Log.e(TAG, "Error auto-marking prayer as missed", e)
        }
    }

    private fun isPrayerTimeWithinValidWindow(prayer: Prayer): Boolean {
        // Check if the prayer time is within a valid window for completion
        val currentTime = System.currentTimeMillis()
        val timeDifference = currentTime - prayer.time
        
        // Define window based on prayer
        val windowDuration = when (prayer.name.lowercase()) {
            "isha" -> 4 * 60 * 60 * 1000L // 4 hours for Isha
            else -> 2 * 60 * 60 * 1000L // 2 hours for other prayers
        }
        
        // If we're within the window duration, return true
        return timeDifference >= 0 && timeDifference <= windowDuration
    }
}
