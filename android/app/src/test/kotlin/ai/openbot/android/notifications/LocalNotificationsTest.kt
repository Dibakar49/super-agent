package ai.openbot.android.notifications

import android.Manifest
import android.app.*
import ai.openbot.android.data.*
import ai.openbot.core.*
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LocalNotificationsTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    @BeforeTest fun reset(){context.deleteDatabase("openbot.db");LocalNotifications.clear(context);shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)}
    @AfterTest fun cleanup(){LocalNotifications.clear(context);context.deleteDatabase("openbot.db")}
    private fun stage(store:LocalStore):LocalQuestion {
        val bot=store.bots().single();val channel=store.newConversation(bot.id);val now=Instant.now().toString()
        store.beginTurn(LocalMessage("u",channel.id,"user","Plan a trip",now),LocalMessage("a",channel.id,"assistant","Working…",now,MessageState.GENERATING))
        val call=ToolCall("q","ask_person",buildJsonObject{put("question","Which private project?")})
        val question=LocalQuestion("question", "a",channel.id,bot.id,call,"Which private project?",createdAt=now)
        QuestionStore(store).wait(AgentCheckpoint("a",channel.id,bot.id,ProviderSettings(),listOf(askPersonTool),JsonArray(emptyList()),AgentRound("",listOf(call),emptyList()),emptyList(),"",now,questionId=question.id),question)
        return question
    }
    @Test fun `notifications require opt in and permission and dismissed waiting items do not repeat`() {
        LocalStore(context).use{store->
            val question=stage(store)
            LocalNotifications.sync(context,store);assertTrue(manager.activeNotifications.isEmpty())
            store.savePreferences(store.preferences().copy(notificationsEnabled=true))
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            LocalNotifications.sync(context,store);assertTrue(manager.activeNotifications.isEmpty())
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            LocalNotifications.sync(context,store)
            val active=manager.activeNotifications.single()
            assertEquals("openbot",active.notification.channelId)
            assertEquals(Notification.VISIBILITY_PRIVATE,active.notification.visibility)
            assertFalse(active.notification.publicVersion.extras.toString().contains("private project"))
            val intent=shadowOf(active.notification.contentIntent).savedIntent
            assertEquals(NotificationDestination.Approvals(question.channelId,question.id),notificationDestination(intent.dataString))
            assertEquals("ai.openbot.android.MainActivity",intent.component!!.className)
            manager.cancel(active.tag,active.id)
            LocalNotifications.sync(context,store);assertTrue(manager.activeNotifications.isEmpty())
        }
    }
    @Test fun `answering cancels waiting notifications and reply opens the actual conversation`() {
        LocalStore(context).use{store->
            val question=stage(store);store.savePreferences(store.preferences().copy(notificationsEnabled=true))
            LocalNotifications.sync(context,store);assertEquals(1,manager.activeNotifications.size)
            QuestionStore(store).answer(question.id,"Project A")
            LocalNotifications.sync(context,store);assertTrue(manager.activeNotifications.isEmpty())
            val reply=store.messages(question.channelId).first{it.id==question.turnId}.copy(text="Finished.",state=MessageState.COMPLETE)
            store.saveMessage(reply)
            LocalNotifications.reply(context,store,reply,question.botId)
            val active=manager.activeNotifications.single()
            assertEquals(NotificationDestination.Conversation(question.channelId),notificationDestination(shadowOf(active.notification.contentIntent).savedIntent.dataString))
            store.deleteConversation(question.channelId);LocalNotifications.sync(context,store)
            assertTrue(manager.activeNotifications.isEmpty())
        }
    }
}
