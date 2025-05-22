package com.viperdam.kidsprayer.di

import android.content.Context
import com.viperdam.kidsprayer.camera.CameraManager
import com.viperdam.kidsprayer.camera.CameraManagerImpl
import com.viperdam.kidsprayer.mediapipe.MediaPipeHelper
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {
    @Binds
    @Singleton
    abstract fun bindCameraManager(impl: CameraManagerImpl): CameraManager

    companion object {
        @Provides
        @Singleton
        fun provideCameraManagerImpl(
            @ApplicationContext context: Context,
            mediaPipeHelper: MediaPipeHelper
        ): CameraManagerImpl {
            return CameraManagerImpl(context, mediaPipeHelper)
        }

        @Provides
        @Singleton
        fun provideMediaPipeHelper(@ApplicationContext context: Context): MediaPipeHelper {
            val executor = Executors.newSingleThreadScheduledExecutor()
            return MediaPipeHelper(context = context, executor = executor)
        }
    }
}
