package com.vasmarfas.card.tools.developer

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

private fun types(text: String, target: TypeTarget, serializable: Boolean = true, root: String = "Root"): String =
    JsonTypes.generate(Json.parseToJsonElement(text), root, target, serializable)

class JsonTypesTest {
    private val sample = """
        {
          "id": 1,
          "first-name": "Ann",
          "score": 1.5,
          "active": true,
          "created_at": 9007199254740993,
          "class": "a",
          "nickname": null,
          "tags": ["x", "y"],
          "address": {"city": "Oslo", "zip": null},
          "orders": [{"sku": "a", "qty": 1}, {"sku": "b", "qty": 2.5, "note": "n"}],
          "mixed": [1, "two", null],
          "empty": []
        }
    """.trimIndent()

    @Test
    fun kotlinWithSerialization() {
        val expected = """
            import kotlinx.serialization.SerialName
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.json.JsonElement

            @Serializable
            data class Root(
                val id: Int,
                @SerialName("first-name")
                val firstName: String,
                val score: Double,
                val active: Boolean,
                @SerialName("created_at")
                val createdAt: Long,
                val `class`: String,
                val nickname: JsonElement? = null,
                val tags: List<String>,
                val address: Address,
                val orders: List<Order>,
                val mixed: List<JsonElement?>,
                val empty: List<JsonElement>,
            )

            @Serializable
            data class Order(
                val sku: String,
                val qty: Double,
                val note: String? = null,
            )

            @Serializable
            data class Address(
                val city: String,
                val zip: JsonElement? = null,
            )
        """.trimIndent()
        assertEquals(expected, types(sample, TypeTarget.KOTLIN))
    }

    @Test
    fun plainKotlinKeepsUsableKeys() {
        val code = types(sample, TypeTarget.KOTLIN, serializable = false)
        assertFalse("import" in code)
        assertFalse("@" in code)
        assertContains(code, "data class Root(\n    val id: Int,\n    val firstName: String,")
        assertContains(code, "val created_at: Long,")
        assertContains(code, "val nickname: Any? = null,")
        assertContains(code, "val mixed: List<Any?>,")
    }

    @Test
    fun swiftCodable() {
        val code = types(sample, TypeTarget.SWIFT)
        val root = """
            import Foundation

            struct Root: Codable {
                let id: Int
                let firstName: String
                let score: Double
                let active: Bool
                let createdAt: Int
                let `class`: String
                let nickname: JSONValue?
                let tags: [String]
                let address: Address
                let orders: [Order]
                let mixed: [JSONValue?]
                let empty: [JSONValue]

                enum CodingKeys: String, CodingKey {
                    case id
                    case firstName = "first-name"
                    case score
                    case active
                    case createdAt = "created_at"
                    case `class`
                    case nickname
                    case tags
                    case address
                    case orders
                    case mixed
                    case empty
                }
            }

            struct Order: Codable {
                let sku: String
                let qty: Double
                let note: String?
            }
        """.trimIndent()
        assertEquals(root, code.substringBefore("\n\nstruct Address"))
        assertContains(code, "enum JSONValue: Codable {")
    }

    @Test
    fun swiftSkipsTheHelperWhenNothingNeedsIt() {
        assertEquals("import Foundation\n\nstruct Root: Codable {\n    let a: Int\n}", types("""{"a": 1}""", TypeTarget.SWIFT))
    }

    @Test
    fun typescriptInterfaces() {
        val expected = """
            export interface Root {
              id: number;
              "first-name": string;
              score: number;
              active: boolean;
              created_at: number;
              class: string;
              nickname: null;
              tags: string[];
              address: Address;
              orders: Order[];
              mixed: (number | string | null)[];
              empty: unknown[];
            }

            export interface Order {
              sku: string;
              qty: number;
              note?: string;
            }

            export interface Address {
              city: string;
              zip: null;
            }
        """.trimIndent()
        assertEquals(expected, types(sample, TypeTarget.TYPESCRIPT))
    }

    @Test
    fun rootThatIsNotAnObject() {
        assertEquals("typealias Items = List<Item>\n\ndata class Item(\n    val a: Int,\n)", types("""[{"a": 1}]""", TypeTarget.KOTLIN, false, "items"))
        assertEquals("export type Root = number[];", types("[1, 2]", TypeTarget.TYPESCRIPT))
        assertEquals("export type Root = (number | string)[];", types("""[1, "a"]""", TypeTarget.TYPESCRIPT))
        assertEquals("import Foundation\n\ntypealias Root = [Double]", types("[1, 2.5]", TypeTarget.SWIFT))
        assertEquals("typealias Root = String", types("\"x\"", TypeTarget.KOTLIN))
    }

    @Test
    fun rootName() {
        assertContains(types("{}", TypeTarget.KOTLIN, false, "user profile"), "class UserProfile")
        assertContains(types("{}", TypeTarget.KOTLIN, false, "  "), "class Root")
        assertContains(types("{}", TypeTarget.TYPESCRIPT, root = "2fa"), "export interface _2fa")
    }

    @Test
    fun keysThatAreNotIdentifiers() {
        val code = types("""{"2fa": 1, "with space": 2, "${'$'}ref": 3, "": 4, "имя": 5, "a-b": 6, "a_b": 7, "URL": 8}""", TypeTarget.KOTLIN)
        assertContains(code, "@SerialName(\"2fa\")\n    val _2fa: Int,")
        assertContains(code, "@SerialName(\"with space\")\n    val withSpace: Int,")
        assertContains(code, "@SerialName(\"\\\$ref\")\n    val ref: Int,")
        assertContains(code, "@SerialName(\"\")\n    val field: Int,")
        assertContains(code, "    val имя: Int,")
        assertContains(code, "@SerialName(\"a-b\")\n    val aB: Int,")
        assertContains(code, "@SerialName(\"a_b\")\n    val aB2: Int,")
        assertContains(code, "@SerialName(\"URL\")\n    val url: Int,")
    }

    @Test
    fun typeNames() {
        val code = types(
            """{"users": [{"a": 1}], "categories": [{"b": 1}], "addresses": [{"c": 1}], "data": {"d": 1}, "list": [{"e": 1}], "error": {"f": 1}, "root": {"g": 1}, "matrix": [[{"h": 1}]]}""",
            TypeTarget.KOTLIN,
            false,
        )
        for (name in listOf("User", "Category", "Address", "DataModel", "ListItem", "ErrorModel", "Root2", "MatrixItemItem")) {
            assertContains(code, "data class $name(")
        }
    }

    @Test
    fun sameShapeSharesOneType() {
        val code = types("""{"from": {"x": 1}, "to": {"x": 2}, "other": {"x": "s"}}""", TypeTarget.KOTLIN, false)
        assertContains(code, "val from: From,\n    val to: From,\n    val other: Other,")
        assertFalse("data class To(" in code)
    }

    @Test
    fun differentShapesWithTheSameNameGetDifferentTypes() {
        val code = types("""{"a": {"item": {"x": 1}}, "b": {"item": {"y": 1}}}""", TypeTarget.TYPESCRIPT)
        assertContains(code, "export interface Item {\n  x: number;")
        assertContains(code, "export interface Item2 {\n  y: number;")
    }

    @Test
    fun nullsMergeWithTheOtherItems() {
        assertContains(types("""[{"a": null}, {"a": 1}, {"a": 2}]""", TypeTarget.KOTLIN, false), "val a: Int? = null,")
        assertContains(types("""[{"a": null}, {"a": 1}]""", TypeTarget.TYPESCRIPT), "a: number | null;")
        assertContains(types("""[{"a": 1}, {}]""", TypeTarget.TYPESCRIPT), "a?: number;")
        assertContains(types("""[{"a": [1]}, {"a": []}]""", TypeTarget.SWIFT), "let a: [Int]")
        assertContains(types("""{"a": [null, 1]}""", TypeTarget.KOTLIN, false), "val a: List<Int?>,")
    }

    @Test
    fun mixedObjectsStayNamedInTypescript() {
        val code = types("""{"a": [1, {"b": 2}, {"c": true}]}""", TypeTarget.TYPESCRIPT)
        assertContains(code, "a: (number | AItem)[];")
        assertContains(code, "export interface AItem {\n  b?: number;\n  c?: boolean;\n}")
    }

    @Test
    fun emptyObjects() {
        assertEquals("import kotlinx.serialization.Serializable\n\n@Serializable\nclass Root", types("{}", TypeTarget.KOTLIN))
        assertEquals("export interface Root {\n}", types("{}", TypeTarget.TYPESCRIPT))
    }
}
