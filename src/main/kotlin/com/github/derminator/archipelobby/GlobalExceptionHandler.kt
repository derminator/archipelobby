package com.github.derminator.archipelobby

import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(Exception::class)
    fun handleException(exchange: ServerWebExchange, ex: Exception): Mono<String> = mono {
        logger.error("An error occurred", ex)
        val status = when (ex) {
            is ResponseStatusException -> ex.statusCode
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        val errorMessage = when (ex) {
            is ResponseStatusException -> ex.reason ?: "An unexpected error occurred"
            else -> "An unexpected error occurred"
        }
        exchange.response.statusCode = status
        exchange.attributes["errorMessage"] = errorMessage
        if (status == HttpStatus.NOT_FOUND) {
            "error/404"
        } else {
            "error/error"
        }
    }
}

private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)