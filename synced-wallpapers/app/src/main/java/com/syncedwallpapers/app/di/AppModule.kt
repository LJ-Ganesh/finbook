package com.syncedwallpapers.app.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.syncedwallpapers.app.core.AppConfig
import com.syncedwallpapers.app.data.local.AppDatabase
import com.syncedwallpapers.app.data.local.ScheduleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Provides @Singleton
    fun supabase(json: Json): SupabaseClient = createSupabaseClient(
        // A placeholder URL keeps an unconfigured build from crashing; every call then
        // fails and the UI says "not configured".
        supabaseUrl = AppConfig.supabaseUrl.ifBlank { "https://not-configured.invalid" },
        supabaseKey = AppConfig.supabaseAnonKey.ifBlank { "not-configured" },
    ) {
        defaultSerializer = KotlinXSerializer(json)
        httpEngine = OkHttp.create()
        install(Auth) {
            alwaysAutoRefresh = true
            autoLoadFromStorage = true
        }
        install(Postgrest)
        install(Storage)
        install(Realtime)
        install(Functions)
    }

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "synced-wallpapers.db").build()

    @Provides
    fun scheduleDao(db: AppDatabase): ScheduleDao = db.schedules()

    @Provides
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}
