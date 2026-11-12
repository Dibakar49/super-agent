package ai.openbot.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

const val MAX_DICTATION_BYTES = 10 * 1024 * 1024
const val MAX_DICTATION_SECONDS = 120
const val DICTATION_TIMEOUT_MS = 60_000L

fun dictationMediaType(type: String): String =
    when (val value = type.substringBefore(';').trim().lowercase()) {
        "video/webm" -> "audio/webm"
        "video/mp4" -> "audio/mp4"
        "audio/x-wav" -> "audio/wav"
        else -> value
    }

fun dictationExtension(type: String): String? =
    when (dictationMediaType(type)) {
        "audio/webm" -> "webm"
        "audio/mp4" -> "mp4"
        "audio/wav" -> "wav"
        else -> null
    }

private fun dictationWhitespace(c: Char) = c in '\u0009'..'\u000d' || c in '\u2000'..'\u200a' || c in " \u00a0\u1680\u2028\u2029\u202f\u205f\u3000\ufeff"

fun appendDictation(
    draft: String,
    transcript: String,
): String {
    val spoken = transcript.trim(::dictationWhitespace)
    if (spoken.isEmpty())return draft
    return if (draft.isEmpty() || dictationWhitespace(draft.last()))draft + spoken else "$draft $spoken"
}

enum class DictationPhase { IDLE, REQUESTING, RECORDING, TRANSCRIBING, ERROR }

enum class DictationIntent { DRAFT, SEND }

data class DictationState(
    val phase: DictationPhase = DictationPhase.IDLE,
    val seconds: Int = 0,
    val error: String? = null,
    val canRetry: Boolean = false,
    val level: Float = 0f,
)

sealed interface DictationInput

data class DictationAudio(
    val file: File,
    val mimeType: String = "audio/mp4",
) : DictationInput

data class DictationText(
    val text: String,
) : DictationInput

class DictationUnavailableException(
    message: String,
) : IllegalStateException(message)

interface AudioRecording {
    suspend fun finish(): DictationInput

    fun cancel()

    fun level(): Float = 0f
}

/** Native equivalent of the original generation fence, retryable recording and finish intent. */
class DictationSession(
    private val scope: CoroutineScope,
    private val record: suspend ((Throwable) -> Unit, () -> Unit) -> AudioRecording,
    private val transcribe: suspend (DictationAudio) -> String,
    private val onTranscript: (String, DictationIntent) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutable = MutableStateFlow(DictationState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var recording: AudioRecording? = null
    private var audio: DictationAudio? = null
    private var request: Job? = null
    private var timer: Job? = null
    private var intent = DictationIntent.DRAFT

    fun cancel() {
        generation++
        timer?.cancel()
        timer = null
        request?.cancel()
        request = null
        recording?.cancel()
        recording = null
        audio?.file?.delete()
        audio = null
        intent = DictationIntent.DRAFT
        mutable.value = DictationState()
    }

    suspend fun start() {
        cancel()
        val current = generation
        mutable.value = DictationState(phase = DictationPhase.REQUESTING)
        try {
            val created =
                record({ failure ->
                    if (current == generation) {
                        cancel()
                        mutable.value = DictationState(phase = DictationPhase.ERROR, error = failure.message ?: "The microphone stopped recording. Please try again.")
                    }
                }, { if (current == generation)scope.launch { finish() } })
            if (current != generation) {
                created.cancel()
                return
            }
            recording = created
            mutable.value = DictationState(phase = DictationPhase.RECORDING)
            val started = clock()
            timer =
                scope.launch {
                    while (isActive) {
                        delay(50)
                        if (current != generation)break
                        val seconds = ((clock() - started) / 1000).toInt().coerceAtLeast(0)
                        mutable.value = mutable.value.copy(seconds = seconds, level = created.level().coerceIn(0f, 1f))
                        if (seconds >= MAX_DICTATION_SECONDS) {
                            finish()
                            break
                        }
                    }
                }
        } catch (failure: CancellationException) {
            if (current == generation)cancel()
            throw failure
        } catch (failure: Exception) {
            if (current == generation)mutable.value = DictationState(phase = DictationPhase.ERROR, error = if (failure is DictationUnavailableException)failure.message else "Could not start recording. Check your microphone and try again.")
        }
    }

    suspend fun finish(target: DictationIntent = DictationIntent.DRAFT) {
        if (mutable.value.phase != DictationPhase.RECORDING)return
        val active = recording ?: return
        val current = generation
        intent = target
        // A finish called by the timer must not cancel its own coroutine before collecting audio.
        val tick = timer
        timer = null
        if (tick != currentCoroutineContext()[Job])tick?.cancel()
        mutable.value = mutable.value.copy(phase = DictationPhase.TRANSCRIBING)
        try {
            val result = active.finish()
            if (current != generation) {
                (result as? DictationAudio)?.file?.delete()
                return
            }
            recording = null
            when (result) {
                is DictationAudio -> {
                    audio = result
                    require(result.file.length() in 1..MAX_DICTATION_BYTES.toLong() && dictationExtension(result.mimeType) != null) { "Invalid recording." }
                    runTranscription(current)
                }
                is DictationText -> {
                    val text = result.text.trim(::dictationWhitespace)
                    require(text.isNotEmpty() && text.length <= 6000) { "No usable speech was detected." }
                    mutable.value = DictationState()
                    onTranscript(text, intent)
                }
            }
        } catch (failure: CancellationException) {
            if (current == generation)cancel()
            throw failure
        } catch (_: Exception) {
            if (current != generation)return
            recording?.cancel()
            recording = null
            audio?.file?.delete()
            audio = null
            mutable.value = DictationState(phase = DictationPhase.ERROR, error = "Could not finish recording. Please record again.")
        }
    }

    suspend fun retry() {
        if (mutable.value.phase != DictationPhase.ERROR || audio == null)return
        runTranscription(generation)
    }

    private suspend fun runTranscription(current: Long): Unit =
        supervisorScope {
            val recordingAudio = audio ?: return@supervisorScope
            val operation = async { withTimeout(DICTATION_TIMEOUT_MS) { transcribe(recordingAudio) } }
            request = operation
            mutable.value = mutable.value.copy(phase = DictationPhase.TRANSCRIBING, error = null, canRetry = false)
            try {
                val text = operation.await().trim(::dictationWhitespace)
                if (current != generation)return@supervisorScope
                if (text.isEmpty())throw IllegalStateException("No speech was detected. Try recording again.")
                recordingAudio.file.delete()
                audio = null
                val requested = intent
                mutable.value = DictationState()
                onTranscript(text, requested)
            } catch (failure: TimeoutCancellationException) {
                if (current == generation)mutable.value = mutable.value.copy(phase = DictationPhase.ERROR, canRetry = true, error = "Transcription timed out or was cancelled. Try again.")
            } catch (failure: CancellationException) {
                if (current == generation) {
                    cancel()
                    throw failure
                }
            } catch (failure: Exception) {
                if (current == generation)mutable.value = mutable.value.copy(phase = DictationPhase.ERROR, canRetry = true, error = failure.message ?: "Transcription failed. Please retry.")
            } finally {
                if (request === operation)request = null
            }
        }
}
