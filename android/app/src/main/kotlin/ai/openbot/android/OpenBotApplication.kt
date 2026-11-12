package ai.openbot.android

import android.app.Application
import ai.openbot.android.background.RoutineWorker

class OpenBotApplication:Application(),androidx.work.Configuration.Provider {
    override val workManagerConfiguration:androidx.work.Configuration
        get()=androidx.work.Configuration.Builder().build()
    override fun onCreate(){
        super.onCreate();ai.openbot.android.inference.AndroidAudioRecording.cleanPreviousProcess(this);RoutineWorker.register(this);ai.openbot.android.background.RecoveryWorker.enqueue(this)
        ai.openbot.android.notifications.LocalNotifications.createChannel(this)
    }
}
