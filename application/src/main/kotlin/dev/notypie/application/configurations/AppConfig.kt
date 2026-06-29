package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnMicroServiceCondition
import dev.notypie.application.configurations.conditions.OnStandAloneCondition
import dev.notypie.application.service.user.DefaultUserServiceImpl
import dev.notypie.application.service.user.MicroUserServiceImpl
import dev.notypie.application.service.user.UserService
import dev.notypie.domain.user.repository.TeamRepository
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.*

const val APP_CONFIG_PROPERTIES_PREFIX = "slack.app"

enum class OutboxReaderStrategy {
    POLLING,
    CDC,
}

@ConfigurationProperties(prefix = "slack.app")
data class AppConfig(
    val api: Api = Api(),
    val mode: Mode = Mode(),
    val meeting: Meeting = Meeting(),
    val standup: Standup = Standup(),
    val outbox: Outbox = Outbox(),
    val socket: Socket = Socket(),
) {
    data class Mode(
        val standAlone: Boolean = true,
        val outboxReadingStrategy: OutboxReaderStrategy = OutboxReaderStrategy.POLLING,
        val cdc: Cdc = Cdc(),
        val eventPublisher: EventPublisherType = EventPublisherType.APPLICATION_EVENT,
    )

    data class Api(
        val token: String = "",
        // App-level token (xapp-, scope connections:write) — only used by the local-only Socket
        // Mode receiver. Blank in every non-local environment.
        val appToken: String = "",
        val signingSecret: String = "",
        val requestTimestampToleranceSeconds: Long = 300,
    )

    data class Cdc(
        val topic: String = "",
    )

    data class Meeting(
        val reminder: Reminder = Reminder(),
        val agenda: Agenda = Agenda(),
    ) {
        data class Reminder(
            val offsetsMinutes: List<Int> = listOf(15, 5),
            val stuckSendingThresholdMinutes: Long = 5L,
            val dispatchBatchSize: Int = 50,
            val materializeLookbackMinutes: Long = 2L,
        )

        data class Agenda(
            val enabled: Boolean = true,
            val sendAt: String = "08:00",
            val timezone: String = "Asia/Seoul",
        )
    }

    data class Standup(
        val scheduler: Scheduler = Scheduler(),
        val nudge: Nudge = Nudge(),
    ) {
        data class Scheduler(
            val stuckSendingThresholdMinutes: Long = 5L,
            val dispatchBatchSize: Int = 50,
        )

        data class Nudge(
            val offsetMinutes: Long = 30L,
        )
    }

    data class Outbox(
        val health: Health = Health(),
        val polling: Polling = Polling(),
    ) {
        data class Health(
            val stuckThresholdSeconds: Long = 300L,
        )

        data class Polling(
            val batchSize: Int = 100,
            val stuckInProgressSeconds: Long = 300L,
        )
    }

    // Local-only Socket Mode receiver settings. Socket delivers every slash command to one
    // listener, so the command names are mapped to their handlers here.
    data class Socket(
        val meetingCommand: String = "/meetup",
        val standupCommand: String = "/standup",
    )
}

enum class EventPublisherType {
    KAFKA,
    APPLICATION_EVENT,
}

@Configuration
@Conditional(OnStandAloneCondition::class)
class ApplicationOptionConfiguration {
    @Bean
    fun userService(teamRepository: TeamRepository): UserService =
        DefaultUserServiceImpl(teamRepository = teamRepository)
}

@Configuration
@Conditional(OnMicroServiceCondition::class)
class ApplicationMicroServiceOptionConfiguration {
    @Bean
    fun userService(): UserService = MicroUserServiceImpl()
}
