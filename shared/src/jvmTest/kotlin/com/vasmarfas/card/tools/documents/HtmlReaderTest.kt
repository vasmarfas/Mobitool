package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.tools.developer.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlReaderTest {
    private fun read(html: String): Doc = Documents.read(html.encodeToByteArray(), DocFormat.HTML)

    private fun blocks(html: String): String = dumpBlocks(read(html).blocks)

    @Test
    fun metadataAndSkippedParts() {
        val doc = read(
            """
            <!DOCTYPE html>
            <!-- comment <p>not text</p> -->
            <html lang="ru"><head>
            <meta name="author" content="Автор">
            <title>Заголовок &amp; страница</title>
            <style>p { color: red }</style>
            <script>document.write("<p>скрипт</p>")</script>
            </head><body>
            <noscript><p>без скриптов</p></noscript>
            <template><p>шаблон</p></template>
            <p>текст</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals("Заголовок & страница", doc.title)
        assertEquals("Автор", doc.author)
        assertEquals("ru", doc.language)
        assertEquals("P \"текст\"\n", dumpBlocks(doc.blocks))
    }

    @Test
    fun impliedEndTagsAndLooseMarkup() {
        val html = """
            <P align=center>первый
            <p>второй <B>жирный<I>и курсив</b> хвост</i>
            <ul><li>один<li>два<ul><li>вложенный</ul><li>три</ul>
            <table><tr><td>a<td>b<tr><td colspan=2>c</table>
            <dl><dt>термин<dd>определение</dl>
        """.trimIndent()
        assertEquals(
            """
            P[center] "первый"
            P "второй " {b}"жирный" {b,i}"и курсив" " хвост"
            UL
              ITEM
                P "один"
              ITEM
                P "два"
                UL
                  ITEM
                    P "вложенный"
              ITEM
                P "три"
            TABLE header=0
              ROW
                CELL
                  P "a"
                CELL
                  P "b"
              ROW
                CELL span=2
                  P "c"
            P {b}"термин"
            P "определение"

            """.trimIndent(),
            blocks(html),
        )
    }

    @Test
    fun entitiesAndWhitespace() {
        assertEquals(
            "P \"a b${Char(0xA0)}c © «x» — … ← → × ✓ € é ü 😀 & &unknown; ¬ ©2\"\n",
            blocks("<p>  a \n\t b&nbsp;c &copy; &laquo;x&raquo; &mdash; &hellip; &larr; &rarr; &times; &check; &#8364; &eacute; &uuml; &#x1F600; &amp; &unknown; &not &copy2</p>"),
        )
        assertEquals("P \"one\" \\n \"two\"\n", blocks("<p>one <br> two<br><br></p>"))
        assertEquals("CODE \"  keep\\n    spaces\"\n", blocks("<pre>\n  keep\n    spaces\n</pre>"))
        assertEquals("P {c}\"a  b\"\n", blocks("<p><code style=\"white-space: pre\">a  b</code></p>"))
    }

    @Test
    fun inlineFormattingAndLinks() {
        val html = """
            <p><strong>b</strong><em>i</em><u>u</u><ins>ins</ins><s>s</s><del>del</del><code>c</code><kbd>k</kbd>
            <sub>sub</sub><sup>sup</sup><mark>mark</mark><q>цитата</q>
            <a href="https://example.com/">абсолютная</a> <a href="page.html">относительная</a> <a href="#x">якорь</a>
            <a href="javascript:alert(1)">скрипт</a> <a href="//cdn.example.com/x">протокол</a></p>
            <p><span style="font-weight: 700">вес</span><span style="font-style:italic">наклон</span><span style="text-decoration: underline line-through">линии</span>
            <b><span style="font-weight: normal">не жирный</span></b><span style="font-family: 'Courier New', monospace">моно</span>
            <font face="Consolas">шрифт</font><span style="vertical-align: super">верх</span></p>
        """.trimIndent()
        assertEquals(
            "P {b}\"b\" {i}\"i\" {u}\"uins\" {s}\"sdel\" {c}\"ck\" \" \" {sub}\"sub\" {sup}\"sup\" \"mark“цитата” \" {link=https://example.com/}\"абсолютная\" " +
                "\" относительная якорь скрипт \" {link=https://cdn.example.com/x}\"протокол\"\n" +
                "P {b}\"вес\" {i}\"наклон\" {u,s}\"линии\" \" не жирный\" {c}\"моно\" \" \" {c}\"шрифт\" {sup}\"верх\"\n",
            blocks(html),
        )
    }

    @Test
    fun stylesheetClasses() {
        val html = """
            <html><head><style>
            /* EPUB-style classes */
            .it { font-style: italic } span.b, .strong { font-weight: bold }
            p.center { text-align: center } .hidden { display: none }
            @media print { .it { font-style: normal } }
            div p { font-weight: bold }
            </style></head><body>
            <p class="center">по центру <span class="it">курсив</span> <span class="b">жирный</span> <em class="strong">оба</em></p>
            <p class="hidden">скрыто</p><div><p>не жирный: сложные селекторы не применяются</p></div>
            </body></html>
        """.trimIndent()
        assertEquals(
            "P[center] \"по центру \" {i}\"курсив\" \" \" {b}\"жирный\" \" \" {b,i}\"оба\"\nP \"не жирный: сложные селекторы не применяются\"\n",
            blocks(html),
        )
    }

    @Test
    fun blocks() {
        val html = """
            <h1>Первый</h1><h2><b>Второй</b></h2><h7>не заголовок</h7>
            <div align="right">справа<div style="text-align: justify">по ширине</div></div>
            <center>центр</center>
            <blockquote><p>цитата</p><blockquote>вложенная</blockquote></blockquote>
            <hr>
            <div style="page-break-after: always"></div>
            <ol start="3" type="a"><li>три</li><li value="7">семь</li></ol>
            <figure><img src="https://example.com/x.png" alt="внешняя"><figcaption>подпись</figcaption></figure>
        """.trimIndent()
        assertEquals(
            """
            H1 "Первый"
            H2 "Второй"
            P "не заголовок"
            P[end] "справа"
            P[justify] "по ширине"
            P[center] "центр"
            QUOTE
              P "цитата"
              QUOTE
                P "вложенная"
            RULE
            PAGEBREAK
            OL start=3
              ITEM
                P "три"
              ITEM
                P "семь"
            P "внешняя"
            P[center] "подпись"

            """.trimIndent(),
            blocks(html),
        )
    }

    @Test
    fun tables() {
        val html = """
            <table><caption>Подпись</caption>
            <thead><tr><th>A</th><th>B</th><th>C</th></tr></thead>
            <tfoot><tr><td colspan="3">итого</td></tr></tfoot>
            <tbody><tr><td rowspan="2">два ряда</td><td>1</td><td>2</td></tr>
            <tr><td>3</td><td>4</td></tr></tbody></table>
            <table><tr><th>только</th><th>th</th></tr><tr><td>x</td><td><table><tr><td>вложенная</td></tr></table></td></tr></table>
        """.trimIndent()
        assertEquals(
            """
            P[center] "Подпись"
            TABLE header=1
              ROW
                CELL
                  P "A"
                CELL
                  P "B"
                CELL
                  P "C"
              ROW
                CELL
                  P "два ряда"
                CELL
                  P "1"
                CELL
                  P "2"
              ROW
                CELL
                CELL
                  P "3"
                CELL
                  P "4"
              ROW
                CELL span=3
                  P "итого"
            TABLE header=1
              ROW
                CELL
                  P "только"
                CELL
                  P "th"
              ROW
                CELL
                  P "x"
                CELL
                  TABLE header=0
                    ROW
                      CELL
                        P "вложенная"

            """.trimIndent(),
            blocks(html),
        )
    }

    @Test
    fun embeddedPictures() {
        val png = TestImages.png(20, 10)
        val html = "<p>до<img src=\"data:image/png;base64,${encodeBase64(png)}\" alt=\"картинка\" width=\"40\">после</p>" +
            "<img src=\"data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'/%3E\">"
        val doc = read(html)
        assertEquals(4, doc.blocks.size)
        assertEquals("P \"до\"", dumpBlocks(listOf(doc.blocks[0])).trim())
        val picture = doc.blocks[1] as Block.Picture
        assertTrue(picture.bytes.contentEquals(png))
        assertEquals(30f, picture.widthPt)
        assertEquals(15f, picture.heightPt)
        assertEquals("картинка", picture.alt)
        assertEquals("P \"после\"", dumpBlocks(listOf(doc.blocks[2])).trim())
        assertEquals("<svg xmlns='http://www.w3.org/2000/svg'/>", (doc.blocks[3] as Block.Picture).bytes.decodeToString())
    }

    @Test
    fun charsetFromMeta() {
        val text = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1251\"></head><body><p>Привет</p></body></html>"
        val cp1251 = text.replace("Привет", "").encodeToByteArray().let { bytes ->
            val index = text.indexOf("Привет")
            bytes.copyOfRange(0, index) + hexToBytes("CFF0E8E2E5F2")!! + bytes.copyOfRange(index, bytes.size)
        }
        assertEquals("P \"Привет\"\n", dumpBlocks(Documents.read(cp1251, DocFormat.HTML).blocks))
        val meta = "<meta charset=\"koi8-r\"><p>".encodeToByteArray() + hexToBytes("F0D2C9D7C5D4")!!
        assertEquals("P \"Привет\"\n", dumpBlocks(Documents.read(meta, DocFormat.HTML).blocks))
    }

    @Test
    fun unknownCharsetsFallBackByScript() {
        val latin = "<meta charset=\"iso-8859-15\"><p>Caf".encodeToByteArray() + hexToBytes("E9")!!
        assertEquals("P \"Café\"\n", dumpBlocks(Documents.read(latin, DocFormat.HTML).blocks))
        val ukrainian = "<meta charset=\"koi8-u\"><p>".encodeToByteArray() + hexToBytes("F0D2C9D7C5D4")!!
        assertEquals("P \"Привет\"\n", dumpBlocks(Documents.read(ukrainian, DocFormat.HTML).blocks))
        val unicode = "<meta charset=\"unicode-1-1-utf-8\"><p>Привет".encodeToByteArray()
        assertEquals("P \"Привет\"\n", dumpBlocks(Documents.read(unicode, DocFormat.HTML).blocks))
    }

    @Test
    fun deepNestingDoesNotOverflow() {
        val html = "<div>".repeat(50_000) + "глубоко" + "</div>".repeat(50_000) + "<b>".repeat(20_000) + "жирно"
        val doc = read(html)
        assertEquals("P \"глубоко\"\nP {b}\"жирно\"\n", dumpBlocks(doc.blocks))
    }

    @Test
    fun xhtmlSelfClosingAndCdata() {
        val html = """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>a<br/>b</p><div/><p><![CDATA[<raw>]]></p><a id="anchor"/><p>c</p></body></html>"""
        assertEquals("P \"a\" \\n \"b\"\nP \"<raw>\"\nP \"c\"\n", blocks(html))
    }
}
