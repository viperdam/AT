package com.viperdam.kidsprayer.camera

import android.content.Context
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject

interface CameraManager {
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onLandmarksDetected: (List<com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark>) -> Unit
    )

    fun stopCamera()
    fun bindPreview(preview: Preview)
    fun isRunning(): Boolean
}
