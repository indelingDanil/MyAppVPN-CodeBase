package com.myappvpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED — used for always-on VPN reconnect in future.
 * Currently just logs the boot event.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed received")
            // Future: check if auto-connect is enabled and reconnect
        }
    }
}
