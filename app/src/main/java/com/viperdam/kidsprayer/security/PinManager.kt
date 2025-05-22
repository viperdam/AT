package com.viperdam.kidsprayer.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinManager @Inject constructor(@ApplicationContext context: Context) {
    private val masterKeyAlias = try {
        // Try to get existing master key alias first
        // For 1.0.0, MasterKeys.getOrCreate directly returns the alias string
        // and handles creation if it doesn't exist. The builder pattern with
        // specific alias like MasterKey.DEFAULT_MASTER_KEY_ALIAS isn't directly
        // used in the same way for instantiation. We'll use a standard alias.
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to get or create master key: ${e.message}")
        // Fallback or rethrow, as getOrCreate should handle creation.
        // If it fails, something is seriously wrong with Keystore.
        // For simplicity here, rethrowing, but a production app might handle this differently.
        throw IllegalStateException("Could not get or create master key alias", e)
    }

    private val securePrefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "secure_prefs", // file_name (String)
            masterKeyAlias, // master_key_alias (String)
            context,        // context
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create encrypted prefs, recreating: ${e.message}")
        try {
            // First try clearing the preferences
            // Note: Standard SharedPreferences delete method.
            context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            
            // Then create new encrypted preferences
            EncryptedSharedPreferences.create(
                "secure_prefs", // file_name (String)
                masterKeyAlias, // master_key_alias (String)
                context,        // context
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e2: Exception) {
            // If everything fails, fall back to regular preferences
            Log.e(TAG, "Failed to create encrypted prefs even after reset, using regular prefs: ${e2.message}")
            context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        }
    }

    private val stateMutex = Mutex()
    private var lastPinVerification = 0L
    private var lastStateReset = 0L
    private var verificationCount = 0
    private var isLocked = false

    companion object {
        private const val TAG = "PinManager"
        private const val KEY_PIN = "pin"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_ENABLED = "pin_enabled"
        private const val KEY_LAST_VERIFICATION = "last_verification"
        private const val KEY_VERIFICATION_COUNT = "verification_count"
        private const val KEY_LAST_RESET = "last_reset"
        private const val KEY_IS_LOCKED = "is_locked"
        
        const val MAX_VERIFICATIONS = 3
        private const val VERIFICATION_RESET_DELAY = 60_000L // 1 minute
        private const val MIN_VERIFICATION_INTERVAL = 300L // 300ms
    }

    init {
        loadState()
    }

    private fun loadState() {
        try {
            lastPinVerification = securePrefs.getLong(KEY_LAST_VERIFICATION, 0)
            verificationCount = securePrefs.getInt(KEY_VERIFICATION_COUNT, 0)
            lastStateReset = securePrefs.getLong(KEY_LAST_RESET, 0)
            isLocked = securePrefs.getBoolean(KEY_IS_LOCKED, false)

            // Check if we should reset state based on time
            val currentTime = System.currentTimeMillis()
            if (isLocked && currentTime - lastStateReset >= VERIFICATION_RESET_DELAY) {
                resetVerificationStateInternal()
            }

            // Verify PIN integrity
            val savedPin = securePrefs.getString(KEY_PIN, null)
            if (savedPin != null && !securePrefs.getBoolean(KEY_SETUP_COMPLETE, false)) {
                // Inconsistent state detected, clear everything
                securePrefs.edit().clear().commit()
                resetVerificationStateInternal()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading state: ${e.message}")
            resetVerificationStateInternal()
        }
    }

    private fun saveState() {
        securePrefs.edit().apply {
            putLong(KEY_LAST_VERIFICATION, lastPinVerification)
            putInt(KEY_VERIFICATION_COUNT, verificationCount)
            putLong(KEY_LAST_RESET, lastStateReset)
            putBoolean(KEY_IS_LOCKED, isLocked)
        }.apply()
    }

    suspend fun setPin(pin: String) = stateMutex.withLock {
        try {
            require(pin.length == 4 && pin.all { it.isDigit() }) {
                "PIN must be 4 digits"
            }

            // Clear any existing PIN and state first
            securePrefs.edit().clear().commit()

            val currentTime = System.currentTimeMillis()
            // Save the new PIN and mark setup as complete atomically
            val success = securePrefs.edit()
                .putString(KEY_PIN, pin)
                .putBoolean(KEY_SETUP_COMPLETE, true)
                .putLong(KEY_LAST_VERIFICATION, 0)
                .putInt(KEY_VERIFICATION_COUNT, 0)
                .putLong(KEY_LAST_RESET, currentTime)
                .putBoolean(KEY_IS_LOCKED, false)
                .commit()

            if (!success) {
                throw IllegalStateException("Failed to save PIN")
            }

            lastPinVerification = 0
            lastStateReset = currentTime
            verificationCount = 0
            isLocked = false
            
            Log.d(TAG, "PIN set successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting PIN: ${e.message}")
            throw e
        }
    }

    suspend fun verifyPin(pin: String): Boolean = stateMutex.withLock {
        if (pin.length != 4 || !pin.all { it.isDigit() }) {
            Log.e(TAG, "Invalid PIN format")
            return false
        }

        try {
            val currentTime = System.currentTimeMillis()
            
            // Check if locked
            if (isLocked) {
                if (currentTime - lastStateReset < VERIFICATION_RESET_DELAY) {
                    Log.d(TAG, "PIN verification blocked - in cooldown")
                    return false
                } else {
                    resetVerificationStateInternal()
                }
            }
            
            // Prevent rapid verification attempts
            if (currentTime - lastPinVerification < MIN_VERIFICATION_INTERVAL) {
                Log.d(TAG, "PIN verification blocked - too rapid")
                return false
            }
            
            // Check verification count
            if (verificationCount >= MAX_VERIFICATIONS) {
                if (currentTime - lastStateReset >= VERIFICATION_RESET_DELAY) {
                    resetVerificationStateInternal()
                } else {
                    isLocked = true
                    saveState()
                    Log.d(TAG, "PIN verification blocked - max attempts reached")
                    return false
                }
            }

            // Get saved PIN and setup state
            val savedPin = securePrefs.getString(KEY_PIN, null)
            val isSetupComplete = securePrefs.getBoolean(KEY_SETUP_COMPLETE, false)

            if (savedPin == null || !isSetupComplete) {
                Log.e(TAG, "No valid PIN found in secure storage")
                return false
            }
            
            // Update verification state
            lastPinVerification = currentTime
            
            val isValid = savedPin == pin
            
            if (isValid) {
                resetVerificationStateInternal()
                Log.d(TAG, "PIN verified successfully")
            } else {
                verificationCount++
                if (verificationCount >= MAX_VERIFICATIONS) {
                    isLocked = true
                }
                saveState()
                Log.d(TAG, "PIN verification failed - attempt $verificationCount of $MAX_VERIFICATIONS")
            }

            return isValid
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying PIN: ${e.message}")
            return false
        }
    }

    suspend fun isPinSetup(): Boolean = stateMutex.withLock {
        try {
            return securePrefs.getBoolean(KEY_SETUP_COMPLETE, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking PIN setup: ${e.message}")
            return false
        }
    }

    suspend fun clearPin() = stateMutex.withLock {
        try {
            securePrefs.edit()
                .remove(KEY_PIN)
                .putBoolean(KEY_SETUP_COMPLETE, false)
                .putLong(KEY_LAST_VERIFICATION, 0)
                .putInt(KEY_VERIFICATION_COUNT, 0)
                .putLong(KEY_LAST_RESET, System.currentTimeMillis())
                .putBoolean(KEY_IS_LOCKED, false)
                .apply()
            
            lastPinVerification = 0
            lastStateReset = System.currentTimeMillis()
            verificationCount = 0
            isLocked = false
            
            Log.d(TAG, "PIN cleared successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing PIN: ${e.message}")
            throw e
        }
    }

    suspend fun resetAttempts() {
        stateMutex.withLock {
            verificationCount = 0
            isLocked = false
            lastPinVerification = 0L
            lastStateReset = System.currentTimeMillis()
            saveState()
        }
    }

    private fun resetVerificationStateInternal() {
        try {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastStateReset >= MIN_VERIFICATION_INTERVAL) {
                lastStateReset = currentTime
                lastPinVerification = 0
                verificationCount = 0
                isLocked = false
                
                securePrefs.edit()
                    .putLong(KEY_LAST_VERIFICATION, 0)
                    .putInt(KEY_VERIFICATION_COUNT, 0)
                    .putLong(KEY_LAST_RESET, currentTime)
                    .putBoolean(KEY_IS_LOCKED, false)
                    .apply()
                
                Log.d(TAG, "Verification state reset successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting verification state: ${e.message}")
        }
    }

    suspend fun getVerificationState(): VerificationState = stateMutex.withLock {
        try {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastVerification = currentTime - lastPinVerification
            val timeSinceLastReset = currentTime - lastStateReset
            
            // Auto reset if cooldown period has passed
            if (isLocked && timeSinceLastReset >= VERIFICATION_RESET_DELAY) {
                resetVerificationStateInternal()
            }
            
            return VerificationState(
                canVerify = !isLocked && 
                           timeSinceLastVerification >= MIN_VERIFICATION_INTERVAL && 
                           verificationCount < MAX_VERIFICATIONS,
                attemptsRemaining = maxOf(0, MAX_VERIFICATIONS - verificationCount),
                cooldownTimeMs = if (isLocked) 
                    maxOf(0L, VERIFICATION_RESET_DELAY - timeSinceLastReset)
                else 
                    0L,
                isLocked = isLocked
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting verification state: ${e.message}")
            throw e
        }
    }

    data class VerificationState(
        val canVerify: Boolean,
        val attemptsRemaining: Int,
        val cooldownTimeMs: Long,
        val isLocked: Boolean
    )

    // Add these helper methods needed by LockScreenViewModel
    fun hasPinEnabled(): Boolean {
        return securePrefs.getBoolean(KEY_ENABLED, true)
    }
    
    fun getMaxPinAttempts(): Int {
        return MAX_VERIFICATIONS
    }
    
    fun getPinCooldownSeconds(): Int {
        return (VERIFICATION_RESET_DELAY / 1000).toInt()
    }
}
