package com.vishal.fillbyvoice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vishal.fillbyvoice.R
import com.vishal.fillbyvoice.voice.Language
import com.vishal.fillbyvoice.voice.Speaker
import kotlinx.coroutines.delay

// Every app start: the phone asks out loud, like a bank's phone line, "Press 1 for English. हिंदी के लिए 2 दबाएँ।",
// so someone who cannot read the screen can still pick. Asked twice if nobody taps; a tap stops the voice.
@Composable
fun LanguageScreen(onChoose: (Language) -> Unit) {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    LaunchedEffect(Unit) {
        try {
            repeat(2) {
                speaker.speak("Press 1 for English.", Language.ENGLISH)
                speaker.speak("हिंदी के लिए 2 दबाएँ।", Language.HINDI)
                delay(5_000)
            }
        } catch (e: IllegalStateException) {
            // No text-to-speech on this phone: the buttons still work.
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.language_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.language_prompt),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Choice("1", stringResource(R.string.language_english)) { onChoose(Language.ENGLISH) }
        Choice("2", stringResource(R.string.language_hindi)) { onChoose(Language.HINDI) }
    }
}

// A big numbered button: the number is what the phone says to press.
@Composable
private fun Choice(number: String, name: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(120.dp), shape = RoundedCornerShape(28.dp)) {
        Text(number, fontSize = 56.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        Text(name, style = MaterialTheme.typography.headlineMedium)
    }
}
