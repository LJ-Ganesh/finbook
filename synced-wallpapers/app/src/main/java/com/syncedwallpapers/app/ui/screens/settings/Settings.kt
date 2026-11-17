package com.syncedwallpapers.app.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.syncedwallpapers.app.BuildConfig
import com.syncedwallpapers.app.data.local.Settings
import com.syncedwallpapers.app.data.local.SettingsStore
import com.syncedwallpapers.app.data.remote.ProfileDto
import com.syncedwallpapers.app.data.repo.AuthRepository
import com.syncedwallpapers.app.data.repo.ProfileRepository
import com.syncedwallpapers.app.delivery.DeliveryEngine
import com.syncedwallpapers.app.delivery.HomeWallpaperStore
import com.syncedwallpapers.app.health.HealthId
import com.syncedwallpapers.app.health.HealthItem
import com.syncedwallpapers.app.health.SetupHealthChecker
import com.syncedwallpapers.app.push.PushTokens
import com.syncedwallpapers.app.ui.components.ClickableRow
import com.syncedwallpapers.app.ui.components.ConfirmDialog
import com.syncedwallpapers.app.ui.components.ErrorBanner
import com.syncedwallpapers.app.ui.components.HealthDot
import com.syncedwallpapers.app.ui.components.SectionTitle
import com.syncedwallpapers.app.ui.components.SwCard
import com.syncedwallpapers.app.ui.runAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------- Setup health

@HiltViewModel
class HealthViewModel @Inject constructor(val checker: SetupHealthChecker) : ViewModel() {
    private val _items = MutableStateFlow(checker.check())
    val items = _items.asStateFlow()
    fun refresh() { _items.value = checker.check() }
}

/** The checklist (used in onboarding and Settings). Re-checks whenever the app resumes. */
@Composable
fun HealthList(vm: HealthViewModel = hiltViewModel()) {
    val items by vm.items.collectAsState()
    val context = LocalContext.current
    var guideOpen by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    fun open(item: HealthItem) {
        if (item.id == HealthId.NOTIFICATIONS && Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        if (item.id == HealthId.OEM) { guideOpen = true; return }
        val intent = vm.checker.intentFor(item.id) ?: return
        runCatching { context.startActivity(intent) }.onFailure { runCatching { context.startActivity(vm.checker.appDetails()) } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            SwCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HealthDot(item.status)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item.actionLabel?.let { TextButton(onClick = { open(item) }) { Text(it) } }
                }
            }
        }
    }

    if (guideOpen) {
        val guide = vm.checker.oemGuide()
        AlertDialog(
            onDismissRequest = { guideOpen = false },
            title = { Text(guide?.brand ?: "Background settings") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { guide?.steps?.forEach { Text("• $it") } } },
            confirmButton = {
                Button(onClick = {
                    guideOpen = false
                    val intent = vm.checker.intentFor(HealthId.OEM)
                    runCatching { context.startActivity(intent) }.onFailure { runCatching { context.startActivity(vm.checker.appDetails()) } }
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { guideOpen = false }) { Text("Close") } },
        )
    }
}

@Composable
fun SetupHealthScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Setup health") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Green is good. Amber means wallpapers may be a few minutes late. Red means they may be missed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HealthList()
        }
    }
}

// ---------------------------------------------------------------- Settings tab

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val store: SettingsStore,
    private val homeWallpaper: HomeWallpaperStore,
    private val engine: DeliveryEngine,
    private val push: PushTokens,
) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    private val _profile = MutableStateFlow<ProfileDto?>(null)
    val profile = _profile.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val email: String? get() = auth.currentEmail()

    fun load() = viewModelScope.launch { _profile.value = runCatching { profiles.me() }.getOrNull() }
    fun dismiss() { _message.value = null }

    fun setPref(p: SettingsStore.Pref, v: Boolean) = viewModelScope.launch { store.setPref(p, v) }

    fun saveName(name: String) = act { profiles.updateName(name); load() }
    fun savePhone(phone: String) = act {
        if (phone.isNotBlank() && !ProfileRepository.isValidPhone(phone)) error("Enter the number with country code, e.g. +91 98123 45678.")
        profiles.updatePhone(phone.ifBlank { null }); load()
    }
    fun pickHome(uri: android.net.Uri) = act { homeWallpaper.set(uri); _message.value = "Home wallpaper saved." }
    fun useDefault() = act { homeWallpaper.useSystemDefault(); _message.value = "Restores will use the system default wallpaper." }

    /** Sign out: restore any live group wallpaper, wipe local images, drop the push token. */
    fun signOut() = act {
        push.unregister()
        engine.wipe()
        store.clearAccountData()
        auth.signOut()
    }

    fun deleteAccount() = act {
        engine.wipe()
        auth.deleteAccount()
        store.clearAccountData()
        store.setOnboardingDone(false)
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        runAction(block)?.let { _message.value = it }
        _busy.value = false
    }
}

@Composable
fun SettingsTab(onOpenHealth: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val profile by vm.profile.collectAsState()
    val message by vm.message.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var editPhone by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.pickHome(uri) }
    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message?.let { ErrorBanner(it, onDismiss = vm::dismiss) }

        SectionTitle("Home wallpaper")
        SwCard {
            Text(if (s.homeWallpaperFile != null) "Your photo is restored when a group wallpaper ends."
                 else "Restores use the system default wallpaper.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose photo") }
                if (s.homeWallpaperFile != null) TextButton(onClick = vm::useDefault) { Text("Use system default") }
            }
        }

        SectionTitle("Reliability")
        SwCard(onClick = onOpenHealth) {
            Text("Setup health", style = MaterialTheme.typography.titleSmall)
            Text("Notifications, exact alarms, battery and background settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Notifications")
        SwCard {
            PrefRow("New proposals", s.notifyProposals) { vm.setPref(SettingsStore.Pref.PROPOSALS, it) }
            PrefRow("Vote reminders", s.notifyReminders) { vm.setPref(SettingsStore.Pref.REMINDERS, it) }
            PrefRow("Approved / not approved", s.notifyResults) { vm.setPref(SettingsStore.Pref.RESULTS, it) }
            PrefRow("Members, joins and cancellations", s.notifyMembership) { vm.setPref(SettingsStore.Pref.MEMBERSHIP, it) }
            PrefRow("Admin requests", s.notifyAdmin) { vm.setPref(SettingsStore.Pref.ADMIN, it) }
            PrefRow("Quiet “Now live” notice (auto mode)", s.quietLiveNotice) { vm.setPref(SettingsStore.Pref.QUIET_LIVE, it) }
            Text("“Set wallpaper” notifications in tap mode are always shown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Account")
        SwCard {
            ClickableRow("Google account", vm.email ?: "—", onClick = {})
            HorizontalDivider()
            ClickableRow("Name", profile?.name ?: "—", onClick = { editName = true })
            HorizontalDivider()
            ClickableRow("Phone (optional)", profile?.phone ?: "Not set · visible only to your groups", onClick = { editPhone = true })
        }
        Text("To leave a group, open the group and go to its settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
        Text("Version ${BuildConfig.VERSION_NAME} · backend: ${com.syncedwallpapers.app.core.AppConfig.supabaseTarget}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
    }

    if (confirmSignOut) ConfirmDialog("Sign out?", "Any group wallpaper on this phone is removed and your own wallpaper is restored.", "Sign out",
        onConfirm = { confirmSignOut = false; vm.signOut() }, onDismiss = { confirmSignOut = false })
    if (confirmDelete) ConfirmDialog("Delete your account?",
        "You leave every group (your proposals and schedules are cancelled for everyone; if you're an admin, it passes on), and your data is deleted. This can't be undone.",
        "Delete account", onConfirm = { confirmDelete = false; vm.deleteAccount() }, onDismiss = { confirmDelete = false })
    if (editName) EditDialog("Display name", profile?.name ?: "", KeyboardType.Text, { editName = false }) { vm.saveName(it); editName = false }
    if (editPhone) EditDialog("Phone number", profile?.phone ?: "", KeyboardType.Phone, { editPhone = false }) { vm.savePhone(it); editPhone = false }
}

@Composable
private fun PrefRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun EditDialog(title: String, initial: String, type: KeyboardType, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it.take(60) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type)) },
        confirmButton = { Button(onClick = { onSave(value.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
