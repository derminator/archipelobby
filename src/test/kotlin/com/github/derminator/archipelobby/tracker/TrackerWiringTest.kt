package com.github.derminator.archipelobby.tracker

import com.github.derminator.archipelobby.generator.PythonScriptRunner
import com.github.derminator.archipelobby.multiserver.InternalToken
import com.github.derminator.archipelobby.multiserver.MultiServerProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrackerWiringTest {
    private val contextRunner = ApplicationContextRunner()
        // Register NoOp first to make the conditional ordering regression observable.
        .withUserConfiguration(NoOpTrackerService::class.java, PythonTrackerService::class.java)
        .withBean(PythonScriptRunner::class.java, { mock(PythonScriptRunner::class.java) })
        .withBean(MultiServerProperties::class.java, { MultiServerProperties(internalToken = "test-token") })
        .withBean(InternalToken::class.java, { InternalToken(MultiServerProperties(internalToken = "test-token")) })

    @Test
    fun `enabled multiserver registers only the Python tracker`() {
        contextRunner.withPropertyValues("archipelobby.multiserver.enabled=true").run { context ->
            assertEquals(1, context.getBeansOfType(TrackerService::class.java).size)
            assertIs<PythonTrackerService>(context.getBean(TrackerService::class.java))
        }
    }

    @Test
    fun `disabled multiserver registers only the no-op tracker`() {
        contextRunner.withPropertyValues("archipelobby.multiserver.enabled=false").run { context ->
            assertEquals(1, context.getBeansOfType(TrackerService::class.java).size)
            assertIs<NoOpTrackerService>(context.getBean(TrackerService::class.java))
        }
    }

    @Test
    fun `missing multiserver property defaults to the no-op tracker`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(TrackerService::class.java).size)
            assertIs<NoOpTrackerService>(context.getBean(TrackerService::class.java))
        }
    }
}
