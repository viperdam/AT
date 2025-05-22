package com.viperdam.kidsprayer.mediapipe

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ScheduledExecutorService

class MediaPipeHelper(
    private val context: Context,
    private val numPoses: Int = 1,
    private val minPoseDetectionConfidence: Float = 0.5f,
    private val minPosePresenceConfidence: Float = 0.5f,
    private val minTrackingConfidence: Float = 0.5f,
    private val modelName: String = "full",
    private val executor: ScheduledExecutorService
) {

    private var poseLandmarker: PoseLandmarker? = null
    private var landmarkListener: ((PoseLandmarkerResult, MPImage) -> Unit)? = null

    init {
        setupPoseLandmarker()
    }

    fun setLandmarkListener(listener: (PoseLandmarkerResult, MPImage) -> Unit) {
        landmarkListener = listener
    }

    private fun setupPoseLandmarker() {
        val baseOptionsBuilder = BaseOptions.builder()
        val modelAssetPath = "pose_landmarker_$modelName.task"
        baseOptionsBuilder.setModelAssetPath(modelAssetPath)

        val baseOptions = baseOptionsBuilder.build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
            .setMinTrackingConfidence(minTrackingConfidence)
            .setMinPosePresenceConfidence(minPosePresenceConfidence)
            .setNumPoses(numPoses)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::onPoseDetected)
            .setErrorListener { e -> Log.e(TAG, "Error in MediaPipe Pose Landmarker: ${e.message}", e) }
            .build()

        try {
            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up MediaPipe Pose Landmarker", e)
        }
    }

    fun analyzeImage(imageProxy: androidx.camera.core.ImageProxy) {
        val image = imageProxy.image
        if (image != null) {
            try {
                poseLandmarker?.detectAsync(
                    createFromMediaImage(context, image, imageProxy.imageInfo.rotationDegrees),
                    System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during MediaPipe detection", e)
            } finally {
                imageProxy.close()
            }
        } else {
            imageProxy.close() // Always close the proxy
        }
    }

    private fun onPoseDetected(result: PoseLandmarkerResult, image: MPImage) {
        Log.d(TAG, "Pose detected: $result")
        landmarkListener?.invoke(result, image)
    }

    fun close() {
        poseLandmarker?.close()
    }


    companion object {
        private const val TAG = "MediaPipeHelper"
    }
}
