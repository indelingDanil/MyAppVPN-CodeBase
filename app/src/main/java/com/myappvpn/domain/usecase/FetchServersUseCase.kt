package com.myappvpn.domain.usecase

import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.repository.ConfigRepository
import javax.inject.Inject

class FetchServersUseCase @Inject constructor(
    private val configRepository: ConfigRepository,
    private val preferences: AppPreferences
) {
    suspend operator fun invoke(): Result<Int> {
        val result = configRepository.fetchAndSaveServers()
        // Only stamp a refresh that actually delivered servers (0 = no enabled sources / empty
        // lists) — otherwise the "last updated" time would lie and delay the next refresh.
        if (result.getOrDefault(0) > 0) {
            preferences.setLastRefreshTime(System.currentTimeMillis())
        }
        return result
    }
}
