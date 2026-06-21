package com.myappvpn.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.model.SubscriptionSource
import com.myappvpn.data.repository.SubscriptionRepository
import com.myappvpn.domain.usecase.FetchServersUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val fetchServersUseCase: FetchServersUseCase,
) : ViewModel() {

    val sources: StateFlow<List<SubscriptionSource>> = subscriptionRepository.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Validation/duplicate error for the add-subscription field; null = no error. */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    /** True while a refresh triggered by a subscription change is running. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun clearAddError() { _addError.value = null }

    fun addSubscription(url: String) {
        viewModelScope.launch {
            val error = subscriptionRepository.addCustom(url)
            _addError.value = error
            if (error == null) refreshPool()
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
        _refreshing.value = true
        runCatching { fetchServersUseCase() }
        _refreshing.value = false
    }
}
