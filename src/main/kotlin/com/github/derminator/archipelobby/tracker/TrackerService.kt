package com.github.derminator.archipelobby.tracker

interface TrackerService {
    suspend fun getTrackerData(roomId: Long): TrackerData?
    suspend fun getSlotLocations(roomId: Long, slot: Int): SlotLocations?
    suspend fun sendLocationChecks(roomId: Long, slotName: String, locationIds: List<Long>): Boolean
    fun invalidateCache(roomId: Long)
}
