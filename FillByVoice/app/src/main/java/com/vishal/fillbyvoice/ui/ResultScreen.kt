package com.vishal.fillbyvoice.ui

import android.graphics.Bitmap
import com.vishal.fillbyvoice.log.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vishal.fillbyvoice.R
import com.vishal.fillbyvoice.flow.Answer
import com.vishal.fillbyvoice.output.fillForm
import com.vishal.fillbyvoice.output.saveToDownloads
import com.vishal.fillbyvoice.output.sharePdf
import com.vishal.fillbyvoice.output.writeAnswerSheet
import com.vishal.fillbyvoice.pipeline.cleanLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "Pdf"

// Parts 9 + 12: the filled form (the scanned photo with the answers written in), the answer sheet (each form label
// and its answer, big text), and Download / Share for the PDF of both.
@Composable
fun ResultScreen(answers: List<Answer>, photo: Bitmap, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Drawn off the main thread: the photo is large.
    val filled by produceState<Bitmap?>(null, answers) {
        value = try {
            withContext(Dispatchers.Default) { fillForm(photo, answers) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Filled form failed", e)
            null
        }
    }
    // Tap the filled form to see it bigger.
    var bigForm by remember { mutableStateOf(false) }
    // Written on the first tap; Download and Share then use the same file (kept only once it has the filled form).
    var pdf by remember { mutableStateOf<File?>(null) }
    fun withPdf(action: (File) -> Unit) {
        try {
            action(pdf ?: writeAnswerSheet(context, answers, filled).also { if (filled != null) pdf = it })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Answer sheet failed", e)
            Toast.makeText(context, R.string.pdf_failed, Toast.LENGTH_LONG).show()
        }
    }

    Column(modifier) {
        filled?.let { form ->
            Text(
                stringResource(R.string.filled_form),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Image(
                bitmap = remember(form) { form.asImageBitmap() },
                contentDescription = stringResource(R.string.filled_form),
                modifier = Modifier.fillMaxWidth().height(if (bigForm) 420.dp else 200.dp).padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp)).clickable { bigForm = !bigForm },
            )
        }
        AnswerList(answers, stringResource(R.string.answers_title), Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    withPdf {
                        saveToDownloads(context, it)
                        Log.i(TAG, "Saved ${it.name} to Downloads")
                        Toast.makeText(context, context.getString(R.string.pdf_saved, it.name), Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(stringResource(R.string.pdf_download), style = MaterialTheme.typography.titleMedium) }
            FilledTonalButton(
                onClick = { withPdf { sharePdf(context, it, context.getString(R.string.pdf_share_title)) } },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(stringResource(R.string.pdf_share), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

// Each question as printed on the form (numbered, to find it on the paper) and its answer in big text.
// Also shown during the read-back, with "सब सही है?" as the title.
@Composable
fun AnswerList(answers: List<Answer>, title: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.primary)
                Text(
                    stringResource(R.string.answers_count, answers.count { it.value != null }, answers.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(answers) { i, answer ->
            val value = answer.value
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (value != null) colors.surfaceContainerLowest else colors.surfaceContainer,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "${i + 1}. ${cleanLabel(answer.question.label)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                    if (value != null) {
                        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(
                            stringResource(R.string.answer_skipped),
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
