package dev.notypie

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CodeCompanion

fun main(args: Array<String>) {
    runApplication<CodeCompanion>(*args)
}