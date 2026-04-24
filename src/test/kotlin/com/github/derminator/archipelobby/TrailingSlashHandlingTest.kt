package com.github.derminator.archipelobby

import com.github.derminator.archipelobby.generator.ArchipelagoGeneratorService
import com.github.derminator.archipelobby.security.DiscordPrincipal
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest
@AutoConfigureWebTestClient
class TrailingSlashHandlingTest(
    @Autowired val webTestClient: WebTestClient
) {

    @MockitoBean
    lateinit var archipelagoGeneratorService: ArchipelagoGeneratorService

    private val testPrincipal = DiscordPrincipal(0L, "testUser")
    private val testAuthentication = UsernamePasswordAuthenticationToken(
        testPrincipal,
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER"))
    )

    @Test
    fun testRoomsPathWithoutTrailingSlash() {
        webTestClient
            .mutateWith(mockAuthentication(testAuthentication))
            .get().uri("/rooms")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun testRoomsPathWithTrailingSlash() {
        webTestClient
            .mutateWith(mockAuthentication(testAuthentication))
            .get().uri("/rooms/")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun testRoomIdPathWithoutTrailingSlash() {
        webTestClient
            .mutateWith(mockAuthentication(testAuthentication))
            .get().uri("/rooms/1")
            .exchange()
            .expectStatus().isNotFound  // Room doesn't exist, but the path should be recognized
    }

    @Test
    fun testRoomIdPathWithTrailingSlash() {
        webTestClient
            .mutateWith(mockAuthentication(testAuthentication))
            .get().uri("/rooms/1/")
            .exchange()
            .expectStatus().isNotFound  // Room doesn't exist, but the path should be recognized
    }
}
