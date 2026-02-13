package com.github.derminator.archipelobby

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.csrf.XorServerCsrfTokenRequestAttributeHandler
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.anyExchange
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers

@Configuration
@EnableWebFluxSecurity
class SecurityConfiguration {

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http {
            authorizeExchange {
                authorize(pathMatchers("/", "/error", "*.css"), permitAll)
                authorize(anyExchange(), authenticated)
            }
            oauth2Login { }
            anonymous { }
            csrf {
                csrfTokenRequestHandler = XorServerCsrfTokenRequestAttributeHandler().apply {
                    setTokenFromMultipartDataEnabled(true)
                }
            }
        }
}
