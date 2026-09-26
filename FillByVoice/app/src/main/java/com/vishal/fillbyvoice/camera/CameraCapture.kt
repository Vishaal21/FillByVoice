package com.vishal.fillbyvoice.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// About 2000 px on the short side: sharp enough for OCR, far smaller than the 50 MP sensor.
private val PHOTO_SIZE = Size(2560, 1920)

fun createCameraController(context: Context) = LifecycleCameraController(context).apply {
    setEnabledUseCases(CameraController.IMAGE_CAPTURE)
    imageCaptureResolutionSelector = ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(PHOTO_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        )
        .build()
}

// Takes one photo and returns it upright. Runs off the main thread.
suspend fun LifecycleCameraController.takePhoto(): Bitmap = suspendCancellableCoroutine { cont ->
    takePicture(
        Dispatchers.Default.asExecutor(),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val degrees = image.imageInfo.rotationDegrees
                val bitmap = image.toBitmap()
                image.close()
                cont.resume(bitmap.rotate(degrees))
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        },
    )
}

private fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
