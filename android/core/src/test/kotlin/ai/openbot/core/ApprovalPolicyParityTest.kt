package ai.openbot.core

import kotlinx.serialization.json.*
import kotlin.coroutines.*
import kotlin.test.*

class ApprovalPolicyParityTest {
    private val json=Json{ignoreUnknownKeys=true}
    private fun rows(name:String)=Json.parseToJsonElement(javaClass.getResource("/$name-typescript-parity.json")!!.readText()).jsonArray
    @Test fun `connector safety outcomes match the unchanged TypeScript policy`() {
        rows("approval-safety").forEachIndexed{index,item->
            val obj=item.jsonObject;val candidate=json.decodeFromJsonElement<PolicyCandidate>(obj["candidate"]!!)
            val expected=obj["expected"] as? JsonObject;val actual=safetyRequirement(candidate)
            if(expected==null)assertNull(actual,"Case $index: ${candidate.toolRef}")
            else {
                assertEquals(expected["id"]!!.jsonPrimitive.content,actual?.id,"Case $index: ${candidate.toolRef}")
                assertEquals(expected["reason"]!!.jsonPrimitive.content,actual?.reason,"Case $index")
                assertEquals(expected["behaviour"]!!.jsonPrimitive.content,actual?.behaviour?.name?.lowercase(),"Case $index")
            }
        }
    }
    @Test fun `rules reviews preferences and mandatory requirements have the original precedence`() {
        rows("approval-decision").forEachIndexed{index,item->
            val obj=item.jsonObject
            val candidate=json.decodeFromJsonElement<PolicyCandidate>(obj["candidate"]!!)
            val personal=json.decodeFromJsonElement<List<ApprovalRule>>(obj["personal"]!!)
            val team=json.decodeFromJsonElement<List<ApprovalRule>>(obj["team"]!!)
            val expected=json.decodeFromJsonElement<LocalPolicyOutcome>(obj["expected"]!!)
            val review=obj["review"]?.takeIf{it!=JsonNull}?.let{json.decodeFromJsonElement<PolicyReview>(it)}
            var result:Result<LocalPolicyOutcome>?=null
            suspend {
                decidePolicy(candidate,team,personal,autoReview=obj["autoReview"]!!.jsonPrimitive.boolean,
                    askBeforeChanges=obj["enabled"]!!.jsonPrimitive.boolean,reviewer=review?.let{{_:MatchedApprovalRule?->it}})
            }.startCoroutine(object:Continuation<LocalPolicyOutcome>{override val context=EmptyCoroutineContext;override fun resumeWith(value:Result<LocalPolicyOutcome>){result=value}})
            assertEquals(expected,assertNotNull(result).getOrThrow(),"Case $index: ${candidate.toolRef}")
        }
    }
    @Test fun `rule globs preserve literal metacharacters and newline behavior`() {
        rows("approval-glob").forEach{row->val obj=row.jsonObject;assertEquals(obj["matches"]!!.jsonPrimitive.boolean,globMatches(obj["pattern"]!!.jsonPrimitive.content,obj["value"]!!.jsonPrimitive.content))}
    }
    @Test fun `a malformed reviewer result cannot grant pre approval`() {
        assertNotNull(parsePolicyReview("```json\n{\"verdict\":\"proceed\",\"preApproved\":true,\"reason\":\"Requested\"}\n```"))
        assertNull(parsePolicyReview("{\"verdict\":\"proceed\",\"preApproved\":\"true\",\"reason\":\"Requested\"}"))
        assertNull(parsePolicyReview("not JSON"))
        assertFalse(offersStandingRule("task"));assertFalse(offersStandingRule("safety"));assertTrue(offersStandingRule("default"))
    }
}
