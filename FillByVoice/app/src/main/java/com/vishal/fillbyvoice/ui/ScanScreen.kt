package com.vishal.fillbyvoice.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.vishal.fillbyvoice.pipeline.ask
import com.vishal.fillbyvoice.pipeline.findQuestions
import com.vishal.fillbyvoice.voice.Language
import com.vishal.fillbyvoice.voice.Speaker
import com.vishal.fillbyvoice.voice.listen
import kotlinx.coroutines.launch

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
            val lines = readLines(photo)
            status = context.getString(R.string.gemma_loading)
            Gemma.load(context)
            found = findQuestions(lines) { done, total ->
                status = context.getString(R.string.form_reading, done + 1, total)
            }
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        }
    }
    val result = found
    val shown = remember(result) { result?.let { photo.withBoxes(it.questions.map(Question::box)) } ?: photo }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (result == null) {
            Text(status, Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                item { Text(stringResource(R.string.questions_found, result.questions.size)) }
                if (result.tokensPerSecond > 0) {
                    item { Text(stringResource(R.string.gemma_speed, Gemma.backendName, result.tokensPerSecond)) }
                }
                result.questions.firstOrNull()?.let { first ->
                    item { VoiceTest(first, language) }
                }
                itemsIndexed(result.questions) { i, q ->
                    Text("${i + 1}. ${q.ask(language)}\n    ${q.type} ${q.options.joinToString(" / ")}")
                }
            }
        }
        Button(onClick = onRetake, modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.scan_retake))
        }
    }
}

// Part 6 test: the phone asks one question aloud, then listens and shows what it heard.
// Part 8 turns this into the full loop over every question.
@Composable
private fun VoiceTest(question: Question, language: Language) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var heard by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column {
        Button(
            enabled = !busy,
            onClick = {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                    return@Button
                }
                busy = true
                scope.launch {
                    heard = try {
                        speaker.speak(question.ask(language), language)
                        listen(context, language) ?: context.getString(R.string.voice_not_heard)
                    } catch (e: IllegalStateException) {
                        "Error: ${e.message}"
                    }
                    busy = false
                }
            },
        ) {
            Text(stringResource(R.string.voice_test))
        }
        if (heard.isNotEmpty()) Text(stringResource(R.string.voice_heard, heard))
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
