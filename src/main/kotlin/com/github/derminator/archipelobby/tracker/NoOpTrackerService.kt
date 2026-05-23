package com.github.derminator.archipelobby.tracker

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Service

@Service
@ConditionalOnMissingBean(PythonTrackerService::class)
class NoOpTrackerService : TrackerService {
    override suspend fun getTrackerData(roomId: Long): TrackerData? = null
}
