package com.pickle.patcher.ui.screens

import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickle.patcher.patcher.AddonsState
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray70
import com.pickle.patcher.ui.theme.Gray80
import com.pickle.patcher.ui.theme.Gray90
import com.pickle.patcher.ui.theme.SuccessGreen
import com.pickle.patcher.ui.theme.White

@Composable
fun AddonsScreen(vm: PatcherViewModel) {
    val context = LocalContext.current
    val addons by vm.addons.collectAsState()
    val addonFiles by vm.addonFiles.collectAsState()
    val installPath by vm.installPath.collectAsState()

    val missing = addonFiles.count { !it.installed }
    val outdated = addonFiles.count { it.installed && it.outdated }
    val total = addonFiles.size
    val installed = addonFiles.count { it.installed && !it.outdated }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Addons Manager",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Install and manage AMXX addons into your game directory.",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray40,
            )
        }

        item {
            SectionHeader("INSTALL PATH")
            AppCard {
                Text(
                    installPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "addons/ will be installed under this path.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray40,
                )
            }
        }

        item {
            SectionHeader("STORAGE PERMISSION")
            PermissionCard()
        }

        item {
            SectionHeader("ADDON STATUS")
            AppCard {
                if (total == 0) {
                    Text(
                        "Addons ship separately from the mod bundle.\nTap \"Download & Install\" below to fetch and install them into your game directory.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Gray40,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatPill("Total", "$total")
                        StatPill("Installed", "$installed", accent = SuccessGreen)
                        StatPill("Missing", "$missing", accent = if (missing > 0) AlertRed else SuccessGreen)
                        StatPill("Outdated", "$outdated", accent = if (outdated > 0) AlertRed else SuccessGreen)
                    }
                }
            }
        }

        if (total > 0 && (missing > 0 || outdated > 0)) {
            item {
                SectionHeader("MISSING / OUTDATED FILES")
                AppCard {
                    addonFiles.filter { !it.installed || it.outdated }.forEach { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Error,
                                contentDescription = null,
                                tint = AlertRed,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    file.path.removePrefix("addons/"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Gray40,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (!file.installed) "missing" else "outdated — will be replaced",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AlertRed,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("ACTIONS")
            AppCard {
                when (val s = addons) {
                    is AddonsState.Downloading -> {
                        Text(s.step, style = MaterialTheme.typography.bodyMedium, color = Accent)
                        Spacer(Modifier.height(8.dp))
                        AppProgressBar(s.percent)
                    }
                    is AddonsState.Done -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle, null,
                                tint = SuccessGreen, modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(s.message, style = MaterialTheme.typography.bodyMedium, color = SuccessGreen)
                        }
                    }
                    is AddonsState.Error -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning, null,
                                tint = AlertRed, modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(s.message, style = MaterialTheme.typography.bodySmall, color = AlertRed)
                        }
                    }
                    else -> {}
                }

                Spacer(Modifier.height(10.dp))

                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        vm.fetchAndInstallAddons()
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "Download & Install Addons",
                        onClick = {
                            val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Environment.isExternalStorageManager()
                            } else true
                            if (hasPerm) {
                                vm.fetchAndInstallAddons()
                            } else {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                                permLauncher.launch(intent)
                            }
                        },
                        icon = {
                            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (addonFiles.isNotEmpty()) {
            item {
                SectionHeader("ALL FILES (${addonFiles.size})")
            }
            items(addonFiles) { file ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Gray90,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (file.installed && !file.outdated) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            contentDescription = null,
                            tint = if (file.installed && !file.outdated) SuccessGreen else AlertRed,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                file.path.removePrefix("addons/"),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (file.installed && !file.outdated) White else Gray40,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (file.installed && file.outdated) {
                                Text(
                                    "outdated",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AlertRed,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard() {
    val context = LocalContext.current
    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (hasPermission) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (hasPermission) SuccessGreen else AlertRed,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (hasPermission) "All files access granted" else "All files access required",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hasPermission) SuccessGreen else AlertRed,
                )
                Text(
                    "Needed to install addons into the game directory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray40,
                )
            }
        }
        if (!hasPermission) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = "Grant Permission",
                onClick = {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    launcher.launch(intent)
                },
                icon = { Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}
