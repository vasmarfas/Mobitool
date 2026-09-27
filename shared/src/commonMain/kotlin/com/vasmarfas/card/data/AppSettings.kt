package com.vasmarfas.card.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale
import com.vasmarfas.card.core.Analytics
import com.vasmarfas.card.core.AnalyticsEvent
import com.vasmarfas.card.core.AnalyticsParam
import com.vasmarfas.card.core.KeyValueStore
import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.UserProperty
import com.vasmarfas.card.core.appLang
import com.vasmarfas.card.core.applyPlatformLocale
import com.vasmarfas.card.tools.ToolRegistry

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class OnboardingStatus { NONE, DONE, SKIPPED }

class AppSettings(private val store: KeyValueStore = Prefs.store) {
    var lang: Lang by mutableStateOf(
        Lang.fromCode(store.get(KEY_LANG)) ?: Lang.fromSystemTag(Locale.current.toLanguageTag())
    )
        private set

    var themeMode: ThemeMode by mutableStateOf(
        store.get(KEY_THEME)?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.SYSTEM
    )
        private set

    var dynamicColor: Boolean by mutableStateOf(store.get(KEY_DYNAMIC) != "false")
        private set

    var seedColor: Long by mutableStateOf(store.get(KEY_SEED)?.toLongOrNull() ?: DEFAULT_SEED)
        private set

    var wideTables: Boolean by mutableStateOf(store.get(KEY_WIDE_TABLES) == "true")
        private set

    // the home screen in the apps and the favorites on the site, in the order tools were added
    var myTools: List<String> by mutableStateOf(readTools(KEY_MY_TOOLS))
        private set

    var recent: List<String> by mutableStateOf(readTools(KEY_RECENT))
        private set

    var collapsedSections: Set<String> by mutableStateOf(readList(KEY_COLLAPSED).toSet())
        private set

    var onboarding: OnboardingStatus by mutableStateOf(
        store.get(KEY_ONBOARDING)?.let { v -> OnboardingStatus.entries.firstOrNull { it.name == v } } ?: OnboardingStatus.NONE
    )
        private set

    var roles: Set<String> by mutableStateOf(readList(KEY_ROLES).toSet())
        private set

    var interests: Set<String> by mutableStateOf(readList(KEY_INTERESTS).toSet())
        private set

    init {
        if (store.get(KEY_LANG) != null) applyPlatformLocale(lang.code)
        appLang = lang
    }

    fun updateLang(value: Lang) {
        applyPlatformLocale(value.code)
        appLang = value
        lang = value
        store.put(KEY_LANG, value.code)
        changed("lang", value.code)
        Analytics.set(UserProperty.APP_LANG, value.code)
    }

    fun updateThemeMode(value: ThemeMode) {
        themeMode = value
        store.put(KEY_THEME, value.name)
        changed("theme", value.name.lowercase())
        Analytics.set(UserProperty.THEME, value.name.lowercase())
    }

    fun updateDynamicColor(value: Boolean) {
        dynamicColor = value
        store.put(KEY_DYNAMIC, value.toString())
        changed("dynamic_color", onOff(value))
    }

    fun updateSeedColor(value: Long) {
        seedColor = value
        store.put(KEY_SEED, value.toString())
        changed("accent", value.toString(16).takeLast(6))
    }

    fun updateWideTables(value: Boolean) {
        wideTables = value
        store.put(KEY_WIDE_TABLES, value.toString())
        changed("wide_tables", onOff(value))
    }

    fun pin(id: String) {
        if (id !in myTools) saveMyTools(myTools + id)
    }

    fun unpin(id: String) {
        if (id in myTools) saveMyTools(myTools - id)
    }

    fun toggleSection(id: String) {
        collapsedSections = if (id in collapsedSections) collapsedSections - id else collapsedSections + id
        store.put(KEY_COLLAPSED, collapsedSections.joinToString(","))
    }

    fun finishOnboarding(status: OnboardingStatus, roles: Set<String>, interests: Set<String>, tools: List<String>) {
        onboarding = status
        this.roles = roles
        this.interests = interests
        store.put(KEY_ONBOARDING, status.name)
        store.put(KEY_ROLES, roles.joinToString(","))
        store.put(KEY_INTERESTS, interests.joinToString(","))
        saveMyTools(tools)
        syncAnalytics()
    }

    fun markRecent(id: String) {
        recent = (listOf(id) + recent.filter { it != id }).take(8)
        store.put(KEY_RECENT, recent.joinToString(","))
    }

    // only once the analytics SDK is up, user properties sent before that are lost
    fun syncAnalytics() {
        Analytics.set(UserProperty.APP_LANG, lang.code)
        Analytics.set(UserProperty.THEME, themeMode.name.lowercase())
        Analytics.set(UserProperty.ONBOARDING, onboarding.name.lowercase())
        Analytics.set(UserProperty.ROLE, Onboarding.roleKey(roles))
        Analytics.set(UserProperty.INTERESTS, Analytics.bucket(interests.size))
        Analytics.set(UserProperty.HOME_TOOLS, Analytics.bucket(myTools.size))
    }

    private fun saveMyTools(value: List<String>) {
        myTools = value.distinct()
        store.put(KEY_MY_TOOLS, myTools.joinToString(","))
        Analytics.set(UserProperty.HOME_TOOLS, Analytics.bucket(myTools.size))
    }

    private fun changed(setting: String, value: String) {
        Analytics.log(AnalyticsEvent.SETTINGS_CHANGE, mapOf(AnalyticsParam.SETTING to setting, AnalyticsParam.VALUE to value))
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private fun readList(key: String): List<String> =
        store.get(key)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    private fun readTools(key: String): List<String> {
        val stored = readList(key)
        val ids = stored.map { ToolRegistry.byId(it)?.id ?: it }.distinct()
        if (ids != stored) store.put(key, ids.joinToString(","))
        return ids
    }

    companion object {
        const val DEFAULT_SEED = 0xFF326773
        private const val KEY_LANG = "settings.lang"
        private const val KEY_THEME = "settings.theme"
        private const val KEY_DYNAMIC = "settings.dynamic"
        private const val KEY_SEED = "settings.seed"
        private const val KEY_WIDE_TABLES = "settings.wideTables"
        private const val KEY_MY_TOOLS = "tools.favorites"
        private const val KEY_RECENT = "tools.recent"
        private const val KEY_COLLAPSED = "home.collapsed"
        private const val KEY_ONBOARDING = "onboarding.status"
        private const val KEY_ROLES = "onboarding.role"
        private const val KEY_INTERESTS = "onboarding.interests"
    }
}

val LocalSettings = staticCompositionLocalOf<AppSettings> { error("AppSettings not provided") }
