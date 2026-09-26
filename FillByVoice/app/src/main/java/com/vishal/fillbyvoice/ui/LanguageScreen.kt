package com.vishal.fillbyvoice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vishal.fillbyvoice.R
import com.vishal.fillbyvoice.voice.Language

// First launch: pick the language for all questions and read-backs.
@Composable
fun LanguageScreen(onChoose: (Language) -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.language_title), style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { onChoose(Language.HINDI) }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.language_hindi), style = MaterialTheme.typography.headlineMedium)
        }
        Button(onClick = { onChoose(Language.ENGLISH) }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.language_english), style = MaterialTheme.typography.headlineMedium)
        }
    }
}
