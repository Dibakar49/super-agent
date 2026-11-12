package ai.openbot.android.inference

import ai.openbot.core.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*
import java.util.Locale

/** Strictly the Android on-device recognizer. Never silently sends speech to a cloud service. */
class OnDeviceSpeechRecording private constructor(
    private val recognizer: SpeechRecognizer,
    private val onError: (Throwable) -> Unit,
    private val onLimit: () -> Unit,
) : AudioRecording,
    RecognitionListener {
    private val result = CompletableDeferred<String>()

    @Volatile private var disposed = false

    @Volatile private var amplitude = 0f
    private var stopping = false

    override suspend fun finish(): DictationText =
        withContext(Dispatchers.Main.immediate) {
            try {
                check(!disposed) { "Speech recognition was cancelled." }
                if (!stopping && !result.isCompleted) {
                    stopping = true
                    recognizer.stopListening()
                }
                DictationText(withTimeout(15_000) { result.await() })
            } finally {
                this@OnDeviceSpeechRecording.cancel()
            }
        }

    override fun level(): Float = amplitude

    override fun cancel() {
        if (disposed) return
        disposed = true
        result.cancel()
        val release =
            Runnable {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
        if (Looper.myLooper() == Looper.getMainLooper()) release.run() else Handler(Looper.getMainLooper()).post(release)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) {
        if (!disposed) amplitude = (rmsdB / 10f).coerceIn(0f, 1f)
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        if (!disposed) onLimit()
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(
        eventType: Int,
        params: Bundle?,
    ) = Unit

    override fun onResults(results: Bundle?) {
        if (disposed) return
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        if (text.isNullOrBlank()) {
            onError(SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        result.complete(text)
        onLimit()
    }

    override fun onError(error: Int) {
        if (disposed) return
        val message =
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access was denied. Allow it in Android settings and try again."
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected. Try recording again."
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "On-device speech for this language is unavailable. Download a speech language in Android settings, or configure a transcription provider."
                else -> "On-device speech stopped. Try again, or configure a transcription provider in Model settings."
            }
        result.completeExceptionally(IllegalStateException(message))
        onError(IllegalStateException(message))
        cancel()
    }

    companion object {
        fun requireAvailable(context: Context) {
            if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                throw DictationUnavailableException("On-device speech is unavailable on this phone. Configure a transcription provider in Model settings to use dictation.")
            }
        }

        suspend fun create(
            context: Context,
            onError: (Throwable) -> Unit,
            onLimit: () -> Unit,
        ): OnDeviceSpeechRecording =
            withContext(Dispatchers.Main.immediate) {
                requireAvailable(context)
                if (Build.VERSION.SDK_INT < 31) throw DictationUnavailableException("On-device speech requires Android 12 or later.")
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
                val recording = OnDeviceSpeechRecording(recognizer, onError, onLimit)
                try {
                    recognizer.setRecognitionListener(recording)
                    recognizer.startListening(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        },
                    )
                    recording
                } catch (failure: Throwable) {
                    recording.cancel()
                    throw failure
                }
            }
    }
}
