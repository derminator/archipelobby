package com.github.derminator.archipelobby.multiserver

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Secret shared between the Spring app and the multiserver wrapper Python
 * processes it spawns.
 *
 * Set `archipelobby.multiserver.internal-token` to a stable value in any
 * deployment that runs multiple instances or rolls over on redeploy, so a
 * wrapper started by one instance keeps authenticating after the app is
 * redeployed or when a request is served by a different replica. When unset
 * (e.g. local dev) a random per-process token is generated, which is also
 * invalidated on every restart.
 */
@Component
class InternalToken(
    @Value("\${archipelobby.multiserver.internal-token:}") configuredToken: String,
) {
    val value: String = configuredToken.ifBlank { UUID.randomUUID().toString() }
}
