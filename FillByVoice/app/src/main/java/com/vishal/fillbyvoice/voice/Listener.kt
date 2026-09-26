package com.vishal.fillbyvoice.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The phone's ears: listens once and returns what the user said, or null if nothing was heard.
// Any other recognizer problem throws, with Android's error code, so it shows on screen.
// Android requires SpeechRecognizer to be used on the main thread.
suspend fun listen(context: Context, language: Language): String? = suspendCancellableCoroutine { cont ->
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

    fun finish(text: String?, error: Int? = null) {
        recognizer.destroy()
        if (!cont.isActive) return
        if (error == null) cont.resume(text) else cont.resumeWithException(IllegalStateException("Speech error $error"))
    }

    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onResults(results: Bundle?) =
            finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())

        override fun onError(error: Int) = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(null)
            else -> finish(null, error)
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    recognizer.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    )
    cont.invokeOnCancellation { context.mainExecutor.execute { recognizer.destroy() } }
}
