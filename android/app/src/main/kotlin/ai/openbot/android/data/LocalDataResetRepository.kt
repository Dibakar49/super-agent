package ai.openbot.android.data

import ai.openbot.android.notifications.LocalNotifications
import android.content.Context
import java.io.File

/** Caller cancels workers and holds AgentGate so no active turn can recreate deleted data. */
class LocalDataResetRepository(
    private val context: Context,
    private val store: LocalStore,
) {
    fun clear() {
        store.clearUserData()
        ModelSettingsStore(context).clear()
        TranscriptionSettingsStore(context).clear()
        ConnectionSecrets(context).clear()
        LocalNotifications.clear(context)
        for (directory in listOf(File(context.filesDir, "attachments"), File(context.cacheDir, "dictation"))) {
            check(!directory.exists() || directory.deleteRecursively()) { "Some attachment or dictation files could not be removed. Try clearing local data again." }
        }
    }
}
