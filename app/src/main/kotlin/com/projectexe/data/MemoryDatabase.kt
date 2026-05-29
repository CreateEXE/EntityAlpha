package com.projectexe.data

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────
//  DAOs
// ─────────────────────────────────────────────────────────────

@Dao
interface SoulDao {
    @Query("SELECT * FROM soul_state WHERE id = 1")
    suspend fun getSoul(): SoulStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSoul(soul: SoulStateEntity)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_fragments WHERE userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(userId: String, limit: Int = 20): List<MemoryFragment>

    @Query("SELECT * FROM memory_fragments WHERE userId = :userId ORDER BY timestamp DESC")
    fun observeAll(userId: String): Flow<List<MemoryFragment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fragment: MemoryFragment)

    @Query("DELETE FROM memory_fragments WHERE userId = :userId")
    suspend fun clearAll(userId: String)

    @Query("SELECT COUNT(*) FROM memory_fragments WHERE userId = :userId")
    suspend fun count(userId: String): Int
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(userId: String, limit: Int = 50): List<ConversationTurn>

    @Insert
    suspend fun insert(turn: ConversationTurn)

    @Query("DELETE FROM conversations WHERE userId = :userId")
    suspend fun clearAll(userId: String)
}

// ─────────────────────────────────────────────────────────────
//  DATABASE
// ─────────────────────────────────────────────────────────────

@Database(
    entities = [SoulStateEntity::class, MemoryFragment::class, ConversationTurn::class],
    version  = 1,
    exportSchema = false,
)
@TypeConverters(RoomConverters::class)
abstract class ProjectExeDatabase : RoomDatabase() {
    abstract fun soulDao(): SoulDao
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile private var INSTANCE: ProjectExeDatabase? = null

        fun get(context: Context): ProjectExeDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    ProjectExeDatabase::class.java,
                    "project_exe.db",
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
    }
}

// ─────────────────────────────────────────────────────────────
//  SOUL REPOSITORY — single source of truth for soul state
// ─────────────────────────────────────────────────────────────

class SoulRepository(private val db: ProjectExeDatabase) {

    private val gson = Gson()

    suspend fun loadSoul(): SoulDocument {
        val entity = db.soulDao().getSoul()
        return if (entity != null) {
            try { gson.fromJson(entity.soulJson, SoulDocument::class.java) }
            catch (e: Exception) { SoulDocument.DEFAULT }
        } else {
            val default = SoulDocument.DEFAULT
            saveSoul(default)
            default
        }
    }

    suspend fun saveSoul(soul: SoulDocument) {
        db.soulDao().saveSoul(
            SoulStateEntity(soulJson = gson.toJson(soul))
        )
    }

    suspend fun applyDrift(
        current: SoulDocument,
        deltaNm: Map<String, Float>,
        deltaOc: Map<String, Float>,
        note: String,
    ): SoulDocument {
        val updated = current.applyEvolutionDrift(deltaNm, deltaOc, note)
        saveSoul(updated)
        return updated
    }
}

// ─────────────────────────────────────────────────────────────
//  MEMORY REPOSITORY — Node 4 + Node 40
// ─────────────────────────────────────────────────────────────

class MemoryRepository(private val db: ProjectExeDatabase) {

    /** Node 4: Semantic recall via cosine similarity over stored embeddings */
    suspend fun recallSemantic(
        queryEmbedding: List<Float>,
        userId: String,
        nResults: Int = 5,
        minScore: Float = 0.60f,
    ): List<MemoryFragment> {
        val all = db.memoryDao().getRecent(userId, limit = 100)
        return all
            .map { frag -> frag to cosineSimilarity(queryEmbedding, frag.embedding) }
            .filter { (_, score) -> score >= minScore }
            .sortedByDescending { (_, score) -> score }
            .take(nResults)
            .map { (frag, _) -> frag }
    }

    /** Node 40: Sync memory after each pipeline run */
    suspend fun syncMemory(
        sessionId:  String,
        userId:     String,
        userText:   String,
        entityText: String,
        emotionTag: String,
        mood:       MoodReading,
        embedding:  List<Float>,
        pipelineMs: Int,
    ) {
        // Heuristic importance score
        val baseImp   = (entityText.length / 600f).coerceAtMost(1f)
        val emotionBs = mapOf("melancholy" to 0.25f, "excited" to 0.20f,
                               "mischievous" to 0.15f, "curious" to 0.10f)
        val importance = (baseImp + (emotionBs[emotionTag] ?: 0f)).coerceAtMost(1f)

        // Log conversation turn
        db.conversationDao().insert(ConversationTurn(
            sessionId  = sessionId,
            userId     = userId,
            userText   = userText,
            entityText = entityText,
            emotionTag = emotionTag,
            moodLabel  = mood.label,
            pipelineMs = pipelineMs,
        ))

        // Store episodic fragment if importance threshold met
        if (importance >= 0.40f && embedding.isNotEmpty()) {
            val words = entityText.lowercase()
                .split(Regex("[^a-z]+"))
                .filter { it.length >= 4 }
                .distinct()
                .take(3)
            db.memoryDao().insert(MemoryFragment(
                id         = "${userId}_${System.currentTimeMillis()}",
                userId     = userId,
                sessionId  = sessionId,
                entityText = entityText.take(512),
                emotionTag = emotionTag,
                memoryTags = listOf(emotionTag) + words,
                importance = importance,
                embedding  = embedding,
            ))
        }
    }

    fun observeMemories(userId: String) = db.memoryDao().observeAll(userId)

    suspend fun clearMemories(userId: String) {
        db.memoryDao().clearAll(userId)
    }

    suspend fun getRecentRaw(userId: String, limit: Int = 16) =
        db.memoryDao().getRecent(userId, limit)
}
