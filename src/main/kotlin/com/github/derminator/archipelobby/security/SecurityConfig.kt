package com.github.derminator.archipelobby.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity
    ): SecurityWebFilterChain {
        return http
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers("/login/**", "/oauth2/**").permitAll()
                    .anyExchange().authenticated()
            }
            .oauth2Login { }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .build()
    }

    @Bean
    fun discordOAuth2UserService(): ReactiveOAuth2UserService<OAuth2UserRequest, OAuth2User> {
        val delegate = DefaultReactiveOAuth2UserService()
        return ReactiveOAuth2UserService { userRequest ->
            delegate.loadUser(userRequest).map { user ->
                val username = user.attributes["username"]?.toString() ?: user.name
                val attributes = mapOf("username" to username)
                val authorities = user.authorities.filterNot { it is OAuth2UserAuthority }.toMutableSet()
                authorities.add(OAuth2UserAuthority(attributes, "username"))
                DefaultOAuth2User(authorities, attributes, "username")
            }
        }
    }
}
