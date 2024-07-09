package dev.notypie.impl.command

import com.slack.api.methods.Methods
import dev.notypie.domain.command.SlackRequestHandler
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.SlackApiResponse
import org.springframework.http.MediaType

@Deprecated(message = "")
class SlackRequestHandlerImpl(
    private val restRequester: RestRequester,
    private val botToken: String,
) : SlackRequestHandler {

    @Deprecated(message = "")
    override fun sendToSlackServer(headers: SlackRequestHeaders, body: Any): SlackApiResponse =
        this.restRequester.post(uri = Methods.CHAT_POST_MESSAGE,
            authorizationHeader = this.botToken,
            body = body,
            contentType = MediaType.APPLICATION_JSON,
            responseType = SlackApiResponse::class.java).body
            ?.takeIf { it.ok } ?: throw RuntimeException("Slack Server return failed.")
}