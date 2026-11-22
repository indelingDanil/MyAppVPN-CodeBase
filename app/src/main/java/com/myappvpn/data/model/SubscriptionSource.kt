package com.myappvpn.data.model

/**
 * A subscription source the app fetches servers from.
 *
 * - Built-in sources (currently only MyAppVPN) are defined in code with their mirror chains and
 *   can be enabled/disabled but never deleted.
 * - Custom sources are user-added single URLs and can be enabled/disabled and deleted.
 *
 * Only ENABLED sources are fetched; servers from disabled sources are pruned by the existing
 * `deleteNotIn` step in [com.myappvpn.data.repository.ConfigRepository.fetchAndSaveServers].
 */
data class SubscriptionSource(
    val id: String,
    val name: String,
    val urls: List<String>,   // built-in: full mirror chain; custom: single URL
    val enabled: Boolean,
    val isBuiltin: Boolean,
)
