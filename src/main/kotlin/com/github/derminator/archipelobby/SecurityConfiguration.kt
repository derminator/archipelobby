package com.github.derminator.archipelobby

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class SecurityConfiguration {

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .authorizeExchange { exchange ->
                exchange.pathMatchers("/", "/error", "*.css", "/help", "/help/*").permitAll()
                exchange.anyExchange().authenticated()
            }
            .oauth2Login(Customizer.withDefaults())
            .anonymous(Customizer.withDefaults())
            .build()
}
