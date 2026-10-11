package com.myappvpn.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.R
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.repository.ConfigRepository
import com.myappvpn.domain.ConnectionController
import com.myappvpn.domain.usecase.FetchServersUseCase
import com.myappvpn.domain.validation.TcpReachabilityProbe
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val fetchServersUseCase: FetchServersUseCase,
    private val tcpProbe: TcpReachabilityProbe,
    private val preferences: AppPreferences,
    private val controller: ConnectionController,
) : ViewModel() {

    private companion object {
        const val WORKING_WINDOW_MS = 24 * 60 * 60_000L
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Frozen display order (server ids). The watchdog constantly writes latency/speed while
    // connected; without this the DB re-sorts by latency on every write and cards jump under
    // the user's finger + scroll drifts. We refresh per-card VALUES in place but reorder ONLY
    // on explicit user action (refresh / ping) via resortByLatency().
    private val _order = MutableStateFlow<List<String>>(emptyList())

    val excludedCountries: StateFlow<Set<String>> = preferences.excludedCountries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), setOf("Russia"))

    /** Stored mode (true = Auto). null until DataStore is loaded → no check marks flash. */
    val autoMode: StateFlow<Boolean?> = preferences.autoMode
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val vpnState: StateFlow<VpnState> = MyVpnService.vpnState

    /** OpenFlux (Beta) mode on → the list isn't used; the screen shows a banner. */
    val openfluxEnabled: StateFlow<Boolean> = preferences.openfluxEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The server the live session is using right now (null = no session). */
    val activeServer: StateFlow<ServerProfile?> = MyVpnService.connectedServer

    private fun matchesQuery(s: ServerProfile, query: String): Boolean =
        query.isBlank() ||
            s.country.contains(query, ignoreCase = true) ||
            s.name.contains(query, ignoreCase = true)

    /** Server list: filtered by excluded countries + search, in a STABLE (frozen) order. */
    val servers: StateFlow<List<ServerProfile>> = combine(
        configRepository.servers,
        _searchQuery,
        preferences.excludedCountries,
        _order,
    ) { servers, query, excluded, order ->
        val filtered = servers
            .filter { it.country !in excluded }
            .filter { matchesQuery(it, query) }
        if (order.isEmpty()) {
            filtered
        } else {
            // Keep the frozen order; new servers (not yet ordered) go to the end.
            val byId = filtered.associateBy { it.id }
            val orderSet = order.toHashSet()
            order.mapNotNull { byId[it] } + filtered.filter { it.id !in orderSet }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Emits once a minute so the 24h window below is re-evaluated even without DB writes. */
    private val minuteTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    /**
     * Proxies that were confirmed actually working (Telegram-reachable, recorded via markVerified
     * by the balancer pin orchestrator or a manual connection) within the last 24h. Manual fallback
     * pool: if Auto ever picks badly, the user can tap one of these known-good servers.
     * Honors the country filter and the search query like the main list; stamps from the future
     * (clock changes) are ignored.
     */
    val recentlyWorking: StateFlow<List<ServerProfile>> = combine(
        configRepository.servers,
        preferences.excludedCountries,
        _searchQuery,
        minuteTicker,
    ) { list, excluded, query, _ ->
        val now = System.currentTimeMillis()
        val cutoff = now - WORKING_WINDOW_MS
        list.asSequence()
            .filter { it.verifiedAt in (cutoff + 1)..now }
            .filter { it.country !in excluded }
            .filter { matchesQuery(it, query) }
            .sortedByDescending { it.verifiedAt }
            .toList()
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Total number of servers in the DB (before the country filter and search). */
    val totalCount: StateFlow<Int> = configRepository.servers
        .map { it.size }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pingProgress = MutableStateFlow(0f)
    val pingProgress: StateFlow<Float> = _pingProgress.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()

    init {
        loadServersIfEmpty()
        seedOrder()
    }

    private fun loadServersIfEmpty() {
        viewModelScope.launch {
            val current = configRepository.servers.first()
            if (current.isEmpty()) refreshServers()
        }
    }

    /** Seed the frozen order once from the DB's initial (latency-sorted) list. */
    private fun seedOrder() {
        viewModelScope.launch {
            configRepository.servers.collect { list ->
                if (_order.value.isEmpty() && list.isNotEmpty()) {
                    _order.value = list.map { it.id }
                }
            }
        }
    }

    /** Re-sort the frozen order by current latency — only on explicit user action. */
    private fun resortByLatency() {
        viewModelScope.launch { _order.value = configRepository.servers.first().map { it.id } }
    }

    fun refreshServers() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            fetchServersUseCase()
                .onFailure { e -> _error.emit(context.getString(R.string.servers_err_load, e.message.orEmpty())) }
            _isLoading.value = false
            resortByLatency()
        }
    }

    /**
     * Lightweight TCP ping for latency display in the list (no TLS/Xray stages — those
     * are replaced by the in-core observatory of the balancer). Excludes filtered countries.
     */
    fun pingAllServers() {
        viewModelScope.launch {
            _isPinging.value = true
            _pingProgress.value = 0f

            val excluded = preferences.excludedCountries.first()
            val targets = configRepository.servers.first().filter { it.country !in excluded }
            if (targets.isEmpty()) {
                _isPinging.value = false
                return@launch
            }

            tcpProbe.probeAll(targets) { completed, total, _ ->
                _pingProgress.value = completed.toFloat() / total
            }
            _pingProgress.value = 1f
            _isPinging.value = false
            resortByLatency()   // user asked for a ping → now it's OK to re-sort fastest-first
            // Latency-only refresh — do not auto-navigate/select (balancer picks servers).
        }
    }

    /** Manually drop a server from the "Рабочие за 24ч" pool (clears its verified stamp). */
    fun removeFromWorking(server: ServerProfile) {
        viewModelScope.launch { configRepository.markFailed(server.id) }
    }

    /** Undo for [removeFromWorking]: re-stamps the server as verified so it returns to the pool. */
    fun restoreToWorking(server: ServerProfile) {
        viewModelScope.launch { configRepository.markVerified(server.id) }
    }

    /**
     * Pin a specific server → manual mode (live hot-switch if a tunnel is up). Delegated to the
     * app-scoped ConnectionController, so it completes even though this screen pops right away.
     */
    fun selectServer(server: ServerProfile) = controller.pin(server)

    /** Back to Auto (balancer) mode — live under the running tunnel. */
    fun selectAuto() = controller.setAuto()

    /** Leave OpenFlux mode (live session follows via the controller). */
    fun disableOpenFlux() {
        controller.setOpenFluxEnabled(false)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Toggle whether Russian servers are excluded (the default-on filter). */
    fun setExcludeRussia(exclude: Boolean) {
        viewModelScope.launch {
            val current = preferences.excludedCountries.first().toMutableSet()
            if (exclude) current.add("Russia") else current.remove("Russia")
            preferences.setExcludedCountries(current)
        }
    }
}
