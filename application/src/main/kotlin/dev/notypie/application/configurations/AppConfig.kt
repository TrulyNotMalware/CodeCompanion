package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnMicroServiceCondition
import dev.notypie.application.configurations.conditions.OnStandAloneCondition
import dev.notypie.application.service.user.DefaultUserServiceImpl
import dev.notypie.application.service.user.MicroUserServiceImpl
import dev.notypie.application.service.user.UserService
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.user.repository.TeamRepository
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "slack.app")
data class AppConfig(
    val api: Api = Api(),
    val mode: Mode = Mode()
) {

    data class Mode(
        val standAlone: Boolean = true,
        val publisher: PublisherType = PublisherType.POOLING,
        val cdc: Cdc = Cdc()
    )

    data class Api(
        val token: String = ""
    )

    data class Cdc(
        val topic: String = ""
    )
}

@Configuration
@Conditional(OnStandAloneCondition::class)
class ApplicationOptionConfiguration{

    @Bean
    fun userService(
        slackApiRequester: SlackApiRequester,
        teamRepository: TeamRepository
    ): UserService
    = DefaultUserServiceImpl(
        slackApiRequester = slackApiRequester,
        teamRepository = teamRepository
    )
}


@Configuration
@Conditional(OnMicroServiceCondition::class)
class ApplicationMicroServiceOptionConfiguration{

    @Bean
    fun userService(): UserService = MicroUserServiceImpl()
}