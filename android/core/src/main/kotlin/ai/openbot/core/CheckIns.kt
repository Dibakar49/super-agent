package ai.openbot.core

import java.net.URI
import java.time.Instant

private val waitsOnYou = listOf(TaskStatus.READY_FOR_REVIEW, TaskStatus.NEEDS_INPUT, TaskStatus.FAILED)

/** Matches mobile/src/check-ins.tsx: review, answer, retry, newest within each group. */
fun waitingTasks(tasks: List<BotTask>): List<BotTask> = tasks
    .filter { it.status in waitsOnYou }
    .sortedWith(compareBy<BotTask> { waitsOnYou.indexOf(it.status) }
        .thenByDescending { runCatching { Instant.parse(it.updatedAt) }.getOrNull() ?: Instant.MIN })

fun webLink(value: String?): URI? = value?.let {
    runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }
}
