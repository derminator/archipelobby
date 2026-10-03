package com.github.derminator.archipelobby.multiserver

import com.github.derminator.archipelobby.data.Room
import com.github.derminator.archipelobby.data.RoomRepository
import com.github.derminator.archipelobby.generator.PythonScriptRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessMultiServerManagerTest {

    @Test
    fun `port is not published until the listener is ready`(@TempDir tempDir: Path) = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val script = tempDir.resolve("delayed_server.py")
        script.writeText(
            """
            import socket
            import os
            import sys
            import time

            port = int(sys.argv[sys.argv.index("--port") + 1])
            time.sleep(0.75)
            with socket.socket() as server:
                server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                server.bind(("127.0.0.1", port))
                server.listen()
                print("ARCHIPELOBBY_READY=" + os.environ["ARCHIPELOBBY_READY_TOKEN"], flush=True)
                time.sleep(10)
            """.trimIndent(),
        )
        val roomRepository = mock(RoomRepository::class.java)
        `when`(roomRepository.findById(1L)).thenReturn(
            Mono.just(Room(1L, 123L, "Ready test", generatedGameFilePath = "game.archipelago")),
        )
        val properties = MultiServerProperties(
            portRangeStart = port,
            portRangeEnd = port,
            host = "127.0.0.1",
            scriptPath = tempDir.resolve("MultiServer.py").toString(),
            wrapperScriptPath = script.toString(),
        )
        val manager = ProcessMultiServerManager(
            properties,
            roomRepository,
            PythonScriptRunner("python3"),
            InternalToken(properties),
        )

        try {
            manager.startServer(1L)

            assertTrue(manager.isRunning(1L))
            assertNull(manager.getServerPort(1L))
            val readyPort = withTimeout(5_000) {
                while (manager.getServerPort(1L) == null) delay(25)
                manager.getServerPort(1L)
            }
            assertEquals(port, readyPort)
        } finally {
            manager.stopServer(1L)
        }
    }

    @Test
    fun `occupied ports are not allocated`(@TempDir tempDir: Path) = runBlocking {
        ServerSocket(0).use { occupied ->
            val roomRepository = generatedRoomRepository(1L)
            val properties = MultiServerProperties(
                portRangeStart = occupied.localPort,
                portRangeEnd = occupied.localPort,
                bindHost = "127.0.0.1",
                scriptPath = tempDir.resolve("MultiServer.py").toString(),
                wrapperScriptPath = tempDir.resolve("unused.py").toString(),
            )
            val manager = manager(properties, roomRepository)

            assertFailsWith<ResponseStatusException> { manager.startServer(1L) }
            assertTrue(!manager.isRunning(1L))
        }
    }

    @Test
    fun `port remains allocated until stopped process exits`(@TempDir tempDir: Path) = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val script = tempDir.resolve("slow_stop_server.py")
        script.writeText(
            """
            import os
            import signal
            import socket
            import sys
            import time

            port = int(sys.argv[sys.argv.index("--port") + 1])
            server = socket.socket()
            server.bind(("127.0.0.1", port))
            server.listen()
            print("ARCHIPELOBBY_READY=" + os.environ["ARCHIPELOBBY_READY_TOKEN"], flush=True)

            def stop(*_):
                server.close()
                time.sleep(1.5)
                sys.exit(0)

            signal.signal(signal.SIGTERM, stop)
            time.sleep(10)
            """.trimIndent(),
        )
        val roomRepository = generatedRoomRepository(1L, 2L)
        val properties = MultiServerProperties(
            portRangeStart = port,
            portRangeEnd = port,
            host = "127.0.0.1",
            scriptPath = tempDir.resolve("MultiServer.py").toString(),
            wrapperScriptPath = script.toString(),
        )
        val manager = manager(properties, roomRepository)

        try {
            manager.startServer(1L)
            withTimeout(5_000) {
                while (manager.getServerPort(1L) == null) delay(25)
            }
            val stopping = launch { manager.stopServer(1L) }
            delay(250)

            assertFailsWith<ResponseStatusException> { manager.startServer(2L) }
            stopping.join()
            manager.startServer(2L)
            assertTrue(manager.isRunning(2L))
        } finally {
            manager.stopServer(1L)
            manager.stopServer(2L)
        }
    }

    @Test
    fun `bind host defaults to the established host setting`() {
        assertEquals("0.0.0.0", MultiServerProperties(host = "0.0.0.0").bindHost)
    }

    private fun generatedRoomRepository(vararg roomIds: Long): RoomRepository {
        val repository = mock(RoomRepository::class.java)
        roomIds.forEach { roomId ->
            `when`(repository.findById(roomId)).thenReturn(
                Mono.just(Room(roomId, 123L, "Room $roomId", generatedGameFilePath = "game.archipelago")),
            )
        }
        return repository
    }

    private fun manager(properties: MultiServerProperties, roomRepository: RoomRepository) =
        ProcessMultiServerManager(
            properties,
            roomRepository,
            PythonScriptRunner("python3"),
            InternalToken(properties),
        )
}
