package ai.openbot.core

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable data class LocalRoutine(val id:String,val botId:String,val channelId:String,val instruction:String,val title:String?=null,
    val cron:String,val timezone:String="UTC",val enabled:Boolean=true,val nextRunAt:String,val createdAt:String,val lastRunAt:String?=null,val goalId:String?=null,val goalByTitle:Boolean=false)
@Serializable data class LocalRoutineRun(val id:String,val routineId:String,val startedAt:String,val source:String="schedule",
    val status:RoutineStatus=RoutineStatus.RUNNING,val finishedAt:String?=null,val error:String?=null)

fun validateRoutine(routine:LocalRoutine,now:Instant):LocalRoutine {
    val instruction=routine.instruction.trim()
    require(instruction.isNotEmpty()) {"A routine needs an instruction to carry out."}
    require(instruction.codePointCount(0,instruction.length)<=2000) {"An instruction can be at most 2000 characters."}
    val title=routine.title?.replace(Regex("\\s+")," ")?.trim()?.takeIf{it.isNotEmpty()}
    require(title==null||title.codePointCount(0,title.length)<=100){"A routine's title can be at most 100 characters."}
    val next=nextOccurrence(routine.cron,routine.timezone,now)
    return routine.copy(instruction=instruction,title=title,nextRunAt=next.toString())
}
