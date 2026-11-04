package com.vasmarfas.card.tools.design

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vasmarfas.card.core.imageBitmapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class AppIconsRenderTest {
    init {
        registerSkikoComposeImplementation()
    }

    private val logo = imageBitmapOf(IntArray(64 * 48) { 0xFFFF0000.toInt() }, 64, 48)
    private val files = AppIcons.files(logo, AppIcons.SAFE_DP, Rgba(0x32, 0x67, 0x73)).associate { it.path to it.bytes }

    private fun side(path: String): Int {
        val png = files.getValue(path)
        return ((png[18].toInt() and 0xFF) shl 8) or (png[19].toInt() and 0xFF)
    }

    private fun colorType(path: String): Int = files.getValue(path)[25].toInt()

    @Test
    fun eachDensityGetsItsSides() {
        assertEquals(108, side("android/res/mipmap-mdpi/ic_launcher_foreground.png"))
        assertEquals(432, side("android/res/mipmap-xxxhdpi/ic_launcher_monochrome.png"))
        assertEquals(48, side("android/res/mipmap-mdpi/ic_launcher.png"))
        assertEquals(192, side("android/res/mipmap-xxxhdpi/ic_launcher.png"))
        assertEquals(512, side("android/ic_launcher-playstore.png"))
    }

    @Test
    fun everyIosIconInTheManifestExists() {
        AppIcons.iosIcons.forEach { assertEquals(it.side, side("ios/AppIcon.appiconset/Icon-${it.side}.png")) }
        assertTrue("ios/AppIcon.appiconset/Contents.json" in files)
    }

    @Test
    fun onlyTheLayersKeepAnAlphaChannel() {
        assertEquals(6, colorType("android/res/mipmap-hdpi/ic_launcher_foreground.png"))
        assertEquals(2, colorType("ios/AppIcon.appiconset/Icon-1024.png"))
        assertEquals(2, colorType("android/ic_launcher-playstore.png"))
    }

    @Test
    fun faviconHoldsThreeSizes() {
        val ico = files.getValue("web/favicon.ico")
        assertEquals(1, ico[2].toInt())
        assertEquals(3, ico[4].toInt())
        assertEquals(180, side("web/apple-touch-icon.png"))
    }
}
