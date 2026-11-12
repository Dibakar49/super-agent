package ai.openbot.android.background

import android.content.Context
import androidx.work.*
import ai.openbot.android.data.*
import ai.openbot.android.inference.*
import ai.openbot.core.*
import kotlinx.coroutines.*

class GoalWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val runId=inputData.getString("runId")?:return Result.failure()
        LocalStore(applicationContext).use{store->
            val goals=GoalStore(store)
            val existing=goals.run(runId)?:return Result.success()
            // WorkManager can restart its own interrupted job. Never repeat a turn already written.
            if(existing.status==GoalRunStatus.RUNNING) {
                val message=existing.turnId?.let{id->store.messages(goals.get(existing.goalId).channelId).find{it.id==id}}
                when(message?.state) {
                    MessageState.COMPLETE->goals.finish(runId,GoalRunStatus.SUCCEEDED,message.text)
                    MessageState.WAITING->goals.finish(runId,GoalRunStatus.WAITING)
                    null->goals.requeue(runId)
                    else->{
                        store.saveMessage(message.copy(state=MessageState.INTERRUPTED,text="This goal run was interrupted. Check its saved actions before starting another run."))
                        goals.finish(runId,GoalRunStatus.SKIPPED,error="The previous process ended before the turn finished.")
                    }
                }
                if(message!=null)return Result.success()
            }
            if(goals.run(runId)?.status!=GoalRunStatus.QUEUED)return Result.success()
            val goal=goals.get(existing.goalId)
            if(store.checkpoints().any{it.channelId==goal.channelId&&it.phase in listOf(ContinuationPhase.WAITING,ContinuationPhase.QUEUED,ContinuationPhase.RUNNING)})return Result.retry()
            val run=goals.claim(runId)?:return Result.success()
            val model=LocalModel(applicationContext)
            try {
                val outcome=AgentRuntime(applicationContext,store,model).turn(goal.channelId,frameGoal(goal,run.id),goalRunId=run.id)
                val reply=goals.run(runId)?.turnId?.let{id->store.messages(goal.channelId).find{it.id==id}?.text}
                goals.finish(runId,if(outcome==TurnOutcome.WAITING)GoalRunStatus.WAITING else GoalRunStatus.SUCCEEDED,reply.takeIf{outcome==TurnOutcome.COMPLETED})
                return Result.success()
            }catch(waiting:ConversationWaitingException){goals.requeue(runId);return Result.retry()}
            catch(failure:CancellationException){
                if(goals.run(runId)?.turnId==null)goals.requeue(runId)
                else goals.finish(runId,GoalRunStatus.SKIPPED,error="The goal run was interrupted.")
                throw failure
            }catch(failure:Exception) {
                goals.finish(runId,if(goals.get(goal.id).status==GoalStatus.ACTIVE)GoalRunStatus.FAILED else GoalRunStatus.SKIPPED,error=failure.message)
                return Result.success()
            }finally{withContext(NonCancellable){model.close();enqueueQueued(applicationContext,store)}}
        }
    }
    companion object {
        fun enqueueQueued(context:Context,store:LocalStore) {
            val provider=ModelSettingsStore(context).settings().mode==ModelMode.PROVIDER_API
            GoalStore(store).queuedRuns().forEach{run->
                val request=OneTimeWorkRequestBuilder<GoalWorker>().setInputData(workDataOf("runId" to run.id))
                    .setConstraints(Constraints.Builder().apply{if(provider)setRequiredNetworkType(NetworkType.CONNECTED)}.build()).build()
                WorkManager.getInstance(context).enqueueUniqueWork("openbot-goal-run:${run.id}",ExistingWorkPolicy.KEEP,request)
            }
        }
    }
}
