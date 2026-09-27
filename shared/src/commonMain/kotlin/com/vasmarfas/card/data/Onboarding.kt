package com.vasmarfas.card.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.ToolRegistry
import org.jetbrains.compose.resources.StringResource

enum class InterestGroup(val title: StringResource) {
    EVERYDAY(Res.string.interests_everyday),
    HOBBY(Res.string.interests_hobby),
    WORK(Res.string.interests_work),
}

// tools in the order they are offered. Every catalog tool has to be in some interest, OnboardingTest
// checks it
enum class Interest(val id: String, val group: InterestGroup, val title: StringResource, val icon: ImageVector, val tools: List<String>) {
    MONEY(
        "money", InterestGroup.EVERYDAY, Res.string.interest_money, Icons.Filled.Payments,
        listOf(
            "currency-converter", "percentage", "discount-vat", "unit-price", "tip-split", "shopping-split", "loan-calculator",
            "compound-interest", "number-to-words", "crypto-converter", "electricity-cost",
        ),
    ),
    HOME(
        "home", InterestGroup.EVERYDAY, Res.string.interest_home, Icons.Filled.Kitchen,
        listOf("cooking-converter", "countdown-timer", "unit-converter", "notes-scratchpad", "electricity-cost", "water-intake", "tally-counter", "age-calculator"),
    ),
    REPAIR(
        "repair", InterestGroup.EVERYDAY, Res.string.interest_repair, Icons.Filled.Straighten,
        listOf("ruler", "bubble-level", "renovation", "protractor", "magnetometer", "compass", "screen-light", "light-meter", "unit-converter"),
    ),
    DOCUMENTS(
        "documents", InterestGroup.EVERYDAY, Res.string.interest_documents, Icons.Filled.Description,
        listOf(
            "pdf-editor", "images-to-pdf", "merge-pdf", "compress-pdf", "id-photo", "document-converter", "pdf-pages", "pdf-to-images",
            "pdf-to-text", "unlock-pdf", "zip-archive", "qr-generator", "number-check", "number-to-words", "paper-sizes", "csv-converter",
        ),
    ),
    PHOTO(
        "photo", InterestGroup.EVERYDAY, Res.string.interest_photo, Icons.Filled.PhotoCamera,
        listOf(
            "image-compressor", "photo-editor", "id-photo", "image-converter", "exif-viewer", "image-palette", "sunrise-sunset",
            "light-meter", "aspect-ratio",
        ),
    ),
    TIME(
        "time", InterestGroup.EVERYDAY, Res.string.interest_time, Icons.Filled.Schedule,
        listOf(
            "date-calculator", "countdown-timer", "working-hours", "reminder-countdown", "world-clock", "age-calculator", "calendar",
            "stopwatch", "sunrise-sunset",
        ),
    ),
    TRAVEL(
        "travel", InterestGroup.EVERYDAY, Res.string.interest_travel, Icons.Filled.Flight,
        listOf(
            "currency-converter", "world-clock", "unit-converter", "clothing-sizes", "tip-split", "gps-location", "compass",
            "sunrise-sunset", "barometer", "phonetic-alphabet",
        ),
    ),
    AUTO(
        "auto", InterestGroup.EVERYDAY, Res.string.interest_auto, Icons.Filled.DirectionsCar,
        listOf("fuel-cost", "tire-size", "gps-location", "blood-alcohol", "loan-calculator"),
    ),
    STUDY(
        "study", InterestGroup.EVERYDAY, Res.string.interest_study, Icons.Filled.School,
        listOf(
            "calculator", "fraction-calculator", "percentage", "unit-converter", "number-base", "text-counter", "roman-numerals",
            "number-to-words", "ohms-law",
        ),
    ),
    SPORT(
        "sport", InterestGroup.EVERYDAY, Res.string.interest_sport, Icons.Filled.FitnessCenter,
        listOf(
            "workout-timer", "workout-builder", "pace-calculator", "heart-rate-zones", "one-rep-max", "calorie-burn",
            "water-and-macros", "vo2-max", "pedometer", "stopwatch", "bmi-body", "reaction-time",
        ),
    ),
    HEALTH(
        "health", InterestGroup.EVERYDAY, Res.string.interest_health, Icons.Filled.MonitorHeart,
        listOf(
            "bmi-body", "water-intake", "sleep-calculator", "blood-pressure", "breathing", "caffeine-decay", "body-metrics",
            "hba1c-glucose", "water-and-macros", "blood-alcohol",
        ),
    ),
    PHONE(
        "phone", InterestGroup.EVERYDAY, Res.string.interest_phone, Icons.Filled.PhoneAndroid,
        listOf(
            "device-info", "battery", "screen-test", "touch-tester", "display-info", "benchmarks", "stress-test", "vibration-test",
            "keyboard-tester", "speed-test", "clipboard-inspector",
        ),
    ),
    GAMES(
        "games", InterestGroup.EVERYDAY, Res.string.interest_games, Icons.Filled.Casino,
        listOf("dice-roller", "decision-wheel", "team-splitter", "random-generator", "tally-counter", "chess-clock", "reaction-time", "ciphers", "morse-code"),
    ),
    VIDEO(
        "video", InterestGroup.HOBBY, Res.string.interest_video, Icons.Filled.Movie,
        listOf("video-editor", "video-converter", "video-frame", "gif-maker", "audio-converter", "audio-editor", "aspect-ratio"),
    ),
    MUSIC(
        "music", InterestGroup.HOBBY, Res.string.interest_music, Icons.Filled.MusicNote,
        listOf("tuner", "metronome", "sound-meter", "tone-generator", "noise-generator", "spectrum-analyzer", "audio-editor", "audio-converter"),
    ),
    DESIGN(
        "design", InterestGroup.HOBBY, Res.string.interest_design, Icons.Filled.Palette,
        listOf(
            "color-converter", "contrast-checker", "image-palette", "gradient-generator", "aspect-ratio",
            "typography-scale", "material-scheme", "css-units", "paper-sizes", "barcode-generator", "qr-generator", "lorem-ipsum",
        ),
    ),
    SENSORS(
        "sensors", InterestGroup.HOBBY, Res.string.interest_sensors, Icons.Filled.Sensors,
        listOf("compass", "bubble-level", "light-meter", "magnetometer", "accelerometer", "barometer", "sound-meter", "gps-location", "pedometer"),
    ),
    ELECTRONICS(
        "electronics", InterestGroup.HOBBY, Res.string.interest_electronics, Icons.Filled.ElectricBolt,
        listOf("ohms-law", "resistor-color-code", "led-resistor", "voltage-divider", "battery-calculator", "wire-gauge", "programmer-calculator"),
    ),
    PRINTING(
        "printing", InterestGroup.HOBBY, Res.string.interest_printing, Icons.Filled.Print,
        listOf(
            "print-cost", "filament-guide", "filament-length-weight", "flow-and-esteps", "layer-settings", "temperature-tower", "print-time-estimate",
            "gcode-cheatsheet", "shrinkage-scale", "resin-cost",
        ),
    ),
    TEXTS(
        "texts", InterestGroup.HOBBY, Res.string.interest_texts, Icons.Filled.TextFields,
        listOf(
            "text-counter", "keyboard-layout", "case-converter", "find-replace", "transliteration", "text-cleaner", "text-diff",
            "line-tools", "mojibake-fixer", "markdown-preview", "phonetic-alphabet", "morse-code", "ciphers", "lorem-ipsum",
            "unicode-inspector",
        ),
    ),
    NETWORK(
        "network", InterestGroup.WORK, Res.string.interest_network, Icons.Filled.Wifi,
        listOf(
            "ping", "speed-test", "ip-info", "dns-lookup", "traceroute", "port-scanner", "lan-scanner", "subnet-calculator",
            "whois-rdap", "wake-on-lan", "network-interfaces", "mac-lookup", "public-dns", "subnet-splitter", "device-discovery",
            "data-size",
        ),
    ),
    SERVERS(
        "servers", InterestGroup.WORK, Res.string.interest_servers, Icons.Filled.Dns,
        listOf(
            "chmod-calculator", "cron-parser", "tls-certificate", "ports-reference", "http-request", "dns-lookup", "whois-rdap",
            "unix-timestamp", "hash-generator", "checksum-compare", "data-size",
        ),
    ),
    CODE(
        "code", InterestGroup.WORK, Res.string.interest_code, Icons.Filled.Code,
        listOf(
            "json-formatter", "base64", "regex-tester", "jwt-decoder", "hash-generator", "uuid-generator", "url-tools",
            "unix-timestamp", "text-diff", "http-status-codes", "string-escape", "csv-converter",
            "programmer-calculator", "float-inspector", "semver", "mime-types", "ascii-table", "unicode-inspector",
            "markdown-preview", "http-request", "css-units", "material-scheme", "clipboard-inspector", "lorem-ipsum",
        ),
    ),
    SECURITY(
        "security", InterestGroup.WORK, Res.string.interest_security, Icons.Filled.Lock,
        listOf("password-generator", "password-strength", "pwned-check", "totp", "checksum-compare", "random-generator", "hash-generator", "tls-certificate"),
    ),
}

enum class Role(val id: String, val title: StringResource, val hint: StringResource, val icon: ImageVector, val interests: Set<Interest>) {
    HOME("home", Res.string.role_home, Res.string.role_home_hint, Icons.Filled.Home, setOf(Interest.MONEY, Interest.HOME, Interest.REPAIR, Interest.DOCUMENTS, Interest.PHOTO)),
    STUDY("study", Res.string.role_study, Res.string.role_study_hint, Icons.Filled.School, setOf(Interest.STUDY, Interest.DOCUMENTS, Interest.TIME, Interest.TEXTS)),
    OFFICE("office", Res.string.role_office, Res.string.role_office_hint, Icons.Filled.Work, setOf(Interest.DOCUMENTS, Interest.PHOTO, Interest.MONEY, Interest.TIME)),
    DEVELOPER("developer", Res.string.role_developer, Res.string.role_developer_hint, Icons.Filled.Code, setOf(Interest.CODE, Interest.TEXTS, Interest.SECURITY, Interest.DESIGN)),
    ADMIN("admin", Res.string.role_admin, Res.string.role_admin_hint, Icons.Filled.Lan, setOf(Interest.NETWORK, Interest.SERVERS, Interest.SECURITY, Interest.PHONE)),
    MAKER("maker", Res.string.role_maker, Res.string.role_maker_hint, Icons.Filled.Handyman, setOf(Interest.REPAIR, Interest.ELECTRONICS, Interest.PRINTING, Interest.SENSORS)),
    CREATOR("creator", Res.string.role_creator, Res.string.role_creator_hint, Icons.Filled.Brush, setOf(Interest.PHOTO, Interest.VIDEO, Interest.DESIGN, Interest.MUSIC)),
    SPORT("sport", Res.string.role_sport, Res.string.role_sport_hint, Icons.AutoMirrored.Filled.DirectionsRun, setOf(Interest.SPORT, Interest.HEALTH)),
    TRAVEL("travel", Res.string.role_travel, Res.string.role_travel_hint, Icons.Filled.Luggage, setOf(Interest.TRAVEL, Interest.AUTO)),
    OTHER("other", Res.string.role_other, Res.string.role_other_hint, Icons.Filled.Explore, setOf(Interest.MONEY, Interest.DOCUMENTS, Interest.PHOTO, Interest.TIME)),
    ;

    companion object {
        fun byIds(ids: Collection<String>): List<Role> = entries.filter { it.id in ids }
    }
}

object Onboarding {
    // one phone screen, pre-ticked tools mostly stay
    const val BUDGET = 15

    const val MIN_TOOLS = 8

    private const val POPULAR_BONUS = 0.001

    // what a skipped onboarding leaves on the home screen, all of it runs everywhere
    val STARTER = listOf(
        "pdf-editor", "images-to-pdf", "image-compressor", "currency-converter",
        "percentage", "unit-converter", "qr-generator", "speed-test",
    )

    private val runsHere: (String) -> Boolean = { id -> ToolRegistry.byId(id)?.availableHere == true }
    private val popular by lazy { ToolRegistry.popular.map { it.id }.toSet() }

    // fewer leading tools per interest the more interests there are, so the pick stays within BUDGET
    fun propose(roles: Collection<Role>, interests: Collection<Interest>, available: (String) -> Boolean = runsHere): List<String> {
        if (interests.isEmpty()) return emptyList()
        val lead = when {
            interests.size <= 3 -> 3
            interests.size <= 7 -> 2
            else -> 1
        }
        val picked = LinkedHashSet<String>()
        interests.sortedByDescending { weight(it, roles) }.forEach { interest ->
            picked += interest.tools.filter { it !in picked && available(it) }.take(lead)
        }
        picked += ranked(roles, interests, available).filter { it !in picked }.take((BUDGET - picked.size).coerceAtLeast(0))
        if (picked.size < MIN_TOOLS) picked += STARTER.filter { it !in picked && available(it) }.take(MIN_TOOLS - picked.size)
        return picked.toList()
    }

    fun extras(roles: Collection<Role>, interests: Collection<Interest>, proposed: Collection<String>, available: (String) -> Boolean = runsHere): List<String> =
        ranked(roles, interests, available).filter { it !in proposed }

    // weight of each chosen interest the tool is in, divided by its place there: 1, 1/2, 1/3... Shared
    // tools add up, popular ones get a head start that only settles ties
    internal fun scores(roles: Collection<Role>, interests: Collection<Interest>, available: (String) -> Boolean = runsHere): Map<String, Double> {
        val scores = LinkedHashMap<String, Double>()
        interests.forEach { interest ->
            val weight = weight(interest, roles)
            interest.tools.filter(available).forEachIndexed { index, id ->
                scores[id] = (scores[id] ?: if (id in popular) POPULAR_BONUS else 0.0) + weight / (index + 1)
            }
        }
        return scores
    }

    // 1 for an interest one chosen role pre-ticks, +0.5 per further role sharing it, 1.5 when ticked by
    // hand beyond the role defaults
    internal fun weight(interest: Interest, roles: Collection<Role>): Double {
        val sharedBy = roles.count { interest in it.interests }
        return if (sharedBy == 0) 1.5 else 1.0 + 0.5 * (sharedBy - 1)
    }

    // keeps Firebase reports readable: one id, two ids in enum order, or mixed
    fun roleKey(ids: Collection<String>): String? {
        val roles = Role.byIds(ids)
        return when (roles.size) {
            0 -> null
            1, 2 -> roles.joinToString("-") { it.id }
            else -> "mixed"
        }
    }

    private fun ranked(roles: Collection<Role>, interests: Collection<Interest>, available: (String) -> Boolean): List<String> =
        scores(roles, interests, available).entries.sortedByDescending { it.value }.map { it.key }
}
