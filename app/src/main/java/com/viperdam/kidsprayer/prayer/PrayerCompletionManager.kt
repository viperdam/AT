package com.viperdam.kidsprayer.prayer

import android.content.Context
import android.content.SharedPreferences
import com.viperdam.kidsprayer.model.Prayer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import android.util.Log
import android.content.Intent
import com.viperdam.kidsprayer.ui.lock.LockScreenActivity

class PrayerCompletionManager private constructor(context: Context?) {
    var context: Context? = context
        private set
        
    private var prefs: SharedPreferences? = null
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    init {
        if (context != null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val PREFS_NAME = "prayer_completion_prefs"
        private var instance: PrayerCompletionManager? = null
        private const val WINDOW_BUFFER_MINUTES = 20 // 20 minutes before next prayer
        private const val ISHA_END_HOUR = 23
        private const val ISHA_END_MINUTE = 55
        
        // Add new constants for lockscreen state management
        const val LOCK_STATE_PREFS = "prayer_lockscreen_state"
        const val KEY_ACTIVE_PRAYER = "active_prayer"
        const val KEY_ACTIVE_RAKAAT = "active_rakaat_count"
        const val KEY_LOCK_ACTIVE = "lock_screen_active" 
        const val KEY_PIN_VERIFIED = "pin_verified"
        const val KEY_IS_UNLOCKED = "is_unlocked"
        const val KEY_AUTO_MISSED = "auto_missed"
        const val KEY_PRAYER_COMPLETE = "is_prayer_complete"
        const val KEY_LAST_LOCK_TIME = "last_lock_time"
        const val KEY_LAST_UNLOCK_TIME = "last_unlock_time"
        const val KEY_LAST_VALID_PRAYER = "last_valid_prayer"
        const val KEY_LAST_VALID_RAKAAT = "last_valid_rakaat"
        const val KEY_LAST_LOCK_TIMESTAMP = "last_lock_timestamp"
        const val KEY_BYPASS_DETECTED = "bypass_detected"
        const val KEY_BYPASS_COUNT = "bypass_count"
        const val KEY_LOCK_HISTORY = "lock_history"

        fun getInstance(context: Context?): PrayerCompletionManager {
            return instance ?: synchronized(this) {
                instance ?: PrayerCompletionManager(context).also { instance = it }
            }.also { 
                // Update context if instance was created with null context
                if (context != null && it.context == null) {
                    it.context = context
                    it.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun markPrayerComplete(prayerName: String, completionType: CompletionType) {
        val today = LocalDate.now().format(dateFormatter)
        val key = "${today}_${prayerName.lowercase()}"
        // Use commit() for synchronous save to rule out timing issues
        val success = prefs?.edit()?.putString(key, completionType.name)?.commit() ?: false
        if (!success) {
             Log.e("PrayerState", "Failed to commit prayer completion status for $prayerName")
        }

        // Clear pending prayer state if it matches this prayer
        val pendingPrefs = context?.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
        val pendingPrayer = pendingPrefs?.getString("pending_prayer", null)
        if (pendingPrayer == prayerName) {
            pendingPrefs.edit().apply {
                remove("pending_prayer")
                remove("pending_rakaat")
                remove("pending_time")
                apply()
            }
        }
    }

    fun markPrayerAutoMissed(prayerName: String) {
        markPrayerComplete(prayerName, CompletionType.AUTO_MISSED)
    }

    fun markPrayerMissed(prayerName: String) {
        markPrayerComplete(prayerName, CompletionType.PRAYER_MISSED)
    }

    fun isPrayerComplete(prayerName: String): Boolean {
        val today = LocalDate.now().format(dateFormatter)
        val key = "${today}_${prayerName.lowercase()}"
        return prefs?.contains(key) ?: false
    }

    fun getCompletionType(prayerName: String): CompletionType? {
        val today = LocalDate.now().format(dateFormatter)
        val key = "${today}_${prayerName.lowercase()}"
        val value = prefs?.getString(key, null) ?: return null
        return try {
            CompletionType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun clearOldCompletions() {
        val today = LocalDate.now().format(dateFormatter)
        prefs?.all?.keys
            ?.filterNot { it.startsWith(today) }
            ?.forEach { key ->
                prefs?.edit()?.remove(key)?.apply()
            }
    }

    data class CompletionStatus(
        val isComplete: Boolean,
        val completionType: CompletionType?,
        val isMissed: Boolean = completionType == CompletionType.PRAYER_MISSED,
        val isCurrentPrayer: Boolean = false
    )

    private fun calculatePrayerWindow(prayer: Prayer, nextPrayer: Prayer?): Long {
        return when (prayer.name) {
            "Isha" -> {
                // For Isha, window extends until 23:55
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = prayer.time
                calendar.set(Calendar.HOUR_OF_DAY, ISHA_END_HOUR)
                calendar.set(Calendar.MINUTE, ISHA_END_MINUTE)
                calendar.set(Calendar.SECOND, 0)
                calendar.timeInMillis - prayer.time
            }
            else -> {
                // For other prayers, window extends until 20 minutes before next prayer
                if (nextPrayer != null) {
                    nextPrayer.time - prayer.time - (WINDOW_BUFFER_MINUTES * 60 * 1000)
                } else {
                    // Fallback to 2 hours if next prayer time is not available
                    2 * 60 * 60 * 1000
                }
            }
        }
    }

    fun getPrayerCompletionStatus(prayer: Prayer, nextPrayer: Prayer? = null): CompletionStatus {
        val currentTime = System.currentTimeMillis()
        
        // If the prayer time hasn't come yet, it shouldn't be marked as missed
        if (prayer.time > currentTime) {
            return CompletionStatus(false, null, false, false)
        }
        
        val isComplete = isPrayerComplete(prayer.name)
        val completionType = if (isComplete) getCompletionType(prayer.name) else null
        
        // Calculate window based on next prayer time
        val prayerWindow = calculatePrayerWindow(prayer, nextPrayer)
        
        // Add minimum window protection to prevent too-early missing
        val minimumWindowDuration = 30L * 60L * 1000L // 30 minutes minimum window as Long
        val effectiveWindow = Math.max(prayerWindow, minimumWindowDuration)
        
        // Log window calculations for debugging
        val minutesSincePrayer = (currentTime - prayer.time) / (60L * 1000L)
        val windowMinutes = effectiveWindow / (60L * 1000L)
        android.util.Log.d("PrayerDebug", 
            "Prayer ${prayer.name}: Minutes since prayer time: $minutesSincePrayer, " +
            "Window duration: $windowMinutes minutes, " +
            "Window end: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(prayer.time + effectiveWindow))}"
        )
        
        // Check if this is the current prayer (within the prayer window and not completed)
        val isCurrentPrayer = !isComplete && (currentTime - prayer.time) <= effectiveWindow
        
        // Check if we're near the end of the window (within last minute)
        val isWindowEnding = (prayer.time + effectiveWindow - currentTime) <= 60L * 1000L
        
        // Only properly completed prayers are those with PRAYER_PERFORMED or PIN_VERIFIED
        val isProperlyCompleted = completionType == CompletionType.PRAYER_PERFORMED || 
                                  completionType == CompletionType.PIN_VERIFIED
        
        // Only mark as missed if current time is past the window AND the prayer hasn't been completed in any way
        // This ensures prayers are only marked as missed if the lockscreen wasn't unlocked during the window
        if (!isComplete && (currentTime - prayer.time) > effectiveWindow) {
            // Check if the lockscreen was unlocked for this prayer
            val receiverPrefs = context?.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            val wasUnlocked = receiverPrefs?.getBoolean("${prayer.name.lowercase()}_unlocked", false) ?: false
            
            // Only mark as missed if the lockscreen wasn't unlocked
            if (!wasUnlocked) {
                markPrayerMissed(prayer.name)
                return CompletionStatus(true, CompletionType.PRAYER_MISSED, true, false)
            }
        }
        
        // Auto-mark as missed if window is ending within 1 minute and prayer is not completed
        if (!isComplete && isWindowEnding) {
            markPrayerAutoMissed(prayer.name)
            return CompletionStatus(true, CompletionType.AUTO_MISSED, true, false)
        }
        
        return CompletionStatus(isComplete, completionType, 
            completionType == CompletionType.PRAYER_MISSED || completionType == CompletionType.AUTO_MISSED, 
            isCurrentPrayer)
    }

    enum class CompletionType {
        PRAYER_PERFORMED,  // Prayer was completed through pose detection
        PIN_VERIFIED,      // Prayer was bypassed with parent PIN
        PRAYER_MISSED,     // Prayer was missed (time window passed)
        SYSTEM_UNLOCK,     // System unlock
        AUTO_MISSED        // Prayer was automatically marked as missed when window ended
    }

    // Add new methods for lockscreen state management

    /**
     * Sets the lockscreen active with the given prayer information
     */
    fun setLockScreenActive(prayerName: String, rakaatCount: Int) {
        val statePrefs = context?.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            statePrefs?.edit()
                ?.putString(KEY_ACTIVE_PRAYER, prayerName)
                ?.putInt(KEY_ACTIVE_RAKAAT, rakaatCount)
                ?.putBoolean(KEY_LOCK_ACTIVE, true)
                ?.putBoolean(KEY_PIN_VERIFIED, false)
                ?.putBoolean(KEY_IS_UNLOCKED, false)
                ?.putBoolean(KEY_AUTO_MISSED, false)
                ?.putBoolean(KEY_PRAYER_COMPLETE, false)
                ?.putLong(KEY_LAST_LOCK_TIME, System.currentTimeMillis())
                ?.apply()
        }
        
        // Store this as a valid prayer state for potential recovery
        storeValidPrayerState(prayerName, rakaatCount)
        
        Log.d("PrayerState", "Lock screen set active for $prayerName with $rakaatCount rakaats")
    }

    /**
     * Clears the lockscreen active state
     */
    fun clearLockScreenActive() {
        val statePrefs = context?.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val prayerName = statePrefs?.getString(KEY_ACTIVE_PRAYER, null)
            statePrefs?.edit()
                ?.putBoolean(KEY_LOCK_ACTIVE, false)
                ?.putBoolean(KEY_IS_UNLOCKED, true)
                ?.putLong(KEY_LAST_UNLOCK_TIME, System.currentTimeMillis())
                ?.apply()
        }
        Log.d("PrayerState", "Lock screen cleared")
    }

    /**
     * Checks if the lockscreen is currently active
     */
    fun isLockScreenActive(): Boolean {
        val statePrefs = context?.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        return statePrefs?.getBoolean(KEY_LOCK_ACTIVE, false) ?: false
    }

    /**
     * Gets the currently active prayer information
     */
    fun getActivePrayer(): Pair<String?, Int> {
        val statePrefs = context?.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        val prayerName = statePrefs?.getString(KEY_ACTIVE_PRAYER, null)
        val rakaatCount = statePrefs?.getInt(KEY_ACTIVE_RAKAAT, 0) ?: 0
        return Pair(prayerName, rakaatCount)
    }

    /**
     * Stores valid prayer information for recovery in case of bypass
     */
    fun storeValidPrayerState(prayerName: String, rakaatCount: Int) {
        val statePrefs = context?.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            // Store directly as last valid prayer
            statePrefs?.edit()
                ?.putString(KEY_LAST_VALID_PRAYER, prayerName)
                ?.putInt(KEY_LAST_VALID_RAKAAT, rakaatCount)
                ?.putLong(KEY_LAST_LOCK_TIMESTAMP, System.currentTimeMillis())
                ?.apply()
            
            // Also store in active prayer keys to ensure consistency
            statePrefs?.edit()
                ?.putString(KEY_ACTIVE_PRAYER, prayerName)
                ?.putInt(KEY_ACTIVE_RAKAAT, rakaatCount)
                ?.apply()
            
            // Also add to history for additional fallback
            try {
                val historyJson = statePrefs?.getString(KEY_LOCK_HISTORY, "[]")
                val historyArray = org.json.JSONArray(historyJson ?: "[]")
                
                // Create new entry
                val entry = org.json.JSONObject()
                entry.put("prayer", prayerName)
                entry.put("rakaat", rakaatCount)
                entry.put("timestamp", System.currentTimeMillis())
                
                // Add to beginning of array (most recent first)
                val newHistory = org.json.JSONArray()
                newHistory.put(entry)
                
                // Copy previous entries (up to 4 more for a total of 5)
                for (i in 0 until Math.min(historyArray.length(), 4)) {
                    newHistory.put(historyArray.get(i))
                }
                
                statePrefs?.edit()
                    ?.putString(KEY_LOCK_HISTORY, newHistory.toString())
                    ?.apply()
            } catch (e: Exception) {
                Log.e("PrayerState", "Error storing prayer history: ${e.message}")
            }
        }
        Log.d("PrayerState", "Stored valid prayer state: $prayerName with $rakaatCount rakaats")
    }

    /**
     * Marks a prayer as completed with the given completion type
     */
    fun setPrayerCompleted(prayerName: String, completionType: CompletionType) {
        markPrayerComplete(prayerName, completionType)
        
        val statePrefs = context?.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            statePrefs?.edit()
                ?.putBoolean(KEY_PRAYER_COMPLETE, true)
                ?.putBoolean(KEY_IS_UNLOCKED, true)
                ?.putLong(KEY_LAST_UNLOCK_TIME, System.currentTimeMillis())
                ?.apply()
        }
        Log.d("PrayerState", "Prayer $prayerName marked complete with type: $completionType")
    }

    /**
     * Detects if a lockscreen bypass may have occurred
     */
    fun detectBypass(): Boolean {
        val statePrefs = context?.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        val isLockActive = statePrefs?.getBoolean(KEY_LOCK_ACTIVE, false) ?: false
        val isPrayerComplete = statePrefs?.getBoolean(KEY_PRAYER_COMPLETE, false) ?: false
        val isPinVerified = statePrefs?.getBoolean(KEY_PIN_VERIFIED, false) ?: false
        val isUnlocked = statePrefs?.getBoolean(KEY_IS_UNLOCKED, false) ?: false
        
        // Check for recent bypass indicators from LockScreenActivity
        val receiverPrefs = context?.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
        
        // Check for very recent unlock first
        val veryRecentUnlock = receiverPrefs?.getLong("very_recent_unlock", 0L) ?: 0L
        val currentTime = System.currentTimeMillis()
        if (currentTime - veryRecentUnlock < 30000) { // 30 seconds grace period
            Log.d("PrayerState", "Very recent unlock detected (${currentTime - veryRecentUnlock}ms ago), not flagging as bypass")
            return false
        }
        
        val lastBypassAttempt = receiverPrefs?.getLong("last_bypass_attempt", 0) ?: 0
        val lastFocusLoss = receiverPrefs?.getLong("last_focus_loss", 0) ?: 0
        val lastStoppedTime = receiverPrefs?.getLong("lock_screen_last_stopped", 0) ?: 0
        val bypassLocation = receiverPrefs?.getString("bypass_location", "") ?: ""
        
        // Check if any of these events happened recently (within last 3 seconds)
        val recentEvent = System.currentTimeMillis() - maxOf(lastBypassAttempt, lastFocusLoss, lastStoppedTime) < 3000
        
        // A bypass is detected if:
        val bypassDetected = isLockActive && (
            // 1. If unlocked without pin or completion
            (!isPrayerComplete && !isPinVerified && isUnlocked) ||
            
            // 2. If a recent bypass event was detected by LockScreenActivity
            (recentEvent && !isPrayerComplete && !isPinVerified) ||
            
            // 3. If too much time has passed since lock was activated with no verification
            ((System.currentTimeMillis() - (statePrefs?.getLong(KEY_LAST_LOCK_TIMESTAMP, 0) ?: 0)) > 120000L && 
                !isPrayerComplete && !isPinVerified)
        )
        
        if (bypassDetected) {
            val count = (statePrefs?.getInt(KEY_BYPASS_COUNT, 0) ?: 0) + 1
            statePrefs?.edit()
                ?.putBoolean(KEY_BYPASS_DETECTED, true)
                ?.putInt(KEY_BYPASS_COUNT, count)
                ?.apply()
            
            // Log details about the bypass for debugging
            val reason = when {
                !isPrayerComplete && !isPinVerified && isUnlocked -> "Unlocked without verification"
                recentEvent -> "Recent suspicious event from: $bypassLocation"
                else -> "Lock screen timed out without completion"
            }
            
            Log.w("PrayerState", "Potential lockscreen bypass detected: $reason (count: $count)")
            
            // Clear the bypass indicators to prevent double-counting
            receiverPrefs?.edit()
                ?.remove("last_bypass_attempt")
                ?.remove("last_focus_loss")
                ?.putString("bypass_location", "")
                ?.apply()
        }
        
        return bypassDetected
    }

    /**
     * Gets the last valid prayer information for recovery
     */
    fun getLastValidPrayer(): Pair<String?, Int> {
        val statePrefs = context?.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        
        // First check the active prayer - this should be the most recent
        val activePrayerName = statePrefs?.getString(KEY_ACTIVE_PRAYER, null)
        val activeRakaatCount = statePrefs?.getInt(KEY_ACTIVE_RAKAAT, 0) ?: 0
        
        if (!activePrayerName.isNullOrEmpty() && activeRakaatCount > 0) {
            Log.d("PrayerState", "Using active prayer for recovery: $activePrayerName with $activeRakaatCount rakaats")
            return Pair(activePrayerName, activeRakaatCount)
        }
        
        // Fall back to last valid prayer if active prayer is not available
        val prayerName = statePrefs?.getString(KEY_LAST_VALID_PRAYER, null)
        val rakaatCount = statePrefs?.getInt(KEY_LAST_VALID_RAKAAT, 0) ?: 0
        
        // If we still don't have a stored valid prayer, try to get from history
        if (prayerName == null || rakaatCount == 0) {
            try {
                val historyJson = statePrefs?.getString(KEY_LOCK_HISTORY, "[]")
                val historyArray = org.json.JSONArray(historyJson ?: "[]")
                if (historyArray.length() > 0) {
                    val latestEntry = historyArray.getJSONObject(0)
                    val historyPrayer = latestEntry.getString("prayer")
                    val historyRakaat = latestEntry.getInt("rakaat")
                    return Pair(historyPrayer, historyRakaat)
                }
            } catch (e: Exception) {
                Log.e("PrayerState", "Error retrieving prayer history: ${e.message}")
            }
        }
        
        return Pair(prayerName, rakaatCount)
    }

    /**
     * Clears bypass detection state
     */
    fun clearBypassDetection() {
        val statePrefs = context?.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE)
        statePrefs?.edit()
            ?.putBoolean(KEY_BYPASS_DETECTED, false)
            ?.apply()
    }

    /**
     * Sets the context for this manager instance if it wasn't set during construction
     */
    fun setContext(newContext: Context) {
        // Only update if we don't have a context or prefs
        if (context == null || prefs == null) {
            context = newContext
            prefs = newContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            Log.d("PrayerState", "Context updated for PrayerCompletionManager")
        }
    }

    fun checkLockScreenTimeout() { // THIS IS LIKELY THE FUNCTION CALLED PERIODICALLY
        val statePrefs = context?.getSharedPreferences(LOCK_STATE_PREFS, Context.MODE_PRIVATE) ?: return
        val lockActive = statePrefs.getBoolean(KEY_LOCK_ACTIVE, false)
        val pinVerified = statePrefs.getBoolean(KEY_PIN_VERIFIED, false)
        val isUnlocked = statePrefs.getBoolean(KEY_IS_UNLOCKED, false)
        val isPrayerCompleteFlag = statePrefs.getBoolean(KEY_PRAYER_COMPLETE, false) // Read the flag from state prefs
        val lastLockTime = statePrefs.getLong(KEY_LAST_LOCK_TIME, 0)
        val activePrayer = statePrefs.getString(KEY_ACTIVE_PRAYER, null)
        val isPersistentlyComplete = if (activePrayer != null) isPrayerComplete(activePrayer) else false

        if (lockActive && !isPersistentlyComplete) {
            if (!pinVerified && !isUnlocked && !isPrayerCompleteFlag) {
                val timeout = 10 * 60 * 1000L // 10 minute timeout
                if (System.currentTimeMillis() - lastLockTime > timeout) {
                    val bypassCount = statePrefs.getInt(KEY_BYPASS_COUNT, 0) + 1
                    Log.w("PrayerState", "Potential lockscreen bypass detected: Lock screen timed out without completion (count: $bypassCount) for prayer: $activePrayer")
                    statePrefs.edit().putInt(KEY_BYPASS_COUNT, bypassCount).apply()

                    // Trigger recovery (only if activePrayer is not null)
                    if (activePrayer != null) {
                        val rakaatCount = statePrefs.getInt(KEY_ACTIVE_RAKAAT, 4)
                        Log.d("PrayerState", "Using active prayer for recovery: $activePrayer with $rakaatCount rakaats")
                        startLockScreenActivity(activePrayer, rakaatCount, "timeout_recovery")
                    } else {
                        Log.w("PrayerState", "Timeout detected but active prayer is null, cannot recover.")
                    }
                } 
            } else {
                 if (statePrefs.getInt(KEY_BYPASS_COUNT, 0) > 0) {
                     statePrefs.edit().putInt(KEY_BYPASS_COUNT, 0).apply()
                }
            }
        } else {
            if (statePrefs.getInt(KEY_BYPASS_COUNT, 0) > 0) {
                 statePrefs.edit().putInt(KEY_BYPASS_COUNT, 0).apply()
            }
        }
    }

    /**
     * Helper function to start LockScreenActivity with necessary data
     */
    private fun startLockScreenActivity(prayerName: String, rakaatCount: Int, reason: String) {
        context?.let { ctx ->
            try {
                val intent = Intent(ctx, LockScreenActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("prayer_name", prayerName)
                    putExtra("rakaat_count", rakaatCount)
                    putExtra("prayer_time", System.currentTimeMillis()) // Pass current time for reference
                    putExtra("launch_reason", reason)
                    putExtra("monitor_relaunch", true) // Indicate it's a relaunch
                }
                ctx.startActivity(intent)
                Log.d("PrayerState", "Launched LockScreenActivity for $prayerName ($reason)")
            } catch (e: Exception) {
                 Log.e("PrayerState", "Error launching LockScreenActivity from Manager: ${e.message}", e)
            }
        } ?: run {
             Log.e("PrayerState", "Cannot launch LockScreenActivity: Context is null in PrayerCompletionManager")
        }
    }
}
