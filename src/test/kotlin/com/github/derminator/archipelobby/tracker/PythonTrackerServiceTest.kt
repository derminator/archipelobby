package com.github.derminator.archipelobby.tracker

import com.github.derminator.archipelobby.generator.PythonScriptRunner
import com.github.derminator.archipelobby.multiserver.InternalToken
import com.github.derminator.archipelobby.multiserver.MultiServerProperties
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.core.io.DefaultResourceLoader
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PythonTrackerServiceTest {
    private val runner = mock(PythonScriptRunner::class.java)
    private val properties = MultiServerProperties(internalBaseUrl = "http://localhost:8080", internalToken = "test-token")
    private val service = PythonTrackerService(
        runner, DefaultResourceLoader(), properties, InternalToken(properties), "Archipelago/Generate.py",
    ).apply { init() }

    private fun <T> eqNotNull(value: T): T = org.mockito.ArgumentMatchers.eq(value) ?: value

    @AfterEach
    fun cleanup() = service.cleanup()

    @Test
    fun `script failures are visible and never cached`(): Unit = runBlocking {
        `when`(runner.runWithEnvironment(anyString(), anyMap(), anyString(), anyString(), anyString()))
            .thenReturn("""{"players":[],"error":"Failed to read save"}""")

        assertEquals("Failed to read save", service.getTrackerData(1)?.error)
        assertEquals("Failed to read save", service.getTrackerData(1)?.error)
        verify(runner, times(2)).runWithEnvironment(anyString(), anyMap(), anyString(), anyString(), anyString())
    }

    @Test
    fun `success is cached and the bearer token is passed only in the environment`(): Unit = runBlocking {
        `when`(runner.runWithEnvironment(anyString(), anyMap(), anyString(), anyString(), anyString()))
            .thenReturn("""{"players":[]}""")

        assertNotNull(service.getTrackerData(1))
        assertNotNull(service.getTrackerData(1))
        verify(runner).runWithEnvironment(
            anyString(), eqNotNull(mapOf("ARCHIPELOBBY_SPRING_TOKEN" to "test-token")),
            anyString(), eqNotNull("http://localhost:8080"), eqNotNull("1"),
        )
    }

    @Test
    fun `cache evicts old rooms after reaching its size limit`(): Unit = runBlocking {
        `when`(runner.runWithEnvironment(anyString(), anyMap(), anyString(), anyString(), anyString()))
            .thenReturn("""{"players":[]}""")

        for (roomId in 1L..257L) service.getTrackerData(roomId)
        service.getTrackerData(1)
        verify(runner, times(258)).runWithEnvironment(anyString(), anyMap(), anyString(), anyString(), anyString())
    }
}
