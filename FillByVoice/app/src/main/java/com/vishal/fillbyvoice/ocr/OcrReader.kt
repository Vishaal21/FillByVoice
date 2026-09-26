package com.vishal.fillbyvoice.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// One printed line of text and where it sits on the photo (in photo pixels).
data class OcrLine(val text: String, val box: Rect)

private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

// Reads all text lines from the photo. On-device, no internet.
suspend fun readLines(photo: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
    recognizer.process(InputImage.fromBitmap(photo, 0))
        .addOnSuccessListener { result ->
            val lines = result.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line -> line.boundingBox?.let { OcrLine(line.text, it) } }
            cont.resume(lines)
        }
        .addOnFailureListener { cont.resumeWithException(it) }
}
