package com.myappvpn.domain

import android.util.Log
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.parser.VlessUriParser
import com.myappvpn.data.repository.ConfigRepository
import com.myappvpn.domain.usecase.ConnectVpnUseCase
import com.myappvpn.service.ActiveMode
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the Auto/Manual connection mode.
 *
 * Sources of truth:
 *  - mode = DataStore `autoMode` (AppPreferences)
 *  - pin  = Room `servers.isSelected` (ConfigRepository.selectServer / pinnedServer)
 *  - what the running tunnel actually does = MyVpnService.activeMode / connectedServer
 *
 * Runs on an app-lifetime scope so a switch started from a screen that is immediately popped
 * (Servers → back) is never cancelled half-way. All mode operations are applied strictly in
 * call order (single consumer queue + mutex), so rapid taps can't interleave.
 */
@Singleton
class ConnectionController @Inject constructor(
    private val preferences: AppPreferences,
    private val configRepository: ConfigRepository,
    private val connectVpnUseCase: ConnectVpnUseCase,
) {
    private companion object {
        const val TAG = "ConnectionController"
        // Reconnect (mode change to/from OpenFlux): max wait for the old session's teardown.
        const val RECONNECT_TEARDOWN_TIMEOUT_MS = 10_000L
        // Reconnect while the old connect is only pending: max wait for it to become visible.
        const val PENDING_CONNECT_WAIT_MS = 3_000L
        const val MSG_LEAVE_OPENFLUX_NO_SERVERS =
            "Нет доступных серверов для выбранного режима — OpenFlux остаётся активным"
    }

    /** Outcome of [leaveOpenFlux]. */
    private enum class Leave { NOT_INVOLVED, LEFT, ABORTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** FIFO of fire-and-forget operations (setAuto / pin / disconnect). */
    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val _errors = Channel<String>(Channel.BUFFERED)
    /** User-facing errors from background mode switches (delivered once, buffered until collected). */
    val errors: Flow<String> = _errors.receiveAsFlow()

    init {
        // One-time: move a demo URL/token stored by older versions to the openfluxDemo flag.
        scope.launch { OpenFluxEndpoint.migrateOnce(preferences) }
        scope.launch {
            for (op in ops) {
                try {
                    mutex.withLock { op() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Mode operation failed", e)
                    _errors.trySend(e.message ?: "Не удалось переключить режим")
                }
            }
        }
    }

    private fun enqueue(op: suspend () -> Unit) {
        ops.trySend(op)
    }

    /** What we last asked the running session to be. Null = no session / unknown. */
    private sealed interface Target {
        object Auto : Target
        data class Server(val id: String) : Target
        object OpenFlux : Target
    }

    // Our own last request to the live session. The service applies queued switches later, so
    // its published activeMode/connectedServer is stale meanwhile — deciding from it lost
    // quick back-and-forth taps (prefs said Auto, tunnel stayed Manual).
    @Volatile private var lastRequested: Target? = null
    // Connect requested but the service hasn't published CONNECTING yet (it may still be
    // finishing a previous disconnect). Switches in that window must still reach the service,
    // which queues them behind the in-flight connect.
    @Volatile private var connectPendingSince = 0L

    init {
        scope.launch {
            MyVpnService.vpnState.collect { st ->
                when (st) {
                    VpnState.CONNECTING, VpnState.CONNECTED, VpnState.BLOCKED -> connectPendingSince = 0L
                    VpnState.ERROR -> { connectPendingSince = 0L; lastRequested = null }
                    VpnState.DISCONNECTED -> if (connectPendingSince == 0L) lastRequested = null
                    else -> {}
                }
            }
        }
    }

    private fun hasSession(): Boolean {
        val pending = connectPendingSince != 0L &&
            System.currentTimeMillis() - connectPendingSince < 30_000L
        return pending || when (MyVpnService.vpnState.value) {
            VpnState.CONNECTED, VpnState.CONNECTING, VpnState.BLOCKED -> true
            else -> false
        }
    }

    private fun currentTarget(): Target? = lastRequested ?: when (MyVpnService.activeMode.value) {
        ActiveMode.AUTO -> Target.Auto
        ActiveMode.MANUAL -> MyVpnService.connectedServer.value?.id?.let { Target.Server(it) }
        ActiveMode.OPENFLUX -> Target.OpenFlux
        null -> null
    }

    private fun hasOpenFluxSession(): Boolean = hasSession() && currentTarget() == Target.OpenFlux

    /**
     * Full reconnect into the CURRENTLY STORED mode (OpenFlux can't be hot-switched — it is a
     * different process chain): disconnect, wait for DISCONNECTED/ERROR (bounded), connect.
     * Caller holds [mutex].
     */
    private suspend fun reconnectLocked() {
        Log.i(TAG, "Reconnecting to apply the connection mode")
        // The old session may only be "pending" (CONNECT sent, service hasn't published
        // CONNECTING yet). Waiting for DISCONNECTED/ERROR would then return at once and the new
        // CONNECT could race the old one — first let the old connect become visible (bounded).
        val st = MyVpnService.vpnState.value
        if (connectPendingSince != 0L && (st == VpnState.DISCONNECTED || st == VpnState.ERROR)) {
            val left = withTimeoutOrNull(PENDING_CONNECT_WAIT_MS) {
                MyVpnService.vpnState.first { it != VpnState.DISCONNECTED && it != VpnState.ERROR }
            }
            if (left == null) Log.w(TAG, "Pending connect not visible within ${PENDING_CONNECT_WAIT_MS}ms — reconnecting anyway")
        }
        connectPendingSince = 0L
        lastRequested = null
        connectVpnUseCase.disconnect()
        val done = withTimeoutOrNull(RECONNECT_TEARDOWN_TIMEOUT_MS) {
            MyVpnService.vpnState.first { it == VpnState.DISCONNECTED || it == VpnState.ERROR }
        }
        // On timeout still connect: the service queues a CONNECT behind an in-flight teardown.
        if (done == null) Log.w(TAG, "Teardown not confirmed within ${RECONNECT_TEARDOWN_TIMEOUT_MS}ms — connecting anyway")
        connectLocked()
    }

    /**
     * Leaving OpenFlux for Auto/Manual: turn the OpenFlux pref off.
     *  - [Leave.NOT_INVOLVED]: OpenFlux pref off and no OpenFlux session — hot-switch as usual.
     *  - [Leave.LEFT]: pref turned off — the caller stores its mode and reconnects instead of
     *    hot-switching (the service ignores switches in OpenFlux).
     *  - [Leave.ABORTED]: a live OpenFlux session exists but [targetReady] says the target mode
     *    can't connect — nothing changed (OpenFlux stays on, tunnel kept); the caller stores its
     *    mode prefs and reports [MSG_LEAVE_OPENFLUX_NO_SERVERS].
     */
    private suspend fun leaveOpenFlux(targetReady: suspend () -> Boolean): Leave {
        val enabled = preferences.openfluxEnabled.first()
        if (!enabled && !hasOpenFluxSession()) return Leave.NOT_INVOLVED
        if (hasOpenFluxSession() && !targetReady()) {
            Log.w(TAG, "Leave OpenFlux aborted — target mode has no servers")
            return Leave.ABORTED
        }
        if (enabled) preferences.setOpenfluxEnabled(false)
        MyVpnService.clearOpenFluxStatus()
        return Leave.LEFT
    }

    /** Cheap pre-check: Auto has at least one candidate server. */
    private suspend fun autoHasCandidates(): Boolean =
        configRepository.getServersExcludingCountries(preferences.excludedCountries.first()).isNotEmpty()

    /** Cheap pre-check: the STORED Auto/Manual mode can connect (used when leaving OpenFlux). */
    private suspend fun storedModeReady(): Boolean =
        if (preferences.autoMode.first()) autoHasCandidates()
        else configRepository.getSelectedServer() != null

    /**
     * OpenFlux (Beta) on/off. Writes the pref; with a live session, reconnects so the change
     * applies (enabling always reconnects — that also re-applies an edited URL/token; disabling
     * reconnects only if the live session is OpenFlux).
     * @throws IllegalStateException (via [errors]) when enabling during a session without URL/token
     *         — checked BEFORE anything changes, so the running tunnel isn't dropped.
     */
    fun setOpenFluxEnabled(enabled: Boolean) = enqueue {
        // Effective config (the demo exit counts as complete).
        if (enabled && hasSession() && !OpenFluxEndpoint.resolve(preferences).complete) {
            throw IllegalStateException("Укажите адрес и токен OpenFlux")
        }
        // Disabling during a live OpenFlux session: verify the stored mode can connect BEFORE
        // anything changes, so the tunnel isn't dropped into "no servers" (pref stays on).
        if (!enabled && hasOpenFluxSession() && !storedModeReady()) {
            throw IllegalStateException(MSG_LEAVE_OPENFLUX_NO_SERVERS)
        }
        preferences.setOpenfluxEnabled(enabled)
        // Leaving OpenFlux: a kept refusal reason (busy / expired / …) is no longer relevant.
        if (!enabled) MyVpnService.clearOpenFluxStatus()
        if (hasSession() && (enabled || currentTarget() == Target.OpenFlux)) reconnectLocked()
    }

    /** Switch to Auto (balancer). Live-applies under the running tunnel if there is one. */
    fun setAuto() = enqueue {
        when (leaveOpenFlux { autoHasCandidates() }) {
            Leave.LEFT -> {
                preferences.setAutoMode(true)
                if (hasOpenFluxSession()) reconnectLocked()
                return@enqueue
            }
            Leave.ABORTED -> {
                preferences.setAutoMode(true)
                _errors.trySend(MSG_LEAVE_OPENFLUX_NO_SERVERS)
                return@enqueue
            }
            Leave.NOT_INVOLVED -> Unit
        }
        if (hasSession() && currentTarget() != Target.Auto) {
            // Builds the balancer spec first — throws (no servers…) BEFORE the pref is written,
            // so a failed switch can't leave "Авто" stored while the tunnel stays manual.
            connectVpnUseCase.switchToAuto()
            lastRequested = Target.Auto
        }
        preferences.setAutoMode(true)
    }

    /** Pin [server] → Manual mode. Live hot-switches the running tunnel if needed. */
    fun pin(server: ServerProfile) = enqueue {
        if (VlessUriParser.parse(server.vlessUri) == null) {
            throw IllegalStateException("Не удаётся разобрать конфигурацию сервера ${server.name}")
        }
        when (leaveOpenFlux { configRepository.getServerById(server.id) != null }) {
            Leave.LEFT -> {
                preferences.setAutoMode(false)
                configRepository.selectServer(server.id)
                if (hasOpenFluxSession()) reconnectLocked()
                return@enqueue
            }
            Leave.ABORTED -> {
                // The server vanished (e.g. subscription refresh): the pin can't be stored, and
                // storing Manual without a pin would only make the next connect fail.
                _errors.trySend(MSG_LEAVE_OPENFLUX_NO_SERVERS)
                return@enqueue
            }
            Leave.NOT_INVOLVED -> Unit
        }
        val target = Target.Server(server.id)
        if (hasSession() && currentTarget() != target) {
            connectVpnUseCase.switchServer(server.id)
            lastRequested = target
        }
        preferences.setAutoMode(false)
        configRepository.selectServer(server.id)
    }

    /**
     * Start a new session in the stored mode. Caller must have obtained VPN permission.
     * @throws IllegalStateException when in Manual mode with no pinned server, or no servers,
     *         or in OpenFlux mode without URL/token.
     */
    suspend fun connect() = mutex.withLock { connectLocked() }

    /** Body of [connect]; caller holds [mutex]. OpenFlux (if enabled) wins over Auto/Manual. */
    private suspend fun connectLocked() {
        if (preferences.openfluxEnabled.first()) {
            // Validates URL/token itself ("Укажите адрес и токен OpenFlux").
            connectVpnUseCase.connectOpenFlux()
            lastRequested = Target.OpenFlux
        } else if (preferences.autoMode.first()) {
            connectVpnUseCase.connectAuto()
            lastRequested = Target.Auto
        } else {
            val pinned = configRepository.getSelectedServer()
                ?: throw IllegalStateException("Сервер не выбран")
            connectVpnUseCase(pinned)
            lastRequested = Target.Server(pinned.id)
        }
        connectPendingSince = System.currentTimeMillis()
    }

    fun disconnect() = enqueue {
        connectPendingSince = 0L
        connectVpnUseCase.disconnect()
    }
}
