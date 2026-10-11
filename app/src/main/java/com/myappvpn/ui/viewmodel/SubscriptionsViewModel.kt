package com.myappvpn.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.model.SubscriptionSource
import com.myappvpn.data.repository.SubscriptionRepository
import com.myappvpn.domain.usecase.FetchServersUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot UI events (consumed once, survive the screen being briefly off-lifecycle). */
sealed interface SubscriptionsEvent {
    /** The subscription was actually added — the UI clears the URL field. */
    data object Added : SubscriptionsEvent
    /** Re-fetching servers after a change failed (network / all sources unreachable). */
    data object RefreshFailed : SubscriptionsEvent
}

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val fetchServersUseCase: FetchServersUseCase,
) : ViewModel() {

    /** null = not loaded yet (distinguishes "loading" from "empty list"). */
    val sources: StateFlow<List<SubscriptionSource>?> = subscriptionRepository.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Validation/duplicate error for the add-subscription field; null = no error. */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    /** True while at least one refresh triggered by a subscription change is running. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // Number of in-flight refreshes. viewModelScope runs on Main, so a plain Int is safe; a
    // counter (instead of a boolean set/reset per call) keeps the spinner on until the LAST
    // overlapping refresh finishes.
    private var activeRefreshes = 0

    private val _events = Channel<SubscriptionsEvent>(Channel.BUFFERED)
    val events: Flow<SubscriptionsEvent> = _events.receiveAsFlow()

    fun clearAddError() { _addError.value = null }

    fun addSubscription(url: String) {
        viewModelScope.launch {
            val error = subscriptionRepository.addCustom(url)
            _addError.value = error
            if (error == null) {
                _events.send(SubscriptionsEvent.Added)
                refreshPool()
            }
        }
    }

    fun removeSubscription(id: String) {
        viewModelScope.launch {
            subscriptionRepository.removeCustom(id)
            refreshPool()
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            subscriptionRepository.setEnabled(id, enabled)
            refreshPool()
        }
    }

    /** Re-fetch servers so the pool reflects the new set of enabled sources immediately. */
    private suspend fun refreshPool() {
        activeRefreshes++
        _refreshing.value = true
        try {
            val failure = try {
                fetchServersUseCase().exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (failure != null && failure !is CancellationException) {
                _events.send(SubscriptionsEvent.RefreshFailed)
            }
        } finally {
            activeRefreshes--
            if (activeRefreshes == 0) _refreshing.value = false
        }
    }
}
