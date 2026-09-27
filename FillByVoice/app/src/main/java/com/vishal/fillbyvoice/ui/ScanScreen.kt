package com.vishal.fillbyvoice.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.vishal.fillbyvoice.log.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vishal.fillbyvoice.R
import com.vishal.fillbyvoice.camera.createCameraController
import com.vishal.fillbyvoice.camera.takePhoto
import com.vishal.fillbyvoice.llm.Gemma
import com.vishal.fillbyvoice.ocr.readLines
import com.vishal.fillbyvoice.pipeline.FoundQuestions
import com.vishal.fillbyvoice.pipeline.Question
import com.vishal.fillbyvoice.pipeline.findQuestions
import com.vishal.fillbyvoice.voice.Language
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "Scan"

@Composable
fun ScanScreen(language: Language, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }
    LaunchedEffect(Unit) {
        if (!hasCamera) askCamera.launch(Manifest.permission.CAMERA)
    }

    var photo by remember { mutableStateOf<Bitmap?>(null) }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shown = photo
        when {
            !hasCamera -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.camera_permission_needed))
                Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.camera_allow))
                }
            }
            shown != null -> PhotoView(shown, language, onRetake = { photo = null })
            else -> CameraView(onPhoto = { photo = it })
        }
    }
}

@Composable
private fun CameraView(onPhoto: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { createCameraController(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { PreviewView(it).apply { this.controller = controller } },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            stringResource(R.string.scan_hint),
            Modifier.align(Alignment.TopCenter).padding(16.dp)
                .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        LargeFloatingActionButton(
            onClick = {
                if (busy) return@LargeFloatingActionButton
                busy = true
                scope.launch {
                    try {
                        onPhoto(controller.takePhoto())
                    } catch (e: ImageCaptureException) {
                        Toast.makeText(context, R.string.scan_failed, Toast.LENGTH_SHORT).show()
                    }
                    busy = false
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        ) {
            Icon(painterResource(R.drawable.ic_scan), stringResource(R.string.scan_capture))
        }
    }
}

// Photo -> OCR -> Gemma field finder -> question list.
@Composable
private fun PhotoView(photo: Bitmap, language: Language, onRetake: () -> Unit) {
    val context = LocalContext.current
    var status by remember(photo) { mutableStateOf(context.getString(R.string.ocr_reading)) }
    var found by remember(photo) { mutableStateOf<FoundQuestions?>(null) }
    LaunchedEffect(photo) {
        try {
            val began = System.currentTimeMillis()
            val lines = readLines(photo)
            Log.i(TAG, "OCR: ${lines.size} lines in ${System.currentTimeMillis() - began} ms (${photo.width}x${photo.height})")
            status = context.getString(R.string.gemma_loading)
            Gemma.load(context)
            found = findQuestions(lines) { done, total ->
                status = context.getString(R.string.form_reading, done + 1, total)
            }
        } catch (e: Exception) {
            // Retake mid-scan stops the scan: not an error (08:05 run logged it as "Scan failed").
            if (e is CancellationException) throw e
            Log.e(TAG, "Scan failed", e)
            status = "Error: ${e.message}"
        }
    }
    val result = found
    val shown = remember(result) { result?.let { photo.withBoxes(it.questions.map(Question::box)) } ?: photo }
    val image = remember(shown) { shown.asImageBitmap() }
    // Tap the small photo to see the found questions boxed in red, tap again to shrink it.
    var bigPhoto by remember(photo) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (result == null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp)),
            )
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!status.startsWith("Error")) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(status, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onRetake) { Text(stringResource(R.string.scan_retake)) }
            }
        } else {
            // Once the questions start, the question is the big thing: the photo becomes a thumbnail next to the
            // count and the on-device speed line (kept on screen for the jury).
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 52.dp, height = 68.dp).clip(RoundedCornerShape(10.dp))
                        .clickable { bigPhoto = !bigPhoto },
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        "✓ " + stringResource(R.string.questions_found, result.questions.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (result.tokensPerSecond > 0) {
                        Text(
                            "⚡ " + stringResource(R.string.gemma_speed, Gemma.backendName, result.tokensPerSecond),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                TextButton(onClick = onRetake) { Text(stringResource(R.string.scan_retake)) }
            }
            if (bigPhoto) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp)).clickable { bigPhoto = false },
                )
            }
            if (result.questions.isNotEmpty()) {
                QuestionScreen(result.questions, language, photo, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

// Debug view: a red box around each question Gemma found, drawn on a copy of the photo.
private fun Bitmap.withBoxes(boxes: List<Rect>): Bitmap {
    val copy = copy(Bitmap.Config.ARGB_8888, true)
    val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    val canvas = Canvas(copy)
    boxes.forEach { canvas.drawRect(it, paint) }
    return copy
}
