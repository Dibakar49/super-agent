package ai.openbot.core

/** Local inference context is deliberately bounded for phone memory and model context limits. */
data class LocalPromptContext(val text:String,val messageIds:List<String>)

fun localPrompt(bot: LocalBot, memories: List<Memory>, history: List<LocalMessage>, maximumCharacters: Int = 64000, toolNames: List<String> = emptyList(), toolEvents:List<LocalToolEvent> = emptyList()): String = localPromptContext(bot,memories,history,maximumCharacters,toolNames,toolEvents).text

fun localPromptContext(bot: LocalBot, memories: List<Memory>, history: List<LocalMessage>, maximumCharacters: Int = 64000, toolNames: List<String> = emptyList(), toolEvents:List<LocalToolEvent> = emptyList()):LocalPromptContext {
    require(!bot.paused) { "This Bot is paused. Resume it before sending a message." }
    val lastUser = history.lastOrNull { it.role == "user" && it.state == MessageState.COMPLETE }
        ?: error("A user message is required.")
    val instruction = bot.instruction
    val facts = quoteMemoryContext(memories.filter { it.enabled && (it.formedBy != FormedBy.BOT || it.formedByAgentId == bot.id) })
    val prefix = buildString {
        appendLine(instruction)
        appendLine("The OpenBot backend runs on this Android device. Computer use is unavailable.")
        if(toolNames.isEmpty())appendLine("No tools or connected apps are available for this turn. Never claim to have taken an action.")
        else appendLine("Available tools: ${toolNames.joinToString()}. Use the supplied tools to take actions; only report success after a successful result. No other tools or connected apps are available. The person’s timezone is ${java.time.ZoneId.systemDefault().id}.")
        if (facts.isNotEmpty()) appendLine(facts)
        if(toolEvents.isNotEmpty())appendLine(quoteToolContext(toolEvents))
    }
    val current = "Person: ${lastUser.text}\nBot:"
    require(prefix.length + current.length + 100 <= maximumCharacters) {
        "The instructions, enabled memory and message exceed this model's context budget. Choose a larger model context or reduce them. Nothing was sent."
    }
    var remaining = maximumCharacters - prefix.length - current.length - 100
    val prior = history.takeWhile { it.id != lastUser.id }
        .filter { it.state == MessageState.COMPLETE && it.role in listOf("user", "assistant") }
    val selected = mutableListOf<String>()
    val selectedIds=mutableListOf<String>()
    for (message in prior.asReversed()) {
        val line = "${if (message.role == "user") "Person" else "Bot"}: ${message.text}"
        if (line.length + 1 > remaining) break
        selectedIds.add(message.id)
        selected.add(line); remaining -= line.length + 1
    }
    val previous = selected.asReversed().joinToString("\n")
    val text=buildString {
        append(prefix)
        if (selected.size < prior.size) appendLine("Earlier messages were omitted to fit the model's context budget.")
        if (previous.isNotEmpty()) { appendLine("Earlier conversation:"); appendLine(previous) }
        append(current)
    }
    return LocalPromptContext(text,selectedIds.asReversed()+lastUser.id)
}
