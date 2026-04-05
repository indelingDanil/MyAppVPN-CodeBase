package com.myappvpn.domain.usecase

import com.myappvpn.data.model.TrafficStats
import com.myappvpn.service.TrafficMonitor
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetTrafficStatsUseCase @Inject constructor(
    private val trafficMonitor: TrafficMonitor
) {
    operator fun invoke(): Flow<TrafficStats> = trafficMonitor.statsFlow
}
