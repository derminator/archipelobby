package com.github.derminator.archipelobby.security

import com.github.derminator.archipelobby.user.UserRole
import com.github.derminator.archipelobby.user.UserStatus
import com.github.derminator.archipelobby.user.UserService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
import reactor.core.publisher.Mono
import java.net.URI

@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    private val userService: UserService
) {

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        userDetailsService: ObjectProvider<ReactiveUserDetailsService>
    ): SecurityWebFilterChain {
        val defaultUserDetailsService = userDetailsService.ifAvailable
        val authManager = if (defaultUserDetailsService != null) {
            UserDetailsRepositoryReactiveAuthenticationManager(defaultUserDetailsService)
        } else {
            null
        }

        val conditionalAuthManager = ReactiveAuthenticationManager { authentication ->
            userService.hasAnyAdmin().flatMap { hasAdmin ->
                if (hasAdmin) {
                    Mono.error(BadCredentialsException("Only Discord login allowed"))
                } else if (authManager != null) {
                    authManager.authenticate(authentication)
                } else {
                    Mono.error(BadCredentialsException("No authentication manager available"))
                }
            }
        }

        return http
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers("/login/**", "/oauth2/**", "/error", "/api/user/status", "/pending", "/denied").permitAll()
                    .pathMatchers("/api/admin/**").hasRole("ADMIN")
                    .pathMatchers("/setup").authenticated()
                    .anyExchange().hasRole("APPROVED")
            }
            .oauth2Login { oauth2 ->
                oauth2.authenticationSuccessHandler(authenticationSuccessHandler())
            }
            .formLogin { form ->
                form.authenticationManager(conditionalAuthManager)
            }
            .httpBasic { it.disable() }
            .csrf { it.disable() }
            .exceptionHandling { exceptionHandling ->
                exceptionHandling.accessDeniedHandler { exchange, _ ->
                    exchange.getPrincipal<Authentication>().flatMap { auth ->
                        val authorities = auth.authorities.map { it.authority }
                        val uri = when {
                            authorities.contains("ROLE_PENDING") -> "/pending"
                            authorities.contains("ROLE_DENIED") -> "/denied"
                            !authorities.contains("ROLE_APPROVED") && authorities.contains("ROLE_USER") -> "/setup"
                            else -> "/login"
                        }
                        exchange.response.statusCode = HttpStatus.FOUND
                        exchange.response.headers.location = URI.create(uri)
                        Mono.empty<Void>()
                    }
                }
            }
            .build()
    }

    @Bean
    fun authenticationSuccessHandler(): ServerAuthenticationSuccessHandler {
        return ServerAuthenticationSuccessHandler { webFilterExchange, authentication ->
            val authorities = authentication.authorities.map { it.authority }
            val uri = when {
                authorities.contains("ROLE_APPROVED") -> "/"
                authorities.contains("ROLE_PENDING") -> "/pending"
                authorities.contains("ROLE_DENIED") -> "/denied"
                else -> "/"
            }
            val response = webFilterExchange.exchange.response
            response.statusCode = HttpStatus.FOUND
            response.headers.location = URI.create(uri)
            Mono.empty()
        }
    }

    @Bean
    fun discordOAuth2UserService(): ReactiveOAuth2UserService<OAuth2UserRequest, OAuth2User> {
        val delegate = DefaultReactiveOAuth2UserService()
        return ReactiveOAuth2UserService { userRequest ->
            delegate.loadUser(userRequest).flatMap { oAuth2User ->
                val username = oAuth2User.attributes["username"]?.toString() ?: oAuth2User.name
                val discordId = oAuth2User.attributes["id"]?.toString() ?: return@flatMap Mono.just(oAuth2User)

                userService.hasAnyAdmin().flatMap { hasAdmin ->
                    if (!hasAdmin) {
                        userService.setFirstAdmin(discordId, username).map { user ->
                            mapToOAuth2User(oAuth2User, user)
                        }
                    } else {
                        userService.findOrCreateUser(discordId, username).map { user ->
                            mapToOAuth2User(oAuth2User, user)
                        }
                    }
                }
            }
        }
    }

    private fun mapToOAuth2User(oAuth2User: OAuth2User, user: com.github.derminator.archipelobby.user.User): OAuth2User {
        val authorities = mutableSetOf<SimpleGrantedAuthority>()
        when (user.status) {
            UserStatus.APPROVED -> {
                authorities.add(SimpleGrantedAuthority("ROLE_APPROVED"))
                if (user.role == UserRole.ADMIN) {
                    authorities.add(SimpleGrantedAuthority("ROLE_ADMIN"))
                } else {
                    authorities.add(SimpleGrantedAuthority("ROLE_USER"))
                }
            }
            UserStatus.PENDING -> authorities.add(SimpleGrantedAuthority("ROLE_PENDING"))
            UserStatus.DENIED -> authorities.add(SimpleGrantedAuthority("ROLE_DENIED"))
        }
        val attributes = oAuth2User.attributes.toMutableMap()
        attributes["username"] = user.username
        return DefaultOAuth2User(authorities, attributes, "username")
    }
}
