package com.vasmarfas.card.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class Lang(val code: String, val nativeName: String) {
    EN("en", "English"),
    RU("ru", "Русский");

    companion object {
        fun fromCode(code: String?): Lang? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }

        fun fromSystemTag(tag: String?): Lang = when {
            tag == null -> EN
            tag.lowercase().startsWith("ru") -> RU
            else -> EN
        }
    }
}

val LocalLang = staticCompositionLocalOf { Lang.EN }

// for text built outside composition, such as durations from platform code, AppSettings sets it
var appLang: Lang = Lang.EN
    internal set

@Serializable(with = TrSerializer::class)
data class Tr(val en: String, val ru: String = en) {
    operator fun get(lang: Lang): String = when (lang) {
        Lang.EN -> en
        Lang.RU -> ru
    }

    fun isBlank() = en.isBlank() && ru.isBlank()

    override fun toString() = en
}

@Composable
fun Tr.str(): String = this[LocalLang.current]

@Composable
fun StringResource.str(): String = stringResource(this)

object TrSerializer : KSerializer<Tr> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Tr")

    override fun serialize(encoder: Encoder, value: Tr) {
        val jsonEncoder = encoder as JsonEncoder
        jsonEncoder.encodeJsonElement(
            JsonObject(mapOf("en" to JsonPrimitive(value.en), "ru" to JsonPrimitive(value.ru)))
        )
    }

    override fun deserialize(decoder: Decoder): Tr {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        if (element is JsonPrimitive) return Tr(element.content)
        val obj = element.jsonObject
        val en = obj["en"]?.jsonPrimitive?.content ?: obj.values.firstOrNull()?.jsonPrimitive?.content ?: ""
        val ru = obj["ru"]?.jsonPrimitive?.content ?: en
        return Tr(en, ru)
    }
}
