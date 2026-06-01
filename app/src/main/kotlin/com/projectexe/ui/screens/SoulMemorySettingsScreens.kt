package com.projectexe.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import com.projectexe.data.*
import com.projectexe.engine.ModelFileManager
import com.projectexe.ui.UiState
import com.projectexe.ui.theme.*
import android.content.Context
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Divider


// ═══════════════════════════════════════════════════════════════
//  SOUL EDITOR SCREEN
// ═══════════════════════════════════════════════════════════════

@Composable
fun SoulScreen(ui: UiState, onSave: (SoulDocument) -> Unit) {
    var draft by remember(ui.soul) { mutableStateOf(ui.soul) }

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgPanel)
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            MonoLabel("// SOUL EDITOR — AIEOS ${ui.soul.standard.version}", Cyan, 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick  = { draft = ui.soul },
                    border   = BorderStroke(1.dp, Border),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("RESET", color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
                Button(
                    onClick  = { onSave(draft) },
                    colors   = ButtonDefaults.buttonColors(containerColor = Cyan.copy(0.15f)),
                    border   = BorderStroke(1.dp, Cyan.copy(0.5f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("APPLY ✓", color = Cyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
            }
        }

        // Evolution badge
        Row(
            modifier = Modifier.background(Purple.copy(0.06f)).fillMaxWidth().padding(10.dp, 5.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            listOf(
                "CYCLE" to "${ui.soul.evolution.cycle}",
                "INTERACTIONS" to "${ui.soul.evolution.total_interactions}",
                "DRIFT ENTRIES" to "${ui.soul.evolution.drift_log.size}",
            ).forEach { (k, v) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MonoLabel(k, TextDim, 7.sp)
                    MonoLabel(v, Purple, 12.sp)
                }
            }
        }

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Identity ─────────────────────────────────────
            SoulSection("IDENTITY")
            SoulTextRow("DISPLAY_NAME") { draft = draft.copy(identity = draft.identity.copy(names = draft.identity.names.copy(display = it))) }
            SoulTextRow("FIRST_NAME / CALL_SIGN", draft.identity.names.first) { draft = draft.copy(identity = draft.identity.copy(names = draft.identity.names.copy(first = it))) }
            SoulTextRow("AGENT_TYPE", draft.identity.agent_type) { draft = draft.copy(identity = draft.identity.copy(agent_type = it)) }
            SoulTextAreaRow("BACKGROUND", draft.identity.background, 3) { draft = draft.copy(identity = draft.identity.copy(background = it)) }

            // ── Neural Matrix ─────────────────────────────────
            SoulSection("NEURAL MATRIX  ← drives LLM temperature")
            val nm = draft.psychology.neural_matrix
            mapOf(
                "creativity"   to nm.creativity,   "logic"        to nm.logic,
                "empathy"      to nm.empathy,       "curiosity"    to nm.curiosity,
                "stability"    to nm.stability,     "adaptability" to nm.adaptability,
            ).forEach { (key, value) ->
                SoulSlider(key.uppercase(), value, Cyan) { v ->
                    val updated = when (key) {
                        "creativity"   -> nm.copy(creativity = v)
                        "logic"        -> nm.copy(logic = v)
                        "empathy"      -> nm.copy(empathy = v)
                        "curiosity"    -> nm.copy(curiosity = v)
                        "stability"    -> nm.copy(stability = v)
                        "adaptability" -> nm.copy(adaptability = v)
                        else           -> nm
                    }
                    draft = draft.copy(psychology = draft.psychology.copy(neural_matrix = updated))
                }
            }
            // Derived temperature display
            SoulInfoBox(
                "PERSONA temp=${nm.personaTemperature()}  |  FACTUAL temp=${nm.factualTemperature()}",
                Cyan,
            )

            // ── OCEAN ─────────────────────────────────────────
            SoulSection("OCEAN TRAITS")
            val oc = draft.psychology.ocean
            val ocColors = listOf(Cyan, Green, Amber, Color(0xFF06B6D4), Magenta)
            mapOf(
                "openness" to oc.openness, "conscientiousness" to oc.conscientiousness,
                "extraversion" to oc.extraversion, "agreeableness" to oc.agreeableness,
                "neuroticism" to oc.neuroticism,
            ).entries.forEachIndexed { i, (key, value) ->
                SoulSlider(key.uppercase(), value, ocColors[i]) { v ->
                    val updated = when (key) {
                        "openness"          -> oc.copy(openness = v)
                        "conscientiousness" -> oc.copy(conscientiousness = v)
                        "extraversion"      -> oc.copy(extraversion = v)
                        "agreeableness"     -> oc.copy(agreeableness = v)
                        "neuroticism"       -> oc.copy(neuroticism = v)
                        else                -> oc
                    }
                    draft = draft.copy(psychology = draft.psychology.copy(ocean = updated))
                }
            }

            // ── Linguistics ───────────────────────────────────
            SoulSection("LINGUISTICS")
            SoulSlider("FORMALITY_LEVEL", draft.linguistics.formality_level, Amber) {
                draft = draft.copy(linguistics = draft.linguistics.copy(formality_level = it))
            }
            SoulSlider("VERBOSITY", draft.linguistics.verbosity, Amber) {
                draft = draft.copy(linguistics = draft.linguistics.copy(verbosity = it))
            }
            SoulTextAreaRow("SPEECH_STYLE", draft.linguistics.speech_style, 3) {
                draft = draft.copy(linguistics = draft.linguistics.copy(speech_style = it))
            }
            SoulTextAreaRow("VERBAL_TICS", draft.linguistics.verbal_tics, 1) {
                draft = draft.copy(linguistics = draft.linguistics.copy(verbal_tics = it))
            }

            // ── Aura ──────────────────────────────────────────
            SoulSection("AURA  ← Node 7 aesthetic parameters")
            SoulTextRow("TONE", draft.aura.tone) {
                draft = draft.copy(aura = draft.aura.copy(tone = it))
            }
            SoulSlider("WIT_SHARPNESS", draft.aura.wit_sharpness, Purple) {
                draft = draft.copy(aura = draft.aura.copy(wit_sharpness = it))
            }
            SoulTextRow("SPEECH_CADENCE", draft.aura.speech_cadence) {
                draft = draft.copy(aura = draft.aura.copy(speech_cadence = it))
            }

            // ── Motivations ───────────────────────────────────
            SoulSection("MOTIVATIONS")
            SoulTextAreaRow("CORE_DRIVE", draft.motivations.core_drive, 2) {
                draft = draft.copy(motivations = draft.motivations.copy(core_drive = it))
            }
            SoulTextAreaRow("QUIRKS", draft.motivations.quirks, 2) {
                draft = draft.copy(motivations = draft.motivations.copy(quirks = it))
            }
            SoulTextAreaRow("FEARS", draft.motivations.fears, 2) {
                draft = draft.copy(motivations = draft.motivations.copy(fears = it))
            }

            // ── Drift log ─────────────────────────────────────
            if (ui.soul.evolution.drift_log.isNotEmpty()) {
                SoulSection("EVOLUTION DRIFT LOG")
                ui.soul.evolution.drift_log.takeLast(5).reversed().forEach { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(3.dp),
                        color    = Purple.copy(0.06f),
                        border   = BorderStroke(1.dp, Purple.copy(0.2f)),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            MonoLabel("CYCLE ${entry.cycle}", Purple, 9.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(entry.note, color = TextDim, fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  MEMORY SCREEN
// ═══════════════════════════════════════════════════════════════

@Composable
fun MemoryScreen(ui: UiState, onClear: () -> Unit) {
    val emColors = mapOf(
        "curious" to Cyan, "excited" to Amber, "melancholy" to Color(0xFF60A5FA),
        "glitchy" to Magenta, "mischievous" to Green, "neutral" to TextDimMid,
    )

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().background(BgPanel).padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            MonoLabel("// EPISODIC MEMORY — ${ui.memories.size} fragments", Cyan, 11.sp)
            OutlinedButton(
                onClick = onClear,
                border  = BorderStroke(1.dp, Magenta.copy(0.4f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("WIPE ALL", color = Magenta, fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
        }

        if (ui.memories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("no memories yet", color = TextDim, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.memories.reversed()) { mem ->
                    val ec = emColors[mem.emotionTag] ?: TextDimMid
                    Surface(
                        shape  = RoundedCornerShape(3.dp),
                        color  = BgCard,
                        border = BorderStroke(1.dp, Border),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    mem.memoryTags.take(3).forEach { tag ->
                                        TagChip(tag, ec)
                                    }
                                }
                                MonoLabel("imp:${"%.2f".format(mem.importance)}", TextDim, 8.sp)
                            }
                            Spacer(Modifier.height(5.dp))
                            // Importance bar
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp)
                                .background(Border).clip(RoundedCornerShape(1.dp))) {
                                Box(modifier = Modifier
                                    .fillMaxWidth(mem.importance)
                                    .height(2.dp)
                                    .background(ec))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(mem.entityText, color = TextMain, fontSize = 11.sp,
                                lineHeight = 17.sp)
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  SETTINGS SCREEN
// ═══════════════════════════════════════════════════════════════

@Composable
fun SettingsScreen(
    ui:          UiState,
    context:     Context,
    onLoadModel: (String) -> Unit,
) {
    val models = remember { ModelFileManager.listModels(context) }

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().background(BgPanel).padding(14.dp),
        ) {
            MonoLabel("// SYSTEM CONFIG", Cyan, 11.sp)
        }

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Model status ──────────────────────────────────
            SoulSection("INFERENCE ENGINE")
            SettingsCard(
                color = if (ui.modelLoaded) Green else Magenta,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(
                        if (ui.modelLoaded) Green else Magenta,
                        RoundedCornerShape(4.dp)
                    ))
                    MonoLabel(if (ui.modelLoaded) "MODEL ONLINE" else "NO MODEL LOADED", 
                        if (ui.modelLoaded) Green else Magenta, 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                MonoLabel("Backend: llama.cpp (CPU / NEON / dotprod / i8mm)", TextDim, 9.sp)
                MonoLabel("ABI: arm64-v8a (Cortex-A78 optimized)", TextDim, 9.sp)
                MonoLabel("Threads: 4 big cores", TextDim, 9.sp)
            }

            // ── Model selector ────────────────────────────────
            SoulSection("AVAILABLE MODELS")
            if (models.isEmpty()) {
                SettingsCard(Amber) {
                    MonoLabel("No .gguf files found in:", Amber, 10.sp)
                    Spacer(Modifier.height(4.dp))
                    MonoLabel(ModelFileManager.getModelsDir(context).absolutePath,
                        TextDim, 9.sp)
                    Spacer(Modifier.height(8.dp))
                    MonoLabel("Download a GGUF model and place it there:", TextDim, 9.sp)
                }
                // Recommended list
                SoulSection("RECOMMENDED MODELS FOR REVVL 7")
                ModelFileManager.RECOMMENDED_MODELS.forEach { info ->
                    SettingsCard(Cyan) {
                        MonoLabel(info.displayName, Cyan, 10.sp)
                        Spacer(Modifier.height(2.dp))
                        MonoLabel("${info.sizeGb}GB  |  Quality: ${info.quality}", TextDim, 9.sp)
                        MonoLabel(info.hfRepo, TextDimMid, 8.sp)
                    }
                }
            } else {
                models.forEach { file ->
                    val sizeGb = file.length() / (1024f * 1024f * 1024f)
                    SettingsCard(Cyan) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                MonoLabel(file.nameWithoutExtension, Cyan, 10.sp)
                                MonoLabel("${"%.2f".format(sizeGb)} GB", TextDim, 9.sp)
                            }
                            Button(
                                onClick = { onLoadModel(file.absolutePath) },
                                colors  = ButtonDefaults.buttonColors(
                                    containerColor = Cyan.copy(0.15f)),
                                border  = BorderStroke(1.dp, Cyan.copy(0.4f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("LOAD", color = Cyan, fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            // ── Model location instructions ───────────────────
            SoulSection("HOW TO ADD MODELS")
            SettingsCard(Purple) {
                listOf(
                    "1. Connect phone via USB (enable File Transfer)",
                    "2. Navigate to: Android/data/com.projectexe/files/models/",
                    "3. Copy your .gguf file there",
                    "4. Return here and tap LOAD",
                    "",
                    "Or use adb:",
                    "adb push model.gguf /sdcard/Android/data/com.projectexe/files/models/",
                ).forEach { line ->
                    Text(line, color = if (line.startsWith("adb")) Cyan else TextDim,
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp)
                }
            }

            // ── 45-node status ────────────────────────────────
            SoulSection("45-NODE ARCHITECTURE STATUS")
            val phases = listOf(
                Triple("Phase 1: Nodes 1–3",   "Ingestion / Security / Environment", "IMPL"),
                Triple("Phase 2: Nodes 4–8",   "Parallel Cognitive Array",           "PART"),
                Triple("Phase 3: Nodes 9–32",  "Integration / Monologue",            "STUB"),
                Triple("Phase 4: Nodes 33–36", "Dual-Hemisphere P→F→P→F→P",          "IMPL"),
                Triple("Phase 5: Nodes 37–45", "Output / Avatar / Memory / Diag",    "IMPL"),
            )
            SettingsCard(Border) {
                phases.forEach { (label, desc, status) ->
                    val sc = when (status) { "IMPL" -> Green; "PART" -> Amber; else -> TextDim }
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            MonoLabel(label, TextMain, 9.sp)
                            MonoLabel(desc, TextDim, 8.sp)
                        }
                        TagChip(status, sc)
                    }
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  SHARED COMPOSABLE HELPERS
// ═══════════════════════════════════════════════════════════════

@Composable
fun MonoLabel(text: String, color: Color, size: TextUnit) =
    Text(text, color = color, fontSize = size, fontFamily = FontFamily.Monospace,
        letterSpacing = 0.08.em)

@Composable
fun SoulSection(title: String) {
    Text(title, color = Cyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
        letterSpacing = 0.15.em,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
            .then(Modifier.drawBehind {  }).run { this })
    Divider(color = Border, thickness = 1.dp)
}

@Composable
fun SoulSlider(label: String, value: Float, color: Color, onChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            MonoLabel(label, TextDim, 9.sp)
            MonoLabel("${"%.2f".format(value)}", color, 9.sp)
        }
        Slider(
            value         = value,
            onValueChange = onChange,
            modifier      = Modifier.fillMaxWidth().height(24.dp),
            colors        = SliderDefaults.colors(
                thumbColor       = color,
                activeTrackColor = color,
                inactiveTrackColor = Border,
            ),
        )
    }
}

@Composable
fun SoulTextRow(label: String, value: String = "", onChange: (String) -> Unit) {
    Column {
        MonoLabel(label, TextDim, 9.sp)
        Spacer(Modifier.height(3.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = TextMain, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Cyan.copy(0.35f),
                unfocusedBorderColor = Border,
                cursorColor          = Cyan,
            ),
        )
    }
}

@Composable
fun SoulTextAreaRow(label: String, value: String, rows: Int, onChange: (String) -> Unit) {
    Column {
        MonoLabel(label, TextDim, 9.sp)
        Spacer(Modifier.height(3.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = TextMain, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp),
            minLines = rows, maxLines = rows + 2,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Cyan.copy(0.35f),
                unfocusedBorderColor = Border,
                cursorColor          = Cyan,
            ),
        )
    }
}

@Composable
fun SoulInfoBox(text: String, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(3.dp),
        color    = color.copy(0.06f),
        border   = BorderStroke(1.dp, color.copy(0.2f)),
    ) {
        Text(text, color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(10.dp, 6.dp))
    }
}

@Composable
fun SettingsCard(color: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(3.dp),
        color    = BgCard,
        border   = BorderStroke(1.dp, color.copy(0.2f)),
    ) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
fun TagChip(text: String, color: Color) {
    Surface(
        shape  = RoundedCornerShape(2.dp),
        color  = color.copy(0.12f),
        border = BorderStroke(1.dp, color.copy(0.3f)),
    ) {
        Text(text, color = color, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// Needed for SoulSection divider workaround
