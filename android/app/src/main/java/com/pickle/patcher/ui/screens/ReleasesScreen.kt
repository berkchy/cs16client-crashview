package com.pickle.patcher.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pickle.patcher.patcher.AddonsState
import com.pickle.patcher.patcher.BundleState
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray70
import com.pickle.patcher.ui.theme.Gray80
import com.pickle.patcher.ui.theme.Gray90
import com.pickle.patcher.ui.theme.SuccessGreen
import com.pickle.patcher.ui.theme.White
import java.io.File

@Composable
fun ReleasesScreen(vm: PatcherViewModel) {
    val scroll = rememberScrollState()
    val note by vm.releaseNote.collectAsState()
    val bundle by vm.bundle.collectAsState()
    val addons by vm.addons.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Downloads", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            "Install addons or manage the mod bundle.",
            style = MaterialTheme.typography.bodyMedium,
            color = Gray40,
        )

        Spacer(Modifier.height(20.dp))

        SectionHeader("ADDONS")
        AppCard {
            Text(
                "Plugins, modules, and configs for cstrike/.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
            Spacer(Modifier.height(10.dp))

            when (val a = addons) {
                is AddonsState.Downloading -> {
                    AppProgressBar(a.percent)
                    Spacer(Modifier.height(4.dp))
                    Text(a.step, style = MaterialTheme.typography.bodySmall, color = Gray40)
                }
                is AddonsState.Done -> StatusRow(SuccessGreen, a.message)
                is AddonsState.Error -> StatusRow(AlertRed, a.message)
                is AddonsState.None -> StatusRow(Gray40, "Not downloaded yet.")
            }

            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "Download & Install",
                onClick = { vm.fetchAndInstallAddons() },
                icon = { Icon(Icons.Filled.CloudDownload, null, modifier = Modifier.size(18.dp)) },
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader("MOD BUNDLE")
        AppCard {
            note?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudDownload, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                }
                Spacer(Modifier.height(8.dp))
            }

            when (val b = bundle) {
                is BundleState.Downloading -> {
                    Text("Downloading…", style = MaterialTheme.typography.bodySmall, color = Gray40)
                }
                is BundleState.DownloadError -> StatusRow(AlertRed, b.message)
                is BundleState.Ready -> StatusRow(SuccessGreen, "${b.bundleName}  ·  v${b.version}")
                is BundleState.None -> StatusRow(Gray40, "Not downloaded yet.")
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = "Download",
                    onClick = { vm.fetchAndDownloadBundle() },
                    icon = { Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Embedded",
                    onClick = { vm.useEmbeddedBundle() },
                    modifier = Modifier.weight(1f),
                )
            }

            if (vm.hasCachedBundle) {
                Spacer(Modifier.height(6.dp))
                SecondaryButton(
                    text = "Load cached",
                    onClick = { vm.useCachedBundle() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader("APP UPDATE")
        AppCard {
            val pm = context.packageManager
            val current = try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0).versionName
                }
            } catch (_: Throwable) {
                null
            }
            Text(
                "Current: ${current ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Check GitHub releases for a newer patcher APK. If one is found, " +
                    "a popup offers download with progress and automatic install.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "Check for updates",
                onClick = { vm.checkAppUpdate(silent = false) },
                icon = { Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp)) },
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader("GAME FOLDERS")
        GameFoldersCard()
    }
}

@Composable
private fun StatusRow(tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (tint == SuccessGreen) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
private fun GameFoldersCard() {
    val folders = listOf(
        "addons/amxmodx/bin/" to "Core binaries",
        "addons/amxmodx/modules/" to "Module .so files",
        "addons/amxmodx/plugins/" to "Compiled plugins",
        "addons/amxmodx/configs/" to "Configuration files",
        "addons/amxmodx/includes/" to "Pawn headers",
    )

    AppCard {
        folders.forEach { (path, desc) ->
            val dir = File("/storage/emulated/0/xash/cstrike/$path")
            val exists = dir.exists()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.FolderSpecial, null,
                    tint = if (exists) SuccessGreen else Gray70,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(path, style = MaterialTheme.typography.labelSmall, color = White)
                    Text(desc, style = MaterialTheme.typography.labelSmall, color = Gray40)
                }
            }
        }
    }
}
