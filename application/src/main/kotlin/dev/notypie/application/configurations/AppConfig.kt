package dev.notypie.application.configurations

import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.cve.schema.CveSourceType
import dev.notypie.repository.cve.schema.CveTopicCategory
import org.springframework.boot.context.properties.ConfigurationProperties

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
    val agent: Agent = Agent(),
    val authorization: Authorization = Authorization(),
    val mcp: Mcp = Mcp(),
    val cve: Cve = Cve(),
    val ai: Ai = Ai(),
) {
    data class Authorization(
        val bootstrapAdmins: List<String> = emptyList(),
    )

    data class Mode(
        val outboxReadingStrategy: OutboxReaderStrategy = OutboxReaderStrategy.POLLING,
        val cdc: Cdc = Cdc(),
        val eventPublisher: EventPublisherType = EventPublisherType.APPLICATION_EVENT,
    )

    data class Api(
        val token: String = "",
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
        val retention: Retention = Retention(),
    ) {
        data class Health(
            val stuckThresholdSeconds: Long = 300L,
        )

        data class Retention(
            val days: Long = 14L,
            val batchSize: Int = 1_000,
        )

        data class Polling(
            val batchSize: Int = 100,
            val stuckInProgressSeconds: Long = 300L,
            val giveUpAfterHours: Long = 24L,
        )
    }

    data class Socket(
        val meetingCommand: String = "/meetup",
        val standupCommand: String = "/standup",
        val subscribeCommand: String = "/subscribe",
        val unsubscribeCommand: String = "/unsubscribe",
        val subscriptionsCommand: String = "/subscriptions",
        val latestCommand: String = "/latest",
    )

    data class Mcp(
        val enabled: Boolean = false,
        val signingSecret: String = "",
        val tokenTtlSeconds: Long = 300L,
        val clockSkewSeconds: Long = 30L,
        val allowRemote: Boolean = false,
    )

    data class Cve(
        val enabled: Boolean = false,
        val topics: List<TopicDefinition> = emptyList(),
        val github: Github = Github(),
        val nvd: Nvd = Nvd(),
        val collector: Collector = Collector(),
        val notification: Notification = Notification(),
    ) {
        data class TopicDefinition(
            val key: String = "",
            val displayName: String = "",
            val category: CveTopicCategory = CveTopicCategory.ETC,
            val sourceType: CveSourceType = CveSourceType.RSS,
            val sourceConfig: String? = null,
            val deliveryMode: CveDeliveryMode = CveDeliveryMode.DIGEST,
            val active: Boolean = true,
        )

        data class Github(
            val token: String = "",
            val perPage: Int = 10,
        )

        data class Nvd(
            val apiKey: String = "",
            val lookbackMinutes: Long = 120,
        )

        data class Collector(
            val requestTimeoutSeconds: Long = 30,
            val windowMinutes: Long = 5,
        )

        data class Notification(
            val batchSize: Int = 50,
            val digestSendAt: String = "09:00",
            val digestTimezone: String = "Asia/Seoul",
            val digestSummaryMaxLength: Int = 700,
            val deliveryHorizonDays: Long = 7,
        )
    }

    data class Ai(
        val provider: String = "noop",
        val batchSize: Int = 10,
        val maxRetries: Int = 5,
        val backoffMinutes: Long = 10,
        val stuckMinutes: Long = 15,
    )

    data class Agent(
        val sidecar: Sidecar = Sidecar(),
    ) {
        data class Sidecar(
            val baseUrl: String = "http://127.0.0.1:7300",
            val bearerSecret: String = "",
            // Keep above the sidecar's own TURN_TIMEOUT_SEC (90s) so the server-side timeout fires, not this.
            val requestTimeoutSeconds: Long = 120L,
        )
    }
}

enum class EventPublisherType {
    KAFKA,
    APPLICATION_EVENT,
}
