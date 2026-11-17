package com.syncedwallpapers.app.core

import com.syncedwallpapers.app.BuildConfig

/** Build-time configuration from local.properties (see local.properties.example). */
object AppConfig {
    val supabaseUrl: String = BuildConfig.SUPABASE_URL
    val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY
    val supabaseTarget: String = BuildConfig.SUPABASE_TARGET
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    val isSupabaseConfigured: Boolean get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()
    val isGoogleConfigured: Boolean get() = googleWebClientId.isNotBlank()
    val isFirebaseConfigured: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    const val MAX_MEMBERS = 20
    const val IMAGE_BUCKET = "proposal-images"
}
