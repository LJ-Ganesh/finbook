package com.syncedwallpapers.app.ui.nav

import kotlinx.serialization.Serializable

@Serializable object OnboardingRoute
@Serializable object MainRoute
@Serializable data class GroupRoute(val groupId: String)
@Serializable data class GroupSettingsRoute(val groupId: String)
@Serializable object CreateGroupRoute
@Serializable data class InviteRoute(val groupId: String)
@Serializable data class JoinCodeRoute(val code: String? = null)
@Serializable data class JoinReviewRoute(val requestId: String)
@Serializable data class ProposeRoute(val groupId: String, val start: Long? = null, val end: Long? = null, val replaces: String? = null)
@Serializable data class ProposalRoute(val proposalId: String)
@Serializable data class AdminActionRoute(val actionId: String)
@Serializable data class NewAdminActionRoute(val groupId: String, val type: String)
@Serializable object SetupHealthRoute
