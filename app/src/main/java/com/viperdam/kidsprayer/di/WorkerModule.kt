package com.viperdam.kidsprayer.di

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.viperdam.kidsprayer.ads.AdResetWorker
import com.viperdam.kidsprayer.ads.AdRefreshWorker
import com.viperdam.kidsprayer.service.AdSettingsCheckWorker
import com.viperdam.kidsprayer.service.ChildWorkerFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custom WorkerFactory that delegates to the appropriate ChildWorkerFactory
 * based on the worker class name
 */
class KidsPrayerWorkerFactory @Inject constructor(
    private val workerFactories: Map<String, @JvmSuppressWildcards ChildWorkerFactory>
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (val factoryProvider = workerFactories[workerClassName]) {
            null -> null // Let the default WorkerFactory handle this
            else -> factoryProvider.create(appContext, workerParameters)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object WorkerModule {
    
    @Singleton
    @Provides
    fun provideWorkerFactory(
        workerFactories: Map<String, @JvmSuppressWildcards ChildWorkerFactory>
    ): WorkerFactory {
        return KidsPrayerWorkerFactory(workerFactories)
    }
    
    @Singleton
    @Provides
    @IntoMap
    @StringKey("com.viperdam.kidsprayer.service.AdSettingsCheckWorker")
    fun provideAdSettingsCheckWorkerFactory(factory: AdSettingsCheckWorker.Factory): ChildWorkerFactory {
        return factory
    }
    
    @Singleton
    @Provides
    @IntoMap
    @StringKey("com.viperdam.kidsprayer.ads.AdResetWorker")
    fun provideAdResetWorkerFactory(factory: AdResetWorker.Factory): ChildWorkerFactory {
        return factory
    }
    
    @Singleton
    @Provides
    @IntoMap
    @StringKey("com.viperdam.kidsprayer.ads.AdRefreshWorker")
    fun provideAdRefreshWorkerFactory(factory: AdRefreshWorker.Factory): ChildWorkerFactory {
        return factory
    }
}
