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
        if (result.isSuccess) {
            preferences.setLastRefreshTime(System.currentTimeMillis())
        }
        return result
    }
}
