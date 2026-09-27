package com.vishal.fillbyvoice.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.vishal.fillbyvoice.log.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Voice"

// The recognizer once heard speech start and then never answered, and the app waited 5 minutes (06:49 run).
// After STOP_MS it is told to stop and give what it has; after GIVE_UP_MS the loop goes on with what was caught.
private const val STOP_MS = 12_000L
private const val GIVE_UP_MS = 16_000L

// Error 13: the language's offline speech pack is not on the phone (09:16 run: every Hindi answer failed with it).
private const val NO_OFFLINE_PACK = 13
private const val SERVER_DISCONNECTED = 11

// Languages whose offline pack was missing: they listen online from then on (needs internet).
private val online = mutableSetOf<Language>()

// The phone's ears: listens once and returns what the user said, or null if nothing was heard.
// Offline first (airplane mode); if the language's offline pack is missing, online instead.
// waitMs: give up if the user has not started speaking by then (listening for the rest of a long answer,
// where a quiet moment means the answer is complete). Null: the recognizer's own timeout.
suspend fun listen(context: Context, language: Language, waitMs: Long? = null): String? {
    if (language !in online) {
        try {
            return listenOnce(context, language, waitMs, offline = true)
        } catch (e: IllegalStateException) {
            if (e.message != "Speech error $NO_OFFLINE_PACK") throw e
            Log.w(TAG, "No offline speech pack for ${language.listenLocale.toLanguageTag()}: listening online from now on")
            online += language
        }
    }
    return try {
        listenOnce(context, language, waitMs, offline = false)
    } catch (e: IllegalStateException) {
        // 11: the online recognizer dropped its connection at once, right after the switch (09:18 and 09:24 runs),
        // and that answer was lost. A moment later it works.
        if (e.message != "Speech error $SERVER_DISCONNECTED") throw e
        delay(300)
        listenOnce(context, language, waitMs, offline = false)
    }
}

// Listens once. Any recognizer problem other than silence throws, with Android's error code, so it shows on screen.
// Android requires SpeechRecognizer to be used on the main thread.
private suspend fun listenOnce(context: Context, language: Language, waitMs: Long?, offline: Boolean): String? =
    suspendCancellableCoroutine { cont ->
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    val timer = Handler(Looper.getMainLooper())

    fun finish(text: String?, error: Int? = null) {
        if (!cont.isActive) return
        timer.removeCallbacksAndMessages(null)
        recognizer.destroy()
        if (error == null) cont.resume(text) else cont.resumeWithException(IllegalStateException("Speech error $error"))
    }

    // What the recognizer caught while the user was still speaking. The offline recognizer sometimes drops a short
    // word ("yes") at the end with "no match", although it showed it here a moment before.
    var partial: String? = null
    var started = false

    if (waitMs != null) {
        timer.postDelayed({
            if (!started) {
                Log.i(TAG, "  nothing more said")
                finish(null)
            }
        }, waitMs)
    }
    timer.postDelayed({
        Log.i(TAG, "  listening too long, asking the recognizer to stop")
        recognizer.stopListening()
    }, STOP_MS)
    timer.postDelayed({
        Log.w(TAG, "Recognizer did not answer, going on with what was caught: $partial")
        finish(partial)
    }, GIVE_UP_MS)

    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            Log.i(TAG, "Heard: ${heard.joinToString(" | ")}") // all guesses, best first
            finish(heard.firstOrNull() ?: partial)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() && it != partial }?.let {
                    Log.i(TAG, "  so far: $it")
                    partial = it
                }
        }

        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "  mic open, speak now")
        }

        override fun onBeginningOfSpeech() {
            started = true
            Log.i(TAG, "  speech started")
        }

        override fun onEndOfSpeech() {
            Log.i(TAG, "  speech ended")
        }

        override fun onError(error: Int) = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                Log.i(TAG, if (partial != null) "No match (error $error), using what was caught: $partial" else "Heard nothing (error $error)")
                finish(partial)
            }
            else -> {
                Log.w(TAG, "Speech error $error")
                finish(null, error)
            }
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    Log.i(TAG, "Listening in ${language.listenLocale.toLanguageTag()}${if (offline) "" else ", online"}")
    recognizer.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.listenLocale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // A short pause ("It is ... 25 March") ended the answer after "It is". Ask for 2 s of silence first
            // (some recognizers ignore this; the 12 s stop above still holds).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
    )
    cont.invokeOnCancellation {
        context.mainExecutor.execute {
            timer.removeCallbacksAndMessages(null)
            recognizer.destroy()
        }
    }
}
