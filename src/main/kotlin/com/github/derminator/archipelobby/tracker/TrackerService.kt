package com.github.derminator.archipelobby.tracker

interface TrackerService {
    suspend fun getTrackerData(roomId: Long): TrackerData?
}
