package ai.openbot.core

import kotlin.math.*

/** CSS Color 4 OKLCH → sRGB, used to carry app/src/theme.css tokens into Compose. */
fun oklchArgb(lightness: Double, chroma: Double, hue: Double, alpha: Double = 1.0): Int {
    val radians = hue * PI / 180
    val a = chroma * cos(radians)
    val b = chroma * sin(radians)
    val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    fun encode(value: Double): Int {
        val srgb = if (value <= 0.0031308) 12.92 * value else 1.055 * value.pow(1.0 / 2.4) - 0.055
        return (srgb.coerceIn(0.0, 1.0) * 255).roundToInt()
    }
    val red = encode(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)
    val green = encode(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s)
    val blue = encode(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
    return ((alpha.coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or (red shl 16) or (green shl 8) or blue
}
