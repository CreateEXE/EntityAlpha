package com.projectexe.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

private const val TAG = "LlamaEngine"

// ─────────────────────────────────────────────────────────────
//  TokenCallback — called from C++ for each generated token.
//  Bridges JNI → Kotlin Flow via a channel.
// ─────────────────────────────────────────────────────────────

class TokenCallback(
    private val onToken: (String) -> Unit,
) {
    /** Called from llama_jni.cpp on each generated token */
    fun onToken(piece: String) { onToken.invoke(piece) }
}

// ─────────────────────────────────────────────────────────────
//  ModelConfig — settings per GGUF model
// ─────────────────────────────────────────────────────────────

data class ModelConfig(
    val modelPath:   String,
    val nCtx:        Int   = 4096,
    /** Use 4 threads for Cortex-A78 big cores; leave 4 A55 for the OS */
    val nThreads:    Int   = 4,
    /** Max tokens generated per call */
    val maxTokens:   Int   = 512,
    val repeatPenalty: Float = 1.10f,
)

// ─────────────────────────────────────────────────────────────
//  InferenceRequest — one pipeline stage call
// ─────────────────────────────────────────────────────────────

data class InferenceRequest(
    val systemPrompt:  String,
    val userContent:   String,
    val temperature:   Float,
    val topP:          Float  = 0.95f,
    val maxTokens:     Int    = 512,
    val repeatPenalty: Float  = 1.10f,
    val streaming:     Boolean = true,
)

// ─────────────────────────────────────────────────────────────
//  LlamaEngine — singleton wrapping native llama.cpp via JNI
// ─────────────────────────────────────────────────────────────

class LlamaEngine private constructor() {

    // ── Native method declarations (implemented in llama_jni.cpp) ──
    private external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int): Boolean
    private external fun isModelLoaded(): Boolean
    private external fun completion(
        systemPrompt:  String,
        userContent:   String,
        temperature:   Float,
        topP:          Float,
        maxTokens:     Int,
        repeatPenalty: Float,
        callback:      TokenCallback?,
    ): String
    private external fun abortGeneration()
    private external fun getEmbedding(text: String): FloatArray
    private external fun getContextSize(): Int
    private external fun getVocabSize(): Int
    private external fun freeModel()

    // ── State ──────────────────────────────────────────────────
    private var currentConfig: ModelConfig? = null
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Model management ───────────────────────────────────────

    suspend fun load(config: ModelConfig): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Loading: ${config.modelPath}")
        val file = File(config.modelPath)
        if (!file.exists()) {
            Log.e(TAG, "Model file not found: ${config.modelPath}")
            return@withContext false
        }
        val ok = loadModel(config.modelPath, config.nCtx, config.nThreads)
        if (ok) {
            currentConfig = config
            Log.i(TAG, "Model loaded OK | ctx=${getContextSize()} | vocab=${getVocabSize()}")
        } else {
            Log.e(TAG, "loadModel() returned false")
        }
        ok
    }

    fun isLoaded(): Boolean = runCatching { isModelLoaded() }.getOrDefault(false)

    fun free() {
        runCatching { freeModel() }
        currentConfig = null
    }

    // ── Inference — streaming via Flow ─────────────────────────

    /**
     * Runs one pipeline stage inference call.
     * Emits each token piece as it is generated.
     * The Flow completes when the model stops or max_tokens is reached.
     * Collect the flow with .toList().joinToString("") to get the full response.
     */
    fun completionFlow(req: InferenceRequest): Flow<String> = flow {
        if (!isLoaded()) {
            emit("[ERROR: model not loaded]")
            return@flow
        }
        val buffer = StringBuilder()
        val callback = if (req.streaming) {
            TokenCallback { piece -> buffer.append(piece) }
        } else null

        val result = withContext(Dispatchers.IO) {
            completion(
                systemPrompt  = req.systemPrompt,
                userContent   = req.userContent,
                temperature   = req.temperature,
                topP          = req.topP,
                maxTokens     = req.maxTokens,
                repeatPenalty = req.repeatPenalty,
                callback      = callback,
            )
        }
        // For streaming, emit via buffer; JNI already called callback for each token.
        // Here we emit the full result once — streaming display is driven by ViewModel
        // collecting partial strings via a separate shared flow.
        emit(result)
    }.flowOn(Dispatchers.IO)

    /**
     * Runs inference with live token streaming.
     * [onToken] is called on Main for each piece so UI updates immediately.
     * Returns the complete response string.
     */
    suspend fun completionStreaming(
        req: InferenceRequest,
        onToken: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (!isLoaded()) return@withContext "[ERROR: model not loaded]"
        val cb = TokenCallback { piece ->
            // Post to main thread for UI update
            CoroutineScope(Dispatchers.Main).launch { onToken(piece) }
        }
        completion(
            systemPrompt  = req.systemPrompt,
            userContent   = req.userContent,
            temperature   = req.temperature,
            topP          = req.topP,
            maxTokens     = req.maxTokens,
            repeatPenalty = req.repeatPenalty,
            callback      = cb,
        )
    }

    fun abort() { runCatching { abortGeneration() } }

    // ── Embedding (Node 4 semantic memory) ─────────────────────

    suspend fun embed(text: String): List<Float> = withContext(Dispatchers.IO) {
        if (!isLoaded()) return@withContext emptyList()
        getEmbedding(text.take(512)).toList()
    }

    companion object {
        @Volatile private var INSTANCE: LlamaEngine? = null

        fun get(): LlamaEngine = INSTANCE ?: synchronized(this) {
            LlamaEngine().also { INSTANCE = it }
        }

        fun init() {
            System.loadLibrary("projectexe_jni")
            Log.i(TAG, "JNI library loaded")
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  MODEL FILE MANAGER — handles GGUF storage on device
// ─────────────────────────────────────────────────────────────

object ModelFileManager {

    /**
     * Models go in app's external files dir — doesn't require permissions,
     * survives uninstall only if user wants (external), survives app update.
     *
     * Full path example:
     * /sdcard/Android/data/com.projectexe/files/models/qwen2.5-1.5b-q4_k_m.gguf
     */
    fun getModelsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        dir.mkdirs()
        return dir
    }

    fun listModels(context: Context): List<File> =
        getModelsDir(context).listFiles { f -> f.extension == "gguf" }?.toList() ?: emptyList()

    fun getDefaultModelPath(context: Context): String? =
        listModels(context).firstOrNull()?.absolutePath

    fun modelExists(context: Context, filename: String): Boolean =
        File(getModelsDir(context), filename).exists()

    /** Recommended models for Revvl 7 (6GB RAM) */
    val RECOMMENDED_MODELS = listOf(
        ModelInfo(
            filename   = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            displayName= "Qwen2.5 1.5B (Fast — ~20 tok/s)",
            sizeGb     = 1.0f,
            quality    = "Good",
            hfRepo     = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        ),
        ModelInfo(
            filename   = "qwen2.5-3b-instruct-q4_k_m.gguf",
            displayName= "Qwen2.5 3B (Balanced — ~10 tok/s)",
            sizeGb     = 1.9f,
            quality    = "Better",
            hfRepo     = "Qwen/Qwen2.5-3B-Instruct-GGUF",
        ),
        ModelInfo(
            filename   = "gemma-2-2b-it-q4_k_m.gguf",
            displayName= "Gemma 2 2B (Alt — ~15 tok/s)",
            sizeGb     = 1.5f,
            quality    = "Good",
            hfRepo     = "google/gemma-2-2b-it-GGUF",
        ),
    )

    data class ModelInfo(
        val filename:    String,
        val displayName: String,
        val sizeGb:      Float,
        val quality:     String,
        val hfRepo:      String,
    )
}
