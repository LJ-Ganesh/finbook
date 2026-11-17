package com.syncedwallpapers.app.data.repo

import com.syncedwallpapers.app.data.remote.Api
import com.syncedwallpapers.app.data.remote.ProfileDto
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(private val api: Api) {

    suspend fun me(): ProfileDto {
        val uid = api.uid()
        return api.client.from("profiles").select { filter { eq("id", uid) } }.decodeSingle<ProfileDto>()
    }

    /** Profiles of people the user shares a group with (RLS returns nothing else). */
    suspend fun profiles(ids: Collection<String>): Map<String, ProfileDto> {
        if (ids.isEmpty()) return emptyMap()
        return api.client.from("profiles")
            .select { filter { filter("id", FilterOperator.IN, "(${ids.joinToString(",")})") } }
            .decodeList<ProfileDto>().associateBy { it.id }
    }

    suspend fun updateName(name: String) {
        val clean = name.trim().take(60)
        require(clean.isNotEmpty()) { "Name required" }
        api.client.from("profiles").update({ set("name", clean) }) { filter { eq("id", api.uid()) } }
    }

    /** Optional phone number in E.164; visible only to members of your groups. Empty clears it. */
    suspend fun updatePhone(phone: String?) {
        val value = phone?.filter { it == '+' || it.isDigit() }?.ifBlank { null }
        api.client.from("profiles").update({ set("phone_optional", value) }) { filter { eq("id", api.uid()) } }
    }

    suspend fun registerDeviceToken(token: String) = api.rpcUnit("register_device_token") { put("p_token", kotlinx.serialization.json.JsonPrimitive(token)) }

    suspend fun removeMyDeviceTokens() {
        val uid = api.uidOrNull() ?: return
        api.client.from("device_tokens").delete { filter { eq("user_id", uid) } }
    }

    companion object {
        private val E164 = Regex("^\\+[1-9][0-9]{6,14}$")
        fun isValidPhone(s: String): Boolean = E164.matches(s.filter { it == '+' || it.isDigit() })
    }
}
