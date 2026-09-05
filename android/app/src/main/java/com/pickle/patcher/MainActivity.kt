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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.screens.AddonsScreen
import com.pickle.patcher.ui.screens.CompilerScreen
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
                        "Size: ${formatBytes(state.size)}",
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
