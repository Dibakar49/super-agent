package ai.openbot.android.inference

import ai.openbot.core.*
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One private AAC/MP4 recording. Every exit releases the microphone; cancel also removes its bytes. */
class AndroidAudioRecording private constructor(
    context: Context,
    private val onError: (Throwable) -> Unit,
    private val onLimit: () -> Unit,
) : AudioRecording {
    private val directory = File(context.cacheDir, "dictation").apply { mkdirs() }
    private val output = File(directory, UUID.randomUUID().toString() + ".mp4")
    private val app = context.applicationContext

    @Volatile private var recorder: MediaRecorder? = null
    private var started = false

    @Volatile private var automaticallyStopped = false
    private val lock = Any()

    @Suppress("DEPRECATION")
    private fun start() =
        synchronized(lock) {
            val active = if (Build.VERSION.SDK_INT >= 31)MediaRecorder(app) else MediaRecorder()
            recorder = active
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioChannels(1)
            active.setAudioSamplingRate(16_000)
            active.setAudioEncodingBitRate(64_000)
            active.setMaxDuration(MAX_DICTATION_SECONDS * 1000)
            active.setMaxFileSize(MAX_DICTATION_BYTES.toLong())
            active.setOutputFile(output.absolutePath)
            active.setOnErrorListener { _, _, _ -> if (recorder != null)onError(IllegalStateException("The microphone stopped recording. Please try again.")) }
            active.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> {
                        automaticallyStopped = true
                        onLimit()
                    }
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> onError(IllegalStateException("Recording is too large. Try a shorter message."))
                }
            }
            active.prepare()
            active.start()
            started = true
        }

    override suspend fun finish(): DictationAudio =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val active = recorder ?: error("Recording stopped before audio could be collected.")
                try {
                    if (started && !automaticallyStopped)active.stop()
                } finally {
                    started = false
                    recorder = null
                    active.setOnErrorListener(null)
                    active.setOnInfoListener(null)
                    active.release()
                }
                DictationAudio(output)
            }
        }

    override fun level(): Float = synchronized(lock) { if (!started || automaticallyStopped)0f else runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f) }

    override fun cancel() =
        synchronized(lock) {
            recorder?.let { active ->
                recorder = null
                active.setOnErrorListener(null)
                active.setOnInfoListener(null)
                if (started && !automaticallyStopped)runCatching { active.stop() }
                runCatching { active.release() }
            }
            started = false
            output.delete()
            Unit
        }

    companion object {
        suspend fun create(
            context: Context,
            onError: (Throwable) -> Unit,
            onLimit: () -> Unit,
        ): AndroidAudioRecording {
            val recording = AndroidAudioRecording(context, onError, onLimit)
            try {
                return withContext(Dispatchers.IO) {
                    recording.start()
                    recording
                }
            } catch (failure: Throwable) {
                recording.cancel()
                throw failure
            }
        }

        fun cleanPreviousProcess(context: Context) {
            File(context.cacheDir, "dictation").listFiles().orEmpty().forEach { it.delete() }
        }
    }
}
