package com.pickle.patcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickle.patcher.lib.ApkPatcher
import com.pickle.patcher.patcher.AddonsState
import com.pickle.patcher.patcher.BundleState
import com.pickle.patcher.patcher.PatchUiState
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray60
import com.pickle.patcher.ui.theme.Gray70
import com.pickle.patcher.ui.theme.Gray80
import com.pickle.patcher.ui.theme.Gray90
import com.pickle.patcher.ui.theme.SuccessGreen
import com.pickle.patcher.ui.theme.White
import java.text.DecimalFormat

private val mbFmt = DecimalFormat("0.0")
private fun Long.mb(): String = "${mbFmt.format(this / 1048576.0)} MB"

@Composable
fun PatchScreen(vm: PatcherViewModel) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "CS16 Patcher",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Select an APK, load a mod bundle, then patch and install.",
            style = MaterialTheme.typography.bodyMedium,
            color = Gray40,
        )

        Spacer(Modifier.height(20.dp))

        SectionHeader("SOURCE APK")
        SourceCard(vm)

        Spacer(Modifier.height(16.dp))

        SectionHeader("MOD BUNDLE")
        BundleCard(vm)

        Spacer(Modifier.height(16.dp))

        SectionHeader("BUILD")
        PatchCard(vm)

        Spacer(Modifier.height(16.dp))

        SectionHeader("ADDONS")
        AddonsQuickCard(vm)

        Spacer(Modifier.height(16.dp))
    }
}

private fun displayAbi(abi: String): String = when (abi) {
    "armeabi-v7a" -> "Arm32"
    else -> "Arm64"
}

@Composable
private fun SourceCard(vm: PatcherViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            vm.pickSource(it)
        }
    }
    val source by vm.source.collectAsState()

    AppCard {
        if (source == null) {
            Text(
                "No APK selected.",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray40,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "Select APK",
                onClick = { picker.launch(arrayOf("application/vnd.android.package-archive")) },
                icon = { Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp)) },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Accent.copy(alpha = 0.12f),
                ) {
                    Icon(
                        Icons.Filled.InstallDesktop,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                        tint = Accent,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        source!!.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${source!!.sizeBytes.mb()}  ·  ${source!!.entryCount} entries",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                GhostButton("Change", onClick = { picker.launch(arrayOf("application/vnd.android.package-archive")) })
            }
        }
    }
}

@Composable
private fun BundleCard(vm: PatcherViewModel) {
    val bundleState by vm.bundle.collectAsState()
    val abi by vm.abi.collectAsState()
    val sourceAbis = vm.sourceAbis

    AppCard {
        // ABI selector — integrated into bundle card
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ABI",
                style = MaterialTheme.typography.titleSmall,
                color = Gray40,
            )
            Spacer(Modifier.width(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                vm.supportedAbis.forEachIndexed { index, a ->
                    SingleChoiceSegmentedButton(
                        selected = a == abi,
                        onClick = { vm.setAbi(a) },
                        enabled = sourceAbis.isEmpty() || a in sourceAbis,
                        shape = SegmentedButtonDefaults.itemShape(index, vm.supportedAbis.size),
                    ) {
                        Text(displayAbi(a))
                    }
                }
            }
        }
        if (sourceAbis.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Source: ${sourceAbis.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = Gray60,
            )
        }

        Spacer(Modifier.height(12.dp))

        when (val bs = bundleState) {
            is BundleState.None -> {
                Text(
                    "A mod bundle is required.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Gray40,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "Download",
                        onClick = { vm.fetchAndDownloadBundle() },
                        icon = { Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Embedded",
                        onClick = { vm.useEmbeddedBundle() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            is BundleState.Downloading -> {
                if (bs.tagName.isNotBlank()) {
                    Text(
                        bs.tagName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Accent,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text("Downloading…", style = MaterialTheme.typography.bodySmall, color = Gray40)
                Spacer(Modifier.height(8.dp))
                AppProgressBar(bs.percent)
            }
            is BundleState.DownloadError -> {
                Text(bs.message, style = MaterialTheme.typography.bodySmall, color = AlertRed)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Retry", onClick = { vm.fetchAndDownloadBundle() })
                    SecondaryButton("Embedded", onClick = { vm.useEmbeddedBundle() })
                }
            }
            is BundleState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle, null,
                        tint = SuccessGreen, modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(bs.bundleName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${bs.entries} entries  ·  v${bs.version}",
                            style = MaterialTheme.typography.bodySmall, color = Gray40,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchCard(vm: PatcherViewModel) {
    val state by vm.patch.collectAsState()
    val bundle = vm.bundle.collectAsState().value
    val context = LocalContext.current
    val canPatch = vm.source.collectAsState().value != null && bundle is BundleState.Ready

    AppCard {
        when (val s = state) {
            is PatchUiState.Idle -> {
                PrimaryButton(
                    text = "Patch & Sign APK",
                    onClick = { vm.startPatch() },
                    enabled = canPatch,
                    icon = { Icon(Icons.Filled.RocketLaunch, null, modifier = Modifier.size(18.dp)) },
                )
                if (!canPatch) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Select an APK and load a bundle to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
            }
            is PatchUiState.Running -> {
                PatchSteps(s.step, s.progress)
            }
            is PatchUiState.Done -> {
                PatchResult(s.report, onInstall = { context.startActivity(vm.installIntent()) })
            }
            is PatchUiState.Failed -> {
                Icon(Icons.Filled.Warning, null, tint = AlertRed, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(4.dp))
                Text("Patch failed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = Gray40)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Try Again", onClick = { vm.reset() })
            }
        }
    }
}

@Composable
private fun PatchSteps(step: ApkPatcher.Step, progress: Float) {
    val steps = listOf(
        ApkPatcher.Step.ANALYZE to "Analyze",
        ApkPatcher.Step.INJECT to "Inject",
        ApkPatcher.Step.ALIGN to "Align",
        ApkPatcher.Step.SIGN to "Sign",
        ApkPatcher.Step.VERIFY to "Verify",
    )
    val currentIndex = steps.indexOfFirst { it.first == step }

    Column {
        steps.forEachIndexed { i, (_, label) ->
            val st = when {
                i < currentIndex -> StepState.DONE
                i == currentIndex -> StepState.ACTIVE
                else -> StepState.PENDING
            }
            StepRow(
                title = label,
                detail = if (st == StepState.ACTIVE) "${(progress * 100).toInt()}%" else "",
                state = st,
            )
            if (i < steps.lastIndex) Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        AppProgressBar(progress)
    }
}

@Composable
private fun PatchResult(report: ApkPatcher.PatchReport, onInstall: () -> Unit) {
    AnimatedContent(
        targetState = true,
        transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(200))) },
        label = "result",
    ) {
        Column {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SuccessGreen.copy(alpha = 0.08f),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Patch Complete", style = MaterialTheme.typography.titleMedium, color = SuccessGreen)
                    Spacer(Modifier.height(4.dp))
                    val v = report.verification
                    Text(
                        "Output: ${report.signedSizeBytes.mb()}  ·  source ${report.sourceName}\n" +
                            "Added: ${report.addedEntries.size}  ·  kept: ${report.keptCount}  ·  aligned: ${report.alignedStored}\n" +
                            if (report.patchedLibs.isNotEmpty()) "LibCs: PostThink active-item guard applied\n" else "" +
                            "Signature: ${if (v != null && v.verified) "OK (v1=${v.usedV1} v2=${v.usedV2})" else "NOT VERIFIED"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "Install",
                onClick = onInstall,
                icon = { Icon(Icons.Filled.InstallDesktop, null, modifier = Modifier.size(18.dp)) },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Replaces the existing AMXX install. Signed with the debug key.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
        }
    }
}

@Composable
private fun AddonsQuickCard(vm: PatcherViewModel) {
    val addons by vm.addons.collectAsState()
    val addonFiles by vm.addonFiles.collectAsState()
    val missing = addonFiles.count { !it.installed }
    val total = addonFiles.size

    AppCard {
        if (total == 0) {
            Text(
                "No addons scanned. Load a bundle first.",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray40,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = "Scan Addons",
                onClick = { vm.scanAddonsStatus() },
                icon = { Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp)) },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (missing > 0) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (missing > 0) AlertRed else SuccessGreen,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (missing > 0) "$missing addon files missing" else "All $total addon files installed",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (missing > 0) AlertRed else SuccessGreen,
                    )
                    Text(
                        vm.installPath.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (missing > 0) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "Install Missing",
                        onClick = { vm.installAddonsFromBundle() },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Details",
                        onClick = { vm.scanAddonsStatus() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        when (val s = addons) {
            is AddonsState.Done -> {
                Spacer(Modifier.height(8.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = SuccessGreen)
            }
            is AddonsState.Error -> {
                Spacer(Modifier.height(8.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = AlertRed)
            }
            else -> {}
        }
    }
}
