package dev.notypie.application.configurations

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "slack.app")
data class AppConfig(
    var api: Api = Api(),
    var mode: Mode = Mode()
) {

    data class Mode(
        var standAlone: Boolean = true,
        var microService: Boolean = false
    )

    data class Api(
        var token: String = ""
    )
}