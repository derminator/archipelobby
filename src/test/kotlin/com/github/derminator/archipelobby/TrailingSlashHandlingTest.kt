package com.github.derminator.archipelobby

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest
@AutoConfigureWebTestClient
class TrailingSlashHandlingTest(
    @Autowired val webTestClient: WebTestClient
) {

    @Test
    @WithMockUser
    fun testRoomsPathWithoutTrailingSlash() {
        webTestClient.get().uri("/rooms")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    @WithMockUser
    fun testRoomsPathWithTrailingSlash() {
        webTestClient.get().uri("/rooms/")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    @WithMockUser
    fun testRoomIdPathWithoutTrailingSlash() {
        webTestClient.get().uri("/rooms/1")
            .exchange()
            .expectStatus().isNotFound  // Room doesn't exist, but the path should be recognized
    }

    @Test
    @WithMockUser
    fun testRoomIdPathWithTrailingSlash() {
        webTestClient.get().uri("/rooms/1/")
            .exchange()
            .expectStatus().isNotFound  // Room doesn't exist, but the path should be recognized
    }
}
