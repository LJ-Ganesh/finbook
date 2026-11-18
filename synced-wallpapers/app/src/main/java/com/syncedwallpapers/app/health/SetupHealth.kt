package com.syncedwallpapers.app.health

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.syncedwallpapers.app.delivery.AlarmScheduler
import com.syncedwallpapers.app.delivery.WallpaperApplier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class Health { GREEN, AMBER, RED }

enum class HealthId { NOTIFICATIONS, EXACT_ALARMS, BATTERY, OEM, WALLPAPER }

data class HealthItem(
    val id: HealthId,
    val title: String,
    val status: Health,
    val detail: String,
    val actionLabel: String? = null,
)

data class OemGuide(val brand: String, val steps: List<String>, val intents: List<Intent>)

@Singleton
class SetupHealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarms: AlarmScheduler,
    private val applier: WallpaperApplier,
) {
    fun check(): List<HealthItem> {
        val items = mutableListOf<HealthItem>()
        val notif = NotificationManagerCompat.from(context).areNotificationsEnabled()
        items += HealthItem(HealthId.NOTIFICATIONS, "Notifications",
            if (notif) Health.GREEN else Health.RED,
            if (notif) "You'll hear about proposals, votes and tap-to-set." else "Off: you'll miss votes, and tap-to-set can't work.",
            if (notif) null else "Turn on")

        val exact = alarms.canScheduleExact()
        items += HealthItem(HealthId.EXACT_ALARMS, "Exact alarms",
            if (exact) Health.GREEN else Health.AMBER,
            if (exact) "Wallpapers change on time." else "Not allowed: wallpapers may change a few minutes late.",
            if (exact) null else "Allow")

        val pm = context.getSystemService(PowerManager::class.java)
        val unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
        items += HealthItem(HealthId.BATTERY, "Battery optimisation",
            if (unrestricted) Health.GREEN else Health.AMBER,
            if (unrestricted) "The app may run in the background." else "Optimised: Android may delay background work.",
            if (unrestricted) null else "Exempt")

        oemGuide()?.let { guide ->
            items += HealthItem(HealthId.OEM, "${guide.brand} background settings", Health.AMBER,
                "${guide.brand} phones can stop background apps. Check these settings once.", "Open")
        }

        val wp = applier.isAllowed
        items += HealthItem(HealthId.WALLPAPER, "Set wallpaper",
            if (wp) Health.GREEN else Health.RED,
            if (wp) "Allowed (no prompt needed)." else "This device or its policy doesn't allow apps to set wallpapers.")
        return items
    }

    fun worst(items: List<HealthItem> = check()): Health = items.maxOfOrNull { it.status } ?: Health.GREEN

    @SuppressLint("BatteryLife") // Sideloaded: the direct exemption request is acceptable here.
    fun intentFor(id: HealthId): Intent? = when (id) {
        HealthId.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        HealthId.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= 31)
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")) else null
        HealthId.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        HealthId.OEM -> oemGuide()?.intents?.firstOrNull { it.resolveActivity(context.packageManager) != null } ?: appDetails()
        HealthId.WALLPAPER -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetails(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun oemGuide(manufacturer: String = Build.MANUFACTURER): OemGuide? = OemGuides.forManufacturer(manufacturer, context.packageName)
}

/**
 * OEM-specific "autostart / background activity" screens. These are private activities
 * that change between OS versions, so each guide lists several candidates (the first
 * one that resolves is opened) and always falls back to the app's settings page.
 */
object OemGuides {
    private fun c(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    fun forManufacturer(manufacturer: String, packageName: String): OemGuide? = when (manufacturer.lowercase()) {
        "xiaomi", "redmi", "poco" -> OemGuide("Xiaomi / Redmi / POCO", listOf(
            "Settings → Apps → Manage apps → Livrun → Autostart: on",
            "Battery saver → No restrictions",
            "Recent apps: long-press the app and tap the lock icon"),
            listOf(c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                c("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", packageName).putExtra("package_label", "Livrun")))
        "samsung" -> OemGuide("Samsung", listOf(
            "Settings → Apps → Livrun → Battery → Unrestricted",
            "Settings → Battery → Background usage limits: make sure the app isn't in Sleeping or Deep sleeping apps"),
            listOf(c("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                c("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")))
        "oppo", "realme" -> OemGuide("Oppo / Realme", listOf(
            "Settings → Apps → Auto launch (or Startup manager): allow Livrun",
            "Settings → Battery → App battery management → Livrun → Allow background activity"),
            listOf(c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                c("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")))
        "vivo", "iqoo" -> OemGuide("Vivo", listOf(
            "i Manager → App manager → Autostart manager: allow Livrun",
            "Settings → Battery → Background power consumption: allow high background usage"),
            listOf(c("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")))
        "oneplus" -> OemGuide("OnePlus", listOf(
            "Settings → Battery → Battery optimisation → Livrun → Don't optimise",
            "Settings → Apps → Livrun → Battery → Allow background activity"),
            listOf(c("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")))
        else -> null
    }
}
