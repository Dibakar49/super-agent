package ai.openbot.core

import kotlinx.serialization.json.*
import kotlin.test.*

class GoalParityTest {
    @Test fun `goal linkage reproduces the original id precedence and whole quoted title behavior`() {
        val rows=Json.parseToJsonElement(javaClass.getResource("/goal-links-typescript-parity.json")!!.readText()).jsonArray
        rows.forEach{row->
            val obj=row.jsonObject;val r=obj["routine"]!!.jsonObject;val g=obj["goal"]!!.jsonObject
            val goal=LocalGoal(g["id"]!!.jsonPrimitive.content,"bot","channel",g["title"]!!.jsonPrimitive.content,"Work","Done",createdAt="t",updatedAt="t")
            val routine=LocalRoutine("r","bot","channel",r["instruction"]!!.jsonPrimitive.content,cron="0 9 * * *",nextRunAt="t",createdAt="t",
                goalId=r["goalId"]!!.jsonPrimitive.contentOrNull,goalByTitle=r["goalByTitle"]!!.jsonPrimitive.boolean)
            assertEquals(obj["matches"]!!.jsonPrimitive.boolean,routineRunsGoal(routine,goal),routine.instruction)
        }
    }
}
