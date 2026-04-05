package com.myappvpn.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.repository.ConfigRepository
import com.myappvpn.domain.usecase.FetchServersUseCase
import com.myappvpn.domain.usecase.PingProgress
import com.myappvpn.domain.usecase.PingServersUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val fetchServersUseCase: FetchServersUseCase,
    private val pingServersUseCase: PingServersUseCase
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val servers: StateFlow<List<ServerProfile>> = combine(
        configRepository.servers,
        _searchQuery
    ) { servers, query ->
        if (query.isBlank()) servers
        else servers.filter {
            it.country.contains(query, ignoreCase = true) ||
            it.name.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pingProgress = MutableStateFlow(0f)
    val pingProgress: StateFlow<Float> = _pingProgress.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _bestServerId = MutableSharedFlow<String?>()
    val bestServerId: SharedFlow<String?> = _bestServerId.asSharedFlow()

    init {
        loadServersIfEmpty()
    }

    private fun loadServersIfEmpty() {
        viewModelScope.launch {
            val current = configRepository.servers.first()
            if (current.isEmpty()) {
                refreshServers()
            }
        }
    }

    fun refreshServers() {
        viewModelScope.launch {
            _isLoading.value = true
            fetchServersUseCase()
                .onSuccess { count ->
                    // servers will update automatically via Flow
                }
                .onFailure { e ->
                    _error.emit("Failed to load servers: ${e.message}")
                }
            _isLoading.value = false
        }
    }

    fun pingAllServers() {
        viewModelScope.launch {
            _isPinging.value = true
            _pingProgress.value = 0f

            pingServersUseCase.invoke().collect { progress ->
                when (progress) {
                    is PingProgress.Started -> _pingProgress.value = 0f
                    is PingProgress.Progress -> _pingProgress.value = progress.fraction
                    is PingProgress.Done -> {
                        _pingProgress.value = 1f
                        _isPinging.value = false
                        _bestServerId.emit(progress.bestServerId)
                    }
                }
            }
        }
    }

    fun selectServer(server: ServerProfile) {
        viewModelScope.launch {
            configRepository.selectServer(server.id)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
