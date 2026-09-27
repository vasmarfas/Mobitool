package com.vasmarfas.card.core

object AppConfig {
    const val APP_NAME = "Mobitool"
    const val SITE_NAME = "vasmarfas"
    const val SITE_COM = "https://vasmarfas.com"
    const val SITE_RU = "https://vasmarfas.ru"
    const val REPO_URL = "https://github.com/vasmarfas/mobitool"
    const val GITHUB_API = "https://api.github.com"
    private const val RAW_BASE = "https://raw.githubusercontent.com/vasmarfas/mobitool/master/shared/src/commonMain/composeResources/files"
    const val PROFILE_BUNDLED_PATH = "files/profile.json"
    const val RESUME_BUNDLED_PATH = "files/resume.json"
    const val AVATAR_BUNDLED_PATH = "files/images/avatar.png"

    fun contentBase(): String =
        if (siteHost()?.endsWith("vasmarfas.ru") == true) "$SITE_RU/content" else RAW_BASE

    fun contentUrl(name: String): String = "${contentBase()}/$name"

    // the site the visitor is on, in the apps the one of the interface language. Examples in tools point here
    fun site(): String {
        val host = siteHost()
        return when {
            host?.endsWith("vasmarfas.ru") == true -> SITE_RU
            host != null -> SITE_COM
            appLang == Lang.RU -> SITE_RU
            else -> SITE_COM
        }
    }
}
