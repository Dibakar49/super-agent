package ai.openbot.android.ui

import ai.openbot.android.AppState
import ai.openbot.android.OpenBotViewModel
import ai.openbot.android.R
import ai.openbot.core.*
import androidx.compose.foundation.border
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
import java.util.UUID

@Composable internal fun ApprovalRulesScreen(
    state: AppState,
    vm: OpenBotViewModel,
    botId: String = "*",
) {
    val p = LocalOpenBotPalette.current
    var adding by rememberSaveable { mutableStateOf(false) }
    val mine = state.approvalRules.filter { if (botId == "*")it.botId == "*" else it.botId != "*" && globMatches(it.botId, botId) }
    val orphans = if (botId == "*")state.approvalRules.filter { rule -> rule.botId != "*" && state.bots.none { globMatches(rule.botId, it.id) } } else emptyList()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("approval-rules-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Title(if (botId == "*")"Approvals" else "Rules")
                Spacer(Modifier.height(16.dp))
                Text(if (botId == "*")"Choose when your Bots ask before they change things. Rules here apply to all of them." else "The strictest matching rule wins. An ask rule beats an allow rule. Rules for every Bot are in Settings → Approvals.", color = p.mutedForeground)
            }
            if (botId == "*") {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Before a Bot acts", style = MaterialTheme.typography.titleSmall)
                    Text("Bots always ask for your approval before deleting, sending, sharing, or posting content in your apps or on websites. Bots leave payments and account security changes for you to do yourself.", color = p.mutedForeground)
                }
                item { PreferenceRow("Always ask before making changes", "Require your approval for every change to your apps, websites, and files, including routine edits and updates.", state.approvalPreferences.enabled, !state.busy) { vm.saveApprovalPreferences(state.approvalPreferences.copy(enabled = it)) } }
                item {
                    HorizontalDivider(color = p.border)
                    PreferenceRow("Auto-review", "Before a Bot does something that could affect your accounts or share information, OpenBot checks it against your request and your rules. If it can't tell, it asks you.", state.approvalPreferences.autoReview, !state.busy) { vm.saveApprovalPreferences(state.approvalPreferences.copy(autoReview = it)) }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Rules for every Bot", style = MaterialTheme.typography.titleSmall)
                    Text("The strictest matching rule wins. An ask rule beats an allow rule. Rules for one Bot are on that Bot's page.", color = p.mutedForeground)
                }
            }
            if (mine.isEmpty())item { SourceEmpty(if (botId == "*")"No rules yet" else "No rules for this Bot", modifier = Modifier.height(140.dp)) }
            items(mine, key = { it.id }) { rule -> ApprovalRuleRow(rule, state, vm) }
            item {
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { adding = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TablerIcon(R.drawable.ic_plus)
                    Column(Modifier.weight(1f)) {
                        Text("Add a rule", style = MaterialTheme.typography.titleSmall)
                        Text("A tool or app, and what a Bot does before using it.", color = p.mutedForeground)
                    }
                    TablerIcon(R.drawable.ic_chevron_right)
                }
            }
            if (orphans.isNotEmpty()) {
                item {
                    Text("Rules for other Bots", style = MaterialTheme.typography.titleSmall)
                    Text("These don't match a Bot you can open. They still apply to any Bot they match. Remove the ones you don't want.", color = p.mutedForeground)
                }
                items(orphans, key = { it.id }) { rule -> ApprovalRuleRow(rule, state, vm) }
            }
        }
        if (adding)NewRuleDialog(state, botId, { adding = false }) { rule -> vm.saveApprovalRule(rule) { adding = false } }
    }
}

@Composable private fun PreferenceRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, color = LocalOpenBotPalette.current.mutedForeground)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable private fun ApprovalRuleRow(
    rule: ApprovalRule,
    state: AppState,
    vm: OpenBotViewModel,
) {
    val connection = state.connections.find { rule.toolRef.startsWith("${it.id}/") }
    val title =
        if (rule.toolRef == "*") {
            "Any tool"
        } else if (connection != null) {
            "${connection.name} · ${rule.toolRef.substringAfter('/').replace('_',' ')}"
        } else {
            rule.toolRef
        }
    Column(Modifier.fillMaxWidth().testTag("rule-${rule.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (rule.effect != "*" || rule.scope != "*")Text(listOfNotNull(rule.effect.takeIf { it != "*" }, rule.scope.takeIf { it != "*" }?.let { scope -> "on ${state.connections.find{it.id == scope}?.name ?: scope}" }).joinToString(" · "), color = LocalOpenBotPalette.current.mutedForeground)
        BehaviourSelector(rule.behaviour, !state.busy, "Behaviour for $title") { vm.saveApprovalRule(rule.copy(behaviour = it)) }
        OpenBotOutlineButton(onClick = { vm.removeApprovalRule(rule.id) }, enabled = !state.busy) { Text("Remove") }
        HorizontalDivider(color = LocalOpenBotPalette.current.border)
    }
}

@Composable private fun BehaviourSelector(
    value: RuleBehaviour,
    enabled: Boolean,
    label: String,
    onChange: (RuleBehaviour) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, LocalOpenBotPalette.current.border, MaterialTheme.shapes.medium)
                .clickable(enabled = enabled) { open = true }
                .semantics { contentDescription = label }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TablerIcon(R.drawable.ic_chevron_down)
        }
        DropdownMenu(open, { open = false }) {
            RuleBehaviour.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                    onChange(choice)
                    open = false
                })
            }
        }
    }
}

@Composable private fun NewRuleDialog(
    state: AppState,
    botId: String,
    onClose: () -> Unit,
    onSave: (ApprovalRule) -> Unit,
) {
    var tool by rememberSaveable { mutableStateOf("") }
    var effect by rememberSaveable { mutableStateOf("*") }
    var scope by rememberSaveable { mutableStateOf("*") }
    var behaviour by rememberSaveable { mutableStateOf(RuleBehaviour.ASK) }
    var picking by remember { mutableStateOf(false) }
    SourceDialog("Add a rule", onClose) {
        Text("Use * to match anything. Choose a connected tool to fill its app and target, or enter a pattern.", color = LocalOpenBotPalette.current.mutedForeground)
        if (state.connections.isNotEmpty()) {
            Box {
                OpenBotTextButton(onClick = { picking = true }, enabled = !state.busy) { Text("Choose connected tool") }
                DropdownMenu(picking, { picking = false }) {
                    state.connections.forEach { connection ->
                        DropdownMenuItem(text = { Text("${connection.name} · Any action") }, onClick = {
                            tool = "${connection.id}/*"
                            scope = connection.id
                            picking = false
                        })
                        connection.tools.forEach { action ->
                            DropdownMenuItem(text = { Text("${connection.name} · ${action.name}") }, onClick = {
                                tool = "${connection.id}/${action.name}"
                                scope = connection.id
                                picking = false
                            })
                        }
                    }
                }
            }
        }
        SourceTextField(tool, { tool = it }, label = { Text("Tool or app") }, placeholder = { Text("app-id/*, or * for any tool") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        SourceTextField(effect, { effect = it }, label = { Text("Kind of action") }, placeholder = { Text("*, write, read, delegate") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        SourceTextField(scope, { scope = it }, label = { Text("Target") }, placeholder = { Text("* or a connected app") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Text("Behaviour", style = MaterialTheme.typography.bodyMedium)
        BehaviourSelector(behaviour, !state.busy, "Rule behaviour") { behaviour = it }
        Row {
            OpenBotTextButton(onClick = onClose, enabled = !state.busy) { Text("Cancel") }
            OpenBotButton(onClick = { onSave(ApprovalRule(UUID.randomUUID().toString(), botId, tool, effect, scope, behaviour)) }, enabled = !state.busy && tool.isNotBlank(), modifier = Modifier.testTag("save-rule")) { Text("Save rule") }
        }
    }
}
