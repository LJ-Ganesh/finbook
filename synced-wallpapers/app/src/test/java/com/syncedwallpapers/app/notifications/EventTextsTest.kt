package com.syncedwallpapers.app.notifications

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class EventTextsTest {
    @Test fun `rejection shows the reason without a name`() {
        val t = EventTexts.forEvent("PROPOSAL_REJECTED", buildJsonObject { put("group_name", "Family"); put("reason", "Too dark") })!!
        assertThat(t.body).contains("Too dark")
        assertThat(t.title).isEqualTo("Family: not approved")
    }

    @Test fun `skips are anonymous`() {
        val t = EventTexts.forEvent("SCHEDULE_SKIPPED", buildJsonObject { put("group_name", "Family") })!!
        assertThat(t.body).startsWith("A member skipped")
    }

    @Test fun `data-only events show nothing`() {
        assertThat(EventTexts.forEvent("LEFT_GROUP", buildJsonObject { })).isNull()
    }

    @Test fun `every server event type has a channel`() {
        listOf("NEW_PROPOSAL", "VOTE_REMINDER", "PROPOSAL_APPROVED", "PROPOSAL_EXPIRED", "PROPOSAL_CANCELLED", "TIME_SUGGESTION",
            "JOIN_REQUEST", "JOIN_RESULT", "JOIN_TERMS_CHANGED", "MEMBER_JOINED", "MEMBER_LEFT", "MEMBER_REMOVED", "REMOVED_FROM_GROUP",
            "GROUP_DELETED", "ADMIN_ACTION_REQUEST", "ADMIN_ACTION_RESULT", "ADMIN_CHANGED", "YOU_ARE_ADMIN").forEach {
            assertThat(EventTexts.forEvent(it, buildJsonObject { put("group_name", "G") })).isNotNull()
        }
    }
}
