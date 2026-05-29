package com.projectexe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectexe.engine.*
import com.projectexe.ui.*
import com.projectexe.ui.screens.*
import com.projectexe.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LlamaEngine.init()
        setContent { ProjectEXETheme { ProjectEXEApp() } }
    }
}

enum class Screen(val label: String, val icon: ImageVector) {
    CHAT("Chat",     Icons.Default.Chat),
    SOUL("Soul",     Icons.Default.AutoAwesome),
    MEMORY("Memory", Icons.Default.Memory),
    DEBUG("Debug",   Icons.Default.BugReport),
    SYSTEM("System", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectEXEApp() {
    val vm: MainViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var currentScreen by remember { mutableStateOf(Screen.CHAT) }

    // Global error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(ui.currentError) {
        ui.currentError?.let { ewr ->
            val result = snackbarHostState.showSnackbar(
                message     = ewr.error.message.take(80),
                actionLabel = ewr.recoveryLabel.ifBlank { "Dismiss" },
                duration    = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (ewr.recovery) {
                    RecoveryAction.GO_TO_SETTINGS       -> currentScreen = Screen.SYSTEM
                    RecoveryAction.SWITCH_TO_QUICK_MODE -> vm.setPipelineMode(PipelineMode.QUICK)
                    RecoveryAction.CLEAR_CHAT           -> vm.clearChat()
                    RecoveryAction.RELOAD_MODEL         -> currentScreen = Screen.SYSTEM
                    RecoveryAction.RESTART_APP          -> { /* prompt user to restart */ }
                    else                                -> {}
                }
                vm.dismissError()
            }
        }
    }

    Scaffold(
        containerColor    = BgDeep,
        snackbarHost      = { SnackbarHost(snackbarHostState) { data ->
            Snackbar(
                snackbarData     = data,
                containerColor   = Color(0xFF0D0A14),
                contentColor     = TextMain,
                actionColor      = Magenta,
                shape            = RoundedCornerShape(3.dp),
            )
        }},
        topBar  = { TopBar(ui) },
        bottomBar = { BottomBar(currentScreen) { currentScreen = it } },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(BgDeep)) {
            when (currentScreen) {
                Screen.CHAT   -> ChatScreen(ui, vm::sendMessage, vm::abortPipeline, vm::clearChat, vm::setPipelineMode)
                Screen.SOUL   -> SoulScreen(ui, vm::updateSoul)
                Screen.MEMORY -> MemoryScreen(ui, vm::clearMemories)
                Screen.DEBUG  -> DebugScreen(ui, vm::applyPreset, vm::applyStageOverrides, vm::runABTest, vm::clearErrorLog)
                Screen.SYSTEM -> SettingsScreen(ui, ctx, vm::loadModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(ui: UiState) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(7.dp).background(
                    if (ui.modelLoaded) Green else TextDim,
                    androidx.compose.foundation.shape.CircleShape))
                Text("PROJECT.EXE", color = Cyan, fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 0.2.em)
                Text(ui.soul.identity.names.first, color = TextDim, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
                if (ui.degradationLevel != DegradationLevel.FULL && ui.isLoading) {
                    Surface(shape = RoundedCornerShape(2.dp), color = Amber.copy(0.15f),
                        border = BorderStroke(1.dp, Amber.copy(0.4f))) {
                        Text("DEGRADED:${ui.degradationLevel.name}", color = Amber,
                            fontSize = 7.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(4.dp, 2.dp))
                    }
                }
            }
        },
        actions = {
            Row(modifier = Modifier.padding(end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BadgeChip("C${ui.soul.evolution.cycle}", Purple)
                BadgeChip("${ui.soul.evolution.total_interactions}TX", Amber)
                BadgeChip(ui.soul.psychology.mbti, Cyan)
                if (ui.errorLog.isNotEmpty())
                    BadgeChip("${ui.errorLog.size}ERR", Magenta)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPanel, titleContentColor = Cyan),
    )
}

@Composable
private fun BottomBar(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(containerColor = BgPanel, tonalElevation = 0.dp) {
        Screen.values().forEach { screen ->
            val selected = screen == current
            NavigationBarItem(
                selected = selected, onClick = { onSelect(screen) },
                icon     = { Icon(screen.icon, null, modifier = Modifier.size(20.dp)) },
                label    = { Text(screen.label, fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                colors   = NavigationBarItemDefaults.colors(
                    selectedIconColor    = Cyan, selectedTextColor    = Cyan,
                    indicatorColor       = Cyan.copy(0.12f),
                    unselectedIconColor  = TextDim, unselectedTextColor  = TextDim,
                ),
            )
        }
    }
}

@Composable
private fun BadgeChip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(2.dp), color = color.copy(0.12f),
        border = BorderStroke(1.dp, color.copy(0.3f))) {
        Text(text, color = color, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
    }
}
