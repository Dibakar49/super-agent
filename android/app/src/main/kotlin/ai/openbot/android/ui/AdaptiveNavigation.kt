package ai.openbot.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

internal val LocalExpandedNavigation = staticCompositionLocalOf { false }

/** Keep navigation visible on tablets/landscape and keep long chat lines readable. */
@Composable internal fun AdaptiveNavigation(
    drawerState: DrawerState,
    gesturesEnabled: Boolean,
    sidebar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalOpenBotPalette.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        CompositionLocalProvider(LocalExpandedNavigation provides expanded) {
            if (expanded) {
                PermanentNavigationDrawer(drawerContent = {
                    PermanentDrawerSheet(
                        Modifier.width(288.dp),
                        drawerShape = RectangleShape,
                        drawerContainerColor = palette.sidebar,
                        drawerTonalElevation = 0.dp,
                    ) { sidebar() }
                }) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 1000.dp).fillMaxSize()) { content() }
                    }
                }
            } else {
                ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = gesturesEnabled, drawerContent = {
                    ModalDrawerSheet(
                        Modifier.width(288.dp),
                        drawerShape = RectangleShape,
                        drawerContainerColor = palette.sidebar,
                        drawerTonalElevation = 0.dp,
                    ) { sidebar() }
                }, content = content)
            }
        }
    }
}
