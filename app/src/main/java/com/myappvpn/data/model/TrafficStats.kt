package com.myappvpn.data.model

data class TrafficStats(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val uploadSpeed: Long = 0L,      // bytes per second
    val downloadSpeed: Long = 0L,    // bytes per second
    val sessionDurationMs: Long = 0L
) {
    val uploadFormatted: String get() = formatBytes(uploadBytes)
    val downloadFormatted: String get() = formatBytes(downloadBytes)
    val uploadSpeedFormatted: String get() = "${formatBytes(uploadSpeed)}/s"
    val downloadSpeedFormatted: String get() = "${formatBytes(downloadSpeed)}/s"
    val sessionDurationFormatted: String get() = formatDuration(sessionDurationMs)

    companion object {
        val Empty = TrafficStats()

        fun formatBytes(bytes: Long): String = when {
            bytes < 1024L -> "${bytes} B"
            bytes < 1024L * 1024L -> "${"%.1f".format(bytes / 1024.0)} KB"
            bytes < 1024L * 1024L * 1024L -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }

        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return "%02d:%02d:%02d".format(hours, minutes, seconds)
        }
    }
}
