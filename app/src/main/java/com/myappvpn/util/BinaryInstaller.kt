package com.myappvpn.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Provides paths to xray and tun2socks executables.
 *
 * Binaries are packaged as .so files in jniLibs/ with generic names (libcore.so, libnetstack.so)
 * to avoid detection by other apps scanning /proc for known VPN process names.
 * Android extracts them to nativeLibraryDir which allows execution (unlike filesDir on API 29+).
 * geoip.dat / geosite.dat are copied from assets/xray/ to filesDir on first run.
 */
object BinaryInstaller {

    private const val TAG = "BinaryInstaller"

    // Generic binary names — not identifiable as VPN tools by other apps
    private const val XRAY_BINARY = "libcore.so"
    private const val TUN2SOCKS_BINARY = "libnetstack.so"

    /**
     * Returns path to xray executable (from nativeLibraryDir).
     * Also ensures geoip.dat and geosite.dat are in filesDir.
     */
    fun getXrayBinaryPath(context: Context): String {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val xrayBin = File(nativeDir, XRAY_BINARY)
        copyGeoData(context)
        return xrayBin.absolutePath
    }

    /**
     * Returns path to tun2socks executable (from nativeLibraryDir).
     */
    fun getTun2SocksBinaryPath(context: Context): String {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val tun2socksBin = File(nativeDir, TUN2SOCKS_BINARY)
        return tun2socksBin.absolutePath
    }

    /**
     * Copy geoip.dat / geosite.dat from assets/xray/ to filesDir.
     * XRay reads them from the directory set in xray.location.asset env var.
     */
    private fun copyGeoData(context: Context) {
        val geoFiles = listOf("geoip.dat", "geosite.dat")
        for (name in geoFiles) {
            val dest = File(context.filesDir, name)
            if (!dest.exists()) {
                try {
                    context.assets.open("xray/$name").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.d(TAG, "Copied $name to ${dest.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not copy $name: ${e.message}")
                }
            }
        }
    }
}
