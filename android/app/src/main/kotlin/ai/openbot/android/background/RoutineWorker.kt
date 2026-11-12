package ai.openbot.android.background

import android.content.Context
import androidx.work.*
import ai.openbot.android.data.LocalStore
import ai.openbot.android.inference.AgentRuntime
import ai.openbot.android.inference.LocalModel
import ai.openbot.core.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** WorkManager survives process recreation and reboot; Android may defer work while idle. */
class RoutineWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        LocalStore(applicationContext).use {store->
            val model=LocalModel(applicationContext)
            try {
                store.reapRoutineRuns()
                GoalWorker.enqueueQueued(applicationContext,store)
                val manualId=inputData.getString("routineId")
                val manualRun=manualId?.let{id->store.routineRuns(id).find{it.id==inputData.getString("runId")}}
                if(manualId!=null&&manualRun?.status!=RoutineStatus.RUNNING)return Result.success()
                val runtime=AgentRuntime(applicationContext,store,model)
                // Missing provider configuration is a deployment condition, not a scheduled failure.
                try {runtime.checkConfigured()}catch(failure:Exception) {
                    if(manualRun!=null)store.finishRoutineRun(manualRun,RoutineStatus.SKIPPED,failure.message)
                    return Result.success()
                }
                if(ai.openbot.android.data.ModelSettingsStore(applicationContext).settings().mode==ModelMode.PROVIDER_API) {
                    val connectivity=applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val capabilities=connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
                    if(capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)!=true)return Result.retry()
                }
                val now=Instant.now()
                val due=store.routines().filter{if(manualId!=null)it.id==manualId else it.enabled&&Instant.parse(it.nextRunAt)<=now}
                for(routine in due) {
                    if(store.checkpoints().any{it.channelId==routine.channelId&&it.phase in listOf(ContinuationPhase.WAITING,ContinuationPhase.QUEUED,ContinuationPhase.RUNNING)}) {
                        if(manualRun!=null)store.finishRoutineRun(manualRun,RoutineStatus.SKIPPED,"A turn is already waiting for approval in this conversation.")
                        continue
                    }
                    if(!routine.enabled||store.bots().none{it.id==routine.botId&&!it.paused}) {
                        if(manualRun!=null)store.finishRoutineRun(manualRun,RoutineStatus.SKIPPED,"The routine or its Bot was paused before the run started.")
                        continue
                    }
                    val run=manualRun?:try{store.startRoutineRun(routine.id,manual=false)}catch(failure:ScheduleRefusedException){store.disableUnschedulable(routine,failure.message.orEmpty());null}catch(_:IllegalArgumentException){null}?:continue
                    try {
                        val outcome=runtime.turn(routine.channelId,frameFiring(routine.instruction),routineRunId=run.id)
                        store.finishRoutineRun(run,if(outcome==ai.openbot.android.inference.TurnOutcome.WAITING)RoutineStatus.WAITING else RoutineStatus.SUCCEEDED)
                    }catch(failure:CancellationException) {
                        withContext(NonCancellable){store.finishRoutineRun(run,RoutineStatus.SKIPPED,"The background run was interrupted.")}
                        throw failure
                    }catch(failure:Exception) {store.finishRoutineRun(run,RoutineStatus.FAILED,failure.message)}
                }
                return Result.success()
            } finally {withContext(NonCancellable){model.close()}}
        }
    }
    companion object {
        fun register(context:Context) {
            val request=PeriodicWorkRequestBuilder<RoutineWorker>(15,TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("openbot-routines",ExistingPeriodicWorkPolicy.KEEP,request)
        }
        fun enqueueManual(context:Context,run:LocalRoutineRun) {
            val request=OneTimeWorkRequestBuilder<RoutineWorker>().setInputData(workDataOf("routineId" to run.routineId,"runId" to run.id)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("openbot-routine-run:${run.id}",ExistingWorkPolicy.KEEP,request)
        }
    }
}
