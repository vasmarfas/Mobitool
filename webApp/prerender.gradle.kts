import groovy.json.JsonSlurper

// the about page from profile.json as plain HTML: shown while the wasm loads, and the only text
// crawlers, find in page, the translator and Webvisor get to read

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

fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun tr(value: Any?, lang: String): String = if (value is Map<*, *>) (value[lang] ?: value["en"]).toString() else value?.toString().orEmpty()

fun quantity(n: Int, lang: String): String = when {
    lang != "ru" -> if (n == 1) "one" else "other"
    n % 10 == 1 && n % 100 != 11 -> "one"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
    else -> "many"
}

fun countOf(text: String): Int {
    val t = text.removeSuffix("+").replace(',', '.')
    val multiplier = when (t.lastOrNull()?.uppercaseChar()) {
        'K' -> 1_000
        'M' -> 1_000_000
        else -> 1
    }
    return ((if (multiplier == 1) t else t.dropLast(1)).toDoubleOrNull()?.times(multiplier) ?: 0.0).toInt()
}

@Suppress("UNCHECKED_CAST")
fun page(lang: String): String {
    val s = strings.getValue(lang)
    val person = profile["person"] as Map<String, Any?>
    val links = (profile["links"] as List<Map<String, Any?>>).filter { it["active"] != false }
    fun label(link: Map<String, Any?>) = link["label"]?.let { tr(it, lang) } ?: s["link_${link["type"]}"] ?: link["type"].toString()
    fun anchor(link: Map<String, Any?>) = """<a href="${esc(link["url"].toString())}">${esc(label(link))}</a>"""
    val contactTypes = setOf("telegram", "email", "phone")
    val contactLinks = links.filter { it["type"] in contactTypes }
    fun contactAnchor(link: Map<String, Any?>): String {
        val url = link["url"].toString()
        return if (link["type"] == "email") """<a href="${esc(url)}" data-show-address>${esc(url.removePrefix("mailto:"))}</a>""" else anchor(link)
    }
    fun hint(link: Map<String, Any?>): String {
        val url = link["url"].toString()
        val text = when {
            url.startsWith("https://t.me/") -> "@" + url.trimEnd('/').substringAfterLast('/')
            url.startsWith("http") -> url.substringAfter("://").substringBefore('/').removePrefix("www.")
            else -> return ""
        }
        return """ <span class="pre-muted">${esc(text)}</span>"""
    }
    val contacts = contactLinks.joinToString("") { link -> "<li>${contactAnchor(link)}${hint(link)}</li>" }
    val resources = links.filter { it["type"] !in contactTypes && it["type"] != "support" }.joinToString("") { "<li>${anchor(it)}${hint(it)}</li>" }
    val projects = (profile["projects"] as List<Map<String, Any?>>).filter { it["featured"] == true }.joinToString("") { project ->
        val meta = listOf(project["year"]?.toString().orEmpty(), (project["platforms"] as List<String>? ?: emptyList()).joinToString(" · "))
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        val installs = project["downloads"]?.toString()?.let { downloads ->
            val forms = plurals.getValue(lang).getValue("installs_count")
            val form = forms[quantity(countOf(downloads), lang)] ?: forms.getValue("other")
            """<p class="pre-muted">${form.replace("%1\$s", esc(downloads))}</p>"""
        }.orEmpty()
        val projectLinks = (project["links"] as List<Map<String, Any?>>? ?: emptyList()).joinToString(" ") { anchor(it) }
        """<article class="pre-card"><h3>${esc(project["name"].toString())}</h3><p>${esc(tr(project["tagline"], lang))}</p>""" +
            """<p class="pre-muted">${esc(tr(project["description"], lang))}</p><p class="pre-muted">${esc(meta)}</p>$installs""" +
            """<p class="pre-chips">$projectLinks</p></article>"""
    }
    val support = links.firstOrNull { it["type"] == "support" }?.let { """<p class="pre-muted">${s.getValue("support_projects")} ${anchor(it)}</p>""" }.orEmpty()
    val articles = (profile["articles"] as List<Map<String, Any?>>).sortedByDescending { it["date"].toString() }.take(3).joinToString("") { article ->
        val views = article["views"]?.toString()?.let { count ->
            val forms = plurals.getValue(lang).getValue("views_count")
            val form = forms[quantity(countOf(count), lang)] ?: forms.getValue("other")
            " · ${form.replace("%1\$s", esc(count))}"
        }.orEmpty()
        """<li><a href="${esc(article["url"].toString())}">${esc(tr(article["title"], lang))}</a><br><span class="pre-muted">${esc(article["date"].toString())}$views</span></li>"""
    }
    val services = (profile["services"] as List<Map<String, Any?>>? ?: emptyList()).filter { it["active"] != false }.joinToString("") { service ->
        """<article class="pre-card"><h3>${esc(tr(service["title"], lang))}</h3><p class="pre-muted">${esc(tr(service["text"], lang))}</p></article>"""
    }
    val servicesSection = if (services.isEmpty()) "" else """<section><h2>${s.getValue("services_title")}</h2><div class="pre-grid">$services</div></section>"""
    val about = (listOf(person["bio"]) + (person["about"] as List<Any?>? ?: emptyList())).joinToString("") { "<p>${esc(tr(it, lang))}</p>" }
    val pdf = resume["pdf"]?.let { """ · <a href="${esc(it.toString())}">PDF</a>""" }.orEmpty()
    val resumeSection = """<section><h2>${s.getValue("resume_page")}</h2><p><a href="#resume">${s.getValue("experience_education_skills")}</a>$pdf</p></section>"""
    val footer = (contactLinks.map { contactAnchor(it) } + listOf(
        """<a href="download.html">${s.getValue("get_the_app")}</a>""",
        """<a href="https://github.com/vasmarfas/mobitool">${s.getValue("source_code")}</a>""",
    )).joinToString(" ")
    return """<div class="pre" lang="$lang">""" +
        """<header><h1 class="pre-name">${esc(tr(person["name"], lang))}</h1><p class="pre-title">${esc(tr(person["title"], lang))}</p>""" +
        """<p class="pre-muted">${esc(tr(person["location"], lang))}</p></header>""" +
        """<div class="pre-blocks"><section class="pre-block"><h2>${s.getValue("contact_me")}</h2><ul class="pre-rows">$contacts</ul></section>""" +
        """<section class="pre-block"><h2>${s.getValue("my_resources")}</h2><ul class="pre-rows pre-links">$resources</ul></section></div>""" +
        """<section><h2>${s.getValue("featured_projects")}</h2><div class="pre-grid">$projects</div>$support</section>""" +
        """<section><h2>${s.getValue("latest_articles")}</h2><ul class="pre-rows">$articles</ul></section>""" +
        servicesSection +
        """<section><h2>${s.getValue("contact_title")}</h2><p>${s.getValue("contact_body")}</p><ul class="pre-rows">$contacts</ul></section>""" +
        resumeSection +
        """<section><h2>${s.getValue("about_me")}</h2>$about</section>""" +
        """<p class="pre-chips">$footer</p></div>"""
}

extra["prerenderedProfile"] = stringFiles.keys.joinToString("\n") { "    " + page(it) }
