package ai.openbot.android.ui

import android.graphics.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import kotlin.math.abs
import kotlin.math.pow

/** Kotlin rendering of boring-avatars' default marble variant used by AbstractAvatar.tsx. */
@Composable internal fun BotAvatar(
    name: String,
    seed: String,
    size: Dp = 28.dp,
) {
    val bitmap = remember(seed) { marble(seed).asImageBitmap() }
    Image(bitmap, name, Modifier.size(size))
}

private fun marble(seed: String): Bitmap {
    val colors = listOf("#92A1C6", "#146A7C", "#F0AB3D", "#C271B4", "#C20D90")
    val hash = abs(seed.hashCode().toLong())

    fun digit(
        value: Long,
        position: Int,
    ) = (value / 10.0.pow(position)).toLong() % 10

    fun unit(
        value: Long,
        range: Int,
        position: Int? = null,
    ): Float {
        val number = (value % range).toFloat()
        return if (position != null && digit(value, position) % 2 == 0L)-number else number
    }
    val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val clip = Path().apply { addCircle(40f, 40f, 40f, Path.Direction.CW) }
    canvas.clipPath(clip)
    canvas.drawColor(Color.parseColor(colors[(hash % 5).toInt()]))
    val scale = 1.2f + unit(hash * 3, 4) / 10f
    val paths =
        listOf(
            "M32.414 59.35L50.376 70.5H72.5v-71H33.728L26.5 13.381l19.057 27.08L32.414 59.35z",
            "M22.216 24L0 46.75l14.108 38.129L78 86l-3.081-59.276-22.378 4.005 12.972 20.186-23.35 27.395L22.215 24z",
        )
    for (index in 1..2) {
        val value = hash * (index + 1)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(colors[((hash + index) % 5).toInt()])
                maskFilter = BlurMaskFilter(11.25f, BlurMaskFilter.Blur.NORMAL)
                if (index == 2)xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
            }
        canvas.save()
        canvas.translate(unit(value, 8, 1), unit(value, 8, 2))
        canvas.rotate(unit(value, 360, 1), 40f, 40f)
        canvas.scale(scale, scale)
        canvas.drawPath(PathParser.createPathFromPathData(paths[index - 1])!!, paint)
        canvas.restore()
    }
    return bitmap
}
