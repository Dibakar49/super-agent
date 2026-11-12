package ai.openbot.core

import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DictationSessionTest {
    private fun audio(text:String="audio")=DictationAudio(File.createTempFile("openbot-dictation-test-",".mp4").apply{writeText(text)})
    private class Recording(val audio:DictationAudio):AudioRecording {
        var cancelled=false;var finishes=0
        override suspend fun finish():DictationAudio{finishes++;return audio}
        override fun cancel(){cancelled=true;audio.file.delete()}
    }
    @Test fun `media types and latest draft append match original TypeScript`() {
        val data=Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/dictation-typescript-parity.json")).bufferedReader().use{it.readText()}).jsonObject
        for(row in data["formats"]!!.jsonArray) {
            val item=row.jsonObject;val type=item["type"]!!.jsonPrimitive.content
            assertEquals(item["normalized"]!!.jsonPrimitive.content,dictationMediaType(type))
            assertEquals(item["extension"]!!.takeUnless{it==JsonNull}?.jsonPrimitive?.content,dictationExtension(type))
        }
        for(row in data["drafts"]!!.jsonArray) {
            val item=row.jsonObject
            assertEquals(item["expected"]!!.jsonPrimitive.content,appendDictation(item["draft"]!!.jsonPrimitive.content,item["transcript"]!!.jsonPrimitive.content))
        }
    }
    @Test fun `retry keeps one recording and the requested send intent while using the latest draft`()=runBlocking<Unit> {
        val recorded=Recording(audio());var attempts=0;var draft="First";var intent:DictationIntent?=null
        val session=DictationSession(this,{_,_->recorded},{if(attempts++==0)error("Try later.");" spoken words "},{text,target->draft=appendDictation(draft,text);intent=target})
        try {
            session.start();session.finish(DictationIntent.SEND)
            assertEquals(DictationPhase.ERROR,session.state.value.phase);assertTrue(session.state.value.canRetry)
            assertTrue(recorded.audio.file.exists());draft="Edited draft"
            session.retry()
            assertEquals("Edited draft spoken words",draft);assertEquals(DictationIntent.SEND,intent)
            assertEquals(2,attempts);assertEquals(1,recorded.finishes);assertFalse(recorded.audio.file.exists())
            assertEquals(DictationPhase.IDLE,session.state.value.phase)
        }finally{session.cancel()}
    }
    @Test fun `a recorder arriving after cancellation is released without transcribing`()=runBlocking<Unit> {
        val arrival=CompletableDeferred<AudioRecording>();val recorded=Recording(audio());var transcripts=0
        val session=DictationSession(this,{_,_->arrival.await()},{error("No transcription should run")},{_,_->transcripts++})
        try {
            val start=launch{session.start()};yield();assertEquals(DictationPhase.REQUESTING,session.state.value.phase)
            session.cancel();arrival.complete(recorded);start.join()
            assertTrue(recorded.cancelled);assertFalse(recorded.audio.file.exists());assertEquals(0,transcripts)
            assertEquals(DictationPhase.IDLE,session.state.value.phase)
        }finally{session.cancel()}
    }
    @Test fun `cancel aborts the active transcription and ignores a late result`()=runBlocking<Unit> {
        val recorded=Recording(audio());val entered=CompletableDeferred<Unit>();var aborted=false;var transcripts=0
        val session=DictationSession(this,{_,_->recorded},{entered.complete(Unit);try{awaitCancellation()}finally{aborted=true}},{_,_->transcripts++})
        try {
            session.start();val finish=launch{session.finish()};entered.await()
            session.cancel();finish.join()
            assertTrue(aborted);assertEquals(0,transcripts);assertFalse(recorded.audio.file.exists())
            assertEquals(DictationPhase.IDLE,session.state.value.phase)
        }finally{session.cancel()}
    }
    @Test fun `invalid finished audio is deleted and cannot offer a broken retry`()=runBlocking<Unit> {
        val recorded=Recording(audio(""))
        val session=DictationSession(this,{_,_->recorded},{error("An empty file must not be uploaded")},{_,_->error("No transcript expected")})
        try {
            session.start();session.finish()
            assertEquals(DictationPhase.ERROR,session.state.value.phase);assertFalse(session.state.value.canRetry)
            assertFalse(recorded.audio.file.exists())
        }finally{session.cancel()}
    }
    @Test fun `the two minute recording limit finishes into the draft once`()=runBlocking<Unit> {
        val recorded=Recording(audio());var now=0L;val delivered=CompletableDeferred<DictationIntent>()
        val session=DictationSession(this,{_,_->recorded},{"Words"},{_,intent->delivered.complete(intent)},{now})
        try {
            session.start();now=120_000
            assertEquals(DictationIntent.DRAFT,withTimeout(2000){delivered.await()})
            assertEquals(1,recorded.finishes);assertFalse(recorded.audio.file.exists())
        }finally{session.cancel()}
    }
}
