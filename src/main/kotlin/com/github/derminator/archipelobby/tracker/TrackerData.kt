package com.github.derminator.archipelobby.tracker

data class TrackerData(
    val players: List<PlayerProgress>,
)

data class PlayerProgress(
    val slot: Int,
    val name: String,
    val game: String,
    val checksDone: Int,
    val checksTotal: Int,
    val status: String,
) {
    val percent: Double
        get() = if (checksTotal > 0) checksDone.toDouble() / checksTotal * 100.0 else 0.0
}
