package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.data.RoomService
import com.github.derminator.archipelobby.data.Room
import com.github.derminator.archipelobby.data.RoomWithEntries
import com.github.derminator.archipelobby.generator.GameCatalogService
import com.github.derminator.archipelobby.security.DiscordPrincipal
import com.github.derminator.archipelobby.storage.UploadsService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.doReturn
import org.springframework.web.server.ServerWebExchange
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.Semaphore

class RoomControllerResourceTest {

    @Test
    fun `download cleanup deletes archive and releases permit exactly once`() {
        val path = Files.createTempFile("archipelobby-cleanup-test-", ".zip")
        val permits = Semaphore(0)
        val archive = DownloadArchiveResource(
            path,
            java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ),
            permits,
        )

        archive.cleanup().block()
        archive.cleanup().block()

        assertFalse(Files.exists(path))
        assertEquals(1, permits.availablePermits())
    }

    @Test
    fun `download resources are not acquired until the response body is subscribed`() {
        val roomService = mock(RoomService::class.java)
        val uploadsService = mock(UploadsService::class.java)
        val gameCatalogService = mock(GameCatalogService::class.java)
        val exchange = mock(ServerWebExchange::class.java)
        val controller = RoomController(
            roomService,
            uploadsService,
            gameCatalogService,
            com.github.derminator.archipelobby.generator.GenerationJobLimiter(),
        )
        runBlocking {
            doReturn(RoomWithEntries(Room(1L, 3L, "test room"), emptyFlow(), true, emptyList()))
                .`when`(roomService).getRoom(1L, 2L)
        }

        val response = controller.downloadAll(1L, DiscordPrincipal(2L, "user"), exchange).block()

        assertNotNull(response?.body)
        verifyNoInteractions(uploadsService, exchange)
    }
}
