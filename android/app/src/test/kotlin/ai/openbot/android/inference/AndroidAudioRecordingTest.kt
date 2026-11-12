package ai.openbot.android.inference

import ai.openbot.core.*
import android.media.MediaRecorder
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaRecorder
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAudioRecordingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @AfterTest fun cleanup() {
        File(context.cacheDir, "dictation").deleteRecursively()
    }

    @Test fun `recorder uses bounded mono AAC and cancellation releases the microphone and private file`() =
        runBlocking<Unit> {
            val recording = AndroidAudioRecording.create(context, { error("Unexpected recorder error") }, {})
            val native = ReflectionHelpers.getField<MediaRecorder>(recording, "recorder")
            val shadow = shadowOf(native)
            try {
                assertEquals(MediaRecorder.AudioSource.MIC, shadow.audioSource)
                assertEquals(MediaRecorder.OutputFormat.MPEG_4, shadow.outputFormat)
                assertEquals(MediaRecorder.AudioEncoder.AAC, shadow.audioEncoder)
                assertEquals(1, shadow.audioChannels)
                assertEquals(16_000, shadow.audioSamplingRate)
                assertEquals(MAX_DICTATION_SECONDS * 1000, shadow.maxDuration)
                assertEquals(MAX_DICTATION_BYTES.toLong(), shadow.maxFileSize)
                assertEquals(ShadowMediaRecorder.STATE_RECORDING, shadow.state)
                val file = File(shadow.outputPath)
                file.writeText("fixture encoded audio")
                assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath + "/dictation/"))
                recording.cancel()
                assertFalse(file.exists())
                assertEquals(ShadowMediaRecorder.STATE_RELEASED, shadow.state)
            } finally {
                recording.cancel()
            }
        }

    @Test fun `the hardware duration event finishes once and the recorder releases before transcription`() =
        runBlocking<Unit> {
            var limits = 0
            val recording = AndroidAudioRecording.create(context, { error("Unexpected recorder error") }, { limits++ })
            val native = ReflectionHelpers.getField<MediaRecorder>(recording, "recorder")
            val shadow = shadowOf(native)
            try {
                File(shadow.outputPath).writeText("fixture encoded audio")
                shadow.infoListener.onInfo(native, MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0)
                assertEquals(1, limits)
                val audio = recording.finish()
                assertEquals("audio/mp4", audio.mimeType)
                assertTrue(audio.file.isFile)
                assertEquals(ShadowMediaRecorder.STATE_RELEASED, shadow.state)
                assertNull(shadow.errorListener)
                assertNull(shadow.infoListener)
                audio.file.delete()
            } finally {
                recording.cancel()
            }
        }
}
