package com.viperdam.kidsprayer.ui.language

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import android.content.SharedPreferences
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.AlarmManager
import android.widget.Toast

@Singleton
class LanguageManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LanguageManager"
        private const val PREFS_NAME = "LanguagePrefs"
        private const val KEY_LANGUAGE = "selected_language"
        private const val DEFAULT_LANGUAGE = "en"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCurrentLanguage(): String {
        val savedLanguage = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        Log.d(TAG, "Getting current language: $savedLanguage")
        return savedLanguage
    }

    fun setLanguage(languageCode: String): Boolean {
        val currentLang = getCurrentLanguage()
        if (currentLang == languageCode) {
            Log.d(TAG, "Language unchanged: $languageCode")
            return false // No change needed
        }
        
        Log.d(TAG, "Setting language from $currentLang to $languageCode")
        val result = prefs.edit().putString(KEY_LANGUAGE, languageCode).commit() // Use commit instead of apply for immediate write
        
        // Verify the language was saved
        val newSavedLang = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE)
        Log.d(TAG, "Saved language: $newSavedLang, save operation result: $result")
        
        return result && newSavedLang == languageCode
    }

    fun updateConfiguration(configuration: Configuration): Configuration {
        val langCode = getCurrentLanguage()
        Log.d(TAG, "Updating configuration for language: $langCode")
        
        // Use the correct resource directory name
        val resourceLangCode = LanguageModel.getResourceDirectoryName(langCode)
        Log.d(TAG, "Using resource directory code: $resourceLangCode")
        
        // Debug the current locale before changing
        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale
        }
        Log.d(TAG, "Current locale: $currentLocale, system locale: ${Locale.getDefault()}")
        
        // Create the locale based on language code
        val locale = Locale(resourceLangCode)
        
        // Apply this locale as the default
        Locale.setDefault(locale)
        Log.d(TAG, "Set default locale to: $locale")

        // Create a new configuration that will use our locale
        val newConfig = Configuration(configuration)
        
        // Apply the locale to the configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            newConfig.setLocale(locale)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val localeList = LocaleList(locale)
                LocaleList.setDefault(localeList)
                newConfig.setLocales(localeList)
                Log.d(TAG, "Set locales for Android N+: $localeList")
            } else {
                Log.d(TAG, "Set locale (17-23) for pre-N: $locale")
            }
        } else {
            @Suppress("DEPRECATION")
            newConfig.locale = locale
            Log.d(TAG, "Set locale (pre-17) for pre-N: $locale")
        }
        
        // Debug the new config
        val newLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            newConfig.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            newConfig.locale
        }
        Log.d(TAG, "New configuration locale: $newLocale")
        
        // For API < 17, we need to set this directly to make sure it works
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try {
                val resources = context.resources
                val metrics = resources.displayMetrics
                @Suppress("DEPRECATION")
                resources.updateConfiguration(newConfig, metrics)
                Log.d(TAG, "Applied resources update for pre-17 API")
            } catch (e: Exception) {
                Log.e(TAG, "Error applying resource configuration", e)
            }
        }
        
        return newConfig
    }

    fun restartApp(activity: Activity) {
        Log.d(TAG, "Restarting app to apply language change: ${getCurrentLanguage()}")
        
        try {
            // Create an intent that will be triggered by the AlarmManager
            val intent = Intent(activity, activity.javaClass)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.putExtra("restart_from_language_change", true)
            
            // Create a pending intent that will start our app
            val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
            
            val pendingIntent = PendingIntent.getActivity(
                activity.applicationContext, 
                123456, 
                intent,
                pendingIntentFlag
            )
            
            // Get the AlarmManager service
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // Schedule the app to restart in 100ms
            alarmManager.set(
                AlarmManager.RTC,
                System.currentTimeMillis() + 100,
                pendingIntent
            )
            
            // Now kill the process - the alarm will restart it
            Log.d(TAG, "Scheduling app restart via AlarmManager")
            
            // Show toast to inform user
            Toast.makeText(
                activity,
                "Restarting app to apply language change",
                Toast.LENGTH_SHORT
            ).show()
            
            // Also create a fallback launcher intent to ensure restart works
            val packageManager = activity.packageManager
            val launcherIntent = packageManager.getLaunchIntentForPackage(activity.packageName)
            launcherIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            launcherIntent?.putExtra("restart_from_language_change", true)
            
            // Kill the process after a short delay to ensure the toast is shown
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Shutting down process to apply language change")
                try {
                    // Try to start the fallback intent first
                    if (launcherIntent != null) {
                        Log.d(TAG, "Starting fallback launcher intent")
                        activity.startActivity(launcherIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting fallback intent", e)
                }
                
                // Finally kill the process
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting app: ${e.message}", e)
            
            // Fallback to traditional method if AlarmManager fails
            val intent = Intent(activity, activity.javaClass)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivity(intent)
            activity.finish()
        }
    }
} 