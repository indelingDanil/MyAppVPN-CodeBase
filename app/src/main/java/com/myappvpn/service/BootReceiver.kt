package com.myappvpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.domain.ConnectionController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * BOOT_COMPLETED → auto-connect when the user enabled "Подключаться при загрузке" and the VPN
 * permission was already granted (VpnService.prepare == null). Without the permission we can't
 * show the system consent dialog from a receiver, so we just skip.
 *
 * Only BOOT_COMPLETED (not LOCKED_BOOT_COMPLETED): DataStore lives in credential-encrypted
 * storage, unavailable before the first unlock.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var controller: ConnectionController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed received")

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeout(TIMEOUT_MS) {
                    if (!preferences.autoConnect.first()) {
                        Log.d(TAG, "Auto-connect disabled")
                        return@withTimeout
                    }
                    if (VpnService.prepare(appContext) != null) {
                        Log.w(TAG, "Auto-connect skipped: VPN permission not granted")
                        return@withTimeout
                    }
                    when (MyVpnService.vpnState.value) {
                        VpnState.DISCONNECTED, VpnState.ERROR -> {
                            controller.connect()
                            Log.i(TAG, "Auto-connect started")
                        }
                        else -> Log.d(TAG, "Auto-connect skipped: session already active")
                    }
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "Auto-connect timed out / cancelled", e)
            } catch (e: Exception) {
                Log.w(TAG, "Auto-connect failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val TIMEOUT_MS = 20_000L
    }
}
