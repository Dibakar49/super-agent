package ai.openbot.android.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import ai.openbot.core.*
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Goal identity, lifecycle and run outbox live in the phone's transactional SQLite database. */
class GoalStore(private val store:LocalStore) {
    private val json=Json{ignoreUnknownKeys=true}
    private val db get()=store.writableDatabase
    companion object {
        fun createTables(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE goals(id TEXT PRIMARY KEY,bot_id TEXT NOT NULL REFERENCES bots(id) ON DELETE CASCADE,channel_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,updated_at TEXT NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX goals_updated ON goals(updated_at)")
            db.execSQL("CREATE TABLE goal_runs(id TEXT PRIMARY KEY,goal_id TEXT NOT NULL REFERENCES goals(id) ON DELETE CASCADE,status TEXT NOT NULL,created_at TEXT NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX goal_runs_goal ON goal_runs(goal_id,created_at)")
            db.execSQL("CREATE INDEX goal_runs_status ON goal_runs(status)")
        }
    }
    private inline fun <reified T> query(sql:String,args:Array<String>?=null):List<T> = db.rawQuery(sql,args).use{cursor->buildList{while(cursor.moveToNext())add(json.decodeFromString<T>(cursor.getString(0)))}}
    private fun changed(){if(!db.inTransaction())LocalDatabaseChanges.changed()}
    private inline fun <T> transaction(block:()->T):T {
        db.beginTransaction()
        try{val result=block();db.setTransactionSuccessful();return result}finally{db.endTransaction();changed()}
    }
    fun list():List<LocalGoal> = query("SELECT payload FROM goals ORDER BY updated_at DESC,rowid DESC LIMIT 100")
    fun get(id:String):LocalGoal = query<LocalGoal>("SELECT payload FROM goals WHERE id=?",arrayOf(id)).firstOrNull()?:error("This goal could not be found.")
    fun run(id:String):LocalGoalRun? = query<LocalGoalRun>("SELECT payload FROM goal_runs WHERE id=?",arrayOf(id)).firstOrNull()
    fun runs(goalId:String):List<LocalGoalRun> = query("SELECT payload FROM goal_runs WHERE goal_id=? ORDER BY created_at DESC,rowid DESC LIMIT 50",arrayOf(goalId))
    fun queuedRuns():List<LocalGoalRun> = query("SELECT payload FROM goal_runs WHERE status=? ORDER BY created_at,rowid LIMIT 100",arrayOf(GoalRunStatus.QUEUED.name))
    fun save(input:LocalGoal):LocalGoal = transaction {
        val goal=validateGoal(input)
        val old=query<LocalGoal>("SELECT payload FROM goals WHERE id=?",arrayOf(goal.id)).firstOrNull()
        require(old==null||old.botId==goal.botId&&old.channelId==goal.channelId){"A goal's Bot and conversation cannot change."}
        require(goal.subscriptions.isEmpty()||goal.subscriptions==old?.subscriptions){"Event-triggered goals are not available yet. Leave event subscriptions empty."}
        require(store.conversations().any{it.id==goal.channelId&&it.botId==goal.botId}){"Choose an accessible Bot and conversation you belong to."}
        val values=ContentValues().apply{put("payload",json.encodeToString(goal));put("updated_at",goal.updatedAt)}
        if(db.update("goals",values,"id=?",arrayOf(goal.id))==0){values.put("id",goal.id);values.put("bot_id",goal.botId);values.put("channel_id",goal.channelId);db.insertOrThrow("goals",null,values)}
        goal
    }
    fun transition(id:String,status:GoalStatus):LocalGoal = transaction {
        val old=get(id)
        require(old.status!=GoalStatus.COMPLETED||status==GoalStatus.COMPLETED){"A completed goal can't be resumed. Create a new goal."}
        val now=Instant.now().toString()
        save(old.copy(status=status,updatedAt=now,completedAt=if(status==GoalStatus.COMPLETED)old.completedAt?:now else null))
    }
    fun repeats(id:String):String?=whyGoalRepeats(get(id),store.routines())
    fun progress(id:String,summary:String,complete:Boolean=false,sourceRunId:String?=null):LocalGoal = transaction {
        val goal=get(id);val trimmed=summary.trim()
        require(trimmed.isNotEmpty()&&trimmed.length<=6000){"Progress must contain 1–6000 characters."}
        require(goal.status==GoalStatus.ACTIVE){"Only an active goal can make progress."}
        if(sourceRunId!=null) {
            require(run(sourceRunId)?.let{it.goalId==id&&it.status==GoalRunStatus.RUNNING}==true){"Progress must come from the goal's current run."}
            if(complete)repeats(id)?.let{error("This goal repeats because $it, so a run can't complete it. Record this run's progress without complete, and the goal stays active. Only the person ends it.")}
        }
        val now=Instant.now().toString()
        save(goal.copy(progress=trimmed,updatedAt=now,status=if(complete)GoalStatus.COMPLETED else goal.status,completedAt=if(complete)now else goal.completedAt))
    }
    fun enqueue(id:String):LocalGoalRun=transaction {
        val goal=get(id)
        check(goal.status!=GoalStatus.COMPLETED){"\"${goal.title}\" is complete, so it no longer runs and nothing was done. Tell the person, and ask whether to set it up again as a new goal or switch off what runs it."}
        check(goal.status!=GoalStatus.PAUSED){"\"${goal.title}\" is paused, so it didn't run. Resume it first, if the person wants it to run."}
        require(store.bots().any{it.id==goal.botId&&!it.paused}){"This Bot is paused. Resume it before running the goal."}
        LocalGoalRun(UUID.randomUUID().toString(),id,Instant.now().toString()).also(::putRun)
    }
    private fun putRun(run:LocalGoalRun) {
        val values=ContentValues().apply{put("payload",json.encodeToString(run));put("status",run.status.name)}
        if(db.update("goal_runs",values,"id=?",arrayOf(run.id))==0){values.put("id",run.id);values.put("goal_id",run.goalId);values.put("created_at",run.createdAt);db.insertOrThrow("goal_runs",null,values)}
        changed()
    }
    fun claim(id:String):LocalGoalRun? = transaction {
        val old=run(id)?.takeIf{it.status==GoalRunStatus.QUEUED}?:return@transaction null
        val goal=get(old.goalId)
        if(goal.status!=GoalStatus.ACTIVE||store.bots().none{it.id==goal.botId&&!it.paused}) {
            putRun(old.copy(status=GoalRunStatus.SKIPPED,finishedAt=Instant.now().toString(),error="The goal or its Bot is paused or completed."));return@transaction null
        }
        old.copy(status=GoalRunStatus.RUNNING,startedAt=Instant.now().toString()).also(::putRun)
    }
    fun attachTurn(id:String,turnId:String)=transaction {
        val old=run(id)?:error("This goal run could not be found.")
        require(old.status==GoalRunStatus.RUNNING&&old.turnId==null){"This goal run already has a turn."}
        putRun(old.copy(turnId=turnId))
    }
    fun requeue(id:String)=transaction {
        val old=run(id)?:return@transaction
        require(old.status==GoalRunStatus.RUNNING&&old.turnId==null){"A started turn cannot be repeated automatically."}
        putRun(old.copy(status=GoalRunStatus.QUEUED,startedAt=null,error=null))
    }
    fun resumeWaiting(id:String)=transaction {
        val old=run(id)?.takeIf{it.status==GoalRunStatus.WAITING}?:return@transaction
        require(get(old.goalId).status==GoalStatus.ACTIVE){"Resume the goal before answering its waiting run."}
        putRun(old.copy(status=GoalRunStatus.RUNNING,finishedAt=null))
    }
    fun finish(id:String,status:GoalRunStatus,reply:String?=null,error:String?=null)=transaction {
        val old=run(id)?.takeIf{it.status in listOf(GoalRunStatus.RUNNING,GoalRunStatus.QUEUED)||it.status==GoalRunStatus.WAITING&&status in listOf(GoalRunStatus.SKIPPED,GoalRunStatus.FAILED,GoalRunStatus.SUCCEEDED)}?:return@transaction
        putRun(old.copy(status=status,replyText=reply,error=error?.take(1000),finishedAt=Instant.now().toString()))
        if(status==GoalRunStatus.SUCCEEDED&&reply!=null){val goal=get(old.goalId);save(goal.copy(lastResult=reply,updatedAt=Instant.now().toString()))}
    }
}
