package com.myappvpn.service

import android.content.Context
import android.util.Log
import com.myappvpn.util.BinaryInstaller
import com.myappvpn.util.NativeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class Tun2SocksManager(private val context: Context) {

    companion object {
        private const val TAG = "Tun2SocksManager"
        private const val STARTUP_WAIT_MS = 500L
    }

    private var pid: Int = -1

    val isRunning: Boolean get() = pid > 0 && NativeHelper.isProcessAlive(pid)

    /**
     * Start tun2socks via native fork()+exec() so the TUN fd is inherited correctly.
     * Java's ProcessBuilder closes all non-stdio fds before exec; native fork() does not.
     */
    suspend fun start(
        tunFd: Int,
        socksPort: Int = 10808,
        tunAddr: String = "10.0.0.2",
        tunGw: String = "10.0.0.1",
        dnsPrimary: String = "1.1.1.1"
    ): Boolean = withContext(Dispatchers.IO) {
        stop()

        try {
            val tun2socksBin = BinaryInstaller.getTun2SocksBinaryPath(context)

            pid = NativeHelper.startTun2Socks(tun2socksBin, tunFd, socksPort)
            if (pid <= 0) {
                Log.e(TAG, "NativeHelper.startTun2Socks failed (returned $pid)")
                return@withContext false
            }

            delay(STARTUP_WAIT_MS)

            val alive = NativeHelper.isProcessAlive(pid)
            if (alive) {
                Log.i(TAG, "tun2socks started (pid=$pid) fd=$tunFd -> socks5 :$socksPort")
            } else {
                Log.e(TAG, "tun2socks died immediately (pid=$pid)")
                pid = -1
            }
            alive
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tun2socks: ${e.message}")
            false
        }
    }

    fun stop() {
        if (pid > 0) {
            NativeHelper.killProcess(pid)
            pid = -1
        }
        Log.i(TAG, "tun2socks stopped")
    }
}
