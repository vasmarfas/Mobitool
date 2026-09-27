package com.vasmarfas.card.data

import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.parseCount
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

// the Habr API answers any origin, so the site calls it from the browser too, views in profile.json is the fallback
object HabrStats {
    private const val API = "https://habr.com/kek/v2/articles"
    private const val CACHE_KEY = "habr.views"
    private const val CACHE_TIME_KEY = "habr.views.time"
    private const val MAX_AGE_MS = 6L * 60 * 60 * 1000
    private val serializer = MapSerializer(String.serializer(), Int.serializer())
    private val articleId = Regex("""habr\.com/.*/(\d+)/?$""")

    private val _views = MutableStateFlow(readCache())
    val views: StateFlow<Map<String, Int>> = _views.asStateFlow()

    private fun readCache(): Map<String, Int> =
        runCatching { Prefs.store.get(CACHE_KEY)?.let { Net.json.decodeFromString(serializer, it) } }.getOrNull() ?: emptyMap()

    private fun cacheAge(): Long = currentEpochMillis() - (Prefs.store.get(CACHE_TIME_KEY)?.toLongOrNull() ?: 0L)

    fun idOf(article: Article): String? = articleId.find(article.url)?.groupValues?.get(1)

    fun viewsOf(article: Article, live: Map<String, Int>): Int? = idOf(article)?.let { live[it] } ?: parseCount(article.views)

    suspend fun refresh(articles: List<Article>, force: Boolean = false) {
        if (!force && cacheAge() < MAX_AGE_MS && _views.value.isNotEmpty()) return
        val ids = articles.mapNotNull(::idOf).distinct()
        if (ids.isEmpty()) return
        val fetched = coroutineScope {
            ids.map { id ->
                async {
                    runCatching {
                        val response = Net.client.get("$API/$id/")
                        if (!response.status.isSuccess()) return@async null
                        val statistics = Net.json.parseToJsonElement(response.bodyAsText()).jsonObject["statistics"] as? JsonObject
                        (statistics?.get("reach") as? JsonPrimitive)?.intOrNull?.let { id to it }
                    }.getOrNull()
                }
            }.mapNotNull { it.await() }
        }.toMap()
        if (fetched.isEmpty()) return
        val merged = _views.value + fetched
        _views.value = merged
        Prefs.store.put(CACHE_KEY, Net.json.encodeToString(serializer, merged))
        Prefs.store.put(CACHE_TIME_KEY, currentEpochMillis().toString())
    }
}
