package com.pickle.patcher.ui.screens

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickle.patcher.patcher.CompileState
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.patcher.SmaSource
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray80
import com.pickle.patcher.ui.theme.Gray90
import com.pickle.patcher.ui.theme.SuccessGreen
import com.pickle.patcher.ui.theme.White

@Composable
fun CompilerScreen(vm: PatcherViewModel) {
    val scroll = rememberScrollState()
    val scripts by vm.scripts.collectAsState()
    val compile by vm.compile.collectAsState()
    val scriptRoot by vm.scriptRoot.collectAsState()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    val outputRoot by vm.outputRoot.collectAsState()
    var pickForOutput by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Scroll the outer column to the compiler output when a compile starts.
    LaunchedEffect(compile) {
        if (compile is CompileState.Compiling) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (pickForOutput) uri?.let(vm::setOutputRoot) else uri?.let(vm::setScriptRoot)
        pickForOutput = false
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                folderPicker.launch(null)
            } else {
                showPermissionRationale = true
            }
        }
    }

    fun requestStorageAndPickFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                folderPicker.launch(null)
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
                storagePermissionLauncher.launch(intent)
            }
        } else {
            folderPicker.launch(null)
        }
    }

    val selectedSources = scripts.filter { it.path in selected }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Compiler", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            "Compile .sma plugins with the local amxxpc.",
            style = MaterialTheme.typography.bodyMedium,
            color = Gray40,
        )

        Spacer(Modifier.height(20.dp))

        SectionHeader("SCRIPTS FOLDER")
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scriptRoot ?: "No folder selected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scriptRoot != null) Accent else Gray40,
                    modifier = Modifier.weight(1f),
                )
                if (scriptRoot != null) {
                    IconButton(onClick = { vm.refreshScripts() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Refresh, "Refresh", modifier = Modifier.size(18.dp), tint = Gray40)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = if (scriptRoot != null) "Change" else "Select folder",
                onClick = { pickForOutput = false; requestStorageAndPickFolder() },
                icon = { Icon(Icons.Filled.CreateNewFolder, null, modifier = Modifier.size(18.dp)) },
            )

            if (showPermissionRationale) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AlertRed.copy(alpha = 0.1f),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Storage permission is required.",
                            style = MaterialTheme.typography.titleSmall,
                            color = AlertRed,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "The compiler needs access to your files to read .sma scripts and write compiled .amxx output. Please grant \"All files access\" in the system settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray40,
                        )
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(
                            text = "Open Settings",
                            onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                storagePermissionLauncher.launch(intent)
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader("OUTPUT FOLDER")
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    outputRoot ?: "Default: <scripts>/compiled",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (outputRoot != null) Accent else Gray40,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                PrimaryButton(
                    text = if (outputRoot != null) "Change" else "Select folder",
                    onClick = { pickForOutput = true; requestStorageAndPickFolder() },
                    icon = { Icon(Icons.Filled.CreateNewFolder, null, modifier = Modifier.size(18.dp)) },
                )
                if (outputRoot != null) {
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton(
                        text = "Default",
                        onClick = { vm.clearOutputRoot() },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader("PLUGINS (.sma)")
        AppCard {
            if (scriptRoot == null) {
                Text("Select a folder above.", style = MaterialTheme.typography.bodySmall, color = Gray40)
            } else if (scripts.isEmpty()) {
                Text("No .sma files found.", style = MaterialTheme.typography.bodySmall, color = Gray40)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selected.isEmpty()) "${scripts.size} scripts" else "${selected.size} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected.isNotEmpty()) Accent else Gray40,
                    )
                    Row {
                        if (scripts.size > 1) {
                            GhostButton(text = if (selected.size == scripts.size) "Clear" else "All") {
                                selected = if (selected.size != scripts.size) scripts.map { it.path }.toSet() else emptySet()
                            }
                        }
                    }
                }
                scripts.forEach { s ->
                    ScriptRow(s, s.path in selected) {
                        selected = if (s.path in selected) selected - s.path else selected + s.path
                    }
                }
            }
        }

        if (selectedSources.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("COMPILE")
            AppCard {
                Text(
                    if (selectedSources.size == 1)
                        selectedSources.first().name
                    else
                        "${selectedSources.size} plugins selected",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (selectedSources.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "All selected plugins will be compiled in order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                Text(selectedSources.first().scriptDir, style = MaterialTheme.typography.bodySmall, color = Gray40)
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = if (selectedSources.size == 1) "Compile" else "Compile All (${selectedSources.size})",
                    onClick = { vm.compileAll(selectedSources) },
                    enabled = compile !is CompileState.Compiling,
                    icon = { Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp)) },
                )
            }
        }

        when (val c = compile) {
            is CompileState.Compiling -> {
                Spacer(Modifier.height(12.dp))
                SectionHeader("OUTPUT")
                LogBox(c.source, busy = true)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            }
            is CompileState.Done -> {
                Spacer(Modifier.height(12.dp))
                SectionHeader("OUTPUT")
                LogBox(c.log)
            }
            is CompileState.Failed -> {
                Spacer(Modifier.height(12.dp))
                SectionHeader("OUTPUT")
                LogBox(c.message, error = true)
            }
            else -> {}
        }
    }
}

@Composable
private fun ScriptRow(s: SmaSource, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Gray80 else Gray90,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (s.hasInclude) "include/ ✓" else "no include/",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (s.hasInclude) SuccessGreen else Gray40,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, null,
                    tint = Accent, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LogBox(text: String, error: Boolean = false, busy: Boolean = false) {
    val scrollState = rememberScrollState()
    // Keep the log view pinned to its own bottom-out point: kick the scroll to
    // max on each update so the newest lines stay visible while compiling.
    LaunchedEffect(text) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Gray80,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp, max = 240.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
            color = when {
                busy -> Accent
                error -> AlertRed
                else -> Gray40
            },
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(10.dp),
        )
    }
}
