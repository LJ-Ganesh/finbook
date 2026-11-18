package com.syncedwallpapers.app.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syncedwallpapers.app.BuildConfig
import com.syncedwallpapers.app.data.remote.Api
import com.syncedwallpapers.app.data.repo.ActivityRepository
import com.syncedwallpapers.app.data.repo.AdminType
import com.syncedwallpapers.app.data.repo.GroupRepository
import com.syncedwallpapers.app.data.repo.NewProposal
import com.syncedwallpapers.app.data.repo.ProfileRepository
import com.syncedwallpapers.app.data.repo.ProposalRepository
import com.syncedwallpapers.app.data.repo.Target
import com.syncedwallpapers.app.di.AppModule
import io.github.jan.supabase.exceptions.RestException
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Sends every query and RPC the app makes to the configured Supabase project while
 * signed out (LIVE_API_CHECK=1 to enable). Everything is refused with "permission
 * denied", so nothing changes, but PostgREST parses the query and resolves the
 * function signature first: a malformed filter or a wrong parameter name fails with a
 * different error, and this test reports it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LiveApiShapeTest {
    @Test fun `every request is well-formed for PostgREST`() = runBlocking {
        assumeTrue(System.getenv("LIVE_API_CHECK") == "1" && BuildConfig.SUPABASE_URL.startsWith("https://"))
        val json = AppModule.json()
        val client = AppModule.supabase(androidx.test.core.app.ApplicationProvider.getApplicationContext(), json)
        val id = "00000000-0000-4000-8000-000000000001"
        val api = spyk(Api(client)).also { every { it.uid() } returns id; every { it.uidOrNull() } returns id }
        val profiles = ProfileRepository(api)
        val groups = GroupRepository(api, profiles)
        val proposals = ProposalRepository(api, json)
        val activity = ActivityRepository(api)
        val now = Instant.now()

        val calls: List<Pair<String, suspend () -> Any?>> = listOf(
            "profiles.me" to { profiles.me() },
            "profiles.profiles(in)" to { profiles.profiles(listOf(id, "00000000-0000-4000-8000-000000000002")) },
            "profiles.updateName" to { profiles.updateName("x") },
            "profiles.updatePhone(null)" to { profiles.updatePhone(null) },
            "profiles.registerDeviceToken" to { profiles.registerDeviceToken("token-token-token") },
            "profiles.removeMyDeviceTokens" to { profiles.removeMyDeviceTokens() },
            "groups.myGroups" to { groups.myGroups() },
            "groups.pendingCounts" to { groups.pendingCounts() },
            "groups.detail" to { groups.detail(id) },
            "groups.createGroup" to { groups.createGroup("x", "Asia/Kolkata") },
            "groups.setDeliveryMode" to { groups.setDeliveryMode(id, "TAP") },
            "groups.createInvite" to { groups.createInvite(id, null) },
            "groups.createInvite(email)" to { groups.createInvite(id, "a@b.co") },
            "groups.redeemInvite" to { groups.redeemInvite("ABCD2345") },
            "groups.joinPreview" to { groups.joinPreview(id) },
            "groups.acceptJoinTerms" to { groups.acceptJoinTerms(id, true) },
            "groups.cancelJoinRequest" to { groups.cancelJoinRequest(id) },
            "groups.voteJoin" to { groups.voteJoin(id, false, "r") },
            "groups.joinRequests" to { groups.joinRequests(id) },
            "groups.myJoinRequests" to { groups.myJoinRequests() },
            "groups.leave" to { groups.leave(id) },
            "groups.adminActions" to { groups.adminActions(id) },
            "groups.adminAction" to { groups.adminAction(id) },
            "groups.createAdminAction" to { groups.createAdminAction(id, AdminType.RENAME, buildJsonObject { put("name", "x") }) },
            "groups.castAdminVote" to { groups.castAdminVote(id, true, null) },
            "groups.cancelAdminAction" to { groups.cancelAdminAction(id) },
            "groups.adminSummary" to { groups.adminSummary(id) },
            "groups.declineAdmin" to { groups.declineAdmin(id) },
            "groups.acceptAdmin" to { groups.acceptAdmin(id) },
            "proposals.forGroup" to { proposals.forGroup(id, now) },
            "proposals.get" to { proposals.get(id) },
            "proposals.summary" to { proposals.summary(id) },
            "proposals.conflicts" to { proposals.conflicts(id) },
            "proposals.takenSlots" to { proposals.takenSlots(id, now, now.plusSeconds(86400)) },
            "proposals.myCommitments" to { proposals.myCommitments() },
            "proposals.create(replaces)" to { proposals.create(NewProposal(id, now.plusSeconds(7200), now.plusSeconds(9000), Target.BOTH, .4f, .6f, "hi", 1, null, replaces = id)) },
            "proposals.vote" to { proposals.vote(id, false, "r") },
            "proposals.cancel" to { proposals.cancel(id) },
            "proposals.suggestTime" to { proposals.suggestTime(id, now.plusSeconds(7200), now.plusSeconds(9000)) },
            "proposals.suggestions" to { proposals.suggestions(id) },
            "proposals.skip" to { proposals.skip(id) },
            "proposals.deliverySummary" to { proposals.deliverySummary(id) },
            "proposals.reportDelivery" to { proposals.reportDelivery(id, "READY") },
            "proposals.mySchedules" to { proposals.mySchedules() },
            "proposals.serverNow" to { proposals.serverNow() },
            "activity.inbox" to { activity.inbox() },
            "activity.markAllRead" to { activity.markAllRead() },
        )

        val bad = mutableListOf<String>()
        for ((name, call) in calls) {
            val outcome = try { call(); "no error (unexpected for a signed-out client)" } catch (e: RestException) {
                val text = "${e.statusCode} ${e.error}"
                if (e.statusCode == 401 && e.error.contains("permission denied")) null else text
            } catch (t: Throwable) { "${t::class.simpleName}: ${t.message?.take(160)}" }
            println("LIVE ${if (outcome == null) "ok " else "BAD"} $name ${outcome ?: ""}")
            if (outcome != null) bad += "$name → $outcome"
        }
        check(bad.isEmpty()) { "Malformed requests:\n" + bad.joinToString("\n") }
    }
}
