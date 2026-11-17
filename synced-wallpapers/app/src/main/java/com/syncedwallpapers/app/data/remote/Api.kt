package com.syncedwallpapers.app.data.remote

import com.syncedwallpapers.app.core.AppConfig
import com.syncedwallpapers.app.core.AppError
import com.syncedwallpapers.app.core.AppException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Thin helper over PostgREST RPC calls. All state changes on the server go through here. */
@Singleton
class Api @Inject constructor(val client: SupabaseClient) {

    fun requireConfigured() {
        if (!AppConfig.isSupabaseConfigured) throw AppException(AppError.NotConfigured)
    }

    fun uidOrNull(): String? = client.auth.currentUserOrNull()?.id

    fun uid(): String = uidOrNull() ?: throw AppException(AppError.SessionExpired)

    suspend inline fun <reified T : Any> rpc(name: String, noinline params: JsonObjectBuilder.() -> Unit = {}): T {
        requireConfigured()
        return client.postgrest.rpc(name, buildJsonObject(params)).decodeAs<T>()
    }

    suspend inline fun <reified T : Any> rpcList(name: String, noinline params: JsonObjectBuilder.() -> Unit = {}): List<T> {
        requireConfigured()
        return client.postgrest.rpc(name, buildJsonObject(params)).decodeList<T>()
    }

    suspend fun rpcUnit(name: String, params: JsonObjectBuilder.() -> Unit = {}) {
        requireConfigured()
        client.postgrest.rpc(name, buildJsonObject(params))
    }

    suspend fun rpcRaw(name: String, params: JsonObject): String {
        requireConfigured()
        return client.postgrest.rpc(name, params).data
    }
}
