package com.myappvpn.util

object NativeHelper {
    init {
        System.loadLibrary("tun_helper")
    }

    /** Fork + exec tun2socks via native code. Returns child PID or -1 on error. */
    @JvmStatic external fun startTun2Socks(
        binary: String, tunFd: Int, socksPort: Int,
        proxyUser: String? = null, proxyPass: String? = null
    ): Int

    /**
     * SIGTERM, wait up to ~1s, then SIGKILL; always reaps the child. BLOCKING (up to ~1s+) —
     * don't call on the main thread.
     */
    @JvmStatic external fun killProcess(pid: Int)

    /**
     * Returns true if the process is still running (zombies → false). Reaps an exited child,
     * so after a false result the pid must be forgotten (it may be reused).
     */
    @JvmStatic external fun isProcessAlive(pid: Int): Boolean
}
