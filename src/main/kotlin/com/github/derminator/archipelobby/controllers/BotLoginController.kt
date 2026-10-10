package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.security.BotLoginService
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.server.context.ServerSecurityContextRepository
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@Controller
class BotLoginController(
    private val botLoginService: BotLoginService,
    private val securityContextRepository: ServerSecurityContextRepository
) {
    @GetMapping("/login/bot")
    suspend fun login(
        @RequestParam token: String,
        exchange: ServerWebExchange
    ): String {
        val principal = botLoginService.consume(token)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired login link")
        val authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER"))
        )

        exchange.response.headers[HttpHeaders.CACHE_CONTROL] = "no-store"
        exchange.response.headers["Referrer-Policy"] = "no-referrer"
        exchange.session.awaitSingleOrNull()?.changeSessionId()?.awaitSingleOrNull()
        securityContextRepository.save(exchange, SecurityContextImpl(authentication)).awaitSingleOrNull()
        return "redirect:/rooms"
    }
}
