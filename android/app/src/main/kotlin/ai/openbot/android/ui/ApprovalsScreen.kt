package ai.openbot.android.ui

import ai.openbot.android.*
import ai.openbot.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

@Composable internal fun ApprovalsScreen(
    state: AppState,
    vm: OpenBotViewModel,
    channelId: String? = null,
    requestId: String? = null,
) {
    val pending = state.pendingActions.filter { it.status == LocalActionStatus.PENDING && (channelId == null || it.channelId == channelId) }
    val uncertain = state.pendingActions.filter { it.status == LocalActionStatus.UNCERTAIN && (channelId == null || it.channelId == channelId) }
    val questions = state.questions.filter { channelId == null || it.channelId == channelId }
    val scroll = rememberLazyListState()
    LaunchedEffect(requestId, questions.map { it.id }, pending.map { it.id }) {
        val index = (questions.map { it.id } + pending.map { it.id } + uncertain.map { it.id }).indexOf(requestId)
        if (index >= 0)scroll.scrollToItem(index + 1)
    }
    LazyColumn(Modifier.fillMaxSize().testTag("approvals-list"), state = scroll, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Title("Questions and approvals") }
        if (pending.isEmpty() && uncertain.isEmpty() && questions.isEmpty())item { SourceEmpty("Nothing waiting for you", "When a Bot needs your answer or approval, it appears here.", Modifier.height(200.dp)) }
        items(questions, key = { "question-${it.id}" }) { question -> QuestionCard(question, state, vm) }
        items(pending, key = { it.id }) { action -> ApprovalCard(action, state, vm) }
        items(uncertain, key = { it.id }) { action -> UnknownActionCard(action, state, vm) }
    }
}

@Composable internal fun ApprovalCard(
    action: PendingLocalAction,
    state: AppState,
    vm: OpenBotViewModel,
) {
    val p = LocalOpenBotPalette.current
    val bot = state.bots.find { it.id == action.botId }
    val connection = state.connections.find { it.id == action.connectionId }
    Surface(Modifier.fillMaxWidth().testTag("approval-${action.id}"), shape = MaterialTheme.shapes.medium, color = p.card, border = androidx.compose.foundation.BorderStroke(1.dp, p.border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Waiting for your approval", style = MaterialTheme.typography.bodySmall, color = p.mutedForeground)
            Text("${bot?.name ?: "Bot"} asks to use ${action.tool.name}", style = MaterialTheme.typography.titleSmall)
            Text(connection?.name ?: action.connectionUrl, color = p.mutedForeground)
            if (action.tool.description.isNotBlank())Text(action.tool.description, color = p.mutedForeground)
            SelectionContainer {
                Text(
                    prettyJson.encodeToString(
                        kotlinx.serialization.json.JsonObject
                            .serializer(),
                        action.call.arguments,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            action.policy?.reason?.let { Text(it, color = p.mutedForeground, style = MaterialTheme.typography.bodySmall) }
            if (action.policy?.behaviour == PolicyBehaviour.HAND_OFF) {
                OpenBotButton(onClick = { vm.decideApproval(action.id, LocalActionStatus.HANDLED) }, enabled = !state.busy) { Text("I did it myself") }
                Text("You've done it, so the Bot carries on from there.", style = MaterialTheme.typography.bodySmall, color = p.mutedForeground)
            } else {
                OpenBotButton(onClick = { vm.decideApproval(action.id, LocalActionStatus.APPROVED) }, enabled = !state.busy) { Text("Allow once") }
                Text("The Bot does it this time. You're asked again next time.", style = MaterialTheme.typography.bodySmall, color = p.mutedForeground)
                if (offersStandingRule(action.policy?.source) && safetyRequirement(pendingCandidate(action)) == null) {
                    OpenBotOutlineButton(onClick = { vm.decideApproval(action.id, LocalActionStatus.APPROVED, always = true) }, enabled = !state.busy) { Text("Always allow here") }
                    Text("The Bot does it now, and here without asking from now on.", style = MaterialTheme.typography.bodySmall, color = p.mutedForeground)
                }
            }
            OpenBotTextButton(onClick = { vm.decideApproval(action.id, LocalActionStatus.DENIED) }, enabled = !state.busy) { Text("Deny", color = p.destructive) }
        }
    }
}

@Composable internal fun UnknownActionCard(
    action: PendingLocalAction,
    state: AppState,
    vm: OpenBotViewModel,
) {
    val p = LocalOpenBotPalette.current
    Surface(Modifier.fillMaxWidth().testTag("unknown-${action.id}"), shape = MaterialTheme.shapes.medium, color = p.card, border = androidx.compose.foundation.BorderStroke(1.dp, p.border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Action outcome unknown", style = MaterialTheme.typography.titleSmall)
            Text("${state.connections.find{it.id == action.connectionId}?.name ?: action.connectionUrl} · ${action.tool.name}", color = p.mutedForeground)
            Text(outcomeForPerson(OUTCOME_UNKNOWN), color = p.mutedForeground)
            SelectionContainer { Text(action.call.arguments.toString(), style = MaterialTheme.typography.bodyMedium) }
            Text("Check the connected app, then record what happened. This does not run the action again.", style = MaterialTheme.typography.bodySmall, color = p.mutedForeground)
            OpenBotOutlineButton(onClick = { vm.resolveUnknownAction(action.id, true) }, enabled = !state.busy) { Text("I checked: it completed") }
            OpenBotOutlineButton(onClick = { vm.resolveUnknownAction(action.id, false) }, enabled = !state.busy) { Text("I checked: it didn't happen") }
        }
    }
}
