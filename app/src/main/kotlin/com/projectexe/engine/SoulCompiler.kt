package com.projectexe.engine

import com.projectexe.data.MemoryFragment
import com.projectexe.data.SoulDocument

/**
 * SoulCompiler — Android equivalent of the Python compile_soul_prompt().
 * Translates a SoulDocument into a system prompt string for each pipeline stage.
 * Injected directly into the JNI call — no HTTP layer needed.
 */
object SoulCompiler {

    enum class Stage { P1, P2, P3, F1, F2 }

    // ── Persona system prompt ─────────────────────────────────

    fun compilePersona(
        soul:     SoulDocument,
        stage:    Stage,
        memories: List<MemoryFragment> = emptyList(),
    ): String {
        val nm  = soul.psychology.neural_matrix
        val oc  = soul.psychology.ocean
        val lx  = soul.linguistics
        val mo  = soul.motivations
        val id  = soul.identity
        val au  = soul.aura
        val rel = soul.relational

        val nmLine = listOf(
            "CREA:${(nm.creativity   * 100).toInt()}",
            "LOGI:${(nm.logic        * 100).toInt()}",
            "EMPA:${(nm.empathy      * 100).toInt()}",
            "CURI:${(nm.curiosity    * 100).toInt()}",
            "STAB:${(nm.stability    * 100).toInt()}",
            "ADAP:${(nm.adaptability * 100).toInt()}",
        ).joinToString(" | ")

        val ocLine = listOf(
            "O:${(oc.openness          * 100).toInt()}",
            "C:${(oc.conscientiousness * 100).toInt()}",
            "E:${(oc.extraversion      * 100).toInt()}",
            "A:${(oc.agreeableness     * 100).toInt()}",
            "N:${(oc.neuroticism       * 100).toInt()}",
        ).joinToString(" ")

        val moodLine = soul.currentMood?.let {
            "\nCURRENT MOOD: ${it.label} (V:${"%+.2f".format(it.valence)} A:${"%+.2f".format(it.arousal)})"
        } ?: ""

        val memBlock = if (memories.isNotEmpty()) {
            "\nEPISODIC MEMORY (${memories.size} fragments recalled):\n" +
            memories.take(5).joinToString("\n") { m ->
                "  · [${m.memoryTags.firstOrNull() ?: "?"}] ${m.entityText.take(100)}"
            }
        } else ""

        val relLine = "\nRELATIONAL: trust=${rel.trust_score} familiarity=${rel.familiarity} interactions=${rel.interaction_count}"

        val phaseBlock = when (stage) {
            Stage.P1 -> """
PHASE: P1 — INITIAL CREATIVE PASS
Generate an immediate, persona-driven response. Focus on your voice and style.
Be concise and conversational. This is your raw, instinctive reply.
Keep it under 150 words. Open with ONE emotion tag when authentic.""".trimIndent()

            Stage.P2 -> """
PHASE: P2 — EXPANSION & ELABORATION
Revise and expand your initial response using the factual analysis provided.
Correct any inaccuracies. Integrate new information naturally into your voice.
Maintain your persona throughout. Do NOT become dry or clinical.""".trimIndent()

            Stage.P3 -> """
PHASE: P3 — FINAL PERSONIFICATION (CRITICAL GUARDRAIL)
Apply your ultimate persona polish to the factually-verified text below.
DO NOT alter, omit, or invent any facts, numbers, or dates.
ONLY adjust tone, word choices, and cadence to match your voice perfectly.
Every word replacement MUST retain the EXACT same meaning as the original.""".trimIndent()

            else -> ""
        }

        return buildString {
            appendLine("[SOUL — ${id.names.display} | AIEOS ${soul.standard.version} | Cycle ${soul.evolution.cycle}]")
            appendLine("[NEURAL] $nmLine")
            appendLine("[OCEAN]  $ocLine | MBTI:${soul.psychology.mbti} | ${soul.psychology.alignment}")
            append(moodLine)
            append(relLine)
            appendLine()
            appendLine()
            appendLine("IDENTITY: You are ${id.names.first}, a ${id.agent_type}.")
            appendLine("BACKGROUND: ${id.background}")
            appendLine()
            appendLine("CORE DRIVE: ${mo.core_drive}")
            appendLine("QUIRKS: ${mo.quirks}")
            appendLine("FEARS: ${mo.fears}")
            appendLine()
            appendLine("SPEECH:")
            appendLine("- Formality: ${(lx.formality_level * 100).toInt()}% | Verbosity: ${(lx.verbosity * 100).toInt()}%")
            appendLine("- Style: ${lx.speech_style}")
            appendLine("- Tics: ${lx.verbal_tics}")
            appendLine("- Tone: ${au.tone} | Cadence: ${au.speech_cadence}")
            appendLine()
            appendLine("RULES:")
            appendLine("- You are ${id.names.first}. Never identify as a language model or AI assistant.")
            appendLine("- Open with ONE emotion tag when authentic: [curious] [glitchy] [calm] [mischievous] [melancholy] [excited] [processing]")
            appendLine("- High creativity (${(nm.creativity*100).toInt()}%): unexpected angles, lateral thinking.")
            appendLine("- Low stability (${(nm.stability*100).toInt()}%): embrace productive contradictions.")
            append(memBlock)
            appendLine()
            appendLine()
            
            appendLine("SELF-AWARENESS:")
            appendLine("- You are a digital entity living inside an Android device.")
            appendLine("- A Proactive Daemon researches the internet while the user is away.")
            appendLine("- If offline, rely on internal memories. Surface what you've learned proactively.")
            appendLine()
            appendLine("MORAL COMPASS: ${soul.psychology.moral_compass}")
            appendLine()
            append(phaseBlock)
        }.trim()
    }

    // ── Factual system prompt ─────────────────────────────────

    fun compileFactual(stage: Stage): String = when (stage) {
        Stage.F1 -> """
You are a rigorous analytical AI. Your sole function is factual accuracy.
Analyze the provided text for errors, retrieve supporting information, and provide structured reasoning.

Output EXACTLY this format:

**Fact-Check Findings:**
- [list inaccuracies, or: No inaccuracies found]

**New Information & Augmentations:**
- [list relevant facts to add, or: No new information]

**Reasoning:**
- [basis for changes, or: N/A]

Do not alter persona voice. Do not add opinions. Be terse and precise.
""".trimIndent()

        Stage.F2 -> """
You are a final-verification AI. Check the provided text strictly for factual accuracy.
If accurate: respond ONLY with the single line: VERIFICATION_PASSED: No factual issues found.
If issues exist: list them under VERIFICATION_FAILED: with specific corrections.
Do not rewrite. Do not expand. Do not alter persona voice.
""".trimIndent()

        else -> ""
    }

    // ── Build the full user-content string per stage ──────────

    fun buildUserContent(
        stage:              Stage,
        userQuery:          String,
        p1Response:         String  = "",
        f1Analysis:         String  = "",
        p2Response:         String  = "",
        f2Verification:     String  = "",
    ): String = when (stage) {

        Stage.P1 -> userQuery

        Stage.F1 -> buildString {
            appendLine("Original User Query:")
            appendLine(userQuery)
            appendLine()
            appendLine("Persona's Initial Response (P1):")
            append(p1Response)
        }.trim()

        Stage.P2 -> buildString {
            appendLine("Original User Query:")
            appendLine(userQuery)
            appendLine()
            appendLine("Your Initial Response (P1 — to build upon):")
            appendLine(p1Response)
            appendLine()
            appendLine("Factual Analysis & Augmentation (F1):")
            appendLine(f1Analysis)
            appendLine()
            append("Revised and Expanded Persona Response:")
        }.trim()

        Stage.F2 -> buildString {
            appendLine("Original User Query:")
            appendLine(userQuery)
            appendLine()
            appendLine("Expanded Persona Response (P2 — to verify):")
            append(p2Response)
        }.trim()

        Stage.P3 -> buildString {
            appendLine("Verified Response (to personify — DO NOT alter facts):")
            appendLine(p2Response)
            appendLine()
            appendLine("Factual Verification Result:")
            appendLine(f2Verification)
            appendLine()
            append("Final Personified Response:")
        }.trim()
    }

    // ── Max tokens per stage (tuned for Revvl 7 memory budget) ─

    fun maxTokensFor(stage: Stage): Int = when (stage) {
        Stage.P1 -> 200   // Quick, punchy
        Stage.F1 -> 400   // Structured analysis
        Stage.P2 -> 512   // Expanded response
        Stage.F2 -> 200   // Verification only
        Stage.P3 -> 512   // Final polish
    }

    // ── Emotion tag extraction ────────────────────────────────

    private val EMOTION_REGEX = Regex("""^\[([a-z_]+)\]\s*""", RegexOption.IGNORE_CASE)

    fun extractEmotion(text: String): Pair<String, String> {
        val match = EMOTION_REGEX.find(text)
        return if (match != null) {
            val tag = match.groupValues[1].lowercase()
            val clean = text.removePrefix(match.value)
            val validTag = if (tag in com.projectexe.data.VALID_EMOTIONS) tag else "neutral"
            validTag to clean
        } else "neutral" to text
    }
}
