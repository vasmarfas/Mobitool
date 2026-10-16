package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource

enum class PolicyProduct(val key: String, val title: StringResource) {
    APP("app", Res.string.privacy_product_app),
    SITE("site", Res.string.privacy_product_site),
    BOTH("both", Res.string.privacy_product_both),
}

enum class PolicyData(val key: String, val title: StringResource) {
    ANALYTICS("analytics", Res.string.privacy_data_analytics),
    CRASHES("crashes", Res.string.privacy_data_crashes),
    ADS("ads", Res.string.privacy_data_ads),
    ACCOUNTS("accounts", Res.string.privacy_data_accounts),
    LOCATION("location", Res.string.privacy_data_location),
    CAMERA("camera", Res.string.privacy_data_camera),
    PURCHASES("purchases", Res.string.privacy_data_purchases),
}

enum class PolicySdk(val key: String, val title: StringResource, val data: PolicyData, val app: Boolean, val site: Boolean) {
    FIREBASE_ANALYTICS("firebase-analytics", Res.string.privacy_sdk_firebase_analytics, PolicyData.ANALYTICS, app = true, site = false),
    GOOGLE_ANALYTICS("google-analytics", Res.string.privacy_sdk_google_analytics, PolicyData.ANALYTICS, app = false, site = true),
    APPMETRICA("appmetrica", Res.string.privacy_sdk_appmetrica, PolicyData.ANALYTICS, app = true, site = false),
    YANDEX_METRICA("yandex-metrica", Res.string.privacy_sdk_yandex_metrica, PolicyData.ANALYTICS, app = false, site = true),
    CRASHLYTICS("crashlytics", Res.string.privacy_sdk_crashlytics, PolicyData.CRASHES, app = true, site = false),
    SENTRY("sentry", Res.string.privacy_sdk_sentry, PolicyData.CRASHES, app = true, site = true),
    ADMOB("admob", Res.string.privacy_sdk_admob, PolicyData.ADS, app = true, site = false),
    PLAY_BILLING("play-billing", Res.string.privacy_sdk_play_billing, PolicyData.PURCHASES, app = true, site = false),
    APP_STORE("app-store", Res.string.privacy_sdk_app_store, PolicyData.PURCHASES, app = true, site = false),
    ;

    fun fits(product: PolicyProduct): Boolean = when (product) {
        PolicyProduct.APP -> app
        PolicyProduct.SITE -> site
        PolicyProduct.BOTH -> true
    }
}

enum class PolicyDocument(val key: String, val title: StringResource, val fileName: String) {
    PRIVACY("privacy", Res.string.privacy_document_privacy, "privacy-policy"),
    TERMS("terms", Res.string.privacy_document_terms, "terms"),
}

enum class PolicyFormat(val title: String, val extension: String) {
    MARKDOWN("Markdown", "md"),
    HTML("HTML", "html"),
}

data class PolicyInput(
    val app: String = "",
    val developer: String = "",
    val contact: String = "",
    val country: String = "",
    val product: PolicyProduct = PolicyProduct.APP,
    val data: Set<PolicyData> = emptySet(),
    val sdks: Set<PolicySdk> = emptySet(),
    val children: Boolean = false,
    val inAppDeletion: Boolean = false,
    val deletionUrl: String = "",
    val date: String = "",
) {
    val usedSdks: List<PolicySdk> get() = PolicySdk.entries.filter { it in sdks && it.data in data && it.fits(product) }
}

class PolicyBlock(val condition: String, val text: String)

object PolicyTemplates {
    private val marker = Regex("^<!--\\s*(.*?)\\s*-->\\s*$")
    private val placeholder = Regex("\\{([a-z-]+)}")
    private val email = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val url = Regex("^https?://\\S+$")
    private val markdownSpecial = setOf('\\', '`', '*', '_', '[', ']', '<', '>')

    const val ALWAYS = "always"
    const val PLACEHOLDERS = "placeholders"

    fun blocks(template: String): List<PolicyBlock> {
        val blocks = mutableListOf<PolicyBlock>()
        var condition: String? = null
        val text = StringBuilder()
        fun flush() {
            condition?.let { blocks += PolicyBlock(it, text.toString().trim('\n')) }
            text.clear()
        }
        template.replace("\r\n", "\n").lines().forEach { line ->
            val found = marker.matchEntire(line)
            if (found != null) {
                flush()
                condition = found.groupValues[1]
            } else {
                text.append(line).append('\n')
            }
        }
        flush()
        return blocks
    }

    fun flags(input: PolicyInput): Set<String> = buildSet {
        add(input.product.key)
        if (input.product != PolicyProduct.SITE) add("app")
        if (input.product != PolicyProduct.APP) add("site")
        input.data.forEach { add(it.key) }
        if (input.data.isEmpty()) add("nothing")
        val sdks = input.usedSdks
        sdks.forEach { add("sdk:${it.key}") }
        if (sdks.isNotEmpty()) add("sdk")
        if (input.children) add("children")
        if (PolicyData.ACCOUNTS in input.data && input.inAppDeletion) add("in-app-deletion")
        if (PolicyData.ACCOUNTS in input.data && input.deletionUrl.isNotBlank()) add("deletion-url")
        if (input.country.isNotBlank()) add("country")
    }

    fun matches(condition: String, flags: Set<String>): Boolean {
        if (condition == ALWAYS) return true
        if (!condition.startsWith("if ")) return false
        return condition.removePrefix("if ").trim().split(Regex("\\s+")).all { term ->
            term.split('|').any { if (it.startsWith('!')) it.drop(1) !in flags else it in flags }
        }
    }

    fun select(template: String, flags: Set<String>): String {
        val texts = blocks(template).filter { matches(it.condition, flags) && it.text.isNotBlank() }.map { it.text }
        return buildString {
            texts.forEachIndexed { index, text ->
                if (index > 0) append(if (text.startsWith("- ") && texts[index - 1].lines().last().startsWith("- ")) "\n" else "\n\n")
                append(text)
            }
            append('\n')
        }
    }

    fun defaults(template: String): Map<String, String> =
        blocks(template).filter { it.condition == PLACEHOLDERS }
            .flatMap { it.text.lines() }
            .mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            .toMap()

    fun values(template: String, input: PolicyInput): Map<String, String> {
        val defaults = defaults(template)
        return mapOf(
            "app" to input.app,
            "developer" to input.developer,
            "contact" to input.contact,
            "country" to input.country,
            "date" to input.date,
            "deletion-url" to input.deletionUrl,
        ).mapValues { (key, value) -> value.trim().ifEmpty { defaults[key].orEmpty() } }
    }

    fun placeholders(text: String): Set<String> = placeholder.findAll(text).map { it.groupValues[1] }.toSet()

    private fun fill(text: String, values: Map<String, String>, render: (String) -> String): String =
        placeholder.replace(text) { found -> values[found.groupValues[1]]?.let(render) ?: found.value }

    fun markdown(template: String, input: PolicyInput): String {
        val values = values(template, input)
        return fill(select(template, flags(input)), values) { value ->
            when {
                email.matches(value) -> "[${escapeMarkdown(value)}](mailto:$value)"
                url.matches(value) -> "<$value>"
                else -> escapeMarkdown(value)
            }
        }
    }

    private fun escapeMarkdown(value: String): String = buildString {
        value.forEach { c ->
            if (c in markdownSpecial) append('\\')
            append(c)
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun html(template: String, input: PolicyInput, lang: String): String {
        val values = values(template, input)
        fun text(raw: String) = fill(escapeHtml(raw), values) { value ->
            when {
                email.matches(value) -> "<a href=\"mailto:${escapeHtml(value)}\">${escapeHtml(value)}</a>"
                url.matches(value) -> "<a href=\"${escapeHtml(value)}\">${escapeHtml(value)}</a>"
                else -> escapeHtml(value)
            }
        }
        fun inline(raw: String) = Markdown.spans(raw).joinToString("") { span ->
            when (span) {
                is MdSpan.Text -> text(span.text)
                is MdSpan.Bold -> "<strong>${text(span.text)}</strong>"
                is MdSpan.Italic -> "<em>${text(span.text)}</em>"
                is MdSpan.BoldItalic -> "<strong><em>${text(span.text)}</em></strong>"
                is MdSpan.Code -> "<code>${text(span.text)}</code>"
                is MdSpan.Strike -> "<s>${text(span.text)}</s>"
                is MdSpan.Link -> "<a href=\"${escapeHtml(fill(span.url, values) { it })}\">${text(span.text)}</a>"
            }
        }
        val blocks = Markdown.blocks(select(template, flags(input)))
        val title = blocks.filterIsInstance<MdBlock.Heading>().firstOrNull()?.text.orEmpty()
        val body = StringBuilder()
        var list = false
        blocks.forEach { block ->
            if (list && block !is MdBlock.ListItem) {
                body.append("</ul>\n")
                list = false
            }
            when (block) {
                is MdBlock.Heading -> body.append("<h${block.level}>${inline(block.text)}</h${block.level}>\n")
                is MdBlock.Paragraph -> body.append("<p>${inline(block.text)}</p>\n")
                is MdBlock.ListItem -> {
                    if (!list) body.append("<ul>\n")
                    list = true
                    body.append("<li>${inline(block.text)}</li>\n")
                }
                is MdBlock.Quote -> body.append("<blockquote>${inline(block.text)}</blockquote>\n")
                is MdBlock.Code -> body.append("<pre>${escapeHtml(block.code)}</pre>\n")
                MdBlock.Rule -> body.append("<hr>\n")
            }
        }
        if (list) body.append("</ul>\n")
        val plainTitle = fill(title, values) { it }
        return """
            |<!DOCTYPE html>
            |<html lang="$lang">
            |<head>
            |<meta charset="utf-8">
            |<meta name="viewport" content="width=device-width, initial-scale=1">
            |<title>${escapeHtml("$plainTitle · ${values["app"].orEmpty()}")}</title>
            |<style>body{max-width:720px;margin:0 auto;padding:24px 16px;font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}</style>
            |</head>
            |<body>
            |
        """.trimMargin() + body + "</body>\n</html>\n"
    }
}
