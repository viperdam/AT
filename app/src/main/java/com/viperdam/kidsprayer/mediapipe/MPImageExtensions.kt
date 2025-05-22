package com.viperdam.kidsprayer.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.io.ByteArrayOutputStream

private const val TAG = "MPImageExtensions"
private const val SUPPORTED_YUV_FORMAT = ImageFormat.YUV_420_888

fun createFromMediaImage(context: Context, image: Image, rotationDegrees: Int): MPImage {
    if (image.format != SUPPORTED_YUV_FORMAT) {
        throw IllegalArgumentException("Only YUV_420_888 format is supported, provided format: ${image.format}")
    }

    val width = image.width
    val height = image.height
    android.util.Log.d(TAG, "Converting media image with dimensions: width = $width, height = $height")
        val _context = context // Renamed to _context
        val _rotationDegrees = rotationDegrees // Renamed to _rotationDegrees

    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

    return BitmapImageBuilder(bitmap).build()
}

// No cleanup needed for BitmapFactory/YuvImage approach
fun cleanupRenderScript() {
    // No RenderScript resources to cleanup in this implementation
}
