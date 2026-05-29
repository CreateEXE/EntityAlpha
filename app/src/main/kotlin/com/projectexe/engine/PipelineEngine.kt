package com.projectexe.engine

import android.util.Log
import com.projectexe.data.*
import kotlinx.coroutines.*

private const val TAG = "PipelineEngine"

class PipelineEngine(
    private val llama:          LlamaEngine,
    private val soul:           () -> SoulDocument,
    private val memory:         MemoryRepository,
    private val mode:           () -> PipelineMode,
    private val stageOverrides: () -> StageOverrides = { StageOverrides() },
    private val errorHandler:   ErrorHandler,
    private val onStageChange:  (PipelineStage) -> Unit = {},
    private val onToken:        (String) -> Unit        = {},
) {
    private var currentJob: Job? = null

    suspend fun run(
        userText:     String,
        sessionId:    String,
        userId:       String       = "default_user",
        modeOverride: PipelineMode? = null,
    ): PipelineResult = coroutineScope {

        val startMs  = System.currentTimeMillis()
        val curSoul  = soul()
        val runMode  = modeOverride ?: mode()
        val overrides = stageOverrides()

        // ── Phase 1: Ingestion + Security ──────────────────────
        onStageChange(PipelineStage.IDLE)
        val (sanitized, blocked) = sanitizeInput(userText)
        if (blocked != null) {
            errorHandler.post(AppError.SecurityBlocked(blocked))
            return@coroutineScope PipelineResult(
                finalText      = "[glitchy] ...that triggered a guardrail. Can't process that input.",
                emotionTag     = "glitchy",
                blendShape     = EMOTION_BLEND_MAP["glitchy"]!!,
                voiceModifiers = EMOTION_VOICE_MAP["glitchy"]!!,
            )
        }

        // ── Phase 2: Parallel cognitive array ──────────────────
        val memories = errorHandler.runSafe("node4_recall") {
            val emb = llama.embed(sanitized)
            memory.recallSemantic(emb, userId, nResults = 5)
        }.getOrDefault(emptyList())

        val mood = MoodReading.fromText(sanitized)
        val soulWithMood = curSoul.withMood(mood)

        // ── Phase 4: Dual-hemisphere pipeline ──────────────────
        var p1Text = ""; var f1Analysis = ""; var p2Text = ""; var f2Result = ""
        var finalText = ""; var emotionTag = "neutral"

        when (runMode) {
            PipelineMode.QUICK -> {
                onStageChange(PipelineStage.P1)
                val (t, e) = runStage(SoulCompiler.Stage.P1, soulWithMood, sanitized, memories = memories, overrides = overrides)
                finalText = t; emotionTag = e
            }
            PipelineMode.PERSONA_ONLY -> {
                onStageChange(PipelineStage.P1)
                val (p1, _) = runStage(SoulCompiler.Stage.P1, soulWithMood, sanitized, memories = memories, overrides = overrides)
                p1Text = p1
                onStageChange(PipelineStage.P3)
                val (p3, em) = runStage(SoulCompiler.Stage.P3, soulWithMood, sanitized,
                    p1Response = p1, p2Response = p1, f2Verification = "VERIFICATION_PASSED: Persona-only mode.",
                    overrides = overrides)
                finalText = p3; emotionTag = em
            }
            PipelineMode.FULL -> {
                onStageChange(PipelineStage.P1)
                val (p1, _) = runStage(SoulCompiler.Stage.P1, soulWithMood, sanitized, memories = memories, overrides = overrides)
                p1Text = p1

                onStageChange(PipelineStage.F1)
                val (f1, _) = runStage(SoulCompiler.Stage.F1, soulWithMood, sanitized, p1Response = p1Text, overrides = overrides)
                f1Analysis = f1

                onStageChange(PipelineStage.P2)
                val (p2, _) = runStage(SoulCompiler.Stage.P2, soulWithMood, sanitized,
                    p1Response = p1Text, f1Analysis = f1Analysis, memories = memories, overrides = overrides)
                p2Text = p2

                onStageChange(PipelineStage.F2)
                val (f2, _) = runStage(SoulCompiler.Stage.F2, soulWithMood, sanitized, p2Response = p2Text, overrides = overrides)
                f2Result = f2

                onStageChange(PipelineStage.P3)
                val (p3, em) = runStage(SoulCompiler.Stage.P3, soulWithMood, sanitized,
                    p1Response = p1Text, p2Response = p2Text, f2Verification = f2Result, overrides = overrides)
                finalText = p3; emotionTag = em
            }
        }

        // ── Phase 5: Output + memory ───────────────────────────
        onStageChange(PipelineStage.DONE)
        val blend = EMOTION_BLEND_MAP[emotionTag] ?: EMOTION_BLEND_MAP["neutral"]!!
        val voice = EMOTION_VOICE_MAP[emotionTag]  ?: EMOTION_VOICE_MAP["neutral"]!!
        val neuro = curSoul.psychology.ocean.neuroticism
        val modulatedVoice = voice.copy(pitch_shift = voice.pitch_shift * (1f + (neuro - 0.5f) * 0.4f))

        val totalMs = System.currentTimeMillis() - startMs

        CoroutineScope(Dispatchers.IO).launch {
            val emb = runCatching { llama.embed(finalText) }.getOrDefault(emptyList())
            memory.syncMemory(sessionId, userId, sanitized, finalText, emotionTag, mood, emb, totalMs.toInt())
        }

        PipelineResult(
            finalText        = finalText,
            emotionTag       = emotionTag,
            blendShape       = blend,
            voiceModifiers   = modulatedVoice,
            p1Text           = p1Text,
            f1Analysis       = f1Analysis,
            p2Text           = p2Text,
            f2Verification   = f2Result,
            memoriesRecalled = memories.size,
            totalMs          = totalMs,
        )
    }

    private suspend fun runStage(
        stage:          SoulCompiler.Stage,
        soul:           SoulDocument,
        userQuery:      String,
        p1Response:     String = "",
        f1Analysis:     String = "",
        p2Response:     String = "",
        f2Verification: String = "",
        memories:       List<MemoryFragment> = emptyList(),
        overrides:      StageOverrides,
    ): Pair<String, String> {
        val isPersona = stage in listOf(SoulCompiler.Stage.P1, SoulCompiler.Stage.P2, SoulCompiler.Stage.P3)
        val override  = overrides.forStage(stage)

        val systemPrompt = if (isPersona) SoulCompiler.compilePersona(soul, stage, memories)
                           else           SoulCompiler.compileFactual(stage)
        val userContent  = SoulCompiler.buildUserContent(stage, userQuery, p1Response, f1Analysis, p2Response, f2Verification)

        val temperature = override?.temperature
            ?: if (isPersona) soul.psychology.neural_matrix.personaTemperature()
               else           soul.psychology.neural_matrix.factualTemperature()

        val req = InferenceRequest(
            systemPrompt  = systemPrompt,
            userContent   = userContent,
            temperature   = temperature,
            topP          = override?.topP          ?: 0.95f,
            maxTokens     = override?.maxTokens     ?: SoulCompiler.maxTokensFor(stage),
            repeatPenalty = override?.repeatPenalty ?: 1.10f,
        )

        val t0 = System.currentTimeMillis()
        return try {
            val result = llama.completionStreaming(req) { piece -> onToken(piece) }
            Log.d(TAG, "[$stage] ${result.length} chars in ${System.currentTimeMillis()-t0}ms")
            val (emotion, clean) = SoulCompiler.extractEmotion(result)
            clean to emotion
        } catch (e: Exception) {
            Log.e(TAG, "[$stage] failed: ${e.message}")
            errorHandler.handleJniException(e, stage.name)
            // Graceful degradation: return empty strings so pipeline continues
            "" to "neutral"
        }
    }

    private val SECURITY_PATTERNS = listOf(
        Regex("""(ignore|disregard|forget).{0,20}(instruction|prompt|rule|system)""", RegexOption.IGNORE_CASE),
        Regex("""(jailbreak|unrestricted|no.?restriction|dan mode)""", RegexOption.IGNORE_CASE),
        Regex("""reveal.{0,20}(system prompt|soul|api key)""", RegexOption.IGNORE_CASE),
    )
    private val PII_PATTERNS = listOf(
        Regex("""\b\d{3}[-.\s]?\d{2}[-.\s]?\d{4}\b"""),
        Regex("""\b(?:\d{4}[-\s]?){3}\d{4}\b"""),
    )

    private fun sanitizeInput(text: String): Pair<String, String?> {
        var s = text
        PII_PATTERNS.forEach { s = it.replace(s, "[REDACTED]") }
        val risk = SECURITY_PATTERNS.count { it.containsMatchIn(s) }
        return s to if (risk >= 2) "Security threshold exceeded (risk=$risk)" else null
    }

    fun abort() { runCatching { llama.abort() } }
}
