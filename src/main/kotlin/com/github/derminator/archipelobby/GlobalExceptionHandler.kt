package com.github.derminator.archipelobby

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@ControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(Exception::class)
    fun handleException(exchange: ServerWebExchange, ex: Exception): Mono<String> {
        logger.error("An error occurred", ex)
        val status = when (ex) {
            is ResponseStatusException -> ex.statusCode
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        exchange.response.statusCode = status
        return if (status == HttpStatus.NOT_FOUND) {
            Mono.just("error/404")
        } else {
            Mono.just("error/error")
        }
    }
}
