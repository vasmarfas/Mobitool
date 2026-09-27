package com.vasmarfas.card.data

import com.vasmarfas.card.core.Net
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// profile.json is edited remotely while older builds are still installed, so the file and the app can
// drift apart either way. These cases pin down what each kind of drift does
class ProfileCompatibilityTest {
    private val minimal = """
        {
          "updated": "2026-09-16",
          "person": {
            "name": { "en": "V", "ru": "В" },
            "title": { "en": "dev", "ru": "разработчик" },
            "location": { "en": "Moscow", "ru": "Москва" },
            "bio": { "en": "bio", "ru": "био" }
          }
        }
    """.trimIndent()

    private fun parse(text: String) = Net.json.decodeFromString(Profile.serializer(), text)

    private fun withFields(extra: String) =
        minimal.replace(""""updated": "2026-09-16",""", """"updated": "2026-09-16", $extra""")

    @Test
    fun aNewerFileKeepsWorkingInAnOlderBuild() {
        val profile = parse(withFields(""""mastodon": "@v@example.org", "theme": { "accent": "#326773" },"""))
        assertEquals("V", profile.person.name.en)
    }

    @Test
    fun anOlderFileKeepsWorkingInANewerBuild() {
        val profile = parse(minimal)
        assertTrue(profile.links.isEmpty())
        assertTrue(profile.projects.isEmpty())
        assertEquals(Avatar(), profile.avatar)
    }

    @Test
    fun aLinkKeepsItsPlaceUntilItIsSwitchedOff() {
        val links = parse(
            withFields(
                """"links": [
                  { "type": "telegram", "url": "https://t.me/a" },
                  { "type": "email", "url": "mailto:a@b.c", "active": false }
                ],"""
            )
        ).links
        assertEquals(listOf("telegram", "email"), links.map { it.type })
        assertTrue(links[0].active)
        assertFalse(links[1].active)
    }

    @Test
    fun aBrokenFileFailsLoudlySoTheRepositoryCanFallBack() {
        assertFails { parse("""{ "person": { "name": "not an object" } }""") }
    }

    // the home screen crossfades to every new profile value, fetching the same file again must not be one
    @Test
    fun theSameFileGivesAnEqualProfile() {
        assertEquals(parse(minimal), parse(minimal))
    }
}
