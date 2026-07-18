package com.github.derminator.archipelobby.multiserver

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Secret shared between the Spring app and the multiserver wrapper Python
 * processes it spawns.
 *
 * Set `archipelobby.multiserver.internal-token` to a stable value when wrapper
 * processes can outlive the application process or requests can be served by
 * multiple application replicas. When unset, a random per-process token is
 * generated and invalidated on every restart.
 */
@Component
class InternalToken(properties: MultiServerProperties) {
    val value: String = properties.internalToken.ifBlank { UUID.randomUUID().toString() }
}
