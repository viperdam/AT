package com.viperdam.kidsprayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

// Extension property for accessing DataStore
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

// Extension function to get a value from DataStore with a default
suspend fun <T> DataStore<Preferences>.getOrDefault(key: Preferences.Key<T>, defaultValue: T): T {
    return this.data.map { preferences ->
        preferences[key] ?: defaultValue
    }.first()
}

// Object containing all preference keys used in the app
object PreferencesKeys {
    // Lock screen related keys
    val LOCK_SCREEN_ACTIVE = booleanPreferencesKey("lock_screen_active")
    val LAST_LOCK_ACTIVATION_TIME = longPreferencesKey("last_lock_activation_time")
    val IS_AD_LOADING = booleanPreferencesKey("is_ad_loading")
    
    // Ad related keys
    val AD_LAST_SHOW_TIME = longPreferencesKey("ad_last_show_time")
    val AD_LAST_BANNER_DISPLAY_TIME = longPreferencesKey("ad_last_banner_display_time")
    val AD_DAILY_COUNT = intPreferencesKey("ad_daily_count")
    val AD_HOURLY_COUNT = intPreferencesKey("ad_hourly_count")
    val AD_LAST_COUNT_RESET_DAY = intPreferencesKey("ad_last_count_reset_day")
    val AD_LAST_COUNT_RESET_HOUR = intPreferencesKey("ad_last_count_reset_hour")
    val AD_LAST_APP_BACKGROUND_TIME = longPreferencesKey("ad_last_app_background_time")
    val AD_LAST_CHECK_DATE_STRING = stringPreferencesKey("ad_last_check_date_string")
    
    // Notification settings
    val ADHAN_DND_OVERRIDE = booleanPreferencesKey("adhan_dnd_override")
    val NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration")
    val NOTIFICATION_DND_OVERRIDE = booleanPreferencesKey("notification_dnd_override")
} 