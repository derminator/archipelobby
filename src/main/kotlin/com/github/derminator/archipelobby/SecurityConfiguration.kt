package com.github.derminator.archipelobby

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
import org.springframework.security.web.server.csrf.XorServerCsrfTokenRequestAttributeHandler
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.anyExchange
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers
import reactor.core.publisher.Mono
import java.net.URI

@Configuration
@EnableWebFluxSecurity
class SecurityConfiguration(
    private val environment: Environment
) {

    companion object {
        const val LOGIN_REDIRECT_SESSION_KEY = "loginRedirectUri"
        private const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; " +
            "script-src 'self'; " +
            "style-src 'self'; " +
            "img-src 'self'; " +
            "form-action 'self'; " +
            "base-uri 'self'; " +
            "frame-ancestors 'none'"
    }

    @Bean
    fun loginRedirectSuccessHandler(): ServerAuthenticationSuccessHandler =
        ServerAuthenticationSuccessHandler { webFilterExchange, _ ->
            webFilterExchange.exchange.session.flatMap { session ->
                val destination = session.getAttribute<String>(LOGIN_REDIRECT_SESSION_KEY)
                session.attributes.remove(LOGIN_REDIRECT_SESSION_KEY)
                session.save().then(Mono.defer {
                    val response = webFilterExchange.exchange.response
                    response.statusCode = HttpStatus.FOUND
                    response.headers.location = URI.create(destination ?: "/")
                    response.setComplete()
                })
            }
        }

    @Bean
    fun springSecurityFilterChain(
        http: ServerHttpSecurity,
        loginRedirectSuccessHandler: ServerAuthenticationSuccessHandler,
    ): SecurityWebFilterChain {
        val isDiscordEnabled = environment.activeProfiles.contains("discord")

        return http {
            authorizeExchange {
                authorize(pathMatchers("/", "/error", "/style.css", "/robots.txt", "/favicon.svg", "/rooms/login/discord"), permitAll)
                authorize(pathMatchers(HttpMethod.GET, "/rooms/*"), permitAll)
                authorize(pathMatchers("/internal/multiserver/**"), permitAll)
                authorize(pathMatchers("/rooms/*/ws"), permitAll)
                authorize(anyExchange(), authenticated)
            }
            if (isDiscordEnabled) {
                oauth2Login {
                    authenticationSuccessHandler = loginRedirectSuccessHandler
                }
            } else {
                formLogin { }
                logout { }
            }
            anonymous { }
            csrf {
                csrfTokenRequestHandler = XorServerCsrfTokenRequestAttributeHandler().apply {
                    setTokenFromMultipartDataEnabled(true)
                }
                val safeMethods = setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.TRACE, HttpMethod.OPTIONS)
                requireCsrfProtectionMatcher = ServerWebExchangeMatcher { exchange ->
                    when {
                        exchange.request.method in safeMethods -> ServerWebExchangeMatcher.MatchResult.notMatch()
                        exchange.request.path.value().startsWith("/internal/multiserver/") -> ServerWebExchangeMatcher.MatchResult.notMatch()
                        else -> ServerWebExchangeMatcher.MatchResult.match()
                    }
                }
            }
            headers {
                referrerPolicy {
                    policy = ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN
                }
                contentSecurityPolicy {
                    policyDirectives = CONTENT_SECURITY_POLICY
                }
            }
        }
    }
}
