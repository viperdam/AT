package com.viperdam.kidsprayer.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viperdam.kidsprayer.model.Prayer
import com.viperdam.kidsprayer.model.PrayerSetting
import com.viperdam.kidsprayer.service.PrayerScheduler
import com.viperdam.kidsprayer.prayer.PrayerTimeCalculator
import com.viperdam.kidsprayer.service.PrayerReceiver
import com.viperdam.kidsprayer.service.VolumeButtonService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Named

data class PrayerSettings(
    val fajr: PrayerSetting = PrayerSetting(enabled = true, adhanEnabled = false, notificationEnabled = false, lockEnabled = true, adhanVolume = 1.0f),
    val dhuhr: PrayerSetting = PrayerSetting(enabled = true, adhanEnabled = true, notificationEnabled = true, lockEnabled = true, adhanVolume = 1.0f),
    val asr: PrayerSetting = PrayerSetting(enabled = true, adhanEnabled = true, notificationEnabled = true, lockEnabled = true, adhanVolume = 1.0f),
    val maghrib: PrayerSetting = PrayerSetting(enabled = true, adhanEnabled = false, notificationEnabled = true, lockEnabled = true, adhanVolume = 1.0f),
    val isha: PrayerSetting = PrayerSetting(enabled = true, adhanEnabled = false, notificationEnabled = true, lockEnabled = true, adhanVolume = 1.0f),
    val notificationAdvanceTime: Int = 15,
    val calculationMethod: Int = 0,
    val vibrationEnabled: Boolean = true,
    val globalAdhanEnabled: Boolean = true,
    val globalLockEnabled: Boolean = true,
    val globalNotificationsEnabled: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prayerScheduler: PrayerScheduler,
    private val prayerTimeCalculator: PrayerTimeCalculator,
    @Named("systemLocationManager") private val locationManager: LocationManager
) : ViewModel() {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _settings = MutableStateFlow<PrayerSettings?>(null)
    val settings: StateFlow<PrayerSettings?> = _settings.asStateFlow()

    private var currentSettings = PrayerSettings()
    private var originalSettings = PrayerSettings()
    
    // Get the prayer_prefs SharedPreferences that PrayerSettingsManager uses
    private val prayerPrefs: SharedPreferences = 
        context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)

    init {
        // Load settings directly from prayer_prefs
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { _isLoading.value = true }
                
                // Use the default values from the data class
                val defaults = PrayerSettings()
                
                // Load from SharedPreferences["prayer_prefs"]
                currentSettings = PrayerSettings(
                    fajr = PrayerSetting(
                        enabled = prayerPrefs.getBoolean("fajr_enabled", defaults.fajr.enabled),
                        adhanEnabled = prayerPrefs.getBoolean("fajr_adhan", defaults.fajr.adhanEnabled),
                        notificationEnabled = prayerPrefs.getBoolean("fajr_notification", defaults.fajr.notificationEnabled),
                        lockEnabled = prayerPrefs.getBoolean("fajr_lock", defaults.fajr.lockEnabled),
                        adhanVolume = prayerPrefs.getFloat("fajr_adhan_volume", defaults.fajr.adhanVolume)
                    ),
                    dhuhr = PrayerSetting(
                        enabled = prayerPrefs.getBoolean("dhuhr_enabled", defaults.dhuhr.enabled),
                        adhanEnabled = prayerPrefs.getBoolean("dhuhr_adhan", defaults.dhuhr.adhanEnabled),
                        notificationEnabled = prayerPrefs.getBoolean("dhuhr_notification", defaults.dhuhr.notificationEnabled),
                        lockEnabled = prayerPrefs.getBoolean("dhuhr_lock", defaults.dhuhr.lockEnabled),
                        adhanVolume = prayerPrefs.getFloat("dhuhr_adhan_volume", defaults.dhuhr.adhanVolume)
                    ),
                    asr = PrayerSetting(
                        enabled = prayerPrefs.getBoolean("asr_enabled", defaults.asr.enabled),
                        adhanEnabled = prayerPrefs.getBoolean("asr_adhan", defaults.asr.adhanEnabled),
                        notificationEnabled = prayerPrefs.getBoolean("asr_notification", defaults.asr.notificationEnabled),
                        lockEnabled = prayerPrefs.getBoolean("asr_lock", defaults.asr.lockEnabled),
                        adhanVolume = prayerPrefs.getFloat("asr_adhan_volume", defaults.asr.adhanVolume)
                    ),
                    maghrib = PrayerSetting(
                        enabled = prayerPrefs.getBoolean("maghrib_enabled", defaults.maghrib.enabled),
                        adhanEnabled = prayerPrefs.getBoolean("maghrib_adhan", defaults.maghrib.adhanEnabled),
                        notificationEnabled = prayerPrefs.getBoolean("maghrib_notification", defaults.maghrib.notificationEnabled),
                        lockEnabled = prayerPrefs.getBoolean("maghrib_lock", defaults.maghrib.lockEnabled),
                        adhanVolume = prayerPrefs.getFloat("maghrib_adhan_volume", defaults.maghrib.adhanVolume)
                    ),
                    isha = PrayerSetting(
                        enabled = prayerPrefs.getBoolean("isha_enabled", defaults.isha.enabled),
                        adhanEnabled = prayerPrefs.getBoolean("isha_adhan", defaults.isha.adhanEnabled),
                        notificationEnabled = prayerPrefs.getBoolean("isha_notification", defaults.isha.notificationEnabled),
                        lockEnabled = prayerPrefs.getBoolean("isha_lock", defaults.isha.lockEnabled),
                        adhanVolume = prayerPrefs.getFloat("isha_adhan_volume", defaults.isha.adhanVolume)
                    ),
                    notificationAdvanceTime = prayerPrefs.getInt("notification_time", defaults.notificationAdvanceTime),
                    calculationMethod = prayerPrefs.getInt("calculation_method", defaults.calculationMethod),
                    vibrationEnabled = prayerPrefs.getBoolean("vibration_enabled", defaults.vibrationEnabled),
                    globalAdhanEnabled = prayerPrefs.getBoolean("enable_adhan", defaults.globalAdhanEnabled),
                    globalLockEnabled = prayerPrefs.getBoolean("enable_lock", defaults.globalLockEnabled),
                    globalNotificationsEnabled = prayerPrefs.getBoolean("notifications_enabled", defaults.globalNotificationsEnabled)
                )
                
                originalSettings = currentSettings.copy()
                
                withContext(Dispatchers.Main) {
                    _settings.value = currentSettings
                }
                
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error loading settings: ${e.message}")
                withContext(Dispatchers.Main) {
                    _message.value = "Error loading settings: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
            }
        }
    }

    private fun saveSettings() {
        try {
            // Save to prayer_prefs
            prayerPrefs.edit().apply {
                // Fajr settings
                putBoolean("fajr_enabled", currentSettings.fajr.enabled)
                putBoolean("fajr_adhan", currentSettings.fajr.adhanEnabled)
                putBoolean("fajr_notification", currentSettings.fajr.notificationEnabled)
                putBoolean("fajr_lock", currentSettings.fajr.lockEnabled)
                putFloat("fajr_adhan_volume", currentSettings.fajr.adhanVolume)
                
                // Dhuhr settings
                putBoolean("dhuhr_enabled", currentSettings.dhuhr.enabled)
                putBoolean("dhuhr_adhan", currentSettings.dhuhr.adhanEnabled)
                putBoolean("dhuhr_notification", currentSettings.dhuhr.notificationEnabled)
                putBoolean("dhuhr_lock", currentSettings.dhuhr.lockEnabled)
                putFloat("dhuhr_adhan_volume", currentSettings.dhuhr.adhanVolume)
                
                // Asr settings
                putBoolean("asr_enabled", currentSettings.asr.enabled)
                putBoolean("asr_adhan", currentSettings.asr.adhanEnabled)
                putBoolean("asr_notification", currentSettings.asr.notificationEnabled)
                putBoolean("asr_lock", currentSettings.asr.lockEnabled)
                putFloat("asr_adhan_volume", currentSettings.asr.adhanVolume)
                
                // Maghrib settings
                putBoolean("maghrib_enabled", currentSettings.maghrib.enabled)
                putBoolean("maghrib_adhan", currentSettings.maghrib.adhanEnabled)
                putBoolean("maghrib_notification", currentSettings.maghrib.notificationEnabled)
                putBoolean("maghrib_lock", currentSettings.maghrib.lockEnabled)
                putFloat("maghrib_adhan_volume", currentSettings.maghrib.adhanVolume)
                
                // Isha settings
                putBoolean("isha_enabled", currentSettings.isha.enabled)
                putBoolean("isha_adhan", currentSettings.isha.adhanEnabled)
                putBoolean("isha_notification", currentSettings.isha.notificationEnabled)
                putBoolean("isha_lock", currentSettings.isha.lockEnabled)
                putFloat("isha_adhan_volume", currentSettings.isha.adhanVolume)
                
                // General settings
                putInt("notification_time", currentSettings.notificationAdvanceTime)
                putInt("calculation_method", currentSettings.calculationMethod)
                putBoolean("vibration_enabled", currentSettings.vibrationEnabled)
                
                // Global settings
                putBoolean("enable_adhan", currentSettings.globalAdhanEnabled)
                putBoolean("enable_lock", currentSettings.globalLockEnabled)
                putBoolean("notifications_enabled", currentSettings.globalNotificationsEnabled)
                
                apply()
            }
            
            // Update original settings to match current ones
            originalSettings = currentSettings.copy()
            
            Log.d("SettingsViewModel", "Settings saved successfully")
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "Error saving settings: ${e.message}")
            _message.value = "Error saving settings: ${e.message}"
        }
    }

    fun updatePrayerEnabled(prayerName: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val setting = when (prayerName.lowercase()) {
                    "fajr" -> currentSettings.fajr.copy(enabled = enabled)
                    "dhuhr" -> currentSettings.dhuhr.copy(enabled = enabled)
                    "asr" -> currentSettings.asr.copy(enabled = enabled)
                    "maghrib" -> currentSettings.maghrib.copy(enabled = enabled)
                    "isha" -> currentSettings.isha.copy(enabled = enabled)
                    else -> return@launch
                }
                updatePrayerSetting(prayerName, setting)
                
                // Save to SharedPreferences
                saveSettings()
                
                // Reschedule prayers if needed
                if (enabled) {
                    prayerScheduler.checkAndUpdateSchedule()
                } else {
                    prayerScheduler.cancelPrayer(Prayer(prayerName, System.currentTimeMillis(), 0))
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating prayer enabled: ${e.message}")
                _message.value = "Error updating prayer enabled: ${e.message}"
            }
        }
    }

    fun updateAdhanEnabled(prayerName: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val setting = when (prayerName.lowercase()) {
                    "fajr" -> currentSettings.fajr.copy(adhanEnabled = enabled)
                    "dhuhr" -> currentSettings.dhuhr.copy(adhanEnabled = enabled)
                    "asr" -> currentSettings.asr.copy(adhanEnabled = enabled)
                    "maghrib" -> currentSettings.maghrib.copy(adhanEnabled = enabled)
                    "isha" -> currentSettings.isha.copy(adhanEnabled = enabled)
                    else -> return@launch
                }
                updatePrayerSetting(prayerName, setting)
                
                // Save to SharedPreferences
                saveSettings()
                
                // If adhan is currently playing and we disabled it, stop it
                if (!enabled && PrayerReceiver.isAdhanPlaying()) {
                    PrayerReceiver.stopAdhan(context, "settings_change")
                }
                
                // Check if we need to stop the volume button service
                if (!enabled) {
                    // Check if any other prayer has adhan enabled
                    val anyAdhanEnabled = listOf(
                        currentSettings.fajr.adhanEnabled,
                        currentSettings.dhuhr.adhanEnabled,
                        currentSettings.asr.adhanEnabled,
                        currentSettings.maghrib.adhanEnabled,
                        currentSettings.isha.adhanEnabled
                    ).any { it } && (prayerName.lowercase() != "all")
                    
                    if (!anyAdhanEnabled) {
                        // Stop volume button service if no prayers have adhan enabled
                        context.stopService(Intent(context, VolumeButtonService::class.java))
                        Log.d("SettingsViewModel", "Stopped VolumeButtonService as no prayers have adhan enabled")
                    }
                }
                
                // If enabling adhan, make sure global adhan setting is enabled
                if (enabled && !currentSettings.globalAdhanEnabled) {
                    updateGlobalAdhanEnabled(true)
                } else {
                    // Reschedule prayers to update Adhan settings
                    prayerScheduler.checkAndUpdateSchedule()
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating adhan enabled: ${e.message}")
                _message.value = "Error updating adhan enabled: ${e.message}"
            }
        }
    }

    fun updateNotificationEnabled(prayerName: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val setting = when (prayerName.lowercase()) {
                    "fajr" -> currentSettings.fajr.copy(notificationEnabled = enabled)
                    "dhuhr" -> currentSettings.dhuhr.copy(notificationEnabled = enabled)
                    "asr" -> currentSettings.asr.copy(notificationEnabled = enabled)
                    "maghrib" -> currentSettings.maghrib.copy(notificationEnabled = enabled)
                    "isha" -> currentSettings.isha.copy(notificationEnabled = enabled)
                    else -> return@launch
                }
                updatePrayerSetting(prayerName, setting)
                
                // Save to SharedPreferences
                saveSettings()
                
                // Make sure global notification setting is enabled if needed
                if (enabled && !currentSettings.globalNotificationsEnabled) {
                    updateGlobalNotificationsEnabled(true)
                } else {
                    // Reschedule prayers to update notification settings
                    prayerScheduler.checkAndUpdateSchedule()
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating notification enabled: ${e.message}")
                _message.value = "Error updating notification enabled: ${e.message}"
            }
        }
    }

    fun updateLockEnabled(prayerName: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val setting = when (prayerName.lowercase()) {
                    "fajr" -> currentSettings.fajr.copy(lockEnabled = enabled)
                    "dhuhr" -> currentSettings.dhuhr.copy(lockEnabled = enabled)
                    "asr" -> currentSettings.asr.copy(lockEnabled = enabled)
                    "maghrib" -> currentSettings.maghrib.copy(lockEnabled = enabled)
                    "isha" -> currentSettings.isha.copy(lockEnabled = enabled)
                    else -> return@launch
                }
                updatePrayerSetting(prayerName, setting)
                
                // Save to SharedPreferences
                saveSettings()
                
                // If enabling lock, make sure global lock setting is enabled
                if (enabled && !currentSettings.globalLockEnabled) {
                    updateGlobalLockEnabled(true)
                } else {
                    // Reschedule prayers to update Lock settings
                    prayerScheduler.checkAndUpdateSchedule()
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating lock enabled: ${e.message}")
                _message.value = "Error updating lock enabled: ${e.message}"
            }
        }
    }

    private fun updatePrayerSetting(prayerName: String, setting: PrayerSetting) {
        currentSettings = when (prayerName.lowercase()) {
            "fajr" -> currentSettings.copy(fajr = setting)
            "dhuhr" -> currentSettings.copy(dhuhr = setting)
            "asr" -> currentSettings.copy(asr = setting)
            "maghrib" -> currentSettings.copy(maghrib = setting)
            "isha" -> currentSettings.copy(isha = setting)
            else -> currentSettings
        }
        _settings.value = currentSettings
    }

    fun updateAdhanVolume(prayerName: String, volume: Float) {
        viewModelScope.launch {
            try {
                // Clamp volume between 0 and 1
                val clampedVolume = volume.coerceIn(0f, 1f)
                
                // Update setting
                val setting = when (prayerName.lowercase()) {
                    "fajr" -> currentSettings.fajr.copy(adhanVolume = clampedVolume)
                    "dhuhr" -> currentSettings.dhuhr.copy(adhanVolume = clampedVolume)
                    "asr" -> currentSettings.asr.copy(adhanVolume = clampedVolume)
                    "maghrib" -> currentSettings.maghrib.copy(adhanVolume = clampedVolume)
                    "isha" -> currentSettings.isha.copy(adhanVolume = clampedVolume)
                    else -> return@launch
                }
                
                // Update the settings in a way that won't interfere with RecyclerView
                withContext(Dispatchers.Main) {
                    updatePrayerSetting(prayerName, setting)
                }
                
                // Save to SharedPreferences
                saveSettings()
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating Adhan volume: ${e.message}")
                _message.value = "Error updating Adhan volume: ${e.message}"
            }
        }
    }

    fun updateNotificationAdvanceTime(minutes: Int) {
        viewModelScope.launch {
            try {
                currentSettings = currentSettings.copy(notificationAdvanceTime = minutes)
                _settings.value = currentSettings
                
                // Save to SharedPreferences
                saveSettings()
                
                // Reschedule prayers with new notification time
                prayerScheduler.checkAndUpdateSchedule()
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating notification time: ${e.message}")
                _message.value = "Error updating notification time: ${e.message}"
            }
        }
    }

    fun updateCalculationMethod(method: Int) {
        viewModelScope.launch {
            try {
                currentSettings = currentSettings.copy(calculationMethod = method)
                _settings.value = currentSettings
                
                // Save to SharedPreferences
                saveSettings()
                
                // Recalculate and reschedule prayers
                prayerScheduler.checkAndUpdateSchedule()
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating calculation method: ${e.message}")
                _message.value = "Error updating calculation method: ${e.message}"
            }
        }
    }

    // Update DND override methods
    fun updateDndOverride(enabled: Boolean) {
        viewModelScope.launch {
            prayerPrefs.edit().putBoolean("adhan_dnd_override", enabled).apply()
        }
    }

    fun updateNotificationVibrationSetting(enabled: Boolean) {
        viewModelScope.launch {
            prayerPrefs.edit().putBoolean("notification_vibration", enabled).apply()
        }
    }

    fun updateNotificationDndOverrideSetting(enabled: Boolean) {
        viewModelScope.launch {
            prayerPrefs.edit().putBoolean("notification_dnd_override", enabled).apply()
        }
    }

    fun updateAdhanDndOverride(prayerName: String, enabled: Boolean) {
        viewModelScope.launch {
            prayerPrefs.edit().putBoolean("${prayerName.lowercase()}_adhan_dnd_override", enabled).apply()
        }
    }

    fun updateNotificationTime(prayerName: String, minutes: Int) {
        viewModelScope.launch {
            prayerPrefs.edit().putInt("${prayerName.lowercase()}_notification_time", minutes).apply()
        }
    }

    fun updateNotificationVibration(prayerName: String, enabled: Boolean) {
        viewModelScope.launch {
            prayerPrefs.edit().putBoolean("${prayerName.lowercase()}_notification_vibration", enabled).apply()
        }
    }

    fun updateNotificationDndOverride(prayerName: String, enabled: Boolean) {
        viewModelScope.launch {
            prayerPrefs.edit().putBoolean("${prayerName.lowercase()}_notification_dnd_override", enabled).apply()
        }
    }

    fun updateVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                currentSettings = currentSettings.copy(vibrationEnabled = enabled)
                _settings.value = currentSettings
                
                // Save to SharedPreferences
                saveSettings()
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating vibration enabled: ${e.message}")
                _message.value = "Error updating vibration enabled: ${e.message}"
            }
        }
    }

    fun hasUnsavedChanges(): Boolean {
        return currentSettings != originalSettings
    }

    fun saveChanges() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                // Save all settings
                saveSettings()
                
                // Cancel all existing notifications and reschedule based on new settings
                prayerScheduler.cancelAllNotifications()
                
                // Get current location and recalculate prayer times
                // Check for permissions before accessing location
                var location: Location? = null
                try {
                    if (hasLocationPermission(context)) {
                        location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    }
                } catch (e: SecurityException) {
                    Log.e("SettingsViewModel", "Location permission denied", e)
                }
                
                if (location != null) {
                    val prayers = prayerTimeCalculator.calculatePrayerTimes(location)
                    prayers.forEach { prayer ->
                        if (getPrayerSetting(prayer.name).enabled) {
                            prayerScheduler.schedulePrayerNotification(
                                name = prayer.name,
                                time = prayer.time,
                                rakaatCount = prayer.rakaatCount
                            )
                        }
                    }
                } else {
                    Log.w("SettingsViewModel", "Could not get location, using default")
                    // Create a default location if none is available
                    val defaultLocation = Location("default").apply {
                        latitude = 0.0  // Default to 0,0 coordinates
                        longitude = 0.0
                    }
                    val prayers = prayerTimeCalculator.calculatePrayerTimes(defaultLocation)
                    prayers.forEach { prayer ->
                        if (getPrayerSetting(prayer.name).enabled) {
                            prayerScheduler.schedulePrayerNotification(
                                name = prayer.name,
                                time = prayer.time,
                                rakaatCount = prayer.rakaatCount
                            )
                        }
                    }
                }

                // Update original settings to match current
                originalSettings = currentSettings.copy()
                _settings.value = currentSettings
                _message.value = "Settings saved successfully"
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error saving settings: ${e.message}")
                _message.value = "Error saving settings: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun discardChanges() {
        currentSettings = originalSettings.copy()
        _settings.value = currentSettings
    }

    private fun getPrayerSetting(prayerName: String): PrayerSetting {
        return when (prayerName.lowercase()) {
            "fajr" -> currentSettings.fajr
            "dhuhr" -> currentSettings.dhuhr
            "asr" -> currentSettings.asr
            "maghrib" -> currentSettings.maghrib
            "isha" -> currentSettings.isha
            else -> throw IllegalArgumentException("Invalid prayer name")
        }
    }

    fun updateGlobalAdhanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                // Update the local copy
                currentSettings = currentSettings.copy(
                    globalAdhanEnabled = enabled
                )
                
                // Save to SharedPreferences
                saveSettings()
                
                // If adhan is currently playing and we disabled it, stop it
                if (!enabled && PrayerReceiver.isAdhanPlaying()) {
                    PrayerReceiver.stopAdhan(context, "settings_change")
                }
                
                // Reschedule prayers
                prayerScheduler.checkAndUpdateSchedule()
                
                _message.value = "Updated global adhan setting"
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating global adhan enabled: ${e.message}")
                _message.value = "Error updating global adhan enabled: ${e.message}"
            }
        }
    }

    fun updateGlobalLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                // Update the local copy
                currentSettings = currentSettings.copy(
                    globalLockEnabled = enabled
                )
                
                // Save to SharedPreferences
                saveSettings()
                
                // Reschedule prayers
                prayerScheduler.checkAndUpdateSchedule()
                
                _message.value = "Updated global lock setting"
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating global lock enabled: ${e.message}")
                _message.value = "Error updating global lock enabled: ${e.message}"
            }
        }
    }
    
    fun updateGlobalNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                // Update the local copy
                currentSettings = currentSettings.copy(
                    globalNotificationsEnabled = enabled
                )
                
                // Save to SharedPreferences
                saveSettings()
                
                // Reschedule prayers
                prayerScheduler.checkAndUpdateSchedule()
                
                _message.value = "Updated global notification setting"
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Error updating global notifications enabled: ${e.message}")
                _message.value = "Error updating global notifications enabled: ${e.message}"
            }
        }
    }

    // Add this function to check for location permissions
    private fun hasLocationPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
               context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
