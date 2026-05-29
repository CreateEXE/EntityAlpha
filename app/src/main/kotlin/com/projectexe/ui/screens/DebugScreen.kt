package com.projectexe.ui.screens

import androidx.compose.animation.*
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
import com.projectexe.engine.*
import com.projectexe.ui.UiState
import com.projectexe.ui.theme.*

// ─────────────────────────────────────────────────────────────
//  DEBUG SCREEN — testing, tuning, prompt inspection, A/B
// ─────────────────────────────────────────────────────────────

@Composable
fun DebugScreen(
    ui:               UiState,
    onApplyPreset:    (PersonalityPreset) -> Unit,
    onApplyOverrides: (StageOverrides) -> Unit,
    onRunAB:          (String, String) -> Unit,  // (queryA, queryB config label)
    onClearErrors:    () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("PRESETS", "SAMPLERS", "PROMPT", "A/B TEST", "ERRORS")

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        // Tab row
        ScrollableTabRow(
            selectedTabIndex  = selectedTab,
            containerColor    = BgPanel,
            contentColor      = Cyan,
            edgePadding       = 0.dp,
            indicator         = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color    = Cyan,
                )
            },
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = selectedTab == i,
                    onClick  = { selectedTab = i },
                    text     = {
                        Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.12.em)
                    },
                    selectedContentColor   = Cyan,
                    unselectedContentColor = TextDim,
                )
            }
        }

        when (selectedTab) {
            0 -> PresetTab(ui, onApplyPreset)
            1 -> SamplerTab(ui, onApplyOverrides)
            2 -> PromptInspectorTab(ui)
            3 -> ABTestTab(ui, onRunAB)
            4 -> ErrorTab(ui, onClearErrors)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  TAB 0: PERSONALITY PRESETS
// ═══════════════════════════════════════════════════════════════

@Composable
private fun PresetTab(ui: UiState, onApply: (PersonalityPreset) -> Unit) {
    LazyColumn(
        modifier        = Modifier.fillMaxSize(),
        contentPadding  = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                MonoLabel("// PERSONALITY PRESETS", Cyan, 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Swap the full soul document for testing. Preserves evolution history.",
                    color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp,
                )
            }
        }

        items(PersonalityPresets.ALL) { preset ->
            val isActive = ui.soul.identity.names.first == preset.soul.identity.names.first
            val color    = Color(preset.color)

            Surface(
                shape  = RoundedCornerShape(3.dp),
                color  = if (isActive) color.copy(0.10f) else BgCard,
                border = BorderStroke(1.dp, if (isActive) color.copy(0.6f) else Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                        ) {
                            if (isActive) {
                                Surface(
                                    shape = RoundedCornerShape(2.dp),
                                    color = color.copy(0.2f),
                                    border = BorderStroke(1.dp, color.copy(0.5f)),
                                ) {
                                    Text("ACTIVE", color = color, fontSize = 7.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(4.dp, 2.dp))
                                }
                            }
                            MonoLabel(preset.name, if (isActive) color else TextMain, 11.sp)
                        }
                        if (!isActive) {
                            Button(
                                onClick = { onApply(preset) },
                                colors  = ButtonDefaults.buttonColors(
                                    containerColor = color.copy(0.15f)),
                                border  = BorderStroke(1.dp, color.copy(0.4f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text("LOAD", color = color, fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(preset.description, color = TextDim, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 14.sp)

                    // Key trait snapshot
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val nm = preset.soul.psychology.neural_matrix
                        listOf(
                            "CREA" to nm.creativity,
                            "LOGI" to nm.logic,
                            "STAB" to nm.stability,
                        ).forEach { (k, v) ->
                            Surface(
                                shape = RoundedCornerShape(2.dp),
                                color = color.copy(0.08f),
                                border = BorderStroke(1.dp, color.copy(0.2f)),
                            ) {
                                Text("$k:${(v*100).toInt()}",
                                    color = color.copy(0.8f), fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(5.dp, 2.dp))
                            }
                        }
                        preset.samplers.p1?.let { cfg ->
                            Surface(
                                shape = RoundedCornerShape(2.dp),
                                color = Amber.copy(0.08f),
                                border = BorderStroke(1.dp, Amber.copy(0.2f)),
                            ) {
                                Text("T:${cfg.temperature}",
                                    color = Amber.copy(0.8f), fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(5.dp, 2.dp))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ═══════════════════════════════════════════════════════════════
//  TAB 1: SAMPLER OVERRIDES
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SamplerTab(ui: UiState, onApply: (StageOverrides) -> Unit) {
    var overrides by remember { mutableStateOf(StageOverrides()) }
    var activeStage by remember { mutableStateOf(SoulCompiler.Stage.P1) }

    // Current soul-derived defaults for comparison
    val nm = ui.soul.psychology.neural_matrix
    val soulPersonaTemp = nm.personaTemperature()
    val soulFactualTemp = nm.factualTemperature()

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().background(BgPanel).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLabel("// SAMPLER OVERRIDES", Cyan, 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick  = { overrides = StageOverrides() },
                    border   = BorderStroke(1.dp, Border),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("RESET ALL", color = TextDim, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick  = { onApply(overrides) },
                    colors   = ButtonDefaults.buttonColors(containerColor = Cyan.copy(0.15f)),
                    border   = BorderStroke(1.dp, Cyan.copy(0.5f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("APPLY ✓", color = Cyan, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Soul-derived defaults info
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp),
            color    = Purple.copy(0.08f),
            border   = BorderStroke(1.dp, Purple.copy(0.2f)),
            shape    = RoundedCornerShape(3.dp),
        ) {
            Text(
                "Soul-derived: PERSONA temp=${"%.3f".format(soulPersonaTemp)} " +
                "| FACTUAL temp=${"%.3f".format(soulFactualTemp)}\n" +
                "Overrides replace per-stage. null = use soul defaults.",
                color = Purple, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                lineHeight = 13.sp, modifier = Modifier.padding(10.dp, 6.dp),
            )
        }

        // Stage selector
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SoulCompiler.Stage.values().forEach { stage ->
                val active = stage == activeStage
                val isPersona = stage in listOf(SoulCompiler.Stage.P1, SoulCompiler.Stage.P2, SoulCompiler.Stage.P3)
                val col = if (isPersona) Cyan else Magenta
                val hasOverride = overrides.forStage(stage) != null
                FilterChip(
                    selected  = active,
                    onClick   = { activeStage = stage },
                    label     = {
                        Text("${stage.name}${if (hasOverride) "*" else ""}",
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    },
                    colors    = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = col.copy(0.18f),
                        selectedLabelColor     = col,
                        containerColor         = Color.Transparent,
                        labelColor             = TextDim,
                    ),
                    border    = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = active,
                        selectedBorderColor = col.copy(0.5f),
                        borderColor = Border,
                        borderWidth = 1.dp, selectedBorderWidth = 1.dp,
                    ),
                    modifier = Modifier.height(28.dp),
                )
            }
        }

        // Stage sampler editor
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StageSamplerEditor(
                stage     = activeStage,
                current   = overrides.forStage(activeStage),
                onChange  = { cfg -> overrides = when (activeStage) {
                    SoulCompiler.Stage.P1 -> overrides.copy(p1 = cfg)
                    SoulCompiler.Stage.F1 -> overrides.copy(f1 = cfg)
                    SoulCompiler.Stage.P2 -> overrides.copy(p2 = cfg)
                    SoulCompiler.Stage.F2 -> overrides.copy(f2 = cfg)
                    SoulCompiler.Stage.P3 -> overrides.copy(p3 = cfg)
                }},
                onClear   = { overrides = when (activeStage) {
                    SoulCompiler.Stage.P1 -> overrides.copy(p1 = null)
                    SoulCompiler.Stage.F1 -> overrides.copy(f1 = null)
                    SoulCompiler.Stage.P2 -> overrides.copy(p2 = null)
                    SoulCompiler.Stage.F2 -> overrides.copy(f2 = null)
                    SoulCompiler.Stage.P3 -> overrides.copy(p3 = null)
                }},
            )
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun StageSamplerEditor(
    stage:    SoulCompiler.Stage,
    current:  SamplerConfig?,
    onChange: (SamplerConfig) -> Unit,
    onClear:  () -> Unit,
) {
    val draft = current ?: SamplerConfig()
    val isPersona = stage in listOf(SoulCompiler.Stage.P1, SoulCompiler.Stage.P2, SoulCompiler.Stage.P3)
    val col = if (isPersona) Cyan else Magenta
    val isOverrideActive = current != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(3.dp),
        color    = col.copy(0.05f),
        border   = BorderStroke(1.dp, col.copy(if (isOverrideActive) 0.4f else 0.15f)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoLabel(
                    "${stage.name} — ${if (isPersona) "PERSONA (Dolphin)" else "FACTUAL (DeepSeek)"}",
                    col, 10.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isOverrideActive) {
                        TextButton(
                            onClick = onClear,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("CLEAR OVERRIDE", color = TextDim, fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                    Button(
                        onClick = { onChange(draft) },
                        colors  = ButtonDefaults.buttonColors(containerColor = col.copy(0.15f)),
                        border  = BorderStroke(1.dp, col.copy(0.4f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text("SET", color = col, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (!isOverrideActive) {
                Text("Using soul-derived defaults. Tap SET to override.",
                    color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }

            // Core sampling params
            DebugSlider("TEMPERATURE",    draft.temperature,      0f,  2f,   col) { onChange(draft.copy(temperature      = it)) }
            DebugSlider("TOP_P",          draft.topP,             0f,  1f,   col) { onChange(draft.copy(topP             = it)) }
            DebugSlider("MIN_P",          draft.minP,             0f,  0.5f, col) { onChange(draft.copy(minP             = it)) }
            DebugSlider("REPEAT_PENALTY", draft.repeatPenalty,    1f,  1.5f, Amber) { onChange(draft.copy(repeatPenalty  = it)) }
            DebugSlider("FREQ_PENALTY",   draft.frequencyPenalty, 0f,  1f,   Amber) { onChange(draft.copy(frequencyPenalty= it)) }
            DebugSlider("PRES_PENALTY",   draft.presencePenalty,  0f,  1f,   Amber) { onChange(draft.copy(presencePenalty = it)) }

            // TOP_K (int slider via float)
            DebugSlider("TOP_K (${draft.topK})", draft.topK.toFloat(), 1f, 100f, col) {
                onChange(draft.copy(topK = it.toInt()))
            }

            // MAX TOKENS
            DebugSlider("MAX_TOKENS (${draft.maxTokens})", draft.maxTokens.toFloat(), 50f, 1024f, Green) {
                onChange(draft.copy(maxTokens = it.toInt()))
            }

            // Mirostat
            SoulSection("MIROSTAT")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 2).forEach { mode ->
                    FilterChip(
                        selected = draft.mirostatMode == mode,
                        onClick  = { onChange(draft.copy(mirostatMode = mode)) },
                        label    = {
                            Text(if (mode == 0) "OFF" else "v$mode",
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple.copy(0.2f),
                            selectedLabelColor     = Purple,
                            containerColor         = Color.Transparent,
                            labelColor             = TextDim,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = draft.mirostatMode == mode,
                            selectedBorderColor = Purple.copy(0.5f),
                            borderColor = Border, borderWidth = 1.dp, selectedBorderWidth = 1.dp,
                        ),
                        modifier = Modifier.height(26.dp),
                    )
                }
            }
            if (draft.mirostatMode != 0) {
                DebugSlider("MIROSTAT_TAU", draft.mirostatTau, 0f, 10f, Purple) {
                    onChange(draft.copy(mirostatTau = it))
                }
                DebugSlider("MIROSTAT_ETA", draft.mirostatEta, 0f, 1f, Purple) {
                    onChange(draft.copy(mirostatEta = it))
                }
            }
        }
    }
}

@Composable
private fun DebugSlider(
    label:    String,
    value:    Float,
    min:      Float,
    max:      Float,
    color:    Color,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text("${"%.3f".format(value)}", color = color, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value, onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.fillMaxWidth().height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor        = color,
                activeTrackColor  = color,
                inactiveTrackColor = Border,
            ),
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  TAB 2: PROMPT INSPECTOR
// ═══════════════════════════════════════════════════════════════

@Composable
private fun PromptInspectorTab(ui: UiState) {
    var selectedStage by remember { mutableStateOf(SoulCompiler.Stage.P1) }

    val compiledPrompt = remember(ui.soul, selectedStage) {
        when (selectedStage) {
            SoulCompiler.Stage.P1,
            SoulCompiler.Stage.P2,
            SoulCompiler.Stage.P3 -> SoulCompiler.compilePersona(ui.soul, selectedStage)
            SoulCompiler.Stage.F1 -> SoulCompiler.compileFactual(SoulCompiler.Stage.F1)
            SoulCompiler.Stage.F2 -> SoulCompiler.compileFactual(SoulCompiler.Stage.F2)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        // Stage selector
        Row(
            modifier = Modifier.fillMaxWidth().background(BgPanel).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLabel("STAGE:", TextDim, 9.sp)
            SoulCompiler.Stage.values().forEach { stage ->
                val isPersona = stage in listOf(SoulCompiler.Stage.P1, SoulCompiler.Stage.P2, SoulCompiler.Stage.P3)
                val col = if (isPersona) Cyan else Magenta
                FilterChip(
                    selected = stage == selectedStage,
                    onClick  = { selectedStage = stage },
                    label    = { Text(stage.name, fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = col.copy(0.18f),
                        selectedLabelColor     = col,
                        containerColor         = Color.Transparent,
                        labelColor             = TextDim,
                    ),
                    border   = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = stage == selectedStage,
                        selectedBorderColor = col.copy(0.5f),
                        borderColor = Border, borderWidth = 1.dp, selectedBorderWidth = 1.dp,
                    ),
                    modifier = Modifier.height(26.dp),
                )
            }
        }

        // Prompt stats
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp, 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val charCount  = compiledPrompt.length
            val lineCount  = compiledPrompt.lines().size
            val approxToks = (charCount / 3.8f).toInt()
            listOf("CHARS" to "$charCount", "LINES" to "$lineCount", "~TOKENS" to "$approxToks").forEach { (k,v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MonoLabel(k, TextDim, 8.sp)
                    MonoLabel(v, Amber, 8.sp)
                }
            }
        }

        // Prompt text (selectable)
        Column(modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(3.dp),
                color    = Color(0xFF020305),
                border   = BorderStroke(1.dp, Border),
            ) {
                SelectionContainer {
                    Text(
                        text       = compiledPrompt,
                        color      = TextDim,
                        fontSize   = 10.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier   = Modifier.padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  TAB 3: A/B TEST
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ABTestTab(ui: UiState, onRun: (String, String) -> Unit) {
    var query      by remember { mutableStateOf("") }
    var configNote by remember { mutableStateOf("current config") }

    Column(
        modifier = Modifier.fillMaxSize().background(BgDeep)
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonoLabel("// A/B TEST MODE", Cyan, 11.sp)
        Text(
            "Runs the same query twice with the current soul + sampler config.\n" +
            "Compare P1 creative variance and F1 fact-check differences.",
            color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp,
        )

        OutlinedTextField(
            value         = query,
            onValueChange = { query = it },
            modifier      = Modifier.fillMaxWidth(),
            label         = { Text("Test Query", color = TextDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace) },
            placeholder   = { Text("e.g. What is the speed of light?", color = TextDim,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
            textStyle     = androidx.compose.ui.text.TextStyle(
                color = TextMain, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            minLines = 2,
            colors   = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Cyan.copy(0.35f),
                unfocusedBorderColor = Border, cursorColor = Cyan,
            ),
        )

        OutlinedTextField(
            value         = configNote,
            onValueChange = { configNote = it },
            modifier      = Modifier.fillMaxWidth(),
            label         = { Text("Config Label", color = TextDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace) },
            textStyle     = androidx.compose.ui.text.TextStyle(
                color = TextMain, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            singleLine    = true,
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Amber.copy(0.35f),
                unfocusedBorderColor = Border, cursorColor = Amber,
            ),
        )

        // Current config summary
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(3.dp),
            color    = Purple.copy(0.06f),
            border   = BorderStroke(1.dp, Purple.copy(0.2f)),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                MonoLabel("CURRENT CONFIG SNAPSHOT", Purple, 9.sp)
                Spacer(Modifier.height(4.dp))
                val nm = ui.soul.psychology.neural_matrix
                listOf(
                    "Entity"    to ui.soul.identity.names.first,
                    "MBTI"      to ui.soul.psychology.mbti,
                    "P-Temp"    to "${"%.3f".format(nm.personaTemperature())}",
                    "F-Temp"    to "${"%.3f".format(nm.factualTemperature())}",
                    "Mode"      to ui.pipelineMode.name,
                ).forEach { (k, v) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$k:", color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(60.dp))
                        Text(v, color = TextMain, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Button(
            onClick  = { if (query.isNotBlank()) onRun(query.trim(), configNote) },
            enabled  = query.isNotBlank() && !ui.isLoading,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = Cyan.copy(0.15f)),
            border   = BorderStroke(1.dp, Cyan.copy(if (query.isNotBlank()) 0.5f else 0.2f)),
        ) {
            Text("RUN A/B TEST (×2 pipeline)", color = Cyan, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
        }

        if (ui.isLoading) {
            Text("Pipeline running... see Chat tab for streaming output.",
                color = Amber, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════
//  TAB 4: ERROR LOG
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ErrorTab(ui: UiState, onClear: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(BgPanel).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            MonoLabel("// ERROR LOG — ${ui.errorLog.size} entries", Magenta, 11.sp)
            OutlinedButton(
                onClick = onClear,
                border  = BorderStroke(1.dp, Magenta.copy(0.4f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("CLEAR", color = Magenta, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }

        if (ui.errorLog.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("no errors recorded", color = TextDim, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }
        } else {
            LazyColumn(
                modifier       = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.errorLog.reversed()) { entry ->
                    Surface(
                        shape  = RoundedCornerShape(3.dp),
                        color  = Magenta.copy(0.06f),
                        border = BorderStroke(1.dp, Magenta.copy(0.25f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                MonoLabel(entry.type, Magenta, 9.sp)
                                MonoLabel(entry.time, TextDim, 8.sp)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(entry.message, color = TextMain, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                            entry.recovery?.let { rec ->
                                Spacer(Modifier.height(3.dp))
                                Text("Recovery: $rec", color = Amber, fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

// Needed imports
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.ScrollableTabRow
