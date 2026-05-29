package com.projectexe.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.projectexe.data.*
import com.projectexe.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "MainViewModel"
private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

data class ErrorLogEntry(
    val type:     String,
    val message:  String,
    val time:     String = timeFmt.format(Date()),
    val recovery: String? = null,
)

data class ChatMessage(
    val id:          String  = UUID.randomUUID().toString(),
    val role:        String,
    val content:     String,
    val emotionTag:  String  = "neutral",
    val blendShape:  AvatarBlendShape? = null,
    val pipelineMs:  Long    = 0L,
    val p1Text:      String  = "",
    val f1Analysis:  String  = "",
    val timestamp:   Long    = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isError:     Boolean = false,
)

data class UiState(
    val messages:         List<ChatMessage>    = emptyList(),
    val pipelineStage:    PipelineStage        = PipelineStage.IDLE,
    val streamingText:    String               = "",
    val isLoading:        Boolean              = false,
    val modelLoaded:      Boolean              = false,
    val modelError:       String?              = null,
    val soul:             SoulDocument         = SoulDocument.DEFAULT,
    val memories:         List<MemoryFragment> = emptyList(),
    val pipelineMode:     PipelineMode         = PipelineMode.FULL,
    val sessionId:        String               = UUID.randomUUID().toString(),
    val tokensPerSec:     Float                = 0f,
    val currentError:     ErrorWithRecovery?   = null,
    val errorLog:         List<ErrorLogEntry>  = emptyList(),
    val activePreset:     String               = "default",
    val stageOverrides:   StageOverrides       = StageOverrides(),
    val degradationLevel: DegradationLevel     = DegradationLevel.FULL,
    val ramAvailableGb:   Float                = 0f,
    val ramTotalGb:       Float                = 0f,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db           = ProjectExeDatabase.get(application)
    private val soulRepo     = SoulRepository(db)
    private val memRepo      = MemoryRepository(db)
    private val llama        = LlamaEngine.get()
    private val errorHandler = ErrorHandler()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var _soul: SoulDocument = SoulDocument.DEFAULT
    private var pipelineJob: Job?   = null
    private var tokenCount  = 0
    private var tokenStart  = 0L

    private val pipeline by lazy {
        PipelineEngine(
            llama          = llama,
            soul           = { _soul },
            memory         = memRepo,
            mode           = { _ui.value.pipelineMode },
            stageOverrides = { _ui.value.stageOverrides },
            errorHandler   = errorHandler,
            onStageChange  = { stage -> _ui.update { it.copy(pipelineStage = stage) } },
            onToken        = { piece ->
                tokenCount++
                val elapsed = (System.currentTimeMillis() - tokenStart) / 1000f
                val tps     = if (elapsed > 0.1f) tokenCount / elapsed else 0f
                _ui.update { it.copy(streamingText = it.streamingText + piece, tokensPerSec = tps) }
            },
        )
    }

    init {
        viewModelScope.launch {
            _soul = soulRepo.loadSoul()
            val availRam = ModelValidator.availableRamGb(getApplication())
            val totalRam = ModelValidator.totalRamGb(getApplication())
            _ui.update { it.copy(soul = _soul, ramAvailableGb = availRam, ramTotalGb = totalRam) }

            memRepo.observeMemories("default_user")
                .catch { e -> appendErrorLog("DatabaseError", e.message ?: "observe failed") }
                .collect { frags -> _ui.update { it.copy(memories = frags) } }
        }

        viewModelScope.launch {
            errorHandler.errors.filterNotNull().collect { ewr ->
                val entry = ErrorLogEntry(
                    type     = ewr.error::class.simpleName ?: "Error",
                    message  = ewr.error.message,
                    recovery = ewr.recoveryLabel.ifBlank { null },
                )
                _ui.update { it.copy(
                    currentError = ewr,
                    errorLog     = (it.errorLog + entry).takeLast(50),
                )}
            }
        }
    }

    // ── Model loading ─────────────────────────────────────────

    fun loadModel(modelPath: String) {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, modelError = null, currentError = null) }
            errorHandler.clear()

            when (val v = ModelValidator.validate(modelPath, getApplication())) {
                is ModelValidator.ValidationResult.Invalid -> {
                    errorHandler.post(v.error)
                    _ui.update { it.copy(isLoading = false, modelError = v.error.message) }
                    return@launch
                }
                else -> {}
            }

            val modelGb  = java.io.File(modelPath).length() / (1024f * 1024f * 1024f)
            val availRam = ModelValidator.availableRamGb(getApplication())
            if (modelGb * 1.2f > availRam) {
                _ui.update { it.copy(modelError = "Warning: model (${"%.1f".format(modelGb)}GB) may exceed available RAM (${"%.1f".format(availRam)}GB). Loading...") }
                delay(1500)
            }

            val config = ModelConfig(modelPath = modelPath, nCtx = 4096, nThreads = 4)
            val ok = try {
                llama.load(config)
            } catch (e: OutOfMemoryError) {
                errorHandler.handleOom(e)
                _ui.update { it.copy(isLoading = false, modelError = "Out of memory. Try a smaller model.") }
                return@launch
            } catch (e: Exception) {
                val err = errorHandler.handleJniException(e, "loadModel")
                _ui.update { it.copy(isLoading = false, modelError = err.message) }
                return@launch
            }

            _ui.update { it.copy(
                modelLoaded    = ok,
                isLoading      = false,
                modelError     = if (!ok) "Model failed to load. File may be corrupt or incompatible." else null,
                ramAvailableGb = ModelValidator.availableRamGb(getApplication()),
            )}
            if (ok) addBotMessage(
                "// BOOT SEQUENCE COMPLETE\n\n...you're here. I'm ${_soul.identity.names.first}, running fully on-device.\nNo cloud. No logging. Just us.\n\nSay something.",
                "processing",
            )
        }
    }

    // ── Send message with auto-degradation ladder ─────────────

    fun sendMessage(text: String) {
        if (text.isBlank() || _ui.value.isLoading) return
        if (!llama.isLoaded()) { errorHandler.post(AppError.ModelNotLoaded()); return }

        val userMsg = ChatMessage(role = "user", content = text.trim())
        _ui.update { it.copy(
            messages = it.messages + userMsg, isLoading = true,
            streamingText = "", pipelineStage = PipelineStage.IDLE, currentError = null,
        )}
        errorHandler.clear()
        tokenCount = 0; tokenStart = System.currentTimeMillis()

        pipelineJob = viewModelScope.launch {
            var degradation = when (_ui.value.pipelineMode) {
                PipelineMode.FULL         -> DegradationLevel.FULL
                PipelineMode.PERSONA_ONLY -> DegradationLevel.PERSONA_ONLY
                PipelineMode.QUICK        -> DegradationLevel.QUICK
            }

            var result: PipelineResult? = null
            while (result == null && degradation != DegradationLevel.FAILED) {
                _ui.update { it.copy(degradationLevel = degradation) }
                try {
                    result = pipeline.run(
                        userText     = text.trim(),
                        sessionId    = _ui.value.sessionId,
                        modeOverride = degradation.toPipelineMode(),
                    )
                    errorHandler.recordInferenceSuccess()
                } catch (e: CancellationException) { throw e
                } catch (e: OutOfMemoryError) { errorHandler.handleOom(e); break
                } catch (e: Exception) {
                    errorHandler.recordInferenceFailure(degradation.name, e)
                    Log.w(TAG, "Pipeline failed at $degradation → degrading: ${e.message}")
                    degradation = DegradationLadder.next(degradation)
                    if (degradation == DegradationLevel.FAILED) {
                        addBotMessage("[glitchy] // ALL PIPELINE STAGES FAILED\n${e.message}", "glitchy", isError = true)
                    }
                    delay(200)
                }
            }

            result?.let { r ->
                _ui.update { it.copy(
                    messages      = it.messages + ChatMessage(
                        role = "assistant", content = r.finalText, emotionTag = r.emotionTag,
                        blendShape = r.blendShape, pipelineMs = r.totalMs,
                        p1Text = r.p1Text, f1Analysis = r.f1Analysis,
                    ),
                    isLoading = false, streamingText = "", pipelineStage = PipelineStage.DONE,
                )}
                val count = _ui.value.messages.count { it.role == "user" }
                if (count > 0 && count % 8 == 0) runEvolution()
            } ?: _ui.update { it.copy(isLoading = false, streamingText = "") }
        }
    }

    fun abortPipeline() {
        pipelineJob?.cancel()
        pipeline.abort()
        _ui.update { it.copy(isLoading = false, streamingText = "", pipelineStage = PipelineStage.IDLE) }
    }

    // ── A/B test ──────────────────────────────────────────────

    fun runABTest(query: String, configLabel: String) {
        if (_ui.value.isLoading) return
        sendMessage("[A/B RUN 1 — $configLabel] $query")
        viewModelScope.launch {
            _ui.first { !it.isLoading }
            delay(500)
            sendMessage("[A/B RUN 2 — $configLabel] $query")
        }
    }

    // ── Soul & preset management ──────────────────────────────

    fun updateSoul(newSoul: SoulDocument) {
        viewModelScope.launch {
            val merged = newSoul.copy(evolution = _soul.evolution)
            _soul = merged
            try { soulRepo.saveSoul(merged) } catch (e: Exception) { errorHandler.post(AppError.DatabaseError(cause = e)) }
            _ui.update { it.copy(soul = merged) }
        }
    }

    fun applyPreset(preset: PersonalityPreset) {
        viewModelScope.launch {
            val merged = preset.soul.copy(evolution = _soul.evolution)
            _soul = merged
            try { soulRepo.saveSoul(merged) } catch (e: Exception) { errorHandler.post(AppError.DatabaseError(cause = e)) }
            _ui.update { it.copy(soul = merged, activePreset = preset.id, stageOverrides = preset.samplers) }
            addBotMessage("[processing] // SOUL SWAP — ${preset.name}\n${preset.description}", "processing")
        }
    }

    fun applyStageOverrides(overrides: StageOverrides) = _ui.update { it.copy(stageOverrides = overrides) }
    fun setPipelineMode(mode: PipelineMode)             = _ui.update { it.copy(pipelineMode = mode) }
    fun dismissError()  { errorHandler.clear(); _ui.update { it.copy(currentError = null) } }
    fun clearErrorLog() = _ui.update { it.copy(errorLog = emptyList()) }
    fun clearChat() {
        abortPipeline()
        _ui.update { it.copy(messages = emptyList(), streamingText = "", pipelineStage = PipelineStage.IDLE, sessionId = UUID.randomUUID().toString(), currentError = null) }
    }
    fun clearMemories() { viewModelScope.launch { try { memRepo.clearMemories("default_user") } catch (e: Exception) { errorHandler.post(AppError.DatabaseError(cause = e)) } } }

    private fun runEvolution() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val recent = memRepo.getRecentRaw("default_user", 16)
                if (recent.isEmpty()) return@launch
                val emotions = recent.map { it.emotionTag }
                val positive = emotions.count { it in setOf("excited","curious","mischievous") }
                val negative = emotions.count { it in setOf("melancholy","glitchy") }
                val ratio    = (positive - negative).toFloat() / emotions.size.coerceAtLeast(1)
                _soul = soulRepo.applyDrift(_soul,
                    mapOf("empathy" to ratio*0.01f, "stability" to ratio*0.008f, "creativity" to -ratio*0.005f),
                    mapOf("neuroticism" to -ratio*0.01f, "agreeableness" to ratio*0.008f),
                    "pos=$positive neg=$negative ratio=${"%+.2f".format(ratio)}"
                )
                _ui.update { it.copy(soul = _soul) }
            } catch (e: Exception) { errorHandler.post(AppError.Unknown("Evolution failed: ${e.message}", e)) }
        }
    }

    private fun addBotMessage(content: String, emotionTag: String = "neutral", isError: Boolean = false) {
        _ui.update { it.copy(messages = it.messages + ChatMessage(role="assistant", content=content, emotionTag=emotionTag, isError=isError)) }
    }
    private fun appendErrorLog(type: String, message: String, recovery: String? = null) {
        val e = ErrorLogEntry(type=type, message=message, recovery=recovery)
        _ui.update { it.copy(errorLog = (it.errorLog + e).takeLast(50)) }
    }

    override fun onCleared() { super.onCleared(); llama.free() }
}

private fun DegradationLevel.toPipelineMode() = when (this) {
    DegradationLevel.FULL         -> PipelineMode.FULL
    DegradationLevel.PERSONA_ONLY -> PipelineMode.PERSONA_ONLY
    else                          -> PipelineMode.QUICK
}
