package com.vasmarfas.card.data

import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.tools.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingTest {
    private val everywhere: (String) -> Boolean = { true }

    @Test
    fun everyOfferedToolExists() {
        val missing = Interest.entries.flatMap { it.tools }.filter { ToolRegistry.byId(it) == null }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun everyToolBelongsToAnInterest() {
        val offered = Interest.entries.flatMap { it.tools }.toSet()
        val orphans = ToolRegistry.all.map { it.id }.filter { it !in offered }
        assertEquals(emptyList(), orphans, "a new tool has to go into an Interest in data/Onboarding.kt")
    }

    @Test
    fun interestsListEachToolOnce() {
        Interest.entries.forEach { assertEquals(it.tools.size, it.tools.toSet().size, it.id) }
    }

    @Test
    fun idsAreUniqueAndSafeForAnalytics() {
        val ids = Interest.entries.map { it.id } + Role.entries.map { it.id }
        assertTrue(ids.all { Regex("^[a-z]+$").matches(it) }, ids.toString())
        assertEquals(Interest.entries.size, Interest.entries.map { it.id }.toSet().size)
        assertEquals(Role.entries.size, Role.entries.map { it.id }.toSet().size)
    }

    @Test
    fun everyRolePreselectsSomething() {
        Role.entries.forEach { assertTrue(it.interests.isNotEmpty(), it.id) }
    }

    @Test
    fun everyRoleFitsTheBudget() {
        Role.entries.forEach { role ->
            val proposed = Onboarding.propose(listOf(role), role.interests, everywhere)
            assertTrue(proposed.size in Onboarding.MIN_TOOLS..Onboarding.BUDGET, "${role.id}: ${proposed.size}")
            assertEquals(proposed.size, proposed.toSet().size)
        }
    }

    @Test
    fun oneInterestFillsTheBudgetInItsOwnOrder() {
        assertEquals(Interest.NETWORK.tools.take(Onboarding.BUDGET), Onboarding.propose(emptyList(), listOf(Interest.NETWORK), everywhere))
    }

    @Test
    fun eachInterestKeepsItsLeadingTools() {
        val all = Onboarding.propose(Role.entries, Interest.entries, everywhere)
        Interest.entries.forEach { interest -> assertTrue(interest.tools.first() in all, interest.id) }
        val role = Onboarding.propose(listOf(Role.HOME), Role.HOME.interests, everywhere)
        Role.HOME.interests.forEach { interest -> assertTrue(interest.tools.take(2).all { it in role }, interest.id) }
        val few = listOf(Interest.MUSIC, Interest.PRINTING)
        val short = Onboarding.propose(emptyList(), few, everywhere)
        few.forEach { interest -> assertTrue(interest.tools.take(3).all { it in short }, interest.id) }
    }

    @Test
    fun severalRolesStayWithinTheBudget() {
        val roles = listOf(Role.HOME, Role.DEVELOPER)
        val interests = Interest.entries.filter { interest -> roles.any { interest in it.interests } }
        val proposed = Onboarding.propose(roles, interests, everywhere)
        assertEquals(Onboarding.BUDGET, proposed.size)
        interests.forEach { interest -> assertTrue(interest.tools.first() in proposed, interest.id) }
    }

    @Test
    fun interestsSharedByRolesAndPickedByHandWeighMore() {
        val homeAndOffice = listOf(Role.HOME, Role.OFFICE)
        assertEquals(1.5, Onboarding.weight(Interest.DOCUMENTS, homeAndOffice))
        assertEquals(1.0, Onboarding.weight(Interest.REPAIR, homeAndOffice))
        assertEquals(1.5, Onboarding.weight(Interest.MUSIC, listOf(Role.DEVELOPER)))
    }

    @Test
    fun sharedInterestsTakeTheSpareSlots() {
        val roles = listOf(Role.HOME, Role.OFFICE)
        val interests = Interest.entries.filter { interest -> roles.any { interest in it.interests } }
        val proposed = Onboarding.propose(roles, interests, everywhere)
        assertEquals(Onboarding.BUDGET, proposed.size)
        assertTrue(listOf("merge-pdf", "id-photo", "discount-vat").all { it in proposed }, proposed.toString())
        assertTrue("renovation" !in proposed, proposed.toString())
    }

    @Test
    fun aHandPickedInterestOutweighsTheRoleDefaults() {
        val roles = listOf(Role.DEVELOPER)
        val interests = Interest.entries.filter { it in Role.DEVELOPER.interests || it == Interest.MUSIC }
        val proposed = Onboarding.propose(roles, interests, everywhere)
        val music = Interest.MUSIC.tools.count { it in proposed }
        assertTrue(music >= 4, proposed.toString())
        Role.DEVELOPER.interests.forEach { interest -> assertTrue(interest.tools.count { it in proposed } < music, interest.id) }
    }

    @Test
    fun aNarrowChoiceIsToppedUpWithEverydayTools() {
        val available = { id: String -> id == "fuel-cost" || id == "tire-size" || id in Onboarding.STARTER }
        val proposed = Onboarding.propose(listOf(Role.TRAVEL), listOf(Interest.AUTO), available)
        assertEquals(Onboarding.MIN_TOOLS, proposed.size)
        assertEquals(listOf("fuel-cost", "tire-size"), proposed.take(2))
        assertTrue(proposed.drop(2).all { it in Onboarding.STARTER })
    }

    @Test
    fun unavailableToolsAreLeftOut() {
        val proposed = Onboarding.propose(listOf(Role.MAKER), listOf(Interest.REPAIR)) { it != "bubble-level" }
        assertTrue("bubble-level" !in proposed)
        assertTrue("ruler" in proposed)
    }

    @Test
    fun extrasSkipTheProposalAndComeBestFirst() {
        val roles = listOf(Role.HOME)
        val interests = Role.HOME.interests
        val proposed = Onboarding.propose(roles, interests, everywhere)
        val extras = Onboarding.extras(roles, interests, proposed, everywhere)
        assertTrue(extras.none { it in proposed })
        assertEquals(interests.flatMap { it.tools }.toSet(), (proposed + extras).toSet())
        val scores = Onboarding.scores(roles, interests, everywhere)
        assertEquals(extras.sortedByDescending { scores.getValue(it) }, extras)
    }

    @Test
    fun roleKeyStaysShortForAnalytics() {
        assertNull(Onboarding.roleKey(emptyList()))
        assertEquals("admin", Onboarding.roleKey(listOf("admin")))
        assertEquals("home-study", Onboarding.roleKey(listOf("study", "home")))
        assertEquals("mixed", Onboarding.roleKey(listOf("home", "study", "admin")))
        Role.entries.forEach { a ->
            Role.entries.forEach { b ->
                val key = Onboarding.roleKey(listOf(a.id, b.id)).orEmpty()
                assertTrue(key.length <= 36 && Regex("^[a-z-]+$").matches(key), key)
            }
        }
    }

    @Test
    fun starterRunsEverywhere() {
        assertTrue(Onboarding.STARTER.size in 6..Onboarding.MIN_TOOLS)
        Onboarding.STARTER.forEach { id ->
            assertEquals(PlatformKind.all, ToolRegistry.byId(id)?.platforms, id)
        }
    }
}
