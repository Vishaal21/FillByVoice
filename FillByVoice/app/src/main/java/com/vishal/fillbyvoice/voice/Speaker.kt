package com.vishal.fillbyvoice.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// The phone's voice (Android TextToSpeech, offline Hindi / English voices).
class Speaker(context: Context) {
    private val ready = CompletableDeferred<Unit>()
    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) ready.complete(Unit)
        else ready.completeExceptionally(IllegalStateException("Text-to-speech is not available"))
    }

    // Speaks the text and returns only when it has finished, so the mic never hears the phone.
    suspend fun speak(text: String, language: Language) {
        ready.await()
        tts.language = language.locale
        suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish()

                @Deprecated("Required by the abstract class")
                override fun onError(utteranceId: String?) = finish()

                private fun finish() {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "question")
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    fun shutdown() = tts.shutdown()
}
