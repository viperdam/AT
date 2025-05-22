package com.viperdam.kidsprayer

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.*
import android.content.res.Configuration
import android.media.RingtoneManager
import android.os.*
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import androidx.work.Configuration as WorkConfiguration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.FirebaseApp
import com.viperdam.kidsprayer.ads.AdManager
import com.viperdam.kidsprayer.ads.AdResetWorker
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.service.AdRefreshWorker
import com.viperdam.kidsprayer.service.LockScreenMonitorService
import com.viperdam.kidsprayer.service.LockService
import com.viperdam.kidsprayer.service.PrayerScheduler
import com.viperdam.kidsprayer.service.ServiceMonitorWorker
import com.viperdam.kidsprayer.service.StateResetWorker
import com.viperdam.kidsprayer.service.BypassDetectionReceiver
import com.viperdam.kidsprayer.time.TimeSourceManager
import com.viperdam.kidsprayer.ui.lock.ads.LockScreenAds
import com.viperdam.kidsprayer.service.AdSettingsCheckWorker
import com.viperdam.kidsprayer.ui.language.LanguageManager
import com.viperdam.kidsprayer.ads.ConsentManager
import com.viperdam.kidsprayer.state.PrayerLockStateManager
import com.amors.kidsprayer.ui.theme.TextPreloader
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.viperdam.kidsprayer.service.EventCleanupWorker
import com.viperdam.kidsprayer.util.PrayerSettingsManager
import java.util.Calendar
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import android.view.View
import androidx.activity.enableEdgeToEdge
import com.viperdam.kidsprayer.utils.EdgeToEdgeHelper
import com.viperdam.kidsprayer.ads.DateChangeReceiver
import com.viperdam.kidsprayer.ads.AdCacheManager
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import androidx.lifecycle.lifecycleScope
import com.viperdam.kidsprayer.ui.settings.PrayerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.rive.runtime.kotlin.core.Rive

@HiltAndroidApp
class PrayerApp : Application(), WorkConfiguration.Provider, DefaultLifecycleObserver, ComponentCallbacks2 {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var adManager: AdManager

    @Inject
    lateinit var consentManager: ConsentManager

    @Inject
    lateinit var lockScreenAds: LockScreenAds

    @Inject
    lateinit var languageManager: LanguageManager

    @Inject
    lateinit var lockStateManager: PrayerLockStateManager

    @Inject
    lateinit var adCacheManager: AdCacheManager

    @Inject
    lateinit var appUpdateManager: AppUpdateManager

    @Inject
    lateinit var prayerScheduler: PrayerScheduler

    private var isInBackground = false
    private var lastPauseTime = 0L

    private var currentActivity: Activity? = null
    private var shouldResetAdStateOnResume: Boolean = false
    private lateinit var prefs: SharedPreferences
    private val APP_UPDATE_REQUEST_CODE = 123

    private var bypassDetectionReceiver: BypassDetectionReceiver? = null
    private var dateChangeReceiver: DateChangeReceiver? = null

    companion object {
        private const val TAG = "PrayerApp"
        private const val PRAYER_NOTIFICATION_CHANNEL = "prayer_notification_channel"
        private const val PRAYER_SERVICE_CHANNEL = "prayer_service_channel"
        private const val SERVICE_MONITOR_WORK = "prayer_service_monitor"
        private const val PRAYER_SCHEDULER_WORK = "prayer_scheduler_work"
        private const val AD_RESET_WORK = "ad_reset_work"
        private const val EVENT_CLEANUP_WORK = "event_cleanup_work"
        const val SHARED_PREFS_NAME = "PrayerAppPrefs"
        const val PRAYER_PREFS_NAME = "prayer_prefs"

        @Volatile private var instance: PrayerApp? = null
        
        fun getInstance(): PrayerApp {
            return instance ?: throw IllegalStateException("PrayerApp not initialized")
        }
    }

    override fun onCreate() {
        super<Application>.onCreate()
        instance = this
        prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)

        try {
            Rive.init(this)
            Log.d(TAG, "Rive runtime initialized.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Rive runtime", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }

        checkAndSaveDefaultsOnFirstRun()

        try {
            initializeTextOptimizations()
            
            initializePrayerSettingsManager()
            
            TimeSourceManager.getInstance(this)
            
            FirebaseApp.initializeApp(this)
            
            try {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
                FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Crashlytics/Analytics", e)
            }
            
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    try {
                        if (activity.javaClass.simpleName != "LockScreenActivity" && activity is ComponentActivity) {
                            val rootView = activity.findViewById<View>(android.R.id.content)
                            
                            EdgeToEdgeHelper.setupEdgeToEdge(
                                activity = activity,
                                rootView = rootView,
                                applyTopInsets = { windowInsets ->
                                    val toolbarId = activity.resources.getIdentifier(
                                        "toolbar", "id", activity.packageName
                                    )
                                    val appBarId = activity.resources.getIdentifier(
                                        "appBarLayout", "id", activity.packageName
                                    )
                                    
                                    val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                                    
                                    activity.findViewById<View>(appBarId)?.apply {
                                        setPadding(
                                            paddingLeft,
                                            systemBarsInsets.top,
                                            paddingRight,
                                            paddingBottom
                                        )
                                    } ?: activity.findViewById<View>(toolbarId)?.apply {
                                        setPadding(
                                            paddingLeft,
                                            systemBarsInsets.top,
                                            paddingRight,
                                            paddingBottom
                                        )
                                    }
                                },
                                applyBottomInsets = { windowInsets ->
                                    val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                                    
                                    val bottomNavId = activity.resources.getIdentifier(
                                        "bottomNavigation", "id", activity.packageName
                                    )
                                    val bottomControlsId = activity.resources.getIdentifier(
                                        "bottomControls", "id", activity.packageName
                                    )
                                    
                                    activity.findViewById<View>(bottomNavId)?.apply {
                                        setPadding(
                                            paddingLeft,
                                            paddingTop,
                                            paddingRight,
                                            systemBarsInsets.bottom
                                        )
                                    } ?: activity.findViewById<View>(bottomControlsId)?.apply {
                                        setPadding(
                                            paddingLeft,
                                            paddingTop,
                                            paddingRight,
                                            systemBarsInsets.bottom
                                        )
                                    }
                                    
                                    val recyclerViewId = activity.resources.getIdentifier(
                                        "recyclerView", "id", activity.packageName
                                    )
                                    activity.findViewById<androidx.recyclerview.widget.RecyclerView>(recyclerViewId)?.let {
                                        EdgeToEdgeHelper.configureRecyclerViewForEdgeToEdge(it)
                                    }
                                }
                            )
                        } else if (activity.javaClass.simpleName == "LockScreenActivity") {
                             Log.d(TAG, "Skipping automatic edge-to-edge and inset handling for LockScreenActivity.")
                        } else if (activity !is ComponentActivity) {
                             Log.w(TAG, "Activity ${activity.javaClass.simpleName} is not a ComponentActivity, skipping edge-to-edge.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error applying edge-to-edge in ${activity.javaClass.simpleName}: ${e.message}")
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
                }
                
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {
                    currentActivity = activity
                    Log.d(TAG, "Activity Resumed: ${activity.localClassName}")
                    if (shouldResetAdStateOnResume) {
                        Log.d(TAG, "Resetting ad state due to recent settings change on resume")
                        adManager.forceResetAdState()
                        shouldResetAdStateOnResume = false
                    }
                }
                override fun onActivityPaused(activity: Activity) {
                    if (currentActivity == activity) {
                        currentActivity = null
                    }
                    lastPauseTime = SystemClock.elapsedRealtime()
                    isInBackground = true
                    Log.d(TAG, "Activity Paused: ${activity.localClassName}")
                }
                override fun onActivityStopped(activity: Activity) {
                    Log.d(TAG, "Activity Stopped: ${activity.localClassName}")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isInBackground && currentActivity == null) {
                            Log.d(TAG, "App likely went to background")
                        }
                    }, 500)
                }
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {
                     if (currentActivity == activity) {
                        currentActivity = null
                     }
                    Log.d(TAG, "Activity Destroyed: ${activity.localClassName}")
                }
            })
            
            createNotificationChannels()
            initializePrayerState()
            initializePrayerCompletion()

            ProcessLifecycleOwner.get().lifecycle.addObserver(this)

            scheduleAdReset()

            scheduleDailyStateReset()
            
            scheduleEventCleanup()
            
            registerBypassDetector()
            
            registerDateChangeReceiver()
            
            try {
                Log.d(TAG, "Ad manager initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing ad manager", e)
            }
            
            try {
                val currentLang = languageManager.getCurrentLanguage()
                Log.d(TAG, "Language initialized to: $currentLang")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing language settings", e)
            }

            val serviceIntent = Intent(this, LockScreenMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            registerPermissionBroadcastReceiver()

            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val prefs = getSharedPreferences("lock_screen_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("ad_loading", false)
                        .putLong("last_crash_time", System.currentTimeMillis())
                        .putString("crash_reason", throwable.message ?: "Unknown error")
                        .commit()
                    
                    adManager?.let {
                        try {
                            it.forceResetAdState()
                        } catch (e: Exception) {
                            Log.e("PrayerApp", "Error resetting ad state during crash", e)
                        }
                    }
                    
                    Log.e("PrayerApp", "App is crashing. Reset ad state for recovery on restart.", throwable)
                } catch (e: Exception) {
                    Log.e("PrayerApp", "Error handling crash cleanup", e)
                }
                
                defaultHandler?.uncaughtException(thread, throwable)
            }
            
            val prefs = getSharedPreferences("lock_screen_prefs", Context.MODE_PRIVATE)
            val lastCrashTime = prefs.getLong("last_crash_time", 0)
            val isAdLoading = prefs.getBoolean("ad_loading", false)
            
            if (lastCrashTime > 0 && isAdLoading) {
                Log.d("PrayerApp", "Detected crash during ad loading, ensuring reset on startup")
                
                prefs.edit()
                    .putBoolean("ad_loading", false)
                    .putBoolean("crash_recovery_applied", true)
                    .apply()
                
                Handler(Looper.getMainLooper()).postDelayed({
                    adManager?.let {
                        Log.d("PrayerApp", "Performing crash recovery reset of ad state")
                        it.forceResetAdState()
                    }
                }, 5000)
            }

            checkForAppUpdate()

            triggerInitialScheduleCheck()

            Log.d(TAG, "Prayer app initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing prayer app", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private fun checkAndSaveDefaultsOnFirstRun() {
        val isFirstRun = prefs.getBoolean("isFirstRun", true)

        if (isFirstRun) {
            Log.d(TAG, "First run detected. Saving default prayer settings...")
            try {
                val prayerPrefs = getSharedPreferences(PRAYER_PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prayerPrefs.edit()

                val defaultSettings = PrayerSettings()

                editor.putBoolean("fajr_enabled", defaultSettings.fajr.enabled)
                editor.putBoolean("fajr_adhan", defaultSettings.fajr.adhanEnabled)
                editor.putBoolean("fajr_notification", defaultSettings.fajr.notificationEnabled)
                editor.putBoolean("fajr_lock", defaultSettings.fajr.lockEnabled)
                editor.putFloat("fajr_adhan_volume", defaultSettings.fajr.adhanVolume)

                editor.putBoolean("dhuhr_enabled", defaultSettings.dhuhr.enabled)
                editor.putBoolean("dhuhr_adhan", defaultSettings.dhuhr.adhanEnabled)
                editor.putBoolean("dhuhr_notification", defaultSettings.dhuhr.notificationEnabled)
                editor.putBoolean("dhuhr_lock", defaultSettings.dhuhr.lockEnabled)
                editor.putFloat("dhuhr_adhan_volume", defaultSettings.dhuhr.adhanVolume)

                editor.putBoolean("asr_enabled", defaultSettings.asr.enabled)
                editor.putBoolean("asr_adhan", defaultSettings.asr.adhanEnabled)
                editor.putBoolean("asr_notification", defaultSettings.asr.notificationEnabled)
                editor.putBoolean("asr_lock", defaultSettings.asr.lockEnabled)
                editor.putFloat("asr_adhan_volume", defaultSettings.asr.adhanVolume)

                editor.putBoolean("maghrib_enabled", defaultSettings.maghrib.enabled)
                editor.putBoolean("maghrib_adhan", defaultSettings.maghrib.adhanEnabled)
                editor.putBoolean("maghrib_notification", defaultSettings.maghrib.notificationEnabled)
                editor.putBoolean("maghrib_lock", defaultSettings.maghrib.lockEnabled)
                editor.putFloat("maghrib_adhan_volume", defaultSettings.maghrib.adhanVolume)

                editor.putBoolean("isha_enabled", defaultSettings.isha.enabled)
                editor.putBoolean("isha_adhan", defaultSettings.isha.adhanEnabled)
                editor.putBoolean("isha_notification", defaultSettings.isha.notificationEnabled)
                editor.putBoolean("isha_lock", defaultSettings.isha.lockEnabled)
                editor.putFloat("isha_adhan_volume", defaultSettings.isha.adhanVolume)

                editor.putInt("notification_time", defaultSettings.notificationAdvanceTime)
                editor.putInt("calculation_method", defaultSettings.calculationMethod)
                editor.putBoolean("vibration_enabled", defaultSettings.vibrationEnabled)
                editor.putBoolean("enable_adhan", defaultSettings.globalAdhanEnabled)
                editor.putBoolean("enable_lock", defaultSettings.globalLockEnabled)
                editor.putBoolean("notifications_enabled", defaultSettings.globalNotificationsEnabled)

                editor.apply()

                prefs.edit().putBoolean("isFirstRun", false).apply()
                Log.d(TAG, "Default prayer settings saved.")

            } catch (e: Exception) {
                Log.e(TAG, "Error saving default prayer settings", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        } else {
            Log.d(TAG, "Not first run, skipping default settings save.")
        }
    }

    private fun triggerInitialScheduleCheck() {
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Triggering initial prayer schedule check...")
            try {
                prayerScheduler.checkAndUpdateSchedule()
                Log.d(TAG, "Initial prayer schedule check completed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error during initial schedule check", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun initializeTextOptimizations() {
        try {
            TextPreloader.preloadCommonTexts(this)
            
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            com.amors.kidsprayer.ui.theme.TextOptimizationApplier
                                .optimizeAllTextViewsInActivity(activity)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error optimizing TextViews in ${activity.javaClass.simpleName}", e)
                        }
                    }, 300)
                }
                
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            
            Log.d(TAG, "Text optimizations initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing text optimizations", e)
        }
    }

    private fun initializePrayerSettingsManager() {
        PrayerSettingsManager.getInstance(this)
    }
    
    private fun initializePrayerCompletion() {
        try {
            PrayerCompletionManager.getInstance(this).clearOldCompletions()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing prayer completion: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
    
    private fun registerBypassDetector() {
        try {
            val completionManager = PrayerCompletionManager.getInstance(this)
            
            bypassDetectionReceiver = BypassDetectionReceiver()
            val intentFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            
            registerReceiver(bypassDetectionReceiver, intentFilter)
            Log.d(TAG, "Bypass detection receiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering bypass detection receiver", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
    
    private fun scheduleAdReset() {
        try {
            val adResetRequest = PeriodicWorkRequestBuilder<AdResetWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(calculateInitialDelay(3, 0), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                AD_RESET_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                adResetRequest
            )
            Log.d(TAG, "Ad reset worker scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling ad reset: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
    
    private fun scheduleDailyStateReset() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<StateResetWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "daily_state_reset",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            
            Log.d(TAG, "Scheduled daily state reset worker")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling daily state reset", e)
        }
    }
    
    private fun scheduleEventCleanup() {
        try {
            val cleanupRequest = PeriodicWorkRequestBuilder<EventCleanupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(calculateInitialDelay(2, 0), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                EVENT_CLEANUP_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                cleanupRequest
            )
            Log.d(TAG, "Event cleanup worker scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling event cleanup: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }
    
    private fun calculateInitialDelay(targetHour: Int, targetMinute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            
            if (before(now)) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        
        return target.timeInMillis - now.timeInMillis
    }

    private fun initializeApp() {
        FirebaseApp.initializeApp(this)
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
        FirebaseAnalytics.getInstance(this)

        initializeAds()
        
        scheduleAdSettingsEnforcement()

        ServiceMonitorWorker.enqueuePeriodicWork(this)

        if (!com.viperdam.kidsprayer.security.DeviceAdminReceiver.isAdminActive(this)) {
            Log.d(TAG, "Device admin not active, requesting privileges")
            com.viperdam.kidsprayer.security.DeviceAdminReceiver.requestAdminPrivileges(this)
        } else {
            Log.d(TAG, "Device admin already active")
            com.viperdam.kidsprayer.service.LockService.startService(this)
        }
    }

    private fun initializeAds() {
        try {
            if (isMainProcess()) {
                Log.d(TAG, "Initializing ads in main process")
                try {
                    android.webkit.WebView.setDataDirectorySuffix("main_process_" + Process.myPid())
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting WebView data directory suffix", e)
                    FirebaseCrashlytics.getInstance().recordException(e)
                }

                ensureNetworkPermissions()

                try {
                    val isAppInDebugMode = packageManager.getApplicationInfo(packageName, 0).flags and 
                        ApplicationInfo.FLAG_DEBUGGABLE != 0
                    
                    val isDebugMode = false
                    
                    if (hasAllNetworkPermissions()) {
                        Log.d(TAG, "All network permissions granted, initializing AdMob")
                    } else {
                        Log.w(TAG, "Some network permissions missing for AdMob")
                    }
                    
                    MobileAds.initialize(this) { initializationStatus ->
                        try {
                            val statusMap = initializationStatus.adapterStatusMap
                            val areAdsReady = statusMap.values.all { it.initializationState == com.google.android.gms.ads.initialization.AdapterStatus.State.READY }

                            if (areAdsReady) {
                                Log.d(TAG, "Mobile Ads initialized successfully with child-directed settings")
                                lockScreenAds.enforceAdContentRatingG()
                                
                                adCacheManager.start()
                                Log.d(TAG, "Started AdCacheManager for periodic ad monitoring")
                            } else {
                                Log.w(TAG, "Mobile Ads initialization incomplete: ${statusMap.values.map { it.description }}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing ads initialization status", e)
                            FirebaseCrashlytics.getInstance().recordException(e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during AdMob-only initialization section (previously Unity)", e)
                    FirebaseCrashlytics.getInstance().recordException(e)
                }

                val requestConfiguration = RequestConfiguration.Builder()
                    .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_UNSPECIFIED)
                    .setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_UNSPECIFIED)
                    .build()
                MobileAds.setRequestConfiguration(requestConfiguration)
                Log.d(TAG, "Global AdMob RequestConfiguration set to UNSPECIFIED for child/under-age tags.")
                
                getSharedPreferences("ad_settings_prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("child_directed_enforced", true)
                    .putLong("last_enforced_time", System.currentTimeMillis())
                    .apply()
            } else {
                Log.d(TAG, "Skipping ad initialization in non-main process")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ads", e)
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        for (processInfo in activityManager.runningAppProcesses) {
            if (processInfo.pid == pid) {
                return processInfo.processName == packageName
            }
        }
        return false
    }

    private fun setupAdRefreshWorker() {
        val workRequest = PeriodicWorkRequestBuilder<AdRefreshWorker>(1, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ad_refresh_work",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                createNotificationChannel(
                    PRAYER_NOTIFICATION_CHANNEL,
                    getString(R.string.prayer_time_notification),
                    NotificationManager.IMPORTANCE_HIGH
                )

                createNotificationChannel(
                    PRAYER_SERVICE_CHANNEL,
                    getString(R.string.prayer_service_title),
                    NotificationManager.IMPORTANCE_LOW
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error creating notification channels: ${e.message}")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun createNotificationChannel(channelId: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = "Prayer App notifications"
                enableVibration(true)
                enableLights(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initializePrayerState() {
        try {
            val prefs = getSharedPreferences("prayer_settings", Context.MODE_PRIVATE)
            val isFirstRun = prefs.getBoolean("is_first_run", true)

            if (isFirstRun) {
                Log.d(TAG, "First time app run, initializing prayer state")

                prefs.edit().apply {
                    putBoolean("adhan_enabled", true)
                    putFloat("adhan_volume", 0.7f)

                    putBoolean("fajr_enabled", true)
                    putBoolean("fajr_adhan", false)
                    putBoolean("fajr_notification", false)
                    putBoolean("fajr_lock", true)

                    putBoolean("dhuhr_enabled", true)
                    putBoolean("dhuhr_adhan", true)
                    putBoolean("dhuhr_notification", true)
                    putBoolean("dhuhr_lock", true)

                    putBoolean("asr_enabled", true)
                    putBoolean("asr_adhan", true)
                    putBoolean("asr_notification", true)
                    putBoolean("asr_lock", true)

                    putBoolean("maghrib_enabled", true)
                    putBoolean("maghrib_adhan", false)
                    putBoolean("maghrib_notification", true)
                    putBoolean("maghrib_lock", true)

                    putBoolean("isha_enabled", true)
                    putBoolean("isha_adhan", false)
                    putBoolean("isha_notification", false)
                    putBoolean("isha_lock", true)

                    putBoolean("lock_screen_enabled", true)
                    putBoolean("lock_screen_camera", false)

                    putBoolean("notifications_enabled", true)
                    putInt("notification_time", 15)

                    putBoolean("is_first_run", false)
                    apply()
                }

                val prayerScheduler = PrayerScheduler(this)
                prayerScheduler.schedulePrayerWork()

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                            PRAYER_SCHEDULER_WORK,
                            ExistingPeriodicWorkPolicy.UPDATE,
                            PeriodicWorkRequestBuilder<ServiceMonitorWorker>(30, TimeUnit.MINUTES)
                                .setConstraints(
                                    Constraints.Builder()
                                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                                        .build()
                                )
                                .build()
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error initializing prayer state: ${e.message}")
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing prayer state: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private fun scheduleAdSettingsEnforcement() {
        try {
            val immediateWorkRequest = androidx.work.OneTimeWorkRequestBuilder<AdSettingsCheckWorker>()
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
                
            val periodicWorkRequest = androidx.work.PeriodicWorkRequestBuilder<AdSettingsCheckWorker>(
                2, TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            ).build()
            
            WorkManager.getInstance(this).enqueue(immediateWorkRequest)
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "child_directed_ad_settings",
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                periodicWorkRequest
            )
            
            Log.d(TAG, "Scheduled child-directed ad settings enforcement checks")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling ad settings enforcement: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    @Deprecated(
        message = "Overrides a deprecated method",
        level = DeprecationLevel.WARNING
    )
    override fun onLowMemory() {
        super<Application>.onLowMemory()
        Log.d(TAG, "Low memory detected, resetting ad state")
        adManager.forceResetAdState()
    }

    override fun onTrimMemory(level: Int) {
        super<Application>.onTrimMemory(level)
        if (level >= 60) {
            Log.d(TAG, "Memory pressure detected, releasing ad resources")
            adManager.forceResetAdState()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super<Application>.onConfigurationChanged(newConfig)
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d(TAG, "App moved to foreground")
        try {
            adManager?.onAppForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStart lifecycle: ${e.message}")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d(TAG, "App moved to background")
        lastPauseTime = System.currentTimeMillis()
        isInBackground = true
    }

    fun reloadAds() {
    }

    private val stateResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == StateResetWorker.STATE_RESET_ACTION) {
                Log.d(TAG, "Received state reset broadcast")
                initializeApp()
                initializePrayerState()
                initializePrayerCompletion()
            }
        }
    }

    override fun onTerminate() {
        try {
            adCacheManager.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AdCacheManager", e)
        }
        
        try {
            bypassDetectionReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering bypass detection receiver", e)
                }
                bypassDetectionReceiver = null
            }
            
            dateChangeReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering date change receiver", e)
                }
                dateChangeReceiver = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onTerminate", e)
        }
        
        super.onTerminate()
    }

    private fun createNotification(title: String, message: String): Notification {
        return NotificationCompat.Builder(this, PRAYER_NOTIFICATION_CHANNEL)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun ensureNetworkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val requiredPermissions = arrayOf(
                android.Manifest.permission.INTERNET,
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                android.Manifest.permission.ACCESS_WIFI_STATE,
                android.Manifest.permission.CHANGE_NETWORK_STATE,
                android.Manifest.permission.CHANGE_WIFI_STATE,
                android.Manifest.permission.READ_PHONE_STATE
            )
            
            val permissionsToRequest = requiredPermissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            
            if (permissionsToRequest.isNotEmpty()) {
                val intent = Intent(this, NetworkPermissionActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
    }

    private fun hasAllNetworkPermissions(): Boolean {
        val requiredPermissions = arrayOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_NETWORK_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
            android.Manifest.permission.READ_PHONE_STATE
        )
        
        return requiredPermissions.all { permission ->
            checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun registerPermissionBroadcastReceiver() {
        val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "com.viperdam.kidsprayer.PERMISSION_REQUIRED") {
                    val permissionType = intent.getStringExtra("permission_type")
                    Log.d(TAG, "Received permission required broadcast: $permissionType")
                    
                    if (permissionType == "location_foreground_service") {
                        val launchIntent = Intent(context, com.viperdam.kidsprayer.ui.main.MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra("request_location_permissions", true)
                        }
                        context.startActivity(launchIntent)
                    }
                }
            }
        }
        
        try {
            registerReceiver(
                permissionReceiver,
                IntentFilter("com.viperdam.kidsprayer.PERMISSION_REQUIRED"),
                RECEIVER_NOT_EXPORTED
            )
            Log.d(TAG, "Permission broadcast receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register permission broadcast receiver", e)
        }
    }

    private fun registerDateChangeReceiver() {
        try {
            DateChangeReceiver.initialize(this)
            
            dateChangeReceiver = DateChangeReceiver()
            val intentFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            
            registerReceiver(dateChangeReceiver, intentFilter)
            Log.d(TAG, "DateChangeReceiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering DateChangeReceiver: ${e.message}")
        }
    }

    private fun checkForAppUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            Log.d(TAG, "Checking for updates. Available version: ${appUpdateInfo.availableVersionCode()}, Update available: ${appUpdateInfo.updateAvailability()}")

            when (appUpdateInfo.updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE -> Log.d(TAG, "Update state: UPDATE_AVAILABLE")
                UpdateAvailability.UPDATE_NOT_AVAILABLE -> Log.d(TAG, "Update state: UPDATE_NOT_AVAILABLE")
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> Log.d(TAG, "Update state: DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS")
                UpdateAvailability.UNKNOWN -> Log.d(TAG, "Update state: UNKNOWN")
                else -> Log.d(TAG, "Update state: Other (${appUpdateInfo.updateAvailability()})")
            }

            val lastUpdateCheck = prefs.getLong("last_update_prompt_time", 0L)
            val currentTime = System.currentTimeMillis()
            val checkInterval = TimeUnit.DAYS.toMillis(1)

            if (appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) &&
                appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {

                if (currentTime - lastUpdateCheck > checkInterval) {
                    Log.d(TAG, "Flexible update available and interval passed. Requesting update flow.")
                    requestUpdate(appUpdateInfo, AppUpdateType.FLEXIBLE)
                    prefs.edit().putLong("last_update_prompt_time", currentTime).apply()
                } else {
                    Log.d(TAG, "Flexible update available but skipped (checked recently).")
                }

            } else if (appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) &&
                       appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                Log.d(TAG, "Immediate update available/required. Requesting update flow.")
                requestUpdate(appUpdateInfo, AppUpdateType.IMMEDIATE)

            } else {
                 Log.d(TAG, "No suitable update available or allowed at this time.")
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to check for app updates", e)
        }
    }

    private fun requestUpdate(appUpdateInfo: AppUpdateInfo, updateType: Int) {
        currentActivity?.let { activity ->
            try {
                val options = AppUpdateOptions.newBuilder(updateType).build()
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    activity,
                    options,
                    APP_UPDATE_REQUEST_CODE
                )
                Log.d(TAG, "Started update flow for type: $updateType")
            } catch (e: IntentSender.SendIntentException) {
                Log.e(TAG, "Error starting update flow", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        } ?: Log.w(TAG, "Cannot start update flow, currentActivity is null")
    }

    private fun shouldForceImmediateUpdate(appUpdateInfo: AppUpdateInfo): Boolean {
        return (appUpdateInfo.updatePriority() >= 4) ||
               (appUpdateInfo.clientVersionStalenessDays() ?: 0 >= 7)
    }

    /**
     * Public method to access the current activity
     * @return The current foreground activity or null if none
     */
    fun getCurrentActivity(): Activity? {
        return currentActivity
    }
}
