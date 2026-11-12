package ai.openbot.android.ui

import ai.openbot.android.R
import ai.openbot.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The original composer's two rows, 16px corners and 12px inner padding. */
@Composable internal fun SourceComposer(
    text: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onStop: (() -> Unit)? = null,
    stopping: Boolean = false,
    hasAttachments: Boolean = false,
    onAttach: (() -> Unit)? = null,
    attachments: (@Composable () -> Unit)? = null,
) {
    val p = LocalOpenBotPalette.current
    val currentText by rememberUpdatedState(text)
    val currentChange by rememberUpdatedState(onChange)
    var dictatedSend by remember { mutableStateOf<String?>(null) }
    val dictation =
        rememberDictation(enabled) { transcript, intent ->
            val next = appendDictation(currentText, transcript)
            currentChange(next)
            if (intent == DictationIntent.SEND)dictatedSend = next
        }
    val dictating = dictation.state.phase != DictationPhase.IDLE
    LaunchedEffect(text, dictatedSend, enabled) {
        if (dictatedSend != null && text == dictatedSend && enabled) {
            dictatedSend = null
            onSend()
        }
    }
    Surface(modifier.fillMaxWidth(), color = p.card, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, p.border)) {
        Column(Modifier.padding(13.dp)) {
            attachments?.invoke()
            Box {
                BasicTextField(
                    text,
                    onChange,
                    enabled = enabled && !dictating,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.foreground),
                    cursorBrush = SolidColor(p.foreground),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min =
                                    if (compact) {
                                        if (dictating)48.dp else 24.dp
                                    } else {
                                        80.dp
                                    },
                                max = 140.dp,
                            ).then(if (dictating)Modifier.alpha(0f).clearAndSetSemantics {} else Modifier)
                            .semantics { contentDescription = "Message" },
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text("Ask anything", color = p.mutedForeground)
                            inner()
                        }
                    },
                )
                if (dictating)Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { RecordingControls(dictation, enabled) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                onAttach?.let { attach -> IconButton(onClick = attach, enabled = enabled && !dictating, modifier = Modifier.size(28.dp)) { TablerIcon(R.drawable.ic_plus, "Attach files", Modifier.size(18.dp)) } }
                Spacer(Modifier.weight(1f))
                if (!dictating)IconButton(onClick = dictation.start, enabled = enabled, modifier = Modifier.size(28.dp)) { TablerIcon(R.drawable.ic_microphone, "Dictate a message", Modifier.size(16.dp)) }
                if (dictating) {
                    Spacer(Modifier.size(28.dp))
                } else if (onStop != null) {
                    IconButton(onClick = onStop, enabled = !stopping, modifier = Modifier.size(28.dp)) {
                        Box(Modifier.size(28.dp).background(p.foreground, CircleShape), contentAlignment = Alignment.Center) {
                            TablerIcon(R.drawable.ic_player_stop, if (stopping)"Stopping…" else "Stop generating", Modifier.size(16.dp), p.card)
                        }
                    }
                } else {
                    IconButton(onClick = onSend, enabled = enabled && (text.isNotBlank() || hasAttachments), modifier = Modifier.size(28.dp)) {
                        Box(Modifier.size(28.dp).background(p.foreground.copy(alpha = if (enabled && (text.isNotBlank() || hasAttachments))1f else .5f), CircleShape), contentAlignment = Alignment.Center) {
                            TablerIcon(R.drawable.ic_arrow_up, "Send message", Modifier.size(16.dp), p.card)
                        }
                    }
                }
            }
        }
    }
}
