import groovy.json.JsonSlurper
import java.time.Year

// the site's personal pages as plain HTML: the page shows them while the wasm loads, keeps them when it cannot
// run, and crawlers, find in page and the translator read them. Markup, sizes and colours follow the Compose
// screens, the tools and settings tabs exist only in the app

val content = rootProject.layout.projectDirectory.dir("shared/src/commonMain/composeResources")
val stringFiles = mapOf("ru" to "values-ru/strings.xml", "en" to "values/strings.xml")

fun read(path: String): String = providers.fileContents(content.file(path)).asText.get()

val strings = stringFiles.mapValues { (_, path) ->
    Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""").findAll(read(path)).associate { it.groupValues[1] to it.groupValues[2] }
}
val plurals = stringFiles.mapValues { (_, path) ->
    Regex("""<plurals name="([a-z0-9_]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(read(path)).associate { plural ->
        plural.groupValues[1] to Regex("""<item quantity="([a-z]+)">(.*?)</item>""").findAll(plural.groupValues[2])
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}

@Suppress("UNCHECKED_CAST")
val profile = JsonSlurper().parseText(read("files/profile.json")) as Map<String, Any?>
@Suppress("UNCHECKED_CAST")
val resume = JsonSlurper().parseText(read("files/resume.json")) as Map<String, Any?>
val year = Year.now().value

typealias Json = Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun list(value: Any?): List<Json> = value as List<Json>? ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun texts(value: Any?): List<String> = value as List<String>? ?: emptyList()

fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun tr(value: Any?, lang: String): String = if (value is Map<*, *>) (value[lang] ?: value["en"]).toString() else value?.toString().orEmpty()

fun quantity(n: Int, lang: String): String = when {
    lang != "ru" -> if (n == 1) "one" else "other"
    n % 10 == 1 && n % 100 != 11 -> "one"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
    else -> "many"
}

fun plural(name: String, count: Int, lang: String, argument: String? = null): String {
    val forms = plurals.getValue(lang).getValue(name)
    val form = forms[quantity(count, lang)] ?: forms.getValue("other")
    return if (argument == null) form else form.replace("%1\$s", argument)
}

// the same as parseCount and formatCount in core/Format.kt
fun parseCount(text: String?): Int? {
    val t = text?.trim()?.removeSuffix("+")?.replace(',', '.')?.takeIf { it.isNotEmpty() } ?: return null
    val multiplier = when (t.last().uppercaseChar()) {
        'K' -> 1_000
        'M' -> 1_000_000
        else -> 1
    }
    val number = (if (multiplier == 1) t else t.dropLast(1)).toDoubleOrNull() ?: return null
    return Math.round(number * multiplier).toInt()
}

fun formatCount(n: Int, lang: String): String {
    val space = Char(160)
    fun short(value: Int, unit: Int, suffix: String): String {
        val tenths = (value + unit / 20) / (unit / 10)
        return if (tenths < 100 && tenths % 10 != 0) "${tenths / 10}.${tenths % 10}$suffix" else "${(value + unit / 2) / unit}$suffix"
    }
    return when {
        n < 1_000 -> n.toString()
        n < 999_500 -> short(n, 1_000, if (lang == "ru") "${space}тыс." else "K")
        else -> short(n, 1_000_000, if (lang == "ru") "${space}млн" else "M")
    }
}

// Material Icons, the filled set the app draws plus the outlined ones of the bar
val iconPaths = mapOf(
    "home" to "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
    "home-off" to "M12 5.69l5 4.5V18h-2v-6H9v6H7v-7.81l5-4.5M12 3L2 12h3v8h6v-6h2v6h6v-8h3L12 3z",
    "widgets" to "M13 13v8h8v-8h-8zM3 21h8v-8H3v8zM3 3v8h8V3H3zm13.66-1.31L11 7.34 16.66 13l5.66-5.66-5.66-5.65z",
    "widgets-off" to "M16.66 4.52l2.83 2.83-2.83 2.83-2.83-2.83 2.83-2.83M9 5v4H5V5h4m10 10v4h-4v-4h4M9 15v4H5v-4h4m7.66-13.31L11 7.34 16.66 13l5.66-5.66-5.66-5.65zM11 3H3v8h8V3zm10 10h-8v8h8v-8zm-10 0H3v8h8v-8z",
    "description" to "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z",
    "description-off" to "M8 16h8v2H8zm0-4h8v2H8zm6-10H6c-1.1 0-2 .9-2 2v16c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11z",
    "translate" to "M12.87 15.07l-2.54-2.51.03-.03c1.74-1.94 2.98-4.17 3.71-6.53H17V4h-7V2H8v2H1v1.99h11.17C11.5 7.92 10.44 9.75 9 11.35 8.07 10.32 7.3 9.19 6.69 8h-2c.73 1.63 1.73 3.17 2.98 4.56l-5.09 5.02L4 19l5-5 3.11 3.11.76-2.04zM18.5 10h-2L12 22h2l1.12-3h4.75L21 22h2l-4.5-12zm-2.62 7l1.62-4.33L19.12 17h-3.24z",
    "dark" to "M12,3c-4.97,0-9,4.03-9,9s4.03,9,9,9s9-4.03,9-9c0-0.46-0.04-0.92-0.1-1.36c-0.98,1.37-2.58,2.26-4.4,2.26 c-2.98,0-5.4-2.42-5.4-5.4c0-1.81,0.89-3.42,2.26-4.4C12.92,3.04,12.46,3,12,3L12,3z",
    "light" to "M12,7c-2.76,0-5,2.24-5,5s2.24,5,5,5s5-2.24,5-5S14.76,7,12,7L12,7z M2,13l2,0c0.55,0,1-0.45,1-1s-0.45-1-1-1l-2,0 c-0.55,0-1,0.45-1,1S1.45,13,2,13z M20,13l2,0c0.55,0,1-0.45,1-1s-0.45-1-1-1l-2,0c-0.55,0-1,0.45-1,1S19.45,13,20,13z M11,2v2 c0,0.55,0.45,1,1,1s1-0.45,1-1V2c0-0.55-0.45-1-1-1S11,1.45,11,2z M11,20v2c0,0.55,0.45,1,1,1s1-0.45,1-1v-2c0-0.55-0.45-1-1-1 C11.45,19,11,19.45,11,20z M5.99,4.58c-0.39-0.39-1.03-0.39-1.41,0c-0.39,0.39-0.39,1.03,0,1.41l1.06,1.06 c0.39,0.39,1.03,0.39,1.41,0s0.39-1.03,0-1.41L5.99,4.58z M18.36,16.95c-0.39-0.39-1.03-0.39-1.41,0c-0.39,0.39-0.39,1.03,0,1.41 l1.06,1.06c0.39,0.39,1.03,0.39,1.41,0c0.39-0.39,0.39-1.03,0-1.41L18.36,16.95z M19.42,5.99c0.39-0.39,0.39-1.03,0-1.41 c-0.39-0.39-1.03-0.39-1.41,0l-1.06,1.06c-0.39,0.39-0.39,1.03,0,1.41s1.03,0.39,1.41,0L19.42,5.99z M7.05,18.36 c0.39-0.39,0.39-1.03,0-1.41c-0.39-0.39-1.03-0.39-1.41,0l-1.06,1.06c-0.39,0.39-0.39,1.03,0,1.41s1.03,0.39,1.41,0L7.05,18.36z",
    "place" to "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
    "send" to "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z",
    "email" to "M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z",
    "copy" to "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z",
    "check" to "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
    "code" to "M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z",
    "article" to "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z",
    "shop" to "M16 6V4c0-1.11-.89-2-2-2h-4c-1.11 0-2 .89-2 2v2H2v13c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V6h-6zm-6-2h4v2h-4V4zM9 18V9l7.5 4L9 18z",
    "phone" to "M15.5 1h-8C6.12 1 5 2.12 5 3.5v17C5 21.88 6.12 23 7.5 23h8c1.38 0 2.5-1.12 2.5-2.5v-17C18 2.12 16.88 1 15.5 1zm-4 21c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm4.5-4H7V4h9v14z",
    "store" to "M21.9,8.89l-1.05-4.37c-0.22-0.9-1-1.52-1.91-1.52H5.05C4.15,3,3.36,3.63,3.15,4.52L2.1,8.89 c-0.24,1.02-0.02,2.06,0.62,2.88C2.8,11.88,2.91,11.96,3,12.06V19c0,1.1,0.9,2,2,2h14c1.1,0,2-0.9,2-2v-6.94 c0.09-0.09,0.2-0.18,0.28-0.28C21.92,10.96,22.15,9.91,21.9,8.89z M18.91,4.99l1.05,4.37c0.1,0.42,0.01,0.84-0.25,1.17 C19.57,10.71,19.27,11,18.77,11c-0.61,0-1.14-0.49-1.21-1.14L16.98,5L18.91,4.99z M13,5h1.96l0.54,4.52 c0.05,0.39-0.07,0.78-0.33,1.07C14.95,10.85,14.63,11,14.22,11C13.55,11,13,10.41,13,9.69V5z M8.49,9.52L9.04,5H11v4.69 C11,10.41,10.45,11,9.71,11c-0.34,0-0.65-0.15-0.89-0.41C8.57,10.3,8.45,9.91,8.49,9.52z M4.04,9.36L5.05,5h1.97L6.44,9.86 C6.36,10.51,5.84,11,5.23,11c-0.49,0-0.8-0.29-0.93-0.47C4.03,10.21,3.94,9.78,4.04,9.36z M5,19v-6.03C5.08,12.98,5.15,13,5.23,13 c0.87,0,1.66-0.36,2.24-0.95c0.6,0.6,1.4,0.95,2.31,0.95c0.87,0,1.65-0.36,2.23-0.93c0.59,0.57,1.39,0.93,2.29,0.93 c0.84,0,1.64-0.35,2.24-0.95c0.58,0.59,1.37,0.95,2.24,0.95c0.08,0,0.15-0.02,0.23-0.03V19H5z",
    "favorite" to "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
    "campaign" to "M18 11v2h4v-2h-4zm-2 6.61c.96.71 2.21 1.65 3.2 2.39.4-.53.8-1.07 1.2-1.6-.99-.74-2.24-1.68-3.2-2.4-.4.54-.8 1.08-1.2 1.61zM20.4 5.6c-.4-.53-.8-1.07-1.2-1.6-.99.74-2.24 1.68-3.2 2.4.4.53.8 1.07 1.2 1.6.96-.72 2.21-1.65 3.2-2.4zM4 9c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2h1v4h2v-4h1l5 3V6L8 9H4zm11.5 3c0-1.33-.58-2.53-1.5-3.35v6.69c.92-.81 1.5-2.01 1.5-3.34z",
    "call" to "M20.01 15.38c-1.23 0-2.42-.2-3.53-.56-.35-.12-.74-.03-1.01.24l-1.57 1.97c-2.83-1.35-5.48-3.9-6.89-6.83l1.95-1.66c.27-.28.35-.67.24-1.02-.37-1.11-.56-2.3-.56-3.53 0-.54-.45-.99-.99-.99H4.19C3.65 3 3 3.24 3 3.99 3 13.28 10.73 21 20.01 21c.71 0 .99-.63.99-1.18v-3.45c0-.54-.45-.99-.99-.99z",
    "language" to "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2 0 .68.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.9-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2 0-.68.07-1.35.16-2h4.68c.09.65.16 1.32.16 2 0 .68-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2 0-.68-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z",
    "open" to "M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z",
    "arrow" to "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z",
    "star" to "M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
    "download" to "M5,20h14v-2H5V20z M19,9h-4V3H9v6H5l7,7L19,9z",
    "pdf" to "M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8.5 7.5c0 .83-.67 1.5-1.5 1.5H9v2H7.5V7H10c.83 0 1.5.67 1.5 1.5v1zm5 2c0 .83-.67 1.5-1.5 1.5h-2.5V7H15c.83 0 1.5.67 1.5 1.5v3zm4-3H19v1h1.5V11H19v2h-1.5V7h3v1.5zM9 9.5h1v-1H9v1zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm10 5.5h1v-3h-1v3z",
)

// the chevron in front of the name, ui/components/Mark.kt PromptChevron
val sprite = """<svg class="pre-sprite" aria-hidden="true">""" +
    iconPaths.entries.joinToString("") { (name, d) -> """<symbol id="i-$name" viewBox="0 0 24 24"><path d="$d"/></symbol>""" } +
    """<symbol id="i-prompt" viewBox="0 0 170 295"><path d="M25 25L145 145.6 25 266.25" fill="none" stroke="currentColor" stroke-width="50" stroke-linecap="round" stroke-linejoin="round"/></symbol>""" +
    "</svg>"

fun icon(name: String, cls: String = "") = """<svg class="pre-icon$cls" aria-hidden="true"><use href="#i-$name"/></svg>"""

fun linkIcon(type: String) = when (type) {
    "telegram" -> "send"
    "github" -> "code"
    "habr" -> "article"
    "email" -> "email"
    "googleplay" -> "shop"
    "appstore" -> "phone"
    "rustore", "msstore" -> "store"
    "support" -> "favorite"
    "channel" -> "campaign"
    "phone" -> "call"
    "resume" -> "description"
    "website" -> "language"
    else -> "open"
}

fun external(url: String) = if (url.startsWith("http")) """ target="_blank" rel="noopener"""" else ""

fun hint(url: String): String? = when {
    url.startsWith("https://t.me/") -> "@" + url.trimEnd('/').substringAfterLast('/')
    url.startsWith("http") -> url.substringAfter("://").substringBefore('/').removePrefix("www.")
    else -> null
}

val contactTypes = setOf("telegram", "email", "phone")
val links = list(profile["links"]).filter { it["active"] != false }
val contacts = links.filter { it["type"] in contactTypes }
val resources = links.filter { it["type"] !in contactTypes && it["type"] != "support" }
val support = links.firstOrNull { it["type"] == "support" }
val articles = list(profile["articles"]).sortedByDescending { it["date"].toString() }

fun page(lang: String): String {
    val s = strings.getValue(lang)

    fun label(link: Json) = link["label"]?.let { tr(it, lang) } ?: s["link_${link["type"]}"] ?: link["type"].toString()

    // the address is copied rather than opened, as in the app: plenty of visitors have no mail client
    fun linkRow(link: Json): String {
        val url = link["url"].toString()
        val email = link["type"] == "email"
        val text = if (email) url.removePrefix("mailto:") else label(link)
        val note = if (email) """<span class="pre-link-hint pre-copied">${s["copied"]}</span>"""
        else hint(url)?.let { """<span class="pre-link-hint">${esc(it)}</span>""" }.orEmpty()
        val copy = if (email) icon("copy", " pre-copy-icon") + icon("check", " pre-copy-icon pre-copied") else ""
        return """<a class="pre-link pre-ripple" href="${esc(url)}"${if (email) " data-copy" else external(url)}>""" +
            icon(linkIcon(link["type"].toString()), " pre-link-icon") +
            """<span class="pre-link-text"><span class="t-bl${if (email) " pre-address" else ""}">${esc(text)}</span>$note</span>$copy</a>"""
    }

    fun chip(link: Json): String {
        val url = link["url"].toString()
        return """<a class="pre-chip pre-ripple t-ll" href="${esc(url)}"${external(url)}>${icon(linkIcon(link["type"].toString()))}<span>${esc(label(link))}</span></a>"""
    }

    fun tags(items: List<String>) = """<div class="pre-tags">""" + items.joinToString("") { """<span class="pre-tag t-lm">${esc(it)}</span>""" } + "</div>"

    fun sectionTitle(text: String, action: String = "") =
        """<div class="pre-section-title"><h2 class="t-tl">$text</h2>$action</div>"""

    fun seeAll(tab: String) =
        """<a class="pre-text-button pre-ripple t-ll" href="#projects" data-tab="$tab"><span>${s.getValue("see_all")}</span>${icon("arrow", " pre-icon-small")}</a>"""

    fun outlinedButton(link: Json) = """<a class="pre-outlined-button pre-ripple t-ll" href="${esc(link["url"].toString())}"${external(link["url"].toString())}>""" +
        icon("favorite") + """<span>${esc(label(link))}</span></a>"""

    fun project(project: Json, condensed: Boolean): String {
        val stars = project["stars"]?.toString()?.toIntOrNull()?.takeIf { it > 0 }
        val head = """<div class="pre-project-head"><h3 class="t-tl">${esc(project["name"].toString())}</h3>""" +
            (stars?.let { """<span class="pre-stars t-ll">${icon("star", " pre-icon-small")}$it</span>""" }.orEmpty()) + "</div>"
        val meta = listOf(project["year"]?.toString().orEmpty(), texts(project["platforms"]).joinToString(" · "))
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        val downloads = project["downloads"]?.toString()
        val tech = texts(project["tech"]).let { if (condensed) it.take(4) else it }
        val projectLinks = list(project["links"]).filter { it["active"] != false }
        return """<article class="pre-card pre-project">$head""" +
            """<p class="pre-tagline t-ts${if (condensed) " pre-clamp-3" else ""}">${esc(tr(project["tagline"], lang))}</p>""" +
            (if (condensed) "" else """<p class="t-bm">${esc(tr(project["description"], lang))}</p>""") +
            (if (meta.isBlank()) "" else """<p class="pre-meta t-lm">${esc(meta)}</p>""") +
            (downloads?.let {
                """<p class="pre-installs t-ll">${icon("download", " pre-icon-small")}${esc(plural("installs_count", parseCount(it) ?: 0, lang, it))}</p>"""
            }.orEmpty()) +
            (if (tech.isEmpty()) "" else tags(tech)) +
            (if (projectLinks.isEmpty()) "" else """<div class="pre-chips">${projectLinks.joinToString("") { chip(it) }}</div>""") +
            "</article>"
    }

    fun article(article: Json): String {
        val url = article["url"].toString()
        val views = parseCount(article["views"]?.toString())?.let { count ->
            val shown = formatCount(count, lang)
            " · " + plural("views_count", parseCount(shown) ?: count, lang, shown)
        }.orEmpty()
        return """<a class="pre-card pre-article pre-ripple" href="${esc(url)}"${external(url)}>""" +
            icon(linkIcon(article["source"]?.toString() ?: "habr"), " pre-article-icon") +
            """<span class="pre-article-text"><span class="pre-article-title t-tm">${esc(tr(article["title"], lang))}</span>""" +
            (article["summary"]?.let { """<span class="pre-article-summary t-bm">${esc(tr(it, lang))}</span>""" }.orEmpty()) +
            """<span class="pre-meta t-lm">${esc(article["date"].toString() + views)}</span></span></a>"""
    }

    fun contactCard(title: String, body: String): String = if (contacts.isEmpty()) "" else
        """<section class="pre-card pre-contact"><div class="pre-contact-head"><h2 class="t-tl">$title</h2><p class="t-bm">$body</p></div>""" +
            contacts.joinToString("") { linkRow(it) } + "</section>"

    val header = """<div class="pre-header"><span class="pre-brand t-hs">${s.getValue("app_name")}</span>""" +
        """<button type="button" class="pre-text-button pre-ripple t-ll" data-action="lang">${icon("translate", " pre-icon-small")}<span>${if (lang == "ru") "EN" else "RU"}</span></button>""" +
        """<button type="button" class="pre-icon-button pre-ripple" data-action="theme" aria-label="${s.getValue("theme")}">${icon("dark", " pre-to-dark")}${icon("light", " pre-to-light")}</button>""" +
        """<span class="pre-icon-button pre-settings-slot" aria-hidden="true"></span></div>"""

    val person = profile["person"] as Json
    @Suppress("UNCHECKED_CAST")
    val avatar = profile["avatar"] as Json?
    val avatarImage = avatar?.takeIf { it["active"] == true }?.let {
        val url = it["url"]?.toString().orEmpty()
        val src = when {
            url.isBlank() -> "avatar.jpg"
            url.startsWith("http") -> url
            else -> "composeResources/com.vasmarfas.card.resources/files/$url"
        }
        """<img class="pre-avatar" src="${esc(src)}" alt="">"""
    }.orEmpty()
    val hero = """<section class="pre-hero"><div class="pre-hero-head">$avatarImage<div class="pre-hero-text">""" +
        """<div class="pre-prompt"><svg class="pre-chevron" aria-hidden="true"><use href="#i-prompt"/></svg><h1 class="pre-name">${esc(tr(person["name"], lang))}</h1><span class="pre-cursor" aria-hidden="true"></span></div>""" +
        """<p class="t-tm">${esc(tr(person["title"], lang))}</p>""" +
        """<p class="pre-location t-bm">${icon("place", " pre-icon-small")}<span>${esc(tr(person["location"], lang))}</span></p></div></div>""" +
        """<div class="pre-hero-blocks">""" +
        (if (contacts.isEmpty()) "" else """<section class="pre-card pre-block pre-block-contacts"><h2 class="t-tm">${s.getValue("contact_me")}</h2><div class="pre-block-links">${contacts.joinToString("") { linkRow(it) }}</div></section>""") +
        (if (resources.isEmpty()) "" else """<section class="pre-card pre-block pre-block-resources"><h2 class="t-tm">${s.getValue("my_resources")}</h2><div class="pre-block-links">${resources.joinToString("") { linkRow(it) }}</div></section>""") +
        "</div></section>"

    @Suppress("UNCHECKED_CAST")
    val milestones = profile["milestones"] as Json? ?: emptyMap()
    val projects = list(profile["projects"])
    val stars = projects.sumOf { it["stars"]?.toString()?.toIntOrNull() ?: 0 }
    val views = articles.sumOf { parseCount(it["views"]?.toString()) ?: 0 }
    val installs = projects.sumOf { parseCount(it["downloads"]?.toString()) ?: 0 }
    val stats = listOfNotNull(
        (year - (milestones["androidSince"]?.toString()?.toIntOrNull() ?: 2022)).takeIf { it > 0 }?.let { "$it+" to "years_in_android_kmp" },
        (year - (milestones["sysadminSince"]?.toString()?.toIntOrNull() ?: 2018)).takeIf { it > 0 }?.let { "$it+" to "years_of_sysadmin_work" },
        stars.takeIf { it > 0 }?.let { it.toString() to "github_stars" },
        articles.size.toString() to "habr_articles",
        views.takeIf { it > 0 }?.let { formatCount(it, lang) to "article_views" },
        installs.takeIf { it > 0 }?.let { formatCount(it, lang) + "+" to "app_installs" },
    )
    val statsStrip = """<section class="pre-card pre-stats"><div class="pre-stats-grid">""" + stats.joinToString("") { (value, name) ->
        """<div class="pre-stat"><span class="pre-stat-value t-hm">${esc(value)}</span><span class="pre-stat-label t-bs">${plural(name, parseCount(value) ?: 0, lang)}</span></div>"""
    } + "</div></section>"

    val featured = projects.filter { it["featured"] == true }.ifEmpty { projects.take(4) }
    val featuredSection = """<section class="pre-section">${sectionTitle(s.getValue("featured_projects"), seeAll("projects"))}""" +
        """<div class="pre-columns">${featured.joinToString("") { project(it, condensed = true) }}</div></section>"""
    val supportLine = support?.let {
        """<div class="pre-support"><span class="t-bm">${s.getValue("support_projects")}</span>${outlinedButton(it)}</div>"""
    }.orEmpty()
    val latest = if (articles.isEmpty()) "" else
        """<section class="pre-section pre-section-tight">${sectionTitle(s.getValue("latest_articles"), seeAll("articles"))}${articles.take(3).joinToString("") { article(it) }}</section>"""
    val services = list(profile["services"]).filter { it["active"] != false }
    val servicesSection = if (services.isEmpty()) "" else
        """<section class="pre-section">${sectionTitle(s.getValue("services_title"))}<div class="pre-columns">""" + services.joinToString("") { service ->
            """<section class="pre-card pre-service"><h3 class="t-tm">${esc(tr(service["title"], lang))}</h3><p class="t-bm">${esc(tr(service["text"], lang))}</p></section>"""
        } + "</div></section>"
    val teaser = """<a class="pre-card pre-teaser pre-ripple" href="#resume"><span class="pre-teaser-text"><span class="t-ll">${s.getValue("resume_page")}</span>""" +
        """<span class="t-tm">${s.getValue("experience_education_skills")}</span></span>${icon("arrow")}</a>"""
    val about = """<section class="pre-section">${sectionTitle(s.getValue("about_me"))}""" +
        (listOf(person["bio"]) + (person["about"] as List<*>? ?: emptyList<Any?>())).joinToString("") { """<p class="t-bl">${esc(tr(it, lang))}</p>""" } +
        "</section>"
    val footerLinks = contacts.joinToString("") { link ->
        val url = link["url"].toString()
        if (link["type"] == "email") {
            """<a class="pre-text-button pre-footer-button pre-ripple" href="${esc(url)}" data-copy>${icon("email", " pre-icon-small pre-copy-icon")}${icon("check", " pre-icon-small pre-copy-icon pre-copied")}""" +
                """<span class="pre-address">${esc(url.removePrefix("mailto:"))}</span><span class="pre-copied">${s["copied"]}</span></a>"""
        } else {
            """<a class="pre-text-button pre-footer-button pre-ripple" href="${esc(url)}"${external(url)}>${icon(linkIcon(link["type"].toString()), " pre-icon-small")}<span>${esc(label(link))}</span></a>"""
        }
    } + """<a class="pre-text-button pre-footer-button pre-ripple" href="https://github.com/vasmarfas/mobitool" target="_blank" rel="noopener">${icon("code", " pre-icon-small")}<span>${s.getValue("source_code")}</span></a>"""
    val footer = """<footer class="pre-footer"><p class="t-lm">${s.getValue("built_with")}</p><div class="pre-footer-links">$footerLinks</div><p class="t-ls">© $year vasmarfas</p></footer>"""

    val home = """<div class="pre-page pre-home" data-page="home">$header$hero$statsStrip$featuredSection$supportLine$latest$servicesSection""" +
        contactCard(s.getValue("contact_title"), s.getValue("contact_body")) + teaser + about + footer + "</div>"

    val supportCard = support?.let {
        """<section class="pre-card pre-support-card"><h3 class="t-tl">${s.getValue("projects_support_title")}</h3><p class="t-bm">${s.getValue("projects_support_body")}</p>${outlinedButton(it)}</section>"""
    }.orEmpty()
    val projectsPage = """<div class="pre-page pre-projects" data-page="projects"><h2 class="t-hs">${s.getValue("projects")}</h2>""" +
        """<div class="pre-segmented" role="group"><button type="button" class="pre-segment pre-ripple t-ll" data-tab="projects" aria-pressed="true">${s.getValue("projects")}</button>""" +
        """<button type="button" class="pre-segment pre-ripple t-ll" data-tab="articles" aria-pressed="false">${s.getValue("articles")}</button></div>""" +
        """<div class="pre-tab pre-tab-projects">${projects.joinToString("") { project(it, condensed = false) }}$supportCard""" +
        contactCard(s.getValue("projects_order_title"), s.getValue("projects_order_body")) + "</div>" +
        """<div class="pre-tab pre-tab-articles">${articles.joinToString("") { article(it) }}</div></div>"""

    fun month(value: String): String {
        val parts = value.split('-')
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return parts[0]
        return "${s["month_short_$month"] ?: month} ${parts[0]}"
    }

    fun education(item: Json): String {
        val meta = listOfNotNull(item["year"]?.toString(), item["note"]?.let { tr(it, lang) }).joinToString(" · ")
        return """<section class="pre-card pre-education"><h4 class="t-tm">${esc(tr(item["degree"], lang))}</h4>""" +
            """<p class="pre-accent t-bm">${esc(tr(item["institution"], lang))}</p><p class="pre-meta t-lm">${esc(meta)}</p></section>"""
    }

    val experience = list(resume["experience"]).sortedWith(
        compareByDescending<Json> { it["from"].toString() }.thenByDescending { it["to"]?.toString() ?: "9999-99" },
    ).joinToString("") { item ->
        val url = item["url"]?.toString()
        val period = "${month(item["from"].toString())} – ${item["to"]?.let { month(it.toString()) } ?: s.getValue("present")}" +
            (item["location"]?.let { " · " + tr(it, lang) }.orEmpty())
        """<section class="pre-card pre-experience"><h4 class="t-tl">${esc(tr(item["role"], lang))}</h4>""" +
            """<p class="pre-company"><span class="pre-accent t-tm">${esc(tr(item["company"], lang))}</span>""" +
            (url?.let { """<a class="pre-icon-button pre-ripple" href="${esc(it)}" target="_blank" rel="noopener" aria-label="${s.getValue("open")}">${icon("open", " pre-icon-small")}</a>""" }.orEmpty()) +
            """</p><p class="pre-meta t-ll">${esc(period)}</p>""" +
            (item["summary"]?.let { """<p class="t-bm">${esc(tr(it, lang))}</p>""" }.orEmpty()) +
            (item["bullets"] as List<*>? ?: emptyList<Any?>()).joinToString("") { """<p class="pre-bullet t-bm">${esc(tr(it, lang))}</p>""" } +
            (texts(item["tags"]).takeIf { it.isNotEmpty() }?.let { tags(it) }.orEmpty()) +
            "</section>"
    }
    fun resumeSection(title: String, body: String, tight: Boolean = false) =
        if (body.isEmpty()) "" else """<section class="pre-section${if (tight) " pre-section-tight" else ""}">${sectionTitle(title)}$body</section>"""
    val pdf = resume["pdf"]?.toString()?.let { url ->
        """<a class="pre-outlined-button pre-ripple t-ll" href="${esc(url)}" target="_blank" rel="noopener">${icon("pdf")}<span>${s.getValue("resume_pdf")}</span></a>"""
    }.orEmpty()
    val skills = list(resume["skills"]).joinToString("") { group -> """<h3 class="t-tm">${esc(tr(group["title"], lang))}</h3>${tags(texts(group["items"]))}""" }
    val languages = list(resume["languages"]).map { "${tr(it["name"], lang)} — ${tr(it["level"], lang)}" }
    val resumePage = """<div class="pre-page pre-resume" data-page="resume"><div class="pre-page-head"><h2 class="t-hs">${s.getValue("resume_page")}</h2>$pdf</div>""" +
        (resume["summary"]?.let { """<p class="t-bl">${esc(tr(it, lang))}</p>""" }.orEmpty()) +
        resumeSection(s.getValue("experience"), experience) +
        resumeSection(s.getValue("education"), list(resume["education"]).joinToString("") { education(it) }) +
        resumeSection(s.getValue("courses_and_certificates"), list(resume["courses"]).joinToString("") { education(it) }) +
        resumeSection(s.getValue("skills"), skills) +
        resumeSection(s.getValue("languages"), if (languages.isEmpty()) "" else tags(languages), tight = true) +
        "</div>"

    val nav = """<nav class="pre-nav" aria-label="${if (lang == "ru") "Разделы сайта" else "Site sections"}">""" + listOf(
        Triple("home", "home", "home"),
        Triple("projects", "widgets", "projects"),
        Triple("resume", "description", "resume_page"),
    ).joinToString("") { (route, glyph, title) ->
        """<a class="pre-nav-item" href="#$route" data-route="$route"><span class="pre-nav-icon">${icon("$glyph-off", " pre-off")}${icon(glyph, " pre-on")}</span>""" +
            """<span class="pre-nav-label">${s.getValue(title)}</span></a>"""
    } + "</nav>"

    val download = """<a href="download.html">${if (lang == "ru") "страница загрузки" else "download page"}</a>"""
    val notices = if (lang == "ru") {
        """<p class="pre-notice t-bm" data-notice="unsupported">Этот браузер не поддерживает WebAssembly GC, поэтому утилиты на сайте не откроются. Обновите браузер (Chrome 119+, Firefox 120+, Safari 18.2+) или поставьте приложение: $download.</p>""" +
            """<p class="pre-notice t-bm" data-notice="failed">Не удалось загрузить приложение. Проверьте соединение и обновите страницу.</p>""" +
            """<noscript><p class="pre-notice pre-notice-static t-bm">Утилиты на сайте работают на WebAssembly и требуют JavaScript. Приложения для телефона и компьютера: $download.</p></noscript>"""
    } else {
        """<p class="pre-notice t-bm" data-notice="unsupported">This browser does not support WebAssembly GC, so the site's tools will not open here. Update it (Chrome 119+, Firefox 120+, Safari 18.2+) or install the app: $download.</p>""" +
            """<p class="pre-notice t-bm" data-notice="failed">Could not load the app. Check the connection and reload the page.</p>""" +
            """<noscript><p class="pre-notice pre-notice-static t-bm">The site's tools run on WebAssembly and need JavaScript. Apps for phones and computers: $download.</p></noscript>"""
    }

    return """<div class="pre" lang="$lang">$nav<main class="pre-main">$notices$home$projectsPage$resumePage</main></div>"""
}

extra["prerenderedProfile"] = "    " + sprite + "\n" + stringFiles.keys.joinToString("\n") { "    " + page(it) }
