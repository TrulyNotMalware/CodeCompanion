package dev.notypie.application.configurations

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.impl.command.*
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.SlackTemplateBuilder
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
    fun slackTemplateBuilder(restRequester: RestRequester): SlackTemplateBuilder =
        ModalTemplateBuilder(restRequester = restRequester, slackApiToken = appConfig.api.token)

    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 5
//            threadNamePrefix = "ThreadPoolTaskScheduler-" // val cannot be reassigned issue.
            initialize()
        }

    @Bean
    @ConditionalOnMissingBean(MessageDispatcher::class)
    fun messageDispatcher(
        applicationEventPublisher: ApplicationEventPublisher,
        threadPoolTaskScheduler: ThreadPoolTaskScheduler,
        outboxRepository: MessageOutboxRepository,
        retryService: RetryService,
    ) = ApplicationMessageDispatcher(
        botToken = appConfig.api.token,
        applicationEventPublisher = applicationEventPublisher,
        taskScheduler = threadPoolTaskScheduler,
        retryService = retryService,
        outboxRepository = outboxRepository,
    )

    @Bean
    @ConditionalOnMissingBean(SlackEventAsyncDispatcher::class)
    fun slackEventAsyncDispatcher(messageDispatcher: MessageDispatcher): SlackEventAsyncDispatcher =
        SlackEventAsyncDispatcher(messageDispatcher = messageDispatcher)

    @Bean
    @ConditionalOnMissingBean(InteractionPayloadParser::class)
    fun interactionRequestParser(): InteractionPayloadParser = SlackInteractionRequestParser()

    @Bean
    @ConditionalOnMissingBean(SlackApiEventConstructor::class)
    fun slackApiEventConstructor(slackTemplateBuilder: SlackTemplateBuilder): SlackApiEventConstructor =
        SlackApiEventConstructor(
            botToken = appConfig.api.token,
            templateBuilder = slackTemplateBuilder,
        )

    @Bean
    @ConditionalOnMissingBean(SlackIntentResolver::class)
    fun slackIntentResolver(slackApiEventConstructor: SlackApiEventConstructor): SlackIntentResolver =
        SlackIntentResolver(slackEventBuilder = slackApiEventConstructor)

    @Bean
    @ConditionalOnMissingBean(CommandExecutor::class)
    fun commandExecutor(slackIntentResolver: SlackIntentResolver, eventPublisher: EventPublisher): CommandExecutor =
        CommandExecutor(
            intentResolver = slackIntentResolver,
            eventPublisher = eventPublisher,
        )
}
