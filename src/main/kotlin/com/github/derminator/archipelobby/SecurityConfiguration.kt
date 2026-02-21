package com.github.derminator.archipelobby

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.csrf.XorServerCsrfTokenRequestAttributeHandler
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.anyExchange
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers

@Configuration
@EnableWebFluxSecurity
class SecurityConfiguration(
    private val environment: Environment
) {

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        val isDiscordEnabled = environment.activeProfiles.contains("discord")

        return http {
            authorizeExchange {
                authorize(pathMatchers("/", "/error", "*.css"), permitAll)
                authorize(anyExchange(), authenticated)
            }
            if (isDiscordEnabled) {
                oauth2Login { }
            } else {
                formLogin { }
                logout { }
            }
            anonymous { }
            csrf {
                csrfTokenRequestHandler = XorServerCsrfTokenRequestAttributeHandler().apply {
                    setTokenFromMultipartDataEnabled(true)
                }
            }
        }
    }
}
