package dev.notypie.impl.command

import com.slack.api.RequestConfigurator
import com.slack.api.Slack
import com.slack.api.methods.response.chat.ChatPostEphemeralResponse
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.util.http.SlackHttpClient.buildOkHttpClient
import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.dto.ActionEventContents
import dev.notypie.domain.command.dto.DelayHandleEventContents
import dev.notypie.domain.command.dto.PostEventContents
import dev.notypie.domain.command.dto.MessageType
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.history.entity.Status
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.toOutboxMessage
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant


class ApplicationMessageDispatcher(
    private val botToken: String,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val taskScheduler: ThreadPoolTaskScheduler
): MessageDispatcher {

    private val slack: Slack = Slack.getInstance()
    private val okHttpClient = buildOkHttpClient(this.slack.config)
    private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()

    override fun dispatch(event: PostEventContents, commandType: CommandType): CommandOutput{
        //FIXME publish outbox save event. Listen in RelayService.
//        this.applicationEventPublisher.publishEvent(event)
//        this.outboxRepository.save(event.toOutboxMessage())
        this.applicationEventPublisher.publishEvent(event)
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
//        this.outboxRepository.save(event.toOutboxMessage())
        this.applicationEventPublisher.publishEvent(event)
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun listen(event: DelayHandleEventContents){

    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun listen(event: PostEventContents){
        when(event.messageType){
            MessageType.EPHEMERAL_MESSAGE -> dispatchEphemeralContents(event = event)
            MessageType.DIRECT_MESSAGE -> dispatchChatPostMessageContents(event = event)
            MessageType.CHANNEL_ALERT -> dispatchChatPostMessageContents(event = event)
            MessageType.ACTION_RESPONSE -> TODO() //FIXME replace PostEventContents to ActionResponse
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun listen(event: ActionEventContents) = this.dispatchActionResponseContents(event = event)

    private fun dispatchEphemeralContents(event: PostEventContents){
        val requestConfigurer = RequestConfigurator<FormBody.Builder> { builder ->
                for ((key, value) in event.body) builder.add(key, value)
                builder
        }
        val result = slack.methods().postFormWithTokenAndParseResponse(requestConfigurer,
            "chat.postEphemeral", this.botToken, ChatPostEphemeralResponse::class.java)
    }

    private fun dispatchChatPostMessageContents(event: PostEventContents){
        val requestConfigurer = RequestConfigurator<FormBody.Builder> { builder ->
            for ((key, value) in event.body) builder.add(key, value)
            builder
        }
        val result = slack.methods().postFormWithTokenAndParseResponse(requestConfigurer,
            "chat.postMessage", this.botToken, ChatPostMessageResponse::class.java)
    }

    private fun dispatchActionResponseContents(event: ActionEventContents){
        val requestBody = event.body.toRequestBody(MEDIA_TYPE_JSON)
        val request = Request.Builder().url(event.responseUrl).post(requestBody).build()
        val result = this.okHttpClient.newCall(request).execute()

    }
}