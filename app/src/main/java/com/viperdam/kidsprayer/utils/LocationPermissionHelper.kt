package com.viperdam.kidsprayer.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.viperdam.kidsprayer.R
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class LocationPermissionHelper(private val activity: FragmentActivity) {

    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var settingsCallback: ((Boolean) -> Unit)? = null
    private var gpsMonitoringActive = false

    // Add this property to track if we're showing the dialog to prevent multiple dialogs
    private var isShowingLocationDialog = false

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        permissionCallback?.invoke(allGranted)
    }

    private val locationSettingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        settingsCallback?.invoke(result.resultCode == Activity.RESULT_OK)
    }

    private val backgroundPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onBackgroundPermissionResult(granted)
    }
    
    private var onBackgroundPermissionResult: (Boolean) -> Unit = {}

    suspend fun checkAndRequestLocationPermissions(
        onPermissionResult: (Boolean) -> Unit
    ) {
        when {
            hasLocationPermissions() -> {
                // Check if location is enabled
                if (checkLocationSettings()) {
                    onPermissionResult(true)
                } else {
                    requestLocationSettings { enabled ->
                        onPermissionResult(enabled)
                    }
                }
            }
            shouldShowRationale() -> {
                showPermissionRationale { accepted ->
                    if (accepted) {
                        requestLocationPermissions(onPermissionResult)
                    } else {
                        onPermissionResult(false)
                    }
                }
            }
            else -> {
                requestLocationPermissions(onPermissionResult)
            }
        }
    }

    private fun hasLocationPermissions(): Boolean {
        return FOREGROUND_LOCATION_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun shouldShowRationale(): Boolean {
        return FOREGROUND_LOCATION_PERMISSIONS.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
    }

    private fun showPermissionRationale(onResult: (Boolean) -> Unit) {
        AlertDialog.Builder(activity, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(activity.getString(R.string.location_permission_title))
            .setMessage(activity.getString(R.string.location_permission_rationale))
            .setPositiveButton(android.R.string.ok) { dialog, _ -> 
                dialog.dismiss()
                onResult(true) 
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> 
                dialog.dismiss()
                onResult(false) 
            }
            .setCancelable(false)
            .show()
    }

    private fun requestLocationPermissions(onResult: (Boolean) -> Unit) {
        permissionCallback = onResult
        permissionLauncher.launch(FOREGROUND_LOCATION_PERMISSIONS.toTypedArray())
    }

    private suspend fun checkLocationSettings(): Boolean {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            TimeUnit.MINUTES.toMillis(5)
        ).build()

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        try {
            LocationServices.getSettingsClient(activity)
                .checkLocationSettings(builder.build())
                .await()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun startMonitoringGPS() {
        if (gpsMonitoringActive) return
        gpsMonitoringActive = true
        
        val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        LocationServices.getFusedLocationProviderClient(activity)
            .lastLocation
            .addOnSuccessListener { location ->
                if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                    !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    showEnableLocationDialog()
                }
            }

        // Start monitoring GPS status changes
        LocationServices.getFusedLocationProviderClient(activity).locationAvailability
            .addOnSuccessListener { availability ->
                if (!availability.isLocationAvailable) {
                    showEnableLocationDialog()
                }
            }
    }

    fun stopMonitoringGPS() {
        gpsMonitoringActive = false
    }

    private fun showEnableLocationDialog() {
        if (isShowingLocationDialog) return
        isShowingLocationDialog = true

        AlertDialog.Builder(activity, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(activity.getString(R.string.location_disabled))
            .setMessage(activity.getString(R.string.enable_location_message))
            .setPositiveButton(activity.getString(R.string.enable)) { dialog, _ ->
                dialog.dismiss()
                requestLocationSettings { enabled ->
                    isShowingLocationDialog = false
                    if (!enabled && gpsMonitoringActive) {
                        // If still not enabled and monitoring is active, show dialog again after a delay
                        activity.window.decorView.postDelayed({
                            showEnableLocationDialog()
                        }, 30000) // Show dialog again after 30 seconds if GPS is still off
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                isShowingLocationDialog = false
            }
            .setOnDismissListener {
                isShowingLocationDialog = false
            }
            .show()
    }

    private fun requestLocationSettings(onResult: (Boolean) -> Unit) {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            TimeUnit.MINUTES.toMillis(5)
        ).build()

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        LocationServices.getSettingsClient(activity)
            .checkLocationSettings(builder.build())
            .addOnSuccessListener {
                onResult(true)
            }
            .addOnFailureListener { exception ->
                when (exception) {
                    is ResolvableApiException -> {
                        try {
                            settingsCallback = onResult
                            locationSettingsLauncher.launch(
                                IntentSenderRequest.Builder(exception.resolution.intentSender).build()
                            )
                        } catch (e: Exception) {
                            onResult(false)
                        }
                    }
                    else -> onResult(false)
                }
            }
    }

    fun openAppSettings() {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
            activity.startActivity(this)
        }
    }

    fun requestBackgroundLocationIfNeeded(onResult: (Boolean) -> Unit) {
        // Only applicable for Android 10+ (API level 29+)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onResult(true) // Not needed on older Android versions
            return
        }
        
        // First check if we have foreground permissions
        val hasForegroundPermissions = FOREGROUND_LOCATION_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
        
        if (!hasForegroundPermissions) {
            onResult(false) // Need foreground permissions first
            return
        }
        
        // Check if we already have background permission
        val hasBackgroundPermission = ContextCompat.checkSelfPermission(
            activity, 
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        if (hasBackgroundPermission) {
            onResult(true) // Already have background permission
            return
        }
        
        // Check if we should show rationale
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            activity, 
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        
        if (shouldShowRationale) {
            showBackgroundPermissionRationale { accepted ->
                if (accepted) {
                    launchBackgroundPermissionRequest(onResult)
                } else {
                    onResult(false)
                }
            }
        } else {
            // Show explanation dialog before requesting permission
            showBackgroundPermissionExplanation { proceed ->
                if (proceed) {
                    launchBackgroundPermissionRequest(onResult)
                } else {
                    onResult(false)
                }
            }
        }
    }
    
    private fun showBackgroundPermissionExplanation(onResult: (Boolean) -> Unit) {
        AlertDialog.Builder(activity, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(activity.getString(R.string.background_location_title))
            .setMessage(activity.getString(R.string.background_location_explanation))
            .setPositiveButton(android.R.string.ok) { dialog, _ -> 
                dialog.dismiss()
                onResult(true) 
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> 
                dialog.dismiss()
                onResult(false) 
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showBackgroundPermissionRationale(onResult: (Boolean) -> Unit) {
        AlertDialog.Builder(activity, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(activity.getString(R.string.background_location_rationale_title))
            .setMessage(activity.getString(R.string.background_location_rationale))
            .setPositiveButton(android.R.string.ok) { dialog, _ -> 
                dialog.dismiss()
                onResult(true) 
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> 
                dialog.dismiss()
                onResult(false) 
            }
            .setCancelable(false)
            .show()
    }
    
    private fun launchBackgroundPermissionRequest(onResult: (Boolean) -> Unit) {
        onBackgroundPermissionResult = onResult
        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    companion object {
        private val FOREGROUND_LOCATION_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        private val REQUIRED_PERMISSIONS = FOREGROUND_LOCATION_PERMISSIONS
    }
} 