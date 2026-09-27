package com.vasmarfas.card.tools.security

data class DiceRoll(val notation: String, val sides: Int, val rolls: List<Int>, val modifier: Int) {
    val total: Int get() = rolls.sum() + modifier
}

object Dice {
    const val MAX_UNIQUE = 10_000

    private val notation = Regex("^(\\d*)[dD](\\d+)([+-]\\d+)?$")

    fun roll(text: String): DiceRoll? {
        val m = notation.find(text.trim().replace(" ", "")) ?: return null
        val count = if (m.groupValues[1].isEmpty()) 1 else m.groupValues[1].toIntOrNull() ?: return null
        val sides = m.groupValues[2].toIntOrNull() ?: return null
        if (count !in 1..100 || sides !in 2..1000) return null
        val modifier = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        return DiceRoll(text.trim(), sides, List(count) { PasswordGen.randomInt(sides) + 1 }, modifier)
    }

    fun intInRange(from: Int, to: Int): Int = (from + PasswordGen.randomLong(to.toLong() - from + 1)).toInt()

    fun uniqueInts(from: Int, to: Int, count: Int): List<Int>? {
        val size = to.toLong() - from + 1
        if (count > size) return null
        if (count * 2L <= size) {
            val picked = LinkedHashSet<Int>()
            while (picked.size < count) picked += intInRange(from, to)
            return picked.toList()
        }
        val pool = (from..to).toMutableList()
        for (i in 0 until count) {
            val j = i + PasswordGen.randomInt(pool.size - i)
            val tmp = pool[i]
            pool[i] = pool[j]
            pool[j] = tmp
        }
        return pool.subList(0, count).toList()
    }

    fun <T> pick(items: List<T>): T? = if (items.isEmpty()) null else items[PasswordGen.randomInt(items.size)]

    fun <T> shuffled(items: List<T>): List<T> {
        val copy = items.toMutableList()
        PasswordGen.shuffle(copy)
        return copy
    }
}
