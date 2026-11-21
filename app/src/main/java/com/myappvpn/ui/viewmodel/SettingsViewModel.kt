package com.myappvpn.ui.viewmodel

import android.app.Activity
import android.content.Context
import com.myappvpn.util.AppLocale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // Nullable initial values: null = DataStore not loaded yet. The UI renders a placeholder
    // instead of a hardcoded default that would visibly flip a moment after the screen opens.
    val autoConnect: StateFlow<Boolean?> = preferences.autoConnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val hotspotSharing: StateFlow<Boolean?> = preferences.hotspotSharing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val splitTunnelEnabled: StateFlow<Boolean?> = preferences.splitTunnelEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val killSwitchEnabled: StateFlow<Boolean?> = preferences.killSwitchEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** "Обход DPI" — TLS ClientHello fragmentation to the VPN server (applies on next connect). */
    val tlsFragmentEnabled: StateFlow<Boolean?> = preferences.tlsFragmentEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val excludedApps: StateFlow<Set<String>> = preferences.excludedApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** "EXCLUDE" (selected apps bypass VPN) or "INCLUDE" (only selected apps via VPN). */
    val splitTunnelMode: StateFlow<String> = preferences.splitTunnelMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.SPLIT_MODE_EXCLUDE)

    /** Used to hint that connection-time settings apply on the next connect. */
    val vpnState: StateFlow<VpnState> = MyVpnService.vpnState

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    // True until the first installed-apps scan completes (distinguishes "loading" from "empty").
    private val _appsLoading = MutableStateFlow(true)
    val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()
    private var appsLoadStarted = false

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
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun loadInstalledApps() {
        if (appsLoadStarted) return
        appsLoadStarted = true
        _appsLoading.value = true
        viewModelScope.launch {
            try {
                loadInstalledAppsInternal()
            } finally {
                _appsLoading.value = false
            }
        }
    }

    private suspend fun loadInstalledAppsInternal() {
        val excluded = preferences.excludedApps.first()
        // PackageManager IPC per app (launch intent + label) — must stay off Main,
        // otherwise devices with hundreds of apps freeze / ANR on opening the screen.
        val apps = withContext(Dispatchers.Default) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { app ->
                    // Show only apps with launcher intent (user-visible apps)
                    app.packageName != context.packageName &&
                        pm.getLaunchIntentForPackage(app.packageName) != null
                }
                .map { app ->
                    AppInfo(
                        packageName = app.packageName,
                        appName = pm.getApplicationLabel(app).toString(),
                        isExcluded = app.packageName in excluded
                    )
                }
                .sortedBy { it.appName.lowercase() }
                .toList()
        }
        _installedApps.value = apps
    }

    fun setSplitTunnelMode(mode: String) = viewModelScope.launch {
        preferences.setSplitTunnelMode(mode)
    }

    /** Select every app in the current (search-filtered) list. */
    fun selectAllFiltered() = updateSelection { current, filtered -> current + filtered }

    /** Deselect only the apps in the current (search-filtered) list. */
    fun clearAllFiltered() = updateSelection { current, filtered -> current - filtered }

    /** Flip selection of every app in the current (search-filtered) list. */
    fun invertFiltered() = updateSelection { current, filtered ->
        val selectedInFiltered = filtered intersect current
        (current - selectedInFiltered) + (filtered - selectedInFiltered)
    }

    /** One read + one DataStore write, so the service rebuilds the TUN once (debounced). */
    private fun updateSelection(transform: (Set<String>, Set<String>) -> Set<String>) =
        viewModelScope.launch {
            val filtered = filteredApps.value.mapTo(HashSet()) { it.packageName }
            if (filtered.isEmpty()) return@launch
            val current = preferences.excludedApps.first()
            val updated = transform(current, filtered)
            if (updated == current) return@launch
            if (updated.isNotEmpty() && !preferences.splitTunnelEnabled.first()) {
                preferences.setSplitTunnelEnabled(true)
            }
            preferences.setExcludedApps(updated)
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

    /** "SYSTEM" / "LIGHT" / "DARK"; null = not loaded yet. */
    val themeMode: StateFlow<String?> = preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setThemeMode(value: String) = viewModelScope.launch {
        preferences.setThemeMode(value)
    }

    /**
     * In-app language tag ("" = system default), read synchronously. Not a StateFlow: changing
     * it recreates the activity (this ViewModel survives that), so the screen re-reads it.
     */
    fun currentLanguage(): String = AppLocale.current(context)

    /** Applies the language; the activity is recreated (by the system on 13+, by us below). */
    fun setLanguage(activity: Activity, tag: String) = AppLocale.apply(activity, tag)

    fun setHotspotSharing(value: Boolean) = viewModelScope.launch {
        preferences.setHotspotSharing(value)
    }

    fun setKillSwitchEnabled(value: Boolean) = viewModelScope.launch {
        preferences.setKillSwitchEnabled(value)
    }

    fun setTlsFragmentEnabled(value: Boolean) = viewModelScope.launch {
        preferences.setTlsFragmentEnabled(value)
    }

    fun setAutoConnect(value: Boolean) = viewModelScope.launch {
        preferences.setAutoConnect(value)
    }

    fun setSplitTunnelEnabled(value: Boolean) = viewModelScope.launch {
        preferences.setSplitTunnelEnabled(value)
    }
}
