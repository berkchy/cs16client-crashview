package com.pickle.patcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pickle.patcher.patcher.PatcherViewModel
import kotlinx.coroutines.launch
import com.pickle.patcher.ui.screens.AddonsScreen
import com.pickle.patcher.ui.screens.CompilerScreen
import com.pickle.patcher.ui.screens.PluginsScreen
import com.pickle.patcher.ui.screens.CrashLogScreen
import com.pickle.patcher.ui.screens.PatchScreen
import com.pickle.patcher.ui.theme.AmxxPatcherTheme
import com.pickle.patcher.ui.theme.Black
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray85
import com.pickle.patcher.ui.theme.Gray90
import com.pickle.patcher.ui.theme.White
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmxxPatcherTheme {
                val vm: PatcherViewModel = viewModel()
                LaunchedEffect(Unit) {
                    vm.autoInstallAddons()
                    vm.scanAddonsStatus()
                }
                PatcherApp(vm)
            }
        }
    }
}

private enum class Dest(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Patch("patch", "Patch", Icons.Filled.RocketLaunch, Icons.Outlined.RocketLaunch),
    Compiler("compiler", "Compile", Icons.Filled.Code, Icons.Outlined.Code),
    Addons("addons", "Addons", Icons.Filled.Extension, Icons.Outlined.Extension),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatcherApp(vm: PatcherViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val context = androidx.compose.ui.platform.LocalContext.current
    val update by vm.appUpdate.collectAsState()
    val pollEnabled by vm.pollEnabled.collectAsState()

    // Poll GitHub releases every 15s while the app is open. Stops as soon as
    // the popup shows or the user interacts with it (dismiss/download).
    LaunchedEffect(pollEnabled) {
        if (!pollEnabled) return@LaunchedEffect
        vm.checkAppUpdate()
        while (vm.pollEnabled.value) {
            kotlinx.coroutines.delay(15_000)
            if (!vm.pollEnabled.value) break
            when (vm.appUpdate.value) {
                is PatcherViewModel.AppUpdate.Idle,
                is PatcherViewModel.AppUpdate.Failed -> vm.checkAppUpdate()
                else -> break
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(update) {
        val u = update
        if (u is PatcherViewModel.AppUpdate.Downloaded) {
            vm.installIntentFor(u.file)?.let { context.startActivity(it) }
            vm.consumeDownloaded()
        }
    }

    Scaffold(
        containerColor = Black,
        contentColor = White,
        topBar = {
            TopAppBar(
                title = { Text("CS16-Meta Patcher", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (currentRoute == "plugins") {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(
                                androidx.compose.material.icons.automirrored.filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
                actions = { OverflowMenu(vm, nav) },
            )
        },
        bottomBar = {
            Surface(
                color = Gray90,
                shadowElevation = 8.dp,
            ) {
                NavigationBar(
                    containerColor = Gray90,
                    tonalElevation = 0.dp,
                ) {
                    Dest.entries.forEach { dest ->
                        val selected = currentRoute == dest.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    nav.navigate(dest.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.icon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = {
                                Text(
                                    dest.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = Gray40,
                                unselectedTextColor = Gray40,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Patch.route,
            modifier = Modifier.fillMaxSize().padding(padding),
            enterTransition = { fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(200)) },
        ) {
            composable(Dest.Patch.route) { PatchScreen(vm) }
            composable(Dest.Compiler.route) { CompilerScreen(vm) }
            composable(Dest.Addons.route) { AddonsScreen(vm) }
            composable("plugins") { PluginsScreen(vm) }
        }
    }

    val showUpdateDialog = update is PatcherViewModel.AppUpdate.Available ||
        update is PatcherViewModel.AppUpdate.Downloading ||
        update is PatcherViewModel.AppUpdate.Failed
    if (showUpdateDialog) {
        UpdateDialog(vm, update)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes.toDouble() / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

@Composable
private fun OverflowMenu(
    vm: PatcherViewModel,
    nav: androidx.navigation.NavHostController,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAbout by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Update check") },
            onClick = {
                expanded = false
                vm.checkAppUpdate(silent = false)
            },
        )
        DropdownMenuItem(
            text = { Text("Plugins") },
            onClick = {
                expanded = false
                nav.navigate("plugins")
            },
        )
        DropdownMenuItem(
            text = { Text("Share logs") },
            onClick = {
                expanded = false
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val text = vm.buildLogShareText()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share logs"))
                    }
                }
            },
        )
        DropdownMenuItem(
            text = { Text("Show dismissed update") },
            onClick = {
                expanded = false
                vm.showDismissedUpdate()
            },
        )
        DropdownMenuItem(
            text = { Text("Redownload bundle") },
            onClick = {
                expanded = false
                vm.fetchAndDownloadBundle()
            },
        )
        DropdownMenuItem(
            text = { Text("About") },
            onClick = {
                expanded = false
                showAbout = true
            },
        )
    }

    if (showAbout) {
        val pm = context.packageManager
        val version = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0).versionName
            }
        } catch (_: Throwable) {
            "unknown"
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("CS16-Meta Patcher") },
            text = {
                Column {
                    Text("Version: $version", style = MaterialTheme.typography.bodySmall, color = Gray40)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Patches and mods CS16Client on Android: AMXX bundle injection, " +
                            "Pawn compiler, addons manager and crash logs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "github.com/berkchy/cs16client-crashview",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showAbout = false }) {
                    Text("Close")
                }
            },
            containerColor = Gray90,
            titleContentColor = White,
            textContentColor = White,
        )
    }
}

@Composable
private fun UpdateDialog(vm: PatcherViewModel, state: PatcherViewModel.AppUpdate) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            if (state !is PatcherViewModel.AppUpdate.Downloading) vm.dismissUpdate()
        },
        title = {
            Text(
                when (state) {
                    is PatcherViewModel.AppUpdate.Available -> "New update available ${state.tag}"
                    is PatcherViewModel.AppUpdate.Downloading -> "Downloading ${state.tag}…"
                    is PatcherViewModel.AppUpdate.Failed -> "Update failed"
                    else -> "Update"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (state is PatcherViewModel.AppUpdate.Available) {
                    if (state.notes.isNotBlank()) {
                        Text(
                            state.notes.trim().take(1200),
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray40,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        if (state.size > 0) "Size: ${formatBytes(state.size)}"
                        else "Size: varies with build",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                if (state is PatcherViewModel.AppUpdate.Downloading) {
                    val frac = if (state.total > 0) {
                        (state.downloaded.toDouble() / state.total).toFloat().coerceIn(0f, 1f)
                    } else 0f
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = frac,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${formatBytes(state.downloaded)} / ${formatBytes(state.total)}  ·  ${formatSpeed(state.bytesPerSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Install starts automatically when the download finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                if (state is PatcherViewModel.AppUpdate.Failed) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is PatcherViewModel.AppUpdate.Available -> {
                    androidx.compose.material3.TextButton(onClick = { vm.downloadAppUpdate() }) {
                        Text("Download")
                    }
                }
                is PatcherViewModel.AppUpdate.Failed -> {
                    androidx.compose.material3.TextButton(onClick = { vm.checkAppUpdate(silent = false) }) {
                        Text("Retry")
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is PatcherViewModel.AppUpdate.Downloading) {
                androidx.compose.material3.TextButton(onClick = { vm.dismissUpdate() }) {
                    Text("Later")
                }
            }
        },
        containerColor = Gray90,
        titleContentColor = White,
        textContentColor = White,
    )
}
