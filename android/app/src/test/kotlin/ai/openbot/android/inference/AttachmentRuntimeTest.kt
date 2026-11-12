package ai.openbot.android.inference

import ai.openbot.android.data.*
import ai.openbot.core.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AttachmentRuntimeTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val configuration=object:ModelConfiguration {
        override fun settings()=ProviderSettings()
        override fun hasKey()=true
        override fun apiKey()="fixture-attachment-key"
    }
    @BeforeTest fun reset(){context.deleteDatabase("openbot.db");File(context.filesDir,"attachments").deleteRecursively()}
    @AfterTest fun cleanup(){context.deleteDatabase("openbot.db");File(context.filesDir,"attachments").deleteRecursively()}
    @Test fun `attachment only send commits files and a waiting turn reopens without storing base64 history`()=runBlocking<Unit> {
        var store=LocalStore(context);var model=LocalModel(context)
        try {
            val channel=store.newConversation(store.bots().single().id)
            val files=AttachmentStore(context,store)
            val document=files.stage("draft","notes.txt","text/plain",ByteArrayInputStream("Private planning notes".toByteArray()))
            val image=files.stage("draft","photo.png","image/png",ByteArrayInputStream(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47)))
            var requests=0
            val provider=ProviderTransport{_,_,request->
                requests++
                val attached=AttachmentStore(context,store).conversation(channel.id)
                assertEquals(2,attached.size);assertTrue(attached.all{it.messageId!=null&&it.stageKey==null})
                val source=AttachmentWireSource(context,store);source.authorize(channel.id,attached)
                val bytes=ByteArrayOutputStream();AttachmentRequestBody(request,source).writeTo(bytes)
                val wire=bytes.toString("UTF-8")
                assertContains(wire,"Private planning notes");assertContains(wire,"data:image/png;base64,iVBORw==")
                if(requests==1)Json.parseToJsonElement("""{"status":"completed","output":[{"type":"function_call","id":"fc1","call_id":"question-call","name":"ask_person","arguments":"{\"question\":\"Which day?\"}"}]}""").jsonObject
                else Json.parseToJsonElement("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Plan ready."}]}]}""").jsonObject
            }
            val runtime=AgentRuntime(context,store,model,configuration,provider)
            assertEquals(TurnOutcome.WAITING,runtime.turn(channel.id,"",attachmentIds=listOf(document.id,image.id),attachmentStageKey="draft"))
            val question=QuestionStore(store).waiting().single()
            assertEquals(listOf(document.id,image.id),store.messages(channel.id).first().attachmentIds)
            val history=store.checkpoints().single().history.toString()
            assertContains(history,"openbot-attachment:");assertFalse(history.contains("Private planning notes"));assertFalse(history.contains("iVBORw=="))
            store.close();model.close();store=LocalStore(context);model=LocalModel(context)
            val checkpoint=QuestionStore(store).answer(question.id,"Saturday")
            assertEquals(TurnOutcome.COMPLETED,AgentRuntime(context,store,model,configuration,provider).resume(checkpoint.turnId))
            assertEquals(2,requests);assertEquals("Plan ready.",store.messages(channel.id).first{it.id==checkpoint.turnId}.text)
        }finally{model.close();store.close()}
    }
    @Test fun `a missing current attachment fails before a user message or model request is saved`()=runBlocking<Unit> {
        LocalStore(context).use{store->
            val files=AttachmentStore(context,store);val channel=store.newConversation(store.bots().single().id)
            val file=files.stage("draft","notes.txt","text/plain",ByteArrayInputStream("A note".toByteArray()))
            File(context.filesDir,"attachments/${file.id}").delete()
            val model=LocalModel(context)
            try {
                val runtime=AgentRuntime(context,store,model,configuration,ProviderTransport{_,_,_->error("No model request may be sent")})
                assertFails{runtime.turn(channel.id,"Read it",attachmentIds=listOf(file.id),attachmentStageKey="draft")}
                assertTrue(store.messages(channel.id).isEmpty());assertTrue(store.checkpoints().isEmpty())
                assertEquals(file,files.staged("draft").single())
            }finally{model.close()}
        }
    }
}
