package com.viperdam.kidsprayer.di

import android.content.Context
import android.content.SharedPreferences
import android.location.LocationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import com.viperdam.kidsprayer.util.PrayerSettingsManager

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    @Named("systemLocationManager")
    fun provideSystemLocationManager(@ApplicationContext context: Context): LocationManager {
        return context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    @Provides
    @Singleton
    fun providePrayerSettingsManager(@ApplicationContext context: Context): PrayerSettingsManager {
        return PrayerSettingsManager.getInstance(context)
    }
}
