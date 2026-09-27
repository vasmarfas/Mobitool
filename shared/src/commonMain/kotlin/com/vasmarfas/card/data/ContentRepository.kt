package com.vasmarfas.card.data

import com.vasmarfas.card.core.AppConfig
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.resources.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import org.jetbrains.compose.resources.ExperimentalResourceApi

enum class ContentSource { BUNDLED, CACHED, REMOTE }

data class ContentState<T>(
    val value: T? = null,
    val source: ContentSource = ContentSource.BUNDLED,
    val refreshing: Boolean = false,
    val error: String? = null,
)

open class JsonFileRepository<T : Any>(
    private val serializer: KSerializer<T>,
    private val bundledPath: String,
    private val remoteUrl: () -> String,
    private val cacheKey: String,
    private val updatedOf: (T) -> String,
) {
    private val _state = MutableStateFlow(ContentState<T>())
    val state: StateFlow<ContentState<T>> = _state.asStateFlow()

    private fun parse(text: String): T = Net.json.decodeFromString(serializer, text)

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load() {
        if (_state.value.value == null) {
            val cached = runCatching { Prefs.store.get(cacheKey)?.let(::parse) }.getOrNull()
            if (cached != null) _state.value = ContentState(cached, ContentSource.CACHED)
            val bundled = runCatching { parse(Res.readBytes(bundledPath).decodeToString()) }.getOrNull()
            val best = when {
                bundled == null -> cached
                cached == null -> bundled
                updatedOf(cached) > updatedOf(bundled) -> cached
                else -> bundled
            }
            _state.value = ContentState(
                value = best,
                source = if (best === cached && cached != null) ContentSource.CACHED else ContentSource.BUNDLED,
            )
        }
        if (_state.value.source != ContentSource.REMOTE) refresh()
    }

    suspend fun refresh() {
        _state.value = _state.value.copy(refreshing = true, error = null)
        runCatching {
            val response = Net.client.get(remoteUrl())
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val text = response.bodyAsText()
            text to parse(text)
        }.onSuccess { (text, remote) ->
            val current = _state.value.value
            if (current == null || updatedOf(remote) >= updatedOf(current)) {
                Prefs.store.put(cacheKey, text)
                _state.value = ContentState(value = remote, source = ContentSource.REMOTE)
            } else {
                _state.value = _state.value.copy(refreshing = false)
            }
        }.onFailure {
            if (it is CancellationException) {
                _state.value = _state.value.copy(refreshing = false)
                throw it
            }
            _state.value = _state.value.copy(refreshing = false, error = it.message)
        }
    }
}

object ProfileRepository : JsonFileRepository<Profile>(
    serializer = Profile.serializer(),
    bundledPath = AppConfig.PROFILE_BUNDLED_PATH,
    remoteUrl = { AppConfig.contentUrl("profile.json") },
    cacheKey = "profile.cache.json",
    updatedOf = { it.updated },
)

object ResumeRepository : JsonFileRepository<Resume>(
    serializer = Resume.serializer(),
    bundledPath = AppConfig.RESUME_BUNDLED_PATH,
    remoteUrl = { AppConfig.contentUrl("resume.json") },
    cacheKey = "resume.cache.json",
    updatedOf = { it.updated },
)
