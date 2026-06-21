package com.myappvpn.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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
        val KEY_DNS_PRIMARY = stringPreferencesKey("dns_primary")
        val KEY_DNS_SECONDARY = stringPreferencesKey("dns_secondary")
        val KEY_SHOW_STATS = booleanPreferencesKey("show_stats")
        val KEY_SOCKS_PORT = intPreferencesKey("socks_port")
        val KEY_HOTSPOT_SHARING = booleanPreferencesKey("hotspot_sharing")
        val KEY_AUTO_MODE = booleanPreferencesKey("auto_mode")
        val KEY_EXCLUDED_COUNTRIES = stringSetPreferencesKey("excluded_countries")
        // Custom subscriptions: JSON array of {name,url,enabled}. Built-in sources that the user
        // turned OFF are tracked by id in KEY_DISABLED_BUILTINS (empty = all built-ins enabled).
        val KEY_CUSTOM_SUBS = stringPreferencesKey("custom_subscriptions")
        val KEY_DISABLED_BUILTINS = stringSetPreferencesKey("disabled_builtin_sources")
        // Sentinel so an empty user selection is distinguishable from "never set" (→ default).
        private const val EXCLUDED_NONE = "__none__"
        private val DEFAULT_EXCLUDED = setOf("Russia")
    }

    val lastRefreshTime: Flow<Long> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_LAST_REFRESH_TIME] ?: 0L }

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

    val dnsPrimary: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_DNS_PRIMARY] ?: "1.1.1.1" }

    val dnsSecondary: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_DNS_SECONDARY] ?: "8.8.8.8" }

    val showStats: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_SHOW_STATS] ?: true }

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
}
