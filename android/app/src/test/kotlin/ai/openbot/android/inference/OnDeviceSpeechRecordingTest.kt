package ai.openbot.android.inference

import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ai.openbot.core.DictationUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnDeviceSpeechRecordingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @BeforeTest fun setup() { ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true) }
    @AfterTest fun cleanup() { ShadowSpeechRecognizer.reset() }

    @Test fun `local recognition requests offline speech and releases after final text`() = runBlocking<Unit> {
        var stopped = 0
        val recording = OnDeviceSpeechRecording.create(context, { fail("Unexpected speech error") }, { stopped++ })
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        shadowOf(Looper.getMainLooper()).idle()
        val intent = shadow.lastRecognizerIntent
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        shadow.triggerOnResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Local words")) })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stopped)
        assertEquals("Local words", recording.finish().text)
        assertTrue(shadow.isDestroyed)
    }

    @Test fun `cancel destroys the recognizer and discards late text`() = runBlocking<Unit> {
        var results = 0
        val recording = OnDeviceSpeechRecording.create(context, {}, { results++ })
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        shadowOf(Looper.getMainLooper()).idle()
        recording.cancel()
        shadow.triggerOnResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Too late")) })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, results)
        assertTrue(shadow.isDestroyed)
    }

    @Test fun `unavailable local service fails clearly without creating a cloud recognizer`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val failure = assertFailsWith<DictationUnavailableException> { OnDeviceSpeechRecording.requireAvailable(context) }
        assertContains(failure.message.orEmpty(), "Configure a transcription provider")
    }
}
