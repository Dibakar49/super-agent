package ai.openbot.android.ui

import ai.openbot.android.*
import ai.openbot.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun RoutinesScreen(
    state: AppState,
    vm: OpenBotViewModel,
    botId: String? = null,
) {
    val p = LocalOpenBotPalette.current
    var history by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<LocalRoutine?>(null) }
    val rows = state.routines.filter { botId == null || it.botId == botId }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("routines-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Title("Routines")
                Spacer(Modifier.height(16.dp))
                Text("Ask a Bot to create or change a scheduled routine. You can review and stop routines here.", color = p.mutedForeground)
            }
            item { Text("Android checks routines in the background about every 15 minutes. Battery settings and network availability can delay a run.", style = MaterialTheme.typography.bodySmall, color = p.mutedForeground) }
            if (rows.isEmpty())item { SourceEmpty(if (botId == null)"No routines yet" else "No routines for this Bot yet", if (botId == null)"Ask a Bot something like \"every weekday at 9, send me…\" and it shows up here." else "Ask it something like \"every weekday at 9, send me…\" and it shows up here.", Modifier.height(200.dp)) }
            items(rows, key = { it.id }) { routine ->
                val runs = state.routineRuns.filter { it.routineId == routine.id }.sortedByDescending { it.startedAt }
                val channel = state.conversations.find { it.id == routine.channelId }
                val schedule = describeCron(routine.cron)
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("routine-${routine.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(routine.title ?: schedule, style = MaterialTheme.typography.titleSmall)
                            Text("${if (routine.title == null)"" else "$schedule · "}${routine.timezone}", style = MaterialTheme.typography.bodySmall, color = p.mutedForeground)
                            Text(routine.instruction, color = p.mutedForeground)
                        }
                        Switch(routine.enabled, { vm.setRoutineEnabled(routine, it) }, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = "Enable the routine scheduled $schedule" })
                    }
                    if (channel == null) {
                        Text("Conversation deleted", color = p.destructive)
                    } else {
                        Text(conversationTitle(channel), Modifier.clickable { vm.openConversation(channel.id) }, style = MaterialTheme.typography.bodySmall, color = p.mutedForeground)
                    }
                    Text(runs.firstOrNull()?.let { if (it.status == RoutineStatus.RUNNING)"Running…" else "${runLabels[it.status]} · ${localTime(it.finishedAt ?: it.startedAt)}" } ?: "Hasn't run yet", style = MaterialTheme.typography.bodySmall, color = if (runs.firstOrNull()?.status == RoutineStatus.FAILED)p.destructive else p.mutedForeground)
                    Text(
                        if (!routine.enabled) {
                            "Paused"
                        } else if (Instant.parse(routine.nextRunAt) <= Instant.now()) {
                            "Due"
                        } else {
                            "Next ${localTime(routine.nextRunAt)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = p.mutedForeground,
                    )
                    Row {
                        OpenBotOutlineButton(onClick = {
                            history = routine.id
                            vm.runRoutineNow(routine)
                        }, enabled = routine.enabled && !state.busy && channel != null) { Text("Run now") }
                        OpenBotTextButton(onClick = { history = if (history == routine.id)null else routine.id }) { Text("History") }
                        OpenBotTextButton(onClick = { deleting = routine }, enabled = !state.busy) { Text("Delete") }
                    }
                    if (history == routine.id) {
                        HorizontalDivider(color = p.border)
                        if (runs.isEmpty())Text("No runs yet.", style = MaterialTheme.typography.bodySmall, color = p.mutedForeground)
                        runs.forEach { run ->
                            Text("${runLabels[run.status]} · ${if (run.source == "run_now")"Run now" else "Scheduled"} · ${localTime(run.startedAt)}", style = MaterialTheme.typography.bodySmall)
                            run.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.mutedForeground) }
                        }
                    }
                }
                HorizontalDivider(color = p.border)
            }
        }
        deleting?.let { routine ->
            SourceDialog("Delete \"${describeCron(routine.cron)}\"?", { deleting = null }) {
                Text("This routine stops for good. You can't undo this.")
                Row {
                    OpenBotTextButton(onClick = { deleting = null }) { Text("Cancel") }
                    OpenBotButton(onClick = { vm.deleteRoutine(routine) { deleting = null } }, enabled = !state.busy, modifier = Modifier.testTag("confirm-delete-routine")) { Text("Delete") }
                }
            }
        }
    }
}

private fun localTime(value: String): String = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
