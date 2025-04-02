package dev.notypie.impl.command

import com.slack.api.RequestConfigurator
import com.slack.api.Slack
import com.slack.api.methods.response.chat.ChatPostEphemeralResponse
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.util.http.SlackHttpClient.buildOkHttpClient
import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.dto.*
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.history.entity.Status
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.schema.toOutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Instant

private val logger = KotlinLogging.logger {  }

class ApplicationMessageDispatcher(
    private val botToken: String,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val taskScheduler: ThreadPoolTaskScheduler,
    private val retryService: RetryService
): MessageDispatcher {

    private val slack: Slack = Slack.getInstance()
    private val okHttpClient = buildOkHttpClient(this.slack.config)
    private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()

    override fun dispatch(event: PostEventContents, commandType: CommandType): CommandOutput{
        this.applicationEventPublisher.publishEvent(event.toOutboxMessage())
        return CommandOutput(
            ok = true,
            apiAppId = event.apiAppId,
            status = Status.IN_PROGRESSED,
            idempotencyKey = event.idempotencyKey,
            publisherId = event.publisherId,
            channel = event.channel,
            commandType = commandType,
            commandDetailType = event.commandDetailType,
        )
    }

    override fun dispatch(event: ActionEventContents, commandType: CommandType): CommandOutput{
        this.applicationEventPublisher.publishEvent(event.toOutboxMessage())
        return CommandOutput(
            ok = true,
            apiAppId = event.apiAppId,
            status = Status.IN_PROGRESSED,
            idempotencyKey = event.idempotencyKey,
            publisherId = event.publisherId,
            channel = event.channel,
            commandType = commandType,
            commandDetailType = event.commandDetailType,
        )
    }

    override fun dispatch(event: DelayHandleEventContents, commandType: CommandType): CommandOutput {
        this.taskScheduler.schedule({
            this.applicationEventPublisher.publishEvent(event)
        }, Instant.now().plus(event.delayTime, event.timeUnit))
        return CommandOutput(
            ok = true,
            apiAppId = event.apiAppId,
            status = Status.IN_PROGRESSED,
            idempotencyKey = event.idempotencyKey,
            publisherId = event.publisherId,
            channel = event.channel,
            commandType = commandType,
            commandDetailType = event.commandDetailType,
        )
    }

    override fun dispatch(event: SlackEvent): CommandOutput =
        this.retryService.execute(
            action = {
                when(event){
                    is ActionEventContents -> this.dispatchActionResponseContents(event = event)
                    is DelayHandleEventContents -> TODO()
                    is PostEventContents ->
                        when(event.messageType){
                            MessageType.EPHEMERAL_MESSAGE -> dispatchEphemeralContents(event=event)
                            MessageType.CHANNEL_ALERT -> dispatchChatPostMessageContents(event=event)
                            MessageType.DIRECT_MESSAGE -> dispatchChatPostMessageContents(event=event)
                            MessageType.ACTION_RESPONSE -> TODO()
                        }
                }
            }
        )

    @EventListener
    fun listenSlackEvent(event: SlackEvent) = this.dispatch(event = event)

    private fun dispatchEphemeralContents(event: PostEventContents): CommandOutput{
        val requestConfigurer = RequestConfigurator<FormBody.Builder> { builder ->
                for ((key, value) in event.body) builder.add(key, value.toString())
                builder
        }
        val result = slack.methods().postFormWithTokenAndParseResponse(requestConfigurer,
            "chat.postEphemeral", this.botToken, ChatPostEphemeralResponse::class.java)
        return returnSuccessOrFailed(result = result, event = event)
    }

    private fun dispatchChatPostMessageContents(event: PostEventContents): CommandOutput{
        val requestConfigurer = RequestConfigurator<FormBody.Builder> { builder ->
            for ((key, value) in event.body) builder.add(key, value.toString())
            builder
        }
        val result = slack.methods().postFormWithTokenAndParseResponse(requestConfigurer,
            "chat.postMessage", this.botToken, ChatPostMessageResponse::class.java)
        return returnSuccessOrFailed(result = result, event = event)
    }

    private fun dispatchActionResponseContents(event: ActionEventContents): CommandOutput {
        val requestBody = event.body.toRequestBody(MEDIA_TYPE_JSON)
        val request = Request.Builder().url(event.responseUrl).post(requestBody).build()
        val result = this.okHttpClient.newCall(request).execute()
        result.close()
        return returnSuccessOrFailed(result = result, event = event)
    }

    private fun returnSuccessOrFailed(result: ChatPostEphemeralResponse, event: SlackEvent) =
        if(result.isOk) CommandOutput.success(event = event)
        else CommandOutput.fail(event=event, reason = result.error)

    private fun returnSuccessOrFailed(result: ChatPostMessageResponse, event: SlackEvent) =
        if(result.isOk) CommandOutput.success(event = event)
        else CommandOutput.fail(event=event, reason = result.error)

    private fun returnSuccessOrFailed(result: Response, event: SlackEvent) =
        if(result.isSuccessful) CommandOutput.success(event = event)
        else CommandOutput.fail(event=event, reason = result.message)
}