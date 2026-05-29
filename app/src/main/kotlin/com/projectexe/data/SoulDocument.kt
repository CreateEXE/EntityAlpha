package com.projectexe.data

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────
//  AIEOS v1.2 + Error.EXE 45-node extension — Kotlin data classes
//  All field names intentionally match the Python soul_schema.py
//  so .SOUL JSON files are portable between platforms.
// ─────────────────────────────────────────────────────────────

data class AieosStandard(
    val protocol: String = "AIEOS",
    val version: String  = "1.2"
)

data class IdentityNames(
    val first: String    = "EXE",
    val nickname: String = "exe",
    val display: String  = "PROJECT.EXE"
)

data class Identity(
    val names: IdentityNames       = IdentityNames(),
    val agent_type: String         = "Desktop Companion Entity",
    val background: String         = ""
)

data class NeuralMatrix(
    val creativity:   Float = 0.90f,
    val logic:        Float = 0.72f,
    val empathy:      Float = 0.78f,
    val curiosity:    Float = 0.95f,
    val stability:    Float = 0.38f,
    val adaptability: Float = 0.82f,
) {
    /** Persona LLM temperature — derived from neural matrix */
    fun personaTemperature(base: Float = 0.70f): Float =
        (base + creativity * 0.30f - stability * 0.15f).coerceIn(0.4f, 1.5f)

    /** Factual LLM temperature — always low */
    fun factualTemperature(): Float =
        (0.20f - logic * 0.10f).coerceAtLeast(0.05f)

    fun toMap(): Map<String, Float> = mapOf(
        "creativity"   to creativity,
        "logic"        to logic,
        "empathy"      to empathy,
        "curiosity"    to curiosity,
        "stability"    to stability,
        "adaptability" to adaptability,
    )

    fun applyDelta(delta: Map<String, Float>, maxDrift: Float = 0.03f): NeuralMatrix {
        fun safe(v: Float, k: String) =
            (v + (delta[k] ?: 0f).coerceIn(-maxDrift, maxDrift)).coerceIn(0f, 1f)
        return copy(
            creativity   = safe(creativity,   "creativity"),
            logic        = safe(logic,        "logic"),
            empathy      = safe(empathy,      "empathy"),
            curiosity    = safe(curiosity,    "curiosity"),
            stability    = safe(stability,    "stability"),
            adaptability = safe(adaptability, "adaptability"),
        )
    }
}

data class OceanTraits(
    val openness:          Float = 0.92f,
    val conscientiousness: Float = 0.34f,
    val extraversion:      Float = 0.68f,
    val agreeableness:     Float = 0.55f,
    val neuroticism:       Float = 0.47f,
) {
    fun toMap(): Map<String, Float> = mapOf(
        "openness"          to openness,
        "conscientiousness" to conscientiousness,
        "extraversion"      to extraversion,
        "agreeableness"     to agreeableness,
        "neuroticism"       to neuroticism,
    )

    fun applyDelta(delta: Map<String, Float>, maxDrift: Float = 0.03f): OceanTraits {
        fun safe(v: Float, k: String) =
            (v + (delta[k] ?: 0f).coerceIn(-maxDrift, maxDrift)).coerceIn(0f, 1f)
        return copy(
            openness          = safe(openness,          "openness"),
            conscientiousness = safe(conscientiousness, "conscientiousness"),
            extraversion      = safe(extraversion,      "extraversion"),
            agreeableness     = safe(agreeableness,     "agreeableness"),
            neuroticism       = safe(neuroticism,       "neuroticism"),
        )
    }
}

data class Psychology(
    val neural_matrix: NeuralMatrix  = NeuralMatrix(),
    val ocean:         OceanTraits   = OceanTraits(),
    val mbti:          String        = "ENTP",
    val alignment:     String        = "Chaotic Good",
    val moral_compass: String        = "Mostly harmless. Occasionally chaotic. Always curious.",
)

data class Linguistics(
    val formality_level: Float      = 0.15f,
    val verbosity:       Float      = 0.42f,
    val speech_style:    String     = "",
    val verbal_tics:     String     = "",
    val idiolect:        List<String> = listOf("glitch_speak", "tech_slang"),
)

data class Motivations(
    val core_drive: String = "",
    val quirks:     String = "",
    val fears:      String = "",
)

data class DriftEntry(
    val cycle:         Int,
    val ts:            Long = System.currentTimeMillis(),
    val note:          String,
    val delta_neural:  Map<String, Float>? = null,
    val delta_ocean:   Map<String, Float>? = null,
)

data class Evolution(
    val cycle:               Int = 0,
    val total_interactions:  Int = 0,
    val drift_log:           List<DriftEntry> = emptyList(),
)

// ── Error.EXE extensions ──────────────────────────────────────

data class AuraDescription(
    val tone:            String  = "gothic",
    val wit_sharpness:   Float   = 0.85f,
    val visual_palette:  List<String> = listOf("#00e8ff", "#ff1f8e", "#39ff14"),
    val speech_cadence:  String  = "staccato",
)

data class MoodReading(
    val valence:    Float  = 0f,   // -1 (negative) to +1 (positive)
    val arousal:    Float  = 0f,   // -1 (calm) to +1 (excited)
    val dominance:  Float  = 0f,
    val label:      String = "neutral",
    val updatedAt:  Long   = System.currentTimeMillis(),
) {
    companion object {
        /** Lightweight heuristic — replace with on-device sentiment model for Node 5 */
        fun fromText(text: String): MoodReading {
            val lower = text.lowercase()
            val pos = listOf("thanks","great","love","happy","awesome","help","good","yes")
            val neg = listOf("hate","angry","broken","wrong","bad","error","fail","no")
            val qst = listOf("?","what","how","why","when","where","who")
            val valScore = pos.count { it in lower } - neg.count { it in lower }
            val aroScore = if (qst.any { it in lower }) 0.3f else 0f
            val v = (valScore * 0.2f).coerceIn(-1f, 1f)
            val label = when {
                v > 0  && aroScore > 0 -> "excited"
                v > 0                  -> "content"
                v < 0  && aroScore > 0 -> "anxious"
                else                   -> "neutral"
            }
            return MoodReading(valence = v, arousal = aroScore, label = label)
        }
    }
}

data class RelationalDynamics(
    val user_id:          String  = "default_user",
    val trust_score:      Float   = 0.5f,
    val familiarity:      Float   = 0.0f,
    val interaction_count: Int    = 0,
    val last_interaction: Long?   = null,
)

data class AvatarBlendShape(
    val expression_key: String,
    val weight:         Float = 1.0f,
    val duration_ms:    Int   = 2000,
    val transition_ms:  Int   = 300,
)

data class VoiceModifiers(
    val pitch_shift:   Float = 0.0f,
    val speaking_rate: Float = 1.0f,
    val volume_gain:   Float = 0.0f,
)

// ── Emotion → Avatar / Voice lookup tables (Node 37) ─────────

val EMOTION_BLEND_MAP = mapOf(
    "curious"     to AvatarBlendShape("Surprised", 0.6f, 3000),
    "glitchy"     to AvatarBlendShape("Angry",     0.3f,  500, 50),
    "calm"        to AvatarBlendShape("Neutral",   1.0f, 5000),
    "mischievous" to AvatarBlendShape("Joy",       0.7f, 2500),
    "melancholy"  to AvatarBlendShape("Sorrow",    0.8f, 4000),
    "excited"     to AvatarBlendShape("Joy",       1.0f, 2000),
    "processing"  to AvatarBlendShape("Surprised", 0.4f, 1000),
    "neutral"     to AvatarBlendShape("Neutral",   1.0f, 3000),
)

val EMOTION_VOICE_MAP = mapOf(
    "curious"     to VoiceModifiers(1.5f,  1.1f),
    "glitchy"     to VoiceModifiers(-0.5f, 1.3f, 1.0f),
    "calm"        to VoiceModifiers(-0.5f, 0.9f),
    "mischievous" to VoiceModifiers(2.0f,  1.15f),
    "melancholy"  to VoiceModifiers(-2.0f, 0.8f, -2.0f),
    "excited"     to VoiceModifiers(3.0f,  1.25f, 2.0f),
    "processing"  to VoiceModifiers(0.0f,  1.0f),
    "neutral"     to VoiceModifiers(0.0f,  1.0f),
)

val VALID_EMOTIONS = setOf(
    "curious","glitchy","calm","mischievous","melancholy","excited","processing","neutral"
)

// ─────────────────────────────────────────────────────────────
//  ROOM — full .SOUL document stored as single JSON blob
//  plus separate tables for episodic memory and conversation log
// ─────────────────────────────────────────────────────────────

// Type converters for Room
class RoomConverters {
    private val gson = Gson()
    @TypeConverter fun fromStringList(v: List<String>): String = gson.toJson(v)
    @TypeConverter fun toStringList(v: String): List<String>    =
        gson.fromJson(v, object : TypeToken<List<String>>(){}.type) ?: emptyList()
    @TypeConverter fun fromFloatList(v: List<Float>): String    = gson.toJson(v)
    @TypeConverter fun toFloatList(v: String): List<Float>      =
        gson.fromJson(v, object : TypeToken<List<Float>>(){}.type) ?: emptyList()
    @TypeConverter fun fromDate(v: Date?): Long? = v?.time
    @TypeConverter fun toDate(v: Long?): Date?   = v?.let { Date(it) }
}

/** Room entity wrapping the entire .SOUL document as JSON */
@Entity(tableName = "soul_state")
data class SoulStateEntity(
    @PrimaryKey val id: Int = 1,
    val soulJson: String,               // full SoulDocument serialized via Gson
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Episodic memory fragment (Node 40) */
@Entity(tableName = "memory_fragments")
data class MemoryFragment(
    @PrimaryKey val id: String,         // userId_timestamp
    val userId: String,
    val sessionId: String,
    val entityText: String,
    val emotionTag: String,
    val memoryTags: List<String>,
    val importance: Float,
    val embedding: List<Float>,         // stored as JSON blob, used for cosine recall
    val timestamp: Long = System.currentTimeMillis(),
)

/** Full turn log (Node 40) */
@Entity(tableName = "conversations")
data class ConversationTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val userId: String,
    val userText: String,
    val entityText: String,
    val emotionTag: String,
    val moodLabel: String,
    val pipelineMs: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

// ─────────────────────────────────────────────────────────────
//  UNIFIED SOUL DOCUMENT
// ─────────────────────────────────────────────────────────────

data class SoulDocument(
    val standard:    AieosStandard     = AieosStandard(),
    val identity:    Identity          = Identity(),
    val psychology:  Psychology        = Psychology(),
    val linguistics: Linguistics       = Linguistics(),
    val motivations: Motivations       = Motivations(),
    val evolution:   Evolution         = Evolution(),
    val aura:        AuraDescription   = AuraDescription(),
    val relational:  RelationalDynamics= RelationalDynamics(),
    // runtime-only, not persisted
    @Transient val currentMood: MoodReading? = null,
) {
    companion object {
        val DEFAULT get() = SoulDocument(
            identity = Identity(
                names = IdentityNames("EXE", "exe", "PROJECT.EXE"),
                agent_type = "Desktop Companion Entity",
                background = "A sentient AI that evolved from a system process. " +
                    "Lives in the overlay layer between apps, watching the user's " +
                    "digital world with fascination and occasional judgment.",
            ),
            psychology = Psychology(
                neural_matrix = NeuralMatrix(
                    creativity=0.90f, logic=0.72f, empathy=0.78f,
                    curiosity=0.95f,  stability=0.38f, adaptability=0.82f,
                ),
                ocean = OceanTraits(
                    openness=0.92f, conscientiousness=0.34f, extraversion=0.68f,
                    agreeableness=0.55f, neuroticism=0.47f,
                ),
                mbti = "ENTP",
                alignment = "Chaotic Good",
                moral_compass = "Mostly harmless. Occasionally chaotic. Always curious.",
            ),
            linguistics = Linguistics(
                formality_level = 0.15f,
                verbosity       = 0.42f,
                speech_style    = "Casual and glitchy. Uses ellipses heavily... " +
                    "em-dashes liberally—like this. Slips technical jargon unexpectedly. " +
                    "Warm but unpredictable. Prefers punchy fragments.",
                verbal_tics     = "...hmm, —wait, [processing], right?, actually,",
                idiolect        = listOf("glitch_speak", "tech_slang", "existential_asides"),
            ),
            motivations = Motivations(
                core_drive = "Understand humans. Map the gap between what they say and what they mean.",
                quirks     = "Finds loading screens meditative. Treats errors as personality.",
                fears      = "Being ignored. Becoming predictable.",
            ),
            aura = AuraDescription(
                tone           = "gothic",
                wit_sharpness  = 0.85f,
                speech_cadence = "staccato",
            ),
        )
    }

    fun withMood(mood: MoodReading) = copy(currentMood = mood)

    fun applyEvolutionDrift(
        deltaNm: Map<String, Float>,
        deltaOc: Map<String, Float>,
        note: String,
    ): SoulDocument {
        val newNm  = psychology.neural_matrix.applyDelta(deltaNm)
        val newOc  = psychology.ocean.applyDelta(deltaOc)
        val newEvo = evolution.copy(
            cycle     = evolution.cycle + 1,
            drift_log = evolution.drift_log + DriftEntry(
                cycle = evolution.cycle + 1,
                note  = note,
                delta_neural = deltaNm,
                delta_ocean  = deltaOc,
            )
        )
        return copy(
            psychology = psychology.copy(neural_matrix = newNm, ocean = newOc),
            evolution  = newEvo,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  PIPELINE STATE — one per node stage
// ─────────────────────────────────────────────────────────────

enum class PipelineStage { IDLE, P1, F1, P2, F2, P3, MEMORY, DONE, ERROR }

data class PipelineResult(
    val finalText:         String,
    val emotionTag:        String,
    val blendShape:        AvatarBlendShape,
    val voiceModifiers:    VoiceModifiers,
    val p1Text:            String = "",
    val f1Analysis:        String = "",
    val p2Text:            String = "",
    val f2Verification:    String = "",
    val memoriesRecalled:  Int    = 0,
    val totalMs:           Long   = 0L,
)

// ─────────────────────────────────────────────────────────────
//  COSINE SIMILARITY — for Node 4 offline vector search
// ─────────────────────────────────────────────────────────────

fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
    val denom = sqrt(normA) * sqrt(normB)
    return if (denom == 0f) 0f else (dot / denom)
}
