package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.core.Ocr
import com.vasmarfas.card.core.OcrLine
import com.vasmarfas.card.core.OcrPage
import com.vasmarfas.card.tools.media.TextSample
import com.vasmarfas.card.tools.media.VideoText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcrTextTest {
    private fun line(text: String, top: Int, height: Int = 20) = OcrLine(text, 90f, 10, top, 300, top + height)

    @Test
    fun gapsBetweenLinesMakeParagraphs() {
        val page = OcrPage(listOf(line("Первая строка", 0), line("вторая строка", 24), line("Новый абзац", 70), line("", 100)), 400, 200)
        assertEquals("Первая строка\nвторая строка\n\nНовый абзац", OcrText.text(page, joinLines = false))
        assertEquals("Первая строка вторая строка\n\nНовый абзац", OcrText.text(page, joinLines = true))
    }

    @Test
    fun aJumpBackUpStartsTheNextColumn() {
        val page = OcrPage(listOf(line("левая колонка", 0), line("ещё слева", 22), line("правая колонка", 0)), 600, 100)
        assertEquals(2, OcrText.paragraphs(page.lines).size)
    }

    @Test
    fun hyphenatedWordsAreJoinedBack() {
        assertEquals("известный текст", OcrText.join(listOf("из-", "вестный текст")))
        assertEquals("Москва - Петербург", OcrText.join(listOf("Москва -", "Петербург")))
        assertEquals("Юго- Запад", OcrText.join(listOf("Юго-", "Запад")))
        assertEquals("a b", OcrText.join(listOf("a", "", "b")))
    }

    @Test
    fun imagesAreScaledTowardsTesseractSizes() {
        assertEquals(2.0, Ocr.scaleFor(700, 400))
        assertEquals(3.0, Ocr.scaleFor(100, 50))
        assertEquals(1.0, Ocr.scaleFor(2000, 1500))
        assertEquals(0.5, Ocr.scaleFor(7200, 4800))
    }

    @Test
    fun grayFlattensTransparencyOntoWhite() {
        val pixels = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0x00000000, 0xFFFF0000.toInt())
        val gray = Ocr.gray(pixels).map { it.toInt() and 0xFF }
        assertEquals(listOf(0, 255, 255, 76), gray)
    }

    @Test
    fun brightTextKeepsOnlyLightLetters() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(listOf(black, white, white, black), VideoText.brightText(intArrayOf(white, black, 0xFF3060A0.toInt(), 0xFFFFE000.toInt())).toList())
    }

    @Test
    fun similarityIgnoresCaseAndPunctuation() {
        assertEquals(1.0, VideoText.similarity("Привет, мир!", "привет мир"))
        assertTrue(VideoText.similarity("Субтитры к фильму", "Субтитры к филму") > VideoText.SAME_TEXT)
        assertTrue(VideoText.similarity("Первая реплика", "Совсем другое") < 0.5)
        assertEquals(0.0, VideoText.similarity("", "текст"))
    }

    @Test
    fun framesMergeIntoCaptions() {
        val samples = listOf(
            TextSample(0, "Добрый день"),
            TextSample(1000, "Добрый день!"),
            TextSample(2000, "Добрьй день"),
            TextSample(3000, ""),
            TextSample(4000, "Как дела?"),
            TextSample(5000, "Как дела?"),
            TextSample(6000, "Отлично"),
        )
        val spans = VideoText.spans(samples, 1000)
        assertEquals(listOf("Добрый день!", "Как дела?", "Отлично"), spans.map { it.text })
        assertEquals(listOf(0L to 3000L, 4000L to 6000L, 6000L to 7000L), spans.map { it.startMs to it.endMs })
    }

    @Test
    fun exportsSrtAndPlainText() {
        val spans = VideoText.spans(listOf(TextSample(1500, "Один"), TextSample(3_723_000, "Два")), 500)
        assertEquals("1\n00:00:01,500 --> 00:00:02,000\nОдин\n\n2\n01:02:03,000 --> 01:02:03,500\nДва\n", VideoText.srt(spans))
        assertEquals("[00:00:01] Один\n\n[01:02:03] Два", VideoText.plain(spans, withTimes = true))
        assertEquals("Один\n\nДва", VideoText.plain(spans, withTimes = false))
    }
}
