package ai.openbot.agentstudio

import ai.openbot.hostfixture.HostActivity
import ai.openbot.agentstudio.AS_LocalStore.LocalStore
import ai.openbot.agentstudio.AS_Models.LocalMessage
import ai.openbot.agentstudio.AS_Models.Memory
import ai.openbot.agentstudio.AS_Models.FormedBy
import ai.openbot.agentstudio.AS_Models.ReviewState
import ai.openbot.agentstudio.AS_ModelProvider.ProviderSettings
import ai.openbot.agentstudio.AS_ModelSettingsStore.ModelSettingsStore
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.*

class UnrelatedHostWorker(context:android.content.Context,params:androidx.work.WorkerParameters):androidx.work.Worker(context,params) {
    override fun doWork()=Result.success()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentGalleryJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<HostActivity>()
    private fun screenshot(name:String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val view=compose.activity.window.decorView.rootView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output=File(System.getProperty("user.home"),"screenshots/agentstudio-$name.png").apply{requireNotNull(parentFile).mkdirs()}
            output.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    private fun choose(option:String) {
        compose.onNodeWithTag("agent-options").performClick()
        compose.onNodeWithText(option,substring=false).performScrollTo().performClick()
    }
    private fun create(name:String,instruction:String,color:String) {
        compose.onNodeWithTag("create-agent").performClick()
        compose.onNodeWithTag("agent-name").performTextReplacement(name)
        compose.onNodeWithTag("mascot-color-$color").performClick()
        if(name=="Researcher")screenshot("identity")
        compose.onNodeWithTag("agent-next").performClick()
        compose.onNodeWithTag("agent-instructions").performTextReplacement(instruction)
        if(name=="Researcher")screenshot("instructions")
        compose.onNodeWithTag("save-agent").performClick()
        compose.waitUntil(20_000){compose.onAllNodesWithTag("agent-options").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(20_000){runCatching{compose.onNodeWithContentDescription("Message").assertIsEnabled()}.isSuccess}
    }
    @Test fun `empty gallery create two agents private chat and settings host data protection and removal`() {
        compose.waitUntil(20_000){compose.onAllNodesWithTag("make-an-agent").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("make-an-agent").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show sidebar").assertDoesNotExist()
        screenshot("empty")
        create("Researcher","Research evidence, cite sources, and explain uncertainties.","ff168aff")
        val root=compose.activity.application
        val researcher=AgentStudioProfiles.list(root).single()
        val research=AgentStudioProfiles.context(root,researcher.id)
        assertEquals("Researcher",researcher.name)
        assertEquals("Research evidence, cite sources, and explain uncertainties.",researcher.instruction)
        val now=Instant.now().toString()
        val channel=LocalStore(research).use{store->
            assertEquals(researcher.id,store.bots().single().id)
            assertEquals(researcher.instruction,store.bots().single().instruction)
            val conversation=store.newConversation(researcher.id)
            store.saveConversation(conversation.copy(title="Research chat"))
            store.saveMessage(LocalMessage("test-u",conversation.id,"user","Help me compare these sources.",now))
            store.saveMessage(LocalMessage("test-a",conversation.id,"assistant","I can compare their evidence and identify uncertainties.",now))
            store.saveMemory(Memory("private-memory","Research-only note","You",true,ReviewState.CONFIRMED,FormedBy.PERSON,now))
            conversation.id
        }
        ModelSettingsStore(research).save(ProviderSettings(model="research-model"),null,false)
        choose("Chat history")
        compose.onNodeWithText("Research chat",substring=false).performClick()
        compose.waitUntil(20_000){compose.onAllNodesWithText("I can compare their evidence",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Show sidebar").assertDoesNotExist()
        compose.onNodeWithText("Ask Researcher",substring=false).assertIsDisplayed()
        screenshot("chat")
        compose.onNodeWithTag("agent-back").performClick()
        compose.waitUntil(20_000){compose.onAllNodesWithTag("create-agent").fetchSemanticsNodes().isNotEmpty()}
        create("Planner","Plan my tasks, ask before changes, and track deadlines.","ff00c779")
        val planner=AgentStudioProfiles.list(root).first{it.name=="Planner"}
        val planning=AgentStudioProfiles.context(root,planner.id)
        LocalStore(planning).use{store->
            assertEquals(planner.id,store.bots().single().id)
            assertEquals(planner.instruction,store.bots().single().instruction)
            assertTrue(store.conversations().isEmpty())
            assertTrue(store.memories().isEmpty())
        }
        assertNotEquals(ModelSettingsStore(research).settings().model,ModelSettingsStore(planning).settings().model)
        assertNotEquals(research.getDatabasePath("agentstudio.db"),planning.getDatabasePath("agentstudio.db"))
        assertNotSame(AgentStudioProfiles.gate(research),AgentStudioProfiles.gate(planning))
        val request=androidx.work.OneTimeWorkRequestBuilder<UnrelatedHostWorker>().setInitialDelay(1,TimeUnit.DAYS).addTag("unrelated-host-job").build()
        val manager=androidx.work.WorkManager.getInstance(root)
        manager.enqueue(request).result.get(5,TimeUnit.SECONDS)
        val hostFile=File(root.filesDir,"attachments/host.txt").apply{requireNotNull(parentFile).mkdirs();writeText("Host data")}
        AgentStudioProfiles.preview(root,planner.id,"Private planning context",now)
        choose("Settings")
        compose.onNodeWithTag("clear-local-data").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-clear-local-data").performClick()
        compose.waitUntil(20_000){compose.onAllNodesWithTag("confirm-clear-local-data").fetchSemanticsNodes().isEmpty()}
        assertEquals("",requireNotNull(AgentStudioProfiles.get(root,planner.id)).preview)
        assertEquals("Host data",hostFile.readText())
        assertNotEquals(androidx.work.WorkInfo.State.CANCELLED,requireNotNull(manager.getWorkInfoById(request.id).get(5,TimeUnit.SECONDS)).state)
        LocalStore(research).use{store->assertEquals(2,store.messages(channel).size);assertEquals("Research-only note",store.memories().single().content)}
        LocalStore(planning).use{assertEquals(planner.id,it.bots().single().id);assertEquals(planner.instruction,it.bots().single().instruction)}
        compose.onNodeWithTag("agent-back").performClick() // Settings -> chat.
        choose("Delete agent")
        // A slow/cancelling worker must not keep a screen observing a cleared ViewModel.
        val deleteGate=AgentStudioProfiles.gate(planning)
        kotlinx.coroutines.runBlocking { deleteGate.lock() }
        try {
            compose.onNodeWithTag("delete-agent").performClick()
            compose.waitUntil(20_000){compose.onAllNodesWithTag("create-agent").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("agent-"+planner.id).assertDoesNotExist()
        } finally { deleteGate.unlock() }
        compose.waitUntil(20_000){AgentStudioProfiles.list(root).none{it.id==planner.id}}
        compose.waitUntil(20_000){compose.onAllNodesWithTag("create-agent").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(listOf(researcher.id),AgentStudioProfiles.list(root).map{it.id})
        screenshot("gallery")
        assertEquals("Host data",hostFile.readText())
    }
}
