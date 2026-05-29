package com.projectexe.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import com.projectexe.data.PipelineStage
import com.projectexe.engine.PipelineMode
import com.projectexe.ui.*
import com.projectexe.ui.theme.*

// ── Stage pill labels ─────────────────────────────────────────
private val STAGE_LABELS = listOf(
    PipelineStage.P1   to ("P1" to Cyan),
    PipelineStage.F1   to ("F1" to Magenta),
    PipelineStage.P2   to ("P2" to Cyan),
    PipelineStage.F2   to ("F2" to Magenta),
    PipelineStage.P3   to ("P3" to Cyan),
    PipelineStage.MEMORY to ("MEM" to Amber),
)

private val EMOTION_COLORS = mapOf(
    "curious" to Cyan, "excited" to Amber, "melancholy" to Color(0xFF60A5FA),
    "glitchy" to Magenta, "mischievous" to Green, "calm" to Purple,
    "processing" to TextDim, "neutral" to TextDimMid,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    ui:            UiState,
    onSend:        (String) -> Unit,
    onAbort:       () -> Unit,
    onClear:       () -> Unit,
    onModeChange:  (PipelineMode) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new message or streaming update
    LaunchedEffect(ui.messages.size, ui.streamingText.length) {
        if (ui.messages.isNotEmpty()) {
            listState.animateScrollToItem(
                (ui.messages.size - 1 + if (ui.streamingText.isNotEmpty()) 1 else 0)
                    .coerceAtLeast(0)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        // ── Pipeline stage bar ────────────────────────────────
        AnimatedVisibility(visible = ui.isLoading) {
            PipelineStageBar(ui.pipelineStage, ui.tokensPerSec)
        }

        // ── Mode selector ─────────────────────────────────────
        ModeSelector(ui.pipelineMode, onModeChange)

        // ── Messages ──────────────────────────────────────────
        LazyColumn(
            state            = listState,
            modifier         = Modifier.weight(1f),
            contentPadding   = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ui.messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            // Streaming bubble
            if (ui.streamingText.isNotEmpty()) {
                item {
                    StreamingBubble(ui.streamingText, ui.soul.identity.names.first)
                }
            }
            // Empty state
            if (ui.messages.isEmpty() && !ui.isLoading) {
                item { EmptyState(ui.modelLoaded, ui.soul.identity.names.first) }
            }
        }

        // ── Error banner ──────────────────────────────────────
        ui.modelError?.let { err ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color    = Magenta.copy(alpha = 0.12f),
            ) {
                Text(
                    text     = err,
                    color    = Magenta,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp, 8.dp),
                )
            }
        }

        // ── Input bar ─────────────────────────────────────────
        InputBar(
            input      = input,
            isLoading  = ui.isLoading,
            onInput    = { input = it },
            onSend     = {
                if (input.isNotBlank()) { onSend(input.trim()); input = "" }
            },
            onAbort    = onAbort,
            onClear    = onClear,
        )
    }
}

@Composable
private fun PipelineStageBar(currentStage: PipelineStage, tps: Float) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = BgPanel,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                STAGE_LABELS.forEachIndexed { i, (stage, pair) ->
                    val (label, color) = pair
                    val stageIdx   = STAGE_LABELS.indexOfFirst { it.first == currentStage }
                    val isDone     = i < stageIdx
                    val isActive   = stage == currentStage
                    StagePill(label, color, isDone, isActive)
                    if (i < STAGE_LABELS.size - 1) {
                        Text("→", color = TextDim, fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (tps > 0f) {
                    Text("${tps.toInt()} tok/s", color = Amber,
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun StagePill(label: String, color: Color, done: Boolean, active: Boolean) {
    val bg    = when { active -> color.copy(alpha=0.18f); done -> color.copy(alpha=0.08f); else -> Color.Transparent }
    val bdr   = when { active -> color.copy(alpha=0.7f);  done -> color.copy(alpha=0.3f);  else -> TextDim.copy(0.3f) }
    val txt   = if (active || done) color else TextDim

    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(3.dp))
            .border(1.dp, bdr, RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(label, color = txt, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ModeSelector(mode: PipelineMode, onSelect: (PipelineMode) -> Unit) {
    Surface(color = BgPanel, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("MODE:", color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            PipelineMode.values().forEach { m ->
                val active = m == mode
                val col    = when (m) { PipelineMode.FULL -> Cyan; PipelineMode.PERSONA_ONLY -> Purple; PipelineMode.QUICK -> Green }
                val label  = when (m) { PipelineMode.FULL -> "FULL"; PipelineMode.PERSONA_ONLY -> "PERSONA"; PipelineMode.QUICK -> "QUICK" }
                FilterChip(
                    selected = active,
                    onClick  = { onSelect(m) },
                    label    = { Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor    = col.copy(0.18f),
                        selectedLabelColor        = col,
                        containerColor            = Color.Transparent,
                        labelColor                = TextDim,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled          = true,
                        selected         = active,
                        selectedBorderColor = col.copy(0.5f),
                        borderColor      = Border,
                        borderWidth      = 1.dp,
                        selectedBorderWidth = 1.dp,
                    ),
                    modifier = Modifier.height(26.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isBot  = msg.role == "assistant"
    val emCol  = EMOTION_COLORS[msg.emotionTag] ?: TextDimMid
    var showTrace by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isBot) Arrangement.Start else Arrangement.End,
    ) {
        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            // Bubble
            Surface(
                shape  = RoundedCornerShape(
                    topStart = if (isBot) 3.dp else 12.dp,
                    topEnd   = if (isBot) 12.dp else 3.dp,
                    bottomStart = 12.dp, bottomEnd = 12.dp,
                ),
                color  = if (isBot) BgCard else Color(0xFF080F22),
                border = BorderStroke(1.dp, if (isBot) (if (msg.isError) Magenta.copy(0.4f) else Cyan.copy(0.12f)) else Color(0xFF101C38)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (isBot) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("exe", color = TextDim, fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace)
                            // Emotion tag
                            if (msg.emotionTag.isNotEmpty() && msg.emotionTag != "neutral") {
                                Surface(
                                    shape  = RoundedCornerShape(2.dp),
                                    color  = emCol.copy(0.15f),
                                    border = BorderStroke(1.dp, emCol.copy(0.35f)),
                                ) {
                                    Text(msg.emotionTag, color = emCol, fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                }
                            }
                            if (msg.pipelineMs > 0) {
                                Text("${msg.pipelineMs}ms", color = TextDim, fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                            // Trace toggle
                            if (msg.p1Text.isNotEmpty()) {
                                TextButton(
                                    onClick  = { showTrace = !showTrace },
                                    modifier = Modifier.height(20.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                ) {
                                    Text(if (showTrace) "HIDE" else "TRACE",
                                        color = TextDim, fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                    }

                    Text(
                        text       = msg.content,
                        color      = if (msg.isError) Magenta else TextMain,
                        fontSize   = 13.sp,
                        lineHeight = 20.sp,
                        fontFamily = if (isBot) FontFamily.Monospace else FontFamily.Default,
                    )

                    if (!isBot) {
                        Spacer(Modifier.height(4.dp))
                        Text("you", color = TextDim, fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.End))
                    }
                }
            }

            // Pipeline trace
            AnimatedVisibility(visible = showTrace && msg.p1Text.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape    = RoundedCornerShape(3.dp),
                    color    = Purple.copy(0.06f),
                    border   = BorderStroke(1.dp, Purple.copy(0.2f)),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("// PIPELINE TRACE", color = Purple, fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        if (msg.p1Text.isNotEmpty()) TraceRow("P1", msg.p1Text, Cyan)
                        if (msg.f1Analysis.isNotEmpty()) TraceRow("F1", msg.f1Analysis, Magenta)
                    }
                }
            }

            // VRM frame hint (for future avatar integration)
            if (isBot && msg.blendShape != null) {
                Row(
                    modifier = Modifier.padding(start = 2.dp, top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Chip("VRM:${msg.blendShape.expression_key}", emCol)
                }
            }
        }
    }
}

@Composable
private fun TraceRow(label: String, text: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label ", color = color, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        Text(text.take(120) + if (text.length > 120) "…" else "",
            color = TextDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            lineHeight = 13.sp)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Surface(
        shape  = RoundedCornerShape(2.dp),
        color  = color.copy(0.10f),
        border = BorderStroke(1.dp, color.copy(0.25f)),
    ) {
        Text(text, color = color, fontSize = 7.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

@Composable
private fun StreamingBubble(text: String, petName: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape  = RoundedCornerShape(topStart = 3.dp, topEnd = 12.dp,
                bottomStart = 12.dp, bottomEnd = 12.dp),
            color  = BgCard,
            border = BorderStroke(1.dp, Cyan.copy(0.12f)),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp, 10.dp)) {
                Text("$petName.exe · streaming", color = TextDim, fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(5.dp))
                Text(
                    text       = text + "▌",
                    color      = TextMain,
                    fontSize   = 13.sp,
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modelLoaded: Boolean, petName: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚡", fontSize = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (modelLoaded)
                    "// $petName.exe ONLINE\nSay something."
                else
                    "// NO MODEL LOADED\nGo to Settings → Load Model",
                color      = TextDim,
                fontSize   = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun InputBar(
    input:    String,
    isLoading:Boolean,
    onInput:  (String) -> Unit,
    onSend:   () -> Unit,
    onAbort:  () -> Unit,
    onClear:  () -> Unit,
) {
    Surface(color = BgPanel, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Clear
            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Clear",
                    tint = TextDim, modifier = Modifier.size(18.dp))
            }

            // Text input
            OutlinedTextField(
                value         = input,
                onValueChange = onInput,
                modifier      = Modifier.weight(1f),
                placeholder   = {
                    Text("speak to the entity...", color = TextDim,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color      = TextMain,
                    fontSize   = 13.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines  = 4,
                colors    = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Cyan.copy(0.35f),
                    unfocusedBorderColor = Border,
                    cursorColor          = Cyan,
                ),
            )

            // Send / Abort
            if (isLoading) {
                IconButton(
                    onClick  = onAbort,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop",
                        tint = Magenta, modifier = Modifier.size(22.dp))
                }
            } else {
                IconButton(
                    onClick  = onSend,
                    enabled  = input.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send",
                        tint = if (input.isNotBlank()) Cyan else TextDim,
                        modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
