package com.github.derminator.archipelobby.tracker

import com.github.derminator.archipelobby.multiserver.MultiServerManager
import com.github.derminator.archipelobby.multiserver.SaveDataService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SavedTrackerServiceTest {
    private val saves = mock(SaveDataService::class.java)
    private val manager = mock(MultiServerManager::class.java)
    private val service = SavedTrackerService(saves, manager)

    private fun snapshot(status: Int = 20, done: Int = 1, total: Int = 2): ByteArray = """
        {"formatVersion":1,"state":{"type":"dict","items":[]},"tracker":{"players":[
          {"slot":1,"name":"Alice","game":"Minecraft","checksDone":$done,"checksTotal":$total,"statusCode":$status}
        ]}}
    """.trimIndent().toByteArray()

    @Test
    fun `maps saved progress and all statuses without decoding Python state`(): Unit = runBlocking {
        `when`(manager.isRunning(42)).thenReturn(true)
        for ((status, label) in mapOf(
            0 to "Disconnected", 5 to "Connected", 10 to "Ready",
            20 to "Playing", 30 to "Goal Completed", 99 to "Unknown",
        )) {
            `when`(saves.get(42)).thenReturn(snapshot(status))
            val data = service.getTrackerData(42)
            assertNull(data.error)
            assertEquals(PlayerProgress(1, "Alice", "Minecraft", 1, 2, label), data.players.single())
            assertEquals(50.0, data.players.single().percent)
        }
    }

    @Test
    fun `zero-check games have zero percent`(): Unit = runBlocking {
        `when`(manager.isRunning(42)).thenReturn(true)
        `when`(saves.get(42)).thenReturn(snapshot(0, 0, 0))
        assertEquals(0.0, service.getTrackerData(42).players.single().percent)
    }

    @Test
    fun `stopped rooms do not read saves`(): Unit = runBlocking {
        val data = service.getTrackerData(42)
        assertEquals("Tracker unavailable while the server is stopped.", data.error)
        assertEquals(emptyList(), data.players)
        verifyNoInteractions(saves)
    }

    @Test
    fun `reads fresh saves and handles stop and restart without caching`(): Unit = runBlocking {
        `when`(manager.isRunning(42)).thenReturn(true, true, false, true)
        `when`(saves.get(42)).thenReturn(snapshot(done = 0), snapshot(done = 1), snapshot(done = 2))
        assertEquals(0, service.getTrackerData(42).players.single().checksDone)
        assertEquals(1, service.getTrackerData(42).players.single().checksDone)
        assertNotNull(service.getTrackerData(42).error)
        assertEquals(2, service.getTrackerData(42).players.single().checksDone)
        verify(saves, times(3)).get(42)
    }

    @Test
    fun `missing malformed and unsupported saves are visible and retried`(): Unit = runBlocking {
        `when`(manager.isRunning(42)).thenReturn(true)
        val malformed = listOf(
            null, "invalid".toByteArray(), "{}".toByteArray(),
            snapshot().toString(Charsets.UTF_8).replace("\"formatVersion\":1", "\"formatVersion\":2").toByteArray(),
            """{"formatVersion":1,"tracker":{}}""".toByteArray(),
            """{"formatVersion":1,"tracker":{"players":null}}""".toByteArray(),
        )
        for (bytes in malformed) {
            `when`(saves.get(42)).thenReturn(bytes)
            val data = service.getTrackerData(42)
            assertEquals("Tracker unavailable. Please try again.", data.error)
            assertEquals(emptyList(), data.players)
        }
        `when`(saves.get(42)).thenReturn(snapshot())
        assertNull(service.getTrackerData(42).error)
    }

    @Test
    fun `repository failures are unavailable and cancellation propagates`(): Unit = runBlocking {
        `when`(manager.isRunning(42)).thenReturn(true)
        `when`(saves.get(42)).thenThrow(IllegalStateException("database offline"), CancellationException("cancelled"))
        assertNotNull(service.getTrackerData(42).error)
        assertFailsWith<CancellationException> { service.getTrackerData(42) }
    }
}
