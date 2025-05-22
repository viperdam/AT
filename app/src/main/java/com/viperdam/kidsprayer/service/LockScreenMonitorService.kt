package com.viperdam.kidsprayer.service

import android.app.NotificationChannel
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.ads.AdManager
import com.viperdam.kidsprayer.model.Prayer
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.prayer.PrayerTimeCalculator
import com.viperdam.kidsprayer.prayer.LocationManager
import com.viperdam.kidsprayer.service.PrayerScheduler
import com.viperdam.kidsprayer.ui.lock.LockScreenActivity
import com.viperdam.kidsprayer.ui.lock.ads.LockScreenAds
import com.viperdam.kidsprayer.utils.PrayerValidator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import javax.inject.Inject
import kotlin.concurrent.thread
import java.util.Timer
import java.util.TimerTask
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.viperdam.kidsprayer.util.PrayerSettingsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@AndroidEntryPoint
class LockScreenMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val activeCheckInterval = 2000L // Check every 2 seconds when active (was 500L)
    private val inactiveCheckInterval = 15000L // Check every 15 seconds when inactive (was 5000L)
    private val cooldownCheckInterval = 30000L // Check every 30 seconds during cooldown (was 15000L)
    private val currentPrayerCheckInterval = 15 * 60 * 1000L // Check for current prayer every 15 minutes (was 5 minutes)
    private var currentCheckInterval = activeCheckInterval
    private var isRunning = false
    private var consecutiveInvisibleChecks = 0
    private var lastRelaunchAttempt = 0L
    private var lastUnlockState = false
    private var consecutiveInactiveChecks = 0
    private var lastCurrentPrayerCheck = 0L
    private var lastLockScreenActivation = 0L
    private val currentPrayerLockActivationCooldown = 30000L // 30 seconds cooldown between activations (was 10000L)
    private var isProcessingCurrentPrayer = false // Flag to prevent race conditions
    private var cachedPrayerTimes: List<Prayer> = emptyList() // Cache for calculated prayer times
    private var lastPrayerCalculationTime = 0L // Track when prayer times were last calculated
    private val prayerTimeValidityCacheTime = 6 * 60 * 60 * 1000L // Cache prayer times for 6 hours
    // Add backoff mechanism to prevent excessive checks
    private var bypassDetectionCount = 0
    private val maxBypassDetectionCount = 3
    private val bypassDetectionCooldown = 300000L // 5 minutes cooldown after multiple detections
    private var bypassDetectionCooldownEndTime = 0L
    
    // Add variables to track the active prayer
    private var currentActivePrayerName: String? = null
    private var currentActiveRakaatCount: Int = 0

    @Inject
    lateinit var adManager: AdManager
    
    @Inject
    lateinit var lockScreenAds: LockScreenAds
    
    @Inject
    lateinit var prayerTimeCalculator: PrayerTimeCalculator
    
    @Inject
    lateinit var locationManager: LocationManager
    
    @Inject
    lateinit var completionManager: PrayerCompletionManager

    @Inject
    lateinit var settingsManager: PrayerSettingsManager

    // Add timer for bypass checks
    private var bypassCheckTimer: Timer? = null

    // Define the CoroutineScope
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "LockScreenMonitor"
        private const val NOTIFICATION_CHANNEL_ID = "prayer_lock_monitor"
        private const val NOTIFICATION_ID = 3000
        private const val MAX_INVISIBLE_CHECKS = 5
        private const val MIN_RELAUNCH_INTERVAL = 120000L // Increased to 2 minutes (was 60 seconds)
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            val isLockScreenActive = prefs.getBoolean("lock_screen_active", false)
            val isPinVerified = prefs.getBoolean("pin_verified", false)
            val isUnlocked = prefs.getBoolean("is_unlocked", false)
            val isDisplayingAd = prefs.getBoolean("is_displaying_ad", false)
            val lastUnlockTime = prefs.getLong("last_unlock_time", 0)
            val lastStoppedTime = prefs.getLong("lock_screen_last_stopped", 0)
            val isPrayerComplete = prefs.getBoolean("is_prayer_complete", false)
            val activePrayer = prefs.getString("active_prayer", "none")
            val isTestPrayer = prefs.getBoolean("is_test_prayer", false)
            
            // Store active prayer information for quick restoration
            if (isLockScreenActive && activePrayer != "none") {
                currentActivePrayerName = activePrayer
                currentActiveRakaatCount = prefs.getInt("active_rakaat_count", 4)
            }
            
            // Only log active prayer details once a minute to avoid log spam
            if (System.currentTimeMillis() % 60000 < 1000) {
                Log.d(TAG, "Prayer state: active_prayer=$activePrayer, is_test_prayer=$isTestPrayer, is_prayer_complete=$isPrayerComplete, pin_verified=$isPinVerified, is_unlocked=$isUnlocked")
            }
            
            // Check for transition from locked to unlocked state
            // Only request an ad if this is a genuine unlock event (pin verified OR prayer completed)
            if (!lastUnlockState && isUnlocked && (isPinVerified || isPrayerComplete) && !isDisplayingAd) {
                Log.d(TAG, "Detected genuine unlock event, requesting delayed ad")
                // If we detect the lockscreen was just unlocked and we're not showing an ad,
                // request a delayed ad to be shown in MainActivity
                if (adManager.isRewardedAdAvailable()) {
                    lockScreenAds.requestDelayedAd()
                }
            }
            lastUnlockState = isUnlocked
            
            // Check if lock screen is visible (moved this before the bypass check)
            val isLockScreenVisible = isActivityForeground("LockScreenActivity")
            
            // Check if we're in the unlocking grace period (30 seconds after last unlock)
            val isInUnlockGracePeriod = System.currentTimeMillis() - lastUnlockTime < 30000
            
            // Check for possible bypass attempt (screen stopped but not properly completed)
            val possibleBypassAttempt = isLockScreenActive && 
                !isPinVerified && 
                !isUnlocked && 
                !isPrayerComplete &&
                !isDisplayingAd &&
                !isInUnlockGracePeriod &&
                (lastStoppedTime > 0 && (System.currentTimeMillis() - lastStoppedTime) < 10000 || // Within last 10 seconds
                !isLockScreenVisible) // OR screen is not visible right now
            
            // Check if we're in bypass detection cooldown
            val now = System.currentTimeMillis()
            val inBypassCooldown = now < bypassDetectionCooldownEndTime
            
            if (possibleBypassAttempt && !inBypassCooldown) {
                Log.w(TAG, "Detected possible bypass attempt - lock screen was stopped or is not visible but not completed")
                
                // Check for signs of legitimate dismissal that might have been missed
                val legitimateDismissal = prefs.getBoolean("legitimate_dismissal", false)
                val recentPrayerCompletion = System.currentTimeMillis() - prefs.getLong("prayer_completion_time", 0) < 30000
                
                if (legitimateDismissal || recentPrayerCompletion) {
                    Log.d(TAG, "Ignoring false positive bypass - detected legitimate dismissal")
                    // Clear the active prayer tracking
                    prefs.edit()
                        .putBoolean("lock_screen_active", false)
                        .putBoolean("legitimate_dismissal", false)
                        .apply()
                    // Reset bypass counters on legitimate dismissal
                    bypassDetectionCount = 0
                } else {
                    // Immediate recovery for bypass attempts
                    if (currentActivePrayerName != null) {
                        Log.d(TAG, "Immediate recovery with stored prayer: $currentActivePrayerName ($currentActiveRakaatCount rakaats)")
                        bypassDetectionCount++
                        
                        // Check completion status before relaunching
                        if (!completionManager.isPrayerComplete(currentActivePrayerName ?: "")) {
                            // Only attempt relaunch if not too many attempts in short succession
                            if (now - lastRelaunchAttempt >= MIN_RELAUNCH_INTERVAL) {
                                lastRelaunchAttempt = now // Register this relaunch attempt
                                launchLockScreenForPrayer(currentActivePrayerName, currentActiveRakaatCount, "bypass_immediate_recovery")
                            } else {
                                Log.d(TAG, "Too soon since last launch (${(now - lastRelaunchAttempt)/1000}s < ${MIN_RELAUNCH_INTERVAL/1000}s), skipping bypass recovery")
                            }
                        } else {
                             Log.d(TAG, "Prayer '$currentActivePrayerName' already completed, skipping bypass recovery relaunch.")
                        }
                    } else {
                        Log.d(TAG, "Immediate recovery for current active prayer: $activePrayer")
                        bypassDetectionCount++
                        
                        // Check completion status before relaunching
                         if (!completionManager.isPrayerComplete(activePrayer ?: "")) {
                            // Only attempt relaunch if not too many attempts in short succession
                            if (now - lastRelaunchAttempt >= MIN_RELAUNCH_INTERVAL) {
                                lastRelaunchAttempt = now // Register this relaunch attempt
                                val rakaatCount = prefs.getInt("active_rakaat_count", getRakaatCountForPrayer(activePrayer ?: "Prayer Time"))
                                launchLockScreenForPrayer(activePrayer ?: "Prayer Time", rakaatCount, "bypass_immediate_recovery")
                            } else {
                                Log.d(TAG, "Too soon since last launch (${(now - lastRelaunchAttempt)/1000}s < ${MIN_RELAUNCH_INTERVAL/1000}s), skipping bypass recovery")
                            }
                         } else {
                            Log.d(TAG, "Prayer '$activePrayer' already completed, skipping bypass recovery relaunch.")
                         }
                    }
                    
                    // If we've had too many bypass detection attempts, enter cooldown period
                    if (bypassDetectionCount >= maxBypassDetectionCount) {
                        Log.w(TAG, "Reached max bypass detection count ($maxBypassDetectionCount), entering cooldown period of ${bypassDetectionCooldown/1000} seconds")
                        bypassDetectionCooldownEndTime = now + bypassDetectionCooldown
                        bypassDetectionCount = 0
                        // Slow down checking during cooldown
                        currentCheckInterval = cooldownCheckInterval
                    }
                }
            } else if (inBypassCooldown && possibleBypassAttempt) {
                // Log that we're skipping due to cooldown with time remaining
                val cooldownRemaining = (bypassDetectionCooldownEndTime - now) / 1000
                if (cooldownRemaining % 30 == 0L) { // Only log once every 30 seconds
                    Log.d(TAG, "Bypass detection in cooldown period for ${cooldownRemaining}s, skipping recovery")
                }
            } else if (!possibleBypassAttempt) {
                // Reset bypass counters when conditions are normal
                bypassDetectionCount = 0
            }
            
            // Use PrayerValidator to check for invalid prayer data
            val isInInvalidPrayerCooldown = PrayerValidator.isInInvalidPrayerCooldown(applicationContext)
            
            if (isInInvalidPrayerCooldown) {
                // Only log this message when the remaining time changes by at least 5 seconds
                val lastInvalidPrayerTime = prefs.getLong("last_invalid_prayer_time", 0)
                val cooldownRemaining = (60000 - (System.currentTimeMillis() - lastInvalidPrayerTime)) / 1000
                val lastInvalidReason = prefs.getString("last_invalid_reason", "unknown")
                
                // Only log the cooldown message occasionally to reduce log spam
                if (cooldownRemaining % 10 == 0L) {
                    Log.d(TAG, "Invalid prayer data flag is set (reason: $lastInvalidReason), cooldown remaining: ${cooldownRemaining}s")
                }
            }
            
            // Clear the invalid prayer flag after the cooldown
            if (prefs.getBoolean("invalid_prayer_data", false) && !isInInvalidPrayerCooldown) {
                Log.d(TAG, "Invalid prayer cooldown expired, clearing flag")
                prefs.edit().putBoolean("invalid_prayer_data", false).apply()
            }
            
            // Track consecutive inactive checks to progressively slow down polling
            consecutiveInactiveChecks++
            
            // Adjust the check interval based on activity
            if (isLockScreenActive && !isUnlocked && !isDisplayingAd) {
                // Check if the lock screen should be visible but isn't
                if (!isLockScreenVisible) {
                    // Add check here too:
                    if (!isDisplayingAd) {
                        consecutiveInvisibleChecks++
                        if (consecutiveInvisibleChecks >= MAX_INVISIBLE_CHECKS) {
                            val currentNow = System.currentTimeMillis()
                            // Determine prayer name to check
                            val prayerToCheck = currentActivePrayerName ?: activePrayer ?: ""
                            
                            // Check completion status before visibility restore
                            if (!completionManager.isPrayerComplete(prayerToCheck)) {
                                if (currentNow - lastRelaunchAttempt >= MIN_RELAUNCH_INTERVAL) {
                                    lastRelaunchAttempt = currentNow // Register this attempt
                                    // If we have stored current prayer info, use it for restoration
                                    if (currentActivePrayerName != null) {
                                        Log.d(TAG, "Restoring lock screen with stored prayer: $currentActivePrayerName ($currentActiveRakaatCount rakaats)")
                                        launchLockScreenForPrayer(currentActivePrayerName, currentActiveRakaatCount, "visibility_restore")
                                    } else {
                                        Log.d(TAG, "Restoring lock screen for current active prayer: $activePrayer")
                                        val rakaatCount = prefs.getInt("active_rakaat_count", getRakaatCountForPrayer(activePrayer ?: "Prayer Time"))
                                        launchLockScreenForPrayer(activePrayer ?: "Prayer Time", rakaatCount, "visibility_restore")
                                    }
                                    consecutiveInvisibleChecks = 0 // Reset after successful launch
                                } else {
                                    Log.d(TAG, "Too soon to restore visibility (${(currentNow - lastRelaunchAttempt)/1000}s < ${MIN_RELAUNCH_INTERVAL/1000}s), waiting")
                                }
                             } else {
                                Log.d(TAG, "Prayer '$prayerToCheck' already completed, skipping visibility restore relaunch.")
                                consecutiveInvisibleChecks = 0 // Reset counter as prayer is done
                            }
                        }
                    } else {
                        // If ad is displaying, don't count it as invisible/bypass
                        Log.d(TAG, "Lock screen not visible, but ad is active. Resetting invisible checks.")
                        consecutiveInvisibleChecks = 0
                    }
                } else {
                    consecutiveInvisibleChecks = 0
                }

                // Active monitoring but with progressive backoff
                if (consecutiveInactiveChecks < 10) {
                    currentCheckInterval = activeCheckInterval
                } else if (consecutiveInactiveChecks < 30) {
                    currentCheckInterval = inactiveCheckInterval
                } else {
                    currentCheckInterval = cooldownCheckInterval
                }
            } else {
                consecutiveInvisibleChecks = 0
                // Inactive monitoring - use longer intervals
                currentCheckInterval = inactiveCheckInterval
            }
            
            // If in cooldown, always use cooldown interval
            if (inBypassCooldown) {
                currentCheckInterval = cooldownCheckInterval
            }
            
            // Check for current prayer time even if lock screen isn't currently active
            // Only do this check periodically to save resources
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCurrentPrayerCheck >= currentPrayerCheckInterval && !isProcessingCurrentPrayer) {
                lastCurrentPrayerCheck = currentTime
                checkCurrentPrayerTime()
            }
            
            // Log detailed reason why we're not checking for lock screen visibility
            // but do it less frequently to reduce log spam
            if (consecutiveInactiveChecks % 5 == 1) {
                val reason = when {
                    !isLockScreenActive -> "lock screen not active"
                    isPinVerified -> "pin is verified"
                    isUnlocked -> "screen is unlocked"
                    isDisplayingAd -> "displaying an ad"
                    isInUnlockGracePeriod -> "in unlock grace period"
                    prefs.getBoolean("invalid_prayer_data", false) && isInInvalidPrayerCooldown -> "in invalid prayer cooldown"
                    else -> "unknown reason"
                }
                
                // Add more detailed debug info for troubleshooting
                Log.d(TAG, "Not checking lock screen visibility: $reason (active_prayer=$activePrayer, is_test_prayer=$isTestPrayer, pin_verified=$isPinVerified, is_unlocked=$isUnlocked)")
            }

            // Schedule the next check with variable interval
            handler.postDelayed(this, currentCheckInterval)
        }
    }
    
    /**
     * Checks if there's a current prayer time that should have lock screen active
     * and activates it if needed and allowed by settings
     */
    private fun checkCurrentPrayerTime() {
        // Early exit if already processing
        if (isProcessingCurrentPrayer) {
            return
        }

        // Launch a coroutine for potentially long-running operations
        monitorScope.launch(Dispatchers.IO) { // Use the defined scope
            isProcessingCurrentPrayer = true
            try {
                val currentTime = System.currentTimeMillis()
                val location = locationManager.getLastLocation()

                if (location == null) {
                    Log.w(TAG, "Location not available, cannot check current prayer time.")
                    return@launch
                }

                // Use cached times if available and valid, otherwise recalculate
                 val prayerTimes = if (cachedPrayerTimes.isNotEmpty() && 
                     System.currentTimeMillis() - lastPrayerCalculationTime < prayerTimeValidityCacheTime &&
                     !hasDateChanged(lastPrayerCalculationTime, currentTime)) {
                     cachedPrayerTimes
                 } else {
                     val newPrayerTimes = prayerTimeCalculator.calculatePrayerTimes(location)
                     cachedPrayerTimes = newPrayerTimes
                     lastPrayerCalculationTime = currentTime
                     newPrayerTimes
                 }

                if (prayerTimes.isEmpty()) {
                    Log.d(TAG, "No prayer times available for checkCurrentPrayerTime")
                    return@launch
                }

                // Revert to logic similar to findCurrentPrayer to determine the current prayer window
                val currentPrayer = findCurrentPrayer(prayerTimes, currentTime) 

                if (currentPrayer == null) {
                    //Log.d(TAG, "No current prayer active based on calculated times.")
                    return@launch
                }

                // Check if lock screen is enabled for this prayer
                if (!settingsManager.isLockEnabled(currentPrayer.name)) {
                    Log.d(TAG, "Lock screen disabled for current prayer: ${currentPrayer.name}")
                    return@launch
                }
                
                // Check if this specific prayer event has already been completed
                if (completionManager.isPrayerComplete(currentPrayer.name)) {
                    Log.d(TAG, "Current prayer ${currentPrayer.name} is already marked as complete. Not launching lock screen.")
                    return@launch // Exit if already completed
                }

                // Check if lock screen was already activated recently
                if (currentTime - lastLockScreenActivation < MIN_RELAUNCH_INTERVAL) {
                    Log.d(TAG, "Lock screen activated too recently, skipping check.")
                    return@launch
                }
                
                // Check if the lock screen is already active for this specific prayer
                val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("lock_screen_active", false)) {
                    val activePrayer = prefs.getString("active_prayer", null)
                    
                    // If active prayer is the same as current prayer, no need to show another lock screen
                    if (activePrayer == currentPrayer.name) {
                        Log.d(TAG, "Lock screen already active for ${currentPrayer.name}, not activating again")
                        isProcessingCurrentPrayer = false
                        return@launch
                    }
                }
                
                // All checks passed, launch lock screen for current prayer
                Log.d(TAG, "Launching lock screen for current prayer: ${currentPrayer.name}")
                lastLockScreenActivation = currentTime
                
                // Mark as shown immediately to prevent race conditions
                settingsManager.markEventOccurred(PrayerSettingsManager.EVENT_LOCKSCREEN_SHOWN, currentPrayer.name, currentPrayer.time)
                
                // Launch the lock screen
                launchLockScreenForPrayer(currentPrayer.name, currentPrayer.rakaatCount, "current_prayer")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error checking current prayer time", e)
            } finally {
                isProcessingCurrentPrayer = false
            }
        }
    }
    
    /**
     * Find the current prayer based on the current time
     * If no prayer is currently active, returns null
     */
    private fun findCurrentPrayer(prayers: List<Prayer>, currentTime: Long): Prayer? {
        for (i in 0 until prayers.size) {
            val prayer = prayers[i]
            val nextPrayer = if (i < prayers.size - 1) prayers[i + 1] else null
            
            // Calculate the end time for this prayer's window
            val windowEndTime = if (prayer.name.equals("Isha", ignoreCase = true)) {
                // For Isha, window extends until midnight
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = prayer.time
                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.timeInMillis
            } else if (nextPrayer != null) {
                // Window extends until 15 minutes before next prayer
                nextPrayer.time - (15 * 60 * 1000)
            } else {
                // For the last prayer, window extends for 2 hours
                prayer.time + (2 * 60 * 60 * 1000)
            }
            
            // Check if current time falls within this prayer's window
            if (currentTime >= prayer.time && currentTime <= windowEndTime) {
                return prayer
            }
        }
        return null
    }
    
    /**
     * Gets the current prayer based on the actual time
     */
    private suspend fun getCurrentPrayer(): Prayer? {
        try {
            val now = System.currentTimeMillis()
            
            // Check if we need to recalculate prayer times
            if (cachedPrayerTimes.isEmpty() || 
                now - lastPrayerCalculationTime > prayerTimeValidityCacheTime ||
                hasDateChanged(lastPrayerCalculationTime, now)) {
                
                val location = locationManager.getLastLocation() ?: return null
                Log.d(TAG, "Recalculating prayer times - cache expired or empty")
                cachedPrayerTimes = prayerTimeCalculator.calculatePrayerTimes(location)
                lastPrayerCalculationTime = now
            } else {
                Log.d(TAG, "Using cached prayer times from ${(now - lastPrayerCalculationTime) / 1000 / 60} minutes ago")
            }
            
            if (cachedPrayerTimes.isEmpty()) return null
            
            // Find the current prayer
            for (i in 0 until cachedPrayerTimes.size) {
                val prayer = cachedPrayerTimes[i]
                val nextPrayer = if (i < cachedPrayerTimes.size - 1) cachedPrayerTimes[i + 1] else null
                
                // Calculate window end time
                val windowEndTime = if (prayer.name == "Isha") {
                    // For Isha prayer, window extends until 23:55
                    val calendar = Calendar.getInstance()
                    calendar.timeInMillis = prayer.time
                    calendar.set(Calendar.HOUR_OF_DAY, 23)
                    calendar.set(Calendar.MINUTE, 55)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.timeInMillis
                } else if (nextPrayer != null) {
                    // Window extends until 20 minutes before next prayer
                    nextPrayer.time - (20 * 60 * 1000)
                } else {
                    // For the last prayer, window extends for 2 hours
                    prayer.time + (2 * 60 * 60 * 1000)
                }
                
                // Check if current time is within this prayer's window
                if (now >= prayer.time && now <= windowEndTime) {
                    // Check if we're very close to the window end (within 1 minute)
                    if ((windowEndTime - now) < 60 * 1000) {
                        // Window is about to end, check if lockscreen is still active
                        val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
                        val isLockScreenActive = prefs.getBoolean("lock_screen_active", false)
                        val isPinVerified = prefs.getBoolean("pin_verified", false)
                        val isPrayerComplete = prefs.getBoolean("is_prayer_complete", false)
                        
                        if (isLockScreenActive && !isPinVerified && !isPrayerComplete) {
                            // Time to auto-unlock and mark as missed
                            Log.d(TAG, "Prayer window for ${prayer.name} is ending - auto-unlocking and marking as missed")
                            markPrayerMissedAndUnlock(prayer.name)
                        }
                    }
                    return prayer
                }
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current prayer", e)
            return null
        }
    }
    
    /**
     * Utility function to check if the date has changed between two timestamps
     */
    private fun hasDateChanged(time1: Long, time2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = time1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = time2 }
        
        return cal1.get(Calendar.YEAR) != cal2.get(Calendar.YEAR) ||
               cal1.get(Calendar.MONTH) != cal2.get(Calendar.MONTH) ||
               cal1.get(Calendar.DAY_OF_MONTH) != cal2.get(Calendar.DAY_OF_MONTH)
    }
    
    /**
     * Gets the default rakaat count for a prayer
     */
    private fun getRakaatCountForPrayer(prayerName: String): Int {
        return when (prayerName.lowercase()) {
            "fajr" -> 2
            "dhuhr" -> 4
            "asr" -> 4
            "maghrib" -> 3
            "isha" -> 4
            "test prayer" -> 4
            else -> 4 // Default
        }
    }
    
    /**
     * Launches the lock screen activity for a specific prayer
     */
    private fun launchLockScreenForPrayer(prayerName: String?, rakaatCount: Int, reason: String = "unknown") {
        try {
            // Early validation - don't launch with null prayer name
            if (prayerName == null || prayerName.isEmpty()) {
                Log.e(TAG, "Null or empty prayer name, refusing to launch lock screen")
                return
            }
            
            // Verify this is a valid prayer name
            if (!PrayerValidator.isValidPrayerName(prayerName) && prayerName != "Test Prayer") {
                Log.e(TAG, "Invalid prayer name: $prayerName, not launching lock screen")
                return
            }
            
            // Don't launch if we're in invalid prayer data cooldown
            if (PrayerValidator.isInInvalidPrayerCooldown(applicationContext)) {
                Log.d(TAG, "In invalid prayer data cooldown, skipping launch")
                return
            }
            
            // Prevent rapid relaunches
            val now = System.currentTimeMillis()
            if (now - lastRelaunchAttempt < MIN_RELAUNCH_INTERVAL) {
                Log.d(TAG, "Too soon since last launch (${(now - lastRelaunchAttempt)/1000}s < ${MIN_RELAUNCH_INTERVAL/1000}s), skipping")
                return
            }
            
            // Check if this prayer's lock screen is enabled (only check for non-test prayers)
            if (prayerName != "Test Prayer" && !settingsManager.isLockEnabled(prayerName)) {
                Log.d(TAG, "Lock screen disabled for $prayerName, not launching")
                return
            }
            
            // Check if activity for this prayer is already in foreground
            if (isActivityForeground("LockScreenActivity")) {
                val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
                val currentPrayer = prefs.getString("active_prayer", null)
                
                if (currentPrayer == prayerName) {
                    Log.d(TAG, "Lock screen for $prayerName already in foreground, not launching again")
                    return
                }
            }
            
            Log.d(TAG, "Launching lock screen for prayer: $prayerName with $rakaatCount rakaats, reason: $reason")
            
            val intent = Intent(applicationContext, LockScreenActivity::class.java).apply {
                // Set proper flags to show over lock screen
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                
                putExtra("prayer_name", prayerName)
                putExtra("rakaat_count", rakaatCount)
                putExtra("prayer_time", System.currentTimeMillis())
                putExtra("monitor_relaunch", true)
                putExtra("launch_reason", reason)
            }
            
            // Update the last relaunch attempt time
            lastRelaunchAttempt = System.currentTimeMillis()
            
            // Start the activity
            applicationContext.startActivity(intent)
            
            // Reset check interval to active mode
            currentCheckInterval = activeCheckInterval
            consecutiveInactiveChecks = 0
            
            // Log analytics event for lock screen launch
            val params = Bundle().apply {
                putString("prayer_name", prayerName)
                putInt("rakaat_count", rakaatCount)
                putString("launch_reason", reason)
            }
            FirebaseAnalytics.getInstance(this).logEvent("lock_screen_launched", params)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error launching lock screen", e)
        }
    }
    
    /**
     * Mark a prayer as missed and auto-unlock the lockscreen
     */
    private fun markPrayerMissedAndUnlock(prayerName: String) {
        try {
            // Mark prayer as missed
            completionManager.markPrayerMissed(prayerName)
            
            // Auto-unlock the lockscreen
            val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("lock_screen_active", false)
                .putBoolean("is_unlocked", true)
                .putLong("last_unlock_time", System.currentTimeMillis())
                .putBoolean("auto_missed", true)  // Flag to indicate this was auto-missed
                .putString("last_missed_prayer", prayerName)
                .apply()
            
            // Stop the lock screen service
            this@LockScreenMonitorService.stopService(Intent(this@LockScreenMonitorService, LockScreenService::class.java))
            
            // Send broadcast to close any open lock screen
            val intent = Intent("com.viperdam.kidsprayer.AUTO_UNLOCK")
            intent.putExtra("prayer_name", prayerName)
            intent.putExtra("auto_missed", true)
            this@LockScreenMonitorService.sendBroadcast(intent)
            
            Log.d(TAG, "Successfully marked prayer $prayerName as missed and auto-unlocked")
        } catch (e: Exception) {
            Log.e(TAG, "Error auto-unlocking missed prayer", e)
        }
    }
    
    private fun isActivityForeground(activityName: String): Boolean {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            // Try the modern approach first
            val runningAppProcesses = activityManager.runningAppProcesses
            val isForegroundProcess = runningAppProcesses?.any { 
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && 
                it.processName == packageName 
            } ?: false
            
            if (isForegroundProcess) {
                // Check what activity is in foreground
                @Suppress("DEPRECATION")
                val tasks = activityManager.getRunningTasks(1)
                if (!tasks.isNullOrEmpty()) {
                    val topActivity = tasks[0].topActivity
                    return topActivity?.className?.contains(activityName) == true
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking foreground activity", e)
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground()
        
        // Start bypass check timer
        startBypassCheckTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            
            // Check initial state to set appropriate check interval
            val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            val isLockScreenActive = prefs.getBoolean("lock_screen_active", false)
            val isPinVerified = prefs.getBoolean("pin_verified", false)
            val isUnlocked = prefs.getBoolean("is_unlocked", false)
            val isInvalidPrayerCooldown = PrayerValidator.isInInvalidPrayerCooldown(applicationContext)
            
            // Set initial check interval based on state
            currentCheckInterval = when {
                isInvalidPrayerCooldown -> cooldownCheckInterval
                !isLockScreenActive || isPinVerified || isUnlocked -> inactiveCheckInterval
                else -> activeCheckInterval
            }
            
            Log.d(TAG, "Starting lock screen monitor service with initial check interval: $currentCheckInterval ms")
            handler.post(monitorRunnable)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(monitorRunnable)
        monitorScope.cancel() // Cancel the scope when the service is destroyed
        super.onDestroy()
        
        // Stop bypass check timer
        bypassCheckTimer?.cancel()
        bypassCheckTimer = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Prayer Lock Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors prayer lock screen state"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Prayer Lock Active")
            .setContentText("Monitoring prayer lock screen")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // Add bypass detection functionality
    private fun checkForBypass() {
        try {
            // Check if there was a very recent unlock first
            val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            val currentTime = System.currentTimeMillis()
            val veryRecentUnlock = prefs.getLong("very_recent_unlock", 0L)
            if (currentTime - veryRecentUnlock < 30000) { // 30 seconds
                // Log.d(TAG, "CheckForBypass: Very recent unlock detected (${currentTime - veryRecentUnlock}ms ago), skipping check")
                return // No need to check further if recently unlocked
            }
            
            if (completionManager.detectBypass()) { 
                Log.w(TAG, "Monitor service detected potential lockscreen bypass via detectBypass()")
                
                // Get the most recent valid prayer intended for the lock screen
                val (lastPrayer, lastRakaat) = completionManager.getLastValidPrayer()
                
                if (!lastPrayer.isNullOrEmpty() && lastRakaat > 0) {
                    // **CRITICAL FIX: Check persistent completion status BEFORE recovery launch**
                    if (!completionManager.isPrayerComplete(lastPrayer)) {
                        Log.i(TAG, "Bypass detected BUT prayer '$lastPrayer' is NOT complete. Proceeding with recovery launch.")
                        // Launch lock screen with the valid prayer
                        launchLockScreenForPrayer(lastPrayer, lastRakaat, "bypass_timer_recovery") // Changed reason for clarity
                        completionManager.clearBypassDetection() // Clear flag only if launched
                        
                        // Log to analytics
                        try {
                            val bundle = Bundle()
                            bundle.putString("prayer_name", lastPrayer)
                            bundle.putString("recovery_reason", "bypass_timer_detected") // Updated reason
                            FirebaseAnalytics.getInstance(this).logEvent("lockscreen_recovery", bundle)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error logging analytics: ${e.message}")
                        }
                    } else {
                         Log.i(TAG, "Bypass detected BUT prayer '$lastPrayer' IS already complete. Skipping recovery launch.")
                         // Optionally clear the bypass flag here too, as the prayer is done
                         completionManager.clearBypassDetection()
                    }
                } else {
                    Log.w(TAG, "Bypass detected but could not retrieve last valid prayer for recovery.")
                }
            } // else detectBypass() returned false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for bypass: ${e.message}")
        }
    }
    
    // Start timer for bypass detection
    private fun startBypassCheckTimer() {
        bypassCheckTimer = Timer("BypassDetectionTimer")
        bypassCheckTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                checkForBypass()
                
                // Check if we're in bypass detection cooldown
                val now = System.currentTimeMillis()
                val inBypassCooldown = now < bypassDetectionCooldownEndTime
                if (inBypassCooldown) {
                    return // Skip additional checks during cooldown period
                }
                
                // Additional check: verify if lock screen is visible when it should be
                val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
                val isLockScreenActive = prefs.getBoolean("lock_screen_active", false)
                val isPinVerified = prefs.getBoolean("pin_verified", false)
                val isUnlocked = prefs.getBoolean("is_unlocked", false)
                
                // Check if there was a very recent unlock first
                val currentTime = System.currentTimeMillis()
                val veryRecentUnlock = prefs.getLong("very_recent_unlock", 0L)
                if (currentTime - veryRecentUnlock < 30000) { // 30 seconds
                    Log.d(TAG, "BypassTimer: Very recent unlock detected (${currentTime - veryRecentUnlock}ms ago), skipping check")
                    return
                }
                
                if (isLockScreenActive && !isPinVerified && !isUnlocked) {
                    val isLockScreenVisible = isActivityForeground("LockScreenActivity")
                    if (!isLockScreenVisible) {
                        // Lockscreen should be visible but isn't - potential bypass
                        Log.w(TAG, "BypassTimer: Lock screen should be visible but isn't - potential bypass detected")
                        
                        val activePrayer = prefs.getString("active_prayer", null)
                        val rakaatCount = prefs.getInt("active_rakaat_count", 4)
                        
                        // Check if we last tried to relaunch recently (prevent rapid relaunch loops)
                        if (System.currentTimeMillis() - lastRelaunchAttempt >= MIN_RELAUNCH_INTERVAL) { // Use same interval as main check
                            lastRelaunchAttempt = System.currentTimeMillis()
                            
                            // Force the lock screen back to the foreground
                            if (activePrayer != null) {
                                Log.d(TAG, "BypassTimer: Force restoring lock screen with prayer: $activePrayer")
                                launchLockScreenForPrayer(activePrayer, rakaatCount, "bypass_timer_recovery")
                            }
                        }
                    }
                }
            }
        }, 5000, 10000) // Check less frequently - every 10 seconds (was 3 seconds)
        
        Log.d(TAG, "Bypass detection timer started with less frequent checks")
    }
}

