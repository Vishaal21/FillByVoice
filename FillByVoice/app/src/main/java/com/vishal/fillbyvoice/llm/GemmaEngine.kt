package com.vishal.fillbyvoice.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.ResponseFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Copied onto the phone with adb (see PROJECT-BRIEF section 6). Never bundled in the APK.
private const val MODEL_FILE = "gemma-4-E2B-it.litertlm"

data class GemmaReply(val text: String, val tokensPerSecond: Double)

// Gemma on the phone. Loaded once per app run, then used for every prompt. Fully on-device.
// ExperimentalApi: the benchmark flag that gives us real tokens/sec for the speed line.
@OptIn(ExperimentalApi::class)
object Gemma {
    private var engine: Engine? = null

    val backendName: String get() = engine?.engineConfig?.backend?.name.orEmpty()

    // Loads the model on GPU, falls back to CPU. Takes a few seconds the first time.
    suspend fun load(context: Context) = withContext(Dispatchers.Default) {
        if (engine != null) return@withContext
        val model = File(context.getExternalFilesDir(null), MODEL_FILE)
        check(model.exists()) { "Model file not found: ${model.path}" }
        ExperimentalFlags.enableBenchmark = true
        // The cache keeps the compiled GPU program, so later app starts load faster.
        val cache = context.cacheDir.path
        engine = try {
            start(model.path, Backend.GPU(), cache)
        } catch (e: LiteRtLmJniException) {
            start(model.path, Backend.CPU(), cache)
        }
    }

    // One prompt in, one reply out. A fresh conversation each time, so prompts never mix.
    // With a JSON schema, the runtime forces the reply to be valid JSON of that shape.
    suspend fun ask(prompt: String, jsonSchema: String? = null): GemmaReply = withContext(Dispatchers.Default) {
        val engine = checkNotNull(engine) { "Gemma is not loaded" }
        val config = ConversationConfig(enableResponseFormat = jsonSchema != null)
        engine.createConversation(config).use { conversation ->
            val reply = if (jsonSchema == null) {
                conversation.sendMessage(prompt)
            } else {
                conversation.sendMessage(prompt, responseFormat = ResponseFormat.json(jsonSchema))
            }
            val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            GemmaReply(text.trim(), conversation.getBenchmarkInfo().lastDecodeTokensPerSecond)
        }
    }

    private fun start(path: String, backend: Backend, cacheDir: String) =
        Engine(EngineConfig(modelPath = path, backend = backend, cacheDir = cacheDir)).apply { initialize() }
}
