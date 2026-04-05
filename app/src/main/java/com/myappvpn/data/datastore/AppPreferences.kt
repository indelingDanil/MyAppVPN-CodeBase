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
        val KEY_USE_LITE_SUBSCRIPTION = booleanPreferencesKey("use_lite_subscription")
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
        val KEY_IGARECK_FULL = booleanPreferencesKey("igareck_full_subscription")
    }

    val useLiteSubscription: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_USE_LITE_SUBSCRIPTION] ?: true }

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

    suspend fun setUseLiteSubscription(value: Boolean) {
        dataStore.edit { it[KEY_USE_LITE_SUBSCRIPTION] = value }
    }

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

    val igareckFullSubscription: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_IGARECK_FULL] ?: false }

    suspend fun setIgareckFullSubscription(v: Boolean) {
        dataStore.edit { it[KEY_IGARECK_FULL] = v }
    }
}
