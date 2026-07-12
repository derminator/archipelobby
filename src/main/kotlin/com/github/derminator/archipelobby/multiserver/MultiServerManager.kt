package com.github.derminator.archipelobby.multiserver

interface MultiServerManager {
    suspend fun startServer(roomId: Long)
    suspend fun stopServer(roomId: Long)
    fun isRunning(roomId: Long): Boolean

    /**
     * The port the room's MultiServer is listening on, or null when no server is
     * running for the room. Only returns a port while the backing process is
     * alive, so callers get an atomic check and don't need a separate
     * [isRunning] call.
     */
    fun getServerPort(roomId: Long): Int?
}
