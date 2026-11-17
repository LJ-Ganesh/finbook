package com.syncedwallpapers.app.delivery

import android.net.Uri
import com.syncedwallpapers.app.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The user's own "home wallpaper", restored when a group wallpaper ends. Stored encrypted. */
@Singleton
class HomeWallpaperStore @Inject constructor(
    private val images: ImageProcessor,
    private val store: SecureStore,
    private val settings: SettingsStore,
) {
    suspend fun set(uri: Uri) = withContext(Dispatchers.Default) {
        val src = images.decode(uri, ImageProcessor.UPLOAD_LONG_EDGE)
        val png = try {
            val rendered = images.renderForScreen(src, images.screenSize(), 0.5f, 0.5f, null, 0)
            try { images.encodePng(rendered) } finally { rendered.recycle() }
        } finally { src.recycle() }
        val old = settings.current().homeWallpaperFile
        settings.setHomeWallpaper(store.put(png, "home"))
        store.delete(old)
    }

    /** Use the system default wallpaper (WallpaperManager.clear) for restores. */
    suspend fun useSystemDefault() {
        val old = settings.current().homeWallpaperFile
        settings.setHomeWallpaper(null)
        store.delete(old)
    }
}
