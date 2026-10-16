package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyPolicyTest {
    private val template = """
        <!-- placeholders -->
        app: [app name]
        contact: [contact]
        <!-- always -->
        # Privacy Policy

        **Effective date:** {date}
        <!-- if app -->
        {app} is a mobile app by {developer}.
        <!-- if site -->
        {app} is a website by {developer}.
        <!-- if nothing -->
        Nothing is collected.
        <!-- if sdk -->
        ## Third-party services
        <!-- if sdk:firebase-analytics -->
        - **Firebase Analytics.** [Google Privacy Policy](https://policies.google.com/privacy)
        <!-- if sdk:crashlytics -->
        - **Crashlytics.** Crash reports.
        <!-- if ads !children -->
        Ads are personalised.
        <!-- if ads children -->
        Ads are not personalised.
        <!-- if in-app-deletion|deletion-url -->
        You can delete the account yourself.
        <!-- always -->
        Contact: {contact}
    """.trimIndent()

    private val app = PolicyInput(
        app = "Notes",
        developer = "Jane Doe",
        contact = "jane@example.com",
        data = setOf(PolicyData.ANALYTICS, PolicyData.CRASHES),
        sdks = setOf(PolicySdk.FIREBASE_ANALYTICS, PolicySdk.CRASHLYTICS),
        date = "2026-10-16",
    )

    @Test
    fun conditionsCombineTermsAndAlternatives() {
        val flags = setOf("app", "ads")
        assertTrue(PolicyTemplates.matches("always", flags))
        assertTrue(PolicyTemplates.matches("if app ads", flags))
        assertFalse(PolicyTemplates.matches("if app children", flags))
        assertTrue(PolicyTemplates.matches("if ads !children", flags))
        assertTrue(PolicyTemplates.matches("if site|ads", flags))
        assertFalse(PolicyTemplates.matches("if !app|children", flags))
        assertFalse(PolicyTemplates.matches("placeholders", flags))
    }

    @Test
    fun flagsFollowTheInput() {
        val flags = PolicyTemplates.flags(app)
        assertTrue("app" in flags && "site" !in flags && "nothing" !in flags)
        assertTrue("sdk" in flags && "sdk:firebase-analytics" in flags && "sdk:crashlytics" in flags)
        val both = PolicyTemplates.flags(PolicyInput(product = PolicyProduct.BOTH))
        assertTrue("app" in both && "site" in both && "both" in both && "nothing" in both && "sdk" !in both)
        assertFalse("in-app-deletion" in PolicyTemplates.flags(PolicyInput(inAppDeletion = true)))
        assertTrue("in-app-deletion" in PolicyTemplates.flags(PolicyInput(data = setOf(PolicyData.ACCOUNTS), inAppDeletion = true)))
    }

    @Test
    fun sdkWithoutItsDataOrPlatformIsDropped() {
        val noCrashes = app.copy(data = setOf(PolicyData.ANALYTICS))
        assertEquals(listOf(PolicySdk.FIREBASE_ANALYTICS), noCrashes.usedSdks)
        val site = app.copy(product = PolicyProduct.SITE, sdks = setOf(PolicySdk.FIREBASE_ANALYTICS, PolicySdk.GOOGLE_ANALYTICS, PolicySdk.SENTRY))
        assertEquals(listOf(PolicySdk.GOOGLE_ANALYTICS, PolicySdk.SENTRY), site.usedSdks)
    }

    @Test
    fun markdownKeepsOnlyMatchingBlocks() {
        val text = PolicyTemplates.markdown(template, app)
        assertEquals(
            """
            # Privacy Policy

            **Effective date:** 2026-10-16

            Notes is a mobile app by Jane Doe.

            ## Third-party services

            - **Firebase Analytics.** [Google Privacy Policy](https://policies.google.com/privacy)
            - **Crashlytics.** Crash reports.

            Contact: [jane@example.com](mailto:jane@example.com)

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun emptyFieldsFallBackToPlaceholders() {
        val text = PolicyTemplates.markdown(template, PolicyInput(product = PolicyProduct.SITE, date = "2026-10-16"))
        assertTrue("\\[app name\\] is a website by ." in text, text)
        assertTrue("Nothing is collected." in text)
        assertFalse("{" in text)
    }

    @Test
    fun userTextCannotBreakTheMarkup() {
        val tricky = app.copy(app = "My_*App* <b>", developer = "A & B")
        val markdown = PolicyTemplates.markdown(template, tricky)
        assertTrue("My\\_\\*App\\* \\<b\\> is a mobile app by A & B." in markdown, markdown)
        val html = PolicyTemplates.html(template, tricky, "en")
        assertTrue("<p>My_*App* &lt;b&gt; is a mobile app by A &amp; B.</p>" in html, html)
        assertFalse("<b>" in html)
    }

    @Test
    fun htmlIsACompleteDocument() {
        val html = PolicyTemplates.html(template, app.copy(children = true, data = app.data + PolicyData.ADS), "en")
        assertTrue(html.startsWith("<!DOCTYPE html>\n<html lang=\"en\">"))
        assertTrue("<title>Privacy Policy · Notes</title>" in html)
        assertTrue("<h1>Privacy Policy</h1>" in html)
        assertTrue("<p><strong>Effective date:</strong> 2026-10-16</p>" in html)
        assertTrue(
            "<ul>\n<li><strong>Firebase Analytics.</strong> <a href=\"https://policies.google.com/privacy\">Google Privacy Policy</a></li>\n" +
                "<li><strong>Crashlytics.</strong> Crash reports.</li>\n</ul>" in html,
            html,
        )
        assertTrue("Ads are not personalised." in html && "Ads are personalised." !in html)
        assertTrue("<a href=\"mailto:jane@example.com\">jane@example.com</a>" in html)
        assertTrue(html.endsWith("</body>\n</html>\n"))
    }

    @Test
    fun storeAnswersFollowTheData() {
        val nothing = PolicyStores.dataSafety(PolicyInput())
        assertEquals(listOf(Res.string.privacy_play_collects to Res.string.no), nothing.questions)
        assertTrue(nothing.types.isEmpty())

        val crashes = PolicyStores.dataSafety(PolicyInput(data = setOf(PolicyData.CRASHES)))
        assertTrue(crashes.types.none { Res.string.privacy_mark_shared in it.usage })
        val metrica = PolicyInput(data = setOf(PolicyData.ANALYTICS), sdks = setOf(PolicySdk.APPMETRICA))
        assertTrue(PolicyStores.dataSafety(metrica).types.all { Res.string.privacy_mark_shared in it.usage })
        assertEquals(Res.string.yes, PolicyStores.appPrivacy(metrica).questions.last().second)

        val ads = PolicyInput(data = setOf(PolicyData.ADS, PolicyData.CRASHES))
        val ids = PolicyStores.dataSafety(ads).types.single { it.type == Res.string.privacy_play_device_ids }
        assertEquals(Res.string.privacy_mark_shared, ids.usage[1])
        val apple = PolicyStores.appPrivacy(ads)
        assertEquals(Res.string.yes, apple.questions.last().second)
        val performance = apple.types.single { it.type == Res.string.privacy_apple_performance_data }
        assertTrue(Res.string.privacy_mark_linked in performance.usage && Res.string.privacy_mark_not_linked !in performance.usage)
        assertEquals(Res.string.no, PolicyStores.appPrivacy(ads.copy(children = true)).questions.last().second)
    }

    @Test
    fun deletionNeedsAnAccount() {
        val withUrl = app.copy(deletionUrl = "https://example.com/delete")
        assertFalse("delete the account" in PolicyTemplates.markdown(template, withUrl))
        val account = withUrl.copy(data = setOf(PolicyData.ACCOUNTS), inAppDeletion = false)
        assertTrue("delete the account" in PolicyTemplates.markdown(template, account))
    }
}
