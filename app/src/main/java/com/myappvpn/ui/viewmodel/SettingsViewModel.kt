package com.myappvpn.ui.viewmodel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.datastore.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isExcluded: Boolean
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences
) : ViewModel() {

    val autoConnect: StateFlow<Boolean> = preferences.autoConnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val hotspotSharing: StateFlow<Boolean> = preferences.hotspotSharing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val splitTunnelEnabled: StateFlow<Boolean> = preferences.splitTunnelEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val excludedApps: StateFlow<Set<String>> = preferences.excludedApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptySet())

    val dnsPrimary: StateFlow<String> = preferences.dnsPrimary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "1.1.1.1")

    val dnsSecondary: StateFlow<String> = preferences.dnsSecondary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "8.8.8.8")

    val socksPort: StateFlow<Int> = preferences.socksPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 10808)

    val killSwitchEnabled: StateFlow<Boolean> = preferences.killSwitchEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    // Split-tunnel app search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredApps: StateFlow<List<AppInfo>> =
        combine(_installedApps, _searchQuery) { apps, q ->
            if (q.isBlank()) apps
            else apps.filter {
                it.appName.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun loadInstalledApps() {
        viewModelScope.launch {
            val excluded = preferences.excludedApps.first()
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    // Show only apps with launcher intent (user-visible apps)
                    pm.getLaunchIntentForPackage(app.packageName) != null &&
                    app.packageName != context.packageName
                }
                .sortedBy { app ->
                    pm.getApplicationLabel(app).toString()
                }
                .map { app ->
                    AppInfo(
                        packageName = app.packageName,
                        appName = pm.getApplicationLabel(app).toString(),
                        isExcluded = app.packageName in excluded
                    )
                }
            _installedApps.value = apps
        }
    }

    fun toggleAppExclusion(packageName: String) {
        viewModelScope.launch {
            val current = preferences.excludedApps.first().toMutableSet()
            if (packageName in current) {
                current.remove(packageName)
            } else {
                current.add(packageName)
                // Auto-enable split tunnel when the first app is excluded
                if (!preferences.splitTunnelEnabled.first()) {
                    preferences.setSplitTunnelEnabled(true)
                }
            }
            preferences.setExcludedApps(current)
        }
    }

    fun setHotspotSharing(value: Boolean) = viewModelScope.launch {
        preferences.setHotspotSharing(value)
    }

    fun setKillSwitchEnabled(value: Boolean) = viewModelScope.launch {
        preferences.setKillSwitchEnabled(value)
    }

    fun setAutoConnect(value: Boolean) = viewModelScope.launch {
        preferences.setAutoConnect(value)
    }

    fun setSplitTunnelEnabled(value: Boolean) = viewModelScope.launch {
        preferences.setSplitTunnelEnabled(value)
    }

    fun setDnsPrimary(dns: String) = viewModelScope.launch {
        preferences.setDnsPrimary(dns)
    }

    fun setDnsSecondary(dns: String) = viewModelScope.launch {
        preferences.setDnsSecondary(dns)
    }

    fun setSocksPort(port: Int) = viewModelScope.launch {
        preferences.setSocksPort(port)
    }
}
