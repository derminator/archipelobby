package com.github.derminator.archipelobby.multiserver

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "archipelobby.multiserver")
data class MultiServerProperties(
    val portRangeStart: Int = 38281,
    val portRangeEnd: Int = 38380,
    val host: String = "127.0.0.1",
    val scriptPath: String = "Archipelago/MultiServer.py",
    val wrapperScriptPath: String = "python/multiserver_wrapper.py",
    val internalBaseUrl: String = "http://localhost:8080",
)
