package com.vasmarfas.card.tools.security

import com.vasmarfas.card.tools.developer.HashAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Base32Test {
    @Test
    fun rfc4648Vectors() {
        assertEquals("foobar", Base32.decode("MZXW6YTBOI")?.decodeToString())
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".encodeToByteArray()))
        assertEquals("fo", Base32.decode("MZXQ====")?.decodeToString())
        assertEquals(10, assertNotNull(Base32.decode("JBSWY3DPEHPK3PXP")).size)
        assertNull(Base32.decode("1890"))
    }
}

class ChecksumTest {
    private val hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun readsCommonLayouts() {
        assertEquals(hash, normalizeHash("SHA256 (ubuntu.iso) = $hash"))
        assertEquals(hash, normalizeHash("$hash  ubuntu-24.04.iso"))
        assertEquals(hash, normalizeHash("$hash *ubuntu.iso"))
        assertEquals(hash, normalizeHash(hash.uppercase().chunked(2).joinToString(":")))
        assertEquals("e3b0c44298fc1c14", normalizeHash("E3B0 C442 98FC 1C14"))
        assertFalse(normalizeHash("E3B0 C442 98FC 1C14") == normalizeHash("E3B0 FFFF 0000 1111"))
    }
}

class TotpTest {
    private val seed = "12345678901234567890".encodeToByteArray()

    @Test
    fun rfc6238Vectors() {
        assertEquals("94287082", Totp.code(seed, Totp.counter(59_000L, 30), 8, HashAlgorithm.SHA1))
        assertEquals("07081804", Totp.code(seed, Totp.counter(1_111_111_109_000L, 30), 8, HashAlgorithm.SHA1))
        assertEquals("14050471", Totp.code(seed, Totp.counter(1_111_111_111_000L, 30), 8, HashAlgorithm.SHA1))
        assertEquals("89005924", Totp.code(seed, Totp.counter(1_234_567_890_000L, 30), 8, HashAlgorithm.SHA1))
        assertEquals("69279037", Totp.code(seed, Totp.counter(2_000_000_000_000L, 30), 8, HashAlgorithm.SHA1))
    }

    @Test
    fun tenDigitCodeKeepsTheWholeValue() {
        assertEquals("1726969429", Totp.code(seed, Totp.counter(90_000L, 30), 10, HashAlgorithm.SHA1))
    }

    @Test
    fun sixDigitCodeIsSuffix() {
        val eight = Totp.code(seed, Totp.counter(59_000L, 30), 8, HashAlgorithm.SHA1)
        assertEquals(eight.takeLast(6), Totp.code(seed, Totp.counter(59_000L, 30), 6, HashAlgorithm.SHA1))
    }

    @Test
    fun remainingSeconds() {
        assertEquals(30, Totp.secondsRemaining(60_000L, 30))
        assertEquals(1, Totp.secondsRemaining(89_000L, 30))
        assertEquals(0L, Totp.counter(29_000L, 30))
        assertEquals(1L, Totp.counter(30_000L, 30))
    }

    @Test
    fun parsesOtpauthUri() {
        val auth = assertNotNull(Totp.parseUri("otpauth://totp/ACME%20Co:john@example.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co&digits=8&period=60&algorithm=SHA256"))
        assertEquals("JBSWY3DPEHPK3PXP", auth.secret)
        assertEquals("ACME Co", auth.issuer)
        assertEquals("john@example.com", auth.account)
        assertEquals(8, auth.digits)
        assertEquals(60, auth.period)
        assertEquals(HashAlgorithm.SHA256, auth.algorithm)
        assertNull(Totp.parseUri("https://example.com"))
        assertNull(Totp.parseUri("otpauth://totp/x?issuer=y"))
    }

    @Test
    fun defaultsWhenParamsMissing() {
        val auth = assertNotNull(Totp.parseUri("otpauth://totp/acc?secret=MZXW6YTBOI"))
        assertEquals(6, auth.digits)
        assertEquals(30, auth.period)
        assertEquals(HashAlgorithm.SHA1, auth.algorithm)
    }
}

class PasswordGenTest {
    @Test
    fun respectsLengthAndAlphabet() {
        val sets = CharSets(lower = true, upper = false, digits = false, symbols = false)
        val password = assertNotNull(PasswordGen.password(24, sets, requireEach = false))
        assertEquals(24, password.length)
        assertTrue(password.all { it in PasswordGen.LOWER })
    }

    @Test
    fun requireEachIncludesEverySet() {
        val sets = CharSets(lower = true, upper = true, digits = true, symbols = true)
        repeat(20) {
            val password = assertNotNull(PasswordGen.password(8, sets, requireEach = true))
            assertTrue(password.any { it in PasswordGen.LOWER })
            assertTrue(password.any { it in PasswordGen.UPPER })
            assertTrue(password.any { it in PasswordGen.DIGITS })
            assertTrue(password.any { it in PasswordGen.SYMBOLS })
        }
    }

    @Test
    fun excludeAmbiguousRemovesLookalikes() {
        val sets = CharSets(excludeAmbiguous = true)
        val password = assertNotNull(PasswordGen.password(60, sets, requireEach = false))
        assertTrue(password.none { it in "Il1O0" })
    }

    @Test
    fun rejectsImpossibleRequests() {
        assertNull(PasswordGen.password(10, CharSets(lower = false, upper = false, digits = false, symbols = false), requireEach = false))
        assertNull(PasswordGen.password(2, CharSets(), requireEach = true))
    }

    @Test
    fun passphraseShape() {
        val phrase = PasswordGen.passphrase(4, WordLists.english, "-", capitalize = true, addNumber = false)
        assertEquals(4, phrase.split("-").size)
        assertTrue(phrase.split("-").all { it.first().isUpperCase() })
    }

    @Test
    fun entropyMatchesKnownValues() {
        assertEquals(128.0, PasswordGen.entropyBits(256, 16), 0.001)
        assertEquals(64.0, PasswordGen.entropyBits(2, 64), 0.001)
        assertTrue(PasswordGen.passphraseEntropyBits(1000, 4, addNumber = false) > 39.0)
    }

    @Test
    fun randomIntStaysInRange() {
        repeat(200) { assertTrue(PasswordGen.randomInt(6) in 0..5) }
        assertEquals(0, PasswordGen.randomInt(1))
    }
}

class PasswordStrengthTest {
    @Test
    fun weakPasswordsScoreLow() {
        assertEquals(0, PasswordStrength.analyze("123456").score)
        assertEquals(0, PasswordStrength.analyze("password").score)
        assertTrue(PasswordStrength.analyze("qwerty123").score <= 1)
    }

    @Test
    fun longRandomPasswordScoresHigh() {
        assertTrue(PasswordStrength.analyze("7xK#pQ2mVz!9Lw\$Rt4Bn").score >= 3)
    }

    @Test
    fun detectsPatterns() {
        assertTrue(PasswordStrength.analyze("aaaa1234").issues.isNotEmpty())
        assertEquals(4, PasswordStrength.longestRepeat("baaaab"))
        assertEquals(5, PasswordStrength.longestSequence("ab12345x".substring(2)))
        assertTrue(PasswordStrength.keyboardWalk("xqwertyx") >= 6)
        assertTrue(PasswordStrength.hasDate("john1985"))
        assertFalse(PasswordStrength.hasDate("john"))
        assertNotNull(PasswordStrength.dictionaryHit("P4ssword"))
    }

    @Test
    fun alphabetSizeCountsSets() {
        assertEquals(26, PasswordStrength.alphabetSize("abc"))
        assertEquals(52, PasswordStrength.alphabetSize("abcABC"))
        assertEquals(62, PasswordStrength.alphabetSize("abcABC123"))
        assertTrue(PasswordStrength.alphabetSize("abc!") > 26)
    }

    @Test
    fun emptyPasswordIsHandled() {
        val result = PasswordStrength.analyze("")
        assertEquals(0, result.length)
        assertEquals(0.0, result.bits, 0.001)
        assertTrue(result.suggestions.isNotEmpty())
    }
}

class DiceTest {
    @Test
    fun parsesNotation() {
        val roll = assertNotNull(Dice.roll("3d6+2"))
        assertEquals(3, roll.rolls.size)
        assertEquals(6, roll.sides)
        assertEquals(2, roll.modifier)
        assertTrue(roll.rolls.all { it in 1..6 })
        assertTrue(roll.total in 5..20)
        assertEquals(1, assertNotNull(Dice.roll("d20")).rolls.size)
        assertEquals(-1, assertNotNull(Dice.roll("2d8-1")).modifier)
        assertNull(Dice.roll("3x6"))
        assertNull(Dice.roll("0d6"))
        assertNull(Dice.roll("1d1"))
    }

    @Test
    fun uniqueDrawHasNoRepeats() {
        val values = assertNotNull(Dice.uniqueInts(1, 49, 6))
        assertEquals(6, values.size)
        assertEquals(6, values.toSet().size)
        assertTrue(values.all { it in 1..49 })
        assertNull(Dice.uniqueInts(1, 3, 4))
    }

    @Test
    fun wideRangesUseTheWholeSpan() {
        assertTrue(List(40) { Dice.intInRange(0, Int.MAX_VALUE) }.any { it != 0 })
        assertTrue(List(40) { Dice.intInRange(-2_000_000_000, 2_000_000_000) }.any { it > 0 })
        assertEquals(5, assertNotNull(Dice.uniqueInts(1, 300_000_000, 5)).toSet().size)
        assertEquals((1..10).toSet(), assertNotNull(Dice.uniqueInts(1, 10, 10)).toSet())
        assertEquals(1000, assertNotNull(Dice.uniqueInts(Int.MIN_VALUE, Int.MAX_VALUE, 1000)).toSet().size)
    }

    @Test
    fun shuffleKeepsElements() {
        val items = (1..20).toList()
        assertEquals(items.toSet(), Dice.shuffled(items).toSet())
        assertTrue(Dice.intInRange(5, 5) == 5)
        assertNull(Dice.pick(emptyList<String>()))
    }
}
