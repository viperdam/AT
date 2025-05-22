package com.viperdam.kidsprayer.ui

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.viperdam.kidsprayer.R
import java.util.concurrent.TimeUnit

class LocationDialogActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "LocationDialogActivity"
        private const val REQUEST_CHECK_SETTINGS = 1001
        
        // Static flag to prevent multiple dialogs from showing at once
        @Volatile
        private var isDialogShowing = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            // No setContentView - we don't need any layout for this activity
            
            if (isDialogShowing) {
                Log.d(TAG, "Dialog already showing, finishing duplicate activity")
                finish()
                return
            }
            
            // Directly show location settings
            promptLocationSettings()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            // If anything fails, just open the system location settings directly
            openSystemLocationSettings()
        }
    }
    
    /**
     * Handles new intents when the activity is already running
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // If we get a new intent while running, just ignore it
        Log.d(TAG, "Received new intent, ignoring")
    }
    
    private fun promptLocationSettings() {
        try {
            if (isDialogShowing) {
                Log.d(TAG, "Dialog already being shown by another instance")
                finish()
                return
            }
            
            isDialogShowing = true
            
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                TimeUnit.MINUTES.toMillis(5)
            ).build()

            val builder = LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true)

            LocationServices.getSettingsClient(this)
                .checkLocationSettings(builder.build())
                .addOnSuccessListener {
                    // Location settings are already satisfied
                    Log.d(TAG, "Location settings already satisfied")
                    isDialogShowing = false
                    finish()
                }
                .addOnFailureListener { exception ->
                    if (exception is ResolvableApiException) {
                        try {
                            // Skip custom dialog and directly show the system dialog
                            // This avoids theme issues while still giving users the location prompt
                            Log.d(TAG, "Showing system location dialog directly")
                            exception.startResolutionForResult(this, REQUEST_CHECK_SETTINGS)
                        } catch (e: IntentSender.SendIntentException) {
                            Log.e(TAG, "Error showing system location dialog", e)
                            isDialogShowing = false
                            openSystemLocationSettings()
                        }
                    } else {
                        Log.e(TAG, "Unresolvable location settings issue", exception)
                        isDialogShowing = false
                        openSystemLocationSettings()
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error in promptLocationSettings", e)
            isDialogShowing = false
            openSystemLocationSettings()
        }
    }
    
    private fun openSystemLocationSettings() {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open location settings", e)
        } finally {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        isDialogShowing = false
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Ensure flag is reset when activity is destroyed
        isDialogShowing = false
    }
} 