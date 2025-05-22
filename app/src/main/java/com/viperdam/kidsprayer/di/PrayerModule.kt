package com.viperdam.kidsprayer.di

import android.content.Context
import com.viperdam.kidsprayer.prayer.LocationManager
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.prayer.PrayerStateChecker
import com.viperdam.kidsprayer.prayer.PrayerTimeCalculator
import com.viperdam.kidsprayer.state.PrayerLockStateManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PrayerModule {
    
    @Provides
    @Singleton
    fun providePrayerStateChecker(
        @ApplicationContext context: Context,
        prayerTimeCalculator: PrayerTimeCalculator,
        completionManager: PrayerCompletionManager,
        locationManager: LocationManager
    ): PrayerStateChecker = PrayerStateChecker(context, prayerTimeCalculator, completionManager, locationManager)
    
    @Provides
    @Singleton
    fun providePrayerLockStateManager(
        @ApplicationContext context: Context,
        completionManager: PrayerCompletionManager
    ): PrayerLockStateManager = PrayerLockStateManager(context, completionManager)
}
