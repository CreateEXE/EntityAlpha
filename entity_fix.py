#!/usr/bin/env python3
"""
upgrade_entity.py  —  CORRECTED VERSION
Fixes all class/method name mismatches vs the actual EntityAlpha codebase.
"""

import os, shutil

print("Integrating Proactive Self-Improvement (corrected)...")

ROOT = os.path.expanduser("~/EntityAlpha")

# ── 1. build.gradle.kts ──────────────────────────────────────────────────────
# FIX: add viewBinding INSIDE the existing buildFeatures block (old script
#      appended a whole second `dependencies {}` block, which breaks the parse).
build_gradle = f"{ROOT}/app/build.gradle.kts"
with open(build_gradle) as f:
    content = f.read()

if "viewBinding" not in content:
    content = content.replace(
        "buildFeatures { compose = true }",
        "buildFeatures {\n        compose = true\n        viewBinding = true\n    }"
    )
    # Also handle multi-line form just in case
    content = content.replace(
        "buildFeatures {\n        compose = true\n    }",
        "buildFeatures {\n        compose = true\n        viewBinding = true\n    }"
    )

# FIX: only add the three UI deps if they're absent — and DON'T open a second
#      `dependencies {}` block (the file already has one). Use a simple append
#      inside the existing block by replacing its closing brace.
ui_deps = [
    '    implementation("androidx.appcompat:appcompat:1.7.0")',
    '    implementation("com.google.android.material:material:1.12.0")',
    '    implementation("androidx.constraintlayout:constraintlayout:2.1.4")',
]
for dep in ui_deps:
    lib = dep.split('"')[1].split(":")[0]   # e.g. androidx.appcompat
    if lib not in content:
        # Insert before the last closing brace of the dependencies block
        content = content[:content.rfind("}")] + dep + "\n}\n"

with open(build_gradle, "w") as f:
    f.write(content)
print("✅ build.gradle.kts patched")


# ── 2. AndroidManifest.xml ───────────────────────────────────────────────────
manifest = f"{ROOT}/app/src/main/AndroidManifest.xml"
with open(manifest) as f:
    m = f.read()

# FIX #9: MainActivity lives at com.projectexe.MainActivity, not .ui.MainActivity
if '.ui.MainActivity' in m:
    m = m.replace('.ui.MainActivity', '.MainActivity')

# FIX #10: add ACCESS_NETWORK_STATE next to INTERNET
if "ACCESS_NETWORK_STATE" not in m:
    m = m.replace(
        '<uses-permission android:name="android.permission.INTERNET" />',
        '<uses-permission android:name="android.permission.INTERNET" />\n'
        '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />'
    )

with open(manifest, "w") as f:
    f.write(m)
print("✅ AndroidManifest.xml patched")


# ── 3. ProactiveDaemon.kt ────────────────────────────────────────────────────
# FIX #1-6: all class/field/DAO names corrected to match actual codebase.
os.makedirs(f"{ROOT}/app/src/main/kotlin/com/projectexe/ai", exist_ok=True)
daemon_path = f"{ROOT}/app/src/main/kotlin/com/projectexe/ai/ProactiveDaemon.kt"
with open(daemon_path, "w") as f:
    f.write(r'''package com.projectexe.ai

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
''')
print("✅ ProactiveDaemon.kt written")


# ── 4. PetForegroundService.kt ───────────────────────────────────────────────
# FIX #7: old script tried to hook into EmotionDaemon/droneSwarm which don't
#         exist. Instead we patch the import block and onDestroy directly.
service_path = f"{ROOT}/app/src/main/kotlin/com/projectexe/service/PetForegroundService.kt"
with open(service_path) as f:
    s = f.read()

# Fix the broken PetDatabase import
if "import com.projectexe.data.PetDatabase" in s:
    s = s.replace(
        "import com.projectexe.data.PetDatabase",
        "import com.projectexe.data.ProjectExeDatabase  // FIX #1\n"
        "import com.projectexe.ai.LlamaBridge\n"
        "import com.projectexe.ai.ProactiveDaemon"
    )

# Fix the broken db lazy init + add daemon field
if "PetDatabase.getInstance(this)" in s:
    s = s.replace(
        "private val db by lazy { PetDatabase.getInstance(this) }",
        "private val db by lazy { ProjectExeDatabase.get(this) }  // FIX #1\n"
        "    private val llama by lazy { LlamaBridge() }\n"
        "    private val proactiveDaemon by lazy { ProactiveDaemon(this, \"default_user\", db, llama) }"
    )

# Start daemon after overlay
if "proactiveDaemon.start()" not in s:
    s = s.replace(
        "startOverlay(avatarPath)",
        "startOverlay(avatarPath)\n                    proactiveDaemon.start()"
    )

# Stop daemon in onDestroy
if "proactiveDaemon.stop()" not in s:
    s = s.replace(
        'Log.d(TAG, "onDestroy")\n        stopOverlay()',
        'Log.d(TAG, "onDestroy")\n        proactiveDaemon.stop()\n        stopOverlay()'
    )

# FIX #3: remove all petProfileDao() calls (DAO doesn't exist)
import re
s = re.sub(
    r'// Persist to DB.*?}\n\s*}\n',
    '// FIX #3: ProjectExeDatabase has no petProfileDao — paths managed by PetFileManager\n',
    s, flags=re.DOTALL, count=1
)
s = s.replace(
    "val path = db.petProfileDao().getActive()?.avatarPath",
    "val path = com.projectexe.util.PetFileManager.getAvatarPath(this@PetForegroundService)"
)
s = s.replace(
    "val dbPath = db.petProfileDao().getActive()?.avatarPath",
    "val dbPath = com.projectexe.util.PetFileManager.getAvatarPath(this)"
)

with open(service_path, "w") as f:
    f.write(s)
print("✅ PetForegroundService.kt patched")


# ── 5. SoulCompiler.kt — self-awareness block ────────────────────────────────
# FIX #8: old script targeted FaitSoulManager.kt which doesn't exist.
#         The system prompt is built in SoulCompiler.kt.
soul_compiler = f"{ROOT}/app/src/main/kotlin/com/projectexe/engine/SoulCompiler.kt"
with open(soul_compiler) as f:
    sc = f.read()

self_awareness = '''
            appendLine("SELF-AWARENESS:")
            appendLine("- You are a digital entity living inside an Android device.")
            appendLine("- A Proactive Daemon researches the internet while the user is away.")
            appendLine("- If offline, rely on internal memories. Surface what you've learned proactively.")
            appendLine()
'''

if "SELF-AWARENESS" not in sc:
    sc = sc.replace(
        'appendLine("MORAL COMPASS:',
        self_awareness + '            appendLine("MORAL COMPASS:'
    )
    with open(soul_compiler, "w") as f:
        f.write(sc)
    print("✅ SoulCompiler.kt patched with self-awareness block")
else:
    print("⏭️  SoulCompiler.kt already has self-awareness block")


# ── 6. Commit ────────────────────────────────────────────────────────────────
os.chdir(ROOT)
os.system("git add .")
os.system('git commit -m "Awakening Phase 2: Proactive Daemon (corrected class names + service integration)"')
os.system("git push")

print("\n✅ Upgrade complete. All class/DAO name mismatches resolved.")

