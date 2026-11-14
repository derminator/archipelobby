package com.github.derminator.archipelobby.multiserver

interface MultiServerManager {
    suspend fun startServer(roomId: Long)
    suspend fun stopServer(roomId: Long)
    fun isRunning(roomId: Long): Boolean

    /**
     * The port the room's MultiServer is listening on, or null until its listener
     * is ready or after the process exits. Callers can use [isRunning] separately
     * when they need to distinguish a starting process from a stopped one.
     */
    fun getServerPort(roomId: Long): Int?
}
