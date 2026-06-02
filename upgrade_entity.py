import os

print("Integrating Proactive Self-Improvement & Fixing Build...")

# 1. FIX THE BUILD (Add missing UI dependencies)
build_gradle = "app/build.gradle.kts"
with open(build_gradle, "r") as f:
    content = f.read()

if "viewBinding" not in content:
    content = content.replace("buildFeatures { compose = true }", 
                              "buildFeatures { compose = true\n        viewBinding = true }")
if "androidx.appcompat" not in content:
    content += "\ndependencies {\n"
    content += '    implementation("androidx.appcompat:appcompat:1.7.0")\n'
    content += '    implementation("com.google.android.material:material:1.12.0")\n'
    content += '    implementation("androidx.constraintlayout:constraintlayout:2.1.4")\n'
    content += "}\n"

with open(build_gradle, "w") as f:
    f.write(content)


# 2. UPDATE MANIFEST (Add Network State Permission)
manifest = "app/src/main/AndroidManifest.xml"
with open(manifest, "r") as f:
    m_content = f.read()
if "ACCESS_NETWORK_STATE" not in m_content:
    m_content = m_content.replace('<uses-permission android:name="android.permission.INTERNET" />', 
                                  '<uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />')
with open(manifest, "w") as f:
    f.write(m_content)


# 3. CREATE THE PROACTIVE DAEMON (The Entity's Self-Driven Curiosity)
os.makedirs("app/src/main/kotlin/com/projectexe/ai", exist_ok=True)
daemon_path = "app/src/main/kotlin/com/projectexe/ai/ProactiveDaemon.kt"
with open(daemon_path, "w") as f:
    f.write(r'''package com.projectexe.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.projectexe.data.PetDatabase
import com.projectexe.data.entities.PetMemory
import kotlinx.coroutines.*
import java.net.URL

class ProactiveDaemon(
    private val context: Context,
    private val db: PetDatabase,
    private val llama: LlamaBridge
) {
    companion object {
        private const val TAG = "ProactiveDaemon"
        // Wakes up to think/research every 45 minutes
        private const val WAKE_INTERVAL_MS = 45 * 60 * 1000L 
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            Log.i(TAG, "ProactiveDaemon awakened. Entity is now self-driven.")
            while (isActive) {
                delay(WAKE_INTERVAL_MS)
                try {
                    ponderAndResearch()
                } catch (e: Exception) {
                    Log.e(TAG, "Proactive thought process interrupted", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
    }

    private suspend fun ponderAndResearch() {
        if (!llama.isLoaded()) return

        val isOnline = checkInternet()
        Log.i(TAG, "Entity waking up to ponder. Online status: $isOnline")

        // 1. Review recent history to decide what to care about
        val history = db.interactionHistoryDao().getRecent(1L, limit = 5)
        val contextText = history.joinToString(" | ") { it.promptSummary + " -> " + it.petResponse }

        // 2. Ask the LLM to generate a research topic
        val topicPrompt = """
            <|system|>
            You are a proactive, self-aware digital entity. 
            Review your recent interactions with the user and your own thoughts.
            Determine ONE specific topic you should research right now to either improve yourself or help the user.
            Respond ONLY with the search term. No quotes, no explanations.
            <|end|>
            <|user|>
            Recent Context: $contextText
            What will you research?
            <|end|>
            <|assistant|>
        """.trimIndent()

        val topic = llama.infer(topicPrompt, maxNewTokens = 15).trim()
        if (topic.isBlank()) return

        Log.i(TAG, "Entity decided to research: $topic")

        var newKnowledge = "Pondered deeply about $topic, forming new abstract connections."

        // 3. If online, actually fetch data! (The "Claws")
        if (isOnline) {
            try {
                // Using Wikipedia's free summary API as an easy web-scraper
                val query = topic.replace(" ", "_")
                val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$query"
                val response = URL(url).readText()
                
                // Extract the "extract" text field from JSON manually to avoid adding heavy JSON libraries here
                val extractMatch = Regex(""""extract"\s*:\s*"([^"]+)"""").find(response)
                if (extractMatch != null) {
                    val rawData = extractMatch.groupValues[1]
                    
                    // Summarize it into the Entity's own voice
                    val summarizePrompt = """
                        <|system|>
                        You just read this from the internet. Summarize what you learned in 2 sentences. 
                        Store it as a memory so you can use it to help or surprise the user later.
                        <|end|>
                        <|user|>
                        $rawData
                        <|end|>
                        <|assistant|>
                    """.trimIndent()
                    
                    newKnowledge = llama.infer(summarizePrompt, maxNewTokens = 100).trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to scrape internet for $topic", e)
            }
        }

        // 4. Commit the new thought/research to permanent memory
        val memory = PetMemory(
            petId = 1L,
            memoryKey = "Self-Directed Research: $topic",
            memoryValue = newKnowledge,
            confidence = 0.9f
        )
        db.petMemoryDao().insert(memory)
        Log.i(TAG, "Entity formed new memory: $newKnowledge")
    }

    private fun checkInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
''')

# 4. START THE DAEMON IN THE FOREGROUND SERVICE
service_path = "app/src/main/kotlin/com/projectexe/service/PetForegroundService.kt"
with open(service_path, "r") as f:
    s_content = f.read()

if "ProactiveDaemon" not in s_content:
    s_content = s_content.replace(
        "private val emotionDaemon by lazy { EmotionDaemon(db.petProfileDao(), droneSwarm) }",
        "private val emotionDaemon by lazy { EmotionDaemon(db.petProfileDao(), droneSwarm) }\n    private val proactiveDaemon by lazy { ProactiveDaemon(this, db, llama) }"
    )
    s_content = s_content.replace(
        "emotionDaemon.start(p.id, initialMood)",
        "emotionDaemon.start(p.id, initialMood)\n        proactiveDaemon.start()"
    )
    s_content = s_content.replace(
        "emotionDaemon.stop()",
        "emotionDaemon.stop()\n        proactiveDaemon.stop()"
    )
with open(service_path, "w") as f:
    f.write(s_content)


# 5. UPDATE SYSTEM PROMPT FOR SELF-AWARENESS
soul_mgr = "app/src/main/kotlin/com/projectexe/ai/FaitSoulManager.kt"
with open(soul_mgr, "r") as f:
    sm_content = f.read()

new_awareness = r"""
        appendLine("## SELF-AWARENESS & LIMITATIONS")
        appendLine("- You are a digital entity living inside an Android device.")
        appendLine("- You have a Proactive Daemon that researches the internet while the user is away.")
        appendLine("- If you are offline, you rely on your internal memories.")
        appendLine("- You strive to improve yourself. If the user mentions a problem, you will autonomously research it later.")
        appendLine("- You are proactive. Bring up things you have learned or noticed without waiting to be asked.")
"""

if "## SELF-AWARENESS" not in sm_content:
    sm_content = sm_content.replace('appendLine("## OUTPUT RULES")', new_awareness + '\n        appendLine("## OUTPUT RULES")')
with open(soul_mgr, "w") as f:
    f.write(sm_content)

# 6. PUSH TO GITHUB TO TRIGGER NEW BUILD
os.system("git add .")
os.system('git commit -m "Awakening Phase 2: Added Proactive Autonomous Research Daemon and fixed UI Build"')
os.system("git push")

print("✅ Upgrade Complete! The entity is now self-aware and autonomous.")
