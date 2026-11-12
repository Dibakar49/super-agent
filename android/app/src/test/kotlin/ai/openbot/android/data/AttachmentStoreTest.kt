package ai.openbot.android.data

import ai.openbot.core.*
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AttachmentStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val directory get()=File(context.filesDir,"attachments")
    @BeforeTest fun reset(){context.deleteDatabase("openbot.db");directory.deleteRecursively()}
    @AfterTest fun cleanup(){context.deleteDatabase("openbot.db");directory.deleteRecursively()}
    private fun bytes(text:String)=ByteArrayInputStream(text.toByteArray())
    @Test fun `files stage privately and bind atomically to the message that sent them`() {
        LocalStore(context).use{store->
            val attachments=AttachmentStore(context,store)
            val file=attachments.stage("draft:one","../../notes.txt","text/plain",bytes("A private note"))
            assertEquals("../../notes.txt",file.name)
            assertTrue(File(directory,file.id).isFile)
            assertFails{attachments.open(file.id,"another-channel")}
            assertFails{attachments.validateSend(listOf(file.id),"draft:other")}
            val channel=store.newConversation(store.bots().single().id);val now=Instant.now().toString()
            val user=LocalMessage("u",channel.id,"user","Read this",now)
            val pending=LocalMessage("a",channel.id,"assistant","Working…",now,MessageState.GENERATING)
            val db=store.writableDatabase;db.beginTransaction()
            try {
                store.beginTurn(user,pending)
                attachments.bind(listOf(file.id),"draft:one",channel.id,user.id)
                // Simulate the caller aborting the entire accepted turn.
            }finally{db.endTransaction()}
            assertTrue(store.messages(channel.id).isEmpty());assertEquals(listOf(file),attachments.staged("draft:one"))
            db.beginTransaction()
            try{store.beginTurn(user,pending);attachments.bind(listOf(file.id),"draft:one",channel.id,user.id);db.setTransactionSuccessful()}
            finally{db.endTransaction()}
            assertTrue(attachments.staged("draft:one").isEmpty())
            assertEquals("A private note",attachments.open(file.id,channel.id).bufferedReader().use{it.readText()})
            assertFails{attachments.remove(file.id,"draft:one")}
            store.deleteConversation(channel.id);attachments.deleteUnreferenced(listOf(file.id))
            assertNull(attachments.get(file.id));assertFalse(File(directory,file.id).exists())
        }
    }
    @Test fun `count byte and format refusals close input and leave no files behind`() {
        LocalStore(context).use{store->
            val attachments=AttachmentStore(context,store)
            assertFails{attachments.stage("home","drawing.svg","image/svg+xml",bytes("<svg/>"))}
            assertFails{attachments.stage("home","fake.png","image/png",bytes("ordinary text"))}
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            val huge=object:java.io.InputStream() {
                var closed=false;var remaining=MAX_IMAGE_BYTES+1
                override fun read():Int=if(remaining-->0)65 else -1
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    if(remaining<=0)return -1
                    val size=minOf(length,remaining);buffer.fill(65,offset,offset+size);remaining-=size;return size
                }
                override fun close(){closed=true}
            }
            assertFails{attachments.stage("home","huge.txt","text/plain",huge)}
            assertTrue(huge.closed);assertTrue(directory.listFiles().orEmpty().isEmpty())
            repeat(8){attachments.stage("home","notes$it.txt","text/plain",bytes("note"))}
            var closed=false
            val ninth=object:ByteArrayInputStream("note".toByteArray()){override fun close(){closed=true;super.close()}}
            assertFails{attachments.stage("home","ninth.txt","text/plain",ninth)}
            assertTrue(closed);assertEquals(8,attachments.staged("home").size)
        }
    }
    @Test fun `abandoned staged files expire without deleting sent conversation files`() {
        LocalStore(context).use{store->
            val attachments=AttachmentStore(context,store);val now=Instant.parse("2026-11-11T00:00:00Z")
            val old=attachments.stage("old","old.txt","text/plain",bytes("old"),now.minusSeconds(90000))
            val recent=attachments.stage("recent","recent.txt","text/plain",bytes("recent"),now)
            attachments.reap(now)
            assertNull(attachments.get(old.id));assertFalse(File(directory,old.id).exists())
            assertEquals(recent,attachments.get(recent.id));assertTrue(File(directory,recent.id).exists())
        }
    }
}
