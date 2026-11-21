package com.myappvpn.service

import android.content.Context
import android.util.Log
import com.myappvpn.util.BinaryInstaller
import com.myappvpn.util.NativeHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class Tun2SocksManager(private val context: Context) {

    companion object {
        private const val TAG = "Tun2SocksManager"
        private const val STARTUP_WAIT_MS = 500L
    }

    @Volatile private var pid: Int = -1

    /**
     * isProcessAlive reaps an exited child, so once it reports dead the pid is forgotten —
     * otherwise a later stop() could signal an unrelated process that reused the pid.
     */
    val isRunning: Boolean
        get() {
            val p = pid
            if (p <= 0) return false
            if (NativeHelper.isProcessAlive(p)) return true
            if (pid == p) pid = -1
            return false
        }

    /**
     * Start tun2socks via native fork()+exec() so the TUN fd is inherited correctly.
     * Java's ProcessBuilder closes all non-stdio fds before exec; native fork() does not.
     * tun2socks reads the TUN via fd:// — the TUN addresses (172.19.0.1/30, fdfe:dcba:9876::1/126,
     * set in MyVpnService.buildTun) aren't passed to it. [socksPort] is the session's random port.
     * [ownerSocket]: name of the running [OwnerUidServer] → tun2socks checks each flow's socket
     * owner (drops flows from apps outside the VPN that bound to tun0); null = no check.
     */
    suspend fun start(
        tunFd: Int,
        socksPort: Int,
        proxyUser: String? = null,
        proxyPass: String? = null,
        ownerSocket: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        stop()

        try {
            val tun2socksBin = BinaryInstaller.getTun2SocksBinaryPath(context)

            pid = NativeHelper.startTun2Socks(tun2socksBin, tunFd, socksPort, proxyUser, proxyPass, ownerSocket)
            if (pid <= 0) {
                Log.e(TAG, "NativeHelper.startTun2Socks failed (returned $pid)")
                return@withContext false
            }

            delay(STARTUP_WAIT_MS)

            val alive = NativeHelper.isProcessAlive(pid)
            if (alive) {
                Log.i(TAG, "tun2socks started (pid=$pid) fd=$tunFd -> socks5 :$socksPort, owner check ${if (ownerSocket.isNullOrEmpty()) "off" else "on"}")
            } else {
                Log.e(TAG, "tun2socks died immediately (pid=$pid)")
                pid = -1
            }
            alive
        } catch (e: CancellationException) {
            stop()   // don't leave an orphan tun2socks holding the TUN fd
            throw e
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
