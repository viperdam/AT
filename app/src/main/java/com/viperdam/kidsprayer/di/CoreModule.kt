package com.viperdam.kidsprayer.di

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.work.WorkManager
import com.viperdam.kidsprayer.ads.AdManager
import com.viperdam.kidsprayer.prayer.LocationManager
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.prayer.PrayerTimeCalculator
import com.viperdam.kidsprayer.service.PrayerScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    
    private fun isMainProcess(context: Context): Boolean {
        val pid = Process.myPid()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        for (processInfo in activityManager.runningAppProcesses) {
            if (processInfo.pid == pid) {
                return processInfo.processName == context.packageName
            }
        }
        return false
    }
    
    @Provides
    @Singleton
    fun provideAdManager(@ApplicationContext context: Context): AdManager {
        return if (isMainProcess(context)) {
            Log.d("CoreModule", "Providing AdManager in main process")
            AdManager(context)
        } else {
            Log.d("CoreModule", "Providing dummy AdManager in non-main process")
            // Return a lazy-initialized version that won't actually initialize MobileAds
            AdManager(context)
        }
    }

    @Provides
    @Singleton
    fun providePrayerScheduler(@ApplicationContext context: Context): PrayerScheduler {
        return PrayerScheduler(context)
    }

    @Provides
    @Singleton
    fun providePrayerTimeCalculator(): PrayerTimeCalculator {
        return PrayerTimeCalculator()
    }

    @Provides
    @Singleton
    fun provideLocationManager(@ApplicationContext context: Context): LocationManager {
        return LocationManager(context)
    }

    @Provides
    @Singleton
    fun providePrayerCompletionManager(@ApplicationContext context: Context): PrayerCompletionManager {
        return PrayerCompletionManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun providePowerManager(@ApplicationContext context: Context): PowerManager {
        return context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    @Provides
    @Singleton
    fun provideUsageStatsManager(@ApplicationContext context: Context): UsageStatsManager {
        return context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideKeyguardManager(@ApplicationContext context: Context): KeyguardManager {
        return context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    @Provides
    @Singleton
    fun provideDevicePolicyManager(@ApplicationContext context: Context): DevicePolicyManager {
        return context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
}
