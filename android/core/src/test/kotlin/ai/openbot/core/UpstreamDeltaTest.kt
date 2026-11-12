package ai.openbot.core

import java.time.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

class UpstreamDeltaTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/upstream-delta.json")!!.readText()).jsonObject

    @Test fun `hourly descriptions and typed timezone errors match pinned upstream`() {
        fixture["cron"]!!.jsonArray.forEach { row ->
            val value = row.jsonObject
            assertEquals(value["description"]!!.jsonPrimitive.content, describeCron(value["expression"]!!.jsonPrimitive.content))
        }
        val failure = assertFailsWith<ScheduleRefusedException> {
            nextOccurrence("0 * * * *", "Wrong/Timezone", Instant.parse("2026-11-12T00:00:00Z"))
        }
        assertEquals(fixture["unknownTimezone"]!!.jsonObject["code"]!!.jsonPrimitive.content, failure.code)
        assertEquals(fixture["unknownTimezone"]!!.jsonObject["message"]!!.jsonPrimitive.content, failure.message)
    }

    @Test fun `unrun outcomes match upstream and preserve readable persisted legacy outcomes`() {
        fixture["notRun"]!!.jsonArray.forEach { row ->
            val value = row.jsonObject
            assertEquals(value["answer"]!!.jsonPrimitive.content, notRun(value["why"]!!.jsonPrimitive.content))
            assertEquals(value["person"]!!.jsonPrimitive.content, outcomeForPerson(value["answer"]!!.jsonPrimitive.content))
        }
        val named = mapOf("WITHDRAWN_APPROVALS_OFF" to WITHDRAWN_APPROVALS_OFF, "WITHDRAWN_RULE_ALLOWS" to WITHDRAWN_RULE_ALLOWS, "NOT_RUN_WHILE_WAITING" to NOT_RUN_WHILE_WAITING)
        fixture["named"]!!.jsonArray.forEach { row ->
            val value = row.jsonObject
            assertEquals(value["answer"]!!.jsonPrimitive.content, named[value["name"]!!.jsonPrimitive.content])
            assertEquals(value["person"]!!.jsonPrimitive.content, outcomeForPerson(value["answer"]!!.jsonPrimitive.content))
        }
        assertEquals("Withdrawn. You turned off asking before changes.", outcomeForPerson("Not done: this request was withdrawn because the person switched off asking before changes. Try the action again if it is still needed."))
    }
}
