package com.viperdam.kidsprayer.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark

class PoseOverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private var mirrored = true

    private var landmarks: List<NormalizedLandmark> = emptyList()

    private val landmarkPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 12f
    }

    private val connectionPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    fun setLandmarks(landmarks: List<NormalizedLandmark>) {
        this.landmarks = landmarks
        invalidate() // Trigger redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        fun transform(landmark: NormalizedLandmark): Pair<Float, Float> {
            val newX = landmark.y * width
            val newY = (1 - landmark.x) * height
            return Pair(newX, newY)
        }

        if (landmarks.isEmpty()) {
            return
        }
        var hasVisibleLandmarks = false
        for (landmark in landmarks) {
            if (landmark.visibility > 0.5) {
                hasVisibleLandmarks = true
                val (cx, cy) = transform(landmark)
                canvas.drawCircle(cx, cy, 10f, landmarkPaint)
            }
        }

        if (!hasVisibleLandmarks) {
            return
        }

        // Draw connections with updated mapping for a proper front camera display
        val connections = listOf(
            Pair(0, 1),  // Nose to left eye inner
            Pair(0, 4),  // Nose to right eye inner
            Pair(11, 12), // Shoulders: left shoulder to right shoulder
            Pair(11, 23), // Left shoulder to left hip
            Pair(12, 24), // Right shoulder to right hip
            Pair(23, 24), // Hips connection
            Pair(11, 13), Pair(13, 15), // Left arm
            Pair(12, 14), Pair(14, 16), // Right arm
            Pair(23, 25), Pair(25, 27), // Left leg
            Pair(24, 26), Pair(26, 28)  // Right leg
        )

        connections.forEach { (start, end) ->
            if (start < landmarks.size && end < landmarks.size &&
                landmarks[start].visibility > 0.5 && landmarks[end].visibility > 0.5) {
                val (startX, startY) = transform(landmarks[start])
                val (endX, endY) = transform(landmarks[end])
                canvas.drawLine(startX, startY, endX, endY, connectionPaint)
            }
        }
    }
}
