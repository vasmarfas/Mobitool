package com.vasmarfas.card.tools.design

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppIconsTest {
    @Test
    fun densitiesFollowTheAndroidBuckets() {
        assertEquals(listOf(108, 162, 216, 324, 432), AppIcons.densities.map { (AppIcons.CANVAS_DP * it.second).toInt() })
    }

    @Test
    fun contentsJsonPointsAtFilesOfTheRightSide() {
        val root = Json.parseToJsonElement(AppIcons.contentsJson()).jsonObject
        val images = root.getValue("images").jsonArray.map { it.jsonObject }
        assertEquals(18, images.size)
        val pro = images.first { it.getValue("size").jsonPrimitive.content == "83.5x83.5" }
        assertEquals("Icon-167.png", pro.getValue("filename").jsonPrimitive.content)
        assertEquals("2x", pro.getValue("scale").jsonPrimitive.content)
        val store = images.first { it.getValue("idiom").jsonPrimitive.content == "ios-marketing" }
        assertEquals("Icon-1024.png", store.getValue("filename").jsonPrimitive.content)
        assertEquals("xcode", root.getValue("info").jsonObject.getValue("author").jsonPrimitive.content)
    }

    @Test
    fun adaptiveIconHasAllThreeLayers() {
        listOf("<background", "<foreground", "<monochrome").forEach { assertTrue(it in AppIcons.adaptiveXml, it) }
        assertTrue("#326773" in AppIcons.colorXml(Rgba(0x32, 0x67, 0x73)))
    }

    @Test
    fun silhouetteKeepsTheAlphaOfATransparentPicture() {
        val shape = AppIcons.silhouette(intArrayOf(0x00123456, 0x80ABCDEF.toInt(), 0xFF000000.toInt()))
        assertContentEquals(intArrayOf(0x00FFFFFF, 0x80FFFFFF.toInt(), 0xFFFFFFFF.toInt()), shape)
    }

    @Test
    fun silhouetteOfAnOpaquePictureIsWhatDiffersFromTheCorner() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        assertContentEquals(intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0x00FFFFFF), AppIcons.silhouette(intArrayOf(white, black, white)))
        assertContentEquals(intArrayOf(0x00FFFFFF, 0x00FFFFFF), AppIcons.silhouette(intArrayOf(white, white)))
    }
}
