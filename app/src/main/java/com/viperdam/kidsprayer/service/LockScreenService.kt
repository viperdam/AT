package com.viperdam.kidsprayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.ui.lock.LockScreenActivity
import com.viperdam.kidsprayer.utils.PrayerValidator
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.time.TimeSourceManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@AndroidEntryPoint
class LockScreenService : Service(), TimeSourceManager.TimeChangeListener {
    @Inject
    lateinit var workManager: WorkManager
    
    private lateinit var prefs: SharedPreferences
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isTestLock: Boolean = false
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var protectionJobs = mutableListOf<Job>()
    private val stateMutex = Mutex()
    private var lastLockScreenShow = 0L
    private var lastScreenStateChange = 0L
    private var lastProtectionCheck = 0L
    private var lastServiceRestart = 0L
    private var lastWakeLockRefresh = 0L
    private var lastVisibilityCheck = 0L
    private var consecutiveInvisibleChecks = 0
    private val MAX_INVISIBLE_CHECKS = 4
    
    // Add variables to track prayer information
    private var currentPrayerName: String? = null
    private var currentRakaatCount: Int = 0
    private var isPrayerCompleted: Boolean = false
    private var isPinVerified: Boolean = false
    
    private val _serviceState = MutableStateFlow(ServiceState())
    private val serviceState = _serviceState.asStateFlow()
    
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    serviceScope.launch {
                        stateMutex.withLock {
                            if (!isUnlocked()) {
                                handleScreenOff()
                            }
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    serviceScope.launch {
                        stateMutex.withLock {
                            if (!isUnlocked()) {
                                handleScreenOn()
                            }
                        }
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    serviceScope.launch {
                        stateMutex.withLock {
                            if (!isUnlocked()) {
                                handleUserPresent()
                            }
                        }
                    }
                }
            }
        }
    }
    
    private lateinit var activityManager: ActivityManager
    private lateinit var windowManager: WindowManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager

    // Add reference to PrayerCompletionManager
    @Inject
    lateinit var completionManager: PrayerCompletionManager

    // Add TimeSourceManager reference
    private lateinit var timeSourceManager: TimeSourceManager
    
    data class ServiceState(
        val isLockActive: Boolean = false,
        val isCameraActive: Boolean = false,
        val isPinVerified: Boolean = false,
        val lastScreenOff: Long = 0L,
        val lastScreenOn: Long = 0L,
        val lastProtection: Long = 0L
    )

    companion object {
        private const val TAG = "LockScreenService"
        private const val CHANNEL_ID = "prayerLockChannel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "KidsPrayer:LockScreenWakeLock"
        private const val PREFS_NAME = "lock_screen_prefs"
        private const val KEY_ACTIVE_PRAYER = "active_prayer"
        private const val KEY_RAKAAT_COUNT = "rakaat_count"
        private const val KEY_LOCK_ACTIVE = "lock_active"
        private const val KEY_START_TIME = "start_time"
        private const val KEY_IS_TEST_LOCK = "is_test_lock"
        private const val KEY_CAMERA_ACTIVE = "camera_active"
        private const val KEY_LAST_SCREEN_OFF = "last_screen_off"
        private const val KEY_PRAYER_COMPLETE = "prayer_complete"
        private const val KEY_PIN_VERIFIED = "pin_verified"
        private const val KEY_IS_UNLOCKED = "is_unlocked"
        private const val KEY_LAST_VISIBILITY_CHECK = "last_visibility_check"
        private const val WORK_TAG = "prayer_lock_monitor"
        
        private const val PROTECTION_CHECK_INTERVAL = 25L
        private const val MIN_SCREEN_OFF_INTERVAL = 50L
        private const val MAX_LOCK_DURATION = 48 * 60 * 60 * 1000L
        private const val PROTECTION_LAYERS = 5
        private const val QUICK_CHECK_INTERVAL = 25L
        private const val MIN_SHOW_INTERVAL = 100L
        private const val MIN_STATE_CHANGE_INTERVAL = 150L
        private const val WAKELOCK_REFRESH_INTERVAL = 30 * 60 * 1000L
        private const val SERVICE_RESTART_INTERVAL = 15 * 60 * 1000L
        private const val VISIBILITY_CHECK_INTERVAL = 250L
        private const val MAX_INVISIBLE_TIME = 1000L

        fun startService(context: Context, prayerName: String, rakaatCount: Int) {
            val intent = Intent(context, LockScreenService::class.java).apply {
                putExtra("prayer_name", prayerName)
                putExtra("rakaat_count", rakaatCount)
                action = if (prayerName == "Test Prayer") "test_lock" else "normal_lock"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, LockScreenService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        handler = Handler(Looper.getMainLooper())
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        initializeSystemServices()
        registerReceivers()
        createNotificationChannel()
        
        if (!isUnlocked()) {
            acquireWakeLock()
            startProtection()
        }
        
        // Initialize TimeSourceManager and register as listener
        timeSourceManager = TimeSourceManager.getInstance(applicationContext)
        timeSourceManager.addListener(this)
    }

    private fun startProtection() {
        protectionJobs.add(serviceScope.launch {
            while (isActive) {
                ensureLockScreenVisible()
                delay(100) // Check every 100ms
            }
        })

        protectionJobs.add(serviceScope.launch {
            while (isActive) {
                ensureServiceRunning()
                delay(500) // Check every 500ms
            }
        })
    }

    private suspend fun ensureLockScreenVisible() {
        if (SystemClock.elapsedRealtime() - lastVisibilityCheck < 50) return
        lastVisibilityCheck = SystemClock.elapsedRealtime()

        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(1)
            
            if (tasks.isNotEmpty()) {
                val topActivity = tasks[0].topActivity
                if (topActivity?.className != LockScreenActivity::class.java.name) {
                    consecutiveInvisibleChecks++
                    if (consecutiveInvisibleChecks >= MAX_INVISIBLE_CHECKS) {
                        forceLockScreen()
                        consecutiveInvisibleChecks = 0
                    }
                } else {
                    consecutiveInvisibleChecks = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking lock screen visibility", e)
        }
    }

    private fun forceLockScreen(recoveryPrayer: String? = null, recoveryRakaat: Int = 0, isRecovery: Boolean = false) {
        // Only force the lock screen if we haven't verified PIN or completed prayer
        if (isPinVerified || isPrayerCompleted) {
            Log.d(TAG, "Skipping lock screen force - PIN verified: $isPinVerified, Prayer completed: $isPrayerCompleted")
            return
        }
        
        // If recovery parameters provided, use them directly
        if (!recoveryPrayer.isNullOrEmpty() && recoveryRakaat > 0 && isRecovery) {
            Log.d(TAG, "Using recovery parameters for lockscreen: $recoveryPrayer with $recoveryRakaat rakaats")
            completionManager.setLockScreenActive(recoveryPrayer, recoveryRakaat)
            launchLockScreenActivity(recoveryPrayer, recoveryRakaat, true)
            return
        }
        
        // Check if lock screen is already active in the central state manager
        if (completionManager.isLockScreenActive()) {
            val activePrayer = completionManager.getActivePrayer()
            
            // Use active prayer from state manager if available
            if (activePrayer.first != null) {
                Log.d(TAG, "Using active prayer from state manager: ${activePrayer.first}")
                launchLockScreenActivity(activePrayer.first, activePrayer.second, false)
                return
            }
        }
        
        // Get prayer name and rakaat count from tracking variables or legacy prefs as fallback
        val prayerName = currentPrayerName 
                ?: prefs.getString(KEY_ACTIVE_PRAYER, null) 
                ?: PrayerValidator.PRAYER_UNKNOWN
        val rakaatCount = currentRakaatCount.takeIf { it > 0 } 
                ?: prefs.getInt(KEY_RAKAAT_COUNT, 0)
                ?: 4
        
        // Handle "Prayer Time" specially instead of marking it invalid
        if (prayerName == "Prayer Time") {
            Log.d(TAG, "Converting 'Prayer Time' to standard prayer name")
            val standardPrayer = PrayerValidator.getStandardPrayerName(prayerName)
            launchLockScreenActivity(standardPrayer, rakaatCount, false)
            return
        }
        
        // Validate prayer name before launching
        if (!PrayerValidator.isValidPrayerName(prayerName)) {
            Log.w(TAG, "Invalid prayer name detected: $prayerName. Attempting to recover valid prayer.")
            
            // Try to recover with last valid prayer from state manager
            val validPrayer = completionManager.getLastValidPrayer()
            if (validPrayer.first != null) {
                Log.d(TAG, "Recovered with valid prayer: ${validPrayer.first}")
                launchLockScreenActivity(validPrayer.first, validPrayer.second, true)
            } else {
                PrayerValidator.markInvalidPrayerData(this, "Invalid prayer name from LockScreenService")
            }
            return
        }
        
        // If we reach here, we have a valid prayer name but need to ensure it's in the central state
        completionManager.setLockScreenActive(prayerName, rakaatCount)
        launchLockScreenActivity(prayerName, rakaatCount, false)
    }

    private fun launchLockScreenActivity(prayerName: String? = null, rakaatCount: Int = 0, isRecovery: Boolean = false) {
        // Determine prayer info
        val finalPrayerName: String
        val finalRakaatCount: Int
        
        if (!prayerName.isNullOrEmpty() && rakaatCount > 0) {
            finalPrayerName = prayerName
            finalRakaatCount = rakaatCount
        } else {
            // Get from active state
            val prayerData = completionManager.getActivePrayer()
            finalPrayerName = prayerData.first ?: PrayerValidator.PRAYER_UNKNOWN
            finalRakaatCount = prayerData.second.takeIf { it > 0 } ?: 4
        }
        
        // Store as valid prayer state
        completionManager.storeValidPrayerState(finalPrayerName, finalRakaatCount)
        
        val lockIntent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra("prayer_name", finalPrayerName)
            putExtra("rakaat_count", finalRakaatCount)
            putExtra("from_service", true)
            if (isRecovery) {
                putExtra("is_recovery", true)
            }
        }
        
        try {
            // Update tracking variables
            currentPrayerName = finalPrayerName
            currentRakaatCount = finalRakaatCount
            
            startActivity(lockIntent)
            Log.d(TAG, "Lock screen activity launched with prayer: $finalPrayerName" + 
                  if (isRecovery) " (recovery from bypass)" else "")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching lock screen: ${e.message}")
        }
    }

    private suspend fun ensureServiceRunning() {
        if (SystemClock.elapsedRealtime() - lastServiceRestart < 1000) return
        lastServiceRestart = SystemClock.elapsedRealtime()

        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            var isServiceRunning = false
            
            @Suppress("DEPRECATION")
            am.getRunningServices(Integer.MAX_VALUE)?.forEach { service ->
                if (service.service.className == LockScreenService::class.java.name) {
                    isServiceRunning = true
                    return@forEach
                }
            }

            if (!isServiceRunning) {
                startService(Intent(this, LockScreenService::class.java))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring service is running", e)
        }
    }

    private fun initializeSystemServices() {
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground immediately to prevent ANR
        val prayerName = intent?.getStringExtra("prayer_name") ?: PrayerValidator.PRAYER_UNKNOWN
        startForeground(NOTIFICATION_ID, createNotification(prayerName))
        
        // Update current prayer info from intent if valid
        if (!prayerName.isNullOrEmpty() && prayerName != PrayerValidator.PRAYER_UNKNOWN) {
            val rakaatCount = intent?.getIntExtra("rakaat_count", 0) ?: 0
            if (rakaatCount > 0) {
                // Store the current prayer info
                currentPrayerName = prayerName
                currentRakaatCount = rakaatCount
            }
        }
        
        when (intent?.action) {
            "test_lock", "normal_lock" -> {
                if (!isUnlocked()) {
                    val rakaatCount = intent.getIntExtra("rakaat_count", 0)
                    
                    isTestLock = intent.action == "test_lock"
                    savePrayerState(prayerName, rakaatCount)
                    startProtection()
                }
            }
            "ACTION_FOCUS_LOST" -> {
                if (!isUnlocked()) {
                    serviceScope.launch {
                        stateMutex.withLock {
                            val currentTime = SystemClock.elapsedRealtime()
                            if (currentTime - lastProtectionCheck >= MIN_STATE_CHANGE_INTERVAL) {
                                lastProtectionCheck = currentTime
                                // Use our stored prayer information directly
                                val rakaatCount = currentRakaatCount.takeIf { it > 0 } 
                                    ?: prefs.getInt(KEY_RAKAAT_COUNT, 0)
                                showLockScreen(prayerName, rakaatCount)
                            }
                        }
                    }
                }
            }
            "ACTION_UNLOCK" -> {
                setUnlocked(true)
                isPinVerified = true
                stopProtection()
                stopSelf()
            }
            "ACTION_PRAYER_COMPLETE" -> {
                setUnlocked(true)
                isPrayerCompleted = true
                stopProtection()
                stopSelf()
            }
            "ACTION_STOP_LOCK" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        
        // Check for bypass and recover if needed
        if (completionManager.detectBypass()) {
            // Get the prayer name and rakaat count for recovery
            // If we have valid current values, prioritize those
            val recoveryPrayerName = if (!currentPrayerName.isNullOrEmpty() && currentPrayerName != PrayerValidator.PRAYER_UNKNOWN) {
                currentPrayerName
            } else {
                // Otherwise get from the completion manager
                val (lastPrayer, _) = completionManager.getLastValidPrayer()
                lastPrayer
            }
            
            val recoveryRakaatCount = if (currentRakaatCount > 0) {
                currentRakaatCount
            } else {
                val (_, lastRakaat) = completionManager.getLastValidPrayer()
                lastRakaat
            }
            
            if (!recoveryPrayerName.isNullOrEmpty() && recoveryRakaatCount > 0) {
                Log.w(TAG, "Recovering from lockscreen bypass with prayer: $recoveryPrayerName and $recoveryRakaatCount rakaats")
                
                // Force reactivation of lock screen with the recovered prayer info
                completionManager.setLockScreenActive(recoveryPrayerName, recoveryRakaatCount)
                
                // Force the lock screen to appear
                forceLockScreen(recoveryPrayerName, recoveryRakaatCount, true)
                
                // Clear the bypass detection
                completionManager.clearBypassDetection()
            }
        }

        return START_STICKY
    }

    private fun stopProtection() {
        protectionJobs.forEach { it.cancel() }
        protectionJobs.clear()
        
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing wake lock: ${e.message}")
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun showLockScreen(prayerName: String, rakaatCount: Int) {
        Log.d(TAG, "Showing lock screen for prayer: $prayerName")
        
        // Handle "Prayer Time" specially before validation
        val finalPrayerName = if (prayerName == "Prayer Time") {
            Log.d(TAG, "Converting 'Prayer Time' to standard prayer name in showLockScreen")
            PrayerValidator.getStandardPrayerName(prayerName)
        } else {
            prayerName
        }
        
        // Validate prayer name before launching
        if (!PrayerValidator.isValidPrayerName(finalPrayerName)) {
            Log.w(TAG, "Invalid prayer name detected: $finalPrayerName. Marking as invalid.")
            PrayerValidator.markInvalidPrayerData(this, "Invalid prayer name from LockScreenService.showLockScreen")
            return
        }
        
        val currentTime = SystemClock.elapsedRealtime()
        
        if (currentTime - lastLockScreenShow < MIN_SHOW_INTERVAL) {
            Log.d(TAG, "Skipping lock screen show due to MIN_SHOW_INTERVAL")
            return
        }
        
        lastLockScreenShow = currentTime

        val intent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra("prayer_name", finalPrayerName)
            putExtra("rakaat_count", rakaatCount)
        }
        
        // Enhance the intent with PrayerValidator
        PrayerValidator.enhanceLockScreenIntent(intent)

        try {
            startActivity(intent)
            Log.d(TAG, "Lock screen activity started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start lock screen activity", e)
            // Attempt recovery
            handler?.postDelayed({
                try {
                    startActivity(intent)
                    Log.d(TAG, "Lock screen recovery attempt successful")
                } catch (e: Exception) {
                    Log.e(TAG, "Lock screen recovery attempt failed", e)
                }
            }, 500)
        }
    }

    private fun handleScreenOff() {
        if (isUnlocked()) return
        
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastScreenStateChange < MIN_SCREEN_OFF_INTERVAL) return
        
        lastScreenStateChange = currentTime
        _serviceState.value = serviceState.value.copy(
            lastScreenOff = currentTime,
            isCameraActive = false
        )
        
        prefs.edit()
            .putLong(KEY_LAST_SCREEN_OFF, currentTime)
            .putBoolean(KEY_CAMERA_ACTIVE, false)
            .apply()
        
        acquireWakeLock()
    }

    private fun handleScreenOn() {
        if (isUnlocked() || !shouldContinueProtection()) return

        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastScreenStateChange < MIN_STATE_CHANGE_INTERVAL) return
        
        lastScreenStateChange = currentTime
        _serviceState.value = serviceState.value.copy(
            lastScreenOn = currentTime,
            isCameraActive = false
        )
        
        serviceScope.launch {
            delay(MIN_STATE_CHANGE_INTERVAL)
            val prayerName = prefs.getString(KEY_ACTIVE_PRAYER, PrayerValidator.PRAYER_UNKNOWN) ?: PrayerValidator.PRAYER_UNKNOWN
            val rakaatCount = prefs.getInt(KEY_RAKAAT_COUNT, 0)
            showLockScreen(prayerName, rakaatCount)
        }
    }

    private fun handleUserPresent() {
        if (isUnlocked() || !shouldContinueProtection()) return

        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastScreenStateChange < MIN_STATE_CHANGE_INTERVAL) return
        
        lastScreenStateChange = currentTime
        
        serviceScope.launch {
            delay(MIN_STATE_CHANGE_INTERVAL)
            val prayerName = prefs.getString(KEY_ACTIVE_PRAYER, PrayerValidator.PRAYER_UNKNOWN) ?: PrayerValidator.PRAYER_UNKNOWN
            val rakaatCount = prefs.getInt(KEY_RAKAAT_COUNT, 0)
            showLockScreen(prayerName, rakaatCount)
        }
    }

    private fun registerReceivers() {
        try {
            registerReceiver(screenStateReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receivers: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        if (!isUnlocked()) {
            try {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
                
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    WAKELOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                    acquire(MAX_LOCK_DURATION)
                }
                
                lastWakeLockRefresh = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring wake lock: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tracking_service_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.tracking_service_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(prayerName: String): Notification {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("prayer_name", prayerName)
            putExtra("rakaat_count", prefs.getInt(KEY_RAKAAT_COUNT, 0))
        }
        
        // Validate prayer name before creating notification
        if (!PrayerValidator.isValidPrayerName(prayerName)) {
            Log.w(TAG, "Invalid prayer name detected in notification: $prayerName")
            // Still create notification but with a default prayer name
            intent.putExtra("prayer_name", PrayerValidator.PRAYER_UNKNOWN)
        }
        
        // Enhance the intent with PrayerValidator
        PrayerValidator.enhanceLockScreenIntent(intent)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.prayer_in_progress, prayerName))
            .setSmallIcon(R.drawable.ic_lock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun shouldContinueProtection(): Boolean {
        if (isUnlocked()) return false
        
        val isComplete = prefs.getBoolean(KEY_PRAYER_COMPLETE, false)
        val isPinVerified = prefs.getBoolean(KEY_PIN_VERIFIED, false)
        if (isComplete || isPinVerified || isLockExpired()) {
            stopSelf()
            return false
        }
        return true
    }

    private fun isUnlocked(): Boolean {
        return prefs.getBoolean(KEY_IS_UNLOCKED, false)
    }

    private fun setUnlocked(unlocked: Boolean) {
        prefs.edit().putBoolean(KEY_IS_UNLOCKED, unlocked).apply()
        if (unlocked) {
            stopProtection()
        }
    }

    private fun savePrayerState(prayerName: String, rakaatCount: Int) {
        Log.d(TAG, "Saving prayer state: $prayerName with $rakaatCount rakaats")
        
        // Update our local tracking variables
        currentPrayerName = prayerName
        currentRakaatCount = rakaatCount
        
        // Save to preferences
        prefs.edit()
            .putString(KEY_ACTIVE_PRAYER, prayerName)
            .putInt(KEY_RAKAAT_COUNT, rakaatCount)
            .putBoolean(KEY_LOCK_ACTIVE, true)
            .putBoolean(KEY_PIN_VERIFIED, false)
            .putBoolean(KEY_PRAYER_COMPLETE, false)
            .apply()
        
        // Also update the service-wide prayer receiver prefs for consistent state
        getSharedPreferences("prayer_receiver_prefs", Context.MODE_PRIVATE).edit()
            .putString("active_prayer", prayerName)
            .putInt("active_rakaat_count", rakaatCount)
            .putBoolean("lock_screen_active", true)
            .putBoolean("is_unlocked", false)
            .putBoolean("pin_verified", false)
            .putBoolean("is_prayer_complete", false)
            .apply()
        
        _serviceState.value = ServiceState(
            isLockActive = true,
            isCameraActive = false,
            isPinVerified = false,
            lastScreenOff = SystemClock.elapsedRealtime(),
            lastScreenOn = SystemClock.elapsedRealtime(),
            lastProtection = SystemClock.elapsedRealtime()
        )
    }

    private fun isLockExpired(): Boolean {
        val startTime = prefs.getLong(KEY_START_TIME, 0)
        return System.currentTimeMillis() - startTime > MAX_LOCK_DURATION
    }

    private fun clearLockScreen() {
        prefs.edit().apply {
            remove(KEY_ACTIVE_PRAYER)
            remove(KEY_RAKAAT_COUNT)
            remove(KEY_LOCK_ACTIVE)
            remove(KEY_START_TIME)
            remove(KEY_IS_TEST_LOCK)
            remove(KEY_CAMERA_ACTIVE)
            remove(KEY_LAST_SCREEN_OFF)
            remove(KEY_IS_UNLOCKED)
            apply()
        }
        
        _serviceState.value = ServiceState()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        protectionJobs.forEach { it.cancel() }
        protectionJobs.clear()
        
        serviceScope.cancel()
        
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing wake lock: ${e.message}")
                }
            }
        }
        
        handler?.removeCallbacksAndMessages(null)
        
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers: ${e.message}")
        }
        
        if (isTestLock || prefs.getBoolean(KEY_IS_TEST_LOCK, false)) {
            clearLockScreen()
        }
        else if (!isUnlocked() && shouldContinueProtection()) {
           restartService()
        }
        
        // Unregister TimeChangeListener
        timeSourceManager.removeListener(this)
    }

    private fun restartService() {
        if (!isUnlocked() && shouldContinueProtection()) {
            val intent = Intent(this, LockScreenService::class.java).apply {
                action = if (isTestLock) "test_lock" else "normal_lock"
                putExtra("prayer_name", prefs.getString(KEY_ACTIVE_PRAYER, PrayerValidator.PRAYER_UNKNOWN))
                putExtra("rakaat_count", prefs.getInt(KEY_RAKAAT_COUNT, 0))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            if (isTestLock || prefs.getBoolean(KEY_IS_TEST_LOCK, false)) {
                clearLockScreen()
            }
            else if (!isUnlocked() && shouldContinueProtection()) {
                restartService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onTaskRemoved: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Implement TimeChangeListener method
    override fun onTimeChanged(significantChange: Boolean) {
        if (significantChange) {
            Log.d(TAG, "Significant time change detected, resetting prayer state")
            serviceScope.launch {
                resetStateAfterTimeChange()
            }
        }
    }
    
    private suspend fun resetStateAfterTimeChange() {
        stateMutex.withLock {
            // Clear cached prayer info
            currentPrayerName = null
            
            // Reset prayer state in preferences
            prefs.edit()
                .remove(KEY_ACTIVE_PRAYER)
                .apply()
                
            // Force reload current prayer state
            reloadPrayerState()
        }
    }
    
    /**
     * Reloads the prayer state after a time change or other significant event
     */
    private suspend fun reloadPrayerState() {
        try {
            // Try to find a valid prayer based on current time
            val validPrayer = findCurrentValidPrayer()
            if (validPrayer != null) {
                Log.d(TAG, "Found valid prayer after reload: $validPrayer")
                prefs.edit().putString(KEY_ACTIVE_PRAYER, validPrayer).apply()
                currentPrayerName = validPrayer
            } else {
                Log.d(TAG, "No valid prayer found during reload")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reloading prayer state: ${e.message}")
        }
    }
    
    private fun getActivePrayerName(): String {
        val prayerName = prefs.getString(KEY_ACTIVE_PRAYER, PrayerValidator.PRAYER_UNKNOWN) ?: PrayerValidator.PRAYER_UNKNOWN
        
        // Validate prayer name
        if (!PrayerValidator.isValidPrayerName(prayerName)) {
            Log.w(TAG, "Invalid prayer name detected: $prayerName. Attempting to recover valid prayer.")
            // Try to find a valid prayer instead
            val validPrayer = findCurrentValidPrayer()
            if (validPrayer != null) {
                // Update stored prayer with valid one
                prefs.edit().putString(KEY_ACTIVE_PRAYER, validPrayer).apply()
                return validPrayer
            }
            PrayerValidator.markInvalidPrayerData("Invalid prayer name from LockScreenService")
        }
        
        return prayerName
    }
    
    private fun findCurrentValidPrayer(): String? {
        // Implement logic to find the current valid prayer based on time
        // This is a fallback when we have an invalid prayer name
        try {
            val prayerScheduler = PrayerScheduler(applicationContext)
            val prayers = prayerScheduler.getCurrentPrayerTimes()
            val currentTime = timeSourceManager.getCurrentTime()
            
            // Find the current prayer (if any)
            val currentPrayer = prayers.filter { it.time <= currentTime }
                .maxByOrNull { it.time }
                
            return currentPrayer?.name
        } catch (e: Exception) {
            Log.e(TAG, "Error finding current valid prayer", e)
            return null
        }
    }
}
