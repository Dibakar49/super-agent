package ai.openbot.android.data

import ai.openbot.core.*
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ApprovalRuleStore(
    private val store: LocalStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val db get() = store.writableDatabase

    companion object {
        fun createTables(
            db: SQLiteDatabase,
            keepLegacyAsk: Boolean = false,
        ) {
            db.execSQL("CREATE TABLE approval_preferences(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE approval_rules(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
            if (keepLegacyAsk) {
                db.insertOrThrow(
                    "approval_preferences",
                    null,
                    ContentValues().apply {
                        put("id", 1)
                        put("payload", Json.encodeToString(ApprovalPreferences(true, false)))
                    },
                )
            }
        }
    }

    private inline fun <reified T> query(sql: String): List<T> = db.rawQuery(sql, null).use { cursor -> buildList { while (cursor.moveToNext())add(json.decodeFromString<T>(cursor.getString(0))) } }

    private fun changed() {
        if (!db.inTransaction())LocalDatabaseChanges.changed()
    }

    fun preferences(): ApprovalPreferences = query<ApprovalPreferences>("SELECT payload FROM approval_preferences WHERE id=1").firstOrNull() ?: ApprovalPreferences(false, false)

    fun savePreferences(value: ApprovalPreferences) {
        db.insertWithOnConflict(
            "approval_preferences",
            null,
            ContentValues().apply {
                put("id", 1)
                put("payload", json.encodeToString(value))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        changed()
    }

    fun rules(): List<ApprovalRule> = query("SELECT payload FROM approval_rules ORDER BY rowid DESC")

    fun save(rule: ApprovalRule) {
        for (value in listOf(rule.botId, rule.toolRef, rule.effect, rule.scope))require(value.trim().length in 1..300) { "Each rule pattern must contain 1–300 characters." }
        val cleaned = rule.copy(botId = rule.botId.trim(), toolRef = rule.toolRef.trim(), effect = rule.effect.trim(), scope = rule.scope.trim())
        val values = ContentValues().apply { put("payload", json.encodeToString(cleaned)) }
        if (db.update("approval_rules", values, "id=?", arrayOf(rule.id)) == 0) {
            values.put("id", rule.id)
            db.insertOrThrow("approval_rules", null, values)
        }
        changed()
    }

    fun delete(id: String) {
        db.delete("approval_rules", "id=?", arrayOf(id))
        changed()
    }

    fun standingRuleExists(candidate: PolicyCandidate) = rules().any { it.botId == candidate.botId && it.toolRef == candidate.toolRef && it.effect == candidate.effect && it.scope == candidate.scope }

    /** Changing a preference/rule resumes an old request with a withdrawal result, never an effect. */
    fun withdraw(
        reason: String,
        match: (PendingLocalAction) -> Boolean,
    ): List<String> {
        val turns = mutableListOf<String>()
        for (snapshot in store.pendingActions().filter { it.status == LocalActionStatus.PENDING && match(it) }) {
            db.beginTransaction()
            try {
                val action = store.pendingActions().firstOrNull { it.id == snapshot.id && it.status == LocalActionStatus.PENDING } ?: continue
                val checkpoint = store.checkpoints().firstOrNull { it.turnId == action.turnId && it.approvalId == action.id && it.phase == ContinuationPhase.WAITING } ?: continue
                store.savePendingAction(action.copy(status = LocalActionStatus.CANCELLED))
                store.saveToolEvent(
                    LocalToolEvent(
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                        action.turnId,
                        action.channelId,
                        action.call.id,
                        action.call.name,
                        action.call.arguments,
                        reason,
                        true,
                        java.time.Instant
                            .now()
                            .toString(),
                        action.connectionName?.let { "$it · ${action.tool.name.replace('_',' ')}" },
                    ),
                )
                store.saveCheckpoint(checkpoint.copy(phase = ContinuationPhase.QUEUED))
                turns.add(checkpoint.turnId)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                changed()
            }
        }
        return turns
    }
}
