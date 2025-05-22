package com.viperdam.kidsprayer.service

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.ui.lock.LockScreenActivity
import javax.inject.Inject

/**
 * A BroadcastReceiver that specifically handles detection of lockscreen bypasses
 * and performs recovery when needed by restoring the lockscreen with the last valid prayer.
 */
class BypassDetectionReceiver : BroadcastReceiver {
    private var completionManager: PrayerCompletionManager
    
    companion object {
        private const val TAG = "BypassDetection"
        private var lastRecoveryAttempt = 0L
        private const val MIN_RECOVERY_INTERVAL = 3000L // 3 seconds minimum between recovery attempts
    }
    
    // Constructor for manual instantiation
    constructor(completionManager: PrayerCompletionManager) {
        this.completionManager = completionManager
    }
    
    // Required default constructor for when the receiver is instantiated by the system
    constructor() {
        this.completionManager = PrayerCompletionManager.getInstance(null)
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        // Initialize completionManager if needed
        if (completionManager.context == null) {
            completionManager.setContext(context)
        }
        
        if (intent.action == Intent.ACTION_SCREEN_ON || 
            intent.action == Intent.ACTION_USER_PRESENT) {
            
            // Don't check too frequently
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastRecoveryAttempt < MIN_RECOVERY_INTERVAL) {
                return
            }
            
            // Check if lockscreen should be active
            if (completionManager.isLockScreenActive()) {
                // Check if lockscreen is visible
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                var lockScreenVisible = false
                
                try {
                    @Suppress("DEPRECATION")
                    val tasks = am.getRunningTasks(1)
                    if (!tasks.isNullOrEmpty()) {
                        val topActivity = tasks[0].topActivity
                        lockScreenVisible = topActivity?.className?.contains("LockScreenActivity") == true
                    }
                } catch (e: Exception) {
                    // Security exception possible on some devices
                    Log.e(TAG, "Error checking running tasks: ${e.message}")
                }
                
                if (!lockScreenVisible) {
                    // Potential bypass detected, attempt recovery
                    if (completionManager.detectBypass()) {
                        // Get the current active prayer first (most reliable source)
                        val activePrayer = completionManager.getActivePrayer()
                        
                        // If active prayer is valid, use it; otherwise fall back to last valid prayer
                        val (prayerName, rakaatCount) = if (!activePrayer.first.isNullOrEmpty() && activePrayer.second > 0) {
                            activePrayer
                        } else {
                            completionManager.getLastValidPrayer()
                        }
                        
                        if (!prayerName.isNullOrEmpty() && rakaatCount > 0) {
                            Log.w(TAG, "Detected lockscreen bypass, recovering with prayer: $prayerName with $rakaatCount rakaats")
                            lastRecoveryAttempt = currentTime
                            
                            // Launch service to handle recovery
                            val serviceIntent = Intent(context, LockScreenService::class.java).apply {
                                putExtra("prayer_name", prayerName)
                                putExtra("rakaat_count", rakaatCount)
                                putExtra("is_recovery", true)
                            }
                            
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(serviceIntent)
                                } else {
                                    context.startService(serviceIntent)
                                }
                                
                                // Also directly launch activity as a backup
                                val activityIntent = Intent(context, LockScreenActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    putExtra("prayer_name", prayerName)
                                    putExtra("rakaat_count", rakaatCount)
                                    putExtra("from_receiver", true)
                                    putExtra("is_recovery", true)
                                }
                                context.startActivity(activityIntent)
                                
                                // Clear the bypass detection
                                completionManager.clearBypassDetection()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error starting recovery service/activity: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }
} 