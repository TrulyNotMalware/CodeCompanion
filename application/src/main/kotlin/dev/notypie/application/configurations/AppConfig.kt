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
        val subscribeCommand: String = "/subscribe",
        val unsubscribeCommand: String = "/unsubscribe",
        val subscriptionsCommand: String = "/subscriptions",
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

    // CVE-Bot topic subscriptions. Topics are config-supplied: at boot each entry is upserted
    // into cve_topic by key, so the yaml stays the admin-managed source while rows added by
    // other means survive restarts. [github]/[nvd]/[collector] tune the M4 feed collector.
    data class Cve(
        val enabled: Boolean = false,
        val topics: List<TopicDefinition> = emptyList(),
        val github: Github = Github(),
        val nvd: Nvd = Nvd(),
        val collector: Collector = Collector(),
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

        // GitHub Releases source. [token] lifts the unauthenticated rate limit (blank = anonymous);
        // it is never logged. [perPage] caps releases fetched per topic per tick.
        data class Github(
            val token: String = "",
            val perPage: Int = 10,
        )

        // NVD 2.0 source. [apiKey] raises the rate limit (blank = anonymous); it is never logged.
        // [lookbackMinutes] sizes the lastModStartDate..now scan window; overlaps are dedup-safe.
        data class Nvd(
            val apiKey: String = "",
            val lookbackMinutes: Long = 120,
        )

        // Collector tick knobs. [requestTimeoutSeconds] bounds one source HTTP call; [windowMinutes]
        // is the once-per-window claim bucket the tick time is truncated to.
        data class Collector(
            val requestTimeoutSeconds: Long = 30,
            val windowMinutes: Long = 5,
        )
    }

    // AI summarization for CVE events. `provider` selects the AiSummarizer implementation
    // (noop = no external calls, the safe default; sidecar = reuse the agent lane). The remaining
    // knobs tune the summarize-once worker's claim/retry loop.
    data class Ai(
        val provider: String = "noop",
        val batchSize: Int = 10,
        val maxRetries: Int = 5,
        val backoffMinutes: Long = 10,
        val stuckMinutes: Long = 15,
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
