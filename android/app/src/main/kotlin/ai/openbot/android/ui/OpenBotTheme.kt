package ai.openbot.android.ui

import ai.openbot.android.R
import ai.openbot.core.oklchArgb
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun css(
    l: Double,
    c: Double,
    h: Double,
    alpha: Double = 1.0,
) = Color(oklchArgb(l, c, h, alpha))

@Immutable data class OpenBotPalette(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val muted: Color,
    val mutedForeground: Color,
    val border: Color,
    val sidebar: Color,
    val sidebarAccent: Color,
    val ring: Color,
    val destructive: Color,
    val success: Color,
)

// Values copied from app/src/theme.css, not substituted with Material's blue palette.
private val light =
    OpenBotPalette(
        css(.993, .002, 286.4),
        css(.108, .015, 220.9),
        Color.White,
        css(.956, .005, 286.3),
        css(.506, .013, 285.9),
        css(.894, .014, 286.1),
        css(.948, .011, 286.2),
        css(.832, .085, 281.4, .30),
        css(.644, .167, 276.1),
        css(.504, .178, 22.9),
        css(.514, .1, 167.2),
    )
private val dark =
    OpenBotPalette(
        css(.182, .023, 296.6),
        css(.956, .005, 286.3),
        css(.212, .028, 297.1),
        css(.253, .032, 297.6),
        css(.69, .017, 285.9),
        css(.28, .035, 298.6),
        css(.156, .017, 295.8),
        css(.832, .085, 281.4, .12),
        css(.713, .151, 277.7),
        css(.792, .121, 16.4),
        css(.832, .13, 170.1),
    )
val LocalOpenBotPalette = staticCompositionLocalOf { light }

@OptIn(ExperimentalTextApi::class)
private val originalFont =
    FontFamily(
        listOf(400, 500, 600, 700).map { weight ->
            Font(R.font.atkinson_hyperlegible_next, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
        },
    )

@Composable fun OpenBotTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val night = darkTheme ?: isSystemInDarkTheme()
    val p = if (night) dark else light
    val scheme =
        (if (night) darkColorScheme() else lightColorScheme()).copy(
            primary = p.foreground,
            onPrimary = p.card,
            primaryContainer = p.muted,
            onPrimaryContainer = p.foreground,
            secondary = p.muted,
            onSecondary = p.foreground,
            secondaryContainer = p.muted,
            onSecondaryContainer = p.foreground,
            background = p.background,
            onBackground = p.foreground,
            surface = p.card,
            onSurface = p.foreground,
            surfaceVariant = p.muted,
            onSurfaceVariant = p.mutedForeground,
            outline = p.border,
            outlineVariant = p.border,
            surfaceContainer = p.card,
            surfaceContainerHigh = p.card,
            surfaceContainerHighest = p.muted,
            error = p.destructive,
            errorContainer = p.destructive.copy(alpha = .08f),
            onErrorContainer = p.destructive,
        )
    val body = TextStyle(fontFamily = originalFont, fontSize = 13.125.sp, lineHeight = 18.75.sp)
    val typography =
        Typography(
            bodyLarge = body.copy(fontSize = 15.sp, lineHeight = 22.5.sp),
            bodyMedium = body,
            bodySmall = body.copy(fontSize = 11.25.sp, lineHeight = 15.sp),
            titleLarge = body.copy(fontSize = 22.5.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.5625).sp),
            titleMedium = body.copy(fontSize = 16.875.sp, lineHeight = 26.25.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = body.copy(fontSize = 15.sp, lineHeight = 22.5.sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = body.copy(fontSize = 22.5.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            labelLarge = body.copy(fontWeight = FontWeight.Medium),
            labelMedium = body.copy(fontSize = 11.25.sp, lineHeight = 15.sp),
            labelSmall = body.copy(fontSize = 10.sp, lineHeight = 12.sp),
        )
    CompositionLocalProvider(LocalOpenBotPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(16.dp)),
            content = content,
        )
    }
}
