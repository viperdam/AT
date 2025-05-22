package com.viperdam.kidsprayer.receivers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.viperdam.kidsprayer.services.LocationMonitorService

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed, starting services")
            
            // Before starting services, check permissions on Android 15+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 15
                val hasForegroundPermission = ActivityCompat.checkSelfPermission(
                    context, 
                    Manifest.permission.FOREGROUND_SERVICE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                val hasLocationPermission = ActivityCompat.checkSelfPermission(
                    context, 
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || 
                ActivityCompat.checkSelfPermission(
                    context, 
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                if (!hasForegroundPermission || !hasLocationPermission) {
                    Log.w(TAG, "Missing required permissions for location services on boot")
                    // Cannot request permissions from a BroadcastReceiver
                    // Instead, start the main activity which can request permissions
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    launchIntent?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        it.putExtra("request_location_permissions", true)
                        context.startActivity(it)
                    }
                    return
                }
            }
            
            // Start the LocationMonitorService
            val locationServiceIntent = Intent(context, LocationMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(locationServiceIntent)
                Log.d(TAG, "Started LocationMonitorService as foreground service")
            } else {
                context.startService(locationServiceIntent)
                Log.d(TAG, "Started LocationMonitorService")
            }
        }
    }
} 