package com.viperdam.kidsprayer.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TimeSourceManager - provides a consistent time source and detects changes
 * Uses NTP for network time and device time as fallback.
 */
class TimeSourceManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "TimeSourceManager"
        private const val PREFS_NAME = "time_source_prefs"
        private const val KEY_LAST_TIME_CHECK = "last_time_check"
        private const val KEY_TIME_SOURCE = "time_source"
        private const val KEY_LAST_NTP_TIME = "last_ntp_time"
        private const val KEY_LAST_DEVICE_TIME = "last_device_time"
        private const val KEY_TIME_OFFSET = "time_offset"
        
        private const val TIME_SOURCE_NETWORK = "network"
        private const val TIME_SOURCE_DEVICE = "device"
        
        // Threshold to detect significant time changes (1 minute)
        private const val TIME_CHANGE_THRESHOLD_MS = 60 * 1000L
        // How often to sync with NTP (15 minutes)
        private const val NTP_SYNC_INTERVAL_MS = 15 * 60 * 1000L
        
        @Volatile private var instance: TimeSourceManager? = null
        
        fun getInstance(context: Context): TimeSourceManager {
            return instance ?: synchronized(this) {
                instance ?: TimeSourceManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val listeners = mutableListOf<TimeChangeListener>()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var currentTimeSource = prefs.getString(KEY_TIME_SOURCE, TIME_SOURCE_DEVICE) ?: TIME_SOURCE_DEVICE
    private var lastTimeCheck = prefs.getLong(KEY_LAST_TIME_CHECK, System.currentTimeMillis())
    
    // Time offset between device time and network time (in milliseconds)
    private var timeOffset = prefs.getLong(KEY_TIME_OFFSET, 0L)
    private val checkingTime = AtomicBoolean(false)
    
    // Used for periodic NTP syncing
    private val handler = Handler(Looper.getMainLooper())
    private val ntpSyncRunnable = object : Runnable {
        override fun run() {
            checkTimeSource()
            handler.postDelayed(this, NTP_SYNC_INTERVAL_MS)
        }
    }
    
    // Define the broadcast receiver before using it
    private val timeChangeBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Time change detected: ${intent.action}")
            checkTimeSource()
        }
    }
    
    // Register for time and timezone broadcasts
    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(timeChangeBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(timeChangeBroadcastReceiver, filter)
        }
        
        // Initial check
        checkTimeSource()
        
        // Schedule periodic checks
        handler.postDelayed(ntpSyncRunnable, NTP_SYNC_INTERVAL_MS)
    }
    
    /**
     * Get the current time from the preferred time source
     * This is the main method that should be used throughout the app
     */
    fun getCurrentTime(): Long {
        return when (currentTimeSource) {
            TIME_SOURCE_NETWORK -> {
                // Apply offset to device time to get approximated network time
                System.currentTimeMillis() + timeOffset
            }
            else -> {
                // Use device time as fallback
                System.currentTimeMillis()
            }
        }
    }
    
    /**
     * Add a listener to be notified of time changes
     */
    fun addListener(listener: TimeChangeListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }
    
    /**
     * Remove a previously added listener
     */
    fun removeListener(listener: TimeChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
    
    /**
     * Check time source and update if needed
     * This is automatically called periodically and when time changes are detected
     */
    fun checkTimeSource() {
        // Avoid concurrent time checks
        if (checkingTime.getAndSet(true)) {
            Log.d(TAG, "Time check already in progress, skipping")
            return
        }
        
        try {
            val deviceTimeMs = System.currentTimeMillis()
            val lastDeviceTimeMs = prefs.getLong(KEY_LAST_DEVICE_TIME, deviceTimeMs)
            
            // First check for device time inconsistency
            if (Math.abs(deviceTimeMs - lastDeviceTimeMs) > TIME_CHANGE_THRESHOLD_MS && 
                !isExpectedTimeDifference(lastDeviceTimeMs, deviceTimeMs)) {
                Log.d(TAG, "Significant device time change detected: Last=${Date(lastDeviceTimeMs)}, Current=${Date(deviceTimeMs)}")
                notifyTimeChanged(true)
            }
            
            // Update last device time
            prefs.edit().putLong(KEY_LAST_DEVICE_TIME, deviceTimeMs).apply()
            
            // Try to get network time via NTP
            val ntpClient = SntpClient()
            var networkTimeMs: Long? = null
            var newTimeSource = TIME_SOURCE_DEVICE
            
            if (ntpClient.requestTime("time.google.com", 5000)) {
                networkTimeMs = ntpClient.getNtpTime()
                
                // Ensure network time is not null AND positive before proceeding
                if (networkTimeMs != null && networkTimeMs > 0) {
                    // Network time available, check if significantly different from device time
                    val newOffset = networkTimeMs - deviceTimeMs
                    val lastNtpTime = prefs.getLong(KEY_LAST_NTP_TIME, 0L)
                    var significantChangeDetected = false
                    
                    // Check if there's a significant difference from current offset
                    if (Math.abs(newOffset - timeOffset) > TIME_CHANGE_THRESHOLD_MS) {
                        Log.d(TAG, "Significant time difference detected: " +
                                   "Device=${Date(deviceTimeMs)}, " +
                                   "Network=${Date(networkTimeMs)}, " +
                                   "New offset=${newOffset}ms")
                        
                        // Update time offset
                        timeOffset = newOffset
                        prefs.edit().putLong(KEY_TIME_OFFSET, timeOffset).apply()
                        
                        // If offset changed significantly, mark it as a significant change
                        significantChangeDetected = true
                    }
                    
                    // Store this NTP time for future reference
                    prefs.edit().putLong(KEY_LAST_NTP_TIME, networkTimeMs).apply()
                    
                    // Notify only if a significant change was flagged
                    if (significantChangeDetected) {
                         notifyTimeChanged(true)
                    }
                    
                    newTimeSource = TIME_SOURCE_NETWORK
                }
            }
            
            // Save the current check time
            lastTimeCheck = deviceTimeMs
            prefs.edit()
                .putLong(KEY_LAST_TIME_CHECK, lastTimeCheck)
                .putString(KEY_TIME_SOURCE, newTimeSource)
                .apply()
            
            // Check if time source changed
            if (newTimeSource != currentTimeSource) {
                Log.d(TAG, "Time source changed from $currentTimeSource to $newTimeSource")
                currentTimeSource = newTimeSource
                notifyTimeChanged(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking time source", e)
        } finally {
            checkingTime.set(false)
        }
    }
    
    private fun isExpectedTimeDifference(lastTime: Long, currentTime: Long): Boolean {
        // Check if the difference is close to a multiple of our sync interval
        val diff = currentTime - lastTime
        return Math.abs(diff % NTP_SYNC_INTERVAL_MS) < TIME_CHANGE_THRESHOLD_MS
    }
    
    private fun notifyTimeChanged(significantChange: Boolean) {
        synchronized(listeners) {
            for (listener in listeners) {
                try {
                    listener.onTimeChanged(significantChange)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying listener", e)
                }
            }
        }
    }
    
    interface TimeChangeListener {
        fun onTimeChanged(significantChange: Boolean)
    }
}

/**
 * SNTP client for network time synchronization
 */
class SntpClient {
    private var ntpTimeValue: Long = 0

    fun requestTime(host: String, timeout: Int): Boolean {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = timeout

            val address = InetAddress.getByName(host)
            val buffer = ByteArray(48)
            buffer[0] = 0x1B // NTP request

            val packet = DatagramPacket(buffer, buffer.size, address, 123)
            val requestTime = System.currentTimeMillis()
            socket.send(packet)
            socket.receive(packet)

            val responseTime = System.currentTimeMillis()
            val secondsSince1900 = ((buffer[40].toLong() and 0xFF) shl 24) or
                                 ((buffer[41].toLong() and 0xFF) shl 16) or
                                 ((buffer[42].toLong() and 0xFF) shl 8) or
                                 (buffer[43].toLong() and 0xFF)

            val secondsSince1970 = secondsSince1900 - 2208988800L
            ntpTimeValue = (secondsSince1970 * 1000) + ((responseTime - requestTime) / 2)
            return true
        } catch (e: Exception) {
            Log.e("SntpClient", "Error getting time: ${e.message}")
            return false
        } finally {
            socket?.close()
        }
    }

    fun getNtpTime(): Long {
        return ntpTimeValue
    }
} 