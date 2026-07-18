package com.github.derminator.archipelobby.multiserver

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "archipelobby.multiserver")
data class MultiServerProperties(
    // Whether the process-backed MultiServer manager is active. Consumed by the
    // @ConditionalOnProperty on ProcessMultiServerManager; declared here so the
    // key resolves as a known configuration property.
    val enabled: Boolean = false,
    val portRangeStart: Int = 38281,
    val portRangeEnd: Int = 38380,
    val host: String = "127.0.0.1",
    val scriptPath: String = "Archipelago/MultiServer.py",
    val wrapperScriptPath: String = "python/multiserver_wrapper.py",
    val internalBaseUrl: String = "http://localhost:8080",
    // Shared secret for the internal wrapper<->Spring API. Blank (the default)
    // makes InternalToken generate a random per-process token instead.
    val internalToken: String = "",
)
