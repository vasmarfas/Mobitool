package com.vasmarfas.card.tools.text

import com.vasmarfas.card.core.Lang

class SpelledChar(val char: Char, val word: String?)

object PhoneticAlphabet {
    private val nato = listOf(
        "Alfa" to "Альфа", "Bravo" to "Браво", "Charlie" to "Чарли", "Delta" to "Дельта", "Echo" to "Эхо",
        "Foxtrot" to "Фокстрот", "Golf" to "Гольф", "Hotel" to "Хотел", "India" to "Индия", "Juliett" to "Джульетта",
        "Kilo" to "Кило", "Lima" to "Лима", "Mike" to "Майк", "November" to "Новембер", "Oscar" to "Оскар",
        "Papa" to "Папа", "Quebec" to "Квебек", "Romeo" to "Ромео", "Sierra" to "Сьерра", "Tango" to "Танго",
        "Uniform" to "Юниформ", "Victor" to "Виктор", "Whiskey" to "Виски", "X-ray" to "Экс-рей", "Yankee" to "Янки",
        "Zulu" to "Зулу",
    )

    private val digits = listOf(
        "Zero" to "ноль", "One" to "один", "Two" to "два", "Three" to "три", "Four" to "четыре",
        "Five" to "пять", "Six" to "шесть", "Seven" to "семь", "Eight" to "восемь", "Nine" to "девять",
    )

    private val russian = mapOf(
        'А' to "Анна", 'Б' to "Борис", 'В' to "Василий", 'Г' to "Григорий", 'Д' to "Дмитрий", 'Е' to "Елена",
        'Ё' to "Ёлка", 'Ж' to "Женя", 'З' to "Зинаида", 'И' to "Иван", 'Й' to "Иван краткий", 'К' to "Константин",
        'Л' to "Леонид", 'М' to "Михаил", 'Н' to "Николай", 'О' to "Ольга", 'П' to "Павел", 'Р' to "Роман",
        'С' to "Семён", 'Т' to "Татьяна", 'У' to "Ульяна", 'Ф' to "Фёдор", 'Х' to "Харитон", 'Ц' to "Цапля",
        'Ч' to "Человек", 'Ш' to "Шура", 'Щ' to "Щука", 'Ъ' to "Твёрдый знак", 'Ы' to "Еры", 'Ь' to "Мягкий знак",
        'Э' to "Эхо", 'Ю' to "Юрий", 'Я' to "Яков",
    )

    fun word(char: Char, lang: Lang): String? {
        val upper = char.uppercaseChar()
        return when (upper) {
            in 'A'..'Z' -> nato[upper - 'A'].let { if (lang == Lang.RU) it.second else it.first }
            in '0'..'9' -> digits[upper - '0'].let { if (lang == Lang.RU) it.second else it.first }
            else -> russian[upper]
        }
    }

    fun spell(text: String, lang: Lang): List<SpelledChar> = text.map { SpelledChar(it, word(it, lang)) }
}
