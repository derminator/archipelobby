package com.github.derminator.archipelobby

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.reactive.UrlHandlerFilter
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono

@Configuration
class WebFluxConfiguration {
    /**
     * Configure UrlHandlerFilter to handle trailing slashes transparently.
     * The filter mutates the request path to remove trailing slashes,
     * allowing controllers with single path definitions to handle both
     * /path and /path/ URLs.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun trailingSlashHandler(): WebFilter = UrlHandlerFilter
        .trailingSlashHandler("/**").mutateRequest()
        .build()

    @Bean
    fun permissionsPolicyFilter(): WebFilter = WebFilter { exchange, chain ->
        chain.filter(exchange).then(Mono.fromRunnable {
            exchange.response.headers.set(
                "Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
            )
        })
    }
}
