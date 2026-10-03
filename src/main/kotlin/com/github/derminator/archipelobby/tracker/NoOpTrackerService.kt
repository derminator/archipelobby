package com.github.derminator.archipelobby.tracker

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty("archipelobby.multiserver.enabled", havingValue = "false", matchIfMissing = true)
class NoOpTrackerService : TrackerService {
    override suspend fun getTrackerData(roomId: Long): TrackerData? = null
}
