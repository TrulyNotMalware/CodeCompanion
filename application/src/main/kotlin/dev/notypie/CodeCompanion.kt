package dev.notypie

import dev.notypie.configurations.EnableMonitoringK8s
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@EnableMonitoringK8s
@ConfigurationPropertiesScan
@SpringBootApplication
class CodeCompanion

fun main(args: Array<String>) {
    runApplication<CodeCompanion>(*args)
}