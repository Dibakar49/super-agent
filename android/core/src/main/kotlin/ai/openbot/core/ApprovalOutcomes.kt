package ai.openbot.core

// Exact shared/approval-outcomes.ts sentences; these are part of the transcript contract.
const val DECLINED_BY_PERSON = "The person declined this action when you asked for their approval, so it was not done. This was their decision, not a policy or a boundary. Do not try this action, or anything equivalent, again in this conversation unless the person asks you to in words; tell them you did not do it because they declined."
const val ALREADY_WAITING = "An identical request is already waiting for the person's decision on the Bot's page in OpenBot, so this was not done and no second request was made. Do not repeat it; wait for their answer."
val WITHDRAWN_APPROVALS_OFF = notRun("this request was withdrawn because the person switched off asking before changes.")
val WITHDRAWN_RULE_ALLOWS = notRun("this request was withdrawn because a rule the person saved now allows it without asking.")
const val HANDLED_BY_PERSON = "The person did this themselves. Do not repeat it; continue from what they have done."
const val OUTCOME_UNKNOWN = "This action was started but its outcome is unknown. It was not repeated. Check whether it happened before asking the person again."
const val NOT_DONE = "Not done."
val NOT_RUN_WHILE_WAITING = notRun("the turn stopped before this step to wait for the person on another step asked for at the same time.")

fun notRun(why: String) = "Not run: $why Run it again if you still need it, or say in your answer that you could not get it."

fun notDoneOnRecheck(why: String) = "Not done: $why Ask the person again if it is still needed."

fun notCarriedOut(reason: String) = "Not done: this action could not be carried out (${reason.take(300)}). Check whether it happened before asking the person again."

private val forPerson =
    mapOf(
        // Preserve readable outcomes already persisted by version 0.3.4.
        "Not done: this request was withdrawn because the person switched off asking before changes. Try the action again if it is still needed." to "Withdrawn. You turned off asking before changes.",
        "Not done: this request was withdrawn because a rule the person saved now allows it without asking. Try the action again if it is still needed." to "Withdrawn. A rule you saved now allows it.",
        "Not done: the turn stopped before this step to wait for the person on another step asked for at the same time. Run it again if it is still needed." to "Not done. The Bot stopped to wait for you on another step.",
        DECLINED_BY_PERSON to "You declined it.",
        ALREADY_WAITING to "It's already waiting for your answer.",
        WITHDRAWN_APPROVALS_OFF to "Withdrawn. You turned off asking before changes.",
        WITHDRAWN_RULE_ALLOWS to "Withdrawn. A rule you saved now allows it.",
        HANDLED_BY_PERSON to "You did it yourself.",
        NOT_RUN_WHILE_WAITING to "Not done. The Bot stopped to wait for you on another step.",
        OUTCOME_UNKNOWN to "It started, but OpenBot can't tell whether it finished. It wasn't run again.",
    )

fun outcomeForPerson(text: String): String {
    forPerson[text.trim()]?.let { return it }
    if (!text.startsWith("Not done:") && !text.startsWith("Not run:")) return text
    var depth = 0
    for (index in text.indices) {
        when (text[index]) {
            '(' -> depth++
            ')' -> depth = maxOf(0, depth - 1)
            '.' -> if (depth == 0 && text.getOrNull(index + 1) == ' ') return text.substring(0, index + 1)
        }
    }
    return text
}

fun doneByPerson(text: String) = text.trim() == HANDLED_BY_PERSON

fun declinedByPerson(text: String) = text.trim() == DECLINED_BY_PERSON
