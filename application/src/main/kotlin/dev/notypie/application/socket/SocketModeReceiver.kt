package dev.notypie.application.socket

import com.slack.api.Slack
import com.slack.api.socket_mode.SocketModeClient
import com.slack.api.socket_mode.response.AckResponse
import dev.notypie.application.common.parseRequestBodyData
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.agent.AskSlashService
import dev.notypie.application.service.calendar.CalendarSlashService
import dev.notypie.application.service.cve.query.CveQuerySlashService
import dev.notypie.application.service.cve.subscription.CveSubscriptionSlashService
import dev.notypie.application.service.interaction.InteractionHandler
import dev.notypie.application.service.meeting.MeetingService
import dev.notypie.application.service.mention.AppMentionEventHandler
import dev.notypie.application.service.standup.StandupSlashService
import dev.notypie.common.containFailure
import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.ViewOpenDeferral
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

private val log = KotlinLogging.logger {}

private const val APP_MENTION_EVENT_TYPE = "app_mention"

@Component
@Profile("local")
class SocketModeReceiver(
    private val appConfig: AppConfig,
    private val meetingService: MeetingService,
    private val standupSlashService: StandupSlashService,
    private val cveSubscriptionSlashService: CveSubscriptionSlashService,
    private val cveQuerySlashService: CveQuerySlashService,
    private val calendarSlashService: CalendarSlashService,
    private val askSlashService: AskSlashService,
    private val interactionHandler: InteractionHandler,
    private val appMentionEventHandler: AppMentionEventHandler,
) : SmartLifecycle {
    private val noHeaders: MultiValueMap<String, String> = LinkedMultiValueMap()

    @Volatile
    private var client: SocketModeClient? = null

    override fun start() {
        val appToken = appConfig.api.appToken
        if (appToken.isBlank()) {
            log.warn { "`local` profile active but slack.app.api.app-token is blank — receiver not started." }
            return
        }
        containFailure(onFailure = { log.error(it) { "Failed to start Socket Mode receiver." } }) {
            val socketClient = Slack.getInstance().socketMode(appToken)
            socketClient.addSlashCommandsEnvelopeListener { envelope ->
                logErrorThenRethrow(kind = "slash", envelopeId = envelope.envelopeId) {
                    ack(socketClient = socketClient, envelopeId = envelope.envelopeId)
                    handleSlash(payloadJson = envelope.payload.toString())
                }
            }
            socketClient.addInteractiveEnvelopeListener { envelope ->
                logErrorThenRethrow(kind = "interactive", envelopeId = envelope.envelopeId) {
                    // Handled first — a view_submission's response_action must ride the ack itself.
                    handleInteractive(payloadJson = envelope.payload.toString()) { ackBody ->
                        ackInteractive(socketClient = socketClient, envelopeId = envelope.envelopeId, ackBody = ackBody)
                    }
                }
            }
            socketClient.addEventsApiEnvelopeListener { envelope ->
                logErrorThenRethrow(kind = "event", envelopeId = envelope.envelopeId) {
                    ack(socketClient = socketClient, envelopeId = envelope.envelopeId)
                    handleEvent(payloadJson = envelope.payload.toString())
                }
            }
            socketClient.connect()
            client = socketClient
            log.info { "Slack Socket Mode receiver connected (local profile)." }
        }
    }

    override fun stop() {
        try {
            containFailure(onFailure = { log.warn(it) { "Failed to disconnect Socket Mode client cleanly." } }) {
                client?.disconnect()
            }
        } finally {
            client = null
        }
    }

    override fun isRunning(): Boolean = client != null

    internal fun logErrorThenRethrow(kind: String, envelopeId: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Error) {
            log.error(error) {
                "Socket Mode $kind envelope envelopeId=$envelopeId hit ${error::class.java.name}; the SDK drops this " +
                    "message loop (one of ten) without a log of its own, so restart the app if Socket Mode goes quiet"
            }
            throw error
        }
    }

    private fun ack(socketClient: SocketModeClient, envelopeId: String) =
        socketClient.sendSocketModeResponse(AckResponse.builder().envelopeId(envelopeId).build())

    private fun ackInteractive(socketClient: SocketModeClient, envelopeId: String, ackBody: String?) {
        if (ackBody == null) {
            ack(socketClient = socketClient, envelopeId = envelopeId)
            return
        }
        // AckResponse can't carry a payload; ackBody is already valid JSON, so embed it in a raw envelope.
        socketClient.sendSocketModeResponse("""{"envelope_id":"$envelopeId","payload":$ackBody}""")
    }

    internal fun handleSlash(payloadJson: String) {
        containFailure(onFailure = { log.error(it) { "Socket Mode slash-command handling failed." } }) {
            val data =
                jsonMapper
                    .readValue(payloadJson, Map::class.java)
                    .entries
                    .associate { (key, value) -> key.toString() to value.toString() }
            val (payload, commandData) = parseRequestBodyData(data = data)
            ViewOpenDeferral.afterBoundary { dispatchSlash(payload = payload, commandData = commandData) }
        }
    }

    internal fun dispatchSlash(payload: SlashCommandRequestBody, commandData: InboundCommand) {
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

            appConfig.socket.calendarCommand ->
                calendarSlashService.handleCalendar(
                    headers = noHeaders,
                    payload = payload,
                    commandData = commandData,
                )

            appConfig.socket.askCommand ->
                askSlashService.handleAsk(
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

            appConfig.socket.latestCommand ->
                cveQuerySlashService.handleLatest(
                    headers = noHeaders,
                    payload = payload,
                    commandData = commandData,
                )

            else -> log.warn { "Unmapped slash command over Socket Mode: ${payload.command}" }
        }
    }

    internal fun handleInteractive(payloadJson: String, acknowledge: (ackBody: String?) -> Unit) {
        val ackBody =
            try {
                interactionHandler.handleInteraction(headers = noHeaders, payload = payloadJson)
            } catch (exception: Exception) {
                log.error(exception) { "Socket Mode interaction handling failed; not acknowledging the envelope." }
                return
            }
        acknowledge(ackBody)
    }

    internal fun handleEvent(payloadJson: String) {
        containFailure(onFailure = { log.error(it) { "Socket Mode event handling failed." } }) {
            @Suppress("UNCHECKED_CAST")
            val payload = jsonMapper.readValue(payloadJson, Map::class.java) as Map<String, Any>
            val eventType = (payload["event"] as? Map<*, *>)?.get("type")
            if (eventType == APP_MENTION_EVENT_TYPE) {
                appMentionEventHandler.handleEvent(headers = noHeaders, payload = payload)
            } else {
                log.debug { "Ignoring non-app_mention Socket Mode event: type=$eventType" }
            }
        }
    }
}
