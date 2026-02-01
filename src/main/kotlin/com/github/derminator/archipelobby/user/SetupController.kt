package com.github.derminator.archipelobby.user

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.net.URI

@RestController
class SetupController(private val userService: UserService) {

    @GetMapping("/setup")
    fun setup(): Mono<ResponseEntity<String>> {
        return userService.hasAnyAdmin().map { hasAdmin ->
            if (hasAdmin) {
                ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(URI.create("/")).build()
            } else {
                ResponseEntity.ok().body("""
                    <html>
                        <head><title>Archipelobby Setup</title></head>
                        <body>
                            <h1>Initial Setup</h1>
                            <p>You are logged in as the setup user. To complete setup, please login with Discord to become the first administrator.</p>
                            <a href="/oauth2/authorization/discord">Login with Discord</a>
                        </body>
                    </html>
                """.trimIndent())
            }
        }
    }
    
    @GetMapping("/pending")
    fun pending(): String {
        return "Your account is pending approval. Please contact an administrator."
    }

    @GetMapping("/denied")
    fun denied(): String {
        return "Your account has been denied access."
    }
}
