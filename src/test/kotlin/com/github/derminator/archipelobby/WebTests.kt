package com.github.derminator.archipelobby

import discord4j.core.GatewayDiscordClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOAuth2Login
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@SpringBootTest(
    properties = [
        "DISCORD_BOT_TOKEN=dummy",
        "DISCORD_CLIENT_ID=dummy",
        "DISCORD_CLIENT_SECRET=dummy"
    ]
)
class WebTests {

    @MockitoBean
    lateinit var gatewayDiscordClient: GatewayDiscordClient

    @MockitoBean
    lateinit var roomRepository: RoomRepository

    @MockitoBean
    lateinit var roomMemberRepository: RoomMemberRepository

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
        `when`(roomMemberRepository.findByUserId(anyLong())).thenReturn(Flux.empty())
        `when`(roomMemberRepository.findByRoomIdAndUserId(anyLong(), anyLong())).thenReturn(Mono.empty())

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
    fun `non-existent page redirects to login for unauthenticated user`() {
        // Since we didn't permit the non-existent path, it should redirect to login first
        webTestClient.get().uri("/this-page-does-not-exist")
            .header("Accept", "text/html")
            .exchange()
            .expectStatus().is3xxRedirection
            .expectHeader().valueMatches("Location", ".*/oauth2/authorization/discord")
    }
}
