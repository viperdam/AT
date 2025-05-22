package com.viperdam.kidsprayer.ads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receiver for handling ad refresh alarms
 * Ensures ads are refreshed hourly even if the device goes to sleep
 */
@AndroidEntryPoint
class AdRefreshReceiver : BroadcastReceiver() {
    
    @Inject
    lateinit var adManager: AdManager
    
    companion object {
        private const val TAG = "AdRefreshReceiver"
        const val ACTION_REFRESH_AD = "com.viperdam.kidsprayer.ACTION_REFRESH_AD"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        // Use the injected adManager directly
        if (intent.action == ACTION_REFRESH_AD) {
            Log.d(TAG, "Received ad refresh broadcast")
            
            val result = goAsync()
            val scope = CoroutineScope(Dispatchers.IO)
            
            scope.launch {
                try {
                    // Check if lock screen is still active based on session start time?
                    // Or use a simpler check like a flag in shared prefs set by LockService
                    val prefs = context.getSharedPreferences("lock_screen_prefs", Context.MODE_PRIVATE)
                    val isLockScreenActive = prefs.getBoolean("lock_screen_active", false)
                    
                    if (isLockScreenActive) {
                        Log.d(TAG, "Lock screen is active, forcing ad state reset and preload.")
                        // Use the public method on the AdManager instance
                        adManager.forceResetAdState()
                        // Preloading is handled by the reset logic now
                    } else {
                        Log.d(TAG, "Lock screen is not active, skipping ad refresh.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during ad refresh handling", e)
                } finally {
                    // Must call finish() so the system knows we are done processing
                    result.finish()
                    Log.d(TAG, "Finished processing ad refresh broadcast.")
                }
            }
        }
    }
} 