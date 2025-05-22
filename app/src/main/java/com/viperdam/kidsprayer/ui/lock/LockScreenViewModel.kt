package com.viperdam.kidsprayer.ui.lock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.ads.AdManager
import com.viperdam.kidsprayer.camera.CameraManager
import com.viperdam.kidsprayer.camera.PoseOverlayView
import com.viperdam.kidsprayer.ml.RakaatTracker
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager.CompletionType
import com.viperdam.kidsprayer.prayer.PrayerStateChecker
import com.viperdam.kidsprayer.security.PinManager
import com.viperdam.kidsprayer.service.LockScreenService
import com.viperdam.kidsprayer.service.PrayerScheduler
import com.viperdam.kidsprayer.ui.lock.LockScreenActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import com.google.android.gms.ads.OnUserEarnedRewardListener

@HiltViewModel
class LockScreenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pinManager: PinManager,
    private val completionManager: PrayerCompletionManager,
    private val cameraManager: CameraManager,
    private val adManager: AdManager,
    private val prayerStateChecker: PrayerStateChecker
) : ViewModel(), PrayerScheduler.LockScreenListener {

    companion object {
        private const val TAG = "LockScreenViewModel"
        private const val PREFS_NAME = "lock_screen_prefs"
        private const val KEY_PRAYER_STARTED = "prayer_started"
        private const val KEY_CURRENT_RAKAAT = "current_rakaat"
        private const val KEY_CURRENT_POSITION = "current_position"
        private const val KEY_CAMERA_ACTIVE = "camera_active"
        private const val KEY_RETRY_COUNT = "retry_count"
        private const val KEY_CAMERA_STATE_CHANGE = "camera_state_change"
        private const val KEY_PIN_STATE_CHANGE = "pin_state_change"
        private const val KEY_QUEUED_PRAYERS = "queued_prayers"
        private const val MAX_RETRIES = 3
        private const val CAMERA_INIT_TIMEOUT = 10000L // 10 seconds
        private const val MIN_STATE_CHANGE_INTERVAL = 500L // Minimum time between state changes
        private const val DISPLAY_CACHE_TIMEOUT = 5000L // 5 seconds
        const val ACTION_PRAYER_STATUS_CHANGED = "com.viperdam.kidsprayer.PRAYER_STATUS_CHANGED"
    }

    // State variables
    private var _isPrayerComplete = false
    val isPrayerComplete: Boolean get() = _isPrayerComplete
    private var _pinVerified: Boolean = false
    val pinVerified: Boolean get() = _pinVerified
    private var lockTaskFailed = false

    data class UiState(
        val prayerName: String = "",
        val rakaatCount: Int = 0,
        val currentRakaat: Int = 0,
        val currentPosition: String = "Standing", // Initialize to Standing
        val isPrayerStarted: Boolean = false,
        val isCameraActive: Boolean = false,
        val cameraError: String? = null,
        val errorMessage: String? = null,
        val shouldShowPin: Boolean = true,
        val shouldShowStartButton: Boolean = true,
        val shouldAutoUnlock: Boolean = false,
        val pinAttempts: Int = 0,
        val isLockedOut: Boolean = false,
        val pinEnabled: Boolean = true,
        val pinCooldownSeconds: Int = 0,
        val shouldShowAds: Boolean = false,
        val hasQueuedPrayers: Boolean = false,
        val shouldFinish: Boolean = false,
        val isLoading: Boolean = false,
        val isInFallbackMode: Boolean = false, // New flag for fallback mode
        val pinVerified: Boolean = false,
        val isPrayerComplete: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var rakaatTracker: RakaatTracker? = null
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var retryCount = 0
    private var lastCameraStateChange = 0L
    private var lastPinStateChange = 0L
    private var isTestLock = false
    private var cooldownJob: Job? = null
    private var stateRecoveryJob: Job? = null
    private val stateMutex = Mutex()
    private var poseOverlayView: PoseOverlayView? = null

    private var queuedPrayers = mutableListOf<Pair<String, Int>>() // List of (prayerName, rakaatCount)

    private var nextPrayerIntent: Intent? = null
    private var nextServiceIntent: Intent? = null

    fun setPoseOverlayView(view: PoseOverlayView) {
        poseOverlayView = view
    }

    private data class DisplayState(
        val prayerName: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val displayCache = mutableMapOf<String, DisplayState>()
    private val _prayerDisplayState = MutableStateFlow<String?>(null)
    val prayerDisplayState: StateFlow<String?> = _prayerDisplayState.asStateFlow()

    private fun updatePrayerDisplay(prayerName: String?) {
        if (prayerName == null) {
            _prayerDisplayState.value = null
            return
        }

        val now = System.currentTimeMillis()
        val cached = displayCache[prayerName]
        if (cached != null && now - cached.timestamp < DISPLAY_CACHE_TIMEOUT) {
            _prayerDisplayState.value = cached.prayerName
            return
        }

        displayCache[prayerName] = DisplayState(prayerName)
        _prayerDisplayState.value = prayerName
    }

    private fun updateUiState(update: (UiState) -> UiState) {
        _uiState.update(update)
    }

    init {
        loadQueuedPrayers()
        viewModelScope.launch {
            try {
                _isPrayerComplete = false
                _pinVerified = false
                _uiState.update {
                    it.copy(
                        shouldShowPin = true,
                        shouldShowStartButton = false,
                        shouldShowAds = false,
                        isPrayerStarted = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing state", e)
            }
        }
        
        // Initialize default PIN if not set
        viewModelScope.launch {
            if (!pinManager.isPinSetup()) {
                try {
                    pinManager.setPin("1234") // Default PIN
                    Log.d(TAG, "Default PIN initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Error initializing default PIN: ${e.message}")
                }
            }
        }
    }

    fun initializePrayer(prayerName: String, rakaatCount: Int) {
        Log.d(TAG, "Initializing prayer: name='$prayerName', rakaats=$rakaatCount")
        
        // Initialize RakaatTracker
        rakaatTracker = RakaatTracker(rakaatCount)
        
        // Observe RakaatTracker state
        viewModelScope.launch {
            rakaatTracker?.state?.collect { trackerState ->
                updateUiState { currentState ->
                    currentState.copy(
                        currentRakaat = trackerState.currentRakaat,
                        currentPosition = trackerState.currentPosition.toString(),
                        shouldAutoUnlock = trackerState.shouldAutoUnlock
                    )
                }
            }
        }
        
        // Never allow generic prayer time
        if (prayerName == context.getString(R.string.prayer_time_notification) || !isValidPrayerName(prayerName)) {
            Log.w(TAG, "Invalid or generic prayer name ('$prayerName'), finishing activity")
            _uiState.update { it.copy(shouldFinish = true) }
            return
        }

        isTestLock = prayerName == "Test Prayer"
        
        // Load saved state
        retryCount = prefs.getInt(KEY_RETRY_COUNT, 0)
        lastCameraStateChange = prefs.getLong(KEY_CAMERA_STATE_CHANGE, 0)
        lastPinStateChange = prefs.getLong(KEY_PIN_STATE_CHANGE, 0)
        
        // Initialize with ads disabled and prayer incomplete
        _isPrayerComplete = false
        _pinVerified = false
        updateUiState { 
            it.copy(
                prayerName = prayerName,
                rakaatCount = rakaatCount,
                isPrayerStarted = false,
                currentRakaat = 0,
                currentPosition = "Get Ready",
                isCameraActive = false,
                shouldShowPin = true,
                shouldShowStartButton = true,
                shouldAutoUnlock = false,
                shouldShowAds = false
            )
        }

        // Delay the prayer completion check to give time for user interaction
        viewModelScope.launch {
            delay(5000) // 5 second delay
            try {
                val pinState = pinManager.getVerificationState()
                Log.d(TAG, "PIN state: isLocked=${pinState.isLocked}, attemptsRemaining=${pinState.attemptsRemaining}")
                
                // Only check completion status after delay
                val isPrayerAlreadyComplete = !isTestLock && completionManager.isPrayerComplete(prayerName)
                _isPrayerComplete = isPrayerAlreadyComplete
                
                updateUiState { 
                    it.copy(
                        pinEnabled = !pinState.isLocked,
                        pinCooldownSeconds = (pinState.cooldownTimeMs / 1000).toInt(),
                        errorMessage = if (pinState.isLocked) "PIN entry is locked. Please wait for the cooldown." else null,
                        isLockedOut = pinState.isLocked,
                        pinAttempts = PinManager.MAX_VERIFICATIONS - pinState.attemptsRemaining,
                        shouldAutoUnlock = isPrayerAlreadyComplete,
                        shouldShowAds = false
                    )
                }
                
                if (pinState.isLocked) {
                    startPinCooldownTimer(pinState.cooldownTimeMs)
                }
                
                updatePrayerDisplay(prayerName)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing PIN state: ${e.message}")
            }
        }
        
        if (!_isPrayerComplete) {
            rakaatTracker = RakaatTracker(rakaatCount)
            observeRakaatTracker()
        }
    }

    private fun isValidPrayerName(prayerName: String): Boolean {
        return !prayerName.isEmpty() && !prayerName.isBlank()
    }

    fun startPrayer() {
        viewModelScope.launch {
            stateMutex.withLock {
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastCameraStateChange >= MIN_STATE_CHANGE_INTERVAL) {
                    lastCameraStateChange = currentTime
                    prefs.edit().putLong(KEY_CAMERA_STATE_CHANGE, currentTime).apply()
                    
                    updateUiState { 
                        it.copy(
                            isPrayerStarted = true,
                            isCameraActive = true,
                            errorMessage = null,
                            cameraError = null,
                            shouldShowPin = false,
                            shouldShowStartButton = false
                        )
                    }
                    savePrayerState()
                    
                    // Start camera preview immediately
                    try {
                        if (!cameraManager.isRunning()) {
                            lifecycleOwner?.let { owner ->
                                viewFinder?.let { finder ->
                                    cameraManager.startCamera(owner, finder) { landmarks ->
                                        handleLandmarksDetected(landmarks)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting camera: ${e.message}")
                        handleCameraError(e)
                    }
                }
            }
        }
    }

    suspend fun startPoseDetection() {
        try {
            withTimeoutOrNull(CAMERA_INIT_TIMEOUT) {
                // Only check actual camera state, not UI state
                if (cameraManager.isRunning()) {
                    Log.d(TAG, "Camera already active, skipping initialization")
                    // Force stop and restart to ensure clean state
                    try {
                        cameraManager.stopCamera()
                        // Give a small delay for resources to release
                        kotlinx.coroutines.delay(200)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping camera before restart: ${e.message}")
                    }
                }

                lifecycleOwner?.let { owner ->
                    viewFinder?.let { finder ->
                        try {
                            cameraManager.startCamera(owner, finder) { landmarks ->
                                handleLandmarksDetected(landmarks)
                            }
                            
                            updateUiState {
                                it.copy(
                                    isCameraActive = true,
                                    isPrayerStarted = true,
                                    cameraError = null,
                                    errorMessage = null,
                                    shouldShowStartButton = false
                                )
                            }
                            savePrayerState()
                        } catch (e: Exception) {
                            Log.e(TAG, "Camera initialization failed: ${e.message}")
                            handleCameraError(e)
                            throw e
                        }
                    }
                }
            } ?: throw Exception("Camera initialization timeout")
        } catch (e: Exception) {
            handleCameraError(e)
            throw e
        }
    }

    private fun handleLandmarksDetected(landmarks: List<NormalizedLandmark>) {
        poseOverlayView?.setLandmarks(landmarks)
        rakaatTracker?.processPosition(landmarks)
    }

       private fun observeRakaatTracker() {
        viewModelScope.launch {
            rakaatTracker?.state?.collect { trackerState ->
                updateUiState {
                    it.copy(
                        currentRakaat = trackerState.currentRakaat,
                        currentPosition = trackerState.currentPosition.toString(),
                        errorMessage = trackerState.errorMessage
                    )
                }
                
                // Complete prayer via rakaat tracking
                if (trackerState.isComplete) {
                    handlePrayerCompletion(CompletionType.PRAYER_PERFORMED)
                }
                
                savePrayerState()
            }
        }
    }

  private fun handleCameraError(error: Exception) {
        viewModelScope.launch {
            stateMutex.withLock {
                retryCount++
                updateUiState {
                    it.copy(
                        isCameraActive = false,
                        cameraError = if (retryCount < MAX_RETRIES) error.message else "Camera initialization failed",
                        errorMessage = "Error: ${error.message}",
                        shouldShowPin = true,
                        shouldShowStartButton = true
                    )
                }
                savePrayerState()
            }
        }
    }

    fun stopPrayer() {
        viewModelScope.launch {
            try {
                // Only stop if prayer is actually started
                if (_uiState.value.isPrayerStarted) {
                    stopPoseDetection()
                    updateUiState {
                        it.copy(
                            isPrayerStarted = false,
                            isCameraActive = false,
                            errorMessage = null,
                            cameraError = null,
                            shouldShowPin = true,
                            shouldShowStartButton = true,
                            currentRakaat = 0,
                            currentPosition = "Get Ready"
                        )
                    }
                    savePrayerState()
                    rakaatTracker?.reset()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping prayer: ${e.message}")
            }
        }
    }

    fun stopPoseDetection() {
        viewModelScope.launch {
            stateMutex.withLock {
                try {
                    // Force stop regardless of running state to ensure resources are released
                    cameraManager.stopCamera()
                    
                    updateUiState {
                        it.copy(
                            isCameraActive = false,
                            shouldShowPin = false,
                            shouldShowStartButton = true
                        )
                    }
                    savePrayerState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping camera: ${e.message}")
                    // Reset state even if there's an error
                    updateUiState {
                        it.copy(
                            isCameraActive = false,
                            shouldShowPin = false,
                            shouldShowStartButton = true
                        )
                    }
                    savePrayerState()
                }
            }
        }
    }

    private fun savePrayerState() {
        prefs.edit().apply {
            putBoolean(KEY_PRAYER_STARTED, uiState.value.isPrayerStarted)
            putInt(KEY_CURRENT_RAKAAT, uiState.value.currentRakaat)
            putString(KEY_CURRENT_POSITION, uiState.value.currentPosition)
            putBoolean(KEY_CAMERA_ACTIVE, uiState.value.isCameraActive)
            putInt(KEY_RETRY_COUNT, retryCount)
            putLong(KEY_CAMERA_STATE_CHANGE, lastCameraStateChange)
            putLong(KEY_PIN_STATE_CHANGE, lastPinStateChange)
            apply()
        }
    }

    private fun startPinCooldownTimer(cooldownTimeMs: Long) {
        cooldownJob?.cancel()
        stateRecoveryJob?.cancel()

        // Safety check for invalid cooldown time
        if (cooldownTimeMs <= 0) {
            resetPinStateInternal()
            return
        }

        cooldownJob = viewModelScope.launch {
            try {
                var remainingTime = cooldownTimeMs
                val startTime = SystemClock.elapsedRealtime()
                
                while (remainingTime > 0 && isActive) {
                    updateUiState {
                        it.copy(
                            isLockedOut = true,
                            pinEnabled = false,
                            pinCooldownSeconds = (remainingTime / 1000).toInt(),
                            errorMessage = "Please wait ${remainingTime / 1000} seconds"
                        )
                    }
                    try {
                        delay(1000L)
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Cooldown timer cancelled")
                        break
                    }
                    
                    val elapsed = SystemClock.elapsedRealtime() - startTime
                    remainingTime = (cooldownTimeMs - elapsed).coerceAtLeast(0)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Cooldown timer cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error in cooldown timer: ${e.message}")
            } finally {
                resetPinStateInternal()
            }
        }

        // Safety timeout to prevent permanent lockout
        stateRecoveryJob = viewModelScope.launch {
            try {
                try {
                    delay(cooldownTimeMs + 5000) // Add 5 second buffer
                    if (uiState.value.isLockedOut) {
                        Log.w(TAG, "Safety timeout triggered - forcing pin state reset")
                        resetPinStateInternal()
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Safety timeout cancelled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in safety timeout: ${e.message}")
            }
        }
    }

    private fun resetPinStateInternal() {
        updateUiState {
            it.copy(
                isLockedOut = false,
                pinEnabled = true,
                pinCooldownSeconds = 0,
                errorMessage = null
            )
        }
    }

    fun verifyPin(pin: String) {
        viewModelScope.launch {
            try {
                if (pinManager.verifyPin(pin)) {
                    _pinVerified = true
                    // Reset pin state and mark as PIN verified
                    updateUiState {
                        it.copy(
                            pinAttempts = 0,
                            errorMessage = null,
                            shouldShowPin = false,
                            shouldAutoUnlock = false, // Modified to prevent immediate auto-unlock
                            pinEnabled = true,
                            pinCooldownSeconds = 0,
                            isLockedOut = false,
                            pinVerified = true
                        )
                    }
                    
                    // Complete prayer via PIN
                    handlePrayerCompletion(CompletionType.PIN_VERIFIED)
                } else {
                    // Start 5-second cooldown timer
                    startPinCooldownTimer(5000L)
                    updateUiState {
                        it.copy(
                            errorMessage = "Incorrect PIN. Please wait 5 seconds.",
                            pinAttempts = it.pinAttempts + 1,
                            pinEnabled = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verifying PIN", e)
                updateUiState { 
                    it.copy(
                        errorMessage = "Error verifying PIN",
                        pinEnabled = true,
                        pinCooldownSeconds = 0
                    )
                }
            }
        }
    }

    private fun handlePrayerCompletion(completionType: CompletionType) {
        viewModelScope.launch {
            try {
                _isPrayerComplete = true
                Log.d(TAG, "Prayer completed. Type=$completionType, pinVerified=$_pinVerified, prayerName=${_uiState.value.prayerName}")
                
                val prayerName = _uiState.value.prayerName
                if (prayerName.isNotEmpty()) {
                    Log.d(TAG, "Marking prayer [$prayerName] as complete with type: $completionType")
                    completionManager.markPrayerComplete(prayerName, completionType)

                // **Crucial Fix:** Clear the active lock state in the manager's state file
                // This prevents false bypass detection by the monitor service.
                completionManager.clearLockScreenActive()

                    prayerStateChecker.onPrayerCompleted(prayerName)
    
                    // Verify that it was stored correctly
                    val storedType = completionManager.getCompletionType(prayerName)
                    Log.d(TAG, "Stored completion type for prayer [$prayerName]: $storedType")
                }

                _uiState.update { 
                    it.copy(
                        shouldShowAds = false,
                        isPrayerComplete = true
                    )
                }

                if (_pinVerified) {
                    Log.d(TAG, "PIN already verified, enabling ads")
                    displayAd()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in handlePrayerCompletion", e)
            }
        }
    }

    fun onPrayerComplete() {
        viewModelScope.launch {
            try {
                stopPoseDetection()
                val currentState = _uiState.value
                if (!_isPrayerComplete) {
                    completionManager.markPrayerComplete(currentState.prayerName, CompletionType.PRAYER_PERFORMED)
                    handlePrayerCompletion(CompletionType.PRAYER_PERFORMED)
                    
                    // Update the UI state to mark prayer complete and request ad display.
                    updateUiState { it.copy(shouldShowAds = false, shouldAutoUnlock = false) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onPrayerComplete: ${e.message}")
            }
        }
    }

    fun handleUnlock(activity: Activity) {
        viewModelScope.launch {
            try {
                if (isTestLock) {
                    // For test lock, show ad immediately
                    showUnlockAd(activity)
                } else {
                    // Show the ad only if it's available
                    if (adManager.isRewardedAdAvailable()) {
                        showUnlockAd(activity)
                    } else {
                        // If ad not available, just finish the activity
                        finishActivity(activity)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling unlock", e)
                finishActivity(activity)
            }
        }
    }

    fun showUnlockAd(activity: Activity) {
        // Set the callback *before* checking availability/showing
        // This ensures the callback is set even if the ad is shown immediately
        adManager.setRewardedAdCallback(object : AdManager.RewardedAdCallback {
            override fun onAdLoaded() {
                Log.d(TAG, "Rewarded ad loaded successfully (ViewModel callback).")
            }
            
            override fun onAdFailedToLoad(errorMessage: String?) {
                Log.e(TAG, "Failed to load rewarded ad (ViewModel callback): $errorMessage")
                // If load fails, we might still want to finish if an unlock was triggered
                // Check shouldFinish flag? Or just proceed to finish?
                 Log.d(TAG, "Ad failed to load, finishing activity directly.")
                 finishActivity(activity)
            }
            
            override fun onAdDismissed() {
                Log.d(TAG, "Rewarded ad dismissed (ViewModel callback), finishing activity.")
                // User closed the ad without earning reward, or ad finished.
                finishActivity(activity)
            }

            override fun onAdShown() {
                Log.d(TAG, "Rewarded ad shown successfully (ViewModel callback).")
                // Keep screen pinned while ad is showing
                _uiState.update { it.copy(isLoading = false) }
            }

            override fun onAdFailedToShow(errorMessage: String?) {
                Log.e(TAG, "Failed to show rewarded ad (ViewModel callback): $errorMessage, finishing activity.")
                // If show fails, definitely finish.
                finishActivity(activity)
            }

            override fun onAdClicked() {
                Log.d(TAG, "Rewarded ad clicked (ViewModel callback), stopping lock task.")
                // Just unpin and let the ad handle navigation
                // The finish will happen either via reward or dismissal callback.
                try {
                    // Ensure stopLockTask is called on the Activity's main thread if necessary
                    activity.runOnUiThread { activity.stopLockTask() } 
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping lock task on ad click: ${e.message}")
                }
            }
            
            override fun onUserEarnedReward(amount: Int) {
                Log.d(TAG, "User earned reward (ViewModel callback): $amount, finishing activity.")
                // User earned reward, finish the activity.
                finishActivity(activity)
            }
        })

        // Now check availability and show
        if (adManager.isRewardedAdAvailable()) {
            Log.d(TAG, "Unity rewarded ad available, attempting to show.")
            // Show the preloaded rewarded ad. The listener is handled internally by AdManager now.
            adManager.showRewardedAd(activity)
        } else {
            Log.d(TAG, "No Unity rewarded ad available, finishing activity directly.")
            finishActivity(activity) // Finish immediately if no ad is ready
        }
    }

    private fun finishActivity(activity: Activity) {
         // Ensure UI updates and finish() are called on the main thread
         activity.runOnUiThread {
             try {
                 // Check if activity is finishing or destroyed before proceeding
                 if (activity.isFinishing || activity.isDestroyed) {
                    Log.w(TAG, "finishActivity called but activity is already finishing/destroyed.")
                    return@runOnUiThread
                 }
                 
                 Log.d(TAG, "Stopping lock task and finishing activity: ${activity.localClassName}")
                 activity.stopLockTask()
                 _uiState.update { it.copy(isLoading = false, shouldFinish = true) }
                 activity.finish()
             } catch (e: Exception) {
                 Log.e(TAG, "Error during finishActivity: ${e.message}", e)
                 // Force finish if error occurs during cleanup
                 if (!activity.isFinishing) {
                    activity.finish()
                 }
             }
         }
    }

    fun onResume() {
        viewModelScope.launch {
            stateMutex.withLock {
                try {
                    // Check if we need to show an ad
                    if (prayerStateChecker.shouldShowAdOnResume()) {
                        updateUiState { it.copy(shouldShowAds = false) }
                        // Preload ad if needed
                        if (!adManager.isRewardedAdAvailable()) {
                            adManager.preloadRewardedAd()
                        }
                    }
                    
                    // Check for any pending prayers
                    prayerStateChecker.checkAndQueuePrayers()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onResume: ${e.message}")
                }
            }
        }
    }

    fun preloadNextAd() {
        try {
            // Renamed preload function
            adManager.preloadRewardedAd()
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading next ad", e)
        }
    }

    private fun handleAdClosed() {
        try {
            // Renamed preload function
            adManager.preloadRewardedAd()
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading ad after close", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPoseDetection()
        cooldownJob?.cancel()
        clearPrayerStatePreferences()
        viewModelScope.cancel() // This will cancel the ad preloading coroutine
    }

    private fun clearPrayerStatePreferences() {
        prefs.edit()
            .clear()
            .remove("pin_verified")
            .apply()
        resetPinStateInternal()
        cooldownJob?.cancel()
        
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("pin_verified")
            .apply()
    }

    fun onAdClosed() {
        viewModelScope.launch {
            try {
                if (nextServiceIntent != null && nextPrayerIntent != null) {
                    Log.d(TAG, "Starting next prayer after ad closed")
                    
                    // Start service first
                    context.startService(nextServiceIntent)
                    
                    // Small delay to ensure service is running
                    delay(500)
                    
                    // Start activity
                    context.startActivity(nextPrayerIntent)
                    
                    // Clear intents
                    nextServiceIntent = null
                    nextPrayerIntent = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onAdClosed: ${e.message}")
            }
        }
    }

    override fun unlockAndReset() {
        viewModelScope.launch {
            try {
                _pinVerified = false
                _isPrayerComplete = false
                updateUiState {
                    it.copy(
                        shouldShowPin = true,
                        shouldShowStartButton = true,
                        shouldShowAds = false,
                        pinEnabled = true,
                        pinCooldownSeconds = 0,
                        isLockedOut = false,
                        pinAttempts = 0,
                        errorMessage = null
                    )
                }
                clearPrayerStatePreferences()
            } catch (e: Exception) {
                Log.e(TAG, "Error in unlockAndReset", e)
            }
        }
    }

    private fun loadQueuedPrayers() {
        try {
            val queueStr = prefs.getString(KEY_QUEUED_PRAYERS, "") ?: ""
            if (queueStr.isNotEmpty()) {
                queuedPrayers.clear()
                queueStr.split(",").forEach { prayerStr ->
                    val parts = prayerStr.split(":")
                    if (parts.size == 2) {
                        queuedPrayers.add(Pair(parts[0], parts[1].toInt()))
                    }
                }
                updateUiState { it.copy(hasQueuedPrayers = queuedPrayers.isNotEmpty()) }
                Log.d(TAG, "Loaded ${queuedPrayers.size} prayers from queue")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading queued prayers: ${e.message}")
        }
    }

    // New method to enable fallback mode
    fun enableFallbackMode() {
        _uiState.update { currentState ->
            currentState.copy(
                isInFallbackMode = true,
                errorMessage = "Using fallback mode - some features may be limited"
            )
        }
    }

    fun resetPrayerState() {
        viewModelScope.launch {
            try {
                _isPrayerComplete = false
                _pinVerified = false
                
                _uiState.update {
                    it.copy(
                        shouldShowPin = true,
                        shouldShowStartButton = false,
                        shouldShowAds = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting prayer state", e)
            }
        }
    }

    fun onPrayerStarted() {
        viewModelScope.launch {
            try {
                _isPrayerComplete = false
                _uiState.update {
                    it.copy(isPrayerStarted = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting prayer", e)
            }
        }
    }

    fun onPrayerStopped() {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isPrayerStarted = false,
                        shouldShowAds = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping prayer", e)
            }
        }
    }

    fun onLockTaskFailed() {
        lockTaskFailed = true
        _uiState.update {
            it.copy(
                isInFallbackMode = true,
                shouldShowAds = false
            )
        }
    }

    // Add properties for camera initialization
    private var lifecycleOwner: LifecycleOwner? = null
    private var viewFinder: PreviewView? = null

    fun initializeCamera(owner: LifecycleOwner, finder: PreviewView) {
        lifecycleOwner = owner
        viewFinder = finder
    }

    private fun displayAd() {
        viewModelScope.launch {
            try {
                _uiState.update { 
                    it.copy(shouldShowAds = true)
                }
                Log.d(TAG, "Ad display requested. Current state: shouldShowAds=${_uiState.value.shouldShowAds}")
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting ad display", e)
            }
        }
    }

    fun resetPinAttempts() {
        viewModelScope.launch {
            try {
                _pinVerified = false
                updateUiState {
                    it.copy(
                        pinAttempts = 0,
                        errorMessage = null,
                        shouldShowPin = true,
                        shouldShowStartButton = true,
                        shouldShowAds = false,
                        pinEnabled = true,
                        pinCooldownSeconds = 0,
                        isLockedOut = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting PIN attempts", e)
            }
        }
    }

    fun resetPinState() {
        viewModelScope.launch {
            try {
                _pinVerified = false
                updateUiState {
                    it.copy(
                        pinAttempts = 0,
                        errorMessage = null,
                        shouldShowPin = true,
                        shouldShowStartButton = true,
                        shouldShowAds = false,
                        pinEnabled = true,
                        pinCooldownSeconds = 0,
                        isLockedOut = false
                    )
                }
                
                // Also reset prayer state if needed
                if (_isPrayerComplete) {
                    resetPrayerState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting PIN state", e)
            }
        }
    }

    /**
     * Resets all prayer tracking state when a user presses back
     * This ensures they can start a fresh prayer session
     */
    fun resetPrayerTracking() {
        viewModelScope.launch {
            stateMutex.withLock {
                // Reset rakaat tracking
                _isPrayerComplete = false
                _pinVerified = false
                rakaatTracker?.reset()
                
                // Reset prayer progress state
                updateUiState {
                    it.copy(
                        isPrayerStarted = false,
                        isCameraActive = false,
                        shouldShowStartButton = true,
                        shouldShowPin = true,
                        currentRakaat = 0,
                        currentPosition = "Get Ready",
                        isPrayerComplete = false,
                        errorMessage = null,
                        cameraError = null
                    )
                }
                
                // Save the reset state
                savePrayerState()
                
                // Make sure any camera resources are released
                try {
                    if (cameraManager.isRunning()) {
                        cameraManager.stopCamera()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping camera during reset: ${e.message}")
                }
                
                // Log the reset
                Log.d(TAG, "Prayer tracking reset - user pressed back button")
            }
        }
    }

    /**
     * Mark the current prayer as complete
     */
    fun markPrayerComplete() {
        viewModelScope.launch {
            try {
                stopPoseDetection()
                val currentState = _uiState.value
                if (!_isPrayerComplete) {
                    _isPrayerComplete = true
                    completionManager.markPrayerComplete(currentState.prayerName, CompletionType.PRAYER_PERFORMED)
                    handlePrayerCompletion(CompletionType.PRAYER_PERFORMED)
                    
                    // Update the UI state to mark prayer complete
                    updateUiState { 
                        it.copy(
                            isPrayerComplete = true, 
                            shouldShowAds = false, 
                            shouldAutoUnlock = true
                        ) 
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in markPrayerComplete: ${e.message}")
            }
        }
    }
}
