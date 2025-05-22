package com.viperdam.kidsprayer.state

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class LockScreenStateManager private constructor(private val context: Context) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lockscreen_state")
    private val stateMutex = Mutex()
    private val _state = MutableStateFlow(LockScreenState())
    val state: StateFlow<LockScreenState> = _state.asStateFlow()

    companion object {
        const val UNLOCK_READY_ACTION = "com.viperdam.kidsprayer.UNLOCK_READY"
        private const val MIN_STATE_CHANGE_INTERVAL = 500L // 500ms
        private const val PIN_COOLDOWN_DURATION = 30_000L // 30 seconds
        private const val MAX_PIN_ATTEMPTS = 3

        @Volatile
        private var INSTANCE: LockScreenStateManager? = null

        fun getInstance(context: Context): LockScreenStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LockScreenStateManager(context).also { INSTANCE = it }
            }
        }

        private object PreferencesKeys {
            val PRAYER_NAME = stringPreferencesKey("prayer_name")
            val RAKAAT_COUNT = intPreferencesKey("rakaat_count")
            val CURRENT_RAKAAT = intPreferencesKey("current_rakaat")
            val PIN_ATTEMPTS = intPreferencesKey("pin_attempts")
            val LAST_PIN_ATTEMPT = longPreferencesKey("last_pin_attempt")
            val IS_CAMERA_ACTIVE = booleanPreferencesKey("is_camera_active")
            val CURRENT_POSITION = stringPreferencesKey("current_position")
            val IS_PRAYER_COMPLETE = booleanPreferencesKey("is_prayer_complete")
            val LAST_STATE_CHANGE = longPreferencesKey("last_state_change")
        }
    }

    private var lastStateChange = 0L

    data class LockScreenState(
        val isLocked: Boolean = false,
        val unlockMethod: String? = null,
        val pinAttempts: Int = 0,
        val isLockedOut: Boolean = false,
        val pinCooldownSeconds: Long = 0,
        val errorMessage: String? = null,
        val currentRakaat: Int = 0,
        val isMissedPrayer: Boolean = false,
        val prayerName: String? = null,
        val isPrayerComplete: Boolean = false,
        val isCameraActive: Boolean = false,
        val currentPosition: String? = null
    )

    enum class UnlockMethod {
        NONE,
        PIN,
        MEDIAPIPE,
    }

    enum class PrayerPhase {
        IDLE, STANDING, BOWING, SITTING
    }

    private var currentPhase: PrayerPhase = PrayerPhase.IDLE
    private var phaseStartTime: Long = 0L
    private var rakaaCount: Int = 0

    private suspend fun persistLockScreenState(state: LockScreenState) {
        Log.d("LockScreenStateManager", "Persisted lock screen state: $state")
    }

    private suspend fun restoreLockScreenState(): LockScreenState? {
        Log.d("LockScreenStateManager", "No valid lock screen state to restore")
        return null
    }

   init {
        // Restore state when manager is created
        CoroutineScope(Dispatchers.Main).launch {
            try {
                context.dataStore.data.first().let { preferences ->
                    _state.update { _: LockScreenState -> 
                        LockScreenState(
                            isLocked = preferences[PreferencesKeys.IS_PRAYER_COMPLETE] ?: false, // Example: Use a relevant key
                            prayerName = preferences[PreferencesKeys.PRAYER_NAME],
                            currentRakaat = preferences[PreferencesKeys.CURRENT_RAKAAT] ?: 0,
                            pinAttempts = preferences[PreferencesKeys.PIN_ATTEMPTS] ?: 0,
                            isCameraActive = preferences[PreferencesKeys.IS_CAMERA_ACTIVE] ?: false,
                            currentPosition = preferences[PreferencesKeys.CURRENT_POSITION],
                            isPrayerComplete = preferences[PreferencesKeys.IS_PRAYER_COMPLETE] ?: false,
                            // Initialize other fields as needed from DataStore or defaults
                        )
                    }
                }
            } catch (e: IOException) {
                Log.e("LockScreenStateManager", "Error restoring state from DataStore", e)
                // Initialize with default state if restore fails
                 _state.update { _: LockScreenState -> LockScreenState() }
            }
        }
    }

    suspend fun initialize(prayerName: String, rakaatCount: Int, isMissedPrayer: Boolean = false) {
        _state.update { currentState: LockScreenState -> 
            currentState.copy(
                isLocked = true,
                prayerName = prayerName,
                currentRakaat = rakaatCount, // Assuming rakaatCount is the total needed
                isMissedPrayer = isMissedPrayer,
                pinAttempts = 0,
                isLockedOut = false,
                pinCooldownSeconds = 0,
                errorMessage = null,
                isPrayerComplete = false, // Ensure prayer starts incomplete
                isCameraActive = false, // Camera starts inactive
                currentPosition = null // Position starts null
            )
        }
        saveState() // Persist initial state
    }

    suspend fun unlock(prayerName: String, unlockMethod: UnlockMethod) {
        _state.update { currentState: LockScreenState -> 
            if (currentState.isLocked && currentState.prayerName == prayerName) {
                currentState.copy(
                    unlockMethod = unlockMethod.name
                )
            } else {
                currentState // No change if not locked or wrong prayer
            }
        }
        // Persist state change if needed, though unlock might not need immediate persistence
        // saveState()
        
        // Signal that unlock is ready to proceed only if state was updated
        if (_state.value.unlockMethod == unlockMethod.name) {
             context.sendBroadcast(Intent(UNLOCK_READY_ACTION).apply {
                putExtra("prayer_name", prayerName)
                putExtra("unlock_method", unlockMethod.name)
            })
        }
    }

    suspend fun verifyPin(pin: String, correctPin: String): Boolean {
        var isCorrect = false
        _state.update { currentState: LockScreenState -> 
            if (!currentState.isLocked || currentState.isLockedOut) return@update currentState // Cannot verify if not locked or locked out

            isCorrect = pin == correctPin
            if (isCorrect) {
                currentState.copy(
                    isLocked = false,
                    pinAttempts = 0,
                    isLockedOut = false,
                    pinCooldownSeconds = 0,
                    errorMessage = null,
                    unlockMethod = UnlockMethod.PIN.name // Mark unlock method
                )
            } else {
                val newAttempts = currentState.pinAttempts + 1
                val isLockedOut = newAttempts >= MAX_PIN_ATTEMPTS
                currentState.copy(
                    pinAttempts = newAttempts,
                    isLockedOut = isLockedOut,
                    pinCooldownSeconds = if (isLockedOut) (PIN_COOLDOWN_DURATION / 1000).toLong() else 0,
                    errorMessage = "Incorrect PIN. ${MAX_PIN_ATTEMPTS - newAttempts} attempts remaining."
                )
            }
        }
        saveState() // Persist after pin attempt
        return isCorrect
    }

  suspend fun startPrayer() {
        _state.update { currentState: LockScreenState -> 
            // Can only start prayer if locked and unlock method is MediaPipe (or similar)
            if (currentState.isLocked /* && currentState.unlockMethod == UnlockMethod.MEDIAPIPE.name */) {
                 currentState.copy(
                    isLocked = false, // Unlock implicitly by starting prayer
                    isCameraActive = true,
                    isPrayerComplete = false // Ensure prayer starts incomplete
                )
            } else {
                currentState
            }
        }
        saveState()
    }

   suspend fun updatePrayerProgress(currentRakaatProgress: Int, position: String) {
        _state.update { currentState: LockScreenState -> 
            // Only update if camera is active (prayer is in progress)
            if (!currentState.isCameraActive) return@update currentState

            val totalRakaatNeeded = currentState.currentRakaat // Assuming this holds total needed
            val isPrayerComplete = currentRakaatProgress >= totalRakaatNeeded
            
            currentState.copy(
                // currentRakaat should reflect progress, not total needed
                // Let's assume a new field 'completedRakaat' or similar is needed
                // For now, let's just update position and completion status
                currentPosition = position,
                isPrayerComplete = isPrayerComplete,
                isCameraActive = !isPrayerComplete // Deactivate camera upon completion
            )
        }
       saveState()
    }

    suspend fun reset(preservePermissions: Boolean = false, preserveSettings: Boolean = false) {
        withContext(Dispatchers.IO) {
            try {
                // Store permission state if needed
                val deviceAdmin = if (preservePermissions) {
                    val prefs = context.getSharedPreferences("device_admin", Context.MODE_PRIVATE)
                    prefs.all.toMap()
                } else null

                // Store settings if needed
                val settings = if (preserveSettings) {
                    // Store prayer settings
                    val prayerSettings = context.getSharedPreferences("prayer_settings", Context.MODE_PRIVATE)
                    val userPrefs = context.getSharedPreferences("user_preferences", Context.MODE_PRIVATE)
                    
                    mapOf(
                        "prayer_settings" to prayerSettings.all,
                        "user_preferences" to userPrefs.all
                    )
                } else null

                // Store DataStore settings if needed
                val dataStoreSettings = if (preserveSettings) {
                    context.dataStore.data.first()
                } else null

                // Clear shared preferences
                context.getSharedPreferences("lock_screen_state", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()

                // Clear DataStore
                context.dataStore.edit { preferences ->
                    preferences.clear()
                }

                // Restore permission state if needed
                if (preservePermissions && deviceAdmin != null) {
                    val prefs = context.getSharedPreferences("device_admin", Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    deviceAdmin.forEach { (key, value) ->
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Float -> editor.putFloat(key, value)
                            is String -> editor.putString(key, value)
                        }
                    }
                    editor.apply()
                }

                // Restore settings if needed
                if (preserveSettings && settings != null) {
                    // Restore prayer settings
                    settings["prayer_settings"]?.let { settingsMap ->
                        val prefs = context.getSharedPreferences("prayer_settings", Context.MODE_PRIVATE)
                        val editor = prefs.edit()
                        settingsMap.forEach { (key, value) ->
                            when (value) {
                                is Boolean -> editor.putBoolean(key, value)
                                is Int -> editor.putInt(key, value)
                                is Long -> editor.putLong(key, value)
                                is Float -> editor.putFloat(key, value)
                                is String -> editor.putString(key, value)
                            }
                        }
                        editor.apply()
                    }

                    // Restore user preferences
                    settings["user_preferences"]?.let { prefsMap ->
                        val prefs = context.getSharedPreferences("user_preferences", Context.MODE_PRIVATE)
                        val editor = prefs.edit()
                        prefsMap.forEach { (key, value) ->
                            when (value) {
                                is Boolean -> editor.putBoolean(key, value)
                                is Int -> editor.putInt(key, value)
                                is Long -> editor.putLong(key, value)
                                is Float -> editor.putFloat(key, value)
                                is String -> editor.putString(key, value)
                            }
                        }
                        editor.apply()
                    }
                }

                // Restore DataStore settings if needed
                if (preserveSettings && dataStoreSettings != null) {
                    context.dataStore.edit { preferences ->
                        dataStoreSettings.asMap().forEach { (key, value) ->
                            when (value) {
                                is Boolean -> preferences[booleanPreferencesKey(key.name)] = value
                                is Int -> preferences[intPreferencesKey(key.name)] = value
                                is Long -> preferences[longPreferencesKey(key.name)] = value
                                is Float -> preferences[floatPreferencesKey(key.name)] = value
                                is Double -> preferences[doublePreferencesKey(key.name)] = value
                                is String -> preferences[stringPreferencesKey(key.name)] = value
                            }
                        }
                    }
                }

                // Reset state flow to initial state
                _state.update { _: LockScreenState -> LockScreenState() }

                // Reset local state variables
                lastStateChange = 0L
                currentPhase = PrayerPhase.IDLE
                phaseStartTime = 0L
                rakaaCount = 0

                saveState() // Persist the reset state
                Log.d("LockScreenStateManager", "State reset completed successfully")
            } catch (e: Exception) {
                Log.e("LockScreenStateManager", "Error resetting state", e)
                throw e
            }
        }
    }

    suspend fun updateRakaaState(detectedPose: String) {
        val now = System.currentTimeMillis()
        _state.update { currentState: LockScreenState -> 
             // Only update if camera is active
            if (!currentState.isCameraActive) return@update currentState

            var nextPhase = currentPhase
            var nextPhaseStartTime = phaseStartTime
            var nextRakaaCount = rakaaCount
            var nextPosition = currentState.currentPosition

            when (currentPhase) {
                 PrayerPhase.IDLE -> {
                    if (detectedPose == "standing") {
                        nextPhase = PrayerPhase.STANDING
                        nextPhaseStartTime = now
                        nextPosition = "Standing - Hold for 7 seconds"
                    } else {
                        nextPhaseStartTime = 0L
                        nextPosition = "Please stand to begin prayer"
                    }
                }
                PrayerPhase.STANDING -> {
                    if (detectedPose == "standing") {
                        val remainingTime = 7000L - (now - phaseStartTime)
                        if (remainingTime > 0) {
                            nextPosition = "Standing - ${remainingTime / 1000 + 1} seconds remaining"
                        } else { // 7 seconds elapsed
                            nextPhase = PrayerPhase.BOWING
                            nextPhaseStartTime = now
                            nextPosition = "Please bow"
                        }
                    } else { // Pose changed
                        nextPhase = PrayerPhase.IDLE
                        nextPhaseStartTime = 0L
                        nextPosition = "Please maintain standing position"
                    }
                }
                PrayerPhase.BOWING -> {
                     if (detectedPose == "bowing") {
                        val remainingTime = 2000L - (now - phaseStartTime)
                        if (remainingTime > 0) {
                            nextPosition = "Bowing - ${remainingTime/1000 + 1} seconds remaining"
                        } else { // 2 seconds elapsed
                            nextPhase = PrayerPhase.SITTING
                            nextPhaseStartTime = now
                            nextPosition = "Please sit"
                        }
                    } else { // Pose changed
                         nextPhase = PrayerPhase.IDLE
                         nextPhaseStartTime = 0L
                        nextPosition = "Please maintain bowing position"
                    }
                }
                PrayerPhase.SITTING -> {
                    if (detectedPose == "sitting") {
                        val remainingTime = 2000L - (now - phaseStartTime)
                        if (remainingTime > 0) {
                            nextPosition = "Sitting - ${remainingTime/1000 + 1} seconds remaining"
                        } else { // 2 seconds elapsed
                            nextRakaaCount++
                            nextPhase = PrayerPhase.IDLE // Reset for next rakaa or completion
                            nextPhaseStartTime = 0L
                            nextPosition = "Rakaa $nextRakaaCount completed (${nextRakaaCount}/${currentState.currentRakaat})"
                        }
                    } else if (detectedPose != "bowing") { // Avoid accidental reset if moving from bowing to sitting
                         nextPhase = PrayerPhase.IDLE
                         nextPhaseStartTime = 0L
                        nextPosition = "Please maintain sitting position"
                    }
                }
            }

            // Update local phase tracking
            currentPhase = nextPhase
            phaseStartTime = nextPhaseStartTime
            rakaaCount = nextRakaaCount

            // Return updated state for StateFlow
            currentState.copy(
                currentPosition = nextPosition,
                // Update currentRakaat based on progress if needed
                // Or introduce a new field like 'completedRakaat'
                // For now, assume currentState.currentRakaat holds total
            )
        }
        // No need to call saveState() here, as state is persisted implicitly by DataStore flow
    }

    private fun resetPhase() {
        currentPhase = PrayerPhase.IDLE
        phaseStartTime = 0L
    }

    // Keep getRakaaCount if needed elsewhere, but main progress is in StateFlow
    fun getRakaaCount(): Int {
        return rakaaCount
    }

   private suspend fun saveState() {
       // No need for explicit saveState if relying on DataStore persistence
       // DataStore updates automatically trigger persistence
        // try {
        //     context.dataStore.edit { preferences ->
        //         val currentState = _state.value
        //         preferences[PreferencesKeys.PRAYER_NAME] = currentState.prayerName ?: ""
        //         preferences[PreferencesKeys.CURRENT_RAKAAT] = currentState.currentRakaat
        //         preferences[PreferencesKeys.PIN_ATTEMPTS] = currentState.pinAttempts
        //         // ... persist other relevant fields
        //         preferences[PreferencesKeys.LAST_STATE_CHANGE] = System.currentTimeMillis() // Update timestamp
        //     }
        // } catch (e: IOException) {
        //     Log.e("LockScreenStateManager", "Error saving state to DataStore", e)
        // }
    }
}
