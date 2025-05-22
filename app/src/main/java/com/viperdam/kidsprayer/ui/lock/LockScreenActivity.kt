package com.viperdam.kidsprayer.ui.lock

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.ads.AdManager
import com.viperdam.kidsprayer.camera.CameraManager as AppCameraManager
import com.viperdam.kidsprayer.databinding.ActivityLockScreenBinding
import com.viperdam.kidsprayer.prayer.PrayerStateChecker
import com.viperdam.kidsprayer.security.DeviceAdminReceiver
import com.viperdam.kidsprayer.service.LockScreenService
import com.viperdam.kidsprayer.service.PrayerReceiver
import com.viperdam.kidsprayer.ui.main.MainActivity
import com.viperdam.kidsprayer.utils.PrayerValidator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.*
import android.widget.Toast
import com.viperdam.kidsprayer.ui.lock.ads.LockScreenAds
import com.viperdam.kidsprayer.util.PrayerSettingsManager
import androidx.activity.enableEdgeToEdge
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.viperdam.kidsprayer.utils.SystemBarsUtil
import android.content.SharedPreferences
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

@AndroidEntryPoint
class LockScreenActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLockScreenBinding
    private val viewModel: LockScreenViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())
    private var contentCheckHandler: Handler? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var homeButtonReceiver: BroadcastReceiver? = null
    private var canFinish = false
    private var isShowingAd = false
    private var isUnlocked = false
    private var shouldUnlock = false
    private var adLoadTimeout: Runnable? = null
    private var lastScreenOffTime = 0L
    private var lastScreenOnTime = 0L
    private var lastCameraReset = 0L
    private var lastPinReset = 0L
    private var lastStateSync = 0L
    private var lastServiceCheck = 0L
    private var stateCheckRunnable: Runnable? = null
    private var lastFocusTime = 0L
    private var lastTouchTime = 0L
    private var lastInputTime = 0L
    private var emptyScreenRetryCount = 0
    private var lastContentCheck = 0L
    private var adCheckRunnable: Runnable? = null
    private var forceShowAd: Boolean = false
    private var isTestPrayer = false
    private var pinVerified = false
    private var isUnlocking = false

    companion object {
        private const val TAG = "LockScreenActivity"
        private const val PREFS_NAME = "LockScreenPrefs"
        private const val KEY_DEVICE_BOOT_TIME = "device_boot_time"
        private const val KEY_WAS_LOCKED = "was_locked"
        private const val KEY_LAST_ACTIVATION = "last_activation"
        private const val KEY_LOCK_SCREEN_SHOWN = "lock_screen_shown"
        private const val AD_CHECK_INTERVAL = 30000L // 30 seconds
        private const val AD_LOAD_TIMEOUT = 5000L // 5 seconds
        private const val FOCUS_CHECK_INTERVAL = 1000L // 1 second
        private const val CONTENT_CHECK_INTERVAL = 1000L // 1 second
        private const val CONTENT_CHECK_THRESHOLD = 2000L // 2 seconds
        private const val MIN_INPUT_INTERVAL = 200L // 200ms
        private const val MIN_SCREEN_EVENT_INTERVAL = 1000L // 1 second
        private const val MIN_STATE_RESET_INTERVAL = 2000L // 2 seconds
        private const val STATE_SYNC_INTERVAL = 5000L // 5 seconds
        private const val SERVICE_CHECK_INTERVAL = 10000L // 10 seconds
        private const val MAX_EMPTY_SCREEN_RETRIES = 3
        private const val TOUCH_EVENT_INTERVAL = 300L // 300ms
        var isPinned = false
        
        // Define the missing action constant
        const val ACTION_LOCKSCREEN_DESTROYED = "com.viperdam.kidsprayer.ACTION_LOCKSCREEN_DESTROYED"
        
        // Static flag to track if a lock screen is already active
        @Volatile
        private var isInstanceActive = false
        // Use this consistent naming for the prefs file related to lock screen state
        const val LOCK_SCREEN_PREFS = "lock_screen_prefs" // Renamed from PREFS_NAME for clarity
        const val KEY_LOCK_SCREEN_ACTIVE = "lock_screen_active" // Added
        // private const val PREFS_NAME = "LockScreenPrefs" // Keep or remove original if not used elsewhere
    }

    @Inject
    lateinit var cameraManager: AppCameraManager

    @Inject
    lateinit var adManager: AdManager

    @Inject
    lateinit var keyguardManager: KeyguardManager

    @Inject
    lateinit var devicePolicyManager: DevicePolicyManager

    @Inject
    lateinit var prayerStateChecker: PrayerStateChecker

    @Inject
    lateinit var lockScreenAds: LockScreenAds

    // Modify the existing lazy prefs to use the new constants
    private val prefs by lazy { getSharedPreferences(LOCK_SCREEN_PREFS, Context.MODE_PRIVATE) }

    // Added for ad preloading logic - REMOVE the duplicate declaration below
    // private lateinit var prefs: SharedPreferences
    private val refreshRunnable = Runnable { checkAndRefreshAd() }
    private val ONE_HOUR_MS = 60 * 60 * 1000L
    // Use 1 minute for testing:
    // private val ONE_HOUR_MS = 1 * 60 * 1000L

    /**
     * Validates prayer data and sets appropriate flags if invalid
     * @param prayerName The name of the prayer to validate
     * @return true if prayer data is valid, false otherwise
     */
    private fun validatePrayerData(prayerName: String?): Boolean {
        // Set isTestPrayer here
        isTestPrayer = prayerName?.equals("Test Prayer", ignoreCase = true) == true
        
        Log.d(TAG, "Validating prayer data: '$prayerName', isTestPrayer: $isTestPrayer")
        
        // Use the centralized PrayerValidator 
        if (!PrayerValidator.isValidPrayerName(prayerName) && !isTestPrayer) {
            val errorReason = when {
                prayerName == null -> "null prayer name"
                prayerName.isEmpty() -> "empty prayer name"
                prayerName == "Prayer Time" -> "generic prayer name"
                else -> "unknown reason"
            }
            Log.w(TAG, "Invalid prayer data received ($errorReason)")
            
            // Use the centralized method to mark invalid prayer data
            PrayerValidator.markInvalidPrayerData(this, errorReason)
            
            Log.w(TAG, "Set invalid_prayer_data=true and cooldown timestamp to prevent immediate relaunch")
            return false
        }
        
        return true
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "LockScreenActivity onCreate")
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display using the standard library function.
        enableEdgeToEdge()
        
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Extract prayer name from intent
        val prayerName = intent.getStringExtra("prayer_name")
        
        // Keep screen on while lock screen is active 
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Set up proper inset handling for edge-to-edge display
        SystemBarsUtil.setupEdgeToEdge(this) { _, windowInsets ->
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Apply padding to header area (prayerInfoCard)
            binding.prayerInfoCard.setPadding(
                binding.prayerInfoCard.paddingLeft,
                systemBarsInsets.top + binding.prayerInfoCard.paddingTop,
                binding.prayerInfoCard.paddingRight,
                binding.prayerInfoCard.paddingBottom
            )
            
            // Apply padding to bottom area (pinEntryCard or startPrayerButton)
            binding.pinEntryCard.setPadding(
                binding.pinEntryCard.paddingLeft,
                binding.pinEntryCard.paddingTop,
                binding.pinEntryCard.paddingRight,
                systemBarsInsets.bottom + binding.pinEntryCard.paddingBottom
            )
            
            windowInsets
        }
        
        // Apply immersive mode after view is set
        window.decorView.post {
            // Apply insets if needed while maintaining edge-to-edge appearance
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        }

        // Validate prayer data before proceeding
        val prayerTime = intent.getLongExtra("prayer_time", 0)
        val rakaatCount = intent.getIntExtra("rakaat_count", 0)
        Log.d(TAG, "Prayer validation - name: '$prayerName', time: $prayerTime, rakaat: $rakaatCount")
        
        // Early validation to prevent launching with invalid data
        Log.d(TAG, "Validating prayer data: '$prayerName', isTestPrayer: ${intent.getBooleanExtra("is_test_prayer", false)}")
        
        // For non-test prayers, validate the data
        if (prayerName == null && !intent.getBooleanExtra("is_test_prayer", false)) {
            Log.w(TAG, "Invalid prayer data received (null prayer name)")
            
            // Mark invalid data with longer cooldown (2 minutes instead of 1)
            PrayerValidator.markInvalidPrayerData(this, "null prayer name", 120000)
            Log.w(TAG, "Set invalid_prayer_data=true and cooldown timestamp to prevent immediate relaunch")
            
            // Don't show this activity, just finish
            finish()
            return
        }
        
        // Proceed with normal initialization
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get the settings manager
        val settingsManager = PrayerSettingsManager.getInstance(this)
        
        // Check if an instance is already running
        if (isInstanceActive) {
            Log.d(TAG, "Another lock screen instance is already active, finishing this one")
            finish()
            return
        }
        
        // Check if lock screen is enabled for this prayer
        if (prayerName != null && !prayerName.equals("Test Prayer", ignoreCase = true)) {
            if (!settingsManager.isLockEnabled(prayerName)) {
                Log.d(TAG, "Lock screen disabled for $prayerName, finishing activity")
                finish()
                return
            }
            
            // Check if this event has already been handled
            val prayerTime = intent.getLongExtra("prayer_time", 0L)
            if (prayerTime > 0 && settingsManager.hasEventOccurred(PrayerSettingsManager.EVENT_LOCKSCREEN_SHOWN, prayerName, prayerTime)) {
                Log.d(TAG, "Lock screen already shown for $prayerName at ${Date(prayerTime)}, finishing activity")
                // Verify if PIN is already verified, and if so, just finish
                val receiverPrefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
                if (receiverPrefs.getBoolean("pin_verified", false)) {
                    Log.d(TAG, "PIN already verified for $prayerName, finishing activity")
                    finish()
                    return
                }
            }
            
            // Mark as shown immediately to prevent race conditions
            if (prayerTime > 0) {
                settingsManager.markEventOccurred(PrayerSettingsManager.EVENT_LOCKSCREEN_SHOWN, prayerName, prayerTime)
            }
        }
        
        // Set the flag immediately to prevent another instance from starting
        isInstanceActive = true

        // If this is a relaunch from the monitor service, update our state
        val isMonitorRelaunch = intent.getBooleanExtra("monitor_relaunch", false)
        if (isMonitorRelaunch) {
            Log.d(TAG, "This is a relaunch from monitor service")
            // Update state to prevent immediate dismissal
            prefs.edit().apply {
                putBoolean(KEY_WAS_LOCKED, true)
                putLong(KEY_LAST_ACTIVATION, System.currentTimeMillis())
                putBoolean(KEY_LOCK_SCREEN_SHOWN, true)
                apply()
            }
            
            // Also update shared prayer receiver prefs
            val receiverPrefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            receiverPrefs.edit().apply {
                putBoolean("lock_screen_active", true)
                putBoolean("pin_verified", false)
                putBoolean("is_unlocked", false)
                
                // Save test prayer info if applicable
                if (prayerName?.equals("Test Prayer", ignoreCase = true) == true) {
                    putBoolean("is_test_prayer", true)
                    putString("active_prayer", "Test Prayer")
                    putInt("active_rakaat", intent.getIntExtra("rakaat_count", 4))
                } else if (prayerName != null) {
                    putBoolean("is_test_prayer", false)
                    putString("active_prayer", prayerName)
                    putInt("active_rakaat", intent.getIntExtra("rakaat_count", 0))
                }
                
                apply()
            }
        }

        // Set content view early so we can show error notifications
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enhanced validation with detailed logging
        // Don't redeclare these variables, they're already declared above
        Log.d(TAG, "Prayer validation - name: '$prayerName', time: $prayerTime, rakaat: $rakaatCount")

        // If missing critical prayer data, show detailed error and finish activity
        if (!validatePrayerData(prayerName)) {
            // Show error notification before finishing
            showErrorNotification("Error: Missing prayer information")
            
            // Give the user a chance to see the error before finishing
            handler.postDelayed({ finish() }, 3000)
            return
        }
        
        Log.d(TAG, "Prayer data validated successfully for '$prayerName'")
        
        // Fix for prayer time issues - Set current time if prayer time is invalid
        val currentTimeMillis = System.currentTimeMillis()
        if (prayerTime <= 0) {
            Log.w(TAG, "Invalid prayer time ($prayerTime) received for $prayerName, using current time instead")
            
            // Update prayer time in intent extras
            intent.putExtra("prayer_time", currentTimeMillis)
            
            // Update prayer time in shared preferences for consistency
            val receiverPrefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            receiverPrefs.edit().putLong("pending_prayer_time", currentTimeMillis).apply()
        }

        // Reset states on create
        isShowingAd = false
        isUnlocked = false
        canFinish = false
        emptyScreenRetryCount = 0
        isPinned = false
        
        // Set task description using modern API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setTaskDescription(
                ActivityManager.TaskDescription(
                    getString(R.string.app_name),
                    R.mipmap.ic_launcher,
                    resources.getColor(R.color.primary, theme)
                )
            )
        }

        binding.prayerTimeTextView.text = getCurrentPrayerTime()

        viewModel.setPoseOverlayView(binding.poseOverlay)

        // Configure window after view is set
        val showWhenLocked = intent.getBooleanExtra("show_when_locked", true)
        val turnScreenOn = intent.getBooleanExtra("turn_screen_on", true)
        
        if (showWhenLocked) {
            setShowWhenLocked(true)
        }
        if (turnScreenOn) {
            setTurnScreenOn(true)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (getSystemService(KeyguardManager::class.java))?.requestDismissKeyguard(this, null)
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enable immersive mode after view is set
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.post {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.post {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        }

        // Lock to task (kiosk mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Check if device admin is active
                if (!DeviceAdminReceiver.isAdminActive(this)) {
                    Log.e(TAG, "Cannot pin screen - device admin not active")
                    DeviceAdminReceiver.requestAdminPrivileges(this)
                    isPinned = false
                    return
                }

                // Try pinning with retry logic
                var retryCount = 0
                val maxRetries = 3
                val retryDelay = 500L
                
                while (retryCount < maxRetries && !isPinned) {
                    Log.d(TAG, "Attempting to start lock task mode")
                    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    
                    // Check if already in lock task mode
                    if (activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                        Log.d(TAG, "Already in lock task mode")
                        isPinned = true
                        prefs.edit().putBoolean(KEY_WAS_LOCKED, true).apply()
                        break
                    }
                    
                    if (devicePolicyManager.isDeviceOwnerApp(packageName) || devicePolicyManager.isProfileOwnerApp(packageName)) {
                        Log.d(TAG, "App is device/profile owner, using setLockTaskFeatures")
                        try {
                            // Ensure we're whitelisted
                            devicePolicyManager.setLockTaskPackages(
                                DeviceAdminReceiver.getComponentName(this),
                                arrayOf(packageName)
                            )
                            
                            // Start lock task mode
                            startLockTask()
                            isPinned = true
                            prefs.edit().putBoolean(KEY_WAS_LOCKED, true).apply()
                            Log.d(TAG, "Successfully pinned screen on attempt ${retryCount + 1}")
                        } catch (e: Exception) {
                            retryCount++
                            Log.e(TAG, "Failed to start lock task mode (attempt $retryCount): ${e.message}")
                            if (retryCount < maxRetries) {
                                Thread.sleep(retryDelay)
                            }
                        }
                    } else {
                        Log.w(TAG, "App is not device/profile owner, using fallback without setLockTaskFeatures")
                        try {
                            // Fallback: Use startLockTask() without features
                            startLockTask()
                            isPinned = true
                            prefs.edit().putBoolean(KEY_WAS_LOCKED, true).apply()
                            Log.d(TAG, "Successfully pinned screen on attempt ${retryCount + 1} using fallback")
                        } catch (e: Exception) {
                            retryCount++
                            Log.e(TAG, "Failed to start lock task mode (attempt $retryCount) using fallback: ${e.message}")
                            if (retryCount < maxRetries) {
                                Thread.sleep(retryDelay)
                            }
                        }
                    }
                }

                if (!isPinned) {
                    Log.e(TAG, "Failed to pin screen after $maxRetries attempts")
                    enableFallbackMode()
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up lock screen", e)
                enableFallbackMode()
            }
        }

        // Keep screen on and prevent backgrounding
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM  // Prevent IME from taking focus
        )
        
        // Handle system bars using WindowInsetsController
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Initialize UI
        setupUI()
        setupLockScreen()
        
        // Initialize ViewModel with prayer info
        viewModel.initializePrayer(prayerName ?: "Unknown Prayer", rakaatCount)
        
        // Start observing states after setup
        observeStates()

        // Hide system UI immediately
        hideSystemUI()

        // Initialize UI components
        setupCameraPreview()
        setupPrayerUI()
        setupBackButton()

        // Start monitoring states
        startStateMonitoring()
        startContentChecking()

        // Observe UI state for ad and navigation logic
        observeUiState()

        // Register screen off receiver with high priority
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // Only show lock screen if not unlocked and not finishing
                        if (!isUnlocked && !canFinish) {
                            showLockScreen(force = true)
                        }
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        // Only show lock screen if not unlocked and not finishing
                        if (!isUnlocked && !canFinish) {
                            moveTaskToFront()
                            showLockScreen(force = true)
                        }
                    }
                }
            }
        }
        registerReceiver(screenStateReceiver, filter)

        // Setup ad click handling
        setupAdCallbacks()

        // Check if this is a fresh boot
        val lastBootTime = prefs.getLong(KEY_DEVICE_BOOT_TIME, 0)
        val currentBootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        
        if (lastBootTime != currentBootTime) {
            // Device was rebooted, save new boot time
            prefs.edit().putLong(KEY_DEVICE_BOOT_TIME, currentBootTime).apply()
            setupLockScreen()
        } else if (!isPinned && prefs.getBoolean(KEY_WAS_LOCKED, false) && !isUnlocked) {
            // We were locked but not pinned (e.g., after process restart)
            setupLockScreen()
        }

        // Debug logging for lock screen active flag
        val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("lock_screen_active", false)
        Log.d(TAG, "LockScreenActivity onCreate - lock_screen_active flag is: $isActive")
        
        // If flag is not set, set it now and log
        if (!isActive) {
            Log.d(TAG, "Setting lock_screen_active flag to true in onCreate")
            prefs.edit().putBoolean("lock_screen_active", true).apply()
        }

        // Initialize SharedPreferences - REMOVED - Handled by lazy delegate 'prefs'
        // prefs = getSharedPreferences(LOCK_SCREEN_PREFS, Context.MODE_PRIVATE)
    }

    private fun setupUI() {
        // Set window flags to allow touch input for PIN entry
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )

        // Prevent task manager from showing this task
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
        }

        // Set up touch interceptor with improved focus handling
        binding.root.setOnTouchListener { _, event ->
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastTouchTime >= TOUCH_EVENT_INTERVAL) {
                lastTouchTime = currentTime
                when (event.action) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_MOVE -> {
                        // Check if touch is outside PIN entry area
                        if (binding.pinEntryCard.visibility != View.VISIBLE || 
                            !isTouchInsideView(event, binding.pinEntryCard)) {
                            
                            // Only handle the touch if we're not in the PIN entry
                            handleOutsideTouchGracefully(event)
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false // Let the touch pass through to children
        }

        // Ensure PIN entry card is focusable and visible
        binding.pinEntryCard.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            visibility = View.VISIBLE
            elevation = 10f // Ensure it's above other views
            requestFocus()
        }

        // Observe UI state for updates
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                if (!isUnlocked) {
                    binding.apply {
                        // Update PIN entry visibility with higher priority handling
                        if (state.shouldShowPin) {
                            pinEntryCard.visibility = View.VISIBLE
                            pinEntryCard.bringToFront()
                            pinEntryCard.requestLayout()
                            Log.d(TAG, "UI state updated: PIN entry set to VISIBLE")
                        } else {
                            // Only hide PIN if we're showing the camera - otherwise keep it visible
                            if (state.isPrayerStarted && viewFinder.visibility == View.VISIBLE) {
                                pinEntryCard.visibility = View.GONE
                                Log.d(TAG, "UI state updated: PIN entry set to GONE (camera active)")
                            } else if (!state.isPrayerStarted) {
                                // If prayer not started, always show PIN
                                pinEntryCard.visibility = View.VISIBLE
                                Log.d(TAG, "UI state enforced: PIN entry visible when prayer not started")
                            }
                        }
                        
                        // Update camera preview
                        viewFinder.visibility = if (state.isPrayerStarted) View.VISIBLE else View.GONE
                        
                        // Update start button and ensure it's enabled
                        startPrayerButton.visibility = if (state.shouldShowStartButton) View.VISIBLE else View.GONE
                        startPrayerButton.isEnabled = state.shouldShowStartButton && !state.isCameraActive
                        
                        // Update prayer info
                        prayerName.text = state.prayerName
                        rakaatCounter.text = "${state.currentRakaat}/${state.rakaatCount}"
                        positionName.text = state.currentPosition
                        
                        // Show/hide error messages
                        errorCard.visibility = if (state.errorMessage != null) View.VISIBLE else View.GONE
                        
                        // Handle auto-unlock if needed
                        if (state.shouldAutoUnlock && !isUnlocked) {
                            handleUnlock()
                        }
                    }
                }
            }
        }
    }

    private fun setupLockScreen() {
        if (isUnlocked || isFinishing || shouldUnlock) {
            Log.d(TAG, "Not setting up lock screen - already unlocked or finishing")
            return
        }

        try {
            // Hide system UI using the modern approach
            // Use modern window insets API for edge-to-edge content
            val rootView = window.decorView.findViewById<View>(android.R.id.content)
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { v: View, windowInsets: WindowInsetsCompat ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                // Apply insets if needed while maintaining edge-to-edge appearance
                WindowInsetsCompat.CONSUMED
            }
            
            // Use the modern insetsController API
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())

            // For older API levels
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }

            // Lock to task (kiosk mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    // Check if device admin is active
                    if (!DeviceAdminReceiver.isAdminActive(this)) {
                        Log.e(TAG, "Cannot pin screen - device admin not active")
                        DeviceAdminReceiver.requestAdminPrivileges(this)
                        isPinned = false
                        return
                    }

                    // Try pinning with retry logic
                    var retryCount = 0
                    val maxRetries = 3
                    val retryDelay = 500L
                    
                    while (retryCount < maxRetries && !isPinned) {
                        Log.d(TAG, "Attempting to start lock task mode")
                        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        
                        // Check if already in lock task mode
                        if (activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                            Log.d(TAG, "Already in lock task mode")
                            isPinned = true
                            prefs.edit().putBoolean(KEY_WAS_LOCKED, true).apply()
                            break
                        }
                        
                        if (devicePolicyManager.isDeviceOwnerApp(packageName) || devicePolicyManager.isProfileOwnerApp(packageName)) {
                            Log.d(TAG, "App is device/profile owner, using setLockTaskFeatures")
                            try {
                                // Ensure we're whitelisted
                                devicePolicyManager.setLockTaskPackages(
                                    DeviceAdminReceiver.getComponentName(this),
                                    arrayOf(packageName)
                                )
                                
                                // Start lock task mode
                                startLockTask()
                                isPinned = true
                                prefs.edit().putBoolean(KEY_WAS_LOCKED, true).apply()
                                Log.d(TAG, "Successfully pinned screen on attempt ${retryCount + 1}")
                            } catch (e: Exception) {
                                retryCount++
                                Log.e(TAG, "Failed to start lock task mode (attempt $retryCount): ${e.message}")
                                if (retryCount < maxRetries) {
                                    Thread.sleep(retryDelay)
                                }
                            }
                        } else {
                            Log.w(TAG, "App is not device/profile owner, using fallback without setLockTaskFeatures")
                            try {
                                // Check again if we should be pinning
                                if (isFinishing || isUnlocking || isShowingAd) {
                                    Log.d(TAG, "Skipping pinning at last moment as state changed")
                                    break
                                }
                                
                                // Fallback: Use startLockTask() without features
                                startLockTask()
                                isPinned = true
                                prefs.edit().putBoolean(KEY_WAS_LOCKED, true).apply()
                                Log.d(TAG, "Successfully pinned screen on attempt ${retryCount + 1} using fallback")
                            } catch (e: Exception) {
                                retryCount++
                                Log.e(TAG, "Failed to start lock task mode (attempt $retryCount) using fallback: ${e.message}")
                                if (retryCount < maxRetries) {
                                    Thread.sleep(retryDelay)
                                }
                            }
                        }
                    }

                    if (!isPinned) {
                        Log.e(TAG, "Failed to pin screen after $maxRetries attempts")
                        enableFallbackMode()
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up lock screen", e)
                    enableFallbackMode()
                }
            }

            // Ensure we're in the foreground
            moveTaskToFront()
            hideSystemUI()
            
            Log.d(TAG, "Lock screen setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up lock screen", e)
            enableFallbackMode()
        }
    }

    private fun enableFallbackMode() {
        Log.d(TAG, "Enabling fallback mode")
        viewModel.enableFallbackMode()
        showErrorNotification("Using fallback mode - some features may be limited")

        // Register additional receivers to handle system events
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        // Start periodic checks to ensure we stay on top
        startContentChecking()
        moveTaskToFront()
        hideSystemUI()
    }

    private fun observeStates() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                Log.d(TAG, "UI state update: $state")
                
                if (state.shouldFinish && !isFinishing) {
                    Log.d(TAG, "UI state indicates activity should finish")
                    finishAndRemoveTask()
                    return@collect
                }

                // Update UI based on state
                binding.apply {
                    prayerName.text = state.prayerName
                    rakaatCounter.text = "${state.currentRakaat}/${state.rakaatCount}"
                    positionName.text = state.currentPosition
                    
                    // Show error if present
                    state.errorMessage?.let { error ->
                        Log.e(TAG, "Showing error: $error")
                        errorCard.visibility = View.VISIBLE
                        errorMessage.text = error
                        handler.postDelayed({
                            errorCard.visibility = View.GONE
                        }, 2000)
                    } ?: run {
                        errorCard.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun startStateMonitoring() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                handleStateUpdate(state)
            }
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state: LockScreenViewModel.UiState ->
                handleUiStateUpdate(state)
            }
        }
    }

    private fun handleUiStateUpdate(state: LockScreenViewModel.UiState) {
        binding.apply {
            // Update prayer info
            prayerName.text = state.prayerName
            rakaatCounter.text = "${state.currentRakaat}/${state.rakaatCount}"
            positionName.text = state.currentPosition

            // Handle error messages
            errorCard.apply {
                if (state.errorMessage != null) {
                    errorMessage.text = state.errorMessage
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }

            // Handle PIN error messages
            pinError.apply {
                text = state.errorMessage
                visibility = if (state.errorMessage != null) View.VISIBLE else View.GONE
            }

            // Update button states
            startPrayerButton.isEnabled = state.shouldShowStartButton && !state.isPrayerStarted
            
            // Enhanced PIN entry visibility control
            if (state.shouldShowPin) {
                // Show PIN entry with higher priority
                pinEntryCard.visibility = View.VISIBLE
                pinEntryCard.bringToFront()
                
                // Extra check after a delay to ensure the PIN entry card is still visible
                handler.postDelayed({
                    if (!isUnlocked && !isFinishing && state.shouldShowPin && !state.isPrayerStarted) {
                        if (pinEntryCard.visibility != View.VISIBLE) {
                            Log.d(TAG, "Force showing PIN entry that was incorrectly hidden")
                            pinEntryCard.visibility = View.VISIBLE
                            pinEntryCard.bringToFront()
                            pinEntryCard.requestLayout()
                        }
                    }
                }, 150)
            } else if (state.isPrayerStarted && viewFinder.visibility == View.VISIBLE) {
                // Hide only when camera is active
                pinEntryCard.visibility = View.GONE
            }
            
            // Handle completion states
            if (state.pinVerified || state.isPrayerComplete) {
                handlePrayerCompletion(state)
            }
        }
    }

    private fun handlePrayerCompletion(state: LockScreenViewModel.UiState) {
        binding.apply {
            val message = when {
                state.pinVerified -> "Unlocked by Parents"
                state.isPrayerComplete -> "Prayer Completed"
                else -> "Prayer Missed"
            }
            
            // Update UI with completion message
            errorCard.apply {
                errorMessage.text = message
                visibility = View.VISIBLE
            }
            
            // Hide other controls
            startPrayerButton.visibility = View.GONE
            pinEntryCard.visibility = View.GONE
        }
    }

    private fun handleUnlock() {
        if (isUnlocked || isUnlocking) return // Add isUnlocking check

        shouldUnlock = true
        isUnlocked = true
        isUnlocking = true // Set unlocking flag
        prefs.edit().putBoolean(KEY_WAS_LOCKED, false).apply()

        // Get the actual prayer name from the intent
        val actualPrayerName = intent.getStringExtra("prayer_name") ?: "Unknown"
        val isTestPrayer = actualPrayerName.equals("Test Prayer", ignoreCase = true)

        Log.d(TAG, "Unlocking for prayer: $actualPrayerName (isTestPrayer=$isTestPrayer)")

        // Update shared preferences to indicate unlock
        getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("pin_verified", true)
            .putBoolean("is_unlocked", true)
            .putBoolean("is_prayer_complete", viewModel.uiState.value.isPrayerComplete)
            .putString("active_prayer", actualPrayerName) // Store actual prayer name
            .putBoolean("is_test_prayer", isTestPrayer) // Mark if it's a test prayer
            .putLong("last_unlock_time", System.currentTimeMillis())
            .apply()

        // Always request the ad to be shown later by the next activity
        Log.d(TAG, "Requesting delayed ad show for next activity")
        lockScreenAds.requestDelayedAd()

        // Always unpin and finish after requesting the delayed ad
        unpinAndFinish()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        
        // Reset the static flag
        isInstanceActive = false
        
        handler.removeCallbacksAndMessages(null)
        
        if (stateCheckRunnable != null) {
            handler.removeCallbacks(stateCheckRunnable!!)
        }
        
        if (contentCheckHandler != null) {
            contentCheckHandler?.removeCallbacksAndMessages(null)
        }
        
        try {
            if (screenStateReceiver != null) {
                unregisterReceiver(screenStateReceiver)
                screenStateReceiver = null
            }
            
            if (homeButtonReceiver != null) {
                unregisterReceiver(homeButtonReceiver)
                homeButtonReceiver = null
            }
            
            sendBroadcast(Intent(ACTION_LOCKSCREEN_DESTROYED))
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}")
        }
        
        // First priority - ensure unpinning
        if (isPinned) {
            Log.d(TAG, "Activity being destroyed while pinned - ensuring unpin")
            unpinScreen()
        }
        
        super.onDestroy()
        
        // Get prayer name from intent
        val prayerName = intent.getStringExtra("prayer_name")
        val isTestPrayer = prayerName?.equals("Test Prayer", ignoreCase = true) == true
        
        // Notify PrayerReceiver that lock screen was destroyed
        val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
        val isLockScreenActive = prefs.getBoolean("lock_screen_active", false)
        
        if (isLockScreenActive) {
            if ((isTestPrayer || !isTestPrayer) && (shouldUnlock || isUnlocked)) {
                // For both test prayers and regular prayers, handle proper unlock the same way
                Log.d(TAG, "Clearing prayer state during proper unlock: $prayerName (isTest: $isTestPrayer)")
                prefs.edit().apply {
                    putBoolean("lock_screen_active", false)
                    putBoolean("is_test_prayer", isTestPrayer)
                    putBoolean("pin_verified", true)
                    putBoolean("is_unlocked", true)
                    putString("active_prayer", prayerName)
                    putBoolean("is_prayer_complete", true)
                    putLong("last_unlock_time", System.currentTimeMillis())
                    putLong("very_recent_unlock", System.currentTimeMillis())
                    putLong("lock_screen_last_destroyed", System.currentTimeMillis())
                    apply()
                }
            } else {
                // For improper dismissal (no proper unlock), don't mark as complete for any prayer type
                Log.d(TAG, "Lock screen improperly dismissed, preserving active state: $prayerName (isTest: $isTestPrayer)")
                prefs.edit().apply {
                    // Keep lock screen active - crucial for immediate reactivation
                    putBoolean("lock_screen_active", true)
                    putBoolean("is_test_prayer", isTestPrayer)
                    putBoolean("pin_verified", false)
                    putBoolean("is_unlocked", false)
                    putString("active_prayer", prayerName)
                    putBoolean("is_prayer_complete", false)
                    putLong("lock_screen_last_destroyed", System.currentTimeMillis())
                    // Set a very_recent_unlock flag but with a shorter time to prevent immediate 
                    // reactivation but still allow quick recovery if needed
                    putLong("very_recent_unlock", System.currentTimeMillis() - 5000)
                    apply()
                }
            }
            
            // Now the method exists in PrayerReceiver, we can call it
            PrayerReceiver().onLockScreenDestroyed(this)
        }
        
        // Remove timeouts and callbacks
        adLoadTimeout?.let { handler.removeCallbacks(it) }
        stateCheckRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        
        // Stop camera and clean up resources
        stopCamera()
        stopLockService()
        
        // Unregister receivers
        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
        
        homeButtonReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering home button receiver: ${e.message}")
            }
        }

        // For test prayers, be more selective about restarting to prevent infinite loops
        if (!shouldUnlock && !isUnlocked && !isShowingAd && !isFinishing && !isChangingConfigurations) {
            // Only restart if we need to and it's not a test prayer getting properly unlocked
            if (!isTestPrayer || (isTestPrayer && !isUnlocked && !shouldUnlock)) {
                // Add a small delay to prevent immediate restart
                handler.postDelayed({
                    if (!isUnlocked && !shouldUnlock) {  // Double check we're still not unlocked
                        showLockScreen(force = true)
                    }
                }, 500)
            }
        }
        
        contentCheckHandler?.removeCallbacksAndMessages(null)
        contentCheckHandler = null
        
        stopAdMonitoring()
        
        if (isUnlocked && !isShowingAd) {
            Log.d(TAG, "Ensuring screen is unpinned in onDestroy")
            unpinScreen()
        }
        
        // Set lock screen as inactive in shared prefs
        prefs.edit().putBoolean(KEY_LOCK_SCREEN_ACTIVE, false).apply()
        
        // Notify AdManager lock screen context is inactive first
        adManager.notifyLockScreenInactive()
        // Use our new method to handle lock screen deactivation
        adManager.onLockScreenDeactivated()
    }

    private fun startStateSync() {
        stateCheckRunnable = Runnable {
            if (!isUnlocked) {
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastStateSync >= STATE_SYNC_INTERVAL) {
                    lastStateSync = currentTime
                    syncActivityState()
                }
                if (currentTime - lastServiceCheck >= SERVICE_CHECK_INTERVAL) {
                    lastServiceCheck = currentTime
                    startLockService()
                }
                handler.postDelayed(stateCheckRunnable!!, STATE_SYNC_INTERVAL)
            }
        }
        handler.post(stateCheckRunnable!!)
    }

    private fun syncActivityState() {
        if (!isUnlocked) {
            // Ensure camera state is consistent
            if (binding.viewFinder.visibility == View.VISIBLE) {
                val currentTime = SystemClock.elapsedRealtime()
                if (!viewModel.uiState.value.isPrayerStarted && 
                    currentTime - lastCameraReset >= MIN_STATE_RESET_INTERVAL) {
                    lastCameraReset = currentTime
                    viewModel.stopPrayer()
                    stopCamera()
                }
            }

            // Ensure PIN state is consistent
            if (binding.pinEntryCard.visibility == View.VISIBLE) {
                val currentTime = SystemClock.elapsedRealtime()
                if (!viewModel.uiState.value.pinEnabled && 
                    currentTime - lastPinReset >= MIN_STATE_RESET_INTERVAL) {
                    lastPinReset = currentTime
                    viewModel.resetPinState()
                }
            }

            // Ensure we're on top
            moveTaskToFront()
            hideSystemUI()
        }
    }

    private fun registerScreenReceiver() {
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isUnlocked) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            val currentTime = SystemClock.elapsedRealtime()
                            if (currentTime - lastScreenOffTime >= MIN_SCREEN_EVENT_INTERVAL) {
                                lastScreenOffTime = currentTime
                                handleScreenOff()
                            }
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            val currentTime = SystemClock.elapsedRealtime()
                            if (currentTime - lastScreenOnTime >= MIN_SCREEN_EVENT_INTERVAL) {
                                lastScreenOnTime = currentTime
                                handleScreenOn()
                            }
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            handleUserPresent()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenStateReceiver, filter)
        }
    }

    private fun handleScreenOff() {
        if (!isUnlocked) {
            // Stop camera if active
            if (binding.viewFinder.visibility == View.VISIBLE) {
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastCameraReset >= MIN_STATE_RESET_INTERVAL) {
                    lastCameraReset = currentTime
                    viewModel.stopPrayer()
                    stopCamera()
                }
            }
            moveTaskToFront()
        }
    }

    private fun handleScreenOn() {
        if (!isUnlocked) {
            // Reset camera state if needed
            if (binding.viewFinder.visibility == View.VISIBLE) {
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastCameraReset >= MIN_STATE_RESET_INTERVAL) {
                    lastCameraReset = currentTime
                    viewModel.stopPrayer()
                    stopCamera()
                }
            }

            // Reset PIN state if needed
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastPinReset >= MIN_STATE_RESET_INTERVAL) {
                lastPinReset = currentTime
                viewModel.resetPinState()
            }

            moveTaskToFront()
            hideSystemUI()
            startLockService()
        }
    }

    private fun setupBackButton() {
        binding.backButton.setOnClickListener {
            handleBackPress()
        }
    }

    private fun handleBackPress() {
        if (pinVerified || isUnlocking) {
            completeAndFinish()
        } else if (viewModel.uiState.value.isPrayerStarted) {
            // Reset prayer tracking when back is pressed during prayer
            stopCamera()
            viewModel.resetPrayerTracking()
            
            // Switch UI back to initial state
            hideCameraView()
            
            // Explicitly show the PIN entry
            showPinEntryCard()
            
            // Add a slight delay before showing start prayer button to ensure smooth transition
            handler.postDelayed({
                binding.startPrayerButton.visibility = View.VISIBLE
                binding.startPrayerButton.isEnabled = true
                binding.backButton.visibility = View.GONE
                
                // Explicitly force showing the PIN entry card with higher priority
                binding.pinEntryCard.visibility = View.VISIBLE
                binding.pinEntryCard.bringToFront()
                binding.pinEntryCard.requestLayout()
                
                // Try to help the UI update by requesting focus and invalidating the layout
                binding.pinEntryCard.requestFocus()
                binding.root.invalidate()
                
                // Log the state to help diagnose
                Log.d(TAG, "After back press: pinEntryCard visibility set to View.VISIBLE")
                
                // Call showPinEntryCard again after the delay for good measure
                showPinEntryCard()
            }, 300)
        } else {
            // Notify user they need to complete the prayer
            hideSystemUI()
            
            // Force app to foreground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            }
        }
    }
    
    private fun showPinEntryCard() {
        // Always set to visible regardless of current state
        binding.pinEntryCard.visibility = View.VISIBLE
        binding.pinEntryCard.bringToFront()
        binding.pinEntryCard.elevation = 20f  // Higher elevation to ensure it's on top
        binding.pinDisplay.text = ""
        binding.pinError.visibility = View.GONE
        
        // Reset PIN attempts counter for new entry session
        prefs.edit().putInt("pin_attempts", 0).apply()
        
        // Focus on PIN entry
        binding.pinEntryCard.requestFocus()
        
        // Force a layout refresh
        binding.root.invalidate()
        binding.pinEntryCard.requestLayout()
        
        // Also make sure to explicitly set the shouldShowPin flag in the ViewModel
        viewModel.resetPinState()
        
        Log.d(TAG, "showPinEntryCard: PIN entry forcefully shown")
    }

    private fun setupCameraPreview() {
        binding.viewFinder.apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        
        // Initialize camera in ViewModel
        viewModel.initializeCamera(this, binding.viewFinder)
        
        binding.startPrayerButton.setOnClickListener {
            // First make sure any existing prayer/camera session is fully closed
            stopCamera()
            
            // Add a small delay to ensure resources are released before starting again
            handler.postDelayed({
                // Show camera view
                showCameraView()
                
                // Start prayer
                viewModel.startPrayer()
            }, 300)
        }
    }

    private fun setupPrayerUI() {
        val pinBuilder = StringBuilder()
        var lastTouchTime = 0L
        val touchThreshold = 300L // Debounce threshold

        binding.apply {
            // Make Clear button always prominent and accessible
            btnClear.apply {
                isEnabled = true
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                alpha = 1.0f
                elevation = 16f  // Increased elevation for more prominence
                stateListAnimator = null  // Prevent state animations
                setBackgroundResource(R.drawable.button_clear_background)  // Custom background
                setTextColor(getColor(R.color.clear_button_text))  // Custom text color
                
                // Add haptic feedback
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        }
                    }
                    false  // Allow click event to be processed
                }

                // Improve accessibility
                contentDescription = getString(R.string.clear_button_description)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                
                setOnClickListener {
                    val currentTime = SystemClock.elapsedRealtime()
                    if (currentTime - lastTouchTime < touchThreshold) {
                        return@setOnClickListener
                    }
                    lastTouchTime = currentTime
                    
                    if (!isUnlocked) {
                        it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        pinBuilder.clear()
                        updatePinDisplay("")
                        viewModel.resetPinAttempts()  // Reset any failed attempts
                        binding.pinError.visibility = View.GONE  // Clear any error message
                    }
                }
            }

            btn0.setOnClickListener { _ ->
                handlePinInput('0', pinBuilder)
            }

            btn1.setOnClickListener { _ ->
                handlePinInput('1', pinBuilder)
            }

            btn2.setOnClickListener { _ ->
                handlePinInput('2', pinBuilder)
            }

            btn3.setOnClickListener { _ ->
                handlePinInput('3', pinBuilder)
            }

            btn4.setOnClickListener { _ ->
                handlePinInput('4', pinBuilder)
            }

            btn5.setOnClickListener { _ ->
                handlePinInput('5', pinBuilder)
            }

            btn6.setOnClickListener { _ ->
                handlePinInput('6', pinBuilder)
            }

            btn7.setOnClickListener { _ ->
                handlePinInput('7', pinBuilder)
            }

            btn8.setOnClickListener { _ ->
                handlePinInput('8', pinBuilder)
            }

            btn9.setOnClickListener { _ ->
                handlePinInput('9', pinBuilder)
            }

            btnDelete.setOnClickListener { _ ->
                if (!isUnlocked && pinBuilder.isNotEmpty()) {
                    pinBuilder.deleteCharAt(pinBuilder.length - 1)
                    updatePinDisplay(pinBuilder.toString())
                }
            }

            // Observe UI state
            lifecycleScope.launch {
                viewModel.uiState.collectLatest { state ->
                    // Update prayer info
                    prayerName.text = state.prayerName
                    rakaatCounter.text = "${state.currentRakaat}/${state.rakaatCount}"
                    positionName.text = state.currentPosition

                    if (state.errorMessage != null) {
                        errorCard.visibility = View.VISIBLE
                        errorMessage.text = state.errorMessage
                    } else {
                        errorCard.visibility = View.GONE
                    }

                    viewFinder.visibility = if (state.isPrayerStarted) View.VISIBLE else View.GONE
                    
                    // Ensure PIN entry card is always visible when needed
                    if (state.shouldShowPin) {
                        pinEntryCard.visibility = View.VISIBLE
                        pinEntryCard.bringToFront()  // Make sure it's on top
                        pinEntryCard.requestLayout()
                    } else {
                        pinEntryCard.visibility = View.GONE
                    }

                    // Update button states but keep Clear button always enabled
                    val buttonsEnabled = !isUnlocked && state.pinEnabled && !state.isLockedOut
                    arrayOf(btn0, btn1, btn2, btn3, btn4, btn5, btn6, btn7, btn8, btn9, btnDelete).forEach { button ->
                        button.isEnabled = buttonsEnabled
                        button.alpha = if (buttonsEnabled) 1.0f else 0.5f
                    }

                    // Clear button stays enabled and visible
                    btnClear.isEnabled = true
                    btnClear.alpha = 1.0f

                    // Update start button visibility
                    startPrayerButton.visibility = if (state.shouldShowStartButton) View.VISIBLE else View.GONE

                    // Handle auto-unlock if needed
                    if (state.shouldAutoUnlock && !isUnlocked) {
                        handleUnlock()
                    }
                }
            }
        }
    }

    private fun handlePinInput(digit: Char, pinBuilder: StringBuilder) {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastInputTime < MIN_INPUT_INTERVAL) {
            return // Ignore rapid inputs
        }
        lastInputTime = currentTime

        if (pinBuilder.length < 4) {
            pinBuilder.append(digit)
            updatePinDisplay(pinBuilder.toString())
            binding.pinEntryCard.requestFocus() // Keep focus on PIN entry
            
            if (pinBuilder.length == 4) {
                viewModel.verifyPin(pinBuilder.toString())
                if (viewModel.uiState.value.shouldAutoUnlock) {
                    handleUnlock()
                }
                pinBuilder.clear()
                updatePinDisplay("")
            }
        }
    }

    private fun updatePinDisplay(pin: String) {
        binding.pinDisplay.text = "•".repeat(pin.length).padEnd(4, '○')
    }

    private fun stopCamera() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                viewModel.stopPoseDetection()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping camera: ${e.message}")
            }
        }
    }

    private fun moveTaskToFront() {
        if (isUnlocked || isFinishing || shouldUnlock) return
        
        try {
            Log.d(TAG, "Moving task to front")
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            
            // Re-apply window flags using modern APIs
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        (getSystemService(KeyguardManager::class.java))?.requestDismissKeyguard(this, null)
                    }
                }
            }
            
            hideSystemUI()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move task to front", e)
        }
    }

    private fun hideSystemUI() {
        // Use modern window insets API for edge-to-edge content
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v: View, windowInsets: WindowInsetsCompat ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply insets if needed while maintaining edge-to-edge appearance
            WindowInsetsCompat.CONSUMED
        }
        
        // Use the modern insetsController API
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // For older API levels
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "onWindowFocusChanged: $hasFocus")
        
        if (hasFocus && !isUnlocked && !isFinishing) {
            hideSystemUI()
            lastFocusTime = SystemClock.elapsedRealtime()
        } else if (!hasFocus && !isUnlocked && !isFinishing && !isShowingAd) {
            // Lost focus while we should be locked - potential bypass attempt
            Log.w(TAG, "Lost window focus while locked - potential bypass attempt")
            
            // Record this event
            val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("last_focus_loss", System.currentTimeMillis())
                .putString("bypass_location", "focus_loss")
                .apply()
            
            // Try to come back to foreground after a short delay
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    Log.d(TAG, "Attempting recovery from focus loss")
                    moveTaskToFront()
                }
            }, 300)
        }
    }

    private fun showLockScreen(force: Boolean = false) {
        if (!isUnlocked || force) {
            // Check if the activity is already in the foreground
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val tasks = activityManager.getRunningTasks(1)
            if (tasks.isNotEmpty() && tasks[0].topActivity?.className == this::class.java.name) {
                Log.d(TAG, "LockScreenActivity is already in foreground, skipping relaunch")
                setupLockScreen()
                return
            }
            
            val lockIntent = Intent(this, LockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(lockIntent)
            setupLockScreen()
        }
    }

    /**
     * Handles new intents when the activity is already running
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        
        // Additional intent handling logic
        // ...
    }

    override fun onStop() {
        super.onStop()
        
        // Only force unpin if we're actually stopping - not if properly unlocked
        val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
        
        if (!isUnlocked && !shouldUnlock && !isShowingAd && !isFinishing) {
            Log.w(TAG, "Activity stopped while still pinned - potential bypass in onStop")
            
            // Record this as a potential bypass with location information
            prefs.edit()
                .putLong("last_bypass_attempt", System.currentTimeMillis())
                .putString("bypass_location", "onStop")
                .putLong("lock_screen_last_stopped", System.currentTimeMillis())
                .apply()
            
            // Try to come back to foreground after a short delay
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    Log.d(TAG, "Attempting recovery from onStop bypass")
                    moveTaskToFront()
                    setupLockScreen()
                }
            }, 200)
        } else {
            // Normal stop due to unlock or completion
            Log.d(TAG, "Normal activity stop - unpinning screen")
            unpinScreen()
            prefs.edit().putLong("lock_screen_last_stopped", System.currentTimeMillis()).apply()
        }
    }
    
    /**
     * Attempt to detect if the recent apps button was pressed
     * This is a heuristic - not 100% reliable
     */
    private fun isRecentButtonPressed(): Boolean {
        try {
            // If the activity is finishing, this is probably not recent button
            if (isFinishing) return false
            
            // Check if activity is in the background but not finishing
            // This is a common pattern when recent apps is pressed
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appTasks = activityManager.appTasks
            
            if (appTasks.isNotEmpty()) {
                val taskInfo = appTasks[0].taskInfo
                // Recent button typically moves task to background but keeps it in recents
                return taskInfo != null && !taskInfo.isRunning && !isFinishing
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting recent button press: ${e.message}")
            return false
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // This is called when the home button is pressed
        handleHomePressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when {
            !isUnlocked && keyCode == KeyEvent.KEYCODE_BACK -> {
                handleBackPress()
                true
            }
            keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Stop Adhan when volume buttons are pressed
                val intent = Intent(this, PrayerReceiver::class.java).apply {
                    action = "com.viperdam.kidsprayer.STOP_ADHAN_ACTION"
                }
                sendBroadcast(intent)
                true
            }
            !isUnlocked -> {
                moveTaskToFront()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun schedulePeriodicChecks() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!canFinish && !isUnlocked && !isFinishing) {
                    // Only move task to front if we've lost focus
                    if (!window.decorView.hasWindowFocus()) {
                        moveTaskToFront()
                        hideSystemUI()
                    }
                    handler.postDelayed(this, FOCUS_CHECK_INTERVAL)
                }
            }
        }, FOCUS_CHECK_INTERVAL)
    }

    private fun startContentChecking() {
        Log.d(TAG, "Starting content checking mechanism")
        contentCheckHandler = Handler(Looper.getMainLooper())
        scheduleContentCheck()
    }

    private fun scheduleContentCheck() {
        if (isUnlocked || isFinishing || shouldUnlock) return
        
        contentCheckHandler?.postDelayed({
            if (!isFinishing && !isUnlocked) {
                checkScreenContent()
                // Only schedule next check if we're still active
                if (!isFinishing && !isUnlocked) {
                    scheduleContentCheck()
                }
            }
        }, CONTENT_CHECK_INTERVAL)
    }

    private fun checkScreenContent() {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastContentCheck < CONTENT_CHECK_THRESHOLD) {
            return
        }
        lastContentCheck = currentTime

        // Only check if we're in the foreground and have window focus
        if (!window.decorView.hasWindowFocus()) {
            return
        }

        val hasVisibleContent = binding.run {
            val prayerInfoVisible = prayerInfoCard.visibility == View.VISIBLE && 
                                  prayerName.text?.isNotEmpty() == true
            val cameraVisible = cameraContainer.visibility == View.VISIBLE && 
                              viewFinder.visibility == View.VISIBLE
            val pinEntryVisible = pinEntryCard.visibility == View.VISIBLE

            // Only log in debug builds using standard logging level
            Log.v(TAG, "Content check - Prayer: $prayerInfoVisible, Camera: $cameraVisible, Pin: $pinEntryVisible")
            
            prayerInfoVisible || cameraVisible || pinEntryVisible
        }

        if (!hasVisibleContent && !isUnlocked) {
            Log.w(TAG, "Empty screen detected! Retry count: $emptyScreenRetryCount")
            handleEmptyScreen()
        } else {
            // Reset retry count only if we have content
            emptyScreenRetryCount = 0
        }
    }

    private fun handleEmptyScreen() {
        if (emptyScreenRetryCount >= MAX_EMPTY_SCREEN_RETRIES) {
            Log.e(TAG, "Max empty screen retries reached. Attempting recovery...")
            recoverFromEmptyScreen()
            return
        }

        emptyScreenRetryCount++
        
        // Force UI refresh
        binding.root.post {
            Log.d(TAG, "Attempting to refresh UI components")
            binding.prayerInfoCard.visibility = View.VISIBLE
            binding.cameraContainer.visibility = View.VISIBLE
            binding.pinEntryCard.visibility = View.VISIBLE
            
            // Re-initialize prayer info with fallback to default name
            val defaultPrayerName = getString(R.string.prayer_time_notification)
            val prayerName = intent.getStringExtra("prayer_name") ?: defaultPrayerName
            val rakaatCount = intent.getIntExtra("rakaat_count", 4)
            
            // Since initializePrayer expects a non-null String, we're safe here as we have a default
            viewModel.initializePrayer(prayerName, rakaatCount)
        }
    }

    private fun recoverFromEmptyScreen() {
        Log.e(TAG, "Initiating emergency recovery procedure")
        
        // Save current state
        val prayerName = intent.getStringExtra("prayer_name")
        val rakaatCount = intent.getIntExtra("rakaat_count", 4)
        
        // Restart activity
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("prayer_name", prayerName)
            putExtra("rakaat_count", rakaatCount)
            putExtra("is_recovery", true)
        }
        
        startActivity(intent)
        finish()
    }

    override fun finish() {
        try {
            if (!isFinishing) {
                Log.d(TAG, "Finishing LockScreenActivity")
                
                setShowWhenLocked(false)
                setTurnScreenOn(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        (getSystemService(KeyguardManager::class.java))?.requestDismissKeyguard(this, null)
                    }
                }
                
                keyguardManager.requestDismissKeyguard(this, null)
                
                // Clear remaining window flags
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )

                // Reset window layout params
                window.attributes = window.attributes.apply {
                    flags = flags and (
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    ).inv()
                }

                // Ensure system bars are shown before finishing
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    show(WindowInsetsCompat.Type.systemBars())
                    show(WindowInsetsCompat.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                }

                stopCamera()
                stopLockService()
                
                // Unregister receivers
                screenStateReceiver?.let {
                    try {
                        unregisterReceiver(it)
                        screenStateReceiver = null
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unregistering receiver: ${e.message}")
                    }
                }
                
                homeButtonReceiver?.let {
                    try {
                        unregisterReceiver(it)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unregistering home button receiver: ${e.message}")
                    }
                }

                // Get prayer name from intent to check if this is a test prayer
                val prayerName = intent.getStringExtra("prayer_name")
                val isTestPrayer = prayerName?.equals("Test Prayer", ignoreCase = true) == true

                // For test prayers, be more selective about restarting to prevent infinite loops
                if (!shouldUnlock && !isUnlocked && !isShowingAd && !isFinishing && !isChangingConfigurations) {
                    // Only restart if we need to and it's not a test prayer getting properly unlocked
                    if (!isTestPrayer || (isTestPrayer && !isUnlocked && !shouldUnlock)) {
                        // Add a small delay to prevent immediate restart
                        handler.postDelayed({
                            if (!isUnlocked && !shouldUnlock) {  // Double check we're still not unlocked
                                showLockScreen(force = true)
                            }
                        }, 500)
                    }
                }
                
                contentCheckHandler?.removeCallbacksAndMessages(null)
                contentCheckHandler = null
                
                setResult(Activity.RESULT_OK)
                
                // Navigate to main activity with clear flags
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                
                // Start activity immediately and finish
                startActivity(mainIntent)
                finishAndRemoveTask()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in finish(): ${e.message}")
        } finally {
            super.finish()
            // Instead of overridePendingTransition, we'll use the Activity's modern animation API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    private fun startLockService() {
        if (!isUnlocked) {
            try {
                val serviceIntent = Intent(this, LockScreenService::class.java).apply {
                    action = "ACTION_START_LOCK"
                    putExtra("prayer_name", intent.getStringExtra("prayer_name"))
                    putExtra("rakaat_count", intent.getIntExtra("rakaat_count", 4))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting lock service: ${e.message}")
            }
        }
    }

    private fun stopLockService() {
        try {
            val serviceIntent = Intent(this, LockScreenService::class.java)
            stopService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping lock service: ${e.message}")
        }
    }

    private fun unregisterScreenReceiver() {
        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
                screenStateReceiver = null
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen receiver: ${e.message}")
            }
        }
    }

    private fun handleAdClick() {
        shouldUnlock = true
        isUnlocked = true
        unpinAndFinish()
    }

    private fun stopAdMonitoring() {
        adCheckRunnable?.let { handler.removeCallbacks(it) }
        adCheckRunnable = null
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUiState(state)
            }
        }
    }

    private fun updateUiState(state: LockScreenViewModel.UiState) {
        if (isUnlocked || shouldUnlock) return

        // Update prayer info
        binding.prayerName.text = state.prayerName
        binding.rakaatCounter.text = "${state.currentRakaat}/${state.rakaatCount}"
        binding.positionName.text = state.currentPosition

        // Update error message
        if (state.errorMessage != null) {
            binding.errorCard.visibility = View.VISIBLE
            binding.errorMessage.text = state.errorMessage
            handler.postDelayed({
                binding.errorCard.visibility = View.GONE
            }, 2000)
        } else {
            binding.errorCard.visibility = View.GONE
        }

        // Handle prayer completion
        if (state.isPrayerComplete) {
            handleUnlock()
        }

        // Ensure we're on top and UI is hidden
        if (!isUnlocked && !shouldUnlock) {
            moveTaskToFront()
            hideSystemUI()
        }
    }

    private fun handleUserPresent() {
        if (isUnlocked || shouldUnlock) return

        if (!isPinned) {
            setupLockScreen()
        }
    }

    private fun handleHomePressed() {
        if (isUnlocked || shouldUnlock) return

        if (!isPinned) {
            setupLockScreen()
        }
        moveTaskToFront()
    }

    private fun handlePowerPressed() {
        if (isUnlocked || shouldUnlock) return

        if (!isPinned) {
            setupLockScreen()
        }
    }

    private fun handleStateUpdate(state: LockScreenViewModel.UiState) {
        if (isUnlocked || shouldUnlock) return

        binding.apply {
            if (state.isLoading) {
                loadingIndicator.visibility = View.VISIBLE
            } else {
                loadingIndicator.visibility = View.GONE
            }

            if (state.shouldFinish && !isFinishing) {
                isUnlocked = true
                if (isShowingAd) {
                    // Wait for ad to finish
                    return
                }
                finish()
            }

            // Update prayer info
            prayerName.text = state.prayerName
            rakaatCounter.text = "${state.currentRakaat}/${state.rakaatCount}"
            positionName.text = state.currentPosition

            // Handle error messages
            if (state.errorMessage != null) {
                errorCard.visibility = View.VISIBLE
                errorMessage.text = state.errorMessage
                handler.postDelayed({
                    errorCard.visibility = View.GONE
                }, 2000)
            } else {
                errorCard.visibility = View.GONE
            }

            // Handle prayer completion
            if (state.isPrayerComplete) {
                handleUnlock()
            }
        }

        // Ensure we're on top and UI is hidden
        if (!isUnlocked && !shouldUnlock) {
            moveTaskToFront()
            hideSystemUI()
        }
    }

    private fun updateUiForState(state: LockScreenViewModel.UiState) {
        binding.apply {
            prayerName.text = state.prayerName
            rakaatCounter.text = "${state.currentRakaat}/${state.rakaatCount}"
            positionName.text = state.currentPosition
            
            // Show error if present
            state.errorMessage?.let { error ->
                errorCard.visibility = View.VISIBLE
                errorMessage.text = error
                handler.postDelayed({
                    errorCard.visibility = View.GONE
                }, 2000)
            } ?: run {
                errorCard.visibility = View.GONE
            }
        }

        // Keep screen on top and system UI hidden
        moveTaskToFront()
        hideSystemUI()
    }

    private fun unpinScreen() {
        try {
            if (isPinned) {
                Log.d(TAG, "Unpinning screen from unpinScreen()")
                // Use robust retry logic for unpinning
                var retryCount = 0
                val maxRetries = 3
                
                while (retryCount < maxRetries && isPinned) {
                    try {
                        stopLockTask()
                        
                        // Verify unpinning worked by checking the lock task mode state
                        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        isPinned = am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE
                        
                        if (!isPinned) {
                            // Successfully unpinned
                            Log.d(TAG, "Successfully unpinned screen")
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unpinning: ${e.message}")
                    }
                    
                    retryCount++
                    SystemClock.sleep(100)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in unpinScreen: ${e.message}")
        }
    }

    private fun unpinAndFinish() {
        try {
            Log.d(TAG, "unpinAndFinish called")
            
            // Always set the flag to show ads on next activity resume
            val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("pin_verified", true)
                .putBoolean("should_show_ad", true) // Mark that ad should be shown
                .putLong("last_unlock_time", System.currentTimeMillis())
                .putLong("very_recent_unlock", System.currentTimeMillis())  // Add this line
                .apply()
            
            // Set lock screen as inactive in shared prefs
            getSharedPreferences(LOCK_SCREEN_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LOCK_SCREEN_ACTIVE, false)
                .apply()
            
            // Use our new method to handle lock screen deactivation
            adManager.onLockScreenDeactivated()
            
            // Always request delayed ad regardless of availability
            lockScreenAds.requestDelayedAd()
            
            // Unpin the screen
            unpinScreen()
            
            // Make sure we're really unpinned
            if (isPinned) {
                Log.w(TAG, "Still pinned after unpinScreen() call, trying again...")
                try {
                    stopLockTask()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in final unpin attempt: ${e.message}")
                }
            }
            
            // Regardless of pin state, proceed with finishing
            canFinish = true
            isUnlocked = true
            
            // Start the main activity if needed
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // Add flags to indicate ad should be shown
            intent.putExtra("show_unlock_ad", true)
            intent.putExtra("unlock_time", System.currentTimeMillis())
            
            startActivity(intent)
            
            // Then finish this activity
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error in unpinAndFinish: ${e.message}")
            // Try to finish anyway
            finish()
        }
    }

    private fun setupAdCallbacks() {
        // Updated setAdClickHandler to only log
        adManager.setAdClickHandler {
            // Ad was clicked, log it. Navigation is handled by AdMob SDK.
            // The actual unlock/finish should only happen on reward or explicit dismiss.
            Log.d(TAG, "Ad Click Handler invoked (via setAdClickHandler)")
            // Removed unpinAndFinish() from here
        }
        
        // Changed to setRewardedAdCallback and implement RewardedAdCallback
        adManager.setRewardedAdCallback(object : AdManager.RewardedAdCallback {
            override fun onAdLoaded() {
                Log.d(TAG, "Rewarded Ad Loaded (Callback)")
            }
            
            override fun onAdFailedToLoad(errorMessage: String?) {
                Log.d(TAG, "Rewarded Ad Failed to Load (Callback): $errorMessage")
            }
            
            override fun onAdDismissed() {
                // This is called when the ad is dismissed, regardless of reward.
                // The reward is handled by OnUserEarnedRewardListener in AdManager now.
                // LockScreenAds handles the finish logic internally via its callback.
                Log.d(TAG, "Rewarded Ad Dismissed (Callback) - Handled by LockScreenAds")
                isShowingAd = false
                // Reset ad display flag
                val prefsEditor = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("is_displaying_ad", false)
                    .apply()

                // LockScreenAds callback will trigger unpinAndFinish if needed
            }

            override fun onAdShown() {
                Log.d(TAG, "Rewarded Ad Shown (Callback)")
                isShowingAd = true
            }

            override fun onAdFailedToShow(errorMessage: String?) {
                Log.w(TAG, "Rewarded Ad Failed To Show (Callback): $errorMessage")
                isShowingAd = false
                 // Reset ad display flag
                val prefsEditor = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("is_displaying_ad", false)
                    .apply()

                // If ad fails to show, LockScreenAds callback will trigger unpinAndFinish
                Log.d(TAG, "Ad failed to show - Handled by LockScreenAds")

                // Keep retry logic in AdManager
            }

            override fun onAdClicked() {
                // Click handling: Usually just log, as navigation is automatic.
                // Unlock should only happen on reward.
                Log.d(TAG, "Rewarded Ad Clicked (Callback)")
                isShowingAd = true // Keep showing ad flag true until dismissed or reward
                // Ad click doesn't trigger unlock directly
            }
            
            override fun onUserEarnedReward(amount: Int) {
                Log.d(TAG, "User earned reward: $amount")
                // Handle reward logic
            }
        })
    }

    private fun getCurrentPrayerTime(): String {
        try {
            val prayerName = intent.getStringExtra("prayer_name") ?: return "Prayer Time"
            val prayerTime = intent.getLongExtra("prayer_time", 0L)
            
            if (prayerTime <= 0) {
                Log.w(TAG, "Invalid prayer time for $prayerName: $prayerTime")
                return "$prayerName"
            }
            
            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val formattedTime = timeFormat.format(Date(prayerTime))
            return "$prayerName at $formattedTime"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting prayer time", e)
            return "Prayer Time"
        }
    }

    private fun unlockAndFinish() {
        unpinScreen() // Assuming unpinScreen() correctly unpins the device
        
        // Set the very_recent_unlock flag to prevent reactivation
        getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE).edit()
            .putLong("very_recent_unlock", System.currentTimeMillis())
            .putBoolean("is_unlocked", true)
            .apply()
        
        // Set lock screen as inactive in shared prefs
        getSharedPreferences(LOCK_SCREEN_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOCK_SCREEN_ACTIVE, false)
            .apply()
        
        // Use our new method to handle lock screen deactivation
        adManager.onLockScreenDeactivated()
            
        finish()
    }

    private fun showErrorNotification(message: String) {
        binding.errorCard.visibility = View.VISIBLE
        binding.errorMessage.text = message
        handler.postDelayed({
            binding.errorCard.visibility = View.GONE
        }, 5000)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen turned off")
                    // Screen turned off, ensure we're on top when it comes back
                    handler.postDelayed({
                        moveTaskToFront()
                        hideSystemUI()
                    }, 500)
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen turned on")
                    moveTaskToFront()
                    hideSystemUI()
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User present")
                    moveTaskToFront()
                    hideSystemUI()
                }
            }
        }
    }

    private fun showCameraView() {
        binding.cameraContainer.visibility = View.VISIBLE
        binding.viewFinder.visibility = View.VISIBLE
        binding.pinEntryCard.visibility = View.GONE
        binding.startPrayerButton.visibility = View.GONE
        binding.backButton.visibility = View.VISIBLE
        binding.prayerInfoCard.visibility = View.VISIBLE
    }
    
    private fun hideCameraView() {
        binding.cameraContainer.visibility = View.GONE
        binding.viewFinder.visibility = View.GONE
        binding.backButton.visibility = View.GONE
        binding.startPrayerButton.visibility = View.VISIBLE
        binding.startPrayerButton.isEnabled = true
        binding.prayerInfoCard.visibility = View.VISIBLE
        binding.pinEntryCard.visibility = View.VISIBLE  // Explicitly make the PIN entry visible
        
        // Enhanced PIN visibility handling
        binding.pinEntryCard.bringToFront()
        binding.pinEntryCard.elevation = 15f
        binding.pinEntryCard.requestFocus()
        
        // Force a layout refresh
        binding.root.invalidate()
        
        // Log for debugging
        Log.d(TAG, "hideCameraView called: PIN entry card visibility set to View.VISIBLE")
    }

    private fun setupStartPrayerButton() {
        binding.startPrayerButton.setOnClickListener {
            // First make sure any existing prayer/camera session is fully closed
            stopCamera()
            
            // Add a small delay to ensure resources are released before starting again
            handler.postDelayed({
                // Show camera view
                showCameraView()
                
                // Start prayer
                viewModel.startPrayer()
            }, 300)
        }
    }

    override fun onPause() {
        super.onPause()

        val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)

        // Only log potential bypass if we aren't already unlocked, displaying an ad, or finishing
        if (!isUnlocked && !isShowingAd && !isFinishing && !shouldUnlock) {
            Log.d(TAG, "Activity paused while pinned - might be abnormal exit, ensuring unpin")

            // Before assuming bypass, check for legitimate unlock conditions
            val isPinVerified = prefs.getBoolean("pin_verified", false)
            val isPrayerComplete = prefs.getBoolean("is_prayer_complete", false)

            if (isPinVerified || isPrayerComplete || shouldUnlock) {
                // This is a legitimate dismissal
                Log.d(TAG, "Legitimate dismissal detected in onPause - not a bypass")
                prefs.edit()
                    .putBoolean("legitimate_dismissal", true)
                    .putLong("last_unlock_time", System.currentTimeMillis())
                    .apply()
            } else {
                Log.w(TAG, "Potential bypass detected in onPause - not properly dismissed")

                // Record this as a potential bypass with location information
                prefs.edit()
                    .putLong("last_bypass_attempt", System.currentTimeMillis())
                    .putString("bypass_location", "onPause")
                    .apply()
            }
        }

        Log.d(TAG, "LockScreenActivity onPause")
    }

    private fun validatePrayerData(): Boolean {
        val prayerName = intent.getStringExtra("prayer_name")
        val rakaatCount = intent.getIntExtra("rakaat_count", 0)
        val prayerTime = intent.getLongExtra("prayer_time", 0)
        
        // Set isTestPrayer here too
        isTestPrayer = prayerName?.equals("Test Prayer", ignoreCase = true) == true
        
        Log.d(TAG, "Prayer validation - name: '$prayerName', time: $prayerTime, rakaat: $rakaatCount")
        
        if (prayerName.isNullOrEmpty() && !isTestPrayer) {
            Log.w(TAG, "Invalid prayer data received (null prayer name), finishing activity")
            
            // Set flags to prevent immediate relaunch by monitor service
            val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("lock_screen_active", false)
                .putBoolean("invalid_prayer_data", true)  // New flag to indicate invalid data caused exit
                .putLong("last_invalid_prayer_time", System.currentTimeMillis())  // Track when invalid prayer occurred
                .apply()
                
            finish()
            return false
        }
        
        // Add explicit return true at the end
        return true
    }

    private fun pinScreen() {
        try {
            if (!isPinned) {
                Log.d(TAG, "Attempting to pin screen")
                
                // Check if we've shown the pinning dialog recently (within last 5 minutes)
                val lastPinDialogTime = prefs.getLong("last_pin_dialog_time", 0)
                val shouldSkipPinningDialog = System.currentTimeMillis() - lastPinDialogTime < 5 * 60 * 1000
                
                // Only try to pin if we're supposed to show pin UI or if enough time has passed
                if (!shouldSkipPinningDialog) {
                    // Mark that we've shown the dialog
                    prefs.edit().putLong("last_pin_dialog_time", System.currentTimeMillis()).apply()
                }
                
                var retryCount = 0
                val maxRetries = 3
                val retryDelay = 100L // ms
                
                while (retryCount < maxRetries && !isPinned) {
                    // Check if we're in a state where we shouldn't pin (finishing or unlocking)
                    if (isFinishing || isUnlocking || isShowingAd) {
                        Log.d(TAG, "Skipping pinning as activity is finishing, unlocking, or showing ad")
                        break
                    }
                    
                    if (devicePolicyManager.isDeviceOwnerApp(packageName) || devicePolicyManager.isProfileOwnerApp(packageName)) {
                        Log.d(TAG, "App is device/profile owner, using setLockTaskFeatures")
                        try {
                            // Ensure we're whitelisted
                            devicePolicyManager.setLockTaskPackages(
                                DeviceAdminReceiver.getComponentName(this),
                                arrayOf(packageName)
                            )
                            
                            // Check again if we should be pinning
                            if (isFinishing || isUnlocking || isShowingAd) {
                                Log.d(TAG, "Skipping pinning at last moment as state changed")
                                break
                            }
                            
                            // Start lock task mode with dialog suppression if appropriate
                            if (shouldSkipPinningDialog && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                // On Android 9+, we can use the no dialog flag
                                startLockTask()
                            } else {
                                // Standard pinning which may show the dialog
                                startLockTask()
                            }
                            
                            isPinned = true
                            prefs.edit().putBoolean(KEY_WAS_LOCKED, true).apply()
                            Log.d(TAG, "Successfully pinned screen on attempt ${retryCount + 1}")
                        } catch (e: Exception) {
                            retryCount++
                            Log.e(TAG, "Failed to start lock task mode (attempt $retryCount): ${e.message}")
                            if (retryCount < maxRetries) {
                                Thread.sleep(retryDelay)
                            }
                        }
                    } else {
                        Log.w(TAG, "App is not device/profile owner, using fallback without setLockTaskFeatures")
                        try {
                            // Check again if we should be pinning
                            if (isFinishing || isUnlocking || isShowingAd) {
                                Log.d(TAG, "Skipping pinning at last moment as state changed")
                                break
                            }
                            
                            // Fallback: Use startLockTask() without features
                            startLockTask()
                            isPinned = true
                            prefs.edit().putBoolean(KEY_WAS_LOCKED, true).apply()
                            Log.d(TAG, "Successfully pinned screen on attempt ${retryCount + 1} using fallback")
                        } catch (e: Exception) {
                            retryCount++
                            Log.e(TAG, "Failed to start lock task mode (attempt $retryCount) using fallback: ${e.message}")
                            if (retryCount < maxRetries) {
                                Thread.sleep(retryDelay)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in pinScreen: ${e.message}")
        }
    }

    /**
     * Handles touch events outside of UI elements more gracefully to prevent inadvertent errors
     */
    private fun handleOutsideTouchGracefully(event: MotionEvent) {
        try {
            // Only handle ACTION_UP to avoid repeated triggers
            if (event.action != MotionEvent.ACTION_UP) return
            
            // Re-apply immersive mode
            hideSystemUI()
            
            // Move task to front to ensure we stay visible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            }
            
            // Log the touch
            Log.d(TAG, "Touch outside PIN entry area - handled gracefully")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling outside touch gracefully: ${e.message}")
        }
    }
    
    /**
     * Checks if a touch event is inside a specific view
     */
    private fun isTouchInsideView(event: MotionEvent, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = event.rawX
        val y = event.rawY
        return x >= location[0] &&
               x <= location[0] + view.width &&
               y >= location[1] &&
               y <= location[1] + view.height
    }

    /**
     * Safely completes the prayer and finishes the activity
     */
    private fun completeAndFinish() {
        try {
            // Make sure we set this flag to prevent repin attempts
            isUnlocking = true
            
            // Mark as completed in preferences
            getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("lock_screen_active", false)
                .putBoolean("pin_verified", true)
                .putBoolean("is_unlocked", true)
                .putLong("last_unlock_time", System.currentTimeMillis())
                .putLong("very_recent_unlock", System.currentTimeMillis())  // Add this line
                .putBoolean("is_displaying_ad", false)
                // Suppress the pinning dialog for 10 minutes after completion
                .putLong("last_pin_dialog_time", System.currentTimeMillis() + 10 * 60 * 1000)
                .apply()
            
            Log.d(TAG, "Prayer completed, finishing activity")
            
            // Unpin if needed
            if (isPinned) {
                unpinScreen()
            }
            
            // Finish after a brief delay to ensure UI has updated
            handler.postDelayed({
                try {
                    if (!isFinishing) {
                        finish()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error finishing activity: ${e.message}")
                }
            }, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Error in completeAndFinish: ${e.message}")
            // Try to finish anyway
            finish()
        }
    }

    /**
     * Complete the prayer using PIN verification and finish the activity
     */
    private fun finishWithPin() {
        // Update SharedPreferences to mark PIN verification
        val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("pin_verified", true)
            .putBoolean("is_unlocked", true)
            .putLong("last_unlock_time", System.currentTimeMillis())
            .putLong("very_recent_unlock", System.currentTimeMillis())  // Add this line
            .putBoolean("legitimate_dismissal", true)
            .putBoolean("lock_screen_active", false)
            .apply()
        
        // Update local state
        shouldUnlock = true
        isUnlocked = true
        pinVerified = true  // Update the class-level property
        
        // Show success feedback by using the pinDisplay view
        binding.pinDisplay.text = "✓ Verified"
        binding.pinDisplay.setTextColor(getColor(R.color.colorSuccess))
        
        // Unpin the screen
        if (isPinned) {
            unpinScreen()
        }
        
        // Delay finishing to show the verification success UI
        handler.postDelayed({
            if (!isFinishing) {
                completeAndFinish()
            }
        }, 500)
    }

    // Added for ad preloading logic
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "LockScreenActivity onResume")

        // Set flags to indicate the lock screen is active
        isInstanceActive = true
        isUnlocked = false // Ensure unlocked state is reset on resume

        // Notify the AdManager that the lock screen is active
        prefs.edit().putBoolean(KEY_LOCK_SCREEN_ACTIVE, true).apply()

        // Notify AdManager about lock screen context first
        adManager.notifyLockScreenActive()
        // Then call the existing activation logic in AdManager (which includes a preload attempt that now respects the context)
        adManager.onLockScreenActivated()

        // Start the one-hour refresh timer (Rule #4)
        Log.d(TAG, "Scheduling hourly ad refresh check.")
        handler.removeCallbacks(refreshRunnable) // Ensure no duplicates
        handler.postDelayed(refreshRunnable, ONE_HOUR_MS)
    }

    // Added for ad preloading logic
    private fun setLockScreenActive(isActive: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_SCREEN_ACTIVE, isActive).apply()
        Log.d(TAG, "Lock screen active state set to: $isActive")
    }

    // Added for ad preloading logic
    private fun checkAndRefreshAd() {
        // This runs after one hour
        // Check if lock screen is STILL active (important!) and Activity is resumed
        val isActive = prefs.getBoolean(KEY_LOCK_SCREEN_ACTIVE, false)
        if (isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { // Check lifecycle state
            Log.d(TAG, "Lock screen still active after 1 hour. Forcing ad state reset and preload.")
            // Discard old ad and trigger new preload (Rule #4)
            adManager.forceResetAdState()
            // Reschedule the check for the next hour
            Log.d(TAG, "Rescheduling hourly ad refresh check.")
            handler.postDelayed(refreshRunnable, ONE_HOUR_MS)
        } else {
             Log.d(TAG, "Lock screen no longer active or activity not resumed. Stopping refresh checks.")
             // Do not reschedule if the screen is inactive or activity not resumed
        }
    }
}
