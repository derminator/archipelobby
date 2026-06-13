package com.github.derminator.archipelobby.multiserver

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Service

@Service
@ConditionalOnMissingBean(ProcessMultiServerManager::class)
class NoOpMultiServerManager : MultiServerManager {
    override suspend fun startServer(roomId: Long) {}
    override suspend fun stopServer(roomId: Long) {}
    override fun isRunning(roomId: Long): Boolean = false
}
