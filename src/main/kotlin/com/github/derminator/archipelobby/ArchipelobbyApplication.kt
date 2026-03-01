package com.github.derminator.archipelobby

import org.springframework.aot.hint.annotation.ImportRuntimeHints
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
@ImportRuntimeHints(ArchipelobbyRuntimeHints::class)
class ArchipelobbyApplication

fun main(args: Array<String>) {
    runApplication<ArchipelobbyApplication>(*args)
}
