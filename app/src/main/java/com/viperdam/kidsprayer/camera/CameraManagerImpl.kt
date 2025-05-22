package com.viperdam.kidsprayer.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark
import com.viperdam.kidsprayer.mediapipe.MediaPipeHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraManagerImpl @Inject constructor(
    private val context: Context,
    private val mediaPipeHelper: MediaPipeHelper
) : CameraManager {
    private var onLandmarksDetectedListener: ((List<NormalizedLandmark>) -> Unit)? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService? = null
    private var isActive = false
    private var retryCount = 0
    private var lastRotation = -1

    companion object {
        private const val TAG = "CameraManagerImpl"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val EXECUTOR_SHUTDOWN_TIMEOUT_MS = 1000L
    }

    @Synchronized
    override fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onLandmarksDetected: (List<NormalizedLandmark>) -> Unit
    ) {
        this.onLandmarksDetectedListener = onLandmarksDetected
        if (isActive) {
            Log.d(TAG, "Camera already active, skipping start")
            return
        }

        initializeCameraExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
                bindCamera(cameraProviderFuture.get(), lifecycleOwner, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun initializeCameraExecutor() {
        if (cameraExecutor?.isShutdown != false) {
            cameraExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "CameraExecutor").apply {
                    priority = Thread.MAX_PRIORITY
                }
            }
        }
    }

  private fun bindCamera(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        try {
            if (isActive) {
                Log.d(TAG, "Camera already bound, skipping bind")
                return
            }

            cameraProvider = provider
            provider.unbindAll()

            val currentRotation = previewView.display.rotation
            val previewBuilder = Preview.Builder()
            if (currentRotation != lastRotation) {
                previewBuilder.setTargetRotation(currentRotation)
                lastRotation = currentRotation
            }
            preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val executor = cameraExecutor ?: throw IllegalStateException("Camera executor not initialized")
            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor) { imageProxy ->
                        mediaPipeHelper.analyzeImage(imageProxy)
                    }
                }

            val landmarksDetectedCallback = onLandmarksDetectedListener
            mediaPipeHelper.setLandmarkListener { result, _ ->
                if (result.landmarks().isNotEmpty()) {
                    val landmarks = result.landmarks()[0].map { landmark ->
                        NormalizedLandmark.newBuilder()
                            .setX(landmark.x())
                            .setY(landmark.y())
                            .setZ(landmark.z())
                            .setVisibility(landmark.presence().orElse(0f))
                            .build()
                    }
                    landmarksDetectedCallback?.let { it(landmarks) }
                }
            }

            // Select front camera
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            isActive = true
            retryCount = 0
        } catch (e: Exception) {
            handleCameraError(e, lifecycleOwner, previewView)
        }
    }

     @Synchronized
    override fun stopCamera() {
        if (!isActive) {
            Log.d(TAG, "Camera already stopped, skipping stop")
            return
        }

        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            preview = null
            imageAnalyzer = null
            camera = null
            
            cameraExecutor?.let { executor ->
                executor.shutdown()
                try {
                    val terminated = executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    if (!terminated) {
                        executor.shutdownNow()
                        Log.w(TAG, "Forced executor shutdown after timeout")
                    } else {
                        Log.d(TAG, "Executor shutdown gracefully")
                    }
                } catch (e: InterruptedException) {
                    executor.shutdownNow()
                    Log.w(TAG, "Executor shutdown interrupted")
                }
            }
            cameraExecutor = null
            isActive = false
            retryCount = 0
            lastRotation = -1
            Log.d(TAG, "Camera stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera: ${e.message}")
        }
    }

    private fun handleCameraError(error: Exception, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if(retryCount < MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "Camera error: ${error.message}, retrying ($retryCount/$MAX_RETRY_ATTEMPTS)")
            retryCount++
            stopCamera()
            Handler(Looper.getMainLooper()).postDelayed({
//                if (onLandmarksDetectedListener != null) {
//                    startCamera(lifecycleOwner, previewView, onLandmarksDetectedListener!!)
//                }
            }, RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "Camera failed after multiple retries: ${error.message}")
            cleanup()
        }
    }

    private fun cleanup() {
        try {
            isActive = false
            cameraProvider?.unbindAll()
            cameraProvider = null
            preview = null
            imageAnalyzer = null
            camera = null
            shutdownExecutor()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }

    private fun shutdownExecutor() {
        cameraExecutor?.let { executor ->
            try {
                executor.shutdown()
                val terminated = executor.awaitTermination(
                    EXECUTOR_SHUTDOWN_TIMEOUT_MS, 
                    TimeUnit.MILLISECONDS
                )
                
                if (!terminated) {
                    executor.shutdownNow()
                    Log.w(TAG, "Forced executor shutdown after timeout")
                } else {
                    Log.d(TAG, "Executor shutdown gracefully")
                }
            } catch (e: InterruptedException) {
                executor.shutdownNow()
                Log.w(TAG, "Executor shutdown interrupted")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Error during executor shutdown", e)
                executor.shutdownNow()
            }
        }
        cameraExecutor = null
    }

    override fun bindPreview(preview: Preview) {
        this.preview = preview
    }

    override fun isRunning(): Boolean = isActive
}
