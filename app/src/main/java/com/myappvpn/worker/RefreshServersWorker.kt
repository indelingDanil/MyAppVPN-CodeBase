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
            Log.w(TAG, "Refresh failed (attempt $runAttemptCount): ${redact(err?.message)}")
            // Cap backoff retries: after MAX_ATTEMPTS give up until the next periodic run.
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val TAG = "RefreshServersWorker"
        const val WORK_NAME = "refresh_servers_hourly"
        private const val MAX_ATTEMPTS = 3

        private val URL_REGEX = Regex("""(?i)\b([a-z][a-z0-9+.-]*)://[^\s/?#]+[^\s]*""")

        /**
         * Strip URLs down to scheme://host (no userinfo / path / query) — custom subscription
         * URLs may carry tokens and must never reach logcat.
         */
        internal fun redact(message: String?): String {
            if (message.isNullOrEmpty()) return "unknown error"
            return URL_REGEX.replace(message) { m ->
                val host = runCatching { java.net.URI(m.value).host }.getOrNull()
                if (host.isNullOrEmpty()) "${m.groupValues[1]}://<redacted>"
                else "${m.groupValues[1]}://$host/…"
            }
        }
    }
}
