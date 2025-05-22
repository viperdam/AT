package com.viperdam.kidsprayer.ui.lock.screen

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.Window
import com.viperdam.kidsprayer.databinding.ActivityLockScreenBinding
import com.viperdam.kidsprayer.service.LockService
import com.viperdam.kidsprayer.ui.lock.LockScreenViewModel

class LockScreenState(
    private val context: Activity,
    private val binding: ActivityLockScreenBinding,
    private val viewModel: LockScreenViewModel,
    private val window: Window
) {
    companion object {
        private const val TAG = "LockScreenState"
        private const val PREFS_NAME = "LockScreenPrefs"
        private const val KEY_PINNING_ENABLED = "pinning_enabled"
    }

    private var screenReceiver: BroadcastReceiver? = null
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun setupScreenState() {
        // Use modern window flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.setShowWhenLocked(true)
            context.setTurnScreenOn(true)
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(context, null)
            
            // Try to start lock task mode with better error handling
            try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                
                // Check if we should be in pinning mode
                if (prefs.getBoolean(KEY_PINNING_ENABLED, false)) {
                    if (activityManager.getLockTaskModeState() == android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
                        // Try to start lock task mode
                        if (devicePolicyManager.isLockTaskPermitted(context.packageName)) {
                            context.startLockTask()
                            Log.d(TAG, "Lock task mode started successfully")
                        } else {
                            // Fall back to screen pinning if not permitted for lock task
                            context.startLockTask()
                            Log.d(TAG, "Started screen pinning as fallback")
                        }
                    } else {
                        Log.d(TAG, "Already in lock task mode")
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception starting lock task mode: ${e.message}")
                // App is not a device owner or not whitelisted for lock task mode
            } catch (e: Exception) {
                Log.e(TAG, "Error starting lock task mode: ${e.message}")
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        registerScreenReceiver()
        startLockService()
    }

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (!viewModel.uiState.value.isLockedOut) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (!viewModel.uiState.value.isLockedOut) {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(screenReceiver, filter)
        }
    }

    private fun startLockService() {
        if (!viewModel.uiState.value.isLockedOut) {
            try {
                context.startService(Intent(context, LockService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Error starting lock service: ${e.message}")
            }
        }
    }

    fun enablePinning(enable: Boolean) {
        prefs.edit().putBoolean(KEY_PINNING_ENABLED, enable).apply()
        if (enable) {
            setupScreenState() // Try to enter pinning mode immediately
        } else {
            try {
                context.stopLockTask()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping lock task mode: ${e.message}")
            }
        }
    }

    fun isPinningEnabled(): Boolean {
        return prefs.getBoolean(KEY_PINNING_ENABLED, false)
    }

    fun cleanup() {
        screenReceiver?.let {
            try {
                context.unregisterReceiver(it)
                screenReceiver = null
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
        try {
            context.stopService(Intent(context, LockService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service: ${e.message}")
        }
    }
}
