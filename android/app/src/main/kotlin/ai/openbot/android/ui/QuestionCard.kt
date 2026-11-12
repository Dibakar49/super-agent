package ai.openbot.android.ui

import ai.openbot.android.*
import ai.openbot.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Original questionnaire: one decision at a time, with choices and an unrestricted text answer. */
@Composable internal fun QuestionCard(
    question: LocalQuestion,
    state: AppState,
    vm: OpenBotViewModel,
) {
    val p = LocalOpenBotPalette.current
    var index by rememberSaveable(question.id) { mutableIntStateOf(0) }
    var chosen by rememberSaveable(question.id) { mutableStateOf(List(question.parts.size) { "" }) }
    var typed by rememberSaveable(question.id) { mutableStateOf(List(maxOf(1, question.parts.size)) { "" }) }
    Surface(Modifier.fillMaxWidth().testTag("question-${question.id}"), shape = MaterialTheme.shapes.medium, color = p.card, border = BorderStroke(1.dp, p.border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${state.bots.find{it.id == question.botId}?.name ?: "Bot"} has a question", style = MaterialTheme.typography.bodySmall, color = p.mutedForeground)
            Text(question.question, style = MaterialTheme.typography.titleSmall)
            question.why?.takeIf { it.isNotBlank() }?.let { Text(it, color = p.mutedForeground) }
            if (question.parts.isNotEmpty()) {
                val part = question.parts[index]
                Text("${part.header}: ${part.question}", style = MaterialTheme.typography.bodyMedium)
                part.options.forEachIndexed { optionIndex, option ->
                    Surface(
                        Modifier.fillMaxWidth().clickable(enabled = !state.busy) { chosen = chosen.toMutableList().apply { set(index, if (chosen[index] == option)"" else option) } },
                        shape = MaterialTheme.shapes.small,
                        color = if (chosen[index] == option)p.muted else p.card,
                        border = BorderStroke(1.dp, if (chosen[index] == option)p.foreground.copy(alpha = .4f) else p.border),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioButton(selected = chosen[index] == option, onClick = null, modifier = Modifier.size(16.dp))
                            Text(option, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("${optionIndex + 1}", color = p.mutedForeground, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                SourceTextField(
                    typed[index],
                    { value -> if (value.length <= 6000)typed = typed.toMutableList().apply { set(index, value) } },
                    label = { Text(if (part.options.isEmpty())"Your answer" else "Or answer in your own words") },
                    modifier = Modifier.fillMaxWidth().testTag("question-answer"),
                    enabled = !state.busy,
                )
            } else {
                SourceTextField(typed[0], { value -> if (value.length <= 6000)typed = listOf(value) }, label = { Text("Your answer") }, modifier = Modifier.fillMaxWidth().testTag("question-answer"), enabled = !state.busy)
            }
            val valid = typed[index].isNotBlank() || chosen.getOrNull(index)?.isNotBlank() == true
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (index > 0)OpenBotTextButton(onClick = { index-- }, enabled = !state.busy) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (question.parts.size > 1)Text("${index + 1} of ${question.parts.size}", color = p.mutedForeground, style = MaterialTheme.typography.bodySmall)
                if (index < question.parts.lastIndex) {
                    OpenBotButton(onClick = { index++ }, enabled = valid && !state.busy) { Text("Next") }
                } else {
                    OpenBotButton(onClick = {
                        val answer = if (question.parts.isEmpty())typed[0] else partsAnswer(question.parts, chosen, typed)
                        vm.answerQuestion(question.id, answer)
                    }, enabled = valid && !state.busy) { Text(if (question.parts.size > 1)"Send answers" else "Send answer") }
                }
            }
        }
    }
}
