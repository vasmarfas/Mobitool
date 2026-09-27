package com.vasmarfas.card.tools.security

import com.vasmarfas.card.resources.*
import kotlin.math.ln
import kotlin.math.max
import org.jetbrains.compose.resources.StringResource

enum class StrengthPattern { REPEAT, SEQUENCE, KEYBOARD_WALK, DATE, DICTIONARY_WORD, FEW_DISTINCT, WORD_AND_DIGITS }

data class StrengthIssue(val pattern: StrengthPattern, val penaltyBits: Double, val count: Int = 0, val word: String = "")

data class StrengthResult(
    val length: Int,
    val alphabetSize: Int,
    val rawBits: Double,
    val bits: Double,
    val issues: List<StrengthIssue>,
    val suggestions: List<StringResource>,
) {
    val score: Int
        get() = when {
            bits < 28 -> 0
            bits < 36 -> 1
            bits < 60 -> 2
            bits < 80 -> 3
            else -> 4
        }
}

object PasswordStrength {
    private val keyboardRows = listOf(
        "qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890",
        "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю",
    )

    private val common = setOf(
        "password", "passw0rd", "qwerty", "123456", "12345678", "123456789", "1234567890", "letmein", "admin",
        "welcome", "monkey", "dragon", "football", "baseball", "iloveyou", "sunshine", "princess", "master",
        "shadow", "superman", "batman", "trustno1", "abc123", "111111", "000000", "zaq12wsx", "qazwsx",
        "пароль", "привет", "любовь", "россия", "спартак", "зенит", "динамо", "москва", "йцукен", "какдела",
        "solnce", "natasha", "sergey", "andrey", "marina", "alexander", "dmitry", "hello", "test", "secret",
    )

    private val leetMap = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i')

    fun normalize(password: String): String = password.lowercase().map { leetMap[it] ?: it }.joinToString("")

    fun alphabetSize(password: String): Int {
        var size = 0
        if (password.any { it in 'a'..'z' }) size += 26
        if (password.any { it in 'A'..'Z' }) size += 26
        if (password.any { it.isDigit() }) size += 10
        if (password.any { it in PasswordGen.SYMBOLS }) size += PasswordGen.SYMBOLS.length
        if (password.any { it.code in 0x400..0x4FF }) size += 33
        if (password.any { it.code > 0x4FF || (it.code > 0x7E && it.code < 0x400) }) size += 20
        if (password.any { it == ' ' }) size += 1
        return max(size, 1)
    }

    fun longestRepeat(password: String): Int {
        var best = 1
        var current = 1
        for (i in 1 until password.length) {
            current = if (password[i] == password[i - 1]) current + 1 else 1
            if (current > best) best = current
        }
        return if (password.isEmpty()) 0 else best
    }

    fun longestSequence(password: String): Int {
        if (password.length < 2) return password.length
        var best = 1
        var current = 1
        var direction = 0
        for (i in 1 until password.length) {
            val delta = password[i].code - password[i - 1].code
            if ((delta == 1 || delta == -1) && (direction == 0 || direction == delta)) {
                current++
                direction = delta
            } else {
                current = 1
                direction = 0
            }
            if (current > best) best = current
        }
        return best
    }

    fun keyboardWalk(password: String): Int {
        val lower = password.lowercase()
        var best = 1
        for (row in keyboardRows) {
            for (start in lower.indices) {
                for (end in start + 2..lower.length) {
                    val part = lower.substring(start, end)
                    if (row.contains(part) || row.contains(part.reversed())) {
                        if (part.length > best) best = part.length
                    }
                }
            }
        }
        return best
    }

    fun hasDate(password: String): Boolean {
        val years = Regex("(19\\d{2}|20[0-2]\\d)")
        if (years.containsMatchIn(password)) return true
        return Regex("\\b\\d{2}[.\\-/]\\d{2}[.\\-/]\\d{2,4}\\b").containsMatchIn(password)
    }

    fun dictionaryHit(password: String): String? {
        val normalized = normalize(password)
        common.firstOrNull { normalized == it }?.let { return it }
        common.firstOrNull { it.length >= 5 && normalized.contains(it) }?.let { return it }
        val stripped = normalized.trimEnd('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '!', '?', '.')
        if (stripped.length >= 4 && stripped in common) return stripped
        val word = (WordLists.english + WordLists.russian).firstOrNull { it.length >= 5 && it == stripped }
        return word
    }

    fun analyze(password: String): StrengthResult {
        if (password.isEmpty()) {
            return StrengthResult(0, 0, 0.0, 0.0, emptyList(), listOf(Res.string.strength_enter_a_password))
        }
        val alphabet = alphabetSize(password)
        val rawBits = password.length * ln(alphabet.toDouble()) / ln(2.0)
        val issues = mutableListOf<StrengthIssue>()
        val suggestions = mutableListOf<StringResource>()
        if (password.length < 12) {
            suggestions += Res.string.strength_use_12_characters
        }
        val repeat = longestRepeat(password)
        if (repeat >= 3) {
            issues += StrengthIssue(StrengthPattern.REPEAT, repeat * 2.0, count = repeat)
            suggestions += Res.string.strength_avoid_repeats
        }
        val sequence = longestSequence(password)
        if (sequence >= 4) {
            issues += StrengthIssue(StrengthPattern.SEQUENCE, sequence * 2.5, count = sequence)
            suggestions += Res.string.strength_avoid_sequences
        }
        val walk = keyboardWalk(password)
        if (walk >= 4) {
            issues += StrengthIssue(StrengthPattern.KEYBOARD_WALK, walk * 2.5, count = walk)
            suggestions += Res.string.strength_avoid_keyboard_walks
        }
        if (hasDate(password)) {
            issues += StrengthIssue(StrengthPattern.DATE, 8.0)
            suggestions += Res.string.strength_avoid_dates
        }
        val hit = dictionaryHit(password)
        if (hit != null) {
            issues += StrengthIssue(StrengthPattern.DICTIONARY_WORD, 14.0, word = hit)
            suggestions += Res.string.strength_avoid_single_word
        }
        if (password.length >= 4 && password.toSet().size <= 2) {
            issues += StrengthIssue(StrengthPattern.FEW_DISTINCT, 10.0, count = password.toSet().size)
        }
        if (alphabet <= 26) {
            suggestions += Res.string.strength_mix_character_sets
        }
        if (Regex("^[A-ZА-ЯЁ][a-zа-яё]+\\d{1,4}[!?.]?$").matches(password)) {
            issues += StrengthIssue(StrengthPattern.WORD_AND_DIGITS, 10.0)
        }
        val penalty = issues.sumOf { it.penaltyBits }
        val bits = max(rawBits - penalty, if (password.isEmpty()) 0.0 else 1.0)
        if (issues.isEmpty() && password.length >= 16) {
            suggestions += Res.string.strength_good_password
        }
        if (suggestions.isEmpty()) {
            suggestions += Res.string.strength_add_length
        }
        return StrengthResult(password.length, alphabet, rawBits, bits, issues, suggestions)
    }

    fun scoreLabel(score: Int): StringResource = when (score) {
        0 -> Res.string.strength_very_weak
        1 -> Res.string.strength_weak
        2 -> Res.string.strength_medium
        3 -> Res.string.strength_strong
        else -> Res.string.strength_very_strong
    }
}
