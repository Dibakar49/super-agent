package ai.openbot.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

internal class SourceDialogs {
    val entries = mutableStateListOf<Entry>()

    class Entry(
        val id: Any,
        val content: @Composable () -> Unit,
    )
}

internal val LocalSourceDialogs = staticCompositionLocalOf<SourceDialogs?> { null }
internal val LocalSourceDialogOpen = staticCompositionLocalOf { false }

/** Dialogs cover the whole app, including navigation, with underlying accessibility hidden. */
@Composable internal fun SourceDialogHost(content: @Composable () -> Unit) {
    val host = remember { SourceDialogs() }
    val open = host.entries.isNotEmpty()
    CompositionLocalProvider(LocalSourceDialogs provides host, LocalSourceDialogOpen provides open) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(if (open)Modifier.clearAndSetSemantics {} else Modifier)) { content() }
            host.entries.lastOrNull()?.let { entry ->
                key(entry.id) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding()) {
                        entry.content()
                    }
                }
            }
        }
    }
}
