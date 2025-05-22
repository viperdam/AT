package com.viperdam.kidsprayer.ads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.viperdam.kidsprayer.PrayerApp
import com.viperdam.kidsprayer.ui.lock.LockScreenActivity

/**
 * Boot receiver specifically for initializing the ad system with child-directed settings
 * when the device boots up. This ensures that ad restrictions are in place immediately
 * and continuously at system startup.
 */
class AdBootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "AdBootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received boot completed action: ${intent.action}")
        
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
             // Delay slightly to allow app components to potentially initialize
             Handler(Looper.getMainLooper()).postDelayed({
                 Log.d(TAG, "Handling boot completed event for ads")
                 applyAdContentRatingG(context.applicationContext) // Keep this

                 // Check if lock screen was active before reboot
                 val prefs = context.getSharedPreferences(LockScreenActivity.LOCK_SCREEN_PREFS, Context.MODE_PRIVATE)
                 val wasLockScreenActive = prefs.getBoolean(LockScreenActivity.KEY_LOCK_SCREEN_ACTIVE, false)

                 if (wasLockScreenActive) {
                     Log.d(TAG, "Lock screen was active before reboot. Requesting ad preload.")
                     try {
                         // Get AdManager instance (adjust based on your DI setup/Application class)
                         val adManager = (context.applicationContext as? PrayerApp)?.adManager
                         if (adManager != null) {
                              adManager.preloadRewardedAd()
                              Log.d(TAG, "Ad preload requested via AdManager after boot.")
                         } else {
                             Log.w(TAG, "AdManager instance not found after boot.")
                         }
                     } catch (e: Exception) {
                         Log.e(TAG, "Error preloading ad after boot", e)
                     }
                 } else {
                      Log.d(TAG, "Lock screen was not active before reboot.")
                 }

             }, 10000) // 10-second delay
        }
    }
    
    /**
     * Apply G-rated content settings at the global level
     */
    private fun applyAdContentRatingG(context: Context) {
        try {
            // Initialize Mobile Ads first if needed
            MobileAds.initialize(context) { 
                Log.d(TAG, "MobileAds initialized from boot receiver")
            }
            
            // Apply G-rated content settings
            val requestConfiguration = MobileAds.getRequestConfiguration().toBuilder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .build()
            
            MobileAds.setRequestConfiguration(requestConfiguration)
            
            // Store enforcement in shared preferences for tracking
            context.getSharedPreferences("ad_settings_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("child_directed_enforced", true)
                .putLong("last_enforced_time", System.currentTimeMillis())
                .apply()
                
            Log.d(TAG, "Applied ad content rating G settings from boot")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying ad content rating settings at boot: ${e.message}")
        }
    }
} 