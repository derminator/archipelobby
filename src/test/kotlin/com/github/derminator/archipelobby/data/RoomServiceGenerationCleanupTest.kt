package com.github.derminator.archipelobby.data

import com.github.derminator.archipelobby.createDownloadZip

import com.github.derminator.archipelobby.discord.DiscordService
import com.github.derminator.archipelobby.generator.ArchipelagoGeneratorService
import com.github.derminator.archipelobby.generator.GameCatalogService
import com.github.derminator.archipelobby.generator.GeneratedGame
import com.github.derminator.archipelobby.multiserver.MultiServerManager
import com.github.derminator.archipelobby.multiserver.SaveDataService
import com.github.derminator.archipelobby.storage.InMemoryUploadsService
import com.github.derminator.archipelobby.storage.UploadsService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomServiceGenerationCleanupTest {

    @Test
    fun `upload game deletes first file when walkthrough storage fails`() = runBlocking {
        val roomRepository = mock(RoomRepository::class.java)
        val entryRepository = mock(EntryRepository::class.java)
        val discordService = mock(DiscordService::class.java)
        val uploads = TrackingUploads(failOnSave = 2)
        val room = Room(1L, 3L, "room")
        `when`(roomRepository.findById(1L)).thenReturn(Mono.just(room), Mono.just(room))
        `when`(entryRepository.findByRoomId(1L)).thenReturn(
            Flux.just(Entry(4L, 1L, 2L, "player", "game", "yaml")),
        )
        `when`(discordService.isAdminOfGuild(2L, 3L)).thenReturn(true)
        val service = RoomService(
            roomRepository,
            entryRepository,
            mock(EntryPatchFileRepository::class.java),
            mock(ApWorldRepository::class.java),
            discordService,
            uploads,
            mock(ArchipelagoGeneratorService::class.java),
            mock(GameCatalogService::class.java),
            mock(MultiServerManager::class.java),
            mock(SaveDataService::class.java),
        )
        val zip = createDownloadZip(
            listOf(
                "game.archipelago" to byteArrayOf(1),
                "Spoiler.txt" to byteArrayOf(2),
            ),
            maxUncompressedBytes = 1024,
            maxArchiveBytes = 1024,
            maxEntries = 4,
        )

        assertThrows<IllegalStateException> { service.uploadGame(1L, 2L, zip, "game.zip") }

        assertEquals(listOf("stored-1"), uploads.deletedPaths)
    }

    @Test
    fun `aggregate generation input is rejected while loading before generator invocation`() = runBlocking {
        val roomRepository = mock(RoomRepository::class.java)
        val entryRepository = mock(EntryRepository::class.java)
        val entryPatchRepository = mock(EntryPatchFileRepository::class.java)
        val apWorldRepository = mock(ApWorldRepository::class.java)
        val discordService = mock(DiscordService::class.java)
        val uploads = InMemoryUploadsService(maxStorageBytes = 1024)
        val firstPath = uploads.saveFile(ByteArray(4), "first.yaml")
        val secondPath = uploads.saveFile(ByteArray(4), "second.yaml")
        val room = Room(1L, 3L, "room")
        val lockedRoom = room.copy(generatedGameFilePath = Room.GENERATING_SENTINEL)
        var generatorInvoked = false
        val generator = object : ArchipelagoGeneratorService {
            override suspend fun generate(
                yamlFiles: Map<String, ByteArray>,
                apWorldFiles: Map<String, ByteArray>,
            ): GeneratedGame {
                generatorInvoked = true
                return GeneratedGame(byteArrayOf(), byteArrayOf())
            }

            override suspend fun getLocationCount(
                yamlContent: ByteArray,
                apWorldContents: Map<String, ByteArray>,
            ): Int = 0
        }

        `when`(roomRepository.findById(1L)).thenReturn(Mono.just(room), Mono.just(lockedRoom))
        `when`(entryRepository.findByRoomId(1L)).thenReturn(
            Flux.just(
                Entry(4L, 1L, 2L, "first", "game", firstPath),
                Entry(5L, 1L, 2L, "second", "game", secondPath),
            ),
        )
        `when`(apWorldRepository.findByRoomId(1L)).thenReturn(Flux.empty())
        `when`(discordService.isAdminOfGuild(2L, 3L)).thenReturn(true)
        `when`(roomRepository.save(any(Room::class.java))).thenReturn(Mono.just(lockedRoom), Mono.just(room))
        val service = RoomService(
            roomRepository,
            entryRepository,
            entryPatchRepository,
            apWorldRepository,
            discordService,
            uploads,
            generator,
            mock(GameCatalogService::class.java),
            mock(MultiServerManager::class.java),
            mock(SaveDataService::class.java),
            maxGenerationInputBytes = 7,
        )

        assertThrows<org.springframework.web.server.ResponseStatusException> {
            runBlocking { service.generateGame(1L, 2L) }
        }
        assertFalse(generatorInvoked)
    }

    @Test
    fun `patch rollback retains an artifact when its metadata deletion fails`() = runBlocking {
        val roomRepository = mock(RoomRepository::class.java)
        val entryRepository = mock(EntryRepository::class.java)
        val patchRepository = mock(EntryPatchFileRepository::class.java)
        val apWorldRepository = mock(ApWorldRepository::class.java)
        val discordService = mock(DiscordService::class.java)
        val uploads = InMemoryUploadsService(maxStorageBytes = 4096)
        val yamlPath = uploads.saveFile("yaml".toByteArray(), "player.yaml")
        val room = Room(1L, 3L, "room")
        val locked = room.copy(generatedGameFilePath = Room.GENERATING_SENTINEL)
        val persisted = locked.copy(generatedGameFilePath = "committed-game", walkthroughFilePath = "committed-walk")
        val entry = Entry(4L, 1L, 2L, "player", "game", yamlPath)
        val generator = object : ArchipelagoGeneratorService {
            override suspend fun generate(yamlFiles: Map<String, ByteArray>, apWorldFiles: Map<String, ByteArray>) =
                GeneratedGame(
                    "game".toByteArray(),
                    "walk".toByteArray(),
                    linkedMapOf(
                        "AP_seed_P1_player.apz3" to byteArrayOf(1),
                        "AP_seed_P1_player.apz4" to byteArrayOf(2),
                    ),
                )
            override suspend fun getLocationCount(yamlContent: ByteArray, apWorldContents: Map<String, ByteArray>) = 0
        }
        `when`(roomRepository.findById(1L)).thenReturn(Mono.just(room), Mono.just(persisted))
        `when`(entryRepository.findByRoomId(1L)).thenReturn(Flux.just(entry))
        `when`(apWorldRepository.findByRoomId(1L)).thenReturn(Flux.empty())
        `when`(discordService.isAdminOfGuild(2L, 3L)).thenReturn(true)
        `when`(roomRepository.save(any(Room::class.java))).thenReturn(
            Mono.just(locked), Mono.just(persisted), Mono.just(room),
        )
        `when`(patchRepository.save(any(EntryPatchFile::class.java))).thenReturn(
            Mono.just(EntryPatchFile(9L, 4L, "first", "first")),
            Mono.error(IllegalStateException("patch database unavailable")),
        )
        `when`(patchRepository.deleteByFilePath(anyString())).thenReturn(
            Mono.error(IllegalStateException("patch delete unavailable")),
            Mono.empty(),
        )
        val service = RoomService(
            roomRepository, entryRepository, patchRepository, apWorldRepository, discordService, uploads, generator,
            mock(GameCatalogService::class.java), mock(MultiServerManager::class.java), mock(SaveDataService::class.java),
        )

        assertThrows<IllegalStateException> { service.generateGame(1L, 2L) }

        val patches = ArgumentCaptor.forClass(EntryPatchFile::class.java)
        verify(patchRepository, times(2)).save(patches.capture())
        val firstPatch = patches.allValues[0]
        val secondPatch = patches.allValues[1]
        verify(patchRepository).deleteByFilePath(firstPatch.filePath)
        verify(patchRepository).deleteByFilePath(secondPatch.filePath)
        assertTrue(uploads.fileExists(firstPatch.filePath))
        assertFalse(uploads.fileExists(secondPatch.filePath))
    }

    @Test
    fun `non-optimistic final save failure clears sentinel and deletes generated files`() = runBlocking {
        val roomRepository = mock(RoomRepository::class.java)
        val entryRepository = mock(EntryRepository::class.java)
        val entryPatchRepository = mock(EntryPatchFileRepository::class.java)
        val apWorldRepository = mock(ApWorldRepository::class.java)
        val discordService = mock(DiscordService::class.java)
        val gameCatalogService = mock(GameCatalogService::class.java)
        val multiServerManager = mock(MultiServerManager::class.java)
        val saveDataService = mock(SaveDataService::class.java)
        val uploads = InMemoryUploadsService(maxStorageBytes = 1024)
        val yamlPath = uploads.saveFile("yaml".toByteArray(), "player.yaml")
        val room = Room(1L, 3L, "room")
        val lockedRoom = room.copy(generatedGameFilePath = Room.GENERATING_SENTINEL)
        val entry = Entry(4L, 1L, 2L, "player", "game", yamlPath)
        val generator = object : ArchipelagoGeneratorService {
            override suspend fun generate(
                yamlFiles: Map<String, ByteArray>,
                apWorldFiles: Map<String, ByteArray>,
            ) = GeneratedGame("game".toByteArray(), "walkthrough".toByteArray())

            override suspend fun getLocationCount(
                yamlContent: ByteArray,
                apWorldContents: Map<String, ByteArray>,
            ): Int = 0
        }

        `when`(roomRepository.findById(1L)).thenReturn(Mono.just(room), Mono.just(lockedRoom))
        `when`(entryRepository.findByRoomId(1L)).thenReturn(Flux.just(entry))
        `when`(apWorldRepository.findByRoomId(1L)).thenReturn(Flux.empty())
        `when`(discordService.isAdminOfGuild(2L, 3L)).thenReturn(true)
        `when`(roomRepository.save(any(Room::class.java))).thenReturn(
            Mono.just(lockedRoom),
            Mono.error(IllegalStateException("database unavailable")),
            Mono.just(room),
        )
        val service = RoomService(
            roomRepository,
            entryRepository,
            entryPatchRepository,
            apWorldRepository,
            discordService,
            uploads,
            generator,
            gameCatalogService,
            multiServerManager,
            saveDataService,
        )

        assertThrows<IllegalStateException> { runBlocking { service.generateGame(1L, 2L) } }

        val rooms = ArgumentCaptor.forClass(Room::class.java)
        verify(roomRepository, times(3)).save(rooms.capture())
        val failedCommittedRoom = rooms.allValues[1]
        assertNull(rooms.allValues.last().generatedGameFilePath)
        assertFalse(uploads.fileExists(failedCommittedRoom.generatedGameFilePath!!))
        assertFalse(uploads.fileExists(failedCommittedRoom.walkthroughFilePath!!))
    }
}

private class TrackingUploads(private val failOnSave: Int) : UploadsService {
    private var saves = 0
    val deletedPaths = mutableListOf<String>()

    override suspend fun saveFile(content: ByteArray, filename: String): String {
        saves++
        if (saves == failOnSave) throw IllegalStateException("storage failed")
        return "stored-$saves"
    }

    override suspend fun getFile(filePath: String): ByteArray = error("not used")
    override suspend fun getFile(filePath: String, maxBytes: Long): ByteArray = error("not used")
    override suspend fun deleteFile(filePath: String) {
        deletedPaths += filePath
    }
    override suspend fun fileExists(filePath: String): Boolean = false
}
