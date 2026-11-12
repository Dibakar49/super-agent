package ai.openbot.core

import java.time.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

class ScheduleParityTest {
    @Test fun `next occurrence refusals descriptions and DST behavior agree with original TypeScript`() {
        val rows=Json.parseToJsonElement(javaClass.getResource("/routines-typescript-parity.json")!!.readText()).jsonArray
        rows.forEach{row->
            val item=row.jsonObject
            val cron=item["cron"]!!.jsonPrimitive.content
            val zone=item["timezone"]!!.jsonPrimitive.content
            val after=Instant.parse(item["after"]!!.jsonPrimitive.content)
            val label="$cron in $zone after $after"
            assertEquals(item["description"]!!.jsonPrimitive.content,describeCron(cron),label)
            val expectedError=item["error"]?.jsonPrimitive?.content
            if(expectedError!=null)assertEquals(expectedError,assertFailsWith<ScheduleRefusedException>(label){nextOccurrence(cron,zone,after)}.message,label)
            else assertEquals(Instant.parse(item["next"]!!.jsonPrimitive.content),nextOccurrence(cron,zone,after),label)
        }
    }
}
