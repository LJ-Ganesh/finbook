package com.syncedwallpapers.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.syncedwallpapers.app.data.repo.AuthState
import com.syncedwallpapers.app.ui.AppViewModel
import com.syncedwallpapers.app.ui.components.Loading
import com.syncedwallpapers.app.ui.screens.activity.ActivityTab
import com.syncedwallpapers.app.ui.screens.group.AdminActionScreen
import com.syncedwallpapers.app.ui.screens.group.CreateGroupScreen
import com.syncedwallpapers.app.ui.screens.group.GroupScreen
import com.syncedwallpapers.app.ui.screens.group.GroupSettingsScreen
import com.syncedwallpapers.app.ui.screens.group.InviteScreen
import com.syncedwallpapers.app.ui.screens.group.NewAdminActionScreen
import com.syncedwallpapers.app.ui.screens.home.HomeTab
import com.syncedwallpapers.app.ui.screens.join.JoinCodeScreen
import com.syncedwallpapers.app.ui.screens.join.JoinReviewScreen
import com.syncedwallpapers.app.ui.screens.onboarding.OnboardingScreen
import com.syncedwallpapers.app.ui.screens.propose.ProposeScreen
import com.syncedwallpapers.app.ui.screens.proposal.ProposalScreen
import com.syncedwallpapers.app.ui.screens.settings.SettingsTab
import com.syncedwallpapers.app.ui.screens.settings.SetupHealthScreen

@Composable
fun AppRoot(app: AppViewModel, initialRoute: String?) {
    val auth by app.authState.collectAsState()
    val settings by app.settings.collectAsState()
    val s = settings
    if (auth is AuthState.Loading || s == null) { Loading(); return }
    val signedIn = auth is AuthState.SignedIn
    if (!signedIn || !s.onboardingDone) {
        OnboardingScreen(signedIn = signedIn, onboardingDone = s.onboardingDone)
        return
    }
    SignedInNav(app, initialRoute)
}

@Composable
private fun SignedInNav(app: AppViewModel, initialRoute: String?) {
    val nav = rememberNavController()
    val invite by app.pendingInvite.collectAsState()
    LaunchedEffect(invite) { invite?.let { nav.navigate(JoinCodeRoute(it)); app.consumeInvite() } }
    LaunchedEffect(initialRoute) {
        initialRoute?.removePrefix("group/")?.takeIf { initialRoute.startsWith("group/") }?.let { nav.navigate(GroupRoute(it)) }
    }
    NavHost(nav, startDestination = MainRoute) {
        composable<MainRoute> {
            MainTabs(
                onOpenGroup = { nav.navigate(GroupRoute(it)) },
                onCreateGroup = { nav.navigate(CreateGroupRoute) },
                onJoin = { nav.navigate(JoinCodeRoute()) },
                onOpenJoin = { nav.navigate(JoinReviewRoute(it)) },
                onOpenHealth = { nav.navigate(SetupHealthRoute) },
            )
        }
        composable<GroupRoute> {
            GroupScreen(
                onBack = { nav.popBackStack() },
                onSettings = { nav.navigate(GroupSettingsRoute(it)) },
                onPropose = { nav.navigate(ProposeRoute(it)) },
                onOpenProposal = { nav.navigate(ProposalRoute(it)) },
                onOpenAdminAction = { nav.navigate(AdminActionRoute(it)) },
            )
        }
        composable<GroupSettingsRoute> {
            GroupSettingsScreen(
                onBack = { nav.popBackStack() },
                onLeft = { nav.popBackStack(MainRoute, inclusive = false) },
                onInvite = { nav.navigate(InviteRoute(it)) },
                onNewAction = { gid, type -> nav.navigate(NewAdminActionRoute(gid, type.name)) },
                onOpenAction = { nav.navigate(AdminActionRoute(it)) },
            )
        }
        composable<CreateGroupRoute> {
            CreateGroupScreen(onBack = { nav.popBackStack() }, onCreated = { id ->
                nav.navigate(GroupRoute(id)) { popUpTo(MainRoute) }
            })
        }
        composable<InviteRoute> { InviteScreen(it.toRoute<InviteRoute>().groupId, onBack = { nav.popBackStack() }) }
        composable<JoinCodeRoute> {
            JoinCodeScreen(onBack = { nav.popBackStack() }, onRequest = { rid -> nav.navigate(JoinReviewRoute(rid)) { popUpTo(MainRoute) } })
        }
        composable<JoinReviewRoute> {
            JoinReviewScreen(onBack = { nav.popBackStack() }, onJoined = { gid -> nav.navigate(GroupRoute(gid)) { popUpTo(MainRoute) } })
        }
        composable<ProposeRoute> { ProposeScreen(onClose = { nav.popBackStack() }) }
        composable<ProposalRoute> {
            ProposalScreen(onBack = { nav.popBackStack() }, onRepropose = { gid, replaces, start, end ->
                nav.navigate(ProposeRoute(gid, start.toEpochMilli(), end.toEpochMilli(), replaces)) { popUpTo<GroupRoute> { inclusive = false } }
            })
        }
        composable<AdminActionRoute> { AdminActionScreen(onBack = { nav.popBackStack() }) }
        composable<NewAdminActionRoute> {
            NewAdminActionScreen(onBack = { nav.popBackStack() }, onCreated = { id -> nav.navigate(AdminActionRoute(id)) { popUpTo<GroupSettingsRoute>() } })
        }
        composable<SetupHealthRoute> { SetupHealthScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun MainTabs(
    onOpenGroup: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onJoin: () -> Unit,
    onOpenJoin: (String) -> Unit,
    onOpenHealth: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Synced Wallpapers", "Activity", "Settings")
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(titles[tab]) }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Outlined.Home, null) }, label = { Text("Home") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Outlined.Inbox, null) }, label = { Text("Activity") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Outlined.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        androidx.compose.foundation.layout.Box(m) {
            when (tab) {
                0 -> HomeTab(onOpenGroup, onCreateGroup, onJoin, onOpenJoin, onOpenHealth)
                1 -> ActivityTab(onOpenGroup)
                else -> SettingsTab(onOpenHealth)
            }
        }
    }
}
