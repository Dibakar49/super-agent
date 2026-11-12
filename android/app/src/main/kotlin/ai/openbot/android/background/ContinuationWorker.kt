package ai.openbot.android.background

import android.content.Context
import androidx.work.*
import ai.openbot.android.data.LocalStore
import ai.openbot.android.data.GoalStore
import ai.openbot.android.inference.*
import ai.openbot.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ContinuationWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val turnId=inputData.getString("turnId")?:return Result.failure()
        LocalStore(applicationContext).use{store->
            val checkpoint=store.checkpoints().firstOrNull{it.turnId==turnId&&it.phase==ContinuationPhase.QUEUED}?:return Result.success()
            val model=LocalModel(applicationContext)
            try {
                val goals=GoalStore(store)
                checkpoint.goalRunId?.let(goals::resumeWaiting)
                checkpoint.routineRunId?.let(store::resumeRoutineRun)
                val result=AgentRuntime(applicationContext,store,model).resume(turnId)
                checkpoint.routineRunId?.let(store::routineRun)?.let{run->store.finishRoutineRun(run,if(result==TurnOutcome.WAITING)RoutineStatus.WAITING else RoutineStatus.SUCCEEDED)}
                checkpoint.goalRunId?.let{id->
                    val reply=store.messages(checkpoint.channelId).find{it.id==turnId}?.text
                    goals.finish(id,if(result==TurnOutcome.WAITING)GoalRunStatus.WAITING else GoalRunStatus.SUCCEEDED,reply.takeIf{result==TurnOutcome.COMPLETED})
                }
                return Result.success()
            }catch(failure:CancellationException) {
                store.checkpoints().find{it.turnId==turnId}?.let{store.interruptContinuation(it,"The continuation was interrupted.",true)}
                checkpoint.goalRunId?.let{GoalStore(store).finish(it,GoalRunStatus.SKIPPED,error="The continuation was interrupted.")}
                checkpoint.routineRunId?.let(store::routineRun)?.let{store.finishRoutineRun(it,RoutineStatus.SKIPPED,"The continuation was interrupted.")}
                throw failure
            }catch(failure:Exception) {
                store.checkpoints().find{it.turnId==turnId}?.let{store.interruptContinuation(it,"Could not continue this turn. ${failure.message.orEmpty()}",false)}
                checkpoint.goalRunId?.let{GoalStore(store).finish(it,GoalRunStatus.FAILED,error=failure.message)}
                checkpoint.routineRunId?.let(store::routineRun)?.let{store.finishRoutineRun(it,RoutineStatus.FAILED,failure.message)}
                return Result.success() // Failure is final and visible; external effects are never retried blindly.
            }finally{withContext(NonCancellable){model.close()}}
        }
    }
    companion object {
        fun enqueue(context:Context,turnId:String) {
            val request=OneTimeWorkRequestBuilder<ContinuationWorker>().setInputData(workDataOf("turnId" to turnId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("openbot-continuation:$turnId",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        }
    }
}

/** Recover process death under the same execution gate used by live turns. Pending decisions survive. */
class RecoveryWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        AgentGate.mutex.lock()
        try {
            LocalStore(applicationContext).use{store->
                store.recoverInterruptedMessages();store.recoverContinuations();store.reapRoutineRuns();ai.openbot.android.data.AttachmentStore(applicationContext,store).reap()
                store.checkpoints().filter{it.phase==ContinuationPhase.QUEUED}.forEach{ContinuationWorker.enqueue(applicationContext,it.turnId)}
                GoalWorker.enqueueQueued(applicationContext,store)
                ai.openbot.android.notifications.LocalNotifications.sync(applicationContext,store)
            }
        }finally{AgentGate.mutex.unlock()}
        return Result.success()
    }
    companion object {
        fun enqueue(context:Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("openbot-recover",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<RecoveryWorker>().build())
        }
    }
}
