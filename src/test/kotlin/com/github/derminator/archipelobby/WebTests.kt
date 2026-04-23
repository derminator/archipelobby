package com.github.derminator.archipelobby

import com.github.derminator.archipelobby.data.*
import com.github.derminator.archipelobby.discord.DiscordService
import com.github.derminator.archipelobby.discord.GuildInfo
import com.github.derminator.archipelobby.discord.UserInfo
import com.github.derminator.archipelobby.generator.ArchipelagoGeneratorService
import com.github.derminator.archipelobby.security.DiscordPrincipal
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.`when`
import org.springframework.dao.OptimisticLockingFailureException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.*
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.web.reactive.function.BodyInserters
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@SpringBootTest
@EnableAutoConfiguration(
    exclude = [
        R2dbcAutoConfiguration::class,
    ]
)
class WebTests {

    @MockitoBean
    lateinit var discordService: DiscordService

    @MockitoBean
    lateinit var roomRepository: RoomRepository

    @MockitoBean
    lateinit var entryRepository: EntryRepository

    @MockitoBean
    lateinit var apWorldRepository: ApWorldRepository

    @MockitoBean
    lateinit var archipelagoGeneratorService: ArchipelagoGeneratorService

    @Autowired
    lateinit var context: ApplicationContext

    lateinit var webTestClient: WebTestClient

    private val testPrincipal = DiscordPrincipal(0L, "admin")

    @BeforeEach
    fun setup() = runBlocking {
        `when`(discordService.getGuildsForUser(anyLong())).thenReturn(emptyFlow())
        `when`(discordService.getAdminGuildsForUser(anyLong())).thenReturn(emptyFlow())
        `when`(discordService.isMemberOfAnyGuild(anyLong())).thenReturn(true)
        `when`(discordService.isMemberOfGuild(anyLong(), anyLong())).thenReturn(true)
        `when`(discordService.isAdminOfGuild(anyLong(), anyLong())).thenReturn(false)
        `when`(discordService.getUserInfo(anyLong())).thenReturn(UserInfo(0L, "test-user"))
        `when`(entryRepository.findByUserId(anyLong())).thenReturn(Flux.empty())
        `when`(entryRepository.countByRoomIdAndUserId(anyLong(), anyLong())).thenReturn(Mono.just(0L))
        `when`(apWorldRepository.findByRoomId(anyLong())).thenReturn(Flux.empty())

        webTestClient = WebTestClient.bindToApplicationContext(context)
            .apply(springSecurity())
            .configureClient()
            .build()
    }

    @Test
    fun `index page is accessible without authentication`() {
        webTestClient.get().uri("/")
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(body!!.contains("Login with Discord"))
                assert(!body.contains("Logged in as:"))
            }
    }

    @Test
    fun `index page shows username when authenticated`() {
        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .get().uri("/")
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(body!!.contains("Logged in as:"))
                assert(body.contains("admin"))
                assert(body.contains("Logout"))
            }
    }

    @Test
    fun `index page shows username when authenticated with form login`() {
        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    DiscordPrincipal(
                        0L,
                        "admin"
                    ), null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .get().uri("/")
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(body!!.contains("Logged in as:"))
                assert(body.contains("admin"))
            }
    }

    @Test
    fun `non-existent page shows 404 for authenticated user`() {
        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .get().uri("/this-page-does-not-exist")
            .header("Accept", "text/html")
            .exchange()
            .expectStatus().isNotFound
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(body!!.contains("404"))
                assert(body.contains("Page Not Found"))
            }
    }

    @Test
    fun `bad request shows generic error page`() {
        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            ),
        )
            .get().uri("/rooms/abc")
            .header("Accept", "text/html")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(body!!.contains("An error occurred"))
                assert(body.contains("Error - Archipelobby"))
            }
    }

    @Test
    fun `internal server error shows generic error page`() {
        `when`(roomRepository.findById(anyLong())).thenThrow(RuntimeException("Test exception"))

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .get().uri("/rooms/123")
            .header("Accept", "text/html")
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(body!!.contains("An error occurred"))
            }
    }

    @Test
    fun `non-existent page redirects to login for unauthenticated user`() {
        // Since we didn't permit the non-existent path, it should redirect to log in first
        webTestClient.get().uri("/this-page-does-not-exist")
            .header("Accept", "text/html")
            .exchange()
            .expectStatus().is3xxRedirection
            .expectHeader().valueMatches("Location", ".*/login")
    }

    @Test
    fun `adding entry with duplicate name returns error banner in room page`(): Unit = runBlocking {
        val roomId = 1L
        `when`(entryRepository.existsByRoomIdAndName(anyLong(), anyString())).thenReturn(Mono.just(true))
        `when`(roomRepository.findById(anyLong())).thenReturn(Mono.just(Room(roomId, 123, "Test Room")))
        `when`(discordService.isMemberOfGuild(anyLong(), anyLong())).thenReturn(true)
        `when`(entryRepository.findByRoomId(roomId)).thenReturn(Flux.empty())

        val bodyBuilder = MultipartBodyBuilder()
        bodyBuilder.part("yamlFile", "name: Duplicate Name\ngame: Test Game".toByteArray())
            .filename("test.yaml")

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .mutateWith(csrf())
            .post().uri("/rooms/$roomId/entries")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(bodyBuilder.build())
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(body!!.contains("class=\"error-banner\""))
                assert(body.contains("Conflict") || body.contains("already exists"))
            }
    }

    @Test
    fun `rooms page is accessible for user who is not admin in all joined guilds`(): Unit = runBlocking {
        val userId = 0L

        `when`(discordService.getGuildsForUser(userId)).thenReturn(
            flowOf(
                GuildInfo(1, "Guild 1"),
                GuildInfo(2, "Guild 2")
            )
        )
        `when`(discordService.getAdminGuildsForUser(userId)).thenReturn(emptyFlow())
        `when`(roomRepository.findByGuildId(anyLong())).thenReturn(Flux.empty())

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .get().uri("/rooms")
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(!body!!.contains("Guild 1"))
                assert(!body.contains("Guild 2"))
                assert(!body.contains("Guild 3"))
                assert(!body.contains("Create a Room"))
            }
    }

    @Test
    fun `rooms page is accessible with form login`(): Unit = runBlocking {
        val userId = 0L
        `when`(discordService.getGuildsForUser(userId)).thenReturn(emptyFlow())
        `when`(discordService.getAdminGuildsForUser(userId)).thenReturn(emptyFlow())
        `when`(entryRepository.findByUserId(userId)).thenReturn(Flux.empty())

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .get().uri("/rooms")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `session cookie is persistent after form login`() {
        val loginResult = webTestClient
            .mutateWith(csrf())
            .post().uri("/login")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("username", "admin").with("password", "password"))
            .exchange()
            .expectStatus().is3xxRedirection
            .expectBody<String>().returnResult()

        val sessionCookie = loginResult.responseCookies.entries
            .flatMap { it.value }
            .firstOrNull { it.maxAge.seconds > 0 }

        assert(sessionCookie != null) {
            "Expected a persistent session cookie with max-age > 0, but none was found. " +
                "Cookies: ${loginResult.responseCookies}"
        }
        assert(sessionCookie!!.maxAge >= WebSessionConfiguration.SESSION_DURATION) {
            "Expected max-age >= ${WebSessionConfiguration.SESSION_DURATION}, got ${sessionCookie.maxAge}"
        }

        // The session cookie must grant access to a protected resource
        webTestClient
            .get().uri("/rooms")
            .cookie(sessionCookie.name, sessionCookie.value)
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `deleteEntry redirects to room page for entry owner`(): Unit = runBlocking {
        val userId = 0L
        val roomId = 1L
        val entryId = 1L
        val room = Room(roomId, 123, "Test Room")
        val entry = Entry(entryId, roomId, userId, "Test Entry", "Test Game", "path/to/file.yaml")
        `when`(entryRepository.findById(entryId)).thenReturn(Mono.just(entry))
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(userId, 123)).thenReturn(false)
        `when`(entryRepository.deleteById(entryId)).thenReturn(Mono.empty())

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .mutateWith(csrf())
            .post().uri("/rooms/$roomId/entries/$entryId/delete")
            .exchange()
            .expectStatus().is3xxRedirection
            .expectHeader().valueMatches("Location", ".*/rooms/$roomId")
    }

    @Test
    fun `deleteEntry returns forbidden for non-owner non-admin`(): Unit = runBlocking {
        val userId = 0L
        val roomId = 1L
        val entryId = 1L
        val room = Room(roomId, 123, "Test Room")
        val entry = Entry(entryId, roomId, 999L, "Test Entry", "Test Game", "path/to/file.yaml")
        `when`(entryRepository.findById(entryId)).thenReturn(Mono.just(entry))
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(userId, 123)).thenReturn(false)

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .mutateWith(csrf())
            .post().uri("/rooms/$roomId/entries/$entryId/delete")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `deleteApWorld redirects to room page for apworld owner`(): Unit = runBlocking {
        val userId = 0L
        val roomId = 1L
        val apWorldId = 1L
        val room = Room(roomId, 123, "Test Room")
        val apWorld = ApWorld(apWorldId, roomId, userId, "test.apworld", "path/to/test.apworld")
        `when`(apWorldRepository.findById(apWorldId)).thenReturn(Mono.just(apWorld))
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(userId, 123)).thenReturn(false)
        `when`(apWorldRepository.deleteById(apWorldId)).thenReturn(Mono.empty())

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .mutateWith(csrf())
            .post().uri("/rooms/$roomId/apworlds/$apWorldId/delete")
            .exchange()
            .expectStatus().is3xxRedirection
            .expectHeader().valueMatches("Location", ".*/rooms/$roomId")
    }

    @Test
    fun `deleteApWorld returns forbidden for non-owner non-admin`(): Unit = runBlocking {
        val userId = 0L
        val roomId = 1L
        val apWorldId = 1L
        val room = Room(roomId, 123, "Test Room")
        val apWorld = ApWorld(apWorldId, roomId, 999L, "test.apworld", "path/to/test.apworld")
        `when`(apWorldRepository.findById(apWorldId)).thenReturn(Mono.just(apWorld))
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(userId, 123)).thenReturn(false)

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .mutateWith(csrf())
            .post().uri("/rooms/$roomId/apworlds/$apWorldId/delete")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `deleteRoom redirects to home for admin`(): Unit = runBlocking {
        val userId = 0L
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(userId, 123)).thenReturn(true)
        `when`(roomRepository.deleteById(roomId)).thenReturn(Mono.empty())

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .mutateWith(csrf())
            .post().uri("/rooms/$roomId/delete")
            .exchange()
            .expectStatus().is3xxRedirection
            .expectHeader().valueMatches("Location", ".*/")
    }

    @Test
    fun `deleteRoom returns forbidden for non-admin`(): Unit = runBlocking {
        val userId = 0L
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(userId, 123)).thenReturn(false)

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .mutateWith(csrf())
            .post().uri("/rooms/$roomId/delete")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `room detail page is accessible with form login`(): Unit = runBlocking {
        val userId = 0L
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isMemberOfGuild(userId, 123)).thenReturn(true)
        `when`(discordService.isAdminOfGuild(userId, 123)).thenReturn(false)
        `when`(entryRepository.findByRoomId(roomId)).thenReturn(Flux.empty())

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(
                    testPrincipal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
            )
        )
            .get().uri("/rooms/$roomId")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `uploadGame returns forbidden for non-admin`(): Unit = runBlocking {
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(0L, 123)).thenReturn(false)

        val builder = MultipartBodyBuilder()
        builder.part("gameFile", ByteArray(0)).filename("game.archipelago")

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            )
        ).mutateWith(csrf())
            .post().uri("/rooms/$roomId/upload-game")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(builder.build())
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `uploadGame with archipelago file redirects for admin`(): Unit = runBlocking {
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room")
        val entry = Entry(1L, roomId, 0L, "Player", "Game", "path/to/file.yaml")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(0L, 123)).thenReturn(true)
        `when`(entryRepository.findByRoomId(roomId)).thenReturn(Flux.just(entry))
        `when`(roomRepository.save(any(Room::class.java))).thenReturn(Mono.just(room.copy(generatedGameFilePath = "path/to/game.archipelago")))

        val builder = MultipartBodyBuilder()
        builder.part("gameFile", "fake archipelago content".toByteArray()).filename("game.archipelago")

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            )
        ).mutateWith(csrf())
            .post().uri("/rooms/$roomId/upload-game")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(builder.build())
            .exchange()
            .expectStatus().is3xxRedirection
            .expectHeader().valueMatches("Location", ".*/rooms/$roomId")
    }

    @Test
    fun `uploadGame with zip file redirects for admin`(): Unit = runBlocking {
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room")
        val entry = Entry(1L, roomId, 0L, "Player", "Game", "path/to/file.yaml")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(0L, 123)).thenReturn(true)
        `when`(entryRepository.findByRoomId(roomId)).thenReturn(Flux.just(entry))
        `when`(roomRepository.save(any(Room::class.java))).thenReturn(Mono.just(room.copy(generatedGameFilePath = "path/to/game.archipelago")))

        val zipBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("game.archipelago"))
                zos.write("fake archipelago content".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()

        val builder = MultipartBodyBuilder()
        builder.part("gameFile", zipBytes).filename("game.zip")

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            )
        ).mutateWith(csrf())
            .post().uri("/rooms/$roomId/upload-game")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(builder.build())
            .exchange()
            .expectStatus().is3xxRedirection
            .expectHeader().valueMatches("Location", ".*/rooms/$roomId")
    }

    @Test
    fun `uploadGame returns conflict error banner when room already has generated game`(): Unit = runBlocking {
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room", generatedGameFilePath = "existing/path.archipelago")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(0L, 123)).thenReturn(true)
        `when`(discordService.isMemberOfGuild(0L, 123)).thenReturn(true)
        `when`(entryRepository.findByRoomId(roomId)).thenReturn(Flux.empty())

        val builder = MultipartBodyBuilder()
        builder.part("gameFile", "fake content".toByteArray()).filename("game.archipelago")

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            )
        ).mutateWith(csrf())
            .post().uri("/rooms/$roomId/upload-game")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(builder.build())
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody!!
                assert(body.contains("class=\"error-banner\""))
                assert(body.contains("already been generated") || body.contains("Conflict"))
            }
    }

    @Test
    fun `uploadGame returns error banner when room has no entries`(): Unit = runBlocking {
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(0L, 123)).thenReturn(true)
        `when`(discordService.isMemberOfGuild(0L, 123)).thenReturn(true)
        `when`(entryRepository.findByRoomId(roomId)).thenReturn(Flux.empty())

        val builder = MultipartBodyBuilder()
        builder.part("gameFile", "fake content".toByteArray()).filename("game.archipelago")

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            )
        ).mutateWith(csrf())
            .post().uri("/rooms/$roomId/upload-game")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(builder.build())
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody!!
                assert(body.contains("class=\"error-banner\""))
                assert(body.contains("no entries") || body.contains("Unprocessable"))
            }
    }

    @Test
    fun `uploadGame returns conflict error banner on concurrent modification`(): Unit = runBlocking {
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room")
        val entry = Entry(1L, roomId, 0L, "Player", "Game", "path/to/file.yaml")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(0L, 123)).thenReturn(true)
        `when`(discordService.isMemberOfGuild(0L, 123)).thenReturn(true)
        `when`(entryRepository.findByRoomId(roomId)).thenReturn(Flux.just(entry))
        `when`(roomRepository.save(any(Room::class.java))).thenThrow(OptimisticLockingFailureException("concurrent modification"))

        val builder = MultipartBodyBuilder()
        builder.part("gameFile", "fake content".toByteArray()).filename("game.archipelago")

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            )
        ).mutateWith(csrf())
            .post().uri("/rooms/$roomId/upload-game")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(builder.build())
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody!!
                assert(body.contains("class=\"error-banner\""))
                assert(body.contains("concurrently") || body.contains("Conflict"))
            }
    }

    @Test
    fun `generateGame returns conflict when game already generated`(): Unit = runBlocking {
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room", generatedGameFilePath = "existing/path.archipelago")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(0L, 123)).thenReturn(true)

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            )
        ).mutateWith(csrf())
            .post().uri("/rooms/$roomId/generate")
            .exchange()
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `deleteGeneratedGame redirects for admin`(): Unit = runBlocking {
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room", generatedGameFilePath = "path/to/game.archipelago")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(0L, 123)).thenReturn(true)
        `when`(roomRepository.save(any(Room::class.java))).thenReturn(Mono.just(room.copy(generatedGameFilePath = null)))

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            )
        ).mutateWith(csrf())
            .post().uri("/rooms/$roomId/generated-game/delete")
            .exchange()
            .expectStatus().is3xxRedirection
            .expectHeader().valueMatches("Location", ".*/rooms/$roomId")
    }

    @Test
    fun `deleteGeneratedGame returns forbidden for non-admin`(): Unit = runBlocking {
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room", generatedGameFilePath = "path/to/game.archipelago")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(0L, 123)).thenReturn(false)

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            )
        ).mutateWith(csrf())
            .post().uri("/rooms/$roomId/generated-game/delete")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `deleteGeneratedGame returns conflict when generation is in progress`(): Unit = runBlocking {
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room", generatedGameFilePath = Room.GENERATING_SENTINEL)
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isAdminOfGuild(0L, 123)).thenReturn(true)

        webTestClient.mutateWith(
            mockAuthentication(
                UsernamePasswordAuthenticationToken(testPrincipal, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
            )
        ).mutateWith(csrf())
            .post().uri("/rooms/$roomId/generated-game/delete")
            .exchange()
            .expectStatus().isEqualTo(409)
    }
}
