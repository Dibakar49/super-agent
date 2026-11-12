package ai.openbot.android.data

import ai.openbot.core.*
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/** Waiting questions and their answers commit with the continuation, so an answer is consumed once. */
class QuestionStore(
    private val store: LocalStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<LocalQuestion> =
        store.readableDatabase.rawQuery("SELECT payload FROM questions ORDER BY rowid DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext())add(json.decodeFromString<LocalQuestion>(cursor.getString(0))) }
        }

    fun waiting(): List<LocalQuestion> {
        val turns =
            store
                .checkpoints()
                .filter { it.phase == ContinuationPhase.WAITING }
                .map { it.turnId }
                .toSet()
        return list().filter { it.answer == null && it.turnId in turns }
    }

    private fun save(question: LocalQuestion) {
        val values = ContentValues().apply { put("payload", json.encodeToString(question)) }
        if (store.writableDatabase.update("questions", values, "id=?", arrayOf(question.id)) == 0) {
            values.put("id", question.id)
            values.put("turn_id", question.turnId)
            store.writableDatabase.insertOrThrow("questions", null, values)
        }
    }

    fun wait(
        checkpoint: AgentCheckpoint,
        question: LocalQuestion,
    ) {
        require(checkpoint.questionId == question.id && checkpoint.turnId == question.turnId && checkpoint.channelId == question.channelId)
        require(checkpoint.round.calls.getOrNull(checkpoint.results.size) == question.call)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val message = store.messages(question.channelId).first { it.id == question.turnId }
            require(message.role == "assistant" && message.state in listOf(MessageState.GENERATING, MessageState.WAITING))
            save(question)
            store.saveCheckpoint(checkpoint)
            store.saveMessage(message.copy(state = MessageState.WAITING, text = question.question))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            LocalDatabaseChanges.changed()
        }
    }

    fun answer(
        id: String,
        response: String,
    ): AgentCheckpoint {
        val text = response.trim()
        require(text.isNotEmpty() && text.length <= 6000) { "Write an answer of 1–6000 characters." }
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val question = list().firstOrNull { it.id == id } ?: error("This question no longer exists.")
            require(question.answer == null) { "You already answered this question." }
            val checkpoint =
                store.checkpoints().firstOrNull { it.turnId == question.turnId && it.questionId == id && it.phase == ContinuationPhase.WAITING }
                    ?: error("This turn can no longer continue.")
            require(store.bots().any { it.id == question.botId && !it.paused }) { "Resume this Bot before answering." }
            checkpoint.goalRunId?.let { runId ->
                val goals = GoalStore(store)
                val run = goals.run(runId) ?: error("This goal run no longer exists.")
                require(goals.get(run.goalId).status == GoalStatus.ACTIVE) { "Resume this goal before answering." }
            }
            checkpoint.routineRunId?.let { runId ->
                val run = store.routineRun(runId) ?: error("This routine no longer exists.")
                require(store.routines().any { it.id == run.routineId && it.enabled }) { "Resume this routine before answering." }
            }
            val now = Instant.now().toString()
            val framed = questionResponseMessage(question.question, text)
            save(question.copy(answer = text))
            store.saveToolEvent(LocalToolEvent(UUID.randomUUID().toString(), question.turnId, question.channelId, question.call.id, question.call.name, question.call.arguments, framed, false, now, "Asked you"))
            store.saveMessage(LocalMessage("person-response:$id", question.channelId, "user", framed, now, displayText = text))
            val queued = checkpoint.copy(phase = ContinuationPhase.QUEUED)
            store.saveCheckpoint(queued)
            db.setTransactionSuccessful()
            return queued
        } finally {
            db.endTransaction()
            LocalDatabaseChanges.changed()
        }
    }

    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE questions(id TEXT PRIMARY KEY,turn_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX questions_turn ON questions(turn_id)")
        }
    }
}
