package com.viperdam.kidsprayer.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters

/**
 * Factory interface for creating workers with dependencies
 * Used to enable Hilt dependency injection for WorkManager workers
 */
interface ChildWorkerFactory {
    fun create(appContext: Context, params: WorkerParameters): ListenableWorker
} 