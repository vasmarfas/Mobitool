package com.vasmarfas.card.tools.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.imageBitmapOf
import com.vasmarfas.card.core.limitedTo
import com.vasmarfas.card.core.pixels
import com.vasmarfas.card.core.scaled
import com.vasmarfas.card.tools.media.ImageTarget
import com.vasmarfas.card.tools.media.Png
import com.vasmarfas.card.tools.media.encodeImage
import com.vasmarfas.card.tools.media.squared
import kotlin.math.abs

class AppIconFile(val path: String, val bytes: ByteArray)

class IosIcon(val idiom: String, val points: Double, val scale: Int) {
    val side: Int get() = (points * scale).toInt()
}

object AppIcons {
    const val CANVAS_DP = 108
    const val SAFE_DP = 66
    private const val VISIBLE_DP = 72
    private const val LEGACY_DP = 48
    private const val LAYER_SIDE = 432
    private const val MASTER_SIDE = 1024
    private const val IOS_SET = "ios/AppIcon.appiconset"

    val densities = listOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)

    val iosIcons = listOf(
        IosIcon("iphone", 20.0, 2), IosIcon("iphone", 20.0, 3), IosIcon("iphone", 29.0, 2), IosIcon("iphone", 29.0, 3),
        IosIcon("iphone", 40.0, 2), IosIcon("iphone", 40.0, 3), IosIcon("iphone", 60.0, 2), IosIcon("iphone", 60.0, 3),
        IosIcon("ipad", 20.0, 1), IosIcon("ipad", 20.0, 2), IosIcon("ipad", 29.0, 1), IosIcon("ipad", 29.0, 2),
        IosIcon("ipad", 40.0, 1), IosIcon("ipad", 40.0, 2), IosIcon("ipad", 76.0, 1), IosIcon("ipad", 76.0, 2),
        IosIcon("ipad", 83.5, 2), IosIcon("ios-marketing", 1024.0, 1),
    )

    val adaptiveXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
            <background android:drawable="@color/ic_launcher_background" />
            <foreground android:drawable="@mipmap/ic_launcher_foreground" />
            <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
        </adaptive-icon>
    """.trimIndent() + "\n"

    fun colorXml(background: Rgba): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <color name="ic_launcher_background">${background.hex()}</color>
        </resources>
    """.trimIndent() + "\n"

    fun contentsJson(): String = iosIcons.joinToString(
        separator = ",\n",
        prefix = "{\n  \"images\" : [\n",
        postfix = "\n  ],\n  \"info\" : {\n    \"author\" : \"xcode\",\n    \"version\" : 1\n  }\n}\n",
    ) {
        val points = it.points.fmt(1)
        "    {\n      \"filename\" : \"Icon-${it.side}.png\",\n      \"idiom\" : \"${it.idiom}\",\n" +
            "      \"scale\" : \"${it.scale}x\",\n      \"size\" : \"${points}x$points\"\n    }"
    }

    // a themed icon takes only the alpha of this layer. A picture without transparency has no shape in its
    // alpha, so the shape becomes whatever differs from the colour of its corner
    fun silhouette(pixels: IntArray): IntArray {
        if (pixels.any { it ushr 24 != 255 }) return IntArray(pixels.size) { (pixels[it] ushr 24 shl 24) or 0xFFFFFF }
        val corner = luminance(pixels[0])
        val distance = IntArray(pixels.size) { abs(luminance(pixels[it]) - corner) }
        val peak = distance.max().coerceAtLeast(1)
        return IntArray(pixels.size) { (distance[it] * 255 / peak shl 24) or 0xFFFFFF }
    }

    private fun luminance(p: Int): Int = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    // a launcher mask shows the inner 72 dp of the 108 dp layers, flat icons keep the same proportions
    fun icon(source: ImageBitmap, side: Int, logoDp: Int, background: Color): ImageBitmap =
        squared(source, side, side * logoDp / VISIBLE_DP, background)

    fun files(source: ImageBitmap, logoDp: Int, background: Rgba): List<AppIconFile> {
        val master = icon(source, MASTER_SIDE, logoDp, background.toColor())
        val logo = LAYER_SIDE * logoDp / CANVAS_DP
        val foreground = squared(source, LAYER_SIDE, logo)
        val shape = source.limitedTo(LAYER_SIDE)
        val monochrome = squared(imageBitmapOf(silhouette(shape.pixels()), shape.width, shape.height), LAYER_SIDE, logo)
        return buildList {
            add(AppIconFile("android/res/mipmap-anydpi-v26/ic_launcher.xml", adaptiveXml.encodeToByteArray()))
            add(AppIconFile("android/res/values/ic_launcher_background.xml", colorXml(background).encodeToByteArray()))
            densities.forEach { (name, scale) ->
                val layer = (CANVAS_DP * scale).toInt()
                add(png("android/res/mipmap-$name/ic_launcher.png", master, (LEGACY_DP * scale).toInt()))
                add(png("android/res/mipmap-$name/ic_launcher_foreground.png", foreground, layer))
                add(png("android/res/mipmap-$name/ic_launcher_monochrome.png", monochrome, layer))
            }
            add(png("android/ic_launcher-playstore.png", master, 512))
            add(AppIconFile("$IOS_SET/Contents.json", contentsJson().encodeToByteArray()))
            iosIcons.map { it.side }.distinct().forEach { add(png("$IOS_SET/Icon-$it.png", master, it)) }
            add(AppIconFile("web/favicon.ico", encodeImage(master, ImageTarget.ICO, icons = listOf(16, 32, 48))))
            add(png("web/apple-touch-icon.png", master, 180))
            add(png("web/icon-192.png", master, 192))
            add(png("web/icon-512.png", master, 512))
        }
    }

    // Png.encode writes no alpha channel for an opaque picture, App Store Connect rejects icons that have one
    private fun png(path: String, image: ImageBitmap, side: Int): AppIconFile =
        AppIconFile(path, Png.encode(image.scaled(side, side).pixels(), side, side))
}
