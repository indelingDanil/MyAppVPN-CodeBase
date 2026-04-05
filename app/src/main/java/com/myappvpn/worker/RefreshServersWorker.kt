package com.myappvpn.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.myappvpn.domain.usecase.FetchServersUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RefreshServersWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val fetchServersUseCase: FetchServersUseCase,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val result = fetchServersUseCase()
        return if (result.isSuccess) {
            Log.i(TAG, "Refreshed ${result.getOrDefault(0)} servers")
            Result.success()
        } else {
            val err = result.exceptionOrNull()
            Log.w(TAG, "Refresh failed (attempt $runAttemptCount): ${err?.message}")
            Result.retry()
        }
    }

    companion object {
        const val TAG = "RefreshServersWorker"
        const val WORK_NAME = "refresh_servers_hourly"
    }
}
