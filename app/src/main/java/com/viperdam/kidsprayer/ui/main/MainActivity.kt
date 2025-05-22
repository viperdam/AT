package com.viperdam.kidsprayer.ui.main

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.PrayerApp
import com.viperdam.kidsprayer.ads.AdManager
import com.viperdam.kidsprayer.ads.ConsentManager
import com.viperdam.kidsprayer.databinding.ActivityMainBinding
import com.viperdam.kidsprayer.security.DeviceAdminReceiver
import com.viperdam.kidsprayer.security.PinManager
import com.viperdam.kidsprayer.service.LockScreenService
import com.viperdam.kidsprayer.service.PrayerScheduler
import com.viperdam.kidsprayer.ui.lock.LockScreenActivity
import com.viperdam.kidsprayer.ui.lock.LockScreenViewModel
import com.viperdam.kidsprayer.ui.pin.PinSetupDialog
import com.viperdam.kidsprayer.ui.pin.PinVerificationDialog
import com.viperdam.kidsprayer.ui.settings.SettingsActivity
import com.viperdam.kidsprayer.utils.PermissionHelper
import com.viperdam.kidsprayer.utils.PrayerValidator
import com.viperdam.kidsprayer.ui.lock.ads.LockScreenAds
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.app.ActivityManager
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.viperdam.kidsprayer.extensions.doOnEnd
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.app.Dialog
import android.view.Window
import com.viperdam.kidsprayer.ui.rating.RatingDialog
import com.viperdam.kidsprayer.ui.language.LanguageManager
import com.viperdam.kidsprayer.ui.language.LanguageSelectionDialog
import android.content.res.Configuration
import com.viperdam.kidsprayer.utils.LocationPermissionHelper
import com.google.android.gms.common.api.ApiException // Import ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.viperdam.kidsprayer.services.LocationMonitorService // Import the service
import com.viperdam.kidsprayer.receivers.LocationProviderChangedReceiver // Import receiver for action constant
import com.viperdam.kidsprayer.prayer.LocationManager as AppLocationManager // Import AppLocationManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.viperdam.kidsprayer.utils.SystemBarsUtil
import androidx.core.widget.NestedScrollView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean // Import AtomicBoolean
import com.viperdam.kidsprayer.ui.quran.QuranActivity // Import QuranActivity

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), LanguageSelectionDialog.LanguageSelectionProvider {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            try {
                // Refresh prayer times periodically only if monitoring is active
                 if (refreshJob?.isActive == true) {
                    viewModel.refreshPrayerTimes()
                 }
                
                // Schedule next refresh
                val delayToNextRefresh = 15 * 60 * 1000L // 15 minutes 
                refreshHandler.postDelayed(this, delayToNextRefresh)
                Log.d(TAG, "Scheduled next prayer time refresh in ${delayToNextRefresh/1000} seconds")
            } catch (e: Exception) {
                Log.e(TAG, "Error in refresh runnable", e)
                // Retry after 5 seconds if there's an error
                refreshHandler.postDelayed(this, 5000)
            }
        }
    }

    @Inject
    lateinit var pinManager: PinManager

    @Inject
    lateinit var adManager: AdManager

    @Inject
    lateinit var lockScreenAds: LockScreenAds

    @Inject
    lateinit var languageManagerInstance: LanguageManager

    @Inject
    lateinit var consentManager: ConsentManager

    @Inject // Inject AppLocationManager
    lateinit var appLocationManager: AppLocationManager

    private var refreshJob: Job? = null // Tracks the ViewModel monitoring job
    private val handler = Handler(Looper.getMainLooper())
    private var isRequestingPermission = false // Tracks if a system setting dialog is active
    private var deviceAdminDialog: AlertDialog? = null
    private var _currentPermissionCheck = 0 // Tracks progress through system settings checks
    private var currentPermissionCheck: Int
        get() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_PERMISSION_CHECK, _currentPermissionCheck)
            .also { Log.d(TAG, "Getting current permission check: $it") }
        set(value) {
            Log.d(TAG, "Setting current permission check: $value")
            _currentPermissionCheck = value
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_PERMISSION_CHECK, value)
                .apply()
        }

    private lateinit var statusReceiver: BroadcastReceiver

    private val unpinHandler = Handler(Looper.getMainLooper())
    private val unpinChecker = object : Runnable {
        override fun run() {
            // Check if we need to unpin every 1 second while MainActivity is visible
            checkAndUnpinIfNeeded(forceUnpin = true)
            unpinHandler.postDelayed(this, 1000)
        }
    }

    private lateinit var locationPermissionHelper: LocationPermissionHelper

    // UMP SDK related properties
    private lateinit var consentInformation: ConsentInformation
    private val isMobileAdsInitialized = AtomicBoolean(false)

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "KidsPrayerPrefs"
        private const val KEY_PERMISSION_CHECK = "current_permission_check"
        private const val MIN_BANNER_INTERVAL = 60000L // 1 minute in milliseconds
        private const val REQUEST_CHECK_SETTINGS = 1001 // Request code for location settings resolution
        private const val PERMISSION_REQUEST_LOCATION_SERVICE = 1002 // Request code for location service permissions
    }

    override fun attachBaseContext(newBase: Context) {
        // Get the language manager from dagger injection
        val languageManager = (application as? PrayerApp)?.languageManager
            ?: return super.attachBaseContext(newBase)
            
        // Apply selected language to app context
        val config = languageManager.updateConfiguration(Configuration(newBase.resources.configuration))
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Check if we're restarting from a language change
        val isRestartingFromLanguageChange = intent.getBooleanExtra("restart_from_language_change", false)
        if (isRestartingFromLanguageChange) {
            Log.d(TAG, "Restarting from language change")
        }

        // Apply language configuration again right at the start
        try {
            val currentLanguage = languageManagerInstance.getCurrentLanguage()
            Log.d(TAG, "MainActivity onCreate - Current language: $currentLanguage")

            // Force update configuration
            val config = resources.configuration
            val newConfig = languageManagerInstance.updateConfiguration(config)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying language configuration in onCreate", e)
        }

        // Apply splash screen first
        val splashScreen = installSplashScreen()
        // Keep splash screen visible while ads are initializing
        splashScreen.setKeepOnScreenCondition { !isMobileAdsInitialized.get() }

        // Enable edge-to-edge display
        enableEdgeToEdge()
        
        super.onCreate(savedInstanceState)
        
        // Initialize view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize app readiness state early
        viewModel.initializeApp()
        
        // Configure edge-to-edge UI
        SystemBarsUtil.setupEdgeToEdge(this) { view, windowInsets ->
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply padding to the header container
            if (::binding.isInitialized) {
                binding.headerContainer.setPadding(
                    binding.headerContainer.paddingLeft + systemBarsInsets.left,
                    systemBarsInsets.top,
                    binding.headerContainer.paddingRight + systemBarsInsets.right,
                    binding.headerContainer.paddingBottom
                )

                // Apply padding to the bottom navigation container
                binding.bottomNavContainer.setPadding(
                    binding.bottomNavContainer.paddingLeft + systemBarsInsets.left,
                    binding.bottomNavContainer.paddingTop,
                    binding.bottomNavContainer.paddingRight + systemBarsInsets.right,
                    systemBarsInsets.bottom
                )
            }
            
            // Return the insets for other views to apply
            windowInsets
        }
        
        // Initialize location permission helper
        locationPermissionHelper = LocationPermissionHelper(this)

        // Initialize Consent and Ads FIRST
        initializeConsentAndAds()

        // Continue with the rest of the setup AFTER consent/ads attempt
        setupUI()
        initializeAppFlow() // Start the main setup flow (PIN -> Permissions -> Settings)
        setupStatusReceiver()
        startLocationMonitorService() // Start the service to monitor location status

        // Check if launched from the location settings notification (FSI removed, but keep for safety)
        handleIntentAction(intent)

        // Add a small delay before animating the buttons to make them noticeable
        handler.postDelayed({
            animateNavigationButtons()
        }, 500)

        // Check if we were launched to request location permissions
        if (intent?.getBooleanExtra("request_location_permissions", false) == true) {
            // Request the permissions immediately
            Log.d(TAG, "App launched with request to check location permissions")
            val permissionsToRequest = mutableListOf<String>()
            
            // Check for foreground service permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && 
                ActivityCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.FOREGROUND_SERVICE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
            }
            
            // Check for location permissions
            if (ActivityCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            
            // Request permissions if needed
            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toTypedArray(),
                    PERMISSION_REQUEST_LOCATION_SERVICE
                )
            }
        }

        // --- Set Click Listener for quranCard --- 
        binding.quranCard.setOnClickListener {
            Log.d(TAG, "Quran Card clicked, starting QuranActivity...")
            startActivity(Intent(this, QuranActivity::class.java))
        }
        // --- End Set Click Listener ---
    }

    // Launcher for handling standard permission requests
    private val permissionResultLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedPermissions = permissions.filter { !it.value }.keys
        val locationPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val wereLocationPermissionsRequested = permissions.keys.any { it in locationPermissions }
        // Check if location was granted *in this request* or was *already* granted
        val hasForegroundLocation = (wereLocationPermissionsRequested && !deniedPermissions.any { it in locationPermissions }) ||
                                    (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                     ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)

        if (deniedPermissions.isNotEmpty()) {
            Log.w(TAG, "Some permissions were denied: $deniedPermissions")
            showPermissionError(deniedPermissions)
        } else {
            Log.d(TAG, "All requested permissions granted.")
        }

        // Check if background location needs to be requested (only if foreground was granted)
        if (hasForegroundLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             val hasBackground = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
             if (!hasBackground) {
                 requestBackgroundLocationIfNeeded() // This calls checkLocationSettingsAndProceed on completion
                 return@registerForActivityResult // Wait for background result
             }
        }
        
        // Proceed to check location settings if needed, then system settings
        checkLocationSettingsAndProceed(checkSettings = hasForegroundLocation)
    }
    
    private fun showPermissionError(deniedPermissions: Collection<String>) {
        val permissionNames = deniedPermissions.mapNotNull {
            when(it) {
                Manifest.permission.CAMERA -> "Camera"
                Manifest.permission.ACCESS_FINE_LOCATION, 
                Manifest.permission.ACCESS_COARSE_LOCATION -> "Location"
                Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
                // Add others if needed, or return null for less critical ones
                else -> null // it.substringAfterLast('.')
            }
        }.distinct() // Show each type only once
        
        if (permissionNames.isNotEmpty()) {
            showError("${permissionNames.joinToString(", ")} permissions required for full functionality")
        }
    }
    
    private fun requestBackgroundLocationIfNeeded() {
        // Only request background location on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lifecycleScope.launch {
                locationPermissionHelper.requestBackgroundLocationIfNeeded { granted ->
                    Log.d(TAG, "Background location granted: $granted")
                    // Continue with the flow regardless of background permission result
                    checkLocationSettingsAndProceed(checkSettings = true) // Check settings now
                }
            }
        } else {
            checkLocationSettingsAndProceed(checkSettings = true) // Check settings on older versions
        }
    }
    
    // Checks location settings and then proceeds to system checks
    private fun checkLocationSettingsAndProceed(checkSettings: Boolean) {
        Log.d(TAG, "checkLocationSettingsAndProceed called. Check Settings: $checkSettings")
        if (checkSettings) {
            // Check location settings immediately
            requestLocationSettingsEnableNow { settingsOk ->
                // Proceed to system checks. Refresh will happen only if settingsOk is true.
                startSystemChecks(locationReady = settingsOk)
            }
        } else {
             // Location permissions not granted or not applicable, skip settings check
             Log.d(TAG, "Skipping location settings check. Proceeding to system checks.")
             startSystemChecks(locationReady = false) // Don't refresh location yet
        }
    }

    // Launcher for handling location settings resolution result
    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.locationSettingsResolutionAttempted() // Notify ViewModel to clear state
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Location settings enabled by user via dialog.")
            // Settings enabled. Start system checks and trigger refresh.
            startSystemChecks(locationReady = true)
        } else {
            Log.w(TAG, "User did not enable location settings via dialog.")
            showError(getString(R.string.location_settings_required_error))
            // Show the manual button if binding is initialized
            if(::binding.isInitialized) {
                 binding.enableLocationButton.visibility = View.VISIBLE
            }
            // Proceed with other system checks even if location not enabled
            startSystemChecks(locationReady = false)
        }
    }

    /**
     * Handles new intents when the activity is already running
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle intent if activity is already running and receives the FSI
        handleIntentAction(intent)
    }

    private fun handleIntentAction(intent: Intent) {
        // Handle the action from the notification (now standard, not FSI)
        // This might just bring the app to the foreground, rely on onResume checks
        if (intent.action == LocationProviderChangedReceiver.ACTION_REQUEST_LOCATION_SETTINGS) {
            Log.d(TAG, "Launched from location settings request intent (handleIntentAction).")
            // Directly request location settings enable check
            requestLocationSettingsEnableNow { /* Callback result handled by launcher */ }
        }
    }

    // Directly checks and requests location settings enable
    private fun requestLocationSettingsEnableNow(callback: (Boolean) -> Unit) {
        Log.d(TAG, "Checking location settings status...")
        // Ensure we have location permissions before checking settings
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted, cannot check settings.")
            callback(false) // Cannot proceed without permission
            return
        }

        appLocationManager.requestLocationEnable() // Use injected AppLocationManager
            .addOnSuccessListener {
                Log.d(TAG, "Location settings already satisfied.")
                callback(true) // Indicate settings are OK
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Location settings check failed.", exception)
                if (exception is ResolvableApiException) {
                    // Location settings are not satisfied, but this can be fixed
                    triggerLocationSettingsResolution(exception)
                    // Callback will be invoked when the launcher returns, so don't invoke here
                } else {
                    // Location settings are not satisfied and cannot be fixed.
                     showError(getString(R.string.location_settings_required_error))
                     if(::binding.isInitialized) {
                         binding.enableLocationButton.visibility = View.VISIBLE
                     }
                     callback(false) // Indicate settings are NOT ok
                }
            }
    }


    private fun triggerLocationSettingsResolution(resolvableApiException: ResolvableApiException) {
         try {
            Log.d(TAG, "Attempting to launch location settings resolution dialog.")
            val intentSenderRequest = IntentSenderRequest.Builder(resolvableApiException.resolution).build()
            locationSettingsLauncher.launch(intentSenderRequest)
            // Result handled by locationSettingsLauncher callback
        } catch (e: IntentSender.SendIntentException) {
            Log.e(TAG, "Error launching location settings resolution from trigger", e)
            showError("Could not prompt to enable location.")
            // Clear the state in ViewModel as we couldn't launch
            viewModel.locationSettingsResolutionAttempted()
             if(::binding.isInitialized) {
                 binding.enableLocationButton.visibility = View.VISIBLE // Show manual button
             }
             // If using callback version, invoke callback(false) here
             // For launcher version, the launcher callback handles the next step
        }
    }


    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showLocationPermissionError() {
        AlertDialog.Builder(this, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(R.string.location_permission_title) // Updated title
            .setMessage(R.string.location_permission_required)
            .setPositiveButton(R.string.settings) { _, _ ->
                locationPermissionHelper.openAppSettings()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                showError(getString(R.string.location_permission_error)) // Updated error message
            }
            .setCancelable(false)
            .show()
    }

    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun setupUI() {
        try {
            binding.apply {
                // Set current date
                val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
                currentDate.text = dateFormat.format(Date())
                
                // Setup language selector
                languageSelector.setOnClickListener {
                    showLanguageSelectionDialog()
                }
                
                // Make next prayer card clickable to show all prayers
                nextPrayerCard.setOnClickListener {
                    val intent = Intent(this@MainActivity, com.viperdam.kidsprayer.ui.prayers.DailyPrayersActivity::class.java)
                    startActivity(intent)
                }
                
                // Debug log
                Log.d(TAG, "Setting up UI elements")
                
                // Add test data immediately to verify next prayer card is working
                addTestPrayerData()

                // Setup Lottie animation for prayer lock
                testLockButton.setOnClickListener {
                    // Find the CardView and Lottie animation
                    val cardView = testLockButton.findViewById<androidx.cardview.widget.CardView>(R.id.prayerLockCard)
                    val lottieView = cardView?.findViewById<View>(R.id.prayerLockAnimation)
                    
                    // Animate the card with a smaller scale factor
                    cardView?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(100)?.withEndAction {
                        cardView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    }?.start()
                    
                    // Play the Lottie animation
                    if (lottieView is LottieAnimationView) {
                        lottieView.playAnimation()
                    }
                    
                    startTestLock()
                }

                // Setup Qibla finder button
                qiblaFinderButton.setOnClickListener {
                    // Find the CardView and ImageView
                    val cardView = qiblaFinderButton.findViewById<androidx.cardview.widget.CardView>(R.id.qiblaFinderCard)
                    val iconView = cardView?.findViewById<View>(R.id.qiblaFinderIcon)
                    
                    // Animate the card with a smaller scale factor
                    cardView?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(100)?.withEndAction {
                        cardView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    }?.start()
                    
                    // Start the Qibla finder activity
                    try {
                        val className = "com.viperdam.kidsprayer.ui.qibla.QiblaFinderActivity"
                        val intent = Intent()
                        intent.setClassName(this@MainActivity, className)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting QiblaFinderActivity", e)
                        Toast.makeText(this@MainActivity, "Qibla finder is not available", Toast.LENGTH_SHORT).show()
                    }
                }

                // Setup Lottie animation for settings
                settingsButton.setOnClickListener {
                    // Find the CardView and Lottie animation
                    val cardView = settingsButton.findViewById<androidx.cardview.widget.CardView>(R.id.settingsCard)
                    val lottieView = cardView?.findViewById<View>(R.id.settingsAnimation)
                    
                    // Animate the card with a smaller scale factor
                    cardView?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(100)?.withEndAction {
                        cardView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    }?.start()
                    
                    // Play the Lottie animation
                    if (lottieView is LottieAnimationView) {
                        lottieView.playAnimation()
                    }
                    
                    lifecycleScope.launch {
                        if (pinManager.isPinSetup()) {
                            PinVerificationDialog().apply {
                                listener = {
                                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                }
                                show(supportFragmentManager, "pin_verification_settings")
                            }
                        } else {
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        }
                    }
                }
                
                // Setup the new feature button
                val newFeatureButton = findViewById<View>(R.id.newFeatureButton)
                newFeatureButton?.setOnClickListener {
                    // Find the CardView for animation
                    val cardView = newFeatureButton.findViewById<androidx.cardview.widget.CardView>(R.id.newFeatureCard)
                    
                    // Animate the card
                    cardView?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(100)?.withEndAction {
                        cardView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    }?.start()
                    
                    // Show rating and sharing dialog
                    showRatingAndSharingDialog()
                }

                // Setup location button - Initially hidden, shown by ViewModel state if needed
                enableLocationButton.visibility = View.GONE
                enableLocationButton.setOnClickListener {
                    // Directly request settings enable when button clicked
                    requestLocationSettingsEnableNow { /* No callback needed here */ }
                }
            }
            observeViewModel()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up UI: ${e.message}", e)
            showError("Error setting up UI: ${e.message}")
        }
    }

    private fun startTestLock() {
        try {
            Log.d(TAG, "Starting test lock screen...")
            
            // First, set the necessary preferences to mark this as an active test prayer
            val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("lock_screen_active", true)
                putBoolean("is_test_prayer", true)
                putString("active_prayer", "Test Prayer")
                putInt("active_rakaat", 4)
                putBoolean("pin_verified", false)
                putBoolean("is_unlocked", false)
                putLong("last_activation", System.currentTimeMillis())
                apply()
            }
            
            // Stop existing service if running
            LockScreenService.stopService(this)
            
            // Create and launch intent
            val intent = Intent(this, LockScreenActivity::class.java).apply {
                putExtra("prayer_name", "Test Prayer")
                putExtra("rakaat_count", 4)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            
            // Enhance the intent with PrayerValidator
            val enhancedIntent = PrayerValidator.enhanceLockScreenIntent(intent)
            Log.d(TAG, "Starting lock screen activity for test prayer...")
            
            startActivity(enhancedIntent)
            
            // Start the service with a delay
            handler.postDelayed({
                try {
                    Log.d(TAG, "Starting lock screen service for test prayer...")
                    LockScreenService.startService(this, "Test Prayer", 4)
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting lock service: ${e.message}")
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }, 500) // Short delay to ensure activity has started first
        } catch (e: Exception) {
            Log.e(TAG, "Error starting test lock: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    Log.d(TAG, "UI state updated: Prayers=${state.prayers.size}, Loading=${state.isLoading}, Error=${state.error}, ResolutionNeeded=${state.locationSettingsResolution != null}")
                    
                    // Handle Location Settings Resolution (triggered by ViewModel state)
                    state.locationSettingsResolution?.let { resolvableApiException ->
                        Log.d(TAG, "Location settings resolution required (from ViewModel). Launching prompt.")
                        triggerLocationSettingsResolution(resolvableApiException)
                    }

                    // Update Prayer UI
                    state.nextPrayer?.let {
                        binding.nextPrayerName.text = it.name
                        binding.nextPrayerTime.text = formatTime(it.time)
                        Log.d(TAG, "Next prayer set in UI: ${it.name} at ${formatTime(it.time)}")
                    } ?: run {
                        // Handle case where there's no next prayer (e.g., end of day or error)
                        if (!state.isLoading && state.error == null && state.prayers.isEmpty()) { // Show only if no prayers loaded and no error
                            binding.nextPrayerName.text = getString(R.string.no_upcoming_prayers)
                            binding.nextPrayerTime.text = ""
                        } else if (!state.isLoading && state.error != null && state.prayers.isEmpty()) {
                             // If there's an error and no prayers, clear the fields
                             binding.nextPrayerName.text = ""
                             binding.nextPrayerTime.text = ""
                        }
                    }

                    // Update Loading/Error UI
                    if (state.isLoading) {
                        binding.loadingAnimationView.visibility = View.VISIBLE
                        if (!binding.loadingAnimationView.isAnimating) {
                            binding.loadingAnimationView.playAnimation()
                        }
                    } else {
                        binding.loadingAnimationView.cancelAnimation() 
                        binding.loadingAnimationView.visibility = View.GONE
                    }
                    binding.errorMessage.visibility = if (state.error != null && state.locationSettingsResolution == null) View.VISIBLE else View.GONE
                    binding.errorMessage.text = state.error

                    // Show/Hide manual location enable button based on resolution state or error
                    val showEnableButton = state.locationSettingsResolution != null || (state.error != null && state.prayers.isEmpty())
                    binding.enableLocationButton.visibility = if (showEnableButton) View.VISIBLE else View.GONE

                }
            }
        }
    }


    private fun formatTime(timeInMillis: Long): String {
        return try {
            SimpleDateFormat("hh:mm a", Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date(timeInMillis))
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting time: ${e.message}")
            "Invalid Time"
        }
    }

    // Renamed from initializeApp to avoid confusion with ViewModel's initializeApp
    private fun initializeAppFlow() {
        // Add a longer delay to ensure the splash screen has fully transitioned away
        // and the binding is properly initialized
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Check if binding is initialized
                if (!::binding.isInitialized) {
                    Log.e(TAG, "Binding not initialized yet, retrying in 500ms")
                    // If binding is not ready, try again after a short delay
                    Handler(Looper.getMainLooper()).postDelayed({ initializeAppFlow() }, 500)
                    return@postDelayed
                }
                
                // Now proceed with the initialization flow
                Log.d(TAG, "Starting initialization flow with PIN and permissions")
                lifecycleScope.launch {
                    try {
                        // First check if PIN is setup
                        if (!pinManager.isPinSetup()) {
                            // Check if FragmentManager state is saved before showing dialog
                            if (!supportFragmentManager.isStateSaved) {
                                showFirstTimePinSetup()
                            } else {
                                Log.w(TAG, "Skipping PIN setup dialog: state already saved.")
                                // Handle this case if necessary, e.g., show a Toast or retry later
                                // Depending on the flow, you might want to call checkPermissions() here anyway
                                // or schedule the PIN setup for when the activity resumes.
                                // For now, just logging.
                            }
                        } else {
                            // Then check permissions
                            checkPermissions() // This will eventually call checkLocationSettingsAndProceed
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error initializing app flow: ${e.message}")
                        showError("Error initializing app: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in initializeAppFlow: ${e.message}")
                // Show a quick toast since we can't guarantee binding is available
                Toast.makeText(applicationContext, "Error initializing app", Toast.LENGTH_SHORT).show()
            }
        }, 1500) // 1.5 second delay to ensure splash screen has transitioned away
    }

    private fun showFirstTimePinSetup() {
        // No need for lifecycleScope here, called from within one in initializeAppFlow
        // No need to check isPinSetup again, already checked by the caller
        PinSetupDialog().apply {
            isCancelable = false
            setOnPinConfirmedListener { pin ->
                setupPin(pin)
            }
            // Check again just before showing, although the main check is in the caller
            if (!supportFragmentManager.isStateSaved) {
                 show(supportFragmentManager, "pin_setup")
            } else {
                 Log.w(TAG, "Skipping PIN setup dialog show() call: state already saved.")
            }
        }
    }

    private fun setupPin(pin: String) {
        lifecycleScope.launch {
            try {
                pinManager.setPin(pin)
                Toast.makeText(
                    this@MainActivity,
                    R.string.new_pin_set,
                    Toast.LENGTH_SHORT
                ).show()
                checkPermissions() // Check permissions after PIN setup
            } catch (e: Exception) {
                Log.e(TAG, "Error setting PIN: ${e.message}")
                showError("Error setting PIN: ${e.message}")
            }
        }
    }

    private fun checkPermissions() {
        // List permissions that need to be requested via standard Android dialog
        val standardPermissions = mutableListOf<String>().apply {
            // Camera permission always needs to be requested with standard dialog
            add(Manifest.permission.CAMERA)
            
            // Location permissions
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            
            // Other permissions
            add(Manifest.permission.WAKE_LOCK)
            add(Manifest.permission.VIBRATE)
            add(Manifest.permission.FOREGROUND_SERVICE)
            add(Manifest.permission.RECEIVE_BOOT_COMPLETED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Check which permissions need to be requested
        val permissionsToRequest = standardPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting standard permissions: $permissionsToRequest")
            // Use standard Android permission dialog
            permissionResultLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // All standard permissions granted, check for background location permission
            Log.d(TAG, "All standard permissions already granted.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasBackgroundLocation = ContextCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                if (!hasBackgroundLocation) {
                    requestBackgroundLocationIfNeeded() // This calls checkLocationSettingsAndProceed
                    return // Wait for background location result
                }
            }
            
            // All permissions (including background if needed) are granted, proceed
            checkLocationSettingsAndProceed(checkSettings = true)
        }
    }


    override fun onResume() {
        super.onResume()
        
        // Show pending ad if requested by LockScreenActivity
        lockScreenAds.showPendingAdIfNeeded(this)
        
        // Check if we need to unpin the app
        checkAndUnpinIfNeeded()
        
        // Update the Sun/Moon visualizer
        updateTimeVisualizer()
        
        // Start the refresh handler only if monitoring is supposed to be active
        if (currentPermissionCheck >= 5 && refreshJob?.isActive == true) {
             startRefreshHandler()
        }
        
        // Reset stale lock screen state
        val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
        if (prefs.getLong("last_stale_check", 0) < System.currentTimeMillis() - 60 * 60 * 1000) {
            prefs.edit().putLong("last_stale_check", System.currentTimeMillis()).apply()
            val lockScreenActive = prefs.getBoolean("lock_screen_active", false)
            if (lockScreenActive) {
                // Check if it's actually stale (hasn't been interacted with in 3 hours)
                val lastInteraction = prefs.getLong("last_lock_interaction", 0)
                if (lastInteraction < System.currentTimeMillis() - 3 * 60 * 60 * 1000) {
                    Log.w(TAG, "Detected stale lock screen state - resetting")
                    prefs.edit().putBoolean("lock_screen_active", false).apply()
                }
            }
        }
        
        // Handle system permission/settings checks continuation if needed
        // This ensures if user grants a setting and returns, the next check proceeds
        if (isRequestingPermission) {
            // Check the current state and potentially advance
            checkSystemSettingsContinuation()
        }
        // No 'else' needed here, as the normal flow is handled by other functions
    }

    // Helper to check settings status when returning to the activity
    private fun checkSystemSettingsContinuation() {
         when (currentPermissionCheck) {
            0 -> { // Checking Battery Optimization
                if (!isPowerOptimized()) {
                    isRequestingPermission = false
                    currentPermissionCheck = 1
                    checkSystemSettings()
                } else {
                    // Still waiting for user to change setting
                    Log.d(TAG, "Still waiting for battery optimization change.")
                }
            }
            1 -> { // Checking Device Admin
                if (DeviceAdminReceiver.isAdminActive(this)) {
                    isRequestingPermission = false
                    currentPermissionCheck = 2
                    checkSystemSettings()
                } else {
                     Log.d(TAG, "Still waiting for device admin activation.")
                }
            }
            2 -> { // Checking Overlay Permission
                if (Settings.canDrawOverlays(this)) {
                    isRequestingPermission = false
                    currentPermissionCheck = 3
                    checkSystemSettings()
                } else {
                     Log.d(TAG, "Still waiting for overlay permission.")
                }
            }
            3 -> { // Checking Alarm Permission
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || 
                    (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()) {
                    isRequestingPermission = false
                    currentPermissionCheck = 4
                    checkSystemSettings()
                } else {
                     Log.d(TAG, "Still waiting for alarm permission.")
                }
            }
            4 -> { // Checking DND Permission
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || 
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted) {
                    isRequestingPermission = false
                    currentPermissionCheck = 5
                    checkSystemSettings() // Move to final state check
                } else {
                     Log.d(TAG, "Still waiting for DND permission.")
                }
            }
            // State 5 means all checks passed, no continuation needed
        }
    }


    private fun checkSystemSettings() {
        if (isRequestingPermission) {
            Log.d(TAG, "Already requesting a specific permission/setting, skipping checkSystemSettings sequence.")
            return
        }

        Log.d(TAG, "Checking system settings sequence, current state: $currentPermissionCheck")

        when (currentPermissionCheck) {
            0 -> { // Battery Optimization
                if (isPowerOptimized()) {
                    isRequestingPermission = true
                    showBatteryOptimizationDialog()
                } else {
                    currentPermissionCheck = 1
                    checkSystemSettings() // Proceed to next check immediately
                }
            }
            1 -> { // Device Admin
                if (!DeviceAdminReceiver.isAdminActive(this)) {
                    isRequestingPermission = true
                    showDeviceAdminDialog(false)
                } else {
                    currentPermissionCheck = 2
                    checkSystemSettings() // Proceed to next check immediately
                }
            }
            2 -> { // Overlay Permission
                if (!Settings.canDrawOverlays(this)) {
                    isRequestingPermission = true
                    AlertDialog.Builder(this, R.style.Theme_KidsPrayer_Dialog)
                        .setTitle(R.string.overlay_permission_title)
                        .setMessage(R.string.overlay_permission_message)
                        .setPositiveButton(R.string.settings) { _, _ ->
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        }
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            isRequestingPermission = false
                            showError("Overlay permission is recommended for timely reminders.")
                            // Proceed even if cancelled
                            currentPermissionCheck = 3
                            checkSystemSettings()
                        }
                        .setOnCancelListener {
                            isRequestingPermission = false
                             // Proceed even if cancelled
                            currentPermissionCheck = 3
                            checkSystemSettings()
                        }
                        .show()
                } else {
                    currentPermissionCheck = 3
                    checkSystemSettings() // Proceed to next check immediately
                }
            }
            3 -> { // Alarm Permission (Android S+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !(getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()) {
                    isRequestingPermission = true
                    showAlarmPermissionDialog()
                } else {
                    currentPermissionCheck = 4
                    checkSystemSettings() // Proceed to next check immediately
                }
            }
            4 -> { // Do Not Disturb Permission (Android M+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    !(getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .isNotificationPolicyAccessGranted) {
                    isRequestingPermission = true
                    showDoNotDisturbPermissionDialog()
                } else {
                    currentPermissionCheck = 5 // All checks passed
                    startPrayerMonitoring() // Start core functionality
                }
            }
            5 -> { // All checks completed
                Log.d(TAG, "All system settings checks passed.")
                startPrayerMonitoring()
            }
        }
    }

    // Starts system checks after location settings are confirmed (or skipped)
    private fun startSystemChecks(locationReady: Boolean) {
        Log.d(TAG, "startSystemChecks called. Location Ready: $locationReady")
        if (locationReady) {
             viewModel.refreshPrayerTimes() // Trigger initial refresh now
        }
        currentPermissionCheck = 0 // Reset system settings check index
        isRequestingPermission = false
        checkSystemSettings() // Start system checks (battery, admin etc)
    }


    private fun startPrayerMonitoring() {
        // Ensure this doesn't run multiple times unnecessarily
        if (refreshJob?.isActive == true) {
            Log.d(TAG, "Prayer monitoring flow collection already active.")
            // return // Don't return, still need to start service and timer if not running
        }
        Log.d(TAG, "Starting prayer monitoring components...")
        
        // Start the Location Monitor Service
        try {
            // Check for required permissions first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 15
                val hasForegroundPermission = ActivityCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.FOREGROUND_SERVICE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                val hasLocationPermission = ActivityCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || 
                ActivityCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                if (!hasForegroundPermission || !hasLocationPermission) {
                    // We're missing permissions, request them
                    val permissionsToRequest = mutableListOf<String>()
                    
                    if (!hasForegroundPermission) {
                        permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
                    }
                    if (!hasLocationPermission) {
                        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                    
                    ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest.toTypedArray(),
                        PERMISSION_REQUEST_LOCATION_SERVICE
                    )
                    
                    Log.d(TAG, "Requesting permissions for location service: ${permissionsToRequest.joinToString()}")
                    return // Don't continue until permissions are granted
                }
            }
            
            val serviceIntent = Intent(this, LocationMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "LocationMonitorService started.")
        } catch (e: Exception) {
             Log.e(TAG, "Error starting LocationMonitorService", e)
        }

        // Start ViewModel monitoring (flow collection) if not already active
        if (refreshJob?.isActive != true) {
             viewModel.startMonitoring() 
             refreshJob = lifecycleScope.launch { /* Keep track locally */ }
             Log.d(TAG, "ViewModel location flow collection started.")
        } else {
             Log.d(TAG, "ViewModel location flow collection already active.")
        }
        
        // Start UI refresh timer
        startRefreshHandler()
    }


    private fun setupStatusReceiver() {
        try {
            statusReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == LockScreenViewModel.ACTION_PRAYER_STATUS_CHANGED) {
                        viewModel.refreshPrayerTimes()
                    }
                }
            }
            val filter = IntentFilter(LockScreenViewModel.ACTION_PRAYER_STATUS_CHANGED)
            registerReceiver(
                statusReceiver,
                filter,
                RECEIVER_NOT_EXPORTED // Use RECEIVER_NOT_EXPORTED for modern Android
            )
            Log.d(TAG, "Successfully registered status receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering status receiver: ${e.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        
        // Start the periodic unpin checker
        unpinHandler.post(unpinChecker)
        
        // Immediately check if we need to unpin
        checkAndUnpinIfNeeded(forceUnpin = true)
    }
    
    override fun onStop() {
        super.onStop()
        
        // Notify LockScreenAds if MainActivity is stopped while an ad might have been showing
        lockScreenAds.notifyAdHostActivityStopped()

        // Stop the periodic unpin checker when activity is not visible
        unpinHandler.removeCallbacks(unpinChecker)
        // Stop the refresh handler when activity is not visible
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // Remove all callbacks
            refreshHandler.removeCallbacks(refreshRunnable)
            unpinHandler.removeCallbacks(unpinChecker)
            
            // Safely unregister receiver
            if (::statusReceiver.isInitialized) {
                unregisterReceiver(statusReceiver)
                Log.d(TAG, "Successfully unregistered status receiver")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}")
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.battery_optimization_button) { _, _ ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // For Android 14+, open battery settings directly
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                        // Show guidance for Android 14+
                        AlertDialog.Builder(this, R.style.Theme_KidsPrayer_Dialog)
                            .setTitle("Additional Steps Required")
                            .setMessage("1. Tap on 'Battery'\n2. Select 'Unrestricted' for battery usage\n3. Return to the app when done")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        // For Android 6-13, use the direct battery optimization intent
                        val intent = Intent().apply {
                            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            data = Uri.parse("package:$packageName")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting battery optimization: ${e.message}")
                    Toast.makeText(
                        this,
                        "Failed to open battery settings. Please manually disable battery optimization for this app.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                isRequestingPermission = false
                showError("Battery optimization is recommended for reliable notifications.")
                // Proceed even if cancelled
                currentPermissionCheck = 1
                checkSystemSettings()
            }
            .setOnCancelListener {
                isRequestingPermission = false
                 // Proceed even if cancelled
                currentPermissionCheck = 1
                checkSystemSettings()
            }
            .show()
    }

    private fun showDeviceAdminDialog(isRetry: Boolean) {
        deviceAdminDialog = AlertDialog.Builder(this, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(if (isRetry) R.string.device_admin_retry_title else R.string.device_admin_title)
            .setMessage(if (isRetry) R.string.device_admin_retry_message else R.string.device_admin_message)
            .setPositiveButton(R.string.settings) { _, _ ->
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceAdminReceiver.getComponentName(this@MainActivity))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_explanation))
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                isRequestingPermission = false
                showError("Device Admin is required for lock screen features.")
                 // Proceed even if cancelled
                currentPermissionCheck = 2
                checkSystemSettings()
            }
            .setOnCancelListener {
                isRequestingPermission = false
                 // Proceed even if cancelled
                currentPermissionCheck = 2
                checkSystemSettings()
            }
            .show()
    }

    private fun showAlarmPermissionDialog() {
        AlertDialog.Builder(this, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(R.string.alarm_permission_title)
            .setMessage(R.string.alarm_permission_message)
            .setPositiveButton(R.string.settings) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                isRequestingPermission = false
                showError("Exact alarm permission is needed for precise prayer notifications.")
                 // Proceed even if cancelled
                currentPermissionCheck = 4
                checkSystemSettings()
            }
            .setOnCancelListener {
                isRequestingPermission = false
                 // Proceed even if cancelled
                currentPermissionCheck = 4
                checkSystemSettings()
            }
            .show()
    }

    private fun showDoNotDisturbPermissionDialog() {
        AlertDialog.Builder(this, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(R.string.dnd_permission_title)
            .setMessage(R.string.dnd_permission_message)
            .setPositiveButton(R.string.settings) { _, _ ->
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                isRequestingPermission = false
                showError("Do Not Disturb access allows Adhan during silent mode.")
                 // Proceed even if cancelled
                currentPermissionCheck = 5
                checkSystemSettings()
            }
            .setOnCancelListener {
                isRequestingPermission = false
                 // Proceed even if cancelled
                currentPermissionCheck = 5
                checkSystemSettings()
            }
            .show()
    }

    private fun isPowerOptimized(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                usageStatsManager.getAppStandbyBucket() > UsageStatsManager.STANDBY_BUCKET_ACTIVE
            } catch (e: Exception) {
                Log.e(TAG, "Error checking app standby bucket: ${e.message}")
                // Assume not optimized if check fails
                false
            }
        } else {
            !powerManager.isIgnoringBatteryOptimizations(packageName)
        }
    }

    private fun addTestPrayerData() {
        // Create some test prayer data to immediately verify the next prayer display is working
        val currentTime = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        
        val testPrayers = listOf(
            createTestPrayer("Fajr", calendar.apply { 
                set(Calendar.HOUR_OF_DAY, 5)
                set(Calendar.MINUTE, 30)
            }.timeInMillis, 2),
            createTestPrayer("Dhuhr", calendar.apply { 
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 30)
            }.timeInMillis, 4),
            createTestPrayer("Asr", calendar.apply { 
                set(Calendar.HOUR_OF_DAY, 15)
                set(Calendar.MINUTE, 45)
            }.timeInMillis, 4),
            createTestPrayer("Maghrib", calendar.apply { 
                set(Calendar.HOUR_OF_DAY, 18)
                set(Calendar.MINUTE, 15)
            }.timeInMillis, 3),
            createTestPrayer("Isha", calendar.apply { 
                set(Calendar.HOUR_OF_DAY, 20)
                set(Calendar.MINUTE, 0)
            }.timeInMillis, 4)
        )
        
        // Find the next prayer time
        val nextPrayer = testPrayers.firstOrNull { it.time > System.currentTimeMillis() }
        nextPrayer?.let {
            binding.nextPrayerName.text = it.name
            binding.nextPrayerTime.text = formatTime(it.time)
            Log.d(TAG, "Set test next prayer: ${it.name} at ${formatTime(it.time)}")
        }
    }

    private fun createTestPrayer(name: String, time: Long, rakaatCount: Int): com.viperdam.kidsprayer.model.Prayer {
        return com.viperdam.kidsprayer.model.Prayer(
            name = name,
            time = time,
            rakaatCount = rakaatCount
        )
    }

    /**
     * Checks if the app is pinned and unpins it if we're in MainActivity
     * 
     * @param forceUnpin If true, will unpin regardless of lock screen state
     */
    private fun checkAndUnpinIfNeeded(forceUnpin: Boolean = false) {
        try {
            // Get the ActivityManager to check if we're pinned
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            // Check if we're in lock task mode (pinned)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val lockTaskState = am.lockTaskModeState
                val isPinned = lockTaskState == ActivityManager.LOCK_TASK_MODE_PINNED
                
                // If we're pinned and this is MainActivity, always unpin
                if (isPinned && (forceUnpin || isMainActivityActive())) {
                    Log.d(TAG, "MainActivity detected while app is pinned. Unpinning immediately...")
                    try {
                        stopLockTask()
                        Toast.makeText(this, "Exiting pin mode", Toast.LENGTH_SHORT).show()
                        
                        // Suppress the pinning dialog for a while after unpinning
                        val prefs = getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putLong("last_unpin_time", System.currentTimeMillis())
                            .apply()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unpin app: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndUnpinIfNeeded: ${e.message}")
        }
    }
    
    /**
     * Determines if MainActivity is currently active and in the foreground
     */
    private fun isMainActivityActive(): Boolean {
        // In MainActivity, we're definitely active
        return true
    }

    private fun updateTimeVisualizer() {
        try {
            // Update the sun/moon visualizer if it exists
            if (::binding.isInitialized) {
                binding.sunMoonVisualizer?.onResume()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating time visualizer: ${e.message}")
        }
    }

    private fun startRefreshHandler() {
        try {
            // Start the refresh handler with the runnable
            refreshHandler.removeCallbacksAndMessages(null)
            refreshHandler.post(refreshRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting refresh handler: ${e.message}")
        }
    }

    private fun animateNavigationButtons() {
        try {
            // Animate each button card with a staggered delay for a nicer effect
            animateCard(findViewById(R.id.testLockButton), R.id.prayerLockCard, 0)
            animateCard(findViewById(R.id.qiblaFinderButton), R.id.qiblaFinderCard, 100)
            animateCard(findViewById(R.id.settingsButton), R.id.settingsCard, 200)
            animateCard(findViewById(R.id.newFeatureButton), R.id.newFeatureCard, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Error animating navigation buttons: ${e.message}")
        }
    }
    
    private fun animateCard(container: View?, cardId: Int, delay: Long) {
        container?.let { parent ->
            val cardView = parent.findViewById<androidx.cardview.widget.CardView>(cardId)
            cardView?.apply {
                alpha = 0f
                scaleX = 0.85f
                scaleY = 0.85f
                
                // Animate the card
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .setStartDelay(delay)
                    .start()
                    
                // Optional: play Lottie animation for further emphasis
                findViewById<View>(R.id.prayerLockAnimation)?.let {
                    if (it is LottieAnimationView && cardId == R.id.prayerLockCard) {
                        it.postDelayed({ it.playAnimation() }, delay + 200)
                    }
                }
                findViewById<View>(R.id.qiblaFinderIcon)?.let {
                    if (it is ImageView && cardId == R.id.qiblaFinderCard) {
                        // Apply a more distinctive rotation animation for the compass icon
                        it.postDelayed({
                            // First slightly enlarge the icon
                            it.animate()
                                .scaleX(1.15f)
                                .scaleY(1.15f)
                                .setDuration(200)
                                .withEndAction {
                                    // Then do a complete rotation with easing
                                    it.animate()
                                        .rotation(720f) // Two full rotations
                                        .setDuration(1500)
                                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                                        .withEndAction {
                                            // Return to original scale with a slight bounce effect
                                            it.animate()
                                                .scaleX(1.0f)
                                                .scaleY(1.0f)
                                                .setDuration(300)
                                                .setInterpolator(android.view.animation.OvershootInterpolator(0.7f))
                                                .withEndAction { 
                                                    it.rotation = 0f 
                                                }
                                                .start()
                                        }
                                        .start()
                                }
                                .start()
                        }, delay + 200)
                    }
                }
                findViewById<View>(R.id.settingsAnimation)?.let {
                    if (it is LottieAnimationView && cardId == R.id.settingsCard) {
                        it.postDelayed({ it.playAnimation() }, delay + 200)
                    }
                }
            }
        }
    }

    private fun showRatingAndSharingDialog() {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_rating_sharing)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
            }
        }

        // Initialize views
        val rateButton = dialog.findViewById<Button>(R.id.rateButton)
        val shareButton = dialog.findViewById<Button>(R.id.shareButton)
        val closeButton = dialog.findViewById<Button>(R.id.closeButton)

        // Set click listeners
        rateButton.setOnClickListener {
            dialog.dismiss()
            RatingDialog(this).show()
        }

        shareButton.setOnClickListener {
            dialog.dismiss()
            shareApp()
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun shareApp() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_app_subject))
            putExtra(Intent.EXTRA_TEXT, "Check out this amazing prayer times app: https://play.google.com/store/apps/details?id=${packageName}")
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_via)))
    }

    private fun showLanguageSelectionDialog() {
        try {
            val dialog = LanguageSelectionDialog().apply {
                setOnLanguageSelectedListener { languageCode ->
                    // Make sure we save the language code before restarting
                    Log.d(TAG, "Language selected: $languageCode")
                    
                    // First check if the language exists as a resource directory
                    val resourceExists = try {
                        val resourceId = resources.getIdentifier("string/app_name", "id", packageName)
                        resourceId != 0
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking resource existence: ${e.message}", e)
                        true // Assume it exists to avoid blocking language change
                    }
                    
                    Log.d(TAG, "Resource exists for $languageCode: $resourceExists")
                    
                    // CRITICAL FIX: Set the language before restarting
                    val languageChanged = languageManagerInstance.setLanguage(languageCode)
                    Log.d(TAG, "Language set result: $languageChanged")
                    
                    if (languageChanged) {
                        Toast.makeText(this@MainActivity, getString(R.string.changing_language), Toast.LENGTH_SHORT).show()
                        // Restart app to apply language changes
                        languageManagerInstance.restartApp(this@MainActivity)
                    } else {
                        // Language is already set to this value
                        Toast.makeText(this@MainActivity, getString(R.string.language_already_set), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            dialog.show(supportFragmentManager, "language_selection")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing language selection dialog: ${e.message}", e)
            Toast.makeText(this, "Error showing language selector", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getLanguageManager(): LanguageManager = languageManagerInstance

    /**
     * Called when the configuration changes, including language changes
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        val currentLanguage = languageManagerInstance.getCurrentLanguage()
        Log.d(TAG, "onConfigurationChanged - Current language: $currentLanguage, config locale: ${newConfig.locales.get(0)}")
        
        // Apply our language configuration to the new configuration
        val updatedConfig = languageManagerInstance.updateConfiguration(newConfig)
        
        // Create a new context with our updated configuration
        val context = createConfigurationContext(updatedConfig)
        
        // Call super with the updated config
        super.onConfigurationChanged(updatedConfig)
        
        // Need to refresh all view content since we changed the language
        refreshAllContent()
    }

    /**
     * Refresh all content to reflect the current language
     */
    private fun refreshAllContent() {
        Log.d(TAG, "Refreshing all content with current language: ${languageManagerInstance.getCurrentLanguage()}")
        
        try {
            // Refresh the binding
            if (::binding.isInitialized) {
                // Update text views with translated strings
                binding.nextPrayerTitle.text = getString(R.string.next_prayer)
                binding.currentDate.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
                binding.enableLocationButton?.text = getString(R.string.enable_location)
                
                // Update the action bar title
                supportActionBar?.title = getString(R.string.app_name)
                
                // Refresh the view model data
                viewModel.refreshPrayerTimes()
            } else {
                Log.d(TAG, "Binding not initialized, can't refresh UI")
                // If binding isn't ready, we'll schedule a delayed recreation
                Handler(Looper.getMainLooper()).postDelayed({
                    recreate()
                }, 500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing content after language change", e)
            // Last resort - recreate the activity
            recreate()
        }
    }

    // ADD the new functions for UMP and Mobile Ads Init
    private fun initializeConsentAndAds() {
        // Set tag for underage of consent. True as this is a child-directed app.
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(true)
            .build()

        consentInformation = UserMessagingPlatform.getConsentInformation(this)

        // For testing purposes, add debug settings if needed (ensure test device ID is correct)
        // val debugSettings = ConsentDebugSettings.Builder(this)
        //     // .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
        //     .addTestDeviceHashedId("YOUR_TEST_DEVICE_HASHED_ID") // Obtain from logcat
        //     .build()
        // val params = ConsentRequestParameters.Builder()
        //     .setConsentDebugSettings(debugSettings)
        //     .setTagForUnderAgeOfConsent(true)
        //     .build()
        // consentInformation.reset() // Force reset for testing

        // Request consent information update
        consentInformation.requestConsentInfoUpdate(
            this,
            params,
            { // OnConsentInfoUpdateSuccessListener
                Log.d(TAG, "UMP Flow: Consent info update success. Status: ${consentInformation.consentStatus}, CanRequestAds: ${consentInformation.canRequestAds()}")
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this@MainActivity) { loadAndShowError ->
                    if (loadAndShowError != null) {
                        Log.w(TAG, "UMP Flow: Consent form load/show error: ${loadAndShowError.errorCode} - ${loadAndShowError.message}")
                    }
                    // Initialize ads regardless of form error, but after attempt.
                    if (consentInformation.canRequestAds()) {
                        initializeMobileAds()
                    } else {
                        Log.w(TAG, "UMP Flow: Consent check indicates ads should not be requested (canRequestAds is false). Skipping Mobile Ads init.")
                        // Ensure splash screen can dismiss even if ads aren't initialized
                        isMobileAdsInitialized.set(true)
                    }
                }
            },
            { // OnConsentInfoUpdateFailureListener
                requestError ->
                Log.e(TAG, "UMP Flow: Consent info update failed: ${requestError.errorCode} - ${requestError.message}")
                // Initialize ads even if UMP update fails, as per Google's recommendation.
                initializeMobileAds()
            }
        )

        // Initial check: if consent already allows ads, try initializing immediately.
        // This handles cases where the update listener might not be called if info is up-to-date.
        if (consentInformation.canRequestAds() && !isMobileAdsInitialized.get()) {
             Log.d(TAG, "UMP Flow: Initial check allows ads, attempting init.")
             initializeMobileAds()
        }
    }

    private fun initializeMobileAds() {
        // Use AtomicBoolean to ensure initialization happens only once.
        if (isMobileAdsInitialized.compareAndSet(false, true)) {
            // Initialize on a background thread to avoid blocking the main thread.
            Thread {
                MobileAds.initialize(this) { initializationStatus ->
                    Log.d(TAG, "Mobile Ads SDK Initialized on background thread. Status: $initializationStatus")
                    // Apply global configuration AFTER Mobile Ads SDK is initialized.
                    try {
                        // Run configuration on main thread.
                        runOnUiThread {
                            val requestConfiguration = RequestConfiguration.Builder()
                                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                                .build()
                            MobileAds.setRequestConfiguration(requestConfiguration)
                            Log.d(TAG, "Applied MAX_AD_CONTENT_RATING_G globally AFTER Mobile Ads init.")
                            // Splash screen condition (set in onCreate) will now become false, allowing dismissal.
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error applying MAX_AD_CONTENT_RATING_G after Mobile Ads init", e)
                    }
                }
            }.start()
        } else {
            Log.d(TAG, "Mobile Ads SDK already initializing/initialized.")
        }
    }

    private fun startLocationMonitorService() {
        try {
            Log.d(TAG, "Starting LocationMonitorService...")
            val serviceIntent = Intent(this, com.viperdam.kidsprayer.services.LocationMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting LocationMonitorService", e)
        }
    }

    // Add the onRequestPermissionsResult method to handle permission responses
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_LOCATION_SERVICE) {
            // Check if all permissions were granted
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (allGranted) {
                Log.d(TAG, "All required permissions granted, starting LocationMonitorService")
                // Start the service now that we have permissions
                val serviceIntent = Intent(this, LocationMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                // Handle the case where permissions were denied
                Log.w(TAG, "Some permissions were denied for LocationMonitorService")
                // Show a dialog explaining why permissions are needed
                AlertDialog.Builder(this, R.style.Theme_KidsPrayer_Dialog)
                    .setTitle("Permissions Required")
                    .setMessage("Location and foreground service permissions are required for accurate prayer times. Without these permissions, some features may not work correctly.")
                    .setPositiveButton("Settings") { _, _ ->
                        // Open app settings so user can enable permissions
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Continue Anyway") { _, _ ->
                        // User chose to continue without permissions
                        // The app will work with reduced functionality
                        Log.d(TAG, "User chose to continue without required permissions")
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun enableEdgeToEdge() {
        // Implementation of enableEdgeToEdge method
    }
}