package ai.openbot.android.ui

import ai.openbot.android.R
import ai.openbot.android.data.TranscriptionSettingsStore
import ai.openbot.android.inference.*
import ai.openbot.core.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.util.Locale

internal data class DictationUi(
    val session: DictationSession,
    val state: DictationState,
    val start: () -> Unit,
    val cancel: () -> Unit,
)

@Composable internal fun rememberDictation(
    enabled: Boolean,
    onTranscript: (String, DictationIntent) -> Unit,
): DictationUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val delivered by rememberUpdatedState(onTranscript)
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> pending[0]?.complete(granted) }
    val session =
        remember(context) {
            val settings = TranscriptionSettingsStore(context)
            val provider = TranscriptionProvider(settings)
            DictationSession(scope, record = { onError, onLimit ->
                val useProvider = settings.configured()
                if (!useProvider)OnDeviceSpeechRecording.requireAvailable(context)
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    val answer = CompletableDeferred<Boolean>()
                    pending[0] = answer
                    try {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                        if (!answer.await()) {
                            onError(IllegalStateException("Microphone access was denied. Allow it in Android settings and try again."))
                            throw CancellationException("Microphone permission denied.")
                        }
                    } finally {
                        if (pending[0] === answer)pending[0] = null
                    }
                }
                if (useProvider) {
                    AndroidAudioRecording.create(context, onError, onLimit)
                } else {
                    OnDeviceSpeechRecording.create(context, onError, onLimit)
                }
            }, transcribe = provider::transcribe, onTranscript = { text, intent -> delivered(text, intent) })
        }
    val state by session.state.collectAsState()
    val cancel =
        remember(session) {
            {
                session.cancel()
                pending[0]?.cancel()
                pending[0] = null
                Unit
            }
        }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP)cancel() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            cancel()
        }
    }
    LaunchedEffect(enabled) { if (!enabled)cancel() }
    return DictationUi(session, state, {
        scope.launch { session.start() }
        Unit
    }, cancel)
}

@Composable internal fun RecordingControls(
    dictation: DictationUi,
    canSend: Boolean,
) {
    val p = LocalOpenBotPalette.current
    val scope = rememberCoroutineScope()
    val state = dictation.state
    var levels by remember { mutableStateOf(List(24) { 0f }) }
    LaunchedEffect(state.level, state.phase) {
        if (state.phase in listOf(DictationPhase.REQUESTING, DictationPhase.IDLE)) {
            levels = List(24) { 0f }
        } else if (state.phase == DictationPhase.RECORDING) {
            levels = (levels.drop(1) + state.level)
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = dictation.cancel, modifier = Modifier.size(28.dp)) { TablerIcon(R.drawable.ic_x, "Cancel dictation", Modifier.size(16.dp)) }
        Box(Modifier.weight(1f)) {
            when (state.phase) {
                DictationPhase.ERROR -> Text(state.error.orEmpty(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = p.destructive, style = MaterialTheme.typography.bodyMedium)
                DictationPhase.REQUESTING -> Text("Waiting for microphone permission…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = p.mutedForeground)
                else ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val description = if (state.phase == DictationPhase.TRANSCRIBING)"Transcribing…" else "Listening"
                        Canvas(
                            Modifier.weight(1f).height(28.dp).semantics {
                                contentDescription = description
                                liveRegion = LiveRegionMode.Polite
                            },
                        ) {
                            val width = size.width / 24
                            levels.forEachIndexed { index, level ->
                                val height = maxOf(2.dp.toPx(), level * size.height)
                                drawRoundRect(p.foreground, Offset(index * width, (size.height - height) / 2), Size(maxOf(1f, width - 2.dp.toPx()), height), CornerRadius(1.dp.toPx()))
                            }
                        }
                        Text(String.format(Locale.ROOT, "%d:%02d", state.seconds / 60, state.seconds % 60), Modifier.semantics { contentDescription = "Recording duration" }, style = MaterialTheme.typography.bodySmall, color = p.mutedForeground)
                    }
            }
        }
        if (state.phase == DictationPhase.ERROR) {
            OpenBotTextButton(onClick = { if (state.canRetry)scope.launch { dictation.session.retry() } else dictation.start() }) { Text(if (state.canRetry)"Retry" else "Record again") }
        } else {
            IconButton(onClick = { scope.launch { dictation.session.finish() } }, enabled = state.phase == DictationPhase.RECORDING, modifier = Modifier.size(28.dp)) { TablerIcon(R.drawable.ic_player_stop, "Stop and transcribe", Modifier.size(16.dp)) }
            IconButton(onClick = { scope.launch { dictation.session.finish(DictationIntent.SEND) } }, enabled = state.phase == DictationPhase.RECORDING && canSend, modifier = Modifier.size(28.dp)) {
                if (state.phase == DictationPhase.TRANSCRIBING) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    TablerIcon(R.drawable.ic_arrow_up, "Transcribe and send", Modifier.size(16.dp))
                }
            }
        }
    }
}
