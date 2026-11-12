package ai.openbot.core

/** Shared with the model prompt and transcript renderer, matching shared/routine-firing.ts. */
val FIRING_FRAME = listOf(
    "One of your routines is firing right now, on its schedule, and this is that firing.",
    "Carry out the instruction below in this turn: do the work now, then say what happened.",
    "Do not create, list or change any routine unless the instruction itself asks you to."
)
private val firingPrefix = FIRING_FRAME.joinToString("\n") + "\n\n"
fun frameFiring(instruction: String): String = firingPrefix + instruction
fun readFiring(text: String): String? = if (text.startsWith(firingPrefix)) text.substring(firingPrefix.length) else null
