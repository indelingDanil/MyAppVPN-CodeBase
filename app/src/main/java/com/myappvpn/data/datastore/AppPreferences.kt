package com.myappvpn.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        val KEY_LAST_REFRESH_TIME = longPreferencesKey("last_refresh_time")
        val KEY_SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")
        val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val KEY_SPLIT_TUNNEL_ENABLED = booleanPreferencesKey("split_tunnel_enabled")
        val KEY_EXCLUDED_APPS = stringSetPreferencesKey("excluded_apps")
        val KEY_SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
        const val SPLIT_MODE_EXCLUDE = "EXCLUDE"
        const val SPLIT_MODE_INCLUDE = "INCLUDE"
        // LEGACY — superseded by KEY_DNS_SERVERS (Stage 3b); never read for the tunnel.
        val KEY_DNS_PRIMARY = stringPreferencesKey("dns_primary")
        val KEY_DNS_SECONDARY = stringPreferencesKey("dns_secondary")
        // Custom DNS servers: JSON array of {id,address,label,enabled} (see DnsRepository).
        val KEY_DNS_SERVERS = stringPreferencesKey("dns_servers")
        // Xray dns.queryStrategy: "UseIPv4" (default) or "UseIP".
        val KEY_DNS_QUERY_STRATEGY = stringPreferencesKey("dns_query_strategy")
        val KEY_SHOW_STATS = booleanPreferencesKey("show_stats")
        // UI theme: ThemeMode name (SYSTEM / LIGHT / DARK); absent = SYSTEM.
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_SOCKS_PORT = intPreferencesKey("socks_port")
        val KEY_HOTSPOT_SHARING = booleanPreferencesKey("hotspot_sharing")
        val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch_enabled")
        val KEY_AUTO_MODE = booleanPreferencesKey("auto_mode")
        val KEY_EXCLUDED_COUNTRIES = stringSetPreferencesKey("excluded_countries")
        // OpenFlux (Beta): own exit via a PHP script (phpbox.php) on ordinary web hosting.
        val KEY_OPENFLUX_ENABLED = booleanPreferencesKey("openflux_enabled")
        val KEY_OPENFLUX_URL = stringPreferencesKey("openflux_url")
        val KEY_OPENFLUX_TOKEN = stringPreferencesKey("openflux_token")
        // true = use the author's public demo exit (URL/token are constants, never stored).
        val KEY_OPENFLUX_DEMO = booleanPreferencesKey("openflux_demo")
        // Channel to the own exit (OpenFluxChannel.key) + per-channel links and the share-link key.
        val KEY_OPENFLUX_CHANNEL = stringPreferencesKey("openflux_channel")
        val KEY_OPENFLUX_LINK_MAILRU = stringPreferencesKey("openflux_link_mailru")
        val KEY_OPENFLUX_LINK_CUPS = stringPreferencesKey("openflux_link_cups")
        val KEY_OPENFLUX_LINK_SHARE = stringPreferencesKey("openflux_link_share")
        val KEY_OPENFLUX_SECRET = stringPreferencesKey("openflux_secret")
        // Random per-install id (see installId()).
        val KEY_INSTALL_ID = stringPreferencesKey("install_id")
        // Custom subscriptions: JSON array of {name,url,enabled}. Built-in sources that the user
        // turned OFF are tracked by id in KEY_DISABLED_BUILTINS (empty = all built-ins enabled).
        val KEY_CUSTOM_SUBS = stringPreferencesKey("custom_subscriptions")
        val KEY_DISABLED_BUILTINS = stringSetPreferencesKey("disabled_builtin_sources")
        // Stable credentials of the hotspot SOCKS5 proxy (generated once, see ensureHotspotCredentials).
        val KEY_HOTSPOT_USER = stringPreferencesKey("hotspot_user")
        val KEY_HOTSPOT_PASS = stringPreferencesKey("hotspot_pass")
        // No 0/O/1/l/I — the password is typed by hand on another device.
        private const val HOTSPOT_PASS_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val HOTSPOT_PASS_LENGTH = 10
        private val secureRandom = SecureRandom()

        private fun newHotspotUser(): String =
            "share" + (1..4).joinToString("") { secureRandom.nextInt(10).toString() }

        private fun newHotspotPass(): String = buildString(HOTSPOT_PASS_LENGTH) {
            repeat(HOTSPOT_PASS_LENGTH) {
                append(HOTSPOT_PASS_ALPHABET[secureRandom.nextInt(HOTSPOT_PASS_ALPHABET.length)])
            }
        }
        // Sentinel so an empty user selection is distinguishable from "never set" (→ default).
        private const val EXCLUDED_NONE = "__none__"
        private val DEFAULT_EXCLUDED = setOf("Russia")
    }

    val lastRefreshTime: Flow<Long> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_LAST_REFRESH_TIME] ?: 0L }

    // LEGACY — no longer written or read. The pin lives in Room (`servers.isSelected`, see
    // ConfigRepository.pinnedServer) and the mode in KEY_AUTO_MODE. Kept only so old installs'
    // stored key doesn't need a migration.
    val selectedServerId: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_SELECTED_SERVER_ID] ?: "" }

    val autoConnect: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_AUTO_CONNECT] ?: false }

    val splitTunnelEnabled: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_SPLIT_TUNNEL_ENABLED] ?: false }

    val excludedApps: Flow<Set<String>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_EXCLUDED_APPS] ?: emptySet() }

    /** Raw JSON of the custom DNS server list (decoded by DnsRepository). */
    val dnsServersJson: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_DNS_SERVERS] ?: "[]" }

    /**
     * Atomic read-modify-write of the DNS list inside ONE dataStore.edit (no lost updates).
     * [transform] gets the current JSON and returns the new JSON, or null to leave it unchanged.
     */
    suspend fun updateDnsServersJson(transform: (String) -> String?) {
        dataStore.edit { prefs ->
            val updated = transform(prefs[KEY_DNS_SERVERS] ?: "[]")
            if (updated != null) prefs[KEY_DNS_SERVERS] = updated
        }
    }

    val dnsQueryStrategy: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_DNS_QUERY_STRATEGY] ?: "UseIPv4" }

    suspend fun setDnsQueryStrategy(value: String) {
        dataStore.edit { it[KEY_DNS_QUERY_STRATEGY] = value }
    }

    /** Resets the DNS list and query strategy to defaults in one transaction. */
    suspend fun resetDnsSettings() {
        dataStore.edit {
            it.remove(KEY_DNS_SERVERS)
            it.remove(KEY_DNS_QUERY_STRATEGY)
        }
    }

    // LEGACY (pre-3b) — not used by the tunnel any more; see dnsServersJson.
    val dnsPrimary: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_DNS_PRIMARY] ?: "1.1.1.1" }

    val dnsSecondary: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_DNS_SECONDARY] ?: "8.8.8.8" }

    val showStats: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_SHOW_STATS] ?: true }

    /** ThemeMode name: "SYSTEM" (default), "LIGHT" or "DARK". */
    val themeMode: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_THEME_MODE] ?: "SYSTEM" }

    suspend fun setThemeMode(value: String) {
        dataStore.edit { it[KEY_THEME_MODE] = value }
    }

    val socksPort: Flow<Int> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_SOCKS_PORT] ?: 10808 }

    suspend fun setLastRefreshTime(time: Long) {
        dataStore.edit { it[KEY_LAST_REFRESH_TIME] = time }
    }

    suspend fun setSelectedServerId(id: String) {
        dataStore.edit { it[KEY_SELECTED_SERVER_ID] = id }
    }

    suspend fun setAutoConnect(value: Boolean) {
        dataStore.edit { it[KEY_AUTO_CONNECT] = value }
    }

    suspend fun setSplitTunnelEnabled(value: Boolean) {
        dataStore.edit { it[KEY_SPLIT_TUNNEL_ENABLED] = value }
    }

    suspend fun setExcludedApps(apps: Set<String>) {
        dataStore.edit { it[KEY_EXCLUDED_APPS] = apps }
    }

    /**
     * Split-tunnel mode. "EXCLUDE" (default): apps in [excludedApps] bypass the VPN.
     * "INCLUDE": ONLY apps in [excludedApps] go through the VPN (the set is reused as
     * "selected apps" to avoid a migration).
     */
    val splitTunnelMode: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { if (it[KEY_SPLIT_TUNNEL_MODE] == SPLIT_MODE_INCLUDE) SPLIT_MODE_INCLUDE else SPLIT_MODE_EXCLUDE }

    suspend fun setSplitTunnelMode(mode: String) {
        val normalized = if (mode == SPLIT_MODE_INCLUDE) SPLIT_MODE_INCLUDE else SPLIT_MODE_EXCLUDE
        dataStore.edit { it[KEY_SPLIT_TUNNEL_MODE] = normalized }
    }

    suspend fun setDnsPrimary(dns: String) {
        dataStore.edit { it[KEY_DNS_PRIMARY] = dns }
    }

    suspend fun setDnsSecondary(dns: String) {
        dataStore.edit { it[KEY_DNS_SECONDARY] = dns }
    }

    suspend fun setSocksPort(port: Int) {
        dataStore.edit { it[KEY_SOCKS_PORT] = port }
    }

    val hotspotSharing: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_HOTSPOT_SHARING] ?: false }

    suspend fun setHotspotSharing(v: Boolean) {
        dataStore.edit { it[KEY_HOTSPOT_SHARING] = v }
    }

    /** SOCKS5 login/password for hotspot sharing; null until first generated. */
    val hotspotCredentials: Flow<Pair<String, String>?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val user = prefs[KEY_HOTSPOT_USER]
            val pass = prefs[KEY_HOTSPOT_PASS]
            if (user.isNullOrEmpty() || pass.isNullOrEmpty()) null else user to pass
        }

    /**
     * Returns the stable hotspot SOCKS5 credentials, generating any missing part once.
     * Read + generate + write happen inside ONE dataStore.edit, so concurrent callers can't
     * end up with different values.
     */
    suspend fun ensureHotspotCredentials(): Pair<String, String> {
        val prefs = dataStore.edit { p ->
            if (p[KEY_HOTSPOT_USER].isNullOrEmpty()) p[KEY_HOTSPOT_USER] = newHotspotUser()
            if (p[KEY_HOTSPOT_PASS].isNullOrEmpty()) p[KEY_HOTSPOT_PASS] = newHotspotPass()
        }
        return prefs[KEY_HOTSPOT_USER]!! to prefs[KEY_HOTSPOT_PASS]!!
    }

    /** New random password (login kept). Takes effect on the next connect. */
    suspend fun regenerateHotspotPassword() {
        dataStore.edit { p ->
            if (p[KEY_HOTSPOT_USER].isNullOrEmpty()) p[KEY_HOTSPOT_USER] = newHotspotUser()
            p[KEY_HOTSPOT_PASS] = newHotspotPass()
        }
    }

    /**
     * Kill-switch: when the tunnel drops unexpectedly, keep the TUN up and BLOCK traffic
     * (instead of tearing it down and letting traffic leak over the operator network) while
     * the service recovers. Default ON — users connect to untrusted public exits, a real-IP
     * leak on failure is exactly what we must prevent.
     */
    val killSwitchEnabled: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_KILL_SWITCH] ?: true }

    suspend fun setKillSwitchEnabled(v: Boolean) {
        dataStore.edit { it[KEY_KILL_SWITCH] = v }
    }

    /** Raw JSON of user-added custom subscriptions (decoded by SubscriptionRepository). */
    val customSubscriptionsJson: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_CUSTOM_SUBS] ?: "[]" }

    suspend fun setCustomSubscriptionsJson(json: String) {
        dataStore.edit { it[KEY_CUSTOM_SUBS] = json }
    }

    /** Ids of built-in sources the user disabled (empty = all built-ins enabled). */
    val disabledBuiltinSources: Flow<Set<String>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_DISABLED_BUILTINS] ?: emptySet() }

    suspend fun setDisabledBuiltinSources(ids: Set<String>) {
        dataStore.edit { it[KEY_DISABLED_BUILTINS] = ids }
    }

    /**
     * Connection mode. true = Auto (Xray balancer picks/switches servers); false = manual
     * (connect to the single pinned server). Default Auto.
     */
    val autoMode: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_AUTO_MODE] ?: true }

    suspend fun setAutoMode(value: Boolean) {
        dataStore.edit { it[KEY_AUTO_MODE] = value }
    }

    /**
     * Countries excluded from the server list and the balancer pool.
     * Defaults to {"Russia"} (RU servers have lowest latency but can't unblock
     * Telegram/YouTube, so the balancer would otherwise prefer them).
     */
    val excludedCountries: Flow<Set<String>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val stored = prefs[KEY_EXCLUDED_COUNTRIES] ?: return@map DEFAULT_EXCLUDED
            if (stored.contains(EXCLUDED_NONE)) emptySet() else stored
        }

    suspend fun setExcludedCountries(countries: Set<String>) {
        dataStore.edit {
            it[KEY_EXCLUDED_COUNTRIES] =
                if (countries.isEmpty()) setOf(EXCLUDED_NONE) else countries
        }
    }

    /** OpenFlux (Beta) mode: route traffic through the user's own PHP exit instead of VLESS. */
    val openfluxEnabled: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_OPENFLUX_ENABLED] ?: false }

    suspend fun setOpenfluxEnabled(v: Boolean) {
        dataStore.edit { it[KEY_OPENFLUX_ENABLED] = v }
    }

    /** Full URL of phpbox.php (http/https). */
    val openfluxUrl: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_OPENFLUX_URL] ?: "" }

    suspend fun setOpenfluxUrl(v: String) {
        dataStore.edit { it[KEY_OPENFLUX_URL] = v }
    }

    /** Shared secret configured in the server's config.php. */
    val openfluxToken: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_OPENFLUX_TOKEN] ?: "" }

    suspend fun setOpenfluxToken(v: String) {
        dataStore.edit { it[KEY_OPENFLUX_TOKEN] = v }
    }

    /**
     * OpenFlux uses the author's public demo exit. Its URL/token are NOT stored in
     * [openfluxUrl]/[openfluxToken] (those hold only the user's own exit) — see OpenFluxEndpoint.
     */
    val openfluxDemo: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_OPENFLUX_DEMO] ?: false }

    suspend fun setOpenfluxDemo(v: Boolean) {
        dataStore.edit { it[KEY_OPENFLUX_DEMO] = v }
    }

    private fun stringPref(key: Preferences.Key<String>): Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[key] ?: "" }

    /** Channel to the own exit: "direct" (default) | "mailru" | "cups" | "link" (OpenFluxChannel). */
    val openfluxChannel: Flow<String> = stringPref(KEY_OPENFLUX_CHANNEL)
    /** Mail.ru document public link (channel "mailru"). */
    val openfluxLinkMailru: Flow<String> = stringPref(KEY_OPENFLUX_LINK_MAILRU)
    /** cups.online room code (channel "cups"). */
    val openfluxLinkCups: Flow<String> = stringPref(KEY_OPENFLUX_LINK_CUPS)
    /** openflux://v1/… share link (channel "link"). */
    val openfluxLinkShare: Flow<String> = stringPref(KEY_OPENFLUX_LINK_SHARE)
    /** Optional encryption key for the share link (overrides the link's own). */
    val openfluxSecret: Flow<String> = stringPref(KEY_OPENFLUX_SECRET)

    /** Writes the channel settings in one edit. */
    suspend fun setOpenfluxChannelConfig(
        channel: String, mailru: String, cups: String, share: String, secret: String,
    ) {
        dataStore.edit {
            it[KEY_OPENFLUX_CHANNEL] = channel
            it[KEY_OPENFLUX_LINK_MAILRU] = mailru
            it[KEY_OPENFLUX_LINK_CUPS] = cups
            it[KEY_OPENFLUX_LINK_SHARE] = share
            it[KEY_OPENFLUX_SECRET] = secret
        }
    }

    /**
     * One-time migration (idempotent, single edit): older versions wrote the demo URL/token into
     * [openfluxUrl]/[openfluxToken]. If the stored pair is a demo pair ([isDemoPair]), clear it and
     * set [openfluxDemo] instead.
     */
    suspend fun migrateOpenfluxDemo(isDemoPair: (url: String, token: String) -> Boolean) {
        dataStore.edit { p ->
            val u = p[KEY_OPENFLUX_URL].orEmpty()
            val t = p[KEY_OPENFLUX_TOKEN].orEmpty()
            if (u.isNotEmpty() && isDemoPair(u, t)) {
                p[KEY_OPENFLUX_URL] = ""
                p[KEY_OPENFLUX_TOKEN] = ""
                p[KEY_OPENFLUX_DEMO] = true
            }
        }
    }

    /**
     * Random per-install id (UUID), generated once. Sent to OpenFlux demo exits (`-client`) so the
     * one-time trial is tracked per install. Read + generate + write in ONE dataStore.edit.
     */
    suspend fun installId(): String {
        val prefs = dataStore.edit { p ->
            if (p[KEY_INSTALL_ID].isNullOrEmpty()) p[KEY_INSTALL_ID] = java.util.UUID.randomUUID().toString()
        }
        return prefs[KEY_INSTALL_ID]!!
    }
}
