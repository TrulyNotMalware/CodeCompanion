package dev.notypie.application.socket

import com.slack.api.Slack
import com.slack.api.socket_mode.SocketModeClient
import com.slack.api.socket_mode.response.AckResponse
import dev.notypie.application.common.parseRequestBodyData
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.cve.subscription.CveSubscriptionSlashService
import dev.notypie.application.service.interaction.InteractionHandler
import dev.notypie.application.service.meeting.MeetingService
import dev.notypie.application.service.mention.AppMentionEventHandler
import dev.notypie.application.service.standup.StandupSlashService
import dev.notypie.common.jsonMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

private val log = KotlinLogging.logger {}

private const val APP_MENTION_EVENT_TYPE = "app_mention"

/**
 * Local-only inbound transport: receives slash commands, interactivity, and Events API payloads over
 * a Socket Mode WebSocket and feeds them into the same handlers the HTTP controllers use — so no
 * public URL, tunnel, or signature verification is needed for local testing. Outbound calls still go
 * over the Web API unchanged.
 *
 * Gated to the `local` Spring profile, so the bean is never registered in any other environment. Run
 * locally with `--spring.profiles.active=local` and an app-level token
 * (`slack.app.api.app-token` / `SLACK_APP_TOKEN`, scope `connections:write`).
 */
@Component
@Profile("local")
class SocketModeReceiver(
    private val appConfig: AppConfig,
    private val meetingService: MeetingService,
    private val standupSlashService: StandupSlashService,
    private val cveSubscriptionSlashService: CveSubscriptionSlashService,
    private val interactionHandler: InteractionHandler,
    private val appMentionEventHandler: AppMentionEventHandler,
) : SmartLifecycle {
    // Socket Mode carries no HTTP headers; downstream only wraps them for record-keeping.
    private val noHeaders: MultiValueMap<String, String> = LinkedMultiValueMap()

    @Volatile
    private var client: SocketModeClient? = null

    override fun start() {
        val appToken = appConfig.api.appToken
        if (appToken.isBlank()) {
            log.warn { "`local` profile active but slack.app.api.app-token is blank — receiver not started." }
            return
        }
        runCatching {
            val socketClient = Slack.getInstance().socketMode(appToken)
            socketClient.addSlashCommandsEnvelopeListener { envelope ->
                ack(socketClient = socketClient, envelopeId = envelope.envelopeId)
                handleSlash(payloadJson = envelope.payload.toString())
            }
            socketClient.addInteractiveEnvelopeListener { envelope ->
                // Handle first: a view_submission may need its response_action (e.g. inline
                // validation errors) carried in the ack itself.
                val ackBody = handleInteractive(payloadJson = envelope.payload.toString())
                ackInteractive(socketClient = socketClient, envelopeId = envelope.envelopeId, ackBody = ackBody)
            }
            socketClient.addEventsApiEnvelopeListener { envelope ->
                ack(socketClient = socketClient, envelopeId = envelope.envelopeId)
                handleEvent(payloadJson = envelope.payload.toString())
            }
            socketClient.connect()
            client = socketClient
            log.info { "Slack Socket Mode receiver connected (local profile)." }
        }.onFailure { log.error(it) { "Failed to start Socket Mode receiver." } }
    }

    override fun stop() {
        runCatching { client?.disconnect() }
            .onFailure { log.warn(it) { "Failed to disconnect Socket Mode client cleanly." } }
        client = null
    }

    override fun isRunning(): Boolean = client != null

    private fun ack(socketClient: SocketModeClient, envelopeId: String) {
        socketClient.sendSocketModeResponse(AckResponse.builder().envelopeId(envelopeId).build())
    }

    private fun ackInteractive(socketClient: SocketModeClient, envelopeId: String, ackBody: String?) {
        if (ackBody == null) {
            ack(socketClient = socketClient, envelopeId = envelopeId)
            return
        }
        // AckResponse can't carry a payload, so emit the raw ack envelope. ackBody is already
        // valid JSON (a response_action object) and envelopeId is a URL-safe Slack id, so
        // embedding it directly yields a well-formed envelope.
        socketClient.sendSocketModeResponse("""{"envelope_id":"$envelopeId","payload":$ackBody}""")
    }

    private fun handleSlash(payloadJson: String) {
        runCatching {
            val data =
                jsonMapper
                    .readValue(payloadJson, Map::class.java)
                    .entries
                    .associate { (key, value) -> key.toString() to value.toString() }
            val (payload, commandData) = parseRequestBodyData(headers = noHeaders, data = data)
            when (payload.command) {
                appConfig.socket.meetingCommand ->
                    meetingService.handleMeeting(
                        headers = noHeaders,
                        payload = payload,
                        commandData = commandData,
                    )

                appConfig.socket.standupCommand ->
                    standupSlashService.handleStandup(
                        headers = noHeaders,
                        payload = payload,
                        commandData = commandData,
                    )

                appConfig.socket.subscribeCommand ->
                    cveSubscriptionSlashService.handleSubscribe(
                        headers = noHeaders,
                        payload = payload,
                        commandData = commandData,
                    )

                appConfig.socket.unsubscribeCommand ->
                    cveSubscriptionSlashService.handleUnsubscribe(
                        headers = noHeaders,
                        payload = payload,
                        commandData = commandData,
                    )

                appConfig.socket.subscriptionsCommand ->
                    cveSubscriptionSlashService.handleSubscriptions(
                        headers = noHeaders,
                        payload = payload,
                        commandData = commandData,
                    )

                else -> log.warn { "Unmapped slash command over Socket Mode: ${payload.command}" }
            }
        }.onFailure { log.error(it) { "Socket Mode slash-command handling failed." } }
    }

    private fun handleInteractive(payloadJson: String): String? =
        runCatching {
            interactionHandler.handleInteraction(headers = noHeaders, payload = payloadJson)
        }.onFailure { log.error(it) { "Socket Mode interaction handling failed." } }
            .getOrNull()

    private fun handleEvent(payloadJson: String) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val payload = jsonMapper.readValue(payloadJson, Map::class.java) as Map<String, Any>
            val eventType = (payload["event"] as? Map<*, *>)?.get("type")
            if (eventType == APP_MENTION_EVENT_TYPE) {
                appMentionEventHandler.handleEvent(headers = noHeaders, payload = payload)
            } else {
                log.debug { "Ignoring non-app_mention Socket Mode event: type=$eventType" }
            }
        }.onFailure { log.error(it) { "Socket Mode event handling failed." } }
    }
}
