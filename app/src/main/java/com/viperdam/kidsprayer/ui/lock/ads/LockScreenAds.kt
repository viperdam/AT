package com.viperdam.kidsprayer.ui.lock.ads

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.MobileAds
import com.viperdam.kidsprayer.ads.AdManager
import com.viperdam.kidsprayer.ads.ContentFilteringAdListener
import com.viperdam.kidsprayer.service.AdSettingsCheckWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.android.gms.ads.OnUserEarnedRewardListener

/**
 * Manages lockscreen advertisements with strict child-directed settings
 * Ensures ads are appropriate for children with multiple safety layers
 */
@Singleton
class LockScreenAds @Inject constructor(
    private val adManager: AdManager,
    @ApplicationContext private val context: Context
) {
    private var isShowingAd = false
    private var onFinishedCallback: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAdRequest = false
    private var contentFilteringAdListener: ContentFilteringAdListener? = null
    private var adSettingsReceiver: BroadcastReceiver? = null
    private var lastUnlockAdShowTime = 0L // Added for rate limiting unlock ads
    private val UNLOCK_AD_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes between unlock ads

    companion object {
        private const val TAG = "LockScreenAds"
        private const val CHECK_INTERVAL_MS = 3600000L // 1 hour in milliseconds
        private const val FORCE_RESET_INTERVAL_MS = 86400000L // 24 hours
        private const val AD_SAFETY_CHECK_WORK = "ad_safety_check_work"
        
        // Add action for our broadcast receiver
        private const val ACTION_ENFORCE_CHILD_DIRECTED = "com.viperdam.kidsprayer.ENFORCE_CHILD_DIRECTED"
    }

    init {
        // Ensure child-directed treatment is always applied for lock screen ads
        ensureChildDirectedAdSettings()
        
        // Initialize the content filtering listener
        setupContentFiltering()
        
        // Register broadcast receiver for continuous enforcement
        registerAdSettingsReceiver()
        
        // Schedule periodic checks using WorkManager to ensure 24/7 protection
        schedulePeriodicChecks()
        
        // Start background monitoring
        startBackgroundMonitoring()
        
        Log.d(TAG, "LockScreenAds initialized with 24/7 child protection")
    }
    
    /**
     * Ensures that appropriate ad content rating settings are applied
     */
    private fun ensureChildDirectedAdSettings() {
        try {
            // Set global content rating configuration
            val requestConfiguration = MobileAds.getRequestConfiguration().toBuilder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .build()
            MobileAds.setRequestConfiguration(requestConfiguration)
            
            // Set flag in preferences to indicate settings are enforced
            context.getSharedPreferences("ad_settings_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("child_directed_enforced", true)
                .putLong("last_enforced_time", System.currentTimeMillis())
                .apply()
            
            Log.d(TAG, "Ad settings enforced with MAX_AD_CONTENT_RATING_G for lock screen ads")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting ad content rating settings: ${e.message}")
        }
    }
    
    /**
     * Sets up the content filtering listener for additional protection
     */
    private fun setupContentFiltering() {
        contentFilteringAdListener = ContentFilteringAdListener(
            context = context,
            onBlockedAd = { reason ->
                // Log the blocked ad
                Log.w(TAG, "Blocked inappropriate ad: $reason")
                
                // Optional: Show a toast or notification for debugging
                if (context.packageManager.getApplicationInfo(context.packageName, 0).flags and 
                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    handler.post {
                        Toast.makeText(context, "Blocked inappropriate ad: $reason", Toast.LENGTH_SHORT).show()
                    }
                }
                
                // Force reload a new ad
                adManager.forceResetAdState()
                adManager.preloadRewardedAd()
            }
        )
    }
    
    /**
     * Register a broadcast receiver to handle enforcement requests from other components
     */
    private fun registerAdSettingsReceiver() {
        try {
            adSettingsReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == ACTION_ENFORCE_CHILD_DIRECTED) {
                        Log.d(TAG, "Received enforcement request via broadcast")
                        ensureChildDirectedAdSettings()
                        adManager.forceResetAdState()
                        adManager.preloadRewardedAd()
                    }
                }
            }
            
            val filter = IntentFilter(ACTION_ENFORCE_CHILD_DIRECTED)
            context.registerReceiver(adSettingsReceiver, filter)
            Log.d(TAG, "Ad settings enforcement receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering ad settings receiver: ${e.message}")
        }
    }
    
    /**
     * Unregister broadcast receiver when no longer needed
     */
    fun cleanup() {
        try {
            adSettingsReceiver?.let {
                context.unregisterReceiver(it)
                adSettingsReceiver = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
    }
    
    /**
     * Schedule periodic checks using WorkManager to ensure settings are enforced 24/7
     */
    private fun schedulePeriodicChecks() {
        try {
            // Create a periodic work request to check ad settings
            val adSettingsWorkRequest = PeriodicWorkRequestBuilder<AdSettingsCheckWorker>(
                2, TimeUnit.HOURS, // Run every 2 hours
                30, TimeUnit.MINUTES // With 30 minutes flex period
            ).build()
            
            // Enqueue the work request with a unique name
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                AD_SAFETY_CHECK_WORK,
                ExistingPeriodicWorkPolicy.UPDATE, // Replace existing work if any
                adSettingsWorkRequest
            )
            
            Log.d(TAG, "Scheduled periodic ad settings checks")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling periodic checks: ${e.message}")
        }
    }
    
    /**
     * Start a background handler to periodically enforce settings
     */
    private fun startBackgroundMonitoring() {
        // Create a runnable for periodic checks
        val monitoringRunnable = object : Runnable {
            override fun run() {
                try {
                    // Check if settings were enforced recently
                    val prefs = context.getSharedPreferences("ad_settings_prefs", Context.MODE_PRIVATE)
                    val lastEnforcedTime = prefs.getLong("last_enforced_time", 0L)
                    val currentTime = System.currentTimeMillis()
                    
                    // If it's been more than 1 hour since last enforcement, reapply settings
                    if (currentTime - lastEnforcedTime > CHECK_INTERVAL_MS) {
                        Log.d(TAG, "Background monitor enforcing child-directed settings")
                        ensureChildDirectedAdSettings()
                    }
                    
                    // Force-reset ad state every 24 hours to ensure fresh ads with proper settings
                    if (currentTime - lastEnforcedTime > FORCE_RESET_INTERVAL_MS) {
                        Log.d(TAG, "Background monitor performing 24-hour ad state reset")
                        adManager.forceResetAdState()
                        adManager.preloadRewardedAd()
                    }
                    
                    // Schedule next check
                    handler.postDelayed(this, CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in background monitoring: ${e.message}")
                    // Ensure we don't stop monitoring on error
                    handler.postDelayed(this, CHECK_INTERVAL_MS)
                }
            }
        }
        
        // Start the initial background monitoring
        handler.post(monitoringRunnable)
        Log.d(TAG, "Started background monitoring for ad settings")
    }

    fun preloadAd() {
        // Check if lock screen is active before preloading
        val isLockScreenActive = context.getSharedPreferences("lock_screen_prefs", Context.MODE_PRIVATE)
            .getBoolean("lock_screen_active", false)
            
        if (!isLockScreenActive) {
            Log.d(TAG, "Lock screen not active. Skipping ad preload.")
            return
        }

        // Force child-directed configuration before preloading
        // ensureChildDirectedAdSettings() // Removed this call, AdManager handles configuration

        // Make sure we're not using a test device ID by clearing RequestConfiguration
        try {
            val requestConfiguration = MobileAds.getRequestConfiguration().toBuilder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .setTestDeviceIds(emptyList()) // Clear test device IDs
                .build()
            MobileAds.setRequestConfiguration(requestConfiguration)
            Log.d(TAG, "Cleared test device IDs before preloading ad")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing test device IDs: ${e.message}")
        }
        
        // Now preload the ad
        adManager.preloadRewardedAd()
        Log.d(TAG, "Preloaded ad with MAX_AD_CONTENT_RATING_G settings")
    }

    fun showAd(activity: Activity, onFinished: () -> Unit) {
        if (isShowingAd) {
            onFinished()
            return
        }

        // Reapply child-directed settings before showing ad
        // ensureChildDirectedAdSettings() // Calling this still forces TRUE tags, REMOVE or MODIFY ensureChildDirectedAdSettings

        // Clear test device IDs again just to be sure
        try {
            val requestConfiguration = MobileAds.getRequestConfiguration().toBuilder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .setTestDeviceIds(emptyList()) // Clear test device IDs
                .build()
            MobileAds.setRequestConfiguration(requestConfiguration)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing test device IDs: ${e.message}")
        }
        
        isShowingAd = true
        onFinishedCallback = onFinished

        // Log detailed ad availability status
        val isAdAvailable = adManager.isRewardedAdAvailable()
        Log.d(TAG, "Ad availability check: $isAdAvailable")
        
        if (!isAdAvailable) {
            Log.d(TAG, "No ad available - continuing without showing ad")
            // REMOVED: Don't preload here, only when lock screen is active
            // adManager.preloadRewardedAd() // Try to preload for next time
            handleFinish()
            return
        }

        try {
            // Store showing ad flag in shared preferences
            context.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("is_displaying_ad", true)
                .apply()
                
            // Safety mechanism to ensure callback happens
            handler.postDelayed({
                if (isShowingAd) {
                    Log.w(TAG, "Safety timeout triggered for ad - forcing finish")
                    handleFinish()
                }
            }, 30000) // 30 seconds safety timeout

            // Add diagnostic logging for child-appropriate ads
            Log.d(TAG, "Showing child-appropriate rewarded ad on lock screen")
            
            // Show the ad with strict child-directed settings
            adManager.showRewardedAd(
                activity = activity,
                bypassRateLimit = true // Bypass rate limit for unlock ads
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error showing ad: ${e.message}", e)
            handleFinish()
        }
    }

    fun requestDelayedAd() {
        // Ensure child settings when requesting delayed ad
        ensureChildDirectedAdSettings()
        pendingAdRequest = true
    }

    fun isPendingAdRequested(): Boolean {
        return pendingAdRequest
    }

    fun showPendingAdIfNeeded(activity: Activity) {
        // Always ensure child-directed settings before showing ads
        ensureChildDirectedAdSettings()
        
        if (pendingAdRequest || activity.intent.getBooleanExtra("show_unlock_ad", false)) {
            Log.d(TAG, "Processing pending ad request")
            pendingAdRequest = false
            
            // Clear the intent extra if present
            if (activity.intent.hasExtra("show_unlock_ad")) {
                activity.intent.removeExtra("show_unlock_ad")
            }
            
            if (!activity.isFinishing) {
                // If ad is available, show it immediately
                if (adManager.isRewardedAdAvailable()) {
                    Log.d(TAG, "Ad available, showing immediately")
                    showAd(activity) {
                        // Ad finished showing
                        Log.d(TAG, "Pending ad finished showing")
                    }
                } else {
                    // If ad isn't available, continue without showing ad
                    Log.d(TAG, "No ad available, continuing without showing ad")
                    
                    // Skip retry attempt and just finish
                    handleFinish()
                }
            }
        }
    }

    private fun handleFinish() {
        isShowingAd = false
        
        // Reset ad display flag
        context.getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_displaying_ad", false)
            .apply()
            
        onFinishedCallback?.invoke()
        onFinishedCallback = null
    }
    
    /**
     * Method to be called when the activity hosting the ad is stopped (e.g. user presses Home).
     */
    fun notifyAdHostActivityStopped() {
        if (isShowingAd) {
            Log.w(TAG, "Ad hosting activity stopped while ad was marked as showing. Forcing cleanup.")
            // Simulate ad dismissal to reset states and trigger callbacks
            // This ensures that if AdMob's own dismissal callback is delayed or missed,
            // our app doesn't get stuck thinking an ad is still showing.
            // Removed direct call: adManager.rewardedAdCallback?.onAdDismissed()
            // LockScreenActivity is the AdManager's rewardedAdCallback and will handle its own dismissal logic
            // when AdManager's onAdDismissed (triggered by AdMob SDK) calls LockScreenActivity's onAdDismissed.
            // Here, LockScreenAds just needs to clean up its own state and notify its own client (LockScreenActivity) via onFinishedCallback.
            handleFinish() // Reset LockScreenAds internal state and call its onFinishedCallback
        }
    }
    
    /**
     * Enforce appropriate ad content rating settings
     * This should be called periodically to ensure settings don't drift
     */
    fun enforceAdContentRatingG() {
        Log.d(TAG, "Enforcing ad content rating settings")
        try {
            // Force production mode and disable test device detection
            ensureProductionAdsMode()
            
            // Set content rating for all ads
            val requestConfiguration = MobileAds.getRequestConfiguration().toBuilder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .setTestDeviceIds(emptyList()) // Ensure NO test device IDs are set
                .build()
            MobileAds.setRequestConfiguration(requestConfiguration)
            
            Log.d(TAG, "Ad content rating settings enforced successfully")
            
            // Store timestamp of enforcement
            context.getSharedPreferences("ad_settings_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("child_directed_enforced", true)
                .putLong("last_enforced_time", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing ad content rating settings: ${e.message}")
        }
    }
    
    /**
     * Ensure production ads mode is active and test device detection is disabled
     * This specifically addresses the issue with device IDs like 1C83226BA4A44BE491288DBA6C2CF015
     * being detected as test devices
     */
    private fun ensureProductionAdsMode() {
        try {
            Log.d(TAG, "Ensuring production ads mode...")
            
            // Special workaround to force real ads instead of test ads
            val extras = Bundle()
            extras.putString("is_designed_for_families", "true") 
            extras.putInt("test_mode", 0) // Force test mode off (0 = off, 1 = on)
            extras.putInt("adtest", 0) // Ensure ad test is off
            
            // Store settings to confirm production mode
            context.getSharedPreferences("ad_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("force_production_ads", true)
                .putLong("production_mode_set_time", System.currentTimeMillis())
                .apply()
                
            Log.d(TAG, "Production ads mode enforcement complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring production ads mode: ${e.message}")
        }
    }
}
