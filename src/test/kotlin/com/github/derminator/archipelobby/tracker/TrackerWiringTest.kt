package com.github.derminator.archipelobby.tracker

import com.github.derminator.archipelobby.multiserver.MultiServerManager
import com.github.derminator.archipelobby.multiserver.SaveDataService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrackerWiringTest {
    private val contextRunner = ApplicationContextRunner()
        // Register NoOp first to make the conditional ordering regression observable.
        .withUserConfiguration(NoOpTrackerService::class.java, SavedTrackerService::class.java)
        .withBean(SaveDataService::class.java, { mock(SaveDataService::class.java) })
        .withBean(MultiServerManager::class.java, { mock(MultiServerManager::class.java) })

    @Test
    fun `enabled multiserver registers only the saved tracker`() {
        contextRunner.withPropertyValues("archipelobby.multiserver.enabled=true").run { context ->
            assertEquals(1, context.getBeansOfType(TrackerService::class.java).size)
            assertIs<SavedTrackerService>(context.getBean<TrackerService>())
        }
    }

    @Test
    fun `disabled multiserver registers only the no-op tracker`() {
        contextRunner.withPropertyValues("archipelobby.multiserver.enabled=false").run { context ->
            assertEquals(1, context.getBeansOfType(TrackerService::class.java).size)
            assertIs<NoOpTrackerService>(context.getBean<TrackerService>())
        }
    }

    @Test
    fun `missing multiserver property defaults to the no-op tracker`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(TrackerService::class.java).size)
            assertIs<NoOpTrackerService>(context.getBean<TrackerService>())
        }
    }
}
