package ai.openbot.agentstudio

import java.io.File
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EmbeddedProviderTest {
    @Test fun `read only provider serves its scoped file and rejects traversal and another agent directory`()=runBlocking<Unit> {
        val root=RuntimeEnvironment.getApplication()
        val a=AgentStudioProfile(UUID.randomUUID().toString(),"A","Only A")
        val b=AgentStudioProfile(UUID.randomUUID().toString(),"B","Only B")
        AgentStudioProfiles.save(root,a);AgentStudioProfiles.save(root,b)
        val first=AgentStudioProfiles.context(root,a.id)
        val second=AgentStudioProfiles.context(root,b.id)
        val file=File(first.filesDir,"agentstudio/attachments/${UUID.randomUUID()}").apply{requireNotNull(parentFile).mkdirs();writeText("A private attachment")}
        val uri=AgentStudioFileProvider.getUriForFile(first,root.packageName+".agentstudio.files",file,"research.txt")
        assertEquals("A private attachment",root.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()})
        assertFails{AgentStudioFileProvider.getUriForFile(second,root.packageName+".agentstudio.files",file,"research.txt")}
        assertFails{root.contentResolver.openOutputStream(uri,"w")}
        val unsafe=uri.buildUpon().path("/${a.id}/../host.db").build()
        assertFails{root.contentResolver.openInputStream(unsafe)}
    }
}
