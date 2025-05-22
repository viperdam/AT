package com.viperdam.kidsprayer.di

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppContextModule {
    @Provides
    @Singleton
    fun provideContext(application: Application): Context {
        return application
    }
} 