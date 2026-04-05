package com.myappvpn

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import com.myappvpn.worker.RefreshServersWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class MyApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    // WorkManager uses this instead of the default factory so @HiltWorker DI works.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduleServerRefresh()
    }

    /**
     * Schedules an hourly background refresh of the VLESS server list.
     * KEEP policy: if work is already scheduled, leave it as-is (don't reset the timer).
     * Requires network; uses retry backoff on failure.
     */
    private fun scheduleServerRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<RefreshServersWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RefreshServersWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
