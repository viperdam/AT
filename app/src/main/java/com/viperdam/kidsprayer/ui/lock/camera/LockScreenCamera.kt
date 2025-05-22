package com.viperdam.kidsprayer.ui.lock.camera

import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.View
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.databinding.ActivityLockScreenBinding
import com.viperdam.kidsprayer.ui.lock.LockScreenViewModel
import kotlinx.coroutines.launch

class LockScreenCamera(
    private val binding: ActivityLockScreenBinding,
    private val viewModel: LockScreenViewModel,
    private val context: Context
) {
    companion object {
        private const val TAG = "LockScreenCamera"
        private const val MIN_STATE_RESET_INTERVAL = 1000L
    }

    private var lastCameraReset = 0L

    fun setupCameraPreview() {
        binding.startPrayerButton.setOnClickListener {
            if (!viewModel.uiState.value.isLockedOut) {
                // Make sure any previous camera session is closed
                stopCamera()
                // Start prayer session
                viewModel.startPrayer()
            }
        }

        binding.retryButton.setOnClickListener {
            startCamera()
        }

        (context as androidx.lifecycle.LifecycleOwner).lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                if (!state.isLockedOut) {
                    if (state.isPrayerStarted && !state.isPrayerComplete) {
                        binding.apply {
                            viewFinder.visibility = View.VISIBLE
                            startPrayerButton.visibility = View.GONE
                            pinEntryCard.visibility = View.GONE
                            backButton.visibility = View.VISIBLE
                        }
                        startCamera()
                    } else {
                        binding.apply {
                            viewFinder.visibility = View.GONE
                            backButton.visibility = View.GONE
                            // Always make the start button visible when not in prayer
                            startPrayerButton.visibility = if (state.shouldShowStartButton) View.VISIBLE else View.GONE
                            // Ensure the button is enabled
                            startPrayerButton.isEnabled = state.shouldShowStartButton
                        }
                        stopCamera()
                    }

                    // Update prayer info
                    binding.apply {
                        prayerName.text = state.prayerName
                        rakaatCounter.text = context.getString(R.string.rakaat_count, state.currentRakaat)
                        positionName.text = state.currentPosition
                    }

                    // Handle camera errors
                    if (state.cameraError != null) {
                        binding.apply {
                            errorCard.visibility = View.VISIBLE
                            errorMessage.text = context.getString(R.string.camera_error_message, state.cameraError)
                        }
                    } else {
                        binding.errorCard.visibility = View.GONE
                    }
                }
            }
        }
    }

    fun startCamera() {
        if (!viewModel.uiState.value.isLockedOut) {
            try {
                // First ensure camera is fully stopped
                try {
                    viewModel.stopPoseDetection()
                    // Add a small delay to ensure resources are fully released
                    Handler(android.os.Looper.getMainLooper()).postDelayed({
                        (context as androidx.lifecycle.LifecycleOwner).lifecycleScope.launch {
                            try {
                                viewModel.startPoseDetection()
                                // Make cameraContainer visible too
                                binding.cameraContainer.visibility = View.VISIBLE
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in delayed camera start: ${e.message}")
                                binding.apply {
                                    errorCard.visibility = View.VISIBLE
                                    errorMessage.text = context.getString(R.string.camera_error_message, e.message)
                                }
                            }
                        }
                    }, 300) // 300ms delay to allow camera resources to release
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping camera before restart: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera: ${e.message}")
                binding.apply {
                    errorCard.visibility = View.VISIBLE
                    errorMessage.text = context.getString(R.string.camera_error_message, e.message)
                }
            }
        }
    }

    fun stopCamera() {
        if (!viewModel.uiState.value.isLockedOut) {
            try {
                viewModel.stopPoseDetection()
                // Ensure camera container is hidden
                binding.cameraContainer.visibility = View.GONE
                binding.viewFinder.visibility = View.GONE
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping camera: ${e.message}")
            }
        }
    }

    fun handleScreenOff() {
        if (!viewModel.uiState.value.isLockedOut) {
            if (binding.viewFinder.visibility == View.VISIBLE) {
                val currentTime = android.os.SystemClock.elapsedRealtime()
                if (currentTime - lastCameraReset >= MIN_STATE_RESET_INTERVAL) {
                    lastCameraReset = currentTime
                    viewModel.stopPrayer()
                    stopCamera()
                }
            }
        }
    }

    fun handleScreenOn() {
        if (!viewModel.uiState.value.isLockedOut) {
            if (binding.viewFinder.visibility == View.VISIBLE) {
                val currentTime = android.os.SystemClock.elapsedRealtime()
                if (currentTime - lastCameraReset >= MIN_STATE_RESET_INTERVAL) {
                    lastCameraReset = currentTime
                    viewModel.stopPrayer()
                    stopCamera()
                }
            }
        }
    }
}
