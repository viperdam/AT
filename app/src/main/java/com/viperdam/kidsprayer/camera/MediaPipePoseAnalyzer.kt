package com.viperdam.kidsprayer.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.viperdam.kidsprayer.mediapipe.MediaPipeHelper
import java.util.concurrent.Executors

class MediaPipePoseAnalyzer(private val mediaPipeHelper: MediaPipeHelper) : ImageAnalysis.Analyzer {

    private val executor = Executors.newSingleThreadScheduledExecutor()

    override fun analyze(imageProxy: ImageProxy) {
        mediaPipeHelper.analyzeImage(imageProxy)
    }

    fun shutdown() {
        executor.shutdown()
    }
}
