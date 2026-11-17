package com.syncedwallpapers.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncedwallpapers.app.data.local.Settings
import com.syncedwallpapers.app.data.local.SettingsStore
import com.syncedwallpapers.app.data.repo.AuthRepository
import com.syncedwallpapers.app.data.repo.AuthState
import com.syncedwallpapers.app.data.repo.RealtimeRefresher
import com.syncedwallpapers.app.delivery.DeliveryWork
import com.syncedwallpapers.app.push.PushTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    auth: AuthRepository,
    settingsStore: SettingsStore,
    private val work: DeliveryWork,
    private val push: PushTokens,
    private val realtime: RealtimeRefresher,
) : ViewModel() {
    val authState: StateFlow<AuthState> = auth.state.stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)
    val settings: StateFlow<Settings?> = settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** An invite code from a deep link, consumed by the join screen. */
    private val _pendingInvite = MutableStateFlow<String?>(null)
    val pendingInvite = _pendingInvite.asStateFlow()

    init {
        viewModelScope.launch {
            authState.collect { s ->
                if (s is AuthState.SignedIn) {
                    push.register()
                    realtime.start()
                } else if (s is AuthState.SignedOut) {
                    realtime.stop()
                }
                work.reconcileNow()
            }
        }
    }

    /** App came to the foreground: reconcile (spec: on every app open). */
    fun onForeground() = work.reconcileNow()

    fun onInviteLink(code: String?) { if (!code.isNullOrBlank()) _pendingInvite.value = code }
    fun consumeInvite() { _pendingInvite.value = null }
}
