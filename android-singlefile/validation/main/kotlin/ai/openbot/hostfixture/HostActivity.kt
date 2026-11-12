package ai.openbot.hostfixture

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import ai.openbot.agentstudio.AgentStudioScreen

// Only a validation host. This is not part of the delivered Agent Studio feature.
class HostApplication: Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration get()=androidx.work.Configuration.Builder().build()
}
class HostActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) { AgentStudioScreen(onBack={finish()}) } }
    }
}
