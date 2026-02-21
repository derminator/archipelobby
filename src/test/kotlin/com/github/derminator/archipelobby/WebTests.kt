package com.github.derminator.archipelobby

import com.github.derminator.archipelobby.data.Entry
import com.github.derminator.archipelobby.data.EntryRepository
import com.github.derminator.archipelobby.data.Room
import com.github.derminator.archipelobby.data.RoomRepository
import com.github.derminator.archipelobby.discord.DiscordService
import com.github.derminator.archipelobby.discord.GuildInfo
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
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

    @Autowired
    lateinit var context: ApplicationContext

    lateinit var webTestClient: WebTestClient

    private val testUser = DefaultOAuth2User(
        listOf(SimpleGrantedAuthority("ROLE_USER")),
        mapOf("id" to "123456789", "username" to "TestUser"),
        "id"
    )

    @BeforeEach
    fun setup() = runBlocking {
        `when`(discordService.getGuildsForUser(anyLong())).thenReturn(emptyFlow())
        `when`(discordService.getAdminGuildsForUser(anyLong())).thenReturn(emptyFlow())
        `when`(discordService.isMemberOfAnyGuild(anyLong())).thenReturn(true)
        `when`(discordService.isMemberOfGuild(anyLong(), anyLong())).thenReturn(true)
        `when`(discordService.isAdminOfGuild(anyLong(), anyLong())).thenReturn(false)
        `when`(entryRepository.findByUserId(anyLong())).thenReturn(Flux.empty())
        `when`(entryRepository.countByRoomIdAndUserId(anyLong(), anyLong())).thenReturn(Mono.just(0L))

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
        webTestClient.mutateWith(mockOAuth2Login().oauth2User(testUser))
            .get().uri("/")
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(body!!.contains("Logged in as:"))
                assert(body.contains("TestUser"))
                assert(body.contains("Logout"))
            }
    }

    @Test
    fun `index page shows username when authenticated with form login`() {
        webTestClient.mutateWith(mockUser("DevUser"))
            .get().uri("/")
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(body!!.contains("Logged in as:"))
                assert(body.contains("DevUser"))
            }
    }

    @Test
    fun `non-existent page shows 404 for authenticated user`() {
        webTestClient.mutateWith(mockOAuth2Login().oauth2User(testUser))
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
        webTestClient.mutateWith(mockOAuth2Login().oauth2User(testUser))
            .mutateWith(csrf())
            .post().uri("/rooms")
            // guildId and name are missing, should trigger 400 Bad Request
            .header("Accept", "text/html")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody<String>().consumeWith { response ->
                val body = response.responseBody
                assert(body != null)
                assert(body!!.contains("An error occurred"))
            }
    }

    @Test
    fun `internal server error shows generic error page`() {
        `when`(roomRepository.findById(anyLong())).thenThrow(RuntimeException("Test exception"))

        webTestClient.mutateWith(mockOAuth2Login().oauth2User(testUser))
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
        // Since we didn't permit the non-existent path, it should redirect to login first
        webTestClient.get().uri("/this-page-does-not-exist")
            .header("Accept", "text/html")
            .exchange()
            .expectStatus().is3xxRedirection
            .expectHeader().valueMatches("Location", ".*/login")
    }

    @Test
    fun `adding entry with duplicate name returns conflict`(): Unit = runBlocking {
        `when`(entryRepository.existsByRoomIdAndName(anyLong(), anyString())).thenReturn(Mono.just(true))
        `when`(roomRepository.findById(anyLong())).thenReturn(Mono.just(Room(1, 123, "Test Room")))
        `when`(discordService.isMemberOfGuild(anyLong(), anyLong())).thenReturn(true)

        val bodyBuilder = MultipartBodyBuilder()
        bodyBuilder.part("entryName", "Duplicate Name")
        bodyBuilder.part("yamlFile", "test: data".toByteArray())
            .filename("test.yaml")

        webTestClient.mutateWith(mockOAuth2Login().oauth2User(testUser))
            .mutateWith(csrf())
            .post().uri("/rooms/1/entries")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(bodyBuilder.build())
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `renaming entry to duplicate name returns conflict`() {
        val existingEntry = Entry(1, 1, 123456789, "Old Name", "uploads/test.yaml")
        `when`(entryRepository.findById(anyLong())).thenReturn(Mono.just(existingEntry))
        `when`(entryRepository.existsByRoomIdAndName(anyLong(), anyString())).thenReturn(Mono.just(true))

        webTestClient.mutateWith(mockOAuth2Login().oauth2User(testUser))
            .mutateWith(csrf())
            .post().uri("/rooms/1/entries/1/rename")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("newName", "Duplicate Name"))
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `rooms page is accessible for user who is not admin in all joined guilds`(): Unit = runBlocking {
        val userId = 123456789L

        `when`(discordService.getGuildsForUser(userId)).thenReturn(
            flowOf(
                GuildInfo(1, "Guild 1"),
                GuildInfo(2, "Guild 2")
            )
        )
        `when`(discordService.getAdminGuildsForUser(userId)).thenReturn(emptyFlow())
        `when`(roomRepository.findByGuildId(anyLong())).thenReturn(Flux.empty())

        webTestClient.mutateWith(mockOAuth2Login().oauth2User(testUser))
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
        val userId = 123456789L
        `when`(discordService.getGuildsForUser(userId)).thenReturn(emptyFlow())
        `when`(discordService.getAdminGuildsForUser(userId)).thenReturn(emptyFlow())
        `when`(entryRepository.findByUserId(userId)).thenReturn(Flux.empty())

        webTestClient.mutateWith(mockUser("123456789"))
            .get().uri("/rooms")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `room detail page is accessible with form login`(): Unit = runBlocking {
        val userId = 123456789L
        val roomId = 1L
        val room = Room(roomId, 123, "Test Room")
        `when`(roomRepository.findById(roomId)).thenReturn(Mono.just(room))
        `when`(discordService.isMemberOfGuild(userId, 123)).thenReturn(true)
        `when`(discordService.isAdminOfGuild(userId, 123)).thenReturn(false)
        `when`(entryRepository.findByRoomId(roomId)).thenReturn(Flux.empty())

        webTestClient.mutateWith(mockUser("123456789"))
            .get().uri("/rooms/$roomId")
            .exchange()
            .expectStatus().isOk
    }
}
