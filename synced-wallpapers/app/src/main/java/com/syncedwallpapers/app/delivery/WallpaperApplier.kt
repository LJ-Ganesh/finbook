package com.syncedwallpapers.app.delivery

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** All WallpaperManager calls. Needs only SET_WALLPAPER (a normal permission, no prompt). */
@Singleton
class WallpaperApplier @Inject constructor(@ApplicationContext context: Context) {
    private val wm = WallpaperManager.getInstance(context)

    val isAllowed: Boolean get() = wm.isWallpaperSupported && wm.isSetWallpaperAllowed

    /** allowBackup = false keeps group wallpapers out of device backups. Returns the new wallpaper id. */
    fun setHome(bitmap: Bitmap): Int = wm.setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM)

    fun setLock(bitmap: Bitmap): Int = wm.setBitmap(bitmap, null, false, WallpaperManager.FLAG_LOCK)

    fun currentHomeId(): Int = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)

    fun currentLockId(): Int = wm.getWallpaperId(WallpaperManager.FLAG_LOCK)

    /** Restores the home screen to the user's chosen wallpaper, or the system default. */
    fun restoreHome(homeWallpaper: Bitmap?) {
        if (homeWallpaper != null) wm.setBitmap(homeWallpaper, null, false, WallpaperManager.FLAG_SYSTEM)
        else wm.clear(WallpaperManager.FLAG_SYSTEM)
    }

    /**
     * Removes our separate lock-screen wallpaper so the lock screen follows the home
     * wallpaper again. (Apps can't read the previous lock wallpaper without extra
     * permissions, so a separate one the user had before can't be brought back.)
     */
    fun restoreLock() = wm.clear(WallpaperManager.FLAG_LOCK)
}
