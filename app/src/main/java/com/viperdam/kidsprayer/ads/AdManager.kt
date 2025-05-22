package com.viperdam.kidsprayer.ads

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.google.android.gms.ads.*
import com.google.android.gms.ads.initialization.AdapterStatus
import com.google.android.gms.ads.initialization.InitializationStatus
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener
// Keep AdMob AdView if using banners elsewhere
import com.google.android.gms.ads.AdView 
// Unity Ads Imports
// Removed: com.unity3d.ads.IUnityAdsInitializationListener
// Removed: com.unity3d.ads.IUnityAdsLoadListener
// Removed: com.unity3d.ads.IUnityAdsShowListener
// Removed: com.unity3d.ads.UnityAds
// Removed: com.unity3d.ads.UnityAdsShowOptions
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.viperdam.kidsprayer.BuildConfig // Use this for test mode
import com.viperdam.kidsprayer.R
import android.os.PowerManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import com.viperdam.kidsprayer.data.PreferencesKeys
import com.viperdam.kidsprayer.data.dataStore
import com.viperdam.kidsprayer.data.getOrDefault
import com.viperdam.kidsprayer.security.DeviceAdminReceiver // Keep if used by banner click handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.pow
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


@Singleton
class AdManager @Inject constructor(
    private val context: Context
) { // Removed: IUnityAdsInitializationListener implementation

    // --- State Variables ---
    private var rewardedAdCallback: RewardedAdCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val adManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var adClickHandler: (() -> Unit)? = null

    // Ad Loading State - Use StateFlow
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val adLoadLock = Any()
    private val _isRewardedAdLoaded = MutableStateFlow(false)
    val isRewardedAdLoaded: StateFlow<Boolean> = _isRewardedAdLoaded.asStateFlow()
    private val _lastAdLoadTimestamp = MutableStateFlow(0L)
    val lastAdLoadTimestamp: StateFlow<Long> = _lastAdLoadTimestamp.asStateFlow()

    // AdMob Rewarded Ad object
    private var admobRewardedAd: RewardedAd? = null

    // Flag to indicate if the lock screen feature is currently active context for ads
    private var isLockScreenContextActive = false

    // Initialization State
    private val isAdMobInitialized = AtomicBoolean(false)
    // Removed: private var isUnityAdsInitialized = false
    // Removed: private val unityInitializationLock = Any()
    // Removed: private val unityInitCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var shouldPreloadAdsOnInit = true
    private val isInitialized = AtomicBoolean(false)
    private val initializationLock = Any()
    private val initCallbacks = mutableListOf<(Boolean) -> Unit>()

    // Retry & Circuit Breaker
    private var retryAttempt = 0
    private val maxRetries = 5 // Max load retries
    private val baseRetryDelayMs = 5000L // Initial retry delay
    private var consecutiveLoadFailures = 0 // For circuit breaker
    private val maxConsecutiveLoadFailures = 5 // Threshold for circuit breaker
    private var circuitBreakerUntilTimestamp = 0L // When the circuit breaker opens until
    private val circuitBreakerDurationMs = 10 * 60 * 1000L // 10 minutes
    private var lastLoadErrorTime: Long = 0L // Timestamp of the last load error

    // Rate Limiting & Usage Tracking (Keep existing logic if needed for other purposes)
    private var lastShowTime: Long = 0L
    private var lastBannerDisplayTime: Long = 0L
    private var dailyAdCount: Int = 0
    private var hourlyAdCount: Int = 0
    private var lastDay: Int = 0
    private var lastHour: Int = 0
    private var lastAppBackgroundTime: Long = 0L
    private var lastDateString: String = getCurrentDateString()
    
    // RESTORE: lastAdRequestTime definition
    private var lastAdRequestTime = 0L // Track time between ad requests

    // Lock Screen & Lifecycle
    private var lockScreenSessionStartTime: Long = 0L
    private val lockStateLock = Any()

    // Network Monitoring
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkCallbackRegistered = false

    // --- Constants ---
    // Removed: private val UNITY_GAME_ID = "5837617"
    // Removed: private val REWARDED_PLACEMENT_ID = "Rewarded_Android"
    // Removed: private val TEST_MODE = BuildConfig.DEBUG

    // Other constants from original file
    private val ONE_HOUR_MS = 60 * 60 * 1000L
    private val APP_BACKGROUND_THRESHOLD = 30 * 60 * 1000L
    // Use ONE_HOUR_MS for ad age
    private val MAX_REWARD_AD_AGE_MS = ONE_HOUR_MS 
    private val minRequestInterval = 5000L // Min interval between load requests
    private val AD_LOAD_TIMEOUT_MS = 120000 // Timeout for manual check (optional)

    // AdMob Ad Unit IDs
    private val ADMOB_REWARDED_AD_UNIT_ID_PROD = "ca-app-pub-6928555061691394/4036171672"
    private val ADMOB_REWARDED_AD_UNIT_ID_DEBUG = "ca-app-pub-3940256099942544/5224354917"
    private val CURRENT_ADMOB_REWARDED_AD_UNIT_ID: String
        get() = if (BuildConfig.DEBUG) ADMOB_REWARDED_AD_UNIT_ID_DEBUG else ADMOB_REWARDED_AD_UNIT_ID_PROD

    // --- Initialization ---
    init {
        if (isMainProcess()) {
            try {
                // Initialize WebView process suffix
                android.webkit.WebView.setDataDirectorySuffix("ads_" + Process.myPid())
                
                adManagerScope.launch {
                    try {
                        loadState() // Load counters/timestamps
                        resetCountersIfNeeded()
                        checkForDateChange()
                        // Load lock screen state from DataStore if needed
                        // ... (original lock screen state loading logic) ...
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading initial AdManager state", e)
                        loadStateFromPrefs() // Fallback
                        resetCountersIfNeeded()
                        checkForDateChange()
                    }
                }
                Log.d(TAG, "AdManager instance created. Initializing Ad SDKs...")
                // Start combined initialization
                 initializeAdSdks() // Start initialization automatically
            } catch (e: Exception) {
                Log.e(TAG, "Error during AdManager init: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Skipping AdManager initialization in non-main process")
        }
    }

    // Combined SDK Initialization
    fun initializeAdSdks(onComplete: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "Starting combined Ad SDK initialization...")
        // Add external callback if provided
        if (onComplete != null) {
            synchronized(initializationLock) {
                initCallbacks.add(onComplete)
            }
        }
        // Launch initialization tasks
        adManagerScope.launch {
            val admobSuccess = initializeMobileAdsSdkInternal()
            // Removed: val unitySuccess = initializeUnityAdsSdkInternal()
            val overallSuccess = admobSuccess // Success now depends only on AdMob

            Log.d(TAG, "Combined Ad SDK Initialization Result: AdMob=$admobSuccess, Overall=$overallSuccess")

            // Notify external callbacks
            val callbacksToRun: List<(Boolean) -> Unit>
            synchronized(initializationLock) {
                isInitialized.set(overallSuccess) // Set combined status
                callbacksToRun = initCallbacks.toList()
                initCallbacks.clear()
            }
            withContext(Dispatchers.Main) {
                callbacksToRun.forEach { it(overallSuccess) }
            }

            // Preload after successful initialization
            if (overallSuccess && shouldPreloadAdsOnInit) {
                Log.d(TAG, "AdMob SDK initialized successfully.")
                shouldPreloadAdsOnInit = false // Preload only once on initial init
            } else if (!overallSuccess) {
                Log.e(TAG, "AdMob SDK failed to initialize.")
            }
        }
    }

    // Internal AdMob Initialization
    private suspend fun initializeMobileAdsSdkInternal(): Boolean = suspendCancellableCoroutine { continuation ->
        if (isAdMobInitialized.get()) {
            Log.d(TAG, "AdMob SDK already initialized.")
            if (continuation.isActive) continuation.resume(true)
            return@suspendCancellableCoroutine
        }

        Log.d(TAG, "Initializing AdMob SDK...")
        // Ensure initialization happens on the main thread
        handler.post {
             if (!isAdMobInitialized.get()){ // Double check inside handler post
                 MobileAds.initialize(context) { initializationStatus ->
                     val statusMap = initializationStatus.adapterStatusMap
                     var allReady = true
                     for (adapterClass in statusMap.keys) {
                         val status = statusMap[adapterClass]
                         Log.d(TAG, "AdMob Adapter $adapterClass: ${status?.initializationState} (${status?.description})")
                         if (status?.initializationState != AdapterStatus.State.READY) {
                             allReady = false
                         }
                     }
                     Log.d(TAG, "AdMob SDK Initialization complete on main thread. All Adapters Ready: $allReady")
                     isAdMobInitialized.set(true)
                     setAdMobRequestConfiguration() // Apply child-directed settings etc.
                     if (continuation.isActive) continuation.resume(true) // Resume coroutine
                 }
             } else {
                  Log.d(TAG, "AdMob SDK was initialized between check and handler post.")
                  if (continuation.isActive) continuation.resume(true)
             }
        }
        // Handle cancellation
        continuation.invokeOnCancellation {
            Log.w(TAG, "AdMob initialization coroutine cancelled.")
        }
    }

    // Set AdMob specific configurations (Child-directed, etc.)
    private fun setAdMobRequestConfiguration() {
        val configuration = RequestConfiguration.Builder()
            .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE)
            .setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE)
            .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
            .build()
        MobileAds.setRequestConfiguration(configuration)
        Log.d(TAG, "Set AdMob RequestConfiguration for child-directed treatment.")
    }

    // --- Preloading ---

    // Public entry point for preloading all relevant ads
    fun preloadAds() {
        adManagerScope.launch {
            // Preload AdMob Rewarded Ad if in lock screen context
            preloadRewardedAd(isLockScreenTrigger = true) // Assume generic preloadAds calls are for lock screen
        }
    }

    // Specific preload function for AdMob Rewarded Ad
    fun preloadRewardedAd(isLockScreenTrigger: Boolean = false) { // Added isLockScreenTrigger
        adManagerScope.launch {
            // Only proceed if this preload is relevant to an active lock screen scenario
            if (!isLockScreenContextActive && isLockScreenTrigger) {
                Log.d(TAG, "preloadRewardedAd: Skipped - Lock screen context not active, but trigger requires it.")
                // If triggered specifically for lock screen but context isn't active, something is wrong.
                // However, if isLockScreenTrigger is false, it means it's a general call not tied to lock screen activation.
                // For now, let's assume isLockScreenTrigger means it *must* be active.
                // If isLockScreenTrigger is false, this check is bypassed.
            } else if (!isLockScreenContextActive && !isLockScreenTrigger) {
                 // This case could be a general preload not tied to lock screen, we might allow it
                 // For now, to be strict, let's only preload if lock screen context is active OR trigger is true (which implies context should be active)
                 // Let's refine: only load if lock screen context is active
            }

            if (!isLockScreenContextActive) {
                 Log.d(TAG, "preloadRewardedAd: Skipped - Lock screen context not currently active.")
                 return@launch
            }

            // Ensure AdMob SDK is initialized first
            if (!isAdMobInitialized.get()) {
                Log.w(TAG, "Cannot preload AdMob Rewarded Ad - SDK not initialized. Attempting init...")
                val success = initializeMobileAdsSdkInternal() // Try initializing AdMob again
                if (!success) {
                    Log.e(TAG, "AdMob SDK initialization failed during preload attempt.")
                    return@launch // Exit if init fails
                }
                // If init was successful, continue to load below
                Log.d(TAG, "AdMob SDK initialized successfully during preload, proceeding with load.")
            }

            // Check circuit breaker
            if (System.currentTimeMillis() < circuitBreakerUntilTimestamp) {
                val remainingSeconds = (circuitBreakerUntilTimestamp - System.currentTimeMillis()) / 1000
                Log.d(TAG, "preloadRewardedAd: Skipped - Circuit breaker active for $remainingSeconds seconds. UpdateAvailability=${UpdateAvailability.UNKNOWN}")
                return@launch
            }

            // Check loading status and availability using StateFlow values
            synchronized(adLoadLock) { 
                if (_isLoading.value) {
                    Log.d(TAG, "preloadRewardedAd: Skipped - Already loading.")
                    return@launch
                }
                if (_isRewardedAdLoaded.value) {
                    if (System.currentTimeMillis() - _lastAdLoadTimestamp.value > MAX_REWARD_AD_AGE_MS) {
                        Log.d(TAG, "preloadRewardedAd: Stale ad found during preload check. Discarding and forcing new load.")
                        admobRewardedAd = null
                        _isRewardedAdLoaded.value = false // Mark as not loaded to allow new load
                    } else {
                        Log.d(TAG, "preloadRewardedAd: Skipped - Ad already loaded and not stale.")
                        return@launch // Ad is loaded and fresh, so skip.
                    }
                }

                // Check time interval since last request
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastAdRequestTime < minRequestInterval) {
                    Log.d(TAG,"preloadRewardedAd: Skipped - Too soon since last request (${currentTime - lastAdRequestTime}ms). Min interval: $minRequestInterval ms")
                    return@launch
                }

                // Set loading flag and timestamp
                _isLoading.value = true // Update StateFlow
                lastAdRequestTime = currentTime
            }

            Log.d(TAG, "Preloading AdMob Rewarded Ad for unit ID: $CURRENT_ADMOB_REWARDED_AD_UNIT_ID")
            // Perform load on Main thread (AdMob SDK calls should be on main thread)
            withContext(Dispatchers.Main) {
                loadAdMobRewardedAdWithRetry()
            }
        }
    }

    // Internal load function with retry logic (must be called on Main Thread)
    private fun loadAdMobRewardedAdWithRetry() { // Renamed from loadUnityRewardedAdWithRetry
         // Check retry count
        if (retryAttempt >= maxRetries) {
            Log.e(TAG, "Max load retries ($maxRetries) reached for AdMob Rewarded Ad.")
            // Manually trigger failure handling, counting towards circuit breaker
            handleAdMobLoadFailure(LoadAdError(0, "Max retries reached", "AdManager", null, null), "Max retries reached", true) // Create a dummy LoadAdError
            return
        }

        // Ensure we are on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
             Log.e(TAG, "loadAdMobRewardedAdWithRetry called from non-main thread! Posting to main handler.")
             handler.post { loadAdMobRewardedAdWithRetry() }
             return
        }

        Log.d(TAG, "Attempting to load AdMob Rewarded Ad (Attempt ${retryAttempt + 1}) for Unit ID: $CURRENT_ADMOB_REWARDED_AD_UNIT_ID")
        
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(context, CURRENT_ADMOB_REWARDED_AD_UNIT_ID, adRequest, admobRewardedAdLoadCallback)

        // Optional: Add a manual timeout check
        // handler.postDelayed({ checkLoadTimeout() }, AD_LOAD_TIMEOUT_MS)
    }

    // Optional: Manual timeout check logic (can be adapted if needed)
    // private fun checkLoadTimeout() {
    //      synchronized(adLoadLock) {
    //          if (_isLoading.value && admobRewardedAd == null) { // Check AdMob ad object
    //              Log.w(TAG, "AdMob Rewarded Ad load timed out (manual check).")
    //              handleAdMobLoadFailure(LoadAdError(0, "Manual timeout", "AdManager", null, null), "Manual timeout", false)
    //          }
    //      }
    // }


    // --- AdMob Rewarded Ad Load Callback Implementation ---
    private val admobRewardedAdLoadCallback = object : RewardedAdLoadCallback() {
        override fun onAdLoaded(rewardedAd: RewardedAd) {
            // Called by AdMob SDK, likely on main thread
            Log.d(TAG, "AdMob Rewarded Ad loaded successfully. Unit ID: ${rewardedAd.adUnitId}")
            synchronized(adLoadLock) {
                admobRewardedAd = rewardedAd // Store the loaded ad
                _isRewardedAdLoaded.value = true
                _isLoading.value = false
                retryAttempt = 0 // Reset retries on success
                consecutiveLoadFailures = 0 // Reset circuit breaker counter
                _lastAdLoadTimestamp.value = System.currentTimeMillis()
                lastLoadErrorTime = 0L
            }
            // Notify via existing callback (post to main for safety)
            handler.post { rewardedAdCallback?.onAdLoaded() }
        }

        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
            // Called by AdMob SDK, likely on main thread
            Log.e(TAG, "AdMob Rewarded Ad failed to load: Code=${loadAdError.code}, Message=${loadAdError.message}, Domain=${loadAdError.domain}")
            admobRewardedAd = null // Ensure ad object is null on failure
            // Use centralized failure handler, count for retry
            handleAdMobLoadFailure(loadAdError, loadAdError.message, true)
        }
    }

    // Centralized handler for AdMob load failures
    private fun handleAdMobLoadFailure(error: LoadAdError?, message: String, countForRetryAndCircuitBreaker: Boolean) { // Adapted from handleUnityLoadFailure
        val wasLoading: Boolean
        synchronized(adLoadLock) {
             wasLoading = _isLoading.value // Read StateFlow value
             _isLoading.value = false         // Update StateFlow
             _isRewardedAdLoaded.value = false // Ensure flag is false on failure
             lastLoadErrorTime = System.currentTimeMillis()
             if (countForRetryAndCircuitBreaker) {
                 consecutiveLoadFailures++
             }
        }

        // Only proceed with retry/callback logic if we were actually in a loading state
        if (wasLoading) {
            // Notify external listener (post to main thread for safety)
            handler.post { rewardedAdCallback?.onAdFailedToLoad(message) }

            if (countForRetryAndCircuitBreaker) {
                 // Check circuit breaker
                 if (consecutiveLoadFailures >= maxConsecutiveLoadFailures) {
                     circuitBreakerUntilTimestamp = System.currentTimeMillis() + circuitBreakerDurationMs
                     Log.e(TAG, "AdMob load circuit breaker tripped for ${circuitBreakerDurationMs / 1000} seconds due to $consecutiveLoadFailures consecutive failures.")
                     retryAttempt = 0 // Reset attempts when breaker trips
                     // Save circuit breaker state
                     adManagerScope.launch { saveCircuitBreakerState() }
                     return // Don't schedule retry immediately
                 }

                 // Schedule retry with exponential backoff
                 retryAttempt++
                 if (retryAttempt <= maxRetries) { // Use <= to allow maxRetries attempts
                     val delay = min(30000L, baseRetryDelayMs * (2.0.pow(retryAttempt - 1)).toLong())
                     Log.d(TAG, "Scheduling AdMob load retry $retryAttempt/$maxRetries in $delay ms.")
                     handler.postDelayed({
                         // Relaunch the load attempt on the main thread
                         adManagerScope.launch(Dispatchers.Main) {
                             loadAdMobRewardedAdWithRetry()
                         }
                     }, delay)
                 } else {
                     Log.e(TAG, "Max load retries ($maxRetries) reached after failure. Circuit breaker may engage on next failure.")
                     // Reset retryAttempt here? Or let it stay > maxRetries until circuit breaker or success?
                     // Resetting means it will try again sooner after breaker period.
                     // retryAttempt = 0
                 }
            }
        } else {
             // Log if failure handler was called unexpectedly
             Log.w(TAG, "handleAdMobLoadFailure called but was not in a loading state. Error: [$error] $message. Ignoring retry/callback.")
        }
    }

    // Helper to save circuit breaker state
    private suspend fun saveCircuitBreakerState() {
        try {
            context.dataStore.edit { settings ->
                settings[intPreferencesKey("ad_consecutive_failures")] = consecutiveLoadFailures
                settings[longPreferencesKey("ad_circuit_breaker_until")] = circuitBreakerUntilTimestamp
            }
            Log.d(TAG, "Saved circuit breaker state: Failures=$consecutiveLoadFailures, Until=$circuitBreakerUntilTimestamp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save circuit breaker state", e)
             // Fallback?
             prefs.edit()
                 .putInt("ad_consecutive_failures", consecutiveLoadFailures)
                 .putLong("ad_circuit_breaker_until", circuitBreakerUntilTimestamp)
                 .apply()
        }
    }


    // --- Showing Ad ---

    fun isRewardedAdAvailable(): Boolean {
        // Check initialization and loaded status using StateFlow values
        var adMobReady = isAdMobInitialized.get() && admobRewardedAd != null && _isRewardedAdLoaded.value
        
        if (adMobReady && (System.currentTimeMillis() - _lastAdLoadTimestamp.value > MAX_REWARD_AD_AGE_MS)) {
            Log.d(TAG, "isRewardedAdAvailable: Stale ad detected (loaded at ${_lastAdLoadTimestamp.value}, older than $MAX_REWARD_AD_AGE_MS ms). Discarding and preloading.")
            admobRewardedAd = null
            _isRewardedAdLoaded.value = false // Mark as not loaded
            // Preload is triggered below if adMobReady becomes false due to staleness
            adMobReady = false // Mark as not ready because it's stale
        }

        if (!adMobReady) {
            // More detailed logging for debugging
            if (!isAdMobInitialized.get()) {
                 Log.d(TAG, "isRewardedAdAvailable: Failed - AdMob SDK not initialized.")
            } else if (admobRewardedAd == null && _isRewardedAdLoaded.value) {
                 // This case implies it was just nulled due to staleness or failed to load previously
                 Log.d(TAG, "isRewardedAdAvailable: AdMob Rewarded Ad object is null (possibly stale or failed load).")
            } else if (!_isRewardedAdLoaded.value) {
                 Log.d(TAG, "isRewardedAdAvailable: Failed - _isRewardedAdLoaded is false (internal state).")
            }
             // Consider preloading if an ad is not available and not already loading
            if (!_isLoading.value && isAdMobInitialized.get()) { 
                Log.d(TAG, "isRewardedAdAvailable: Ad not available or stale, triggering preload.")
                preloadRewardedAd()
            }
        }
        return adMobReady
    }

    // Method to show the AdMob Rewarded Ad
    fun showRewardedAd(activity: Activity, bypassRateLimit: Boolean = false) {
        // Optional: Implement internal rate limiting check if needed (similar to Unity version)

        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "showRewardedAd called from non-main thread. Posting to main handler.")
            handler.post { showRewardedAd(activity, bypassRateLimit) }
            return
        }

        if (!isRewardedAdAvailable()) { // This already checks admobRewardedAd != null
            Log.e(TAG, "Attempted to show AdMob rewarded ad, but it's not available (checked on main thread).")
            rewardedAdCallback?.onAdFailedToShow("AdMob rewarded ad not available")
            return
        }

        Log.d(TAG, "Showing AdMob Rewarded Ad on Activity: ${activity.localClassName}")
        
        admobRewardedAd?.fullScreenContentCallback = admobFullScreenContentCallback // Set callback before show

        admobRewardedAd?.show(activity, admobOnUserEarnedRewardListener)
    }

    // --- AdMob FullScreenContentCallback and OnUserEarnedRewardListener ---
    private val admobFullScreenContentCallback = object : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() {
            // Called when ad is shown.
            Log.d(TAG, "AdMob Rewarded Ad showed full screen content.")
            handler.post { rewardedAdCallback?.onAdShown() }
            adManagerScope.launch { updateAdCounters() }
            // Reset the ad instance as it's a one-time use object for AdMob
            // admobRewardedAd = null // Do this in onAdDismissedFullScreenContent
            // _isRewardedAdLoaded.value = false
        }

        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
            // Called when ad fails to show.
            Log.e(TAG, "AdMob Rewarded Ad failed to show full screen content: ${adError.message} (Code: ${adError.code})")
            handler.post { rewardedAdCallback?.onAdFailedToShow(adError.message) }
            admobRewardedAd = null // Ad failed to show, so it's no longer available
            _isRewardedAdLoaded.value = false
        }

        override fun onAdDismissedFullScreenContent() {
            // Called when ad is dismissed.
            Log.d(TAG, "AdMob Rewarded Ad dismissed full screen content.")
            // Preload the next rewarded ad.
            // Removed: preloadRewardedAd() - Preloading is now handled by LockScreenActivity onResume or explicit calls
            handler.post { rewardedAdCallback?.onAdDismissed() }
            admobRewardedAd = null // Ad is dismissed, so it's no longer available
            _isRewardedAdLoaded.value = false
        }

        override fun onAdImpression() {
            Log.d(TAG, "AdMob Rewarded Ad impression recorded.")
            // You could add a callback for impression if needed in RewardedAdCallback
        }

        override fun onAdClicked() {
            Log.d(TAG, "AdMob Rewarded Ad clicked.")
            handler.post { rewardedAdCallback?.onAdClicked() }
        }
    }

    private val admobOnUserEarnedRewardListener = OnUserEarnedRewardListener { rewardItem ->
        Log.d(TAG, "User earned reward from AdMob ad. Amount: ${rewardItem.amount}, Type: ${rewardItem.type}")
        handler.post { rewardedAdCallback?.onUserEarnedReward(rewardItem.amount) }
    }

    // --- External Callback Interface & Setter ---
    interface RewardedAdCallback {
        fun onAdLoaded()
        fun onAdFailedToLoad(errorMessage: String?)
        fun onAdShown()
        fun onAdDismissed() // Called after show completes (rewarded or skipped) or if ad fails to show
        fun onAdFailedToShow(errorMessage: String?)
        fun onAdClicked()
        fun onUserEarnedReward(amount: Int) // Keep amount for interface consistency
    }

    // Setter for the external callback
    fun setRewardedAdCallback(callback: RewardedAdCallback?) {
        this.rewardedAdCallback = callback
    }

    // Keep if used for banners
    fun setAdClickHandler(handler: () -> Unit) {
        this.adClickHandler = handler
    }


    // --- Other Ad Types (Example: Banner - Keep/Adapt AdMob Logic) ---
    fun loadBannerAd(adView: AdView) { // Use standard AdView unless AdManagerAdView is specifically needed
         if (!isAdMobInitialized.get()) {
             Log.w(TAG, "Cannot load AdMob banner - SDK not initialized.")
             // Optionally trigger init? initializeAdSdks()
             return
         }
         val currentTime = System.currentTimeMillis()
         if (currentTime - lastBannerDisplayTime < MIN_AD_INTERVAL) { // Use MIN_AD_INTERVAL or a separate banner interval
             Log.d(TAG, "Skipping banner ad load due to rate limiting")
             return
         }

         val sessionId = UUID.randomUUID().toString()
         Log.d(TAG, "Loading AdMob banner ad [Session: $sessionId]")

         // Set ad unit ID from BuildConfig if not already set
         if (adView.adUnitId.isNullOrEmpty()) {
             Log.d(TAG, "Setting fresh banner adUnitId using BuildConfig")
             adView.adUnitId = BuildConfig.ADMOB_BANNER_ID
         }

         adView.adListener = object : AdListener() {
             override fun onAdLoaded() {
                 Log.d(TAG, "AdMob Banner ad loaded successfully [Session: $sessionId]")
                 lastBannerDisplayTime = currentTime
             }

             override fun onAdFailedToLoad(error: LoadAdError) {
                 Log.e(TAG, "AdMob Banner ad failed to load [Session: $sessionId]: ${error.message} (Code: ${error.code})")
                 // Domain: error.domain, Cause: error.cause
             }

             override fun onAdImpression() {
                 Log.d(TAG, "AdMob Banner ad impression recorded [Session: $sessionId]")
             }

             override fun onAdClicked() {
                 Log.d(TAG, "AdMob Banner ad clicked [Session: $sessionId]")
                 // Use the adClickHandler if set (e.g., for DeviceAdmin handling)
                 handler.post { adClickHandler?.invoke() }
             }
         }

         try {
             // Build AdMob request - No Unity extras needed here
             val adRequest = AdRequest.Builder().build()
             // Ensure loadAd runs on the main thread
             handler.post {
                 adView.loadAd(adRequest)
             }
         } catch (e: Exception) {
             Log.e(TAG, "Error loading banner ad: ${e.message}")
         }
    }


    // --- Utility and Lifecycle Methods --- (Keep existing relevant helpers)

    // Example: Adapt performCompleteAdReset
    private suspend fun performCompleteAdReset() {
        try {
            Log.d(TAG, "Performing complete ad reset for AdMob")
            
            // Reset AdMob Rewarded Ad State
            admobRewardedAd = null
            synchronized(adLoadLock) {
                 _isLoading.value = false
                 _isRewardedAdLoaded.value = false
                 retryAttempt = 0
                 consecutiveLoadFailures = 0
                 lastLoadErrorTime = 0L
                 circuitBreakerUntilTimestamp = 0L
                 _lastAdLoadTimestamp.value = 0L
            }
            
            // Reset AdMob Banner State (if applicable)
            lastBannerDisplayTime = 0L

            // Common State Reset
            unregisterNetworkCallback()
            handler.removeCallbacksAndMessages(null) // Cancel pending retries/timeouts
            
            // Reset counters/timestamps from preferences/datastore
            resetCountersAndTimestamps() // Encapsulate reset logic

            // Re-initialize SDKs after a delay - Re-initialization might not be needed unless SDKs crashed.
            // Usually just preloading is enough after a reset.
            // handler.postDelayed({
            //     Log.d(TAG, "Re-initializing SDKs after reset.")
            //     initializeAdSdks() // This will also trigger preload if successful
            // }, 3000)
            
            // Trigger preload directly after resetting state
             handler.postDelayed({
                 Log.d(TAG,"Preloading ads after reset.")
                 preloadAds()
             },1000) // Short delay before preload

        } catch (e: Exception) {
            Log.e(TAG, "Error during complete ad reset", e)
        }
    }
    
    // Helper to reset counters/timestamps
     private suspend fun resetCountersAndTimestamps() {
         Log.d(TAG,"Resetting counters and timestamps")
         lastShowTime = 0L
         hourlyAdCount = 0
         dailyAdCount = 0
         lastDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
         lastHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
         lastDateString = getCurrentDateString() // Reset date string as well
         
         // Save reset state
         saveState()
     }


    // Adapt check for date change etc. if necessary
     suspend fun checkForDateChange() {
         try {
             val currentDateString = getCurrentDateString()
             val savedDateString = lastDateString // Assumes loadState() was called
             
             if (savedDateString != currentDateString) {
                 Log.d(TAG, "Date change detected! Resetting ad state. Current: $currentDateString, Saved: $savedDateString")
                 // FIX: Call public forceResetAdState which wraps performCompleteAdReset
                 forceResetAdState() 
                 // performCompleteAdReset updates lastDateString and saves state
             } else {
                 // Log.d(TAG, "No date change detected.") // Less verbose
             }
         } catch (e: Exception) {
             Log.e(TAG, "Error checking for date change: ${e.message}", e)
         }
     }

    // Adapt onAppForeground if needed
    fun onAppForeground() {
        Log.d(TAG, "App came to foreground.")
        adManagerScope.launch {
            try {
                // Keep date change check if desired
                checkForDateChange()
            } catch (e: Exception) {
                Log.e(TAG, "Error during onAppForeground async checks", e)
            }
        }
    }
    
    // Adapt onAppBackground
    fun onAppBackground() {
        lastAppBackgroundTime = System.currentTimeMillis()
        Log.d(TAG, "App went to background, marking timestamp: $lastAppBackgroundTime")

        // Stop loading if app is backgrounded? Optional, Unity might handle this.
         synchronized(adLoadLock) { // FIX: Use lock to access isLoading safely
             if (_isLoading.value) { // Check StateFlow value
                 Log.w(TAG, "App backgrounded while AdMob Ad was loading. Resetting load state.")
                 // Note: AdMob doesn't have an explicit cancel load API.
                 // We can just reset our state.
                 _isLoading.value = false // Update StateFlow
                 retryAttempt = 0
                 handler.removeCallbacksAndMessages(null) // Remove retry callbacks
             }
         }
        
        // Store the background time
        adManagerScope.launch {
            try {
                context.dataStore.edit { settings ->
                    settings[PreferencesKeys.AD_LAST_APP_BACKGROUND_TIME] = lastAppBackgroundTime
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error storing background time in DataStore", e)
                prefs.edit().putLong("last_app_background_time", lastAppBackgroundTime).apply()
            }
        }
    }

    // --- Lock Screen Activation/Deactivation --- (Keep existing logic, ensure preload/reset calls are correct)
    fun onLockScreenActivated() {
        synchronized(lockStateLock) {
            // Set session start time etc.
            lockScreenSessionStartTime = System.currentTimeMillis()
            Log.d(TAG, "Lock screen activated at $lockScreenSessionStartTime - Triggering Preload")

            // Persist state immediately (use commit for synchronous save)
            // Use the correct SharedPreferences name consistent with LockScreenActivity
            val prefs = context.getSharedPreferences("lock_screen_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("lock_screen_active", true)
                .putLong("lock_screen_session_start", lockScreenSessionStartTime)
                .putLong("last_active_timestamp", lockScreenSessionStartTime)
                .commit()

            // Update DataStore asynchronously
            adManagerScope.launch { saveLockScreenActivationState(true, lockScreenSessionStartTime) }

            // Log.d(TAG,"Performing ad state reset and preload on lock screen activation.")
            // CHANGE: Don't reset state, just attempt preload
            // Force a reset and preload when lock screen becomes active
            // adManagerScope.launch {
            //     performCompleteAdReset() // Reset state completely for the new session
            // }
            // Call preloadAds() directly - this handles checks internally
            preloadAds()
        }
    }

    fun onLockScreenDeactivated() {
         synchronized(lockStateLock) {
            // Use the correct SharedPreferences name consistent with LockScreenActivity
            val prefs = context.getSharedPreferences("lock_screen_prefs", Context.MODE_PRIVATE)
             if (!prefs.getBoolean("lock_screen_active", false)) {
                 Log.d(TAG, "Lock screen already marked inactive, ignoring duplicate deactivation")
                 return
             }

            val sessionDuration = System.currentTimeMillis() - lockScreenSessionStartTime
            Log.d(TAG, "Lock screen deactivated - session lasted ${sessionDuration}ms")

            // Persist state immediately - ENSURE lock_screen_active is set to false
            prefs.edit()
                .putBoolean("lock_screen_active", false)
                .putLong("last_deactivation_time", System.currentTimeMillis())
                .putLong("last_session_duration", sessionDuration)
                .commit()

             // Update DataStore asynchronously
             adManagerScope.launch { saveLockScreenActivationState(false, 0L) }

            // Cancel pending operations related to ad loading/retry
            Log.d(TAG, "Lock screen deactivated - cancelling pending AdMob ad loads/retries.")
             synchronized(adLoadLock) {
                 handler.removeCallbacksAndMessages(null) // Remove pending retries/timeouts
                 // If a load was in progress, reset flags
                 if (_isLoading.value) {
                      _isLoading.value = false
                      retryAttempt = 0 // Reset retry count
                 }
             }
             unregisterNetworkCallback() // Stop listening for network changes
         }
    }
    
    // Helper to save lock screen state to DataStore
    private suspend fun saveLockScreenActivationState(isActive: Boolean, startTime: Long) {
        try {
             context.dataStore.edit { settings ->
                 settings[PreferencesKeys.LOCK_SCREEN_ACTIVE] = isActive
                 settings[PreferencesKeys.LAST_LOCK_ACTIVATION_TIME] = if (isActive) startTime else 0L
                 settings[PreferencesKeys.IS_AD_LOADING] = _isLoading.value // StateFlow value
             }
        } catch (e: Exception) {
             Log.e(TAG, "Error saving lock screen state to DataStore", e)
        }
    }


    // --- Network Handling --- (Keep existing logic)
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d(TAG, "Network available (callback), attempting ad preload if needed.")
                    // Only preload if lock screen is active and ad not loading/loaded
                     val isActive = context.getSharedPreferences("lock_screen_prefs", Context.MODE_PRIVATE)
                                         .getBoolean("lock_screen_active", false)
                     val shouldPreload = synchronized(adLoadLock) { !_isLoading.value && !_isRewardedAdLoaded.value } // Read StateFlow values
                     if (isActive && shouldPreload) {
                         preloadRewardedAd()
                     } else {
                          Log.d(TAG, "Network available, but not preloading (LockScreenActive: $isActive, ShouldPreload: $shouldPreload)")
                     }
                }
                override fun onLost(network: Network) {
                     super.onLost(network)
                     Log.d(TAG, "Network lost (callback).")
                     // Optionally cancel ongoing loads? Unity might handle this internally.
                }
            }

            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            networkCallbackRegistered = true
            Log.d(TAG, "Network callback registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
            networkCallbackRegistered = false
        }
    }

     private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered || networkCallback == null) return
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback!!)
            networkCallback = null
            networkCallbackRegistered = false
            Log.d(TAG, "Network callback unregistered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                       capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                return networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network availability", e)
            return false
        }
    }

    // --- Consent Methods --- (Keep existing logic, applies to both AdMob and Unity where relevant)
    fun setGDPRConsent(hasConsent: Boolean) {
        try {
            // Store preference
            prefs.edit().putBoolean("gdpr_consent", hasConsent).apply()
            Log.d(TAG, "Setting GDPR consent: $hasConsent (For AdMob, UMP SDK is primary)")

            // Removed: Unity Ads MetaData update
            // AdMob consent is handled via UMP SDK and RequestConfiguration (already set in setAdMobRequestConfiguration)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting GDPR consent", e)
        }
    }

    fun setCCPAConsent(hasConsent: Boolean) {
         try {
             // Store preference
             prefs.edit().putBoolean("ccpa_consent", hasConsent).apply()
             Log.d(TAG, "Setting CCPA consent: $hasConsent (For AdMob, UMP SDK is primary)")

             // Removed: Unity Ads MetaData update
              // AdMob consent is handled via UMP SDK and RequestConfiguration (already set in setAdMobRequestConfiguration)
         } catch (e: Exception) {
             Log.e(TAG, "Error setting CCPA consent", e)
         }
    }


    // --- Helpers --- (Keep existing relevant helpers)
    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        for (processInfo in activityManager.runningAppProcesses) {
            if (processInfo.pid == pid) {
                return processInfo.processName == context.packageName
            }
        }
        return false
    }

    private fun getCurrentDateString(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1 // 0-based months
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    // Load/Save/Reset State (Keep implementations, ensure they save/load relevant fields)
    private suspend fun loadState() {
        try {
            val preferences = context.dataStore.data.first()
            lastShowTime = preferences[PreferencesKeys.AD_LAST_SHOW_TIME] ?: 0L
            lastBannerDisplayTime = preferences[PreferencesKeys.AD_LAST_BANNER_DISPLAY_TIME] ?: 0L
            dailyAdCount = preferences[PreferencesKeys.AD_DAILY_COUNT] ?: 0
            hourlyAdCount = preferences[PreferencesKeys.AD_HOURLY_COUNT] ?: 0
            lastDay = preferences[PreferencesKeys.AD_LAST_COUNT_RESET_DAY] ?: 0
            lastHour = preferences[PreferencesKeys.AD_LAST_COUNT_RESET_HOUR] ?: 0
            lastAppBackgroundTime = preferences[PreferencesKeys.AD_LAST_APP_BACKGROUND_TIME] ?: 0L
            lastDateString = preferences[PreferencesKeys.AD_LAST_CHECK_DATE_STRING] ?: getCurrentDateString()
            // Load circuit breaker state
            consecutiveLoadFailures = preferences[intPreferencesKey("ad_consecutive_failures")] ?: 0
            circuitBreakerUntilTimestamp = preferences[longPreferencesKey("ad_circuit_breaker_until")] ?: 0L
            
            Log.d(TAG, "Ad state loaded from DataStore.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading ad state from DataStore", e)
            // Fall back to shared preferences
            loadStateFromPrefs()
        }
    }
    private fun loadStateFromPrefs() {
        try {
            lastShowTime = prefs.getLong(KEY_LAST_SHOW_TIME, 0L)
        } catch (e: ClassCastException) {
            lastShowTime = prefs.getInt(KEY_LAST_SHOW_TIME, 0).toLong()
            prefs.edit().putLong(KEY_LAST_SHOW_TIME, lastShowTime).apply()
        }
        try {
            lastBannerDisplayTime = prefs.getLong(KEY_LAST_BANNER_DISPLAY_TIME, 0L)
        } catch (e: ClassCastException) {
            lastBannerDisplayTime = prefs.getInt("last_banner_display_time", 0).toLong() // Use literal if const missing
            prefs.edit().putLong(KEY_LAST_BANNER_DISPLAY_TIME, lastBannerDisplayTime).apply()
        }

        dailyAdCount = prefs.getInt(KEY_DAILY_AD_COUNT, 0)
        hourlyAdCount = prefs.getInt(KEY_HOURLY_AD_COUNT, 0)
        lastDay = prefs.getInt(KEY_LAST_DAY, Calendar.getInstance().get(Calendar.DAY_OF_YEAR))
        lastHour = prefs.getInt(KEY_LAST_HOUR, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        lastAppBackgroundTime = prefs.getLong("last_app_background_time", 0L) // Use literal if const missing
        lastDateString = prefs.getString(KEY_LAST_DATE_STRING, getCurrentDateString()) ?: getCurrentDateString()
        consecutiveLoadFailures = prefs.getInt("ad_consecutive_failures", 0)
        circuitBreakerUntilTimestamp = prefs.getLong("ad_circuit_breaker_until", 0L)

        Log.d(TAG, "Ad state loaded from SharedPreferences (fallback).")
    }
     private suspend fun saveState() {
         try {
             context.dataStore.edit { settings ->
                 settings[PreferencesKeys.AD_LAST_SHOW_TIME] = lastShowTime
                 settings[PreferencesKeys.AD_LAST_BANNER_DISPLAY_TIME] = lastBannerDisplayTime
                 settings[PreferencesKeys.AD_DAILY_COUNT] = dailyAdCount
                 settings[PreferencesKeys.AD_HOURLY_COUNT] = hourlyAdCount
                 settings[PreferencesKeys.AD_LAST_COUNT_RESET_DAY] = lastDay
                 settings[PreferencesKeys.AD_LAST_COUNT_RESET_HOUR] = lastHour
                 settings[PreferencesKeys.AD_LAST_APP_BACKGROUND_TIME] = lastAppBackgroundTime
                 settings[PreferencesKeys.AD_LAST_CHECK_DATE_STRING] = lastDateString
                 // Save circuit breaker state
                 settings[intPreferencesKey("ad_consecutive_failures")] = consecutiveLoadFailures
                 settings[longPreferencesKey("ad_circuit_breaker_until")] = circuitBreakerUntilTimestamp
             }
             Log.d(TAG, "Ad state saved to DataStore.")
         } catch (e: Exception) {
             Log.e(TAG, "Error saving ad state to DataStore, falling back to SharedPreferences", e)
             saveStateToPrefs()
         }
     }
     private fun saveStateToPrefs() {
          prefs.edit().apply {
              putLong(KEY_LAST_SHOW_TIME, lastShowTime)
              putLong(KEY_LAST_BANNER_DISPLAY_TIME, lastBannerDisplayTime)
              putInt(KEY_DAILY_AD_COUNT, dailyAdCount)
              putInt(KEY_HOURLY_AD_COUNT, hourlyAdCount)
              putInt(KEY_LAST_DAY, lastDay)
              putInt(KEY_LAST_HOUR, lastHour)
              putLong("last_app_background_time", lastAppBackgroundTime) // Use literal if const missing
              putString(KEY_LAST_DATE_STRING, lastDateString)
              putInt("ad_consecutive_failures", consecutiveLoadFailures)
              putLong("ad_circuit_breaker_until", circuitBreakerUntilTimestamp)
              apply()
          }
          Log.d(TAG, "Ad state saved to SharedPreferences (fallback).")
     }
     private suspend fun resetCountersIfNeeded() {
         val calendar = Calendar.getInstance()
         val currentDay = calendar.get(Calendar.DAY_OF_YEAR)
         val currentHour = calendar.get(Calendar.HOUR_OF_DAY)

         val savedLastDay = lastDay
         val savedLastHour = lastHour

         var needsSave = false
         if (savedLastDay != currentDay) {
             Log.d(TAG, "New day detected ($currentDay vs $savedLastDay), resetting daily and hourly ad counts.")
             dailyAdCount = 0
             hourlyAdCount = 0
             lastDay = currentDay
             lastHour = currentHour
             needsSave = true
         } else if (savedLastHour != currentHour) {
             Log.d(TAG, "New hour detected ($currentHour vs $savedLastHour), resetting hourly ad count.")
             hourlyAdCount = 0
             lastHour = currentHour
             needsSave = true
         }

         if (needsSave) {
             saveState() // Save the updated counters
         }
     }
     private suspend fun updateAdCounters() {
          // Ensure counters are up-to-date first
          resetCountersIfNeeded()

          hourlyAdCount++
          dailyAdCount++
          lastShowTime = System.currentTimeMillis() // Update last show time
          Log.d(TAG, "Ad shown. Updated Counts - Hourly: $hourlyAdCount, Daily: $dailyAdCount")

          saveState() // Save the updated counts and timestamp
     }
     // Add back clearAdCounters if used by frequency cap logic
     private suspend fun clearAdCounters() {
          try {
              lastShowTime = 0L
              hourlyAdCount = 0
              dailyAdCount = 0
              lastDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
              lastHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
              saveState() // Persist the cleared counters
              Log.d(TAG, "Cleared ad counters.")
          } catch (e: Exception) {
              Log.e(TAG, "Error clearing ad counters: ${e.message}")
          }
      }

    // ADD BACK: Public forceResetAdState wrapper
    fun forceResetAdState() {
        Log.d(TAG, "Forcing ad state reset")
        // Launch in the appropriate coroutine scope instead of being a suspend function
        adManagerScope.launch {
            performCompleteAdReset()
        }
    }

    // Methods to be called by LockScreenActivity or similar to set context
    fun notifyLockScreenActive() {
        Log.d(TAG, "Notified that Lock Screen is ACTIVE. Ad preloading is now allowed for this context.")
        isLockScreenContextActive = true
        // Attempt a preload immediately if SDK is ready
        if (isAdMobInitialized.get()) {
            preloadRewardedAd(isLockScreenTrigger = true)
        }
    }

    fun notifyLockScreenInactive() {
        Log.d(TAG, "Notified that Lock Screen is INACTIVE. Ad preloading for this context is paused.")
        isLockScreenContextActive = false
        // Optionally, cancel any ongoing loads if strictly no ads outside lock screen context? Dangerous if other ad types exist.
        // For now, just prevent new preloads for lock screen.
    }

    // --- Companion Object ---
    companion object {
        private const val TAG = "AdManager"
        private const val PREFS_NAME = "ad_prefs"
        // Keep relevant keys for SharedPreferences fallback/state
        private const val KEY_LAST_SHOW_TIME = "last_ad_show_time"
        private const val KEY_DAILY_AD_COUNT = "daily_ad_count"
        private const val KEY_HOURLY_AD_COUNT = "hourly_ad_count"
        private const val KEY_LAST_DAY = "last_ad_day"
        private const val KEY_LAST_HOUR = "last_ad_hour"
        private const val KEY_LAST_AD_LOAD_TIME = "last_ad_load_time"
        private const val KEY_LAST_DATE_STRING = "last_date_string"
        // Define missing keys used in fallback saves/loads
        private const val KEY_LAST_BANNER_DISPLAY_TIME = "last_banner_display_time"


        const val KEY_UNLOCK_AD_HANDLED = "unlock_ad_handled" // Keep if used externally
        // Other constants...
        private const val MAX_DAILY_ADS = 999999 // Effectively unlimited
        private const val MAX_HOURLY_ADS = 99999 // Effectively unlimited
        private const val MIN_AD_INTERVAL = 0L // No delay between ads


        // Remove AdMob Rewarded Ad Unit IDs (already added them as class members)
        // const val REWARDED_AD_UNIT_DEBUG = "ca-app-pub-3940256099942544/5354046379" // Example
        // const val REWARDED_AD_UNIT_PROD = "ca-app-pub-6928555061691394/4036171672" // Example
    }
}

