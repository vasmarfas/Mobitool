package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.StringResource

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

private fun assertFails(reason: StringResource, line: Int?, block: () -> Unit) {
    val e = assertFailsWith<DataFormatException> { block() }
    assertEquals(reason.key, e.text.key)
    assertEquals(line, e.line)
}

// examples from chapter 2 and section 8 of the YAML 1.2.2 specification
class YamlParseTest {
    @Test
    fun mappingOfSequences() {
        val yaml = """
            american:
              - Boston Red Sox
              - Detroit Tigers
            national:
            - New York Mets
            - Chicago Cubs
        """.trimIndent()
        assertEquals(
            json("""{"american": ["Boston Red Sox", "Detroit Tigers"], "national": ["New York Mets", "Chicago Cubs"]}"""),
            Yaml.parse(yaml),
        )
    }

    @Test
    fun sequenceOfMappings() {
        val yaml = """
            -
              name: Mark McGwire
              hr:   65
              avg:  0.278
            -
              name: Sammy Sosa
              hr:   63
              avg:  0.288
        """.trimIndent()
        assertEquals(
            json("""[{"name": "Mark McGwire", "hr": 65, "avg": 0.278}, {"name": "Sammy Sosa", "hr": 63, "avg": 0.288}]"""),
            Yaml.parse(yaml),
        )
    }

    @Test
    fun compactNesting() {
        val yaml = """
            - item: Super Hoop   # comment
              quantity: 1
            - - a
              - b
            - c
        """.trimIndent()
        assertEquals(json("""[{"item": "Super Hoop", "quantity": 1}, ["a", "b"], "c"]"""), Yaml.parse(yaml))
    }

    @Test
    fun flowCollections() {
        val yaml = """
            seq: [ Sammy Sosa, 63, 'it''s', "a\tb", ]
            map: { hr: 65, avg: 0.278, empty, "q":1 }
            url: http://example.com:8080/a
            multi: [
              1, # one
              [2, {x: y}]
            ]
        """.trimIndent()
        assertEquals(
            json(
                """{"seq": ["Sammy Sosa", 63, "it's", "a\tb"], "map": {"hr": 65, "avg": 0.278, "empty": null, "q": 1},
                "url": "http://example.com:8080/a", "multi": [1, [2, {"x": "y"}]]}""",
            ),
            Yaml.parse(yaml),
        )
    }

    @Test
    fun coreSchemaScalars() {
        val yaml = """
            canonical: 12345
            hex: 0xC
            octal: 0o14
            leading: 007
            signed: +12
            fixed: 1230.15
            exponent: 12.3015e+02
            short: .5
            nothing: ~
            word: null
            empty:
            yes_stays: yes
            bool: True
            quoted: "true"
            infinity: .inf
            big: 123456789012345678901234567890
        """.trimIndent()
        assertEquals(
            json(
                """{"canonical": 12345, "hex": 12, "octal": 12, "leading": 7, "signed": 12, "fixed": 1230.15, "exponent": 12.3015e+02,
                "short": 0.5, "nothing": null, "word": null, "empty": null, "yes_stays": "yes", "bool": true, "quoted": "true",
                "infinity": ".inf", "big": 123456789012345678901234567890}""",
            ),
            Yaml.parse(yaml),
        )
    }

    @Test
    fun literalAndFoldedBlocks() {
        val yaml = "literal: |\n  \\//||\\/||\n  // ||  ||__\nfolded: >\n  Mark McGwire's\n  year was crippled\n\n  by a knee injury.\n\n    indented\n  end\nlast: 1\n"
        assertEquals(
            json(
                """{"literal": "\\//||\\/||\n// ||  ||__\n",
                "folded": "Mark McGwire's year was crippled\nby a knee injury.\n\n  indented\nend\n", "last": 1}""",
            ),
            Yaml.parse(yaml),
        )
    }

    @Test
    fun chomping() {
        val yaml = "strip: |-\n  text\nclip: |\n  text\nkeep: |+\n  text\n\nindented: |1\n  two\nnext: >-\n  a\n  b\n"
        assertEquals(
            json("""{"strip": "text", "clip": "text\n", "keep": "text\n\n", "indented": " two\n", "next": "a b"}"""),
            Yaml.parse(yaml),
        )
        assertEquals(JsonPrimitive("no newline"), Yaml.parse("|\n  no newline"))
    }

    @Test
    fun quotedAndPlainMultilineFold() {
        val yaml = "plain:\n  This unquoted scalar\n  spans many lines.\n\n  Second.\nquoted: \"So does this\n  quoted scalar.\\n\"\nsingle: 'one\n  two'\n"
        assertEquals(
            json("""{"plain": "This unquoted scalar spans many lines.\nSecond.", "quoted": "So does this quoted scalar.\n", "single": "one two"}"""),
            Yaml.parse(yaml),
        )
    }

    @Test
    fun doubleQuoteEscapes() {
        val expected = "AЖ😀" + Char(27) + Char(0xA0) + "\""
        assertEquals(JsonPrimitive(expected), Yaml.parse("\"\\x41\\u0416\\U0001F600\\e\\_\\\"\""))
    }

    @Test
    fun documentMarkersAndKeys() {
        assertEquals(json("""{"a": 1}"""), Yaml.parse("# header\n---\na: 1\n...\n"))
        assertEquals(json("""{"key with space": 1, "quoted: key": 2, "it's": 3, "1": 4}"""), Yaml.parse("key with space: 1\n\"quoted: key\": 2\n'it''s': 3\n1: 4"))
        assertEquals(JsonPrimitive("just text"), Yaml.parse("just text"))
        assertEquals(json("null"), Yaml.parse("# only a comment\n"))
    }

    @Test
    fun jsonIsYaml() {
        val text = """{"a": [1, 2.5, true, null, {"b": "c\nd"}], "e": {}}"""
        assertEquals(json(text), Yaml.parse(text))
    }

    @Test
    fun windowsLineEnds() {
        assertEquals(json("""{"a": 1, "b": ["c"]}"""), Yaml.parse("a: 1\r\nb:\r\n  - c\r\n"))
    }
}

class YamlErrorTest {
    @Test
    fun unsupportedFeaturesNameTheLine() {
        assertFails(Res.string.data_format_yaml_unsupported, 2) { Yaml.parse("a: 1\nb: &anchor 2") }
        assertFails(Res.string.data_format_yaml_unsupported, 3) { Yaml.parse("a: 1\nb:\n  - *alias") }
        assertFails(Res.string.data_format_yaml_unsupported, 1) { Yaml.parse("a: !!str 1") }
        assertFails(Res.string.data_format_yaml_unsupported, 1) { Yaml.parse("? complex\n: value") }
        assertFails(Res.string.data_format_yaml_unsupported, 1) { Yaml.parse("a: [*x]") }
        assertFails(Res.string.data_format_yaml_documents, 2) { Yaml.parse("a: 1\n---\nb: 2") }
    }

    @Test
    fun malformedDocuments() {
        assertFails(Res.string.data_format_duplicate_key, 3) { Yaml.parse("a: 1\nb: 2\na: 3") }
        assertFails(Res.string.data_format_yaml_tab, 2) { Yaml.parse("a:\n\tb: 1") }
        assertFails(Res.string.data_format_yaml_indent, 3) { Yaml.parse("a:\n    b: 1\n  c: 2") }
        assertFails(Res.string.data_format_yaml_colon, 1) { Yaml.parse("a: b: c") }
        assertFails(Res.string.data_format_yaml_key, 2) { Yaml.parse("a: 1\nplain text") }
        assertFails(Res.string.data_format_unclosed_string, 2) { Yaml.parse("a: 1\nb: \"open\nc: 2") }
        assertFails(Res.string.data_format_bad_escape, 1) { Yaml.parse("a: \"\\q\"") }
        assertFails(Res.string.data_format_flow_separator, 2) { Yaml.parse("a: 1\nb: [1, 2") }
        assertFails(Res.string.data_format_unexpected, 1) { Yaml.parse("a: \"x\" y") }
    }

    @Test
    fun deepNestingStopsWithAnError() {
        assertFails(Res.string.json_too_deep, 1) { Yaml.parse("[".repeat(5000)) }
        assertFails(Res.string.json_too_deep, 1) { Yaml.parse("- ".repeat(5000) + "x") }
    }
}

class YamlWriteTest {
    @Test
    fun blockStyle() {
        val element = json("""{"name": "demo", "ports": [80, 443], "db": {"host": "example.com", "opts": {}}, "list": [{"a": 1, "b": [2]}, [3, []]], "none": null}""")
        val expected = """
            name: demo
            ports:
              - 80
              - 443
            db:
              host: example.com
              opts: {}
            list:
              - a: 1
                b:
                  - 2
              - - 3
                - []
            none: null
        """.trimIndent()
        assertEquals(expected, Yaml.write(element))
    }

    @Test
    fun quotesWhatWouldChangeMeaning() {
        val values = listOf(
            "true", "null", "~", "12", "1e3", "0x10", "yes", "No", "", " lead", "trail ", "a: b", "a #b", "- x", "multi\nline", "tab\t",
            "'q'", "\"q\"", "[x]", "{x}", "*a", "&a", "!t", "|", ">", "%x", "@x", "`x", "ключ: значение", "end:", ".inf", "---", "...",
        )
        for (value in values) {
            val element = JsonObject(mapOf(value to JsonPrimitive(value)))
            assertEquals(element, Yaml.parse(Yaml.write(element)), "value <$value>")
        }
        assertEquals("c/d-e_f.g", Yaml.write(json("\"c/d-e_f.g\"")))
        assertEquals("имя файла", Yaml.write(json("\"имя файла\"")))
        assertEquals("\"2001-12-14\"", Yaml.write(json("\"2001-12-14\"")))
        assertEquals("\"\\u0085\\u2028\"", Yaml.write(JsonPrimitive("" + Char(0x85) + Char(0x2028))))
    }

    @Test
    fun roundTripKeepsBigNumbers() {
        val element = json("""{"big": 12345678901234567890123, "real": 1.50, "exp": 1E+5, "neg": -0.5, "list": [[], {}, [null, [true]]]}""")
        assertEquals(element, Yaml.parse(Yaml.write(element)))
    }
}

// examples from the TOML 1.0.0 specification
class TomlParseTest {
    @Test
    fun specExample() {
        val toml = """
            # This is a TOML document
            title = "TOML Example"

            [owner]
            name = "Tom Preston-Werner"
            dob = 1979-05-27T07:32:00-08:00

            [database]
            enabled = true
            ports = [ 8000, 8001, 8002 ]
            data = [ ["delta", "phi"], [3.14] ]
            temp_targets = { cpu = 79.5, case = 72.0 }

            [servers]

            [servers.alpha]
            ip = "192.0.2.1"
            role = "frontend"

            [servers.beta]
            ip = "192.0.2.2"
            role = "backend"
        """.trimIndent()
        assertEquals(
            json(
                """{"title": "TOML Example",
                "owner": {"name": "Tom Preston-Werner", "dob": "1979-05-27T07:32:00-08:00"},
                "database": {"enabled": true, "ports": [8000, 8001, 8002], "data": [["delta", "phi"], [3.14]], "temp_targets": {"cpu": 79.5, "case": 72.0}},
                "servers": {"alpha": {"ip": "192.0.2.1", "role": "frontend"}, "beta": {"ip": "192.0.2.2", "role": "backend"}}}""",
            ),
            Toml.parse(toml),
        )
    }

    @Test
    fun keys() {
        val toml = """
            bare_key = 1
            bare-key = 2
            1234 = 3
            "127.0.0.1" = 4
            "ʎǝʞ" = 5
            'quoted "value"' = 6
            physical.color = "orange"
            physical.shape = "round"
            site."google.com" = true
            3.14159 = "pi"
        """.trimIndent()
        assertEquals(
            json(
                """{"bare_key": 1, "bare-key": 2, "1234": 3, "127.0.0.1": 4, "ʎǝʞ": 5, "quoted \"value\"": 6,
                "physical": {"color": "orange", "shape": "round"}, "site": {"google.com": true}, "3": {"14159": "pi"}}""",
            ),
            Toml.parse(toml),
        )
    }

    @Test
    fun strings() {
        val toml = "basic = \"I'm a string. \\\"You can quote me\\\". Name\\tJos\\u00E9\\nLocation\\tSF.\"\n" +
            "multi = \"\"\"\nRoses are red\nViolets are blue\"\"\"\n" +
            "fold = \"\"\"\nThe quick brown \\\n\n\n  fox jumps\"\"\"\n" +
            "quotes = \"\"\"Here are two quotation marks: \"\". Three: \"\"\\\".\"\"\"\n" +
            "ending = \"\"\"\"This,\" she said.\"\"\"\"\n" +
            "path = 'C:\\Users\\nodejs'\n" +
            "regex = '''I [dw]on't need \\d{2} apples'''\n" +
            "lines = '''\nThe first newline is\ntrimmed.\n'''\n" +
            "emoji = \"\\U0001F600\"\n"
        val result = Toml.parse(toml)
        assertEquals(
            json(
                """{"basic": "I'm a string. \"You can quote me\". Name\tJosé\nLocation\tSF.",
                "multi": "Roses are red\nViolets are blue", "fold": "The quick brown fox jumps",
                "quotes": "Here are two quotation marks: \"\". Three: \"\"\".", "ending": "\"This,\" she said.\"",
                "path": "C:\\Users\\nodejs", "regex": "I [dw]on't need \\d{2} apples", "lines": "The first newline is\ntrimmed.\n",
                "emoji": "😀"}""",
            ),
            result,
        )
    }

    @Test
    fun numbersAndDates() {
        val toml = """
            int1 = +99
            int2 = -17
            int3 = 1_000
            hex = 0xDEADBEEF
            oct = 0o755
            bin = 0b11010110
            flt1 = +1.0
            flt2 = 5e+22
            flt3 = 6.626e-34
            flt4 = 224_617.445_991_228
            max = 9223372036854775807
            pos = inf
            odt = 1979-05-27 07:32:00Z
            ldt = 1979-05-27T00:32:00.999999
            ld = 1979-05-27
            lt = 07:32:00
        """.trimIndent()
        assertEquals(
            json(
                """{"int1": 99, "int2": -17, "int3": 1000, "hex": 3735928559, "oct": 493, "bin": 214, "flt1": 1.0, "flt2": 5e+22,
                "flt3": 6.626e-34, "flt4": 224617.445991228, "max": 9223372036854775807, "pos": "inf",
                "odt": "1979-05-27 07:32:00Z", "ldt": "1979-05-27T00:32:00.999999", "ld": "1979-05-27", "lt": "07:32:00"}""",
            ),
            Toml.parse(toml),
        )
    }

    @Test
    fun arraysAndInlineTables() {
        val toml = """
            mixed = [ 0.1, "x", 1, ]
            contributors = [
              "Foo Bar <foo@example.com>", # first
              { name = "Baz Qux", email = "bazqux@example.com", url = "https://example.com/bazqux" }
            ]
            point = { x = 1, y.z = 2 }
            empty = {}
        """.trimIndent()
        assertEquals(
            json(
                """{"mixed": [0.1, "x", 1],
                "contributors": ["Foo Bar <foo@example.com>", {"name": "Baz Qux", "email": "bazqux@example.com", "url": "https://example.com/bazqux"}],
                "point": {"x": 1, "y": {"z": 2}}, "empty": {}}""",
            ),
            Toml.parse(toml),
        )
    }

    @Test
    fun tablesAndArraysOfTables() {
        val toml = """
            [x.y.z.w]
            [x]

            [[fruits]]
            name = "apple"

            [fruits.physical]
            color = "red"

            [[fruits.varieties]]
            name = "red delicious"

            [[fruits.varieties]]
            name = "granny smith"

            [[fruits]]
            name = "banana"

            [[fruits.varieties]]
            name = "plantain"

            [fruit]
            apple.color = "red"
            apple.taste.sweet = true

            [fruit.apple.texture]
            smooth = true
        """.trimIndent()
        assertEquals(
            json(
                """{"x": {"y": {"z": {"w": {}}}},
                "fruits": [
                  {"name": "apple", "physical": {"color": "red"}, "varieties": [{"name": "red delicious"}, {"name": "granny smith"}]},
                  {"name": "banana", "varieties": [{"name": "plantain"}]}
                ],
                "fruit": {"apple": {"color": "red", "taste": {"sweet": true}, "texture": {"smooth": true}}}}""",
            ),
            Toml.parse(toml),
        )
    }
}

class TomlErrorTest {
    @Test
    fun redefinitionsFromTheSpec() {
        assertFails(Res.string.data_format_duplicate_key, 2) { Toml.parse("name = \"Tom\"\nname = \"Pradyun\"") }
        assertFails(Res.string.data_format_toml_not_table, 2) { Toml.parse("fruit.apple = 1\nfruit.apple.smooth = true") }
        assertFails(Res.string.data_format_toml_table_twice, 4) { Toml.parse("[fruit]\napple = \"red\"\n\n[fruit]\norange = \"orange\"") }
        assertFails(Res.string.data_format_toml_table_twice, 4) { Toml.parse("[fruit]\napple.x = 1\n\n[fruit.apple]\ntexture = \"smooth\"") }
        assertFails(Res.string.data_format_toml_not_table, 2) { Toml.parse("type = { name = \"Nail\" }\ntype.edible = false") }
        assertFails(Res.string.data_format_toml_table_twice, 4) { Toml.parse("[[fruits]]\nname = \"apple\"\n\n[fruits]\n") }
        assertFails(Res.string.data_format_toml_table_twice, 2) { Toml.parse("fruits = []\n[[fruits]]") }
        assertFails(Res.string.data_format_toml_table_twice, 5) { Toml.parse("[a.b.c]\nz = 9\n\n[a]\nb.c.t = 1") }
    }

    @Test
    fun syntax() {
        assertFails(Res.string.data_format_toml_equals, 1) { Toml.parse("key") }
        assertFails(Res.string.data_format_toml_key, 1) { Toml.parse("= \"no key\"") }
        assertFails(Res.string.data_format_toml_value, 2) { Toml.parse("a = 1\nb = nope") }
        assertFails(Res.string.data_format_toml_value, 1) { Toml.parse("a = 01") }
        assertFails(Res.string.data_format_toml_value, 1) { Toml.parse("a = 1__0") }
        assertFails(Res.string.data_format_toml_value, 1) { Toml.parse("a = .5") }
        assertFails(Res.string.data_format_toml_newline, 1) { Toml.parse("first = \"Tom\" last = \"Preston-Werner\"") }
        assertFails(Res.string.data_format_toml_header, 1) { Toml.parse("[a") }
        assertFails(Res.string.data_format_unclosed_string, 3) { Toml.parse("a = 1\n\nb = \"open\nc = 2") }
        assertFails(Res.string.data_format_unclosed_string, 1) { Toml.parse("a = \"\"\"open") }
        assertFails(Res.string.data_format_bad_escape, 1) { Toml.parse("a = \"\\q\"") }
        assertFails(Res.string.data_format_bad_escape, 1) { Toml.parse("a = \"\\uD800\"") }
        assertFails(Res.string.data_format_control_char, 1) { Toml.parse("a = \"${Char(1)}\"") }
        assertFails(Res.string.data_format_flow_separator, 1) { Toml.parse("a = [1 2]") }
        assertFails(Res.string.data_format_toml_key, 1) { Toml.parse("a = { b = 1, }") }
        assertFails(Res.string.data_format_toml_int_range, 1) { Toml.parse("a = 9223372036854775808") }
    }

    @Test
    fun deepNestingStopsWithAnError() {
        assertFails(Res.string.json_too_deep, 1) { Toml.parse("a = " + "[".repeat(5000)) }
        assertFails(Res.string.json_too_deep, 1) { Toml.parse("a = " + "{ b = ".repeat(5000)) }
        assertFails(Res.string.json_too_deep, 1) { Toml.parse(List(5000) { "a" }.joinToString(".") + " = 1") }
    }
}

class TomlWriteTest {
    @Test
    fun tablesComeAfterValues() {
        val element = json(
            """{"owner": {"name": "Tom", "tags": ["a", "b"]}, "title": "Example", "servers": [{"ip": "192.0.2.1", "meta": {"up": true}}, {"ip": "192.0.2.2"}],
            "a b": {"c.d": 1}, "mixed": [1, "x", {"y": 2}], "nested": [[1], [2.5]], "empty": {}, "none": []}""",
        )
        val expected = """
            title = "Example"
            mixed = [1, "x", { y = 2 }]
            nested = [[1], [2.5]]
            none = []

            [owner]
            name = "Tom"
            tags = ["a", "b"]

            [[servers]]
            ip = "192.0.2.1"

            [servers.meta]
            up = true

            [[servers]]
            ip = "192.0.2.2"

            ["a b"]
            "c.d" = 1

            [empty]
        """.trimIndent()
        assertEquals(expected, Toml.write(element))
        assertEquals(element, Toml.parse(expected))
    }

    @Test
    fun whatTomlCannotHold() {
        assertFails(Res.string.data_format_toml_root, null) { Toml.write(json("[1]")) }
        assertFails(Res.string.data_format_toml_root, null) { Toml.write(json("1")) }
        val nulled = assertFailsWith<DataFormatException> { Toml.write(json("""{"a": {"b": [1, null]}}""")) }
        assertEquals(Res.string.data_format_toml_null.key, nulled.text.key)
        assertEquals(listOf<Any>("a.b.1"), nulled.args.toList())
        assertFails(Res.string.data_format_toml_big_int, null) { Toml.write(json("""{"a": 9223372036854775808}""")) }
    }

    @Test
    fun stringsSurviveTheRoundTrip() {
        val element = json("""{"s": "tab\t quote\" slash\\ line\n unicode ж 😀 \u0001", "ключ": 1, "": 2}""")
        assertEquals(element, Toml.parse(Toml.write(element)))
    }
}

class DataFormatsTest {
    @Test
    fun jsonErrorNamesTheLine() {
        assertFails(Res.string.data_format_bad_json, 3) { DataFormats.parse("{\n  \"a\": 1,\n  oops\n}", DataFormat.JSON) }
        assertFails(Res.string.json_too_deep, null) { DataFormats.parse("[".repeat(5000), DataFormat.JSON) }
    }

    @Test
    fun convertsBetweenAllThree() {
        val toml = "title = \"demo\"\n\n[db]\nports = [80, 443]"
        val element = DataFormats.parse(toml, DataFormat.TOML)
        val yaml = DataFormats.write(element, DataFormat.YAML)
        assertEquals("title: demo\ndb:\n  ports:\n    - 80\n    - 443", yaml)
        val back = DataFormats.write(DataFormats.parse(yaml, DataFormat.YAML), DataFormat.JSON)
        assertEquals(element, json(back))
        assertEquals(toml, DataFormats.write(json(back), DataFormat.TOML))
    }

    @Test
    fun numberSpellings() {
        assertEquals("0.5", DataFormats.number(".5").toString())
        assertEquals("5", DataFormats.number("5.").toString())
        assertEquals("-7", DataFormats.number("-007").toString())
        assertEquals("1.5e+3", DataFormats.number("+1.5e+3").toString())
        assertEquals("0", DataFormats.number("000").toString())
    }
}
