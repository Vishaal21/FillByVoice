package com.vishal.fillbyvoice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vishal.fillbyvoice.R
import com.vishal.fillbyvoice.llm.Gemma
import com.vishal.fillbyvoice.voice.Language
import kotlinx.coroutines.launch

// Test prompt from the AI Edge Gallery tests: a short, checkable answer (25/03/1990).
private const val TEST_PROMPT = "Write this date as DD/MM/YYYY. Reply with only the date: 25 March 1990"

@Composable
fun SettingsScreen(language: Language, onLanguage: (Language) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val loading = stringResource(R.string.gemma_loading)
    val thinking = stringResource(R.string.gemma_thinking)

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.language_title))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterChip(
                selected = language == Language.HINDI,
                onClick = { onLanguage(Language.HINDI) },
                label = { Text(stringResource(R.string.language_hindi)) },
            )
            FilterChip(
                selected = language == Language.ENGLISH,
                onClick = { onLanguage(Language.ENGLISH) },
                label = { Text(stringResource(R.string.language_english)) },
            )
        }
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        status = loading
                        Gemma.load(context)
                        status = thinking
                        val reply = Gemma.ask(TEST_PROMPT)
                        val speed = context.getString(R.string.gemma_speed, Gemma.backendName, reply.tokensPerSecond)
                        status = reply.text + "\n\n" + speed
                    } catch (e: Exception) {
                        status = "Error: ${e.message}"
                    }
                    busy = false
                }
            },
        ) {
            Text(stringResource(R.string.gemma_test))
        }
        Text(status)
    }
}
