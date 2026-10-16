package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource

class StoreType(val type: StringResource, val usage: List<StringResource>)

class StoreAnswers(
    val questions: List<Pair<StringResource, StringResource>>,
    val types: List<StoreType>,
    val notes: List<StringResource>,
)

private class TypeBuilder(private val order: List<StringResource>) {
    private val types = linkedMapOf<StringResource, LinkedHashSet<StringResource>>()

    fun add(type: StringResource, vararg usage: StringResource) {
        types.getOrPut(type) { linkedSetOf() } += usage
    }

    fun build(): List<StoreType> = types.map { (type, usage) -> StoreType(type, usage.sortedBy { order.indexOf(it) }) }
}

object PolicyStores {
    private val playOrder = listOf(
        Res.string.privacy_mark_collected,
        Res.string.privacy_mark_shared,
        Res.string.privacy_purpose_functionality,
        Res.string.privacy_purpose_account,
        Res.string.privacy_purpose_analytics,
        Res.string.privacy_purpose_ads,
        Res.string.privacy_purpose_fraud,
    )

    private val appleOrder = listOf(
        Res.string.privacy_mark_linked,
        Res.string.privacy_mark_not_linked,
        Res.string.privacy_mark_tracking,
        Res.string.privacy_purpose_functionality,
        Res.string.privacy_purpose_analytics,
        Res.string.privacy_purpose_third_party_ads,
    )

    fun dataSafety(input: PolicyInput): StoreAnswers {
        val data = input.data
        if (data.isEmpty()) {
            return StoreAnswers(listOf(Res.string.privacy_play_collects to Res.string.no), emptyList(), listOf(Res.string.privacy_play_note_nothing))
        }
        val collected = Res.string.privacy_mark_collected
        val shared = Res.string.privacy_mark_shared
        val analytics = Res.string.privacy_purpose_analytics
        val functionality = Res.string.privacy_purpose_functionality
        val fraud = Res.string.privacy_purpose_fraud
        val types = TypeBuilder(playOrder)
        if (PolicyData.ANALYTICS in data) {
            val marks = if (PolicySdk.APPMETRICA in input.usedSdks) arrayOf(collected, shared) else arrayOf(collected)
            types.add(Res.string.privacy_play_app_interactions, *marks, analytics)
            types.add(Res.string.privacy_play_device_ids, *marks, analytics)
            types.add(Res.string.privacy_play_approximate_location, *marks, analytics)
            if (PolicyData.PURCHASES in data) types.add(Res.string.privacy_play_purchase_history, *marks, analytics)
        }
        if (PolicyData.CRASHES in data) {
            types.add(Res.string.privacy_play_crash_logs, collected, analytics)
            types.add(Res.string.privacy_play_diagnostics, collected, analytics)
            types.add(Res.string.privacy_play_device_ids, collected, analytics)
        }
        if (PolicyData.ADS in data) {
            val ads = Res.string.privacy_purpose_ads
            types.add(Res.string.privacy_play_approximate_location, collected, shared, ads, analytics, fraud)
            types.add(Res.string.privacy_play_app_interactions, collected, shared, ads, analytics, fraud)
            types.add(Res.string.privacy_play_diagnostics, collected, shared, ads, analytics, fraud)
            types.add(Res.string.privacy_play_device_ids, collected, shared, ads, analytics, fraud)
        }
        if (PolicyData.ACCOUNTS in data) {
            types.add(Res.string.privacy_play_email, collected, functionality, Res.string.privacy_purpose_account)
            types.add(Res.string.privacy_play_user_ids, collected, functionality, Res.string.privacy_purpose_account)
        }
        if (PolicyData.LOCATION in data) types.add(Res.string.privacy_play_precise_location, collected, functionality)
        if (PolicyData.CAMERA in data) types.add(Res.string.privacy_play_photos, collected, functionality)
        if (PolicyData.PURCHASES in data) types.add(Res.string.privacy_play_purchase_history, collected, functionality, fraud)
        val notes = buildList {
            if (PolicyData.LOCATION in data || PolicyData.CAMERA in data) add(Res.string.privacy_note_on_device)
            if (PolicyData.ACCOUNTS in data) add(Res.string.privacy_play_note_deletion)
            if (input.children) add(Res.string.privacy_play_note_children)
        }
        return StoreAnswers(
            listOf(
                Res.string.privacy_play_collects to Res.string.yes,
                Res.string.privacy_play_encrypted to Res.string.yes,
                Res.string.privacy_play_deletion to if (PolicyData.ACCOUNTS in data) Res.string.yes else Res.string.no,
            ),
            types.build(),
            notes,
        )
    }

    fun appPrivacy(input: PolicyInput): StoreAnswers {
        val data = input.data
        if (data.isEmpty()) {
            return StoreAnswers(listOf(Res.string.privacy_apple_collects to Res.string.no), emptyList(), listOf(Res.string.privacy_apple_note_nothing))
        }
        val accounts = PolicyData.ACCOUNTS in data
        val appMetrica = PolicySdk.APPMETRICA in input.usedSdks
        val tracking = (PolicyData.ADS in data || appMetrica) && !input.children
        val linked = Res.string.privacy_mark_linked
        val notLinked = Res.string.privacy_mark_not_linked
        val trackingMarks = if (tracking) arrayOf(Res.string.privacy_mark_tracking) else emptyArray()
        val metricaTracking = if (appMetrica) trackingMarks else emptyArray()
        val analytics = Res.string.privacy_purpose_analytics
        val functionality = Res.string.privacy_purpose_functionality
        val ads = Res.string.privacy_purpose_third_party_ads
        val owned = if (accounts) linked else notLinked
        val types = TypeBuilder(appleOrder)
        if (PolicyData.ANALYTICS in data) {
            types.add(Res.string.privacy_apple_device_id, linked, *metricaTracking, analytics)
            types.add(Res.string.privacy_apple_product_interaction, notLinked, analytics)
            types.add(Res.string.privacy_apple_coarse_location, notLinked, analytics)
            if (PolicyData.PURCHASES in data) types.add(Res.string.privacy_apple_purchase_history, owned, analytics)
        }
        if (PolicyData.CRASHES in data) {
            types.add(Res.string.privacy_apple_crash_data, notLinked, functionality)
            types.add(Res.string.privacy_apple_performance_data, notLinked, functionality)
            types.add(Res.string.privacy_apple_other_diagnostics, notLinked, analytics)
        }
        if (PolicyData.ADS in data) {
            types.add(Res.string.privacy_apple_device_id, linked, *trackingMarks, ads, analytics)
            types.add(Res.string.privacy_apple_advertising_data, notLinked, *trackingMarks, ads, analytics)
            types.add(Res.string.privacy_apple_product_interaction, notLinked, ads, analytics)
            types.add(Res.string.privacy_apple_coarse_location, notLinked, ads, analytics)
            types.add(Res.string.privacy_apple_performance_data, linked, ads, analytics)
        }
        if (accounts) {
            types.add(Res.string.privacy_apple_email, linked, functionality)
            types.add(Res.string.privacy_apple_user_id, linked, functionality)
        }
        if (PolicyData.LOCATION in data) types.add(Res.string.privacy_apple_precise_location, owned, functionality)
        if (PolicyData.CAMERA in data) types.add(Res.string.privacy_apple_photos, owned, functionality)
        if (PolicyData.PURCHASES in data) types.add(Res.string.privacy_apple_purchase_history, owned, functionality)
        val notes = buildList {
            if (tracking) add(Res.string.privacy_apple_note_att)
            if (PolicyData.ANALYTICS in data || PolicyData.CRASHES in data) add(Res.string.privacy_apple_note_user_id)
            if (PolicyData.LOCATION in data || PolicyData.CAMERA in data) add(Res.string.privacy_note_on_device)
            if (accounts && !input.inAppDeletion) add(Res.string.privacy_apple_note_deletion)
            if (input.children && (PolicyData.ANALYTICS in data || PolicyData.ADS in data)) add(Res.string.privacy_apple_note_kids)
        }
        return StoreAnswers(
            listOf(
                Res.string.privacy_apple_collects to Res.string.yes,
                Res.string.privacy_apple_tracking to if (tracking) Res.string.yes else Res.string.no,
            ),
            types.build().map { if (linked in it.usage) StoreType(it.type, it.usage - notLinked) else it },
            notes,
        )
    }
}
