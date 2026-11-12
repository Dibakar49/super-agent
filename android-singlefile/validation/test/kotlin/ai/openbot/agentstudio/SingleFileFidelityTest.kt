package ai.openbot.agentstudio.validation.core

import ai.openbot.agentstudio.AgentStudioOriginalSources
import ai.openbot.agentstudio.AgentStudioResources
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import kotlin.test.*

class SingleFileFidelityTest {
    @Test fun `all original source and resource bytes are preserved and runtime schemas are identical`() {
        val expected = Json.parseToJsonElement(javaClass.getResource("/source-fidelity.json")!!.readText()).jsonObject
        val actual = mutableMapOf<String,String>()
        ZipInputStream(AgentStudioResources.originalBytes().inputStream()).use { zip ->
            while(true) {
                val entry=zip.nextEntry?:break
                val data=zip.readBytes()
                actual[entry.name]=MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it.toInt() and 255)}
                if(entry.name.startsWith("android/core/src/main/resources/")) {
                    val name=entry.name.substringAfterLast('/')
                    assertContentEquals(data,AgentStudioResources.open(name)!!.use{it.readBytes()},name)
                }
            }
        }
        assertEquals(expected.keys,actual.keys)
        expected.forEach { (name,hash) -> assertEquals(hash.jsonPrimitive.content,actual[name],name) }
        assertEquals(110,actual.keys.count{it.endsWith(".kt")})
    }
}
