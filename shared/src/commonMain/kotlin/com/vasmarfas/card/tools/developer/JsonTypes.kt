package com.vasmarfas.card.tools.developer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class TypeTarget(val title: String) {
    KOTLIN("Kotlin"),
    SWIFT("Swift"),
    TYPESCRIPT("TypeScript"),
}

private sealed interface Shape {
    data object Unknown : Shape
    data object Flag : Shape
    data object Real : Shape
    data object Text : Shape
    data class Whole(val wide: Boolean) : Shape
    class Fields(val slots: Map<String, Slot>) : Shape
    class Items(val item: Slot) : Shape
    class Mixed(val parts: List<Shape>) : Shape
}

private class Slot(val shape: Shape, val nullable: Boolean = false, val optional: Boolean = false)

private fun slotOf(element: JsonElement): Slot = when (element) {
    is JsonObject -> Slot(Shape.Fields(element.mapValues { slotOf(it.value) }))
    is JsonArray -> Slot(Shape.Items(element.map { slotOf(it) }.reduceOrNull { a, b -> merge(a, b) } ?: Slot(Shape.Unknown)))
    is JsonNull -> Slot(Shape.Unknown, nullable = true)
    is JsonPrimitive -> Slot(
        when (JsonTree.kindOf(element)) {
            JsonKind.STRING -> Shape.Text
            JsonKind.BOOLEAN -> Shape.Flag
            else -> numberShape(element.content)
        },
    )
}

private fun numberShape(content: String): Shape = when {
    content.any { it == '.' || it == 'e' || it == 'E' } -> Shape.Real
    content.toIntOrNull() != null -> Shape.Whole(wide = false)
    content.toLongOrNull() != null -> Shape.Whole(wide = true)
    else -> Shape.Real
}

private fun merge(a: Slot, b: Slot): Slot = Slot(merge(a.shape, b.shape), a.nullable || b.nullable, a.optional || b.optional)

private fun merge(a: Shape, b: Shape): Shape {
    if (a !is Shape.Mixed && b !is Shape.Mixed) return unite(a, b) ?: Shape.Mixed(listOf(a, b))
    val parts = (if (a is Shape.Mixed) a.parts else listOf(a)).toMutableList()
    for (part in if (b is Shape.Mixed) b.parts else listOf(b)) {
        val united = parts.indices.firstNotNullOfOrNull { i -> unite(parts[i], part)?.let { i to it } }
        if (united == null) parts += part else parts[united.first] = united.second
    }
    return Shape.Mixed(parts)
}

private fun unite(a: Shape, b: Shape): Shape? = when {
    a is Shape.Unknown -> b
    b is Shape.Unknown -> a
    a is Shape.Whole && b is Shape.Whole -> Shape.Whole(a.wide || b.wide)
    (a is Shape.Whole && b is Shape.Real) || (a is Shape.Real && b is Shape.Whole) -> Shape.Real
    a is Shape.Fields && b is Shape.Fields -> Shape.Fields(mergeFields(a.slots, b.slots))
    a is Shape.Items && b is Shape.Items -> Shape.Items(merge(a.item, b.item))
    a == b -> a
    else -> null
}

private fun mergeFields(a: Map<String, Slot>, b: Map<String, Slot>): Map<String, Slot> {
    val merged = LinkedHashMap<String, Slot>()
    for ((key, slot) in a) merged[key] = b[key]?.let { merge(slot, it) } ?: Slot(slot.shape, slot.nullable, optional = true)
    for ((key, slot) in b) if (key !in a) merged[key] = Slot(slot.shape, slot.nullable, optional = true)
    return merged
}

private fun words(key: String): List<String> =
    key.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }

private fun identifier(name: String, fallback: String): String = when {
    name.isEmpty() -> fallback
    name.first().isDigit() -> "_$name"
    else -> name
}

private fun capitalized(word: String) = word.replaceFirstChar { it.uppercase() }

private fun pascal(key: String): String = identifier(words(key).joinToString("") { capitalized(it) }, "Item")

private fun camel(key: String): String {
    val parts = words(key)
    val first = parts.firstOrNull().orEmpty().let { if (it == it.uppercase()) it.lowercase() else it.replaceFirstChar { c -> c.lowercase() } }
    return identifier(first + parts.drop(1).joinToString("") { capitalized(it) }, "field")
}

private fun singular(word: String): String = when {
    word.endsWith("ies") && !word.endsWith("vies") && !word.endsWith("kies") -> word.dropLast(3) + "y"
    word.endsWith("sses") || word.endsWith("xes") || word.endsWith("ches") || word.endsWith("shes") -> word.dropLast(2)
    word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is") -> word.dropLast(1)
    else -> word + "Item"
}

private fun unique(name: String, used: MutableSet<String>): String {
    var candidate = name
    var suffix = 2
    while (!used.add(candidate)) candidate = name + suffix++
    return candidate
}

private class TypeWriter(private val target: TypeTarget, private val serializable: Boolean, private val rootName: String) {
    private val names = hashSetOf(rootName)
    private val declared = HashMap<String, String>()
    private val declarations = ArrayList<String>()
    private var usesAny = false
    private var usesSerialName = false

    private fun pick(kotlin: String, swift: String, typescript: String) = when (target) {
        TypeTarget.KOTLIN -> kotlin
        TypeTarget.SWIFT -> swift
        TypeTarget.TYPESCRIPT -> typescript
    }

    private fun anyType(): String {
        usesAny = true
        return pick(if (serializable) "JsonElement" else "Any", "JSONValue", "unknown")
    }

    private fun type(slot: Slot, hint: String): String {
        val base = base(slot.shape, hint)
        return when {
            target != TypeTarget.TYPESCRIPT -> if (slot.nullable || slot.optional) "$base?" else base
            !slot.nullable -> base
            slot.shape == Shape.Unknown -> "null"
            else -> "$base | null"
        }
    }

    private fun base(shape: Shape, hint: String): String = when (shape) {
        Shape.Unknown -> anyType()
        Shape.Flag -> pick("Boolean", "Bool", "boolean")
        Shape.Real -> pick("Double", "Double", "number")
        Shape.Text -> pick("String", "String", "string")
        is Shape.Whole -> pick(if (shape.wide) "Long" else "Int", "Int", "number")
        is Shape.Fields -> declare(shape, hint, false)
        is Shape.Items -> array(type(shape.item, singular(hint)))
        is Shape.Mixed ->
            if (target == TypeTarget.TYPESCRIPT) shape.parts.map { base(it, hint) }.distinct().joinToString(" | ") else anyType()
    }

    private fun array(item: String) = when (target) {
        TypeTarget.KOTLIN -> "List<$item>"
        TypeTarget.SWIFT -> "[$item]"
        TypeTarget.TYPESCRIPT -> if (' ' in item) "($item)[]" else "$item[]"
    }

    private fun declare(shape: Shape.Fields, hint: String, root: Boolean): String {
        val body = when (target) {
            TypeTarget.KOTLIN -> kotlinBody(shape)
            TypeTarget.SWIFT -> swiftBody(shape)
            TypeTarget.TYPESCRIPT -> typescriptBody(shape)
        }
        declared[body]?.let { return it }
        val name = if (root) rootName else freeName(hint)
        declared[body] = name
        declarations += when (target) {
            TypeTarget.KOTLIN -> (if (serializable) "@Serializable\n" else "") + (if (body.isEmpty()) "class " else "data class ") + name + body
            TypeTarget.SWIFT -> "struct $name: Codable $body"
            TypeTarget.TYPESCRIPT -> "export interface $name $body"
        }
        return name
    }

    private fun freeName(hint: String): String {
        val name = pascal(hint)
        return unique(if (name in reservedTypes) name + "Model" else name, names)
    }

    private fun property(key: String, used: MutableSet<String>): String {
        // without @SerialName the key survives only as the property name itself
        val keepKey = target == TypeTarget.KOTLIN && !serializable && key.isNotEmpty() && !key.first().isDigit() &&
            key.all { it.isLetterOrDigit() || it == '_' }
        return unique(if (keepKey) key else camel(key), used)
    }

    private fun escaped(name: String): String {
        val keywords = if (target == TypeTarget.KOTLIN) kotlinKeywords else swiftKeywords
        return if (name in keywords) "`$name`" else name
    }

    private fun kotlinBody(shape: Shape.Fields): String {
        if (shape.slots.isEmpty()) return ""
        val used = HashSet<String>()
        val fields = shape.slots.map { (key, slot) ->
            val name = property(key, used)
            val rename = serializable && name != key
            if (rename) usesSerialName = true
            val annotation = if (rename) "    @SerialName(${DataFormats.quote(key).replace("$", "\\$")})\n" else ""
            val default = if (slot.nullable || slot.optional) " = null" else ""
            "$annotation    val ${escaped(name)}: ${type(slot, key)}$default,"
        }
        return "(\n" + fields.joinToString("\n") + "\n)"
    }

    private fun swiftBody(shape: Shape.Fields): String {
        val used = HashSet<String>()
        val properties = shape.slots.keys.associateWith { property(it, used) }
        val fields = shape.slots.map { (key, slot) -> "    let ${escaped(properties.getValue(key))}: ${type(slot, key)}" }
        val codingKeys = if (properties.all { (key, name) -> key == name }) {
            emptyList()
        } else {
            listOf("", "    enum CodingKeys: String, CodingKey {") +
                properties.map { (key, name) -> "        case ${escaped(name)}" + if (key == name) "" else " = ${DataFormats.quote(key)}" } +
                "    }"
        }
        return (listOf("{") + fields + codingKeys + "}").joinToString("\n")
    }

    private fun typescriptBody(shape: Shape.Fields): String {
        val fields = shape.slots.map { (key, slot) ->
            val name = if (typescriptName.matches(key)) key else DataFormats.quote(key)
            "  $name${if (slot.optional) "?" else ""}: ${type(slot, key)};"
        }
        return (listOf("{") + fields + "}").joinToString("\n")
    }

    fun write(slot: Slot): String {
        val shape = slot.shape
        val alias = if (shape is Shape.Fields && !slot.nullable) {
            declare(shape, rootName, true)
            null
        } else {
            val type = type(slot, rootName)
            if (target == TypeTarget.TYPESCRIPT) "export type $rootName = $type;" else "typealias $rootName = $type"
        }
        val blocks = listOfNotNull(header(), alias) + declarations.asReversed() + listOfNotNull(helper())
        return blocks.joinToString("\n\n")
    }

    private fun header(): String? = when (target) {
        TypeTarget.KOTLIN -> if (serializable) {
            listOfNotNull(
                "import kotlinx.serialization.SerialName".takeIf { usesSerialName },
                "import kotlinx.serialization.Serializable".takeIf { declarations.isNotEmpty() },
                "import kotlinx.serialization.json.JsonElement".takeIf { usesAny },
            ).joinToString("\n").ifEmpty { null }
        } else {
            null
        }
        TypeTarget.SWIFT -> "import Foundation"
        TypeTarget.TYPESCRIPT -> null
    }

    private fun helper(): String? = if (target == TypeTarget.SWIFT && usesAny) swiftValue else null

    private companion object {
        val typescriptName = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        val reservedTypes = setOf(
            "Any", "Array", "Bool", "Boolean", "Char", "Character", "Codable", "CodingKeys", "Data", "Date", "Decoder", "Dictionary",
            "Double", "Encoder", "Error", "Float", "Function", "Int", "JSONValue", "JsonElement", "List", "Long", "Map", "Nothing",
            "Number", "Object", "Optional", "Promise", "Record", "Result", "Self", "SerialName", "Serializable", "Set", "String",
            "Symbol", "Type", "Unit",
        )
        val kotlinKeywords = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object",
            "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        )
        val swiftKeywords = setOf(
            "Any", "Self", "as", "associatedtype", "await", "break", "case", "catch", "class", "continue", "default", "defer", "deinit",
            "do", "else", "enum", "extension", "fallthrough", "false", "fileprivate", "for", "func", "guard", "if", "import", "in", "init",
            "inout", "internal", "is", "let", "nil", "open", "operator", "precedencegroup", "private", "protocol", "public", "repeat",
            "rethrows", "return", "self", "static", "struct", "subscript", "super", "switch", "throw", "throws", "true", "try", "typealias",
            "var", "where", "while",
        )
        val swiftValue = """
            enum JSONValue: Codable {
                case null
                case bool(Bool)
                case number(Double)
                case string(String)
                case array([JSONValue])
                case object([String: JSONValue])

                init(from decoder: Decoder) throws {
                    let container = try decoder.singleValueContainer()
                    if container.decodeNil() {
                        self = .null
                    } else if let value = try? container.decode(Bool.self) {
                        self = .bool(value)
                    } else if let value = try? container.decode(Double.self) {
                        self = .number(value)
                    } else if let value = try? container.decode(String.self) {
                        self = .string(value)
                    } else if let value = try? container.decode([JSONValue].self) {
                        self = .array(value)
                    } else {
                        self = .object(try container.decode([String: JSONValue].self))
                    }
                }

                func encode(to encoder: Encoder) throws {
                    var container = encoder.singleValueContainer()
                    switch self {
                    case .null: try container.encodeNil()
                    case .bool(let value): try container.encode(value)
                    case .number(let value): try container.encode(value)
                    case .string(let value): try container.encode(value)
                    case .array(let value): try container.encode(value)
                    case .object(let value): try container.encode(value)
                    }
                }
            }
        """.trimIndent()
    }
}

object JsonTypes {
    fun generate(element: JsonElement, rootName: String, target: TypeTarget, serializable: Boolean): String =
        TypeWriter(target, serializable, pascal(rootName.ifBlank { "Root" })).write(slotOf(element))
}
