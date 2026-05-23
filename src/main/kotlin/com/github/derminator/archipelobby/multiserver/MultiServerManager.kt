package com.github.derminator.archipelobby.multiserver

interface MultiServerManager {
    suspend fun startServer(roomId: Long)
    suspend fun stopServer(roomId: Long)
    fun isRunning(roomId: Long): Boolean
}
