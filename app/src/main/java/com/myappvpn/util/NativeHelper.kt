package com.myappvpn.util

object NativeHelper {
    init {
        System.loadLibrary("tun_helper")
    }

    /** Fork + exec tun2socks via native code. Returns child PID or -1 on error. */
    @JvmStatic external fun startTun2Socks(binary: String, tunFd: Int, socksPort: Int): Int

    /** Send SIGTERM to a process. */
    @JvmStatic external fun killProcess(pid: Int)

    /** Returns true if the process is still running. */
    @JvmStatic external fun isProcessAlive(pid: Int): Boolean
}
