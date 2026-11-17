package com.syncedwallpapers.app.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.syncedwallpapers.app.BuildConfig
import com.syncedwallpapers.app.core.AppConfig
import com.syncedwallpapers.app.data.local.SettingsStore
import com.syncedwallpapers.app.data.repo.ProfileRepository
import com.syncedwallpapers.app.delivery.DeliveryEngine
import com.syncedwallpapers.app.delivery.DeliveryWork
import com.syncedwallpapers.app.notifications.Notifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Firebase is used for push only, initialised from local.properties (no google-services.json). */
object FirebaseInit {
    fun init(context: Context): Boolean {
        if (!AppConfig.isFirebaseConfigured) return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build())
        return true
    }
}

@Singleton
class PushTokens @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val settings: SettingsStore,
) {
    /** Register this device's token for the signed-in user (on login and on token change). */
    suspend fun register(token: String? = null) {
        if (!FirebaseInit.init(context)) return
        val t = token ?: runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        settings.setFcmToken(t)
        runCatching { profiles.registerDeviceToken(t) }
    }

    suspend fun unregister() {
        runCatching { profiles.removeMyDeviceTokens() }
        settings.setFcmToken(null)
    }
}

/**
 * Data messages only. Every message triggers reconciliation (so a late or duplicated
 * push can never put the device in a wrong state); events that must take effect now
 * (left / removed / deleted / cancelled) are handled immediately as well.
 */
@AndroidEntryPoint
class PushService : FirebaseMessagingService() {
    @Inject lateinit var work: DeliveryWork
    @Inject lateinit var engine: DeliveryEngine
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var tokens: PushTokens
    @Inject lateinit var json: Json

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNewToken(token: String) {
        scope.launch { tokens.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"] ?: return
        val payload = runCatching { json.parseToJsonElement(message.data["payload"] ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val outboxId = message.data["outbox_id"]?.toLongOrNull() ?: System.currentTimeMillis()
        val groupId = payload["group_id"]?.jsonPrimitive?.contentOrNull
        scope.launch {
            if (groupId != null && type in setOf("LEFT_GROUP", "REMOVED_FROM_GROUP", "GROUP_DELETED")) engine.forgetGroup(groupId)
            notifier.showEvent(outboxId, type, payload, settings.current())
        }
        work.reconcileNow()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
