package com.github.derminator.archipelobby

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.WebSession
import org.springframework.web.server.session.CookieWebSessionIdResolver
import org.springframework.web.server.session.InMemoryWebSessionStore
import org.springframework.web.server.session.WebSessionIdResolver
import org.springframework.web.server.session.WebSessionStore
import reactor.core.publisher.Mono
import java.time.Duration

@Configuration
class WebSessionConfiguration {

    companion object {
        val SESSION_DURATION: Duration = Duration.ofDays(7)
    }

    @Bean
    fun webSessionIdResolver(): WebSessionIdResolver = CookieWebSessionIdResolver().apply {
        addCookieInitializer { cookie ->
            cookie.maxAge(SESSION_DURATION)
            cookie.httpOnly(true)
            cookie.sameSite("Lax")
        }
    }

    @Bean
    fun webSessionStore(): WebSessionStore =
        object : InMemoryWebSessionStore() {
            override fun createWebSession(): Mono<WebSession> =
                super.createWebSession().map { session ->
                    session.also { it.maxIdleTime = SESSION_DURATION }
                }
        }
}
