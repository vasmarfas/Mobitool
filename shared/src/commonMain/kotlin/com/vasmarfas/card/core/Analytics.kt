package com.vasmarfas.card.core

object AnalyticsConfig {
    const val FIREBASE_ENABLED = true
}

// privacy-policy.md lists every event for users, a new event or parameter goes there too
enum class AnalyticsEvent(val eventName: String) {
    APP_OPEN("app_open"),
    SCREEN_VIEW("screen_view"),
    TOOL_OPEN("tool_open"),
    TOOL_ACTION("tool_action"),
    TOOL_PIN("tool_pin"),
    TOOL_UNPIN("tool_unpin"),
    RESULT_COPY("result_copy"),
    TOOL_ERROR("tool_error"),
    SEARCH("search"),
    SEARCH_EMPTY("search_empty"),
    SCROLL_DEPTH("scroll_depth"),
    CATALOG_FILTER("catalog_filter"),
    HOME_SECTION_TOGGLE("home_section_toggle"),
    HOME_REMOVE("home_remove"),
    HOME_REMOVE_CANCEL("home_remove_cancel"),
    HOME_ADD_TOOLS("home_add_tools"),
    ONBOARDING_START("onboarding_start"),
    ONBOARDING_STEP("onboarding_step"),
    ONBOARDING_BACK("onboarding_back"),
    ONBOARDING_SKIP("onboarding_skip"),
    ONBOARDING_ROLE("onboarding_role"),
    ONBOARDING_INTEREST("onboarding_interest"),
    ONBOARDING_TOOL_TOGGLE("onboarding_tool_toggle"),
    ONBOARDING_COMPLETE("onboarding_complete"),
    SETTINGS_CHANGE("settings_change"),
    LINK_OPEN("link_open"),
    SUPPORT_OPEN("support_open"),
}

object AnalyticsParam {
    const val SCREEN_NAME = "screen_name"
    const val SCREEN_CLASS = "screen_class"
    const val SCREEN = "screen"
    const val DEPTH = "depth"
    const val TOOL = "tool"
    const val CATEGORY = "category"
    const val SOURCE = "source"
    const val STEP = "step"
    const val ROLE = "role"
    const val INTEREST = "interest"
    const val STATE = "state"
    const val SETTING = "setting"
    const val VALUE = "value"
    const val LINK = "link"
    const val ENTRY = "entry"
    const val SEARCH_TERM = "search_term"
}

// Firebase sums and averages numeric parameters, a string one it can only count
object AnalyticsMetric {
    const val RESULTS = "results"
    const val STEP_INDEX = "step_index"
    const val SECONDS = "seconds"
    const val PROPOSED = "proposed"
    const val KEPT = "kept"
    const val ADDED = "added"
    const val ROLES = "roles"
    const val INTERESTS = "interests"
}

// user properties split every Firebase report: by role, by how full the home screen is, by language
enum class UserProperty(val key: String) {
    ROLE("role"),
    ONBOARDING("onboarding"),
    HOME_TOOLS("home_tools"),
    INTERESTS("interests"),
    APP_LANG("app_lang"),
    THEME("theme"),
}

expect fun initAnalytics()

expect fun logEvent(name: String, params: Map<String, String>, metrics: Map<String, Long>)

expect fun setUserProperty(name: String, value: String?)

// a Metrica page view on the site, the apps have nothing to send it to
expect fun trackPage(path: String, title: String)

object Analytics {
    private const val MAX_VALUE_LENGTH = 40
    private const val MAX_PROPERTY_LENGTH = 36

    fun log(event: AnalyticsEvent, params: Map<String, String> = emptyMap(), metrics: Map<String, Long> = emptyMap()) {
        logEvent(event.eventName, safeParams(params), metrics.filterKeys(::isIdentifier))
    }

    // events that name no screen of their own are about this one
    var currentScreen: String = ""
        private set

    fun screen(name: String, screenClass: String, path: String, title: String) {
        currentScreen = name
        log(AnalyticsEvent.SCREEN_VIEW, mapOf(AnalyticsParam.SCREEN_NAME to name, AnalyticsParam.SCREEN_CLASS to screenClass))
        trackPage(path, title)
    }

    fun scrollDepth(depth: Int) {
        log(AnalyticsEvent.SCROLL_DEPTH, mapOf(AnalyticsParam.SCREEN to currentScreen, AnalyticsParam.DEPTH to depth.toString()))
    }

    // every quarter passed since reported, so a jump to the bottom still counts 25, 50 and 75
    fun depthsReached(percent: Int, reported: Int): List<Int> = listOf(25, 50, 75, 100).filter { it in (reported + 1)..percent }

    fun search(query: String, results: Int) {
        val term = searchTerm(query)?.let { mapOf(AnalyticsParam.SEARCH_TERM to it) }.orEmpty()
        logEvent(
            (if (results == 0) AnalyticsEvent.SEARCH_EMPTY else AnalyticsEvent.SEARCH).eventName,
            term,
            mapOf(AnalyticsMetric.RESULTS to results.toLong()),
        )
    }

    fun set(property: UserProperty, value: String?) {
        setUserProperty(property.key, value?.takeIf { isIdentifier(it, MAX_PROPERTY_LENGTH) })
    }

    // coarse buckets, Firebase compares user properties as text
    fun bucket(count: Int): String = when {
        count <= 0 -> "0"
        count <= 5 -> "1-5"
        count <= 10 -> "6-10"
        count <= 20 -> "11-20"
        count <= 40 -> "21-40"
        else -> "41-plus"
    }

    internal fun safeParams(params: Map<String, String>): Map<String, String> =
        params.filter { (key, value) -> isIdentifier(key) && isIdentifier(value) }

    // sent only when it can be nothing but a tool name, addresses, phone numbers and IPs fail the check
    internal fun searchTerm(query: String): String? {
        val term = query.trim().lowercase().replace(Regex("\\s+"), " ")
        return term.takeIf { t ->
            t.length in 2..30 && t.count { it.isDigit() } <= 4 && t.all { it.isLetterOrDigit() || it == ' ' || it == '-' }
        }
    }

    private fun isIdentifier(value: String, maxLength: Int = MAX_VALUE_LENGTH): Boolean =
        value.length in 1..maxLength &&
            value.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
}
