package dev.notypie.application.configurations

import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.impl.command.InteractionPayloadParser
import dev.notypie.impl.command.SlackApiClientImpl
import dev.notypie.impl.command.SlackInteractionRequestParser
import dev.notypie.impl.command.ApplicationMessageDispatcher
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.SlackTemplateBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
class SlackRequestBuilderConfiguration(
    private val appConfig: AppConfig,
) {

    @Bean
    @ConditionalOnMissingBean(SlackTemplateBuilder::class)
    fun slackTemplateBuilder() : SlackTemplateBuilder = ModalTemplateBuilder()

    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler
    = ThreadPoolTaskScheduler().apply {
            poolSize = 5
            threadNamePrefix = "ThreadPoolTaskScheduler-"
            initialize()
    }

    @Bean
    @ConditionalOnMissingBean(MessageDispatcher::class)
    fun messageDispatcher(
        applicationEventPublisher: ApplicationEventPublisher,
        threadPoolTaskScheduler: ThreadPoolTaskScheduler,
        outboxRepository: MessageOutboxRepository
    ) = ApplicationMessageDispatcher(
        botToken = appConfig.api.token, applicationEventPublisher = applicationEventPublisher,
        taskScheduler = threadPoolTaskScheduler)

    @Bean
    @ConditionalOnMissingBean(SlackApiRequester::class)
    fun slackRequestBuilder(slackTemplateBuilder: SlackTemplateBuilder,
                            applicationEventPublisher: ApplicationEventPublisher,
                            slackMessageDispatcher: MessageDispatcher): SlackApiRequester = SlackApiClientImpl(
        botToken = appConfig.api.token, templateBuilder = slackTemplateBuilder,
        messageDispatcher = slackMessageDispatcher
    )

    @Bean
    @ConditionalOnMissingBean(InteractionPayloadParser::class)
    fun interactionRequestParser(): InteractionPayloadParser = SlackInteractionRequestParser()
}