package com.viperdam.kidsprayer.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility class to help with device identification for ad testing
 */
object DeviceInfoUtil {
    private const val TAG = "DeviceInfoUtil"
    
    /**
     * Get information about the current device that can be used for ad testing
     * This should only be used during development/debugging
     */
    suspend fun getDeviceInfo(context: Context): Map<String, String> {
        val deviceInfo = mutableMapOf<String, String>()
        
        try {
            // Basic device info
            deviceInfo["model"] = "${Build.MANUFACTURER} ${Build.MODEL}"
            deviceInfo["android_version"] = Build.VERSION.RELEASE
            deviceInfo["android_sdk"] = Build.VERSION.SDK_INT.toString()
            deviceInfo["build_type"] = if (isDebugBuild(context)) "DEBUG" else "RELEASE"
            
            // Device IDs
            deviceInfo["android_id"] = getAndroidId(context)
            
            // Try to get advertising ID (requires Google Play Services)
            try {
                deviceInfo["advertising_id"] = withContext(Dispatchers.IO) {
                    AdvertisingIdClient.getAdvertisingIdInfo(context).id ?: "Unknown"
                }
            } catch (e: Exception) {
                when (e) {
                    is GooglePlayServicesNotAvailableException -> 
                        deviceInfo["advertising_id"] = "Google Play Services not available"
                    else -> 
                        deviceInfo["advertising_id"] = "Error: ${e.message}"
                }
                Log.e(TAG, "Error getting advertising ID", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting device info", e)
            deviceInfo["error"] = e.message ?: "Unknown error"
        }
        
        return deviceInfo
    }
    
    /**
     * Get a formatted string of device info for logging
     */
    suspend fun getDeviceInfoForLogging(context: Context): String {
        val info = getDeviceInfo(context)
        return buildString {
            appendLine("======== DEVICE INFO FOR AD TESTING ========")
            info.forEach { (key, value) ->
                appendLine("$key: $value")
            }
            appendLine("============================================")
        }
    }
    
    /**
     * Check if we're running a debug build
     */
    private fun isDebugBuild(context: Context): Boolean {
        return context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
    
    /**
     * Get the Android ID
     */
    private fun getAndroidId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }
} 