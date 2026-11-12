package ai.openbot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.regex.Pattern

@Serializable data class PolicyCandidate(
    val botId: String,
    val toolRef: String,
    val effect: String,
    val scope: String,
    val args: JsonElement = JsonNull,
    val target: JsonElement = JsonNull,
    val destructive: Boolean = false,
)

@Serializable enum class PolicyBehaviour {
    @SerialName("allow")
    ALLOW,

    @SerialName("ask")
    ASK,

    @SerialName("hand_off")
    HAND_OFF,
}

@Serializable data class PolicyReview(
    val verdict: String,
    val preApproved: Boolean,
    val reason: String,
    val safety: String = "none",
    val failedClosed: Boolean = false,
)

@Serializable data class LocalPolicyOutcome(
    val behaviour: PolicyBehaviour,
    val source: String,
    val reason: String,
    val ruleId: String? = null,
    val review: PolicyReview? = null,
)

data class SafetyRequirement(
    val id: String,
    val reason: String,
    val behaviour: PolicyBehaviour,
)

data class MatchedApprovalRule(
    val rule: ApprovalRule,
    val layer: String,
)

fun globMatches(
    pattern: String,
    value: String,
): Boolean = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL).matches(value)

fun ruleMatches(
    rule: ApprovalRule,
    candidate: PolicyCandidate,
) = globMatches(rule.botId, candidate.botId) && globMatches(rule.toolRef, candidate.toolRef) && globMatches(rule.effect, candidate.effect) && globMatches(rule.scope, candidate.scope)

private val ruleRank = mapOf(RuleBehaviour.ALLOW to 0, RuleBehaviour.PRE_APPROVED to 1, RuleBehaviour.ASK to 2, RuleBehaviour.HAND_OFF to 3)

fun strictestRule(
    candidate: PolicyCandidate,
    team: List<ApprovalRule>,
    personal: List<ApprovalRule>,
): MatchedApprovalRule? {
    var best: MatchedApprovalRule? = null
    for ((layer, rules) in listOf("team_rule" to team, "personal_rule" to personal)) {
        for (rule in rules) {
            if (ruleMatches(rule, candidate) && (best == null || ruleRank.getValue(rule.behaviour) > ruleRank.getValue(best.rule.behaviour)))best = MatchedApprovalRule(rule, layer)
        }
    }
    return best
}

fun offersStandingRule(source: String?) = source != "task" && source != "safety"

/** Regex sources and reasons are copied by script from the unchanged original policy.ts. */
private object PolicyPatterns {
    val data = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/approval-policy-patterns.json")).bufferedReader().use { it.readText() }).jsonObject

    fun compile(
        source: String,
        flags: String = "",
    ): Pattern = Pattern.compile(source, if ('i' in flags)Pattern.CASE_INSENSITIVE else 0)

    val firstVerb = compile(data["firstVerb"]!!.jsonPrimitive.content)
    val notSending = compile(data["notSending"]!!.jsonPrimitive.content)
    val partOfSomething = compile(data["partOfSomething"]!!.jsonPrimitive.content)
    val own = compile(data["ownTools"]!!.jsonPrimitive.content, "i")
    val requirements = data["requirements"]!!.jsonArray.map { it.jsonObject }
    val consequential = data["consequential"]!!.jsonArray.map { it.jsonObject }
}

private fun Pattern.test(value: String) = matcher(value).find()

private fun objectPattern(value: JsonObject) = PolicyPatterns.compile(value["pattern"]!!.jsonPrimitive.content, value["flags"]?.jsonPrimitive?.content.orEmpty())

private fun actionName(ref: String) =
    ref
        .substringAfterLast('/')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace(Regex("[-_.\\s]+"), " ")
        .lowercase(java.util.Locale.ROOT)
        .trim()

private fun namedMatch(
    entry: JsonObject,
    name: String,
): Boolean {
    val pattern = objectPattern(entry)
    val kind = entry["kind"]?.jsonPrimitive?.content ?: "regexp"
    if (kind == "regexp")return pattern.test(name)
    val matcher = pattern.matcher(name)
    var acts = false
    while (matcher.find()) {
        val before = name.substring(0, matcher.start())
        val first = PolicyPatterns.firstVerb.matcher(before)
        val objectVerb = first.find() && PolicyPatterns.notSending.test(first.group(1))
        if (!objectVerb && !Regex("\\breactions?\\b").containsMatchIn(before) && !PolicyPatterns.partOfSomething.test(name.substring(matcher.end())))acts = true
    }
    return acts && (kind != "toOthers" || !Regex("\\bto (self|me|myself)\\b").containsMatchIn(name))
}

private data class ReadWords(
    val text: String,
    val filled: Boolean,
)

private fun plainlyEmpty(value: JsonElement) =
    when (value) {
        is JsonArray -> value.isEmpty()
        is JsonObject -> value.isEmpty()
        is JsonPrimitive -> value.isString && value.content.isEmpty()
    }

private fun words(
    value: JsonElement,
    depth: Int = 0,
): ReadWords {
    if (value == JsonNull)return ReadWords("", false)
    if (depth > 6)return ReadWords("", !plainlyEmpty(value))
    return when (value) {
        is JsonPrimitive -> ReadWords(value.content, !value.isString || value.content.isNotEmpty())
        is JsonArray -> {
            val entries = value.map { words(it, depth + 1) }
            ReadWords(entries.joinToString(" ") { it.text }, entries.any { it.filled })
        }
        is JsonObject -> {
            val entries =
                value.map { (key, item) ->
                    val read = words(item, depth + 1)
                    ReadWords(if (read.filled)"$key ${read.text}" else "", read.filled)
                }
            ReadWords(entries.joinToString(" ") { it.text }, entries.any { it.filled })
        }
    }
}

private val recipientKeys = setOf("to", "cc", "bcc", "recipient", "recipients", "recipient_email", "extra_recipients", "attendees", "invitees", "guests")

private fun addressesPeople(args: JsonElement): Boolean =
    (args as? JsonObject)?.any { (key, value) ->
        val lower = key.lowercase(java.util.Locale.ROOT)
        (lower in recipientKeys || Regex("(^|_)(attendees?|invitees?|guests?)(_|$)").containsMatchIn(key.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(java.util.Locale.ROOT))) && Regex("\\S@\\S").containsMatchIn(words(value).text)
    } == true

/** Connector/account policy only. Computer-use dispatch is deliberately absent from the Android app. */
fun safetyRequirement(candidate: PolicyCandidate): SafetyRequirement? {
    if (candidate.effect == "read")return null
    if (Regex("^(computer_(write_file|read_file|list_files|run_command)|host/)").containsMatchIn(candidate.toolRef))return null
    val text = listOf(candidate.toolRef, candidate.scope, words(candidate.args).text, words(candidate.target).text).joinToString(" ")
    val name = actionName(candidate.toolRef)
    PolicyPatterns.requirements.firstOrNull { entry -> objectPattern(entry).test(text) || (entry["tool"] as? JsonObject)?.let { objectPattern(it).test(name) } == true }?.let {
        return SafetyRequirement(it["id"]!!.jsonPrimitive.content, it["reason"]!!.jsonPrimitive.content, PolicyBehaviour.HAND_OFF)
    }
    if (PolicyPatterns.own.test(candidate.toolRef))return null
    val asks =
        PolicyPatterns.consequential.firstOrNull { entry ->
            namedMatch(entry, name) && !(entry["id"]!!.jsonPrimitive.content == "sending" && Regex("\\bdrafts?\\b").containsMatchIn(name) && !Regex("\\bsend\\b").containsMatchIn(name))
        } ?: PolicyPatterns.consequential.firstOrNull { it["id"]!!.jsonPrimitive.content == "sending" && !Regex("\\bdrafts?\\b").containsMatchIn(name) && addressesPeople(candidate.args) }
            ?: PolicyPatterns.consequential.firstOrNull { entry ->
                val matcher = PolicyPatterns.firstVerb.matcher(name)
                entry["id"]!!.jsonPrimitive.content == "deleting" && matcher.find() && Regex("^(removes?|archives?)$").matches(matcher.group(1))
            }
    if (asks != null)return SafetyRequirement(asks["id"]!!.jsonPrimitive.content, asks["reason"]!!.jsonPrimitive.content, PolicyBehaviour.ASK)
    return if (candidate.destructive)SafetyRequirement("deleting", "The app marks this as deleting or overwriting something, so it always asks you first.", PolicyBehaviour.ASK) else null
}

fun reviewable(candidate: PolicyCandidate) = candidate.effect != "read" && !PolicyPatterns.own.test(candidate.toolRef)

fun requestOf(messages: List<LocalMessage>) = messages.filter { it.role == "user" }.joinToString("\n\n") { it.text }.takeLast(6000)

fun parsePolicyReview(text: String): PolicyReview? =
    runCatching {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end >= start)
        val obj = Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject

        fun string(name: String) = obj[name]?.jsonPrimitive?.takeIf { it.isString }?.content
        val verdict = string("verdict")?.takeIf { it in listOf("proceed", "needs_approval", "hand_off") } ?: error("Invalid verdict")
        val pre = obj["preApproved"]?.jsonPrimitive?.takeIf { !it.isString }?.booleanOrNull ?: error("Invalid pre-approval")
        val reason = string("reason")?.takeIf { it.length in 1..1000 } ?: error("Invalid reason")
        val safety = if (obj.containsKey("safety"))string("safety")?.takeIf { it in listOf("none", "credentials", "security_settings", "payments") } ?: error("Invalid safety category") else "none"
        PolicyReview(verdict, pre, reason, safety)
    }.getOrNull()

/** Same precedence as decideAction: mandatory requirements, strictest rule, review, then preference. */
suspend fun decidePolicy(
    candidate: PolicyCandidate,
    team: List<ApprovalRule> = emptyList(),
    personal: List<ApprovalRule> = emptyList(),
    customRulesEnabled: Boolean = true,
    autoReview: Boolean = false,
    askBeforeChanges: Boolean = false,
    reviewer: (suspend (MatchedApprovalRule?) -> PolicyReview)? = null,
): LocalPolicyOutcome {
    safetyRequirement(candidate)?.let { return LocalPolicyOutcome(it.behaviour, "safety", it.reason) }
    val match = strictestRule(candidate, team, if (customRulesEnabled)personal else emptyList())
    if (match?.rule?.behaviour == RuleBehaviour.HAND_OFF)return LocalPolicyOutcome(PolicyBehaviour.HAND_OFF, match.layer, "A rule hands this kind of action to the person.", match.rule.id)
    val review =
        if (autoReview && reviewable(candidate) || match?.rule?.behaviour == RuleBehaviour.PRE_APPROVED) {
            reviewer?.invoke(match) ?: PolicyReview("needs_approval", false, "No reviewer model is configured, so this needs approval.", failedClosed = true)
        } else {
            null
        }
    if (review != null && review.safety != "none" && !review.failedClosed && !Regex("^(computer_(write_file|read_file|list_files|run_command)|host/)").containsMatchIn(candidate.toolRef)) {
        val reason =
            when (review.safety) {
                "credentials" -> "The reviewer found this changes a password, passcode, two-factor setting or other credential, which is always done by the person."
                "security_settings" -> "The reviewer found this changes security, sharing or access settings, which is always done by the person."
                else -> "The reviewer found this pays, purchases or moves money, which is always done by the person."
            }
        return LocalPolicyOutcome(PolicyBehaviour.HAND_OFF, "safety", reason, review = review)
    }
    if (review != null && autoReview && reviewable(candidate)) {
        if (review.verdict == "hand_off")return LocalPolicyOutcome(PolicyBehaviour.HAND_OFF, "auto_review", review.reason, match?.rule?.id, review)
        if (review.verdict == "needs_approval")return LocalPolicyOutcome(PolicyBehaviour.ASK, "auto_review", review.reason, match?.rule?.id, review)
    }
    return when (match?.rule?.behaviour) {
        RuleBehaviour.ASK -> LocalPolicyOutcome(PolicyBehaviour.ASK, match.layer, "A rule asks before this kind of action.", match.rule.id, review)
        RuleBehaviour.PRE_APPROVED ->
            if (review?.preApproved == true && !review.failedClosed) {
                LocalPolicyOutcome(PolicyBehaviour.ALLOW, match.layer, "The person asked for this exact action in this run.", match.rule.id, review)
            } else {
                LocalPolicyOutcome(PolicyBehaviour.ASK, match.layer, "A rule allows this only when the person asked for it, and this run's request did not.", match.rule.id, review)
            }
        RuleBehaviour.ALLOW -> LocalPolicyOutcome(PolicyBehaviour.ALLOW, match.layer, "A rule allows this kind of action.", match.rule.id, review)
        else ->
            if (askBeforeChanges && candidate.effect != "read") {
                LocalPolicyOutcome(PolicyBehaviour.ASK, "default", "You asked to review changes before they happen.", review = review)
            } else {
                LocalPolicyOutcome(PolicyBehaviour.ALLOW, if (review != null)"auto_review" else "default", review?.reason ?: "No rule or preference asks about this.", review = review)
            }
    }
}

fun connectedCandidate(
    botId: String,
    connectionId: String,
    url: String,
    tool: McpTool,
    args: JsonObject,
) = PolicyCandidate(
    botId,
    "$connectionId/${tool.name}",
    "write",
    connectionId,
    args,
    buildJsonObject {
        put("serverId", connectionId)
        put("toolName", tool.name)
        put("url", url)
        put("effect", "write")
    },
    tool.annotations["destructiveHint"] == JsonPrimitive(true),
)

fun pendingCandidate(action: PendingLocalAction) = connectedCandidate(action.botId, action.connectionId, action.connectionUrl, action.tool, action.call.arguments)

/** Recheck current preferences/rules transactionally using the already obtained reviewer verdict. */
fun canExecuteAutomatically(
    candidate: PolicyCandidate,
    rules: List<ApprovalRule>,
    preferences: ApprovalPreferences,
    review: PolicyReview?,
): Boolean {
    if (safetyRequirement(candidate) != null)return false
    val matched = strictestRule(candidate, emptyList(), rules)?.rule
    if (matched?.behaviour in listOf(RuleBehaviour.HAND_OFF, RuleBehaviour.ASK))return false
    val needsReview = preferences.autoReview && reviewable(candidate) || matched?.behaviour == RuleBehaviour.PRE_APPROVED
    if (needsReview) {
        if (review == null || review.failedClosed || review.safety != "none")return false
        if (preferences.autoReview && reviewable(candidate) && review.verdict != "proceed")return false
        if (matched?.behaviour == RuleBehaviour.PRE_APPROVED && !review.preApproved)return false
    }
    return matched?.behaviour in listOf(RuleBehaviour.ALLOW, RuleBehaviour.PRE_APPROVED) || !preferences.enabled || candidate.effect == "read"
}
