package com.github.derminator.archipelobby.multiserver

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Per-process secret shared between the Spring app and the multiserver wrapper
 * Python processes it spawns. Regenerated on every restart so leaked tokens do
 * not survive a redeploy.
 */
@Component
class InternalToken {
    val value: String = UUID.randomUUID().toString()
}
