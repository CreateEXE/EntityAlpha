package com.projectexe.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.projectexe.data.MemoryFragment          // FIX #4: was PetMemory
import com.projectexe.data.ProjectExeDatabase      // FIX #1: was PetDatabase
import kotlinx.coroutines.*
import java.net.URL

class ProactiveDaemon(
    private val context: Context,
    private val userId: String,                    // FIX #6: was Long
    private val db: ProjectExeDatabase,            // FIX #1
    private val llama: LlamaBridge
) {
    companion object {
        private const val TAG = "ProactiveDaemon"
        private const val WAKE_INTERVAL_MS = 45 * 60 * 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            Log.i(TAG, "ProactiveDaemon awakened.")
            while (isActive) {
                delay(WAKE_INTERVAL_MS)
                try { ponderAndResearch() }
                catch (e: Exception) { Log.e(TAG, "Thought interrupted", e) }
            }
        }
    }

    fun stop() { isRunning = false; scope.cancel() }

    private suspend fun ponderAndResearch() {
        if (!llama.isLoaded()) return
        val isOnline = checkInternet()

        // FIX #2: was interactionHistoryDao() | FIX #6: userId is String
        val history = db.conversationDao().getRecent(userId, limit = 5)

        // FIX #5: was promptSummary/petResponse
        val contextText = history.joinToString(" | ") { it.userText + " -> " + it.entityText }

        val topicPrompt = """
            <|system|>
            You are a proactive self-aware digital entity.
            Determine ONE specific topic to research now to improve yourself or help the user.
            Respond ONLY with the search term. No quotes, no explanation.
            <|end|>
            <|user|>
            Recent Context: $contextText
            What will you research?
            <|end|>
            <|assistant|>
        """.trimIndent()

        val topic = llama.infer(topicPrompt, maxNewTokens = 15).trim()
        if (topic.isBlank()) return
        Log.i(TAG, "Entity researching: $topic")

        var newKnowledge = "Pondered deeply about $topic, forming new abstract connections."

        if (isOnline) {
            try {
                val query = topic.replace(" ", "_")
                val response = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$query").readText()
                val extract = Regex(""""extract"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
                if (extract != null) {
                    val prompt = """
                        <|system|>Summarize what you learned in 2 sentences to store as memory.<|end|>
                        <|user|>$extract<|end|>
                        <|assistant|>
                    """.trimIndent()
                    newKnowledge = llama.infer(prompt, maxNewTokens = 100).trim()
                }
            } catch (e: Exception) { Log.w(TAG, "Fetch failed for $topic", e) }
        }

        // FIX #4: was PetMemory with wrong fields — use MemoryFragment
        val memory = MemoryFragment(
            id           = "${userId}_${System.currentTimeMillis()}",
            userId       = userId,
            sessionId    = "proactive",
            entityText   = "[$topic] $newKnowledge",
            emotionTag   = "curious",
            memoryTags   = listOf("research", "autonomous", topic),
            importance   = 0.9f,
            embedding    = emptyList()
        )
        db.memoryDao().insert(memory)   // FIX #3: was petMemoryDao()
        Log.i(TAG, "Stored memory: $newKnowledge")
    }

    private fun checkInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
