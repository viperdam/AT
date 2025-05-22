package com.viperdam.kidsprayer.prayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CancellationException
import java.util.*
import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.tasks.Task
import java.util.concurrent.TimeUnit
import com.google.android.gms.tasks.CancellationTokenSource
import android.os.Handler

// Custom exception for location settings being disabled
class LocationSettingsDisabledException(val resolvableApiException: ResolvableApiException) : Exception("Location settings are disabled.")

class LocationManager(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    
    private val sharedPrefs = context.getSharedPreferences("location_prefs", Context.MODE_PRIVATE)
    
    // Modern location request configuration
    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        TimeUnit.MINUTES.toMillis(5) // Update interval: 5 minutes
    ).apply {
        setMinUpdateIntervalMillis(TimeUnit.MINUTES.toMillis(3)) // Minimum 3 minutes
        setMaxUpdateDelayMillis(TimeUnit.MINUTES.toMillis(10))   // Maximum 10 minutes
        setWaitForAccurateLocation(false)                        // Don't wait for high accuracy
        setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)    // Balance accuracy and battery
    }.build()

    private var locationCallback: ((Boolean) -> Unit)? = null

    companion object {
        private const val TAG = "LocationManager"
        private val LOCATION_TIMEOUT = TimeUnit.SECONDS.toMillis(30) // 30 seconds timeout
    }

    suspend fun getLastLocation(): Location? {
        return try {
            when {
                checkLocationPermission() -> {
                    // Try to get current location with timeout
                    val cancellationTokenSource = CancellationTokenSource()
                    try {
                        val currentLocation = fusedLocationClient
                            .getCurrentLocation(
                                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                cancellationTokenSource.token
                            )
                            .await()

                        when (currentLocation) {
                            null -> getLocationFromCache()
                            else -> {
                                saveLocationToCache(currentLocation)
                                currentLocation
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting current location", e)
                        getLocationFromCache()
                    } finally {
                        cancellationTokenSource.cancel()
                    }
                }
                else -> getLocationFromCache()
            }
        } catch (e: Exception) {
            when (e) {
                is CancellationException -> throw e
                else -> {
                    Log.e(TAG, "Error in getLastLocation", e)
                    getLocationFromCache()
                }
            }
        }
    }

    fun getLocationUpdates(): Flow<Location> = callbackFlow {
        if (!checkLocationPermission()) {
            getLastLocation()?.let { cachedLocation -> 
                trySend(cachedLocation)
            }
            close()
            return@callbackFlow
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    try {
                        saveLocationToCache(location)
                        trySend(location)
                    } catch (e: Exception) {
                        when (e) {
                            is CancellationException -> throw e
                            else -> Log.e(TAG, "Error in location callback", e)
                        }
                    }
                }
            }
        }

        try {
            val locationSettingsRequest = LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true)
                .build()

            LocationServices.getSettingsClient(context)
                .checkLocationSettings(locationSettingsRequest)
                .addOnSuccessListener {
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest,
                        callback,
                        Looper.getMainLooper()
                    )
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Location settings check failed", exception)
                    if (exception is ResolvableApiException) {
                        // Specific error for disabled settings
                        close(LocationSettingsDisabledException(exception))
                    } else {
                        // Other settings errors
                        close(exception)
                    }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
            close(e)
        }

        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }

    fun getTimeZone(): TimeZone {
        return TimeZone.getDefault()
    }

    private fun checkLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    private fun saveLocationToCache(location: Location) {
        sharedPrefs.edit().apply {
            putFloat("lat", location.latitude.toFloat())
            putFloat("lon", location.longitude.toFloat())
            putLong("timestamp", System.currentTimeMillis())
            putFloat("accuracy", location.accuracy)
            putLong("time", location.time)
            apply()
        }
    }

    private fun getLocationFromCache(): Location? {
        val lat = sharedPrefs.getFloat("lat", Float.NaN)
        val lon = sharedPrefs.getFloat("lon", Float.NaN)
        val timestamp = sharedPrefs.getLong("timestamp", 0L)
        val accuracy = sharedPrefs.getFloat("accuracy", 0f)
        val time = sharedPrefs.getLong("time", 0L)
        
        return when {
            lat.isNaN() || lon.isNaN() -> null
            else -> Location("cache").apply {
                latitude = lat.toDouble()
                longitude = lon.toDouble()
                this.accuracy = accuracy
                this.time = time
            }
        }
    }

    /**
     * Get the last known location synchronously (non-suspending function)
     * This is less ideal than the suspending version but necessary for some calls
     * 
     * @return The last known location or null if not available
     */
    fun getLastLocationSync(): Location? {
        // First try to use the cached location
        val cachedLat = sharedPrefs.getFloat("lat", 0f)
        val cachedLong = sharedPrefs.getFloat("lon", 0f)
        val cacheTime = sharedPrefs.getLong("timestamp", 0L)
        
        // Check if we have a valid cached location - no longer check age
        if (cachedLat != 0f && cachedLong != 0f && cacheTime > 0) {
            // Removing age verification - always use cached location if available
            val location = Location("cache")
            location.latitude = cachedLat.toDouble()
            location.longitude = cachedLong.toDouble()
            return location
        }
        
        // Try to get the last known location from the system
        return try {
            getLastKnownLocationSync()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last location sync: ${e.message}", e)
            null
        }
    }
    
    /**
     * Internal implementation to get the last known location synchronously
     */
    @SuppressLint("MissingPermission")
    private fun getLastKnownLocationSync(): Location? {
        // Check if we have location permissions
        if (!checkLocationPermission()) {
            return getDefaultLocation()
        }
        
        try {
            // Try to get location from FusedLocationProvider
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            var lastLocation: Location? = null
            
            // Use the last known location if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Use Tasks.await but in a non-suspending way
                try {
                    // Get last location synchronously - this will block the thread
                    val task = fusedLocationClient.lastLocation
                    lastLocation = Tasks.await(task, 5, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting last location: ${e.message}")
                }
            } else {
                // For older devices, we need to get the best provider and request the last known location
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                
                // Check for network provider
                if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    lastLocation = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                }
                
                // If no location from network, try GPS
                if (lastLocation == null && locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    lastLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                }
            }
            
            // If we have a location, store it in cache
            if (lastLocation != null) {
                saveLocationToCache(lastLocation)
                return lastLocation
            }
            
            // Fall back to default location if no last known location
            return getDefaultLocation()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting location synchronously: ${e.message}", e)
            return getDefaultLocation()
        }
    }

    fun getDefaultLocation(): Location {
        return Location("default").apply {
            latitude = 21.422487  // Mecca latitude
            longitude = 39.826206 // Mecca longitude
            accuracy = 2000f      // 2km accuracy for default location
            time = System.currentTimeMillis()
        }
    }

    /**
     * Gets the current location using a suspending function.
     * This is more modern than using Tasks directly.
     * 
     * @return Current location or null if not available
     * @throws LocationSettingsDisabledException if location settings are disabled
     * @throws SecurityException if location permissions are not granted
     */
    suspend fun getCurrentLocation(): Location? {
        if (!checkLocationPermission()) {
            throw SecurityException("Location permission not granted")
        }
        
        // First check if location is enabled
        val locationSettingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()
            
        try {
            // Check location settings
            LocationServices.getSettingsClient(context)
                .checkLocationSettings(locationSettingsRequest)
                .await()
                
            // Settings are enabled, proceed to get location
            val cancellationTokenSource = CancellationTokenSource()
            try {
                return fusedLocationClient
                    .getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        cancellationTokenSource.token
                    )
                    .await()
                    ?.also { saveLocationToCache(it) }
                    ?: getLocationFromCache() // Return cached location if current is null
            } finally {
                cancellationTokenSource.cancel()
            }
        } catch (e: Exception) {
            when (e) {
                is ApiException -> {
                    // Check if this is a resolvable location settings exception
                    if (e is ResolvableApiException) {
                        throw LocationSettingsDisabledException(e)
                    }
                    Log.e(TAG, "API Exception getting location", e)
                    return getLocationFromCache()
                }
                is CancellationException -> throw e
                else -> {
                    Log.e(TAG, "Error getting current location", e)
                    return getLocationFromCache()
                }
            }
        }
    }

    fun startMonitoringGPS(callback: (Boolean) -> Unit) {
        locationCallback = callback
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        try {
            locationManager.registerGnssStatusCallback(
                object : android.location.GnssStatus.Callback() {
                    override fun onStarted() {
                        locationCallback?.invoke(true)
                    }

                    override fun onStopped() {
                        locationCallback?.invoke(false)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error registering GNSS callback", e)
        }
    }

    fun stopMonitoringGPS() {
        locationCallback = null
    }

    fun requestLocationEnable(): Task<LocationSettingsResponse> {
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)  // This forces the dialog to show every time

        return LocationServices.getSettingsClient(context)
            .checkLocationSettings(builder.build())
    }
}
