package dev.notypie.application.configurations

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
) {
    data class Authorization(
        // Slack user ids treated as ADMIN without a DB row — breaks the bootstrap chicken-and-egg
        // for the user_command_role table.
        val bootstrapAdmins: List<String> = emptyList(),
    )

    data class Mode(
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

    // MCP domain tools exposed to the agent lane. The endpoint is loopback-only by default
    // because the sidecar shares the Pod network namespace; every call authenticates with a
    // per-turn token minted from `signingSecret`.
    data class Mcp(
        val enabled: Boolean = false,
        val signingSecret: String = "",
        val tokenTtlSeconds: Long = 300L,
        val clockSkewSeconds: Long = 30L,
        val allowRemote: Boolean = false,
    )

    // AI-agent backend (claude-sidecar co-process). The sidecar shares the Pod, so the default
    // base URL is Pod-loopback; `bearerSecret` is the shared secret both processes are booted with.
    data class Agent(
        val sidecar: Sidecar = Sidecar(),
    ) {
        data class Sidecar(
            val baseUrl: String = "http://127.0.0.1:7300",
            val bearerSecret: String = "",
            // Client-side ceiling on one converse exchange; keep above the sidecar's own
            // TURN_TIMEOUT_SEC (default 90s) so the server-side timeout is the one that fires.
            val requestTimeoutSeconds: Long = 120L,
        )
    }
}

enum class EventPublisherType {
    KAFKA,
    APPLICATION_EVENT,
}
