package com.viperdam.kidsprayer.ui.main

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viperdam.kidsprayer.model.Prayer
import com.viperdam.kidsprayer.prayer.LocationManager as AppLocationManager
import com.viperdam.kidsprayer.prayer.PrayerTimeCalculator
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.service.PrayerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.* // Import flow operators
import javax.inject.Inject
import java.util.*
import com.google.android.gms.common.api.ResolvableApiException
import com.viperdam.kidsprayer.prayer.LocationSettingsDisabledException
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationManager: AppLocationManager,
    private val prayerTimeCalculator: PrayerTimeCalculator,
    private val prayerCompletionManager: PrayerCompletionManager,
    private val prayerScheduler: PrayerScheduler
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        private val LOCATION_FETCH_TIMEOUT = 15.seconds // Increased timeout from 10 to 15 seconds
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    // Splash screen readiness state
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    var locationUpdateJob: Job? = null

    data class UiState(
        val prayers: List<PrayerUiModel> = emptyList(),
        val nextPrayer: Prayer? = null,
        val isLoading: Boolean = true,
        val error: String? = null,
        val locationSettingsResolution: ResolvableApiException? = null // For prompting user
    )

    data class PrayerUiModel(
        val prayer: Prayer,
        val isComplete: Boolean = false,
        val completionType: PrayerCompletionManager.CompletionType? = null,
        val isMissed: Boolean = false,
        val isCurrentPrayer: Boolean = false
    )

    // Don't start monitoring automatically on init
    // init { }

    fun startMonitoring() {
        // Cancel any existing location update job
        if (locationUpdateJob?.isActive == true) {
             Log.d(TAG, "startMonitoring called but already active.")
             return
        }
        Log.d(TAG, "Starting location updates flow collection.")
        locationUpdateJob = viewModelScope.launch {
            try {
                locationManager.getLocationUpdates()
                    .catch { e -> // Catch errors from the flow itself (e.g., settings disabled)
                        handleLocationError(e, "Error collecting location updates")
                    }
                    .collect { newLocation -> // Collect successful emissions
                        try {
                            Log.d(TAG, "Received location update via flow.")
                            _uiState.update { it.copy(locationSettingsResolution = null, error = null, isLoading = true) }
                            updatePrayerTimes(newLocation)
                        } catch (e: Exception) {
                            handleLocationError(e, "Error processing location update")
                        } finally {
                             _uiState.update { it.copy(isLoading = false) }
                        }
                    }
            } finally {
                 if (coroutineContext.isActive) {
                    _uiState.update { it.copy(isLoading = false) }
                 }
            }
        }
    }

    fun refreshPrayerTimes() {
        viewModelScope.launch {
            var location: Location? = null
            var errorMsg: String? = null
            var resolution: ResolvableApiException? = null

            try {
                Log.d(TAG, "refreshPrayerTimes called.")
                _uiState.update { it.copy(isLoading = true, error = null, locationSettingsResolution = null) }

                // 1. Try last known location (which now never expires from cache)
                location = locationManager.getLastLocation()
                Log.d(TAG, "Last known location: ${location?.latitude}, ${location?.longitude}")

                // 2. If null, try current location with timeout
                if (location == null) {
                    Log.w(TAG, "Last known location null, trying current location...")
                    try {
                        location = withTimeout(LOCATION_FETCH_TIMEOUT) {
                            locationManager.getCurrentLocation()
                        }
                        Log.d(TAG, "Successfully retrieved current location: ${location?.latitude}, ${location?.longitude}")
                    } catch (timeout: TimeoutCancellationException) {
                        Log.e(TAG, "Timeout getting current location.", timeout)
                        errorMsg = "Could not get location quickly."
                    } catch (e: SecurityException) {
                         Log.e(TAG, "Permission error getting current location.", e)
                         errorMsg = "Location permission denied."
                    } catch (e: Exception) {
                         Log.e(TAG, "Error getting current location.", e)
                         if (e is LocationSettingsDisabledException) {
                             resolution = e.resolvableApiException
                             errorMsg = "Please enable location services"
                         } else {
                             errorMsg = "Error getting location."
                         }
                    }
                }

                // 3. If still null, try collecting flow briefly (fallback)
                if (location == null && resolution == null) { // Don't try flow if settings need resolution
                    Log.w(TAG, "Current location failed, trying flow briefly...")
                    try {
                        location = withTimeoutOrNull(LOCATION_FETCH_TIMEOUT / 2) { // Shorter timeout for flow
                            locationManager.getLocationUpdates().firstOrNull() // Get first emission or null
                        }
                        Log.d(TAG, "Flow location result: ${location?.latitude}, ${location?.longitude}")
                    } catch (e: Exception) {
                         handleLocationError(e, "Error getting location from flow")
                         // Error might set resolution or specific error message
                         errorMsg = _uiState.value.error ?: errorMsg // Keep existing error if flow fails
                         resolution = _uiState.value.locationSettingsResolution ?: resolution
                    }
                }

                // 4. If still null, use default location
                if (location == null) {
                    Log.w(TAG, "All location attempts failed, using default.")
                    location = locationManager.getDefaultLocation()
                    errorMsg = errorMsg ?: if (_uiState.value.prayers.isEmpty()) "Using approximate prayer times." else null
                }

                // 5. Update prayer times with whatever location we have
                Log.d(TAG, "Updating prayer times with location: ${location.latitude}, ${location.longitude}")
                updatePrayerTimes(location)

                // 6. Update final UI state with error/resolution if any
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = errorMsg, // Use error captured during fetch attempts
                        locationSettingsResolution = resolution // Use resolution captured during fetch attempts
                    )
                }

            } catch (e: Exception) {
                 if (e !is CancellationException) {
                    Log.e(TAG, "General error in refreshPrayerTimes", e)
                    handleLocationError(e, "Error loading prayer times")
                    // Try updating with default if error occurred and prayers are empty
                    if (_uiState.value.prayers.isEmpty()) {
                         location = locationManager.getDefaultLocation()
                         updatePrayerTimes(location)
                    }
                }
                 _uiState.update { it.copy(isLoading = false) } // Ensure loading is false on general error
            }
        }
    }

    private suspend fun updatePrayerTimes(location: Location?) {
        Log.d(TAG, "Calculating prayer times with location: ${location?.latitude}, ${location?.longitude}")
        try {
            val prayers = withContext(Dispatchers.IO) {
                 prayerTimeCalculator.calculatePrayerTimes(location)
            }

            if (prayers.isNotEmpty()) {
                val nextPrayer = prayers.firstOrNull { it.time > System.currentTimeMillis() }
                Log.d(TAG, "Calculation successful. Next prayer: ${nextPrayer?.name}")

                val prayerUiModels = prayers.mapIndexed { index, prayer ->
                    val nextPrayerForThis = if (index < prayers.size - 1) prayers[index + 1] else null
                    val status = prayerCompletionManager.getPrayerCompletionStatus(prayer, nextPrayerForThis)
                    PrayerUiModel(
                        prayer = prayer,
                        isComplete = status.isComplete,
                        completionType = status.completionType,
                        isMissed = status.isMissed,
                        isCurrentPrayer = status.isCurrentPrayer
                    )
                }

                _uiState.update {
                    it.copy(
                        prayers = prayerUiModels,
                        nextPrayer = nextPrayer,
                        // Don't clear error if location was approximate/default
                        // error = null,
                        isLoading = false
                    )
                }
                prayerScheduler.scheduleAllPrayers(prayers)
            } else {
                Log.w(TAG, "Prayer time calculation returned empty list.")
                _uiState.update {
                    it.copy(
                        prayers = emptyList(),
                        nextPrayer = null,
                        error = it.error ?: "Could not calculate prayer times.",
                        isLoading = false
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during prayer time calculation or scheduling", e)
            _uiState.update {
                it.copy(
                    error = it.error ?: "Error calculating prayer times",
                    isLoading = false
                )
            }
        }
    }

    private fun handleLocationError(e: Throwable, logPrefix: String) {
         when {
            e is LocationSettingsDisabledException -> {
                Log.w(TAG, "$logPrefix: Location settings disabled, requires resolution.")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Please enable location services",
                        locationSettingsResolution = e.resolvableApiException
                    )
                }
            }
            e !is CancellationException -> {
                Log.e(TAG, "$logPrefix: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        error = if (it.prayers.isEmpty()) "Error getting location" else null,
                        isLoading = false,
                        locationSettingsResolution = null
                    )
                }
            }
            // Ignore CancellationException
        }
    }


    override fun onCleared() {
        super.onCleared()
        locationUpdateJob?.cancel()
    }

    /**
     * Call this after the UI has attempted to resolve the location settings issue.
     */
    fun locationSettingsResolutionAttempted() {
        _uiState.update { it.copy(locationSettingsResolution = null) }
        // Trigger a refresh immediately after attempting resolution
        refreshPrayerTimes()
    }

    /**
     * Initialize the app and complete any startup tasks.
     * Used to control the splash screen visibility.
     */
    fun initializeApp() {
        // Shorter delay for splash screen, and ensure we always set isReady to true
        viewModelScope.launch {
            try {
                // Show splash screen for a shorter fixed duration
                delay(500)
            } catch (e: Exception) {
                Log.e(TAG, "Error during app initialization", e)
            } finally {
                // Always mark as ready, even if there was an error
                _isReady.value = true
            }
        }
    }

} // End of MainViewModel class
