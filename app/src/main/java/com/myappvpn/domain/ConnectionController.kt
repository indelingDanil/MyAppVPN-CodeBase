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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** FIFO of fire-and-forget operations (setAuto / pin / disconnect). */
    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val _errors = Channel<String>(Channel.BUFFERED)
    /** User-facing errors from background mode switches (delivered once, buffered until collected). */
    val errors: Flow<String> = _errors.receiveAsFlow()

    init {
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
        null -> null
    }

    /** Switch to Auto (balancer). Live-applies under the running tunnel if there is one. */
    fun setAuto() = enqueue {
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
     * @throws IllegalStateException when in Manual mode with no pinned server, or no servers.
     */
    suspend fun connect() = mutex.withLock {
        if (preferences.autoMode.first()) {
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
