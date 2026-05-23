package com.github.derminator.archipelobby.multiserver

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "archipelobby.multiserver")
data class MultiServerProperties(
    val enabled: Boolean = false,
    val portRangeStart: Int = 38281,
    val portRangeEnd: Int = 38380,
    val host: String = "0.0.0.0",
    val displayHost: String = "localhost",
    val scriptPath: String = "Archipelago/MultiServer.py",
)
