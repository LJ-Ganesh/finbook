package com.syncedwallpapers.app.ui.screens.onboarding

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncedwallpapers.app.data.local.SettingsStore
import com.syncedwallpapers.app.data.repo.AuthRepository
import com.syncedwallpapers.app.data.repo.ProfileRepository
import com.syncedwallpapers.app.data.repo.SignInResult
import com.syncedwallpapers.app.delivery.HomeWallpaperStore
import com.syncedwallpapers.app.ui.components.ErrorBanner
import com.syncedwallpapers.app.ui.components.InfoBanner
import com.syncedwallpapers.app.ui.components.PrimaryButton
import com.syncedwallpapers.app.ui.runAction
import com.syncedwallpapers.app.ui.screens.settings.HealthList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Step { CONCEPT, SIGN_IN, NAME, HOME_WALLPAPER, HEALTH }

data class OnboardingState(
    val step: Step = Step.CONCEPT,
    val busy: Boolean = false,
    val error: String? = null,
    val name: String = "",
    val phone: String = "",
    val homeChosen: Boolean? = null, // true = picked, false = system default
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsStore,
    private val homeWallpaper: HomeWallpaperStore,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state = _state.asStateFlow()

    /** Resume where the user is: signed in but not finished → name step; finished but signed out → sign in only. */
    fun start(signedIn: Boolean, onboardingDone: Boolean) {
        val s = _state.value
        if (s.step != Step.CONCEPT) return
        when {
            signedIn -> loadProfileThen(Step.NAME)
            onboardingDone -> _state.update { it.copy(step = Step.SIGN_IN) }
        }
    }

    fun next() = _state.update { it.copy(step = Step.entries[(it.step.ordinal + 1).coerceAtMost(Step.entries.lastIndex)], error = null) }
    fun back() = _state.update { it.copy(step = Step.entries[(it.step.ordinal - 1).coerceAtLeast(0)], error = null) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun signIn(activity: Activity, onboardingDone: Boolean) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        when (val r = auth.signInWithGoogle(activity)) {
            SignInResult.Success -> if (onboardingDone) _state.update { it.copy(busy = false) } else loadProfileThen(Step.NAME)
            SignInResult.Cancelled -> _state.update { it.copy(busy = false) }
            is SignInResult.Failed -> _state.update { it.copy(busy = false, error = r.error.userMessage) }
        }
    }

    private fun loadProfileThen(step: Step) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val me = runCatching { profiles.me() }.getOrNull()
        _state.update { it.copy(busy = false, step = step, name = me?.name ?: it.name, phone = me?.phone ?: "") }
    }

    fun setName(v: String) = _state.update { it.copy(name = v.take(60)) }
    fun setPhone(v: String) = _state.update { it.copy(phone = v.take(16)) }

    fun saveName() = viewModelScope.launch {
        val s = _state.value
        if (s.name.isBlank()) { _state.update { it.copy(error = "Please enter your name.") }; return@launch }
        if (s.phone.isNotBlank() && !ProfileRepository.isValidPhone(s.phone)) {
            _state.update { it.copy(error = "Enter the phone number with country code, e.g. +91 98123 45678.") }; return@launch
        }
        _state.update { it.copy(busy = true) }
        val err = runAction { profiles.updateName(s.name); profiles.updatePhone(s.phone.ifBlank { null }) }
        _state.update { it.copy(busy = false, error = err) }
        if (err == null) next()
    }

    fun pickHome(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val err = runAction { homeWallpaper.set(uri) }
        _state.update { it.copy(busy = false, error = err, homeChosen = if (err == null) true else it.homeChosen) }
    }

    fun useSystemDefault() = viewModelScope.launch {
        homeWallpaper.useSystemDefault()
        _state.update { it.copy(homeChosen = false) }
    }

    fun finish() = viewModelScope.launch { settings.setOnboardingDone(true) }
}

@Composable
fun OnboardingScreen(signedIn: Boolean, onboardingDone: Boolean, vm: OnboardingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val activity = androidx.activity.compose.LocalActivity.current
    LaunchedEffect(signedIn, onboardingDone) { vm.start(signedIn, onboardingDone) }
    // Signed in from the sign-in-only step (returning user): onboarding is already done.
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        if (!onboardingDone) {
            LinearProgressIndicator(progress = { (state.step.ordinal + 1) / Step.entries.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
        state.error?.let { ErrorBanner(it, onDismiss = vm::dismissError); Spacer(Modifier.height(12.dp)) }
        AnimatedContent(state.step, label = "step", modifier = Modifier.weight(1f)) { step ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (step) {
                    Step.CONCEPT -> Concept(onNext = vm::next)
                    Step.SIGN_IN -> SignIn(state.busy) { activity?.let { vm.signIn(it, onboardingDone) } }
                    Step.NAME -> NameStep(state, vm::setName, vm::setPhone, vm::saveName)
                    Step.HOME_WALLPAPER -> HomeWallpaperStep(state, vm::pickHome, vm::useSystemDefault, vm::next)
                    Step.HEALTH -> {
                        Text("Setup health", style = MaterialTheme.typography.headlineSmall)
                        Text("Wallpapers change in the background at the right time. These settings make that reliable.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HealthList()
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton("Done", onClick = vm::finish)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bullet(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.padding(start = 12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Concept(onNext: () -> Unit) {
    BrandHeader()
    Text("One photo, everyone's phone", style = MaterialTheme.typography.headlineSmall)
    Text("Small trusted groups propose a photo and a time. If everyone approves, it becomes the wallpaper on every member's phone for that window, then your own wallpaper comes back.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Bullet(Icons.Outlined.Groups, "Everyone must agree", "One “no” is enough, and nobody sees who said it.")
    Bullet(Icons.Outlined.CheckCircle, "You stay in control", "Skip any wallpaper on your phone at any time, or choose “Tap to set”.")
    Bullet(Icons.Outlined.Lock, "Photos stay private", "No gallery, no downloads, no history. Photos are deleted after the window.")
    Bullet(Icons.Outlined.ScreenshotMonitor, "An honest limitation", "Anyone can screenshot their own home screen.")
    Bullet(Icons.Outlined.Palette, "Android 12 and newer", "Your phone may change its system colours to match the wallpaper while it's live.")
    Spacer(Modifier.height(8.dp))
    PrimaryButton("Get started", onClick = onNext)
}

@Composable
private fun SignIn(busy: Boolean, onSignIn: () -> Unit) {
    BrandHeader()
    Text("Sign in", style = MaterialTheme.typography.headlineSmall)
    Text("Your Google account is your identity. Group members see your name and email; nobody else does.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    PrimaryButton("Continue with Google", onClick = onSignIn, busy = busy)
}

@Composable
private fun NameStep(state: OnboardingState, onName: (String) -> Unit, onPhone: (String) -> Unit, onSave: () -> Unit) {
    Text("What should your groups call you?", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(state.name, onName, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(state.phone, onPhone, label = { Text("Phone number (optional)") }, singleLine = true,
        supportingText = { Text("Only members of your groups can see it.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
    PrimaryButton("Continue", onClick = onSave, busy = state.busy, enabled = state.name.isNotBlank())
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HomeWallpaperStep(state: OnboardingState, onPick: (Uri) -> Unit, onDefault: () -> Unit, onNext: () -> Unit) {
    // Android Photo Picker: no storage permission needed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onPick(uri) }
    Text("Your own wallpaper", style = MaterialTheme.typography.headlineSmall)
    Text("When a group wallpaper ends, we put this one back. It's stored encrypted on this phone only.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Icon(Icons.Outlined.Wallpaper, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
    PrimaryButton(if (state.homeChosen == true) "Choose a different photo" else "Choose a photo", busy = state.busy,
        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
    OutlinedButton(onClick = onDefault, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Skip: use the system default") }
    when (state.homeChosen) {
        true -> InfoBanner("Saved. You can change it later in Settings.")
        false -> InfoBanner("When a group wallpaper ends, your phone will go back to the system default wallpaper, not your current one. You can choose a photo later in Settings.")
        null -> {}
    }
    if (state.homeChosen != null) PrimaryButton("Continue", onClick = onNext)
    else TextButton(onClick = {}, enabled = false) { Text("Choose one option to continue") }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.syncedwallpapers.app.R.drawable.ic_livrun_mark), contentDescription = null,
            modifier = Modifier.padding(end = 10.dp))
        Text("Livrun", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
