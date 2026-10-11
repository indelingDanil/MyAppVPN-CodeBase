package com.myappvpn.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.model.DnsPreset
import com.myappvpn.data.model.DnsServer
import com.myappvpn.data.repository.DnsRepository
import com.myappvpn.util.DnsConfigInjector
import com.myappvpn.util.DnsValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DnsViewModel @Inject constructor(
    private val dnsRepository: DnsRepository,
) : ViewModel() {

    // Nullable initial values: null = not loaded yet (avoids flashing "empty"/defaults on open).
    val servers: StateFlow<List<DnsServer>?> = dnsRepository.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** "IPv6-ответы (AAAA)" switch: true = UseIP, false = UseIPv4. */
    val ipv6Enabled: StateFlow<Boolean?> = dnsRepository.queryStrategy
        .map { it == DnsConfigInjector.STRATEGY_IP }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Summary for the Settings row. */
    val summary: StateFlow<String?> = dnsRepository.servers
        .map { DnsRepository.summarize(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Error under the add field (or a toggle-limit error); null = none. */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    /** Non-blocking info after a successful add: the server was added DISABLED (enabled-limit hit). */
    private val _addedDisabled = MutableStateFlow(false)
    val addedDisabled: StateFlow<Boolean> = _addedDisabled.asStateFlow()

    fun clearMessages() {
        _addError.value = null
        _addedDisabled.value = false
    }

    /** Live warning for what is being typed (plain DNS / hostname DoH), or null. */
    fun warningFor(input: String): String? =
        if (input.isBlank()) null else DnsValidator.warningFor(input)

    /** [onSuccess] is invoked only when the server was actually added (UI clears the field then). */
    fun add(raw: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            handle(dnsRepository.add(raw), onSuccess)
        }
    }

    fun addPreset(preset: DnsPreset) {
        viewModelScope.launch {
            handle(dnsRepository.addPreset(preset)) {}
        }
    }

    private fun handle(result: DnsRepository.AddResult, onSuccess: () -> Unit) {
        when (result) {
            is DnsRepository.AddResult.Failed -> {
                _addedDisabled.value = false
                _addError.value = result.message
            }
            is DnsRepository.AddResult.Added -> {
                _addError.value = null
                _addedDisabled.value = !result.enabled
                onSuccess()
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch { dnsRepository.remove(id) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val err = dnsRepository.setEnabled(id, enabled)
            _addError.value = err
        }
    }

    fun move(id: String, up: Boolean) {
        viewModelScope.launch { dnsRepository.move(id, up) }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        viewModelScope.launch { dnsRepository.setIpv6Enabled(enabled) }
    }

    fun resetToDefault() {
        viewModelScope.launch {
            dnsRepository.resetToDefault()
            clearMessages()
        }
    }
}
