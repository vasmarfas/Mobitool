package com.vasmarfas.card.tools.developer

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String?,
    val build: String?,
) : Comparable<SemVer> {
    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (preRelease != null) append('-').append(preRelease)
        if (build != null) append('+').append(build)
    }

    val isStable: Boolean get() = preRelease == null && major > 0

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major - other.major
        if (minor != other.minor) return minor - other.minor
        if (patch != other.patch) return patch - other.patch
        return SemVerOps.comparePreRelease(preRelease, other.preRelease)
    }
}

object SemVerOps {
    private val full = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$")

    fun parse(text: String): SemVer? {
        val m = full.find(text.trim()) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        val minor = if (m.groupValues[2].isEmpty()) 0 else m.groupValues[2].toIntOrNull() ?: return null
        val patch = if (m.groupValues[3].isEmpty()) 0 else m.groupValues[3].toIntOrNull() ?: return null
        return SemVer(
            major = major,
            minor = minor,
            patch = patch,
            preRelease = m.groupValues[4].takeIf { it.isNotEmpty() },
            build = m.groupValues[5].takeIf { it.isNotEmpty() },
        )
    }

    fun comparePreRelease(a: String?, b: String?): Int {
        if (a == null && b == null) return 0
        if (a == null) return 1
        if (b == null) return -1
        val x = a.split('.')
        val y = b.split('.')
        for (i in 0 until maxOf(x.size, y.size)) {
            val xi = x.getOrNull(i) ?: return -1
            val yi = y.getOrNull(i) ?: return 1
            val xn = xi.toIntOrNull()
            val yn = yi.toIntOrNull()
            val cmp = when {
                xn != null && yn != null -> xn - yn
                xn != null -> -1
                yn != null -> 1
                else -> xi.compareTo(yi)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun bumpMajor(v: SemVer) = SemVer(v.major + 1, 0, 0, null, null)

    fun bumpMinor(v: SemVer) = SemVer(v.major, v.minor + 1, 0, null, null)

    fun bumpPatch(v: SemVer) = SemVer(v.major, v.minor, v.patch + 1, null, null)

    fun bumpPreRelease(v: SemVer): SemVer {
        val pre = v.preRelease ?: return SemVer(v.major, v.minor, v.patch + 1, "rc.1", null)
        val parts = pre.split('.').toMutableList()
        val lastNumber = parts.indexOfLast { it.toIntOrNull() != null }
        if (lastNumber < 0) {
            parts += "1"
        } else {
            parts[lastNumber] = (parts[lastNumber].toInt() + 1).toString()
        }
        return SemVer(v.major, v.minor, v.patch, parts.joinToString("."), null)
    }

    fun satisfies(version: SemVer, range: String): Boolean =
        range.split("||").any { group -> group.trim().isNotEmpty() && matchesAll(version, group) }

    private fun matchesAll(version: SemVer, group: String): Boolean {
        val tokens = tokenize(group)
        if (tokens.isEmpty()) return false
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token == "-" && i > 0 && i + 1 < tokens.size) {
                val from = parseLoose(tokens[i - 1]) ?: return false
                val to = parseLoose(tokens[i + 1]) ?: return false
                if (version < from || version > upperBoundInclusive(tokens[i + 1], to)) return false
                i += 2
                continue
            }
            if (i + 1 < tokens.size && tokens[i + 1] == "-") {
                i++
                continue
            }
            if (!matchesSingle(version, token)) return false
            i++
        }
        // as in npm, a prerelease passes only a set that names a prerelease of the same major.minor.patch
        return version.preRelease == null || tokens.any { token ->
            val bound = parseLoose(token.trimStart('<', '>', '=', '!', '^', '~'))
            bound?.preRelease != null && bound.major == version.major && bound.minor == version.minor && bound.patch == version.patch
        }
    }

    private fun tokenize(group: String): List<String> = group.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun parseLoose(text: String): SemVer? = parse(text.trim().trimStart('=', 'v'))

    private fun upperBoundInclusive(text: String, parsed: SemVer): SemVer {
        val parts = text.trim().trimStart('v').substringBefore('-').split('.')
        return when (parts.size) {
            1 -> SemVer(parsed.major, Int.MAX_VALUE, Int.MAX_VALUE, null, null)
            2 -> SemVer(parsed.major, parsed.minor, Int.MAX_VALUE, null, null)
            else -> parsed
        }
    }

    private fun matchesSingle(version: SemVer, token: String): Boolean {
        val t = token.trim()
        if (t == "*" || t == "x" || t == "X" || t.isEmpty()) return true
        val operator = listOf(">=", "<=", "!=", "~>", ">", "<", "=", "^", "~").firstOrNull { t.startsWith(it) } ?: ""
        val rest = t.removePrefix(operator).trim().trimStart('v')
        if (rest.isEmpty()) return false
        val parts = rest.substringBefore('-').substringBefore('+').split('.')
        val wildcard = parts.indexOfFirst { it == "x" || it == "X" || it == "*" }
        val normalized = rest.split('.').joinToString(".") { if (it == "x" || it == "X" || it == "*") "0" else it }
        val target = parse(normalized) ?: return false
        val specified = if (wildcard >= 0) wildcard else parts.size
        return when {
            wildcard >= 0 && operator.isEmpty() -> when (wildcard) {
                0 -> true
                1 -> version.major == target.major
                else -> version.major == target.major && version.minor == target.minor
            }
            operator == "^" -> {
                val upper = when {
                    target.major > 0 || specified == 1 -> SemVer(target.major + 1, 0, 0, null, null)
                    target.minor > 0 || specified == 2 -> SemVer(0, target.minor + 1, 0, null, null)
                    else -> SemVer(0, 0, target.patch + 1, null, null)
                }
                version in target..<upper
            }
            operator == "~" || operator == "~>" -> {
                val upper = if (specified >= 2) SemVer(target.major, target.minor + 1, 0, null, null) else SemVer(target.major + 1, 0, 0, null, null)
                version in target..<upper
            }
            operator == ">=" -> version >= target
            operator == "<=" -> version <= target
            operator == ">" -> version > target
            operator == "<" -> version < target
            operator == "!=" -> version.compareTo(target) != 0
            else -> when (specified) {
                1 -> version.major == target.major
                2 -> version.major == target.major && version.minor == target.minor
                else -> version.compareTo(target) == 0
            }
        }
    }
}
