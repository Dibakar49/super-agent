package ai.openbot.android.data

import ai.openbot.core.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoalStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: LocalStore
    private lateinit var goals: GoalStore

    @BeforeTest fun setup() {
        context.deleteDatabase("openbot.db")
        store = LocalStore(context)
        goals = GoalStore(store)
    }

    @AfterTest fun teardown() {
        store.close()
        context.deleteDatabase("openbot.db")
    }

    private fun create(
        id: String = "goal",
        recurring: Boolean = false,
    ): LocalGoal {
        val channel = store.newConversation(store.bots().single().id)
        val now = Instant.now().toString()
        return goals.save(LocalGoal(id, channel.botId, channel.id, "Prepare report", "Write a sourced report", "Report includes the current figures", recurring = recurring, createdAt = now, updatedAt = now))
    }

    @Test fun `goal lifecycle and queued runs persist and completion cannot be undone`() {
        val goal = create()
        val queued = goals.enqueue(goal.id)
        goals.transition(goal.id, GoalStatus.PAUSED)
        assertNull(goals.claim(queued.id))
        assertEquals(GoalRunStatus.SKIPPED, goals.run(queued.id)!!.status)
        assertFails { goals.enqueue(goal.id) }
        goals.transition(goal.id, GoalStatus.ACTIVE)
        val run = assertNotNull(goals.claim(goals.enqueue(goal.id).id))
        assertNull(goals.claim(run.id))
        val complete = goals.progress(goal.id, "Report written with sources", true, run.id)
        assertEquals(GoalStatus.COMPLETED, complete.status)
        assertFails { goals.transition(goal.id, GoalStatus.ACTIVE) }
        assertFails { goals.enqueue(goal.id) }
        goals.finish(run.id, GoalRunStatus.SUCCEEDED, "Report finished")
        store.close()
        store = LocalStore(context)
        goals = GoalStore(store)
        assertEquals("Report finished", goals.get(goal.id).lastResult)
        assertEquals(GoalStatus.COMPLETED, goals.get(goal.id).status)
        assertEquals(GoalRunStatus.SUCCEEDED, goals.runs(goal.id).first().status)
    }

    @Test fun `an enabled routine makes a goal repeat and its run cannot end it`() {
        val goal = create()
        val now = Instant.now().toString()
        store.saveRoutine(LocalRoutine("routine", goal.botId, goal.channelId, "Run this goal", cron = "0 9 * * *", nextRunAt = now, createdAt = now, goalId = goal.id))
        assertEquals("a routine runs it", goals.repeats(goal.id))
        val run = assertNotNull(goals.claim(goals.enqueue(goal.id).id))
        assertFails { goals.progress(goal.id, "Today's report written", true, run.id) }
        assertEquals(GoalStatus.ACTIVE, goals.get(goal.id).status)
        goals.progress(goal.id, "Today's report written", false, run.id)
        assertEquals("Today's report written", goals.get(goal.id).progress)
        store.saveRoutine(store.routines().single().copy(enabled = false))
        assertNull(goals.repeats(goal.id))
        goals.progress(goal.id, "All work complete", true, run.id)
        assertEquals(GoalStatus.COMPLETED, goals.get(goal.id).status)
    }

    @Test fun `progress is bound to the goal run and invalid edits keep the saved goal`() {
        val first = create()
        val other = create("other")
        val run = assertNotNull(goals.claim(goals.enqueue(first.id).id))
        assertFails { goals.progress(other.id, "Wrong goal", false, run.id) }
        assertFails { goals.save(first.copy(title = "x".repeat(161))) }
        assertFails { goals.save(first.copy(channelId = "missing")) }
        assertEquals("Prepare report", goals.get(first.id).title)
        store.deleteConversation(first.channelId)
        assertFails { goals.get(first.id) }
        assertNull(goals.run(run.id))
        assertEquals(other.id, goals.list().single().id)
    }
}
