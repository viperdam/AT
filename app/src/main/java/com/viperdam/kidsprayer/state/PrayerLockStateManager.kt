package com.viperdam.kidsprayer.state

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.viperdam.kidsprayer.model.Prayer
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prayer Lock State Manager
 * Single source of truth for prayer lock screen state
 * Uses StateFlow and DataStore for persistence
 */
@Singleton
class PrayerLockStateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prayerCompletionManager: PrayerCompletionManager
) {
    companion object {
        private const val TAG = "PrayerLockStateManager"
        private val LOCK_STATE_SCOPE = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        // Event types for tracking
        const val EVENT_LOCK_SHOWN = "lock_shown"
        const val EVENT_LOCK_CLEARED = "lock_cleared"
        const val EVENT_UNLOCK_SCHEDULED = "unlock_scheduled"
        const val EVENT_UNLOCK_TRIGGERED = "unlock_triggered"
    }
    
    // Data state class for prayer lock
    data class PrayerLockState(
        val activePrayerName: String? = null,
        val activePrayerTime: Long = 0L,
        val rakaatCount: Int = 0,
        val isLockActive: Boolean = false,
        val isPinVerified: Boolean = false,
        val wasUnlocked: Boolean = false,
        val automaticUnlockScheduled: Boolean = false,
        val automaticUnlockTime: Long = 0L,
        val lastStateChangeTime: Long = System.currentTimeMillis()
    )
    
    // StateFlow as the single source of truth
    private val _lockState = MutableStateFlow(PrayerLockState())
    val lockState: StateFlow<PrayerLockState> = _lockState.asStateFlow()
    
    // Track events to prevent duplicates
    private val _events = MutableStateFlow<Map<String, Long>>(emptyMap())
    
    init {
        // Initialize state from PrayerCompletionManager
        LOCK_STATE_SCOPE.launch {
            if (prayerCompletionManager.isLockScreenActive()) {
                val (prayerName, rakaatCount) = prayerCompletionManager.getActivePrayer()
                if (prayerName != null) {
                    _lockState.value = PrayerLockState(
                        activePrayerName = prayerName,
                        rakaatCount = rakaatCount,
                        isLockActive = true,
                        lastStateChangeTime = System.currentTimeMillis()
                    )
                    Log.d(TAG, "Initialized state from PrayerCompletionManager: $prayerName")
                }
            }
        }
    }
    
    /**
     * Set active lock screen for a prayer
     */
    fun setActiveLock(prayer: Prayer) {
        if (prayer.name.isNullOrEmpty() || prayer.time <= 0 || prayer.rakaatCount <= 0) {
            Log.e(TAG, "Invalid prayer data provided to setActiveLock")
            return
        }
        
        Log.d(TAG, "Setting active lock for ${prayer.name} at ${Date(prayer.time)}")
        
        // Update StateFlow
        _lockState.value = PrayerLockState(
            activePrayerName = prayer.name,
            activePrayerTime = prayer.time,
            rakaatCount = prayer.rakaatCount,
            isLockActive = true,
            isPinVerified = false,
            wasUnlocked = false,
            lastStateChangeTime = System.currentTimeMillis()
        )
        
        // Also update PrayerCompletionManager for compatibility
        prayerCompletionManager.setLockScreenActive(prayer.name, prayer.rakaatCount)
        
        // Record event
        recordEvent(EVENT_LOCK_SHOWN, prayer.name, prayer.time)
    }
    
    /**
     * Clear the active lock screen
     */
    fun clearActiveLock(prayerName: String? = null, wasManualUnlock: Boolean = false) {
        val currentState = _lockState.value
        val nameToUse = prayerName ?: currentState.activePrayerName
        
        if (nameToUse != null) {
            Log.d(TAG, "Clearing active lock for $nameToUse, wasManualUnlock=$wasManualUnlock")
            
            val wasVerified = currentState.isPinVerified || wasManualUnlock
            
            // Update StateFlow
            _lockState.value = currentState.copy(
                isLockActive = false,
                isPinVerified = wasVerified,
                wasUnlocked = wasManualUnlock,
                automaticUnlockScheduled = false,
                lastStateChangeTime = System.currentTimeMillis()
            )
            
            // Also update PrayerCompletionManager for compatibility
            prayerCompletionManager.clearLockScreenActive()
            
            // Record event
            recordEvent(EVENT_LOCK_CLEARED, nameToUse, System.currentTimeMillis())
        }
    }
    
    /**
     * Schedule an automatic unlock for a prayer
     */
    fun scheduleAutomaticUnlock(prayerName: String, unlockTime: Long) {
        val currentState = _lockState.value
        
        if (prayerName != currentState.activePrayerName) {
            Log.w(TAG, "Attempted to schedule unlock for $prayerName but active prayer is ${currentState.activePrayerName}")
            return
        }
        
        Log.d(TAG, "Scheduling automatic unlock for $prayerName at ${Date(unlockTime)}")
        
        // Update StateFlow
        _lockState.value = currentState.copy(
            automaticUnlockScheduled = true,
            automaticUnlockTime = unlockTime,
            lastStateChangeTime = System.currentTimeMillis()
        )
        
        // Record event
        recordEvent(EVENT_UNLOCK_SCHEDULED, prayerName, unlockTime)
    }
    
    /**
     * Trigger the automatic unlock (called when the alarm fires)
     */
    fun triggerAutomaticUnlock(prayerName: String) {
        val currentState = _lockState.value
        
        if (prayerName != currentState.activePrayerName) {
            Log.w(TAG, "Attempted to trigger unlock for $prayerName but active prayer is ${currentState.activePrayerName}")
            return
        }
        
        if (!currentState.automaticUnlockScheduled) {
            Log.w(TAG, "Automatic unlock triggered for $prayerName but none was scheduled")
        }
        
        // Special handling for Isha prayer - log with higher priority
        val isIsha = prayerName.equals("isha", ignoreCase = true)
        if (isIsha) {
            Log.i(TAG, "ISHA PRAYER UNLOCK: Triggering unlock for Isha prayer specifically")
        } else {
            Log.d(TAG, "Triggering automatic unlock for $prayerName")
        }
        
        // Update StateFlow
        _lockState.value = currentState.copy(
            isLockActive = false,
            automaticUnlockScheduled = false,
            lastStateChangeTime = System.currentTimeMillis()
        )
        
        // Mark prayer as missed in completion manager
        prayerCompletionManager.markPrayerMissed(prayerName)
        
        // Also update PrayerCompletionManager for compatibility
        prayerCompletionManager.clearLockScreenActive()
        
        // Special handling for Isha - verify state is really cleared
        if (isIsha) {
            // Double verify that the state is cleared to prevent race conditions
            if (_lockState.value.isLockActive) {
                Log.w(TAG, "ISHA PRAYER UNLOCK: Lock still active after clear attempt! Forcing clear again.")
                _lockState.value = _lockState.value.copy(
                    isLockActive = false,
                    lastStateChangeTime = System.currentTimeMillis()
                )
                prayerCompletionManager.clearLockScreenActive()
            }
            Log.i(TAG, "ISHA PRAYER UNLOCK: Lock screen cleared successfully for Isha prayer")
        }
        
        // Record event
        recordEvent(EVENT_UNLOCK_TRIGGERED, prayerName, System.currentTimeMillis())
    }
    
    /**
     * Check if a prayer's lock screen is active
     */
    fun isLockScreenActive(): Boolean {
        return _lockState.value.isLockActive
    }
    
    /**
     * Check if a specific prayer's lock screen is active
     */
    fun isLockScreenActiveForPrayer(prayerName: String): Boolean {
        val state = _lockState.value
        return state.isLockActive && state.activePrayerName == prayerName
    }
    
    /**
     * Get the active prayer name and rakaat count
     */
    fun getActivePrayer(): Pair<String?, Int> {
        val state = _lockState.value
        return Pair(state.activePrayerName, state.rakaatCount)
    }
    
    /**
     * Record that an event has occurred to prevent duplicates
     */
    private fun recordEvent(eventType: String, prayerName: String, time: Long) {
        val key = "${eventType}_${prayerName}_${time}"
        val currentEvents = _events.value.toMutableMap()
        currentEvents[key] = System.currentTimeMillis()
        _events.value = currentEvents
        
        // Cleanup old events
        cleanupOldEvents()
    }
    
    /**
     * Check if an event has already occurred
     */
    fun hasEventOccurred(eventType: String, prayerName: String, time: Long): Boolean {
        val key = "${eventType}_${prayerName}_${time}"
        return _events.value.containsKey(key)
    }
    
    /**
     * Clean up old events (older than 24 hours)
     */
    private fun cleanupOldEvents() {
        val now = System.currentTimeMillis()
        val cutoff = now - (24 * 60 * 60 * 1000) // 24 hours
        
        val currentEvents = _events.value.toMutableMap()
        val oldKeys = currentEvents.filter { it.value < cutoff }.keys
        
        if (oldKeys.isNotEmpty()) {
            oldKeys.forEach { currentEvents.remove(it) }
            _events.value = currentEvents
            Log.d(TAG, "Cleaned up ${oldKeys.size} old events")
        }
    }
} 