package com.vasmarfas.card.tools.developer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object DependencyFiles {
    private val cargoSection = Regex("""^\s*\[(workspace\.)?package\]\s*$""", RegexOption.MULTILINE)
    private val cargoField = Regex("""^\s*(name|license)\s*=\s*"([^"]*)"""")
    private val nameColumns = setOf("module name", "artifact", "name", "module")
    private val licenseColumns = setOf("license", "licenses", "modulelicense")

    // "package: license" lines of a pasted manifest or report, null when the text is neither
    fun lines(text: String): String? {
        val trimmed = text.trim()
        val pairs = when {
            trimmed.startsWith("{") -> runCatching { Json.parseToJsonElement(trimmed) as JsonObject }.getOrNull()?.let(::json)
            cargoSection.containsMatchIn(trimmed) -> cargo(trimmed)
            else -> csv(trimmed)
        } ?: return null
        return pairs.filter { it.second.isNotEmpty() }.joinToString("\n") { (name, license) -> "$name: $license" }
    }

    private fun json(root: JsonObject): List<Pair<String, String>>? {
        val gradle = root["dependencies"] as? JsonArray
        val lock = root["packages"] as? JsonObject
        return when {
            gradle != null -> gradle.filterIsInstance<JsonObject>().map { module ->
                val version = module.text("moduleVersion")
                module.text("moduleName") + (if (version.isEmpty()) "" else ":$version") to license(module["moduleLicense"] ?: module["moduleLicenses"])
            }
            lock != null -> lock.mapNotNull { (path, entry) ->
                (entry as? JsonObject)?.let { path.substringAfterLast("node_modules/").ifEmpty { root.text("name") } to license(it["license"] ?: it["licenses"]) }
            }
            "name" in root -> listOf(root.text("name") to license(root["license"] ?: root["licenses"]))
            root.values.all { it is JsonObject } -> root.map { (name, entry) -> name to license((entry as JsonObject)["licenses"]) }
            else -> null
        }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun license(value: JsonElement?): String = when (value) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.map(::license).filter { it.isNotEmpty() }.joinToString(" OR ")
        is JsonObject -> license(value["type"] ?: value["moduleLicense"])
        null -> ""
    }

    private fun cargo(text: String): List<Pair<String, String>> {
        val section = text.substring(cargoSection.find(text)!!.range.last + 1).substringBefore("\n[")
        val fields = section.lines().mapNotNull { cargoField.find(it) }.associate { it.groupValues[1] to it.groupValues[2] }
        return listOf(fields["name"].orEmpty().ifEmpty { "Cargo.toml" } to fields["license"].orEmpty())
    }

    private fun csv(text: String): List<Pair<String, String>>? {
        val rows = Csv.parse(text, ',').rows
        val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return null
        val name = header.indexOfFirst { it in nameColumns }
        val license = header.indexOfFirst { it in licenseColumns }
        if (name < 0 || license < 0) return null
        return rows.drop(1).map { it.getOrElse(name) { "" } to it.getOrElse(license) { "" } }
    }
}
