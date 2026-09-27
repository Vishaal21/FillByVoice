package com.vishal.fillbyvoice.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import com.vishal.fillbyvoice.log.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vishal.fillbyvoice.R
import com.vishal.fillbyvoice.flow.Answer
import com.vishal.fillbyvoice.flow.LoopState
import com.vishal.fillbyvoice.flow.QuestionLoop
import com.vishal.fillbyvoice.pipeline.Question
import com.vishal.fillbyvoice.pipeline.hindiOption
import com.vishal.fillbyvoice.voice.Language
import com.vishal.fillbyvoice.voice.Speaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.cancellation.CancellationException

// Part 8: the voice loop starts by itself after the scan. Big text: question k/N, the question, its options, what
// was heard, the value waiting for "सही है?". The mic button at the bottom shows when to speak and mutes.
// Then the read-back (Part 9) and the answer sheet with the PDF (Part 12).
@Composable
fun QuestionScreen(questions: List<Question>, language: Language, photo: Bitmap, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    var micAllowed by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micAllowed = it }
    var state by remember { mutableStateOf<LoopState?>(null) }
    var answers by remember { mutableStateOf<List<Answer>?>(null) }
    var error by remember { mutableStateOf("") }
    // The mic button: muted, the phone stops listening, so talk around the user never becomes an answer.
    val muted = remember { MutableStateFlow(false) }
    val isMuted by muted.collectAsState()

    LaunchedEffect(micAllowed) {
        if (!micAllowed) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }
        try {
            answers = QuestionLoop(context, language, speaker, muted) { state = it }.run(questions)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Loop", "Question loop failed", e)
            error = "Error: ${e.message}"
        }
    }

    val done = answers
    val now = state
    Column(modifier.padding(horizontal = 16.dp)) {
        when {
            error.isNotEmpty() -> Text(error, color = MaterialTheme.colorScheme.error)
            !micAllowed -> Text(stringResource(R.string.mic_permission_needed))
            done != null -> ResultScreen(done, photo, Modifier.fillMaxSize())
            now != null && now.review != null -> {
                AnswerList(now.review, stringResource(R.string.all_right_title), Modifier.weight(1f))
                MicButton(now.listening, isMuted) { muted.value = !isMuted }
            }
            now != null -> {
                Asking(now, language, Modifier.weight(1f))
                MicButton(now.listening, isMuted) { muted.value = !isMuted }
            }
        }
    }
}

// One question: progress, the question in a card, its options as chips, what was heard, the value to confirm.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Asking(now: LoopState, language: Language, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.question_number, now.number, now.total),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary,
        )
        LinearProgressIndicator(
            progress = { if (now.total > 0) now.number / now.total.toFloat() else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp).height(6.dp),
            strokeCap = StrokeCap.Round,
        )
        Surface(shape = RoundedCornerShape(24.dp), color = colors.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Text(
                now.question,
                Modifier.padding(20.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = colors.onPrimaryContainer,
            )
        }
        if (now.options.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                now.options.forEach { option ->
                    // The option the answer matched lights up green while the phone asks "सही है?".
                    val picked = now.value.isNotEmpty() &&
                        (option == now.value || (language == Language.HINDI && option == hindiOption(now.value)))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (picked) colors.tertiary else colors.secondaryContainer,
                    ) {
                        Text(
                            option,
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (picked) colors.onTertiary else colors.onSecondaryContainer,
                        )
                    }
                }
            }
        }
        if (now.heard.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.heard_label), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    Text("“${now.heard}”", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        // Not saved yet: the phone is asking "सही है?".
        if (now.value.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colors.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.is_this_right), style = MaterialTheme.typography.labelLarge, color = colors.onTertiaryContainer)
                    Text(
                        now.value,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

// Big round mic: pulses blue while the phone listens, grey while it speaks, red when muted. Tap to mute / unmute.
@Composable
private fun MicButton(listening: Boolean, muted: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val live = listening && !muted
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = when {
                muted -> colors.errorContainer
                live -> colors.primary
                else -> colors.surfaceContainerHighest
            },
            shadowElevation = if (live) 8.dp else 0.dp,
            modifier = Modifier.size(80.dp).scale(if (live) pulse else 1f),
        ) {
            Box(contentAlignment = Alignment.Center) { Text(if (muted) "🔇" else "🎤", fontSize = 34.sp) }
        }
        Text(
            stringResource(
                when {
                    muted -> R.string.mic_muted
                    live -> R.string.speak_now
                    else -> R.string.phone_speaking
                }
            ),
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (live) FontWeight.Bold else FontWeight.Normal,
            color = when {
                muted -> colors.error
                live -> colors.primary
                else -> colors.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
        )
    }
}
