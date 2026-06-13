package com.github.derminator.archipelobby.tracker

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Service

@Service
@ConditionalOnMissingBean(PythonTrackerService::class)
class NoOpTrackerService : TrackerService {
    override suspend fun getTrackerData(roomId: Long): TrackerData? = null
    override suspend fun getSlotLocations(roomId: Long, slot: Int): SlotLocations? = null
    override suspend fun sendLocationChecks(roomId: Long, slotName: String, locationIds: List<Long>): Boolean = false
    override fun invalidateCache(roomId: Long) {}
}
