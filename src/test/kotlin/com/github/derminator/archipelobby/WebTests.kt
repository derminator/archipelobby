package com.github.derminator.archipelobby

import com.github.derminator.archipelobby.data.Entry
import com.github.derminator.archipelobby.data.EntryRepository
import com.github.derminator.archipelobby.data.Room
import com.github.derminator.archipelobby.data.RoomRepository
import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Member
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*
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
    lateinit var gatewayDiscordClient: GatewayDiscordClient

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
    fun setup() {
        `when`(gatewayDiscordClient.guilds).thenReturn(Flux.empty())
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
            .expectHeader().valueMatches("Location", ".*/oauth2/authorization/discord")
    }

    @Test
    fun `adding entry with duplicate name returns conflict`() {
        `when`(entryRepository.existsByRoomIdAndName(anyLong(), anyString())).thenReturn(Mono.just(true))
        `when`(roomRepository.findById(anyLong())).thenReturn(Mono.just(Room(1, 123, "Test Room")))

        val mockGuild = mock(Guild::class.java)
        `when`(gatewayDiscordClient.getGuildById(Snowflake.of(123))).thenReturn(Mono.just(mockGuild))
        val mockMember = mock(Member::class.java)
        `when`(mockGuild.getMemberById(Snowflake.of(123456789))).thenReturn(Mono.just(mockMember))

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
}
