package com.myappvpn.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.repository.Offer
import com.myappvpn.data.repository.OffersRepository
import com.myappvpn.data.repository.SubscriptionRepository
import com.myappvpn.domain.usecase.FetchServersUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One-shot results of the "try for free" button. */
sealed interface OffersEvent {
    data object TrialAdded : OffersEvent
    data object TrialAlreadyAdded : OffersEvent
    data class TrialInvalid(val reason: String) : OffersEvent
}

@HiltViewModel
class OffersViewModel @Inject constructor(
    repository: OffersRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val fetchServersUseCase: FetchServersUseCase,
) : ViewModel() {

    val offers: StateFlow<List<Offer>> = repository.offers

    /** Every subscription URL the user already has (drives the trial button's "added" state). */
    val addedUrls: StateFlow<Set<String>> = subscriptionRepository.sources
        .map { list -> list.flatMapTo(HashSet()) { it.urls } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _events = Channel<OffersEvent>(Channel.BUFFERED)
    val events: Flow<OffersEvent> = _events.receiveAsFlow()

    init {
        repository.refreshIfStale()   // no-op after the first call in this process / when fresh
    }

    /**
     * Adds the offer's trial link as an enabled custom subscription named [name] (same path as the
     * manual add: validation + dedupe), then re-fetches servers like the Subscriptions screen does.
     */
    fun addTrial(offer: Offer, name: String) {
        val url = offer.trialUrl ?: return
        viewModelScope.launch {
            when (val r = subscriptionRepository.addCustomNamed(url, name)) {
                SubscriptionRepository.AddResult.Added -> {
                    _events.send(OffersEvent.TrialAdded)
                    // The user usually leaves this screen right away; don't let that cancel the
                    // refresh, or the new servers would only show up on the next scheduled one.
                    withContext(NonCancellable) { runCatching { fetchServersUseCase() } }
                }
                SubscriptionRepository.AddResult.AlreadyAdded -> _events.send(OffersEvent.TrialAlreadyAdded)
                is SubscriptionRepository.AddResult.Invalid -> _events.send(OffersEvent.TrialInvalid(r.reason))
            }
        }
    }
}
