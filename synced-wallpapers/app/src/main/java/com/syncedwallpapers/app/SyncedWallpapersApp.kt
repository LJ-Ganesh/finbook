package com.syncedwallpapers.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.syncedwallpapers.app.delivery.DeliveryWork
import com.syncedwallpapers.app.notifications.Channels
import com.syncedwallpapers.app.push.FirebaseInit
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SyncedWallpapersApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    // Lazy: building DeliveryWork touches WorkManager, which needs workerFactory first.
    @Inject lateinit var deliveryWork: dagger.Lazy<DeliveryWork>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Channels.create(this)
        FirebaseInit.init(this)
        deliveryWork.get().schedulePeriodic()
    }
}
