package com.github.derminator.archipelobby

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ImportRuntimeHints

@SpringBootApplication
@ImportRuntimeHints(ArchipelobbyRuntimeHints::class)
class ArchipelobbyApplication

fun main(args: Array<String>) {
    runApplication<ArchipelobbyApplication>(*args)
}
