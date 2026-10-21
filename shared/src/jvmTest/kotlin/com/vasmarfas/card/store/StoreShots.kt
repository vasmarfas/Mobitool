package com.vasmarfas.card.store

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import com.vasmarfas.card.App
import com.vasmarfas.card.tools.documents.fixture
import com.vasmarfas.card.tools.documents.pdf.pdf
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.Assume.assumeTrue

// store screenshots of the real app: ./gradlew :shared:jvmTest --tests "*StoreShots*" -PstoreShots=<folder>,
// -PstoreOnly=phone,ipad limits the devices, -PstorePick=01-home,04-qr the shots
@OptIn(ExperimentalTestApi::class)
class StoreShots {
    private val out = System.getProperty("store.shots")?.let(::File)

    class Device(val name: String, val width: Int, val height: Int, val density: Float, val homeRow: Int = 3)

    class Shot(val name: String, val link: String, val dark: Boolean = false, val prepare: SkikoComposeUiTest.(Boolean) -> Unit = {})

    private val devices = listOf(
        Device("phone", 1080, 2340, 3f),
        Device("iphone", 1320, 2868, 3f),
        Device("tablet", 2560, 1600, 2f),
        Device("ipad", 2752, 2064, 2f, homeRow = 4),
        Device("desktop", 2880, 1800, 2f, homeRow = 4),
    )

    // the home screen puts every category in its own section, one full row each: three tiles on tablets, four on
    // the desktop
    private val home = listOf(
        listOf("image-compressor", "video-editor", "photo-editor", "id-photo"),
        listOf("pdf-editor", "photo-to-text", "doc-scanner", "merge-pdf"),
        listOf("qr-generator", "barcode-generator", "color-converter", "image-palette"),
        listOf("password-generator", "totp", "pwned-check", "password-strength"),
        listOf("file-transfer", "speed-test", "connection-check", "wifi-analyzer"),
    )

    private val shots = listOf(
        Shot("01-home", "#my") { ru -> bringToTop(if (ru) "Фото, видео и аудио" else "Photo, video and audio", 12f) },
        Shot("02-pdf", "#tools/pdf-editor") { ru ->
            pick(sampleLease(ru))
            tap(if (ru) "Выбрать PDF" else "Choose a PDF")
            bringToTop(if (ru) "Договор аренды квартиры.pdf" else "Apartment lease.pdf", 90f)
            val page = pageBounds()
            onRoot().performTouchInput { click(Offset(page.left + page.width * 0.4f, page.top + page.height * 0.318f)) }
        },
        Shot("03-transfer", "#tools/file-transfer") { ru ->
            pick(holidayVideo(ru), sampleLease(ru))
            tap(if (ru) "Выбрать файлы" else "Choose files")
            tap(if (ru) "Через интернет" else "Over the internet")
            tap(if (ru) "Создать ссылку" else "Create a link")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("file-transfer?code=", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            bringToTop(if (ru) "Другие способы" else "Other ways", 90f)
            onRoot().performMouseInput { moveTo(Offset.Zero) }
        },
        Shot("04-text", "#tools/photo-to-text") { ru ->
            pick(sample("recipe-${if (ru) "ru" else "en"}.jpg", if (ru) "Рецепт.jpg" else "Recipe.jpg"))
            tap(if (ru) "Выбрать фото, скриншоты или PDF" else "Choose photos, screenshots or PDF")
            waitUntil(timeoutMillis = 90_000) { onAllNodes(hasText(if (ru) "Копировать" else "Copy")).fetchSemanticsNodes().isNotEmpty() }
            tap(if (ru) "Склеивать строки в абзацы" else "Join lines into paragraphs")
            bringToTop(if (ru) "Склеивать строки в абзацы" else "Join lines into paragraphs", -12f)
        },
        Shot("05-scan", "#tools/doc-scanner") { ru ->
            pick(sample("act-${if (ru) "ru" else "en"}.jpg", if (ru) "Акт.jpg" else "Handover.jpg"))
            tap(if (ru) "Выбрать изображения" else "Choose images")
            settle()
            bringToTop(if (ru) "Края" else "Edges", 200f)
        },
        Shot("06-currency", "#tools/currency-converter", dark = true) { ru ->
            waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText(if (ru) "Курсы обновлены" else "Rates updated")).fetchSemanticsNodes().isNotEmpty() }
            if (!ru) {
                onAllNodes(hasText("RUB") and hasClickAction()).onFirst().performClick()
                settle()
                onAllNodes(hasSetTextAction()).onLast().performTextReplacement("Euro")
                onAllNodes(hasSetTextAction()).onLast().performImeAction()
            }
        },
        Shot("07-qr", "#tools/qr-generator") {
            type("https://vasmarfas.com")
            bringToTop("vasmarfas.com", 235f)
        },
        Shot("08-catalog", "#tools"),
        Shot("check-filament-table", "#tools/filament-guide") { ru ->
            tap(if (ru) "Таблица" else "Table")
            bringToTop(if (ru) "Поиск по названию" else "Search by name", 90f)
        },
        Shot("check-filament-detailed", "#tools/filament-guide") { ru ->
            tap(if (ru) "Таблица" else "Table")
            tap(if (ru) "Расширенный" else "Detailed")
            type("PETG")
            bringToTop(if (ru) "Поиск по названию" else "Search by name", 90f)
        },
    )

    private fun seed(device: Device, lang: String, dark: Boolean) {
        MemoryPreferencesFactory.root.reset()
        val prefs = Preferences.userRoot().node("com/vasmarfas/card")
        prefs.put("settings.lang", lang)
        prefs.put("settings.theme", if (dark) "DARK" else "LIGHT")
        prefs.put("settings.dynamic", "false")
        prefs.put("onboarding.status", "DONE")
        prefs.put("tools.favorites", home.flatMap { it.take(device.homeRow) }.joinToString(","))
    }

    private fun SkikoComposeUiTest.settle() {
        repeat(4) {
            waitForIdle()
            mainClock.advanceTimeBy(700)
            Thread.sleep(250)
        }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.tap(text: String) = onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()

    private fun SkikoComposeUiTest.type(text: String) = onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(text)

    // scrolls the page until the node with the text sits a margin below the top, the collapsing bar takes part of
    // every scroll, hence the passes
    private fun SkikoComposeUiTest.bringToTop(text: String, marginDp: Float) {
        repeat(4) {
            settle()
            val top = onAllNodes(hasText(text, substring = true)).onFirst().fetchSemanticsNode().positionInRoot.y
            val delta = top - marginDp * density.density
            if (abs(delta) < 8f) return
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).onFirst()
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        }
    }

    // FileKit keeps the desktop picker, an internal interface, in a lazy: a scene puts a proxy there that hands out a
    // sample file
    private fun pick(vararg files: File) {
        val type = Class.forName("io.github.vinceglb.filekit.dialogs.platform.PlatformFilePicker")
        val picker = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "openFilePicker" -> files.first()
                "openFilesPicker" -> files.toList()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "sample picker"
                else -> null
            }
        }
        val lazy = Class.forName("${type.name}\$Companion").getDeclaredField("current\$delegate").apply { isAccessible = true }.get(null)
        lazy.javaClass.getDeclaredField("_value").apply { isAccessible = true }.set(lazy, picker)
    }

    private fun sampleLease(ru: Boolean): File {
        val fonts = "src/commonMain/composeResources/files/fonts"
        val bytes = pdf { doc ->
            val regular = PDType0Font.load(doc, File("$fonts/MobitoolSerif-Regular.ttf"))
            val bold = PDType0Font.load(doc, File("$fonts/MobitoolSerif-Bold.ttf"))
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                var y = 760f
                fun text(value: String, font: PDFont, size: Float, x: Float) {
                    cs.beginText()
                    cs.setFont(font, size)
                    cs.newLineAtOffset(x, y)
                    cs.showText(value)
                    cs.endText()
                }
                for (line in fixture(if (ru) "lease-ru.txt" else "lease-en.txt").decodeToString().trimEnd().lines()) {
                    when {
                        line.startsWith("# ") -> {
                            text(line.drop(2), bold, 17f, 72f)
                            y -= 30f
                        }
                        line.startsWith("## ") -> {
                            y -= 8f
                            text(line.drop(3), bold, 12f, 72f)
                            y -= 18f
                        }
                        '|' in line -> {
                            val (left, right) = line.split('|')
                            text(left, regular, 11f, 72f)
                            text(right, regular, 11f, 523f - regular.getStringWidth(right) / 1000 * 11f)
                            y -= 26f
                        }
                        else -> {
                            text(line, regular, 11f, 72f)
                            y -= 15.5f
                        }
                    }
                }
            }
        }
        val dir = Files.createTempDirectory("store").toFile()
        return File(dir, if (ru) "Договор аренды квартиры.pdf" else "Apartment lease.pdf").apply { writeBytes(bytes) }
    }

    private fun sample(name: String, shownAs: String): File {
        val source = File(out, "samples/$name")
        check(source.exists()) { "run python store/tools/samples.py $out first" }
        return File(Files.createTempDirectory("store").toFile(), shownAs).apply { writeBytes(source.readBytes()) }
    }

    private fun holidayVideo(ru: Boolean): File {
        val file = File(Files.createTempDirectory("store").toFile(), if (ru) "Отпуск 2026.mp4" else "Vacation 2026.mp4")
        Files.newByteChannel(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use {
            it.position(1_288_490_188)
            it.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
        file.deleteOnExit()
        return file
    }

    // the page is the only pure white area of the editor
    private fun SkikoComposeUiTest.pageBounds(): Rect {
        settle()
        val pixels = captureToImage().toPixelMap()
        var left = pixels.width
        var right = 0
        var top = pixels.height
        var bottom = 0
        for (y in 0 until pixels.height) {
            var count = 0
            var first = -1
            var last = -1
            for (x in 0 until pixels.width) {
                if (pixels[x, y] != Color.White) continue
                count++
                if (first < 0) first = x
                last = x
            }
            if (count < pixels.width / 5) continue
            top = min(top, y)
            bottom = y
            left = min(left, first)
            right = max(right, last)
        }
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    @Test
    fun render() {
        assumeTrue(out != null)
        val only = System.getProperty("store.only")?.split(',')?.toSet()
        val picked = System.getProperty("store.pick")?.split(',')?.toSet()
        for (device in devices.filter { only == null || it.name in only }) {
            for (lang in listOf("RU", "EN")) {
                for (shot in shots.filter { if (picked == null) !it.name.startsWith("check") else it.name in picked }) {
                    seed(device, lang, shot.dark)
                    val file = File(out, "${device.name}/${lang.lowercase()}/${shot.name}.png")
                    // closing the scene can trip the navigation lifecycle, the picture is written by then
                    runCatching { capture(device, shot, lang == "RU", file) }.onFailure { if (!file.exists()) throw it }
                }
            }
        }
    }

    private fun capture(device: Device, shot: Shot, ru: Boolean, file: File) {
        file.delete()
        file.parentFile.mkdirs()
        DesktopComposeUiTest(device.width, device.height, density = Density(device.density), useStandardTestDispatcherForComposition = false).runTest {
            setContent { App(link = "https://vasmarfas.com/${shot.link}") }
            settle()
            runCatching { shot.prepare(this, ru) }.onFailure {
                File(file.parentFile, "${shot.name}.txt").writeText(it.toString() + "\n\n" + onRoot().printToString())
            }
            settle()
            ImageIO.write(captureToImage().toAwtImage(), "png", file)
        }
    }

    companion object {
        init {
            if (System.getProperty("store.shots") != null) {
                System.setProperty("java.util.prefs.PreferencesFactory", MemoryPreferencesFactory::class.java.name)
            }
        }
    }
}
