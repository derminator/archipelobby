package com.github.derminator.archipelobby.multiserver

import com.github.derminator.archipelobby.data.Room
import com.github.derminator.archipelobby.data.RoomRepository
import com.github.derminator.archipelobby.generator.PythonScriptRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
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
            import sys
            import time

            port = int(sys.argv[sys.argv.index("--port") + 1])
            time.sleep(0.75)
            with socket.socket() as server:
                server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                server.bind(("127.0.0.1", port))
                server.listen()
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
}
