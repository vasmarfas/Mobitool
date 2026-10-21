package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.Net
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class Routing(val prefix: String? = null, val origins: List<Pair<Long, String>> = emptyList(), val holder: String? = null, val prefixes: List<String>? = null)

object RipeStat {
    private const val API = "https://stat.ripe.net/data"

    private fun JsonObject.data(): JsonObject? = this["data"] as? JsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" && it.isNotBlank() }

    fun prefixOverview(json: JsonObject): Routing {
        val data = json.data() ?: return Routing()
        val origins = (data["asns"] as? JsonArray).orEmpty().mapNotNull { item ->
            val asn = (item as? JsonObject)?.text("asn")?.toLongOrNull() ?: return@mapNotNull null
            asn to item.text("holder").orEmpty()
        }
        return Routing(prefix = data.text("resource").takeIf { data.text("announced") == "true" }, origins = origins)
    }

    fun announced(json: JsonObject): List<String> =
        (json.data()?.get("prefixes") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("prefix") }

    fun holder(json: JsonObject): String? = json.data()?.text("holder")

    private suspend fun get(call: String, resource: String): JsonObject =
        Net.json.parseToJsonElement(Net.client.get("$API/$call/data.json?resource=$resource").bodyAsText()).jsonObject

    suspend fun lookup(query: String): Routing? {
        val q = query.trim().lowercase()
        val asn = q.removePrefix("as").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        return when {
            asn != null -> Routing(holder = holder(get("as-overview", "AS$asn")), prefixes = announced(get("announced-prefixes", "AS$asn")))
            looksLikeIp(q) -> prefixOverview(get("prefix-overview", q))
            else -> null
        }
    }
}
