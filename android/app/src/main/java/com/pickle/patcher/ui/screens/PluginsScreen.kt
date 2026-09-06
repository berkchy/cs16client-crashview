package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray90
import com.pickle.patcher.ui.theme.SuccessGreen
import com.pickle.patcher.ui.theme.White

/** " ; zombie_plague40.amxx debug " -> "zombie_plague40" (no ';', no args, no .amxx). */
private fun displayPluginName(raw: String): String {
    var s = raw.trim()
    if (s.startsWith(";")) s = s.substring(1).trim()
    val first = s.split(Regex("\\s+")).firstOrNull().orEmpty()
    return first.removeSuffix(".amxx").ifBlank { "(blank)" }
}

@Composable
fun PluginsScreen(vm: PatcherViewModel) {    val inis by vm.pluginInis.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.loadPluginInis() }
    LaunchedEffect(inis) {
        if (selected == null && inis.isNotEmpty()) selected = inis.first().name
        if (selected != null && inis.none { it.name == selected }) selected = inis.firstOrNull()?.name
    }

    val current = inis.firstOrNull { it.name == selected }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Plugins", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Toggle plugins on/off (';' prefix). Saved instantly.",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray40,
            )
        }

        if (inis.size > 1) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(inis) { ini ->
                        AssistChip(
                            onClick = { selected = ini.name },
                            label = { Text(ini.name) },
                        )
                    }
                }
            }
        }

        if (current == null) {
            item {
                AppCard {
                    Text(
                        "No plugins-*.ini found. Set the game folder on the Addons tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Gray40,
                    )
                }
            }
        } else {
            items(current.lines.indices.toList()) { idx ->
                val line = current.lines[idx]
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = Gray90,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                displayPluginName(line.text),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!line.editable) Gray40 else White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (line.editable && !line.enabled) {
                                Text(
                                    "disabled",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Gray40,
                                )
                            }
                        }
                        if (line.editable && current != null) {
                            Switch(
                                checked = line.enabled,
                                onCheckedChange = { vm.togglePluginLine(current.name, idx) },
                            )
                        }
                    }
                }
            }
            item {
                val on = current.lines.count { it.editable && it.enabled }
                val off = current.lines.count { it.editable && !it.enabled }
                Text(
                    "$on enabled · $off disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = SuccessGreen,
                )
            }
        }
    }
}
