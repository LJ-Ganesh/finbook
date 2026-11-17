package com.syncedwallpapers.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

data class Settings(
    val onboardingDone: Boolean = false,
    /** Encrypted file holding the user's chosen restore wallpaper, or null = system default. */
    val homeWallpaperFile: String? = null,
    val homeWallpaperChoiceMade: Boolean = false,
    val quietLiveNotice: Boolean = true,
    val notifyProposals: Boolean = true,
    val notifyReminders: Boolean = true,
    val notifyResults: Boolean = true,
    val notifyMembership: Boolean = true,
    val notifyAdmin: Boolean = true,
    val serverOffsetMillis: Long = 0,
    val lastFcmToken: String? = null,
)

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val homeFile = stringPreferencesKey("home_wallpaper_file")
        val homeChoice = booleanPreferencesKey("home_wallpaper_choice")
        val quietLive = booleanPreferencesKey("quiet_live")
        val nProposals = booleanPreferencesKey("n_proposals")
        val nReminders = booleanPreferencesKey("n_reminders")
        val nResults = booleanPreferencesKey("n_results")
        val nMembership = booleanPreferencesKey("n_membership")
        val nAdmin = booleanPreferencesKey("n_admin")
        val offset = longPreferencesKey("server_offset")
        val fcm = stringPreferencesKey("fcm_token")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            onboardingDone = p[K.onboarding] ?: false,
            homeWallpaperFile = p[K.homeFile],
            homeWallpaperChoiceMade = p[K.homeChoice] ?: false,
            quietLiveNotice = p[K.quietLive] ?: true,
            notifyProposals = p[K.nProposals] ?: true,
            notifyReminders = p[K.nReminders] ?: true,
            notifyResults = p[K.nResults] ?: true,
            notifyMembership = p[K.nMembership] ?: true,
            notifyAdmin = p[K.nAdmin] ?: true,
            serverOffsetMillis = p[K.offset] ?: 0,
            lastFcmToken = p[K.fcm],
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[K.onboarding] = done }
    suspend fun setHomeWallpaper(file: String?) = context.dataStore.edit {
        if (file == null) it.remove(K.homeFile) else it[K.homeFile] = file
        it[K.homeChoice] = true
    }
    suspend fun setServerOffset(millis: Long) = context.dataStore.edit { it[K.offset] = millis }
    suspend fun setFcmToken(token: String?) = context.dataStore.edit { if (token == null) it.remove(K.fcm) else it[K.fcm] = token }

    enum class Pref { QUIET_LIVE, PROPOSALS, REMINDERS, RESULTS, MEMBERSHIP, ADMIN }

    suspend fun setPref(pref: Pref, value: Boolean) = context.dataStore.edit {
        val key = when (pref) {
            Pref.QUIET_LIVE -> K.quietLive
            Pref.PROPOSALS -> K.nProposals
            Pref.REMINDERS -> K.nReminders
            Pref.RESULTS -> K.nResults
            Pref.MEMBERSHIP -> K.nMembership
            Pref.ADMIN -> K.nAdmin
        }
        it[key] = value
    }

    /** On sign-out/account deletion: keep nothing tied to the previous account. */
    suspend fun clearAccountData() = context.dataStore.edit {
        it.remove(K.fcm); it.remove(K.offset)
    }
}
