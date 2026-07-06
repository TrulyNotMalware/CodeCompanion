package dev.notypie.application.configurations

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.relay.OutboxPayloadRenderer
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.impl.command.*
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.CodecOutboundMessagePort
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.standup.StandupRepository
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.SlackTemplateBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SlackRequestBuilderConfiguration(
    private val appConfig: AppConfig,
) {
    @Bean
    @ConditionalOnMissingBean(SlackTemplateBuilder::class)
    fun slackTemplateBuilder(restRequester: RestRequester): SlackTemplateBuilder =
        ModalTemplateBuilder(restRequester = restRequester, slackApiToken = appConfig.api.token)

    @Bean
    @ConditionalOnMissingBean(MessageDispatcher::class)
    fun messageDispatcher(applicationEventPublisher: ApplicationEventPublisher, retryService: RetryService) =
        ApplicationMessageDispatcher(
            botToken = appConfig.api.token,
            applicationEventPublisher = applicationEventPublisher,
            retryService = retryService,
        )

    @Bean
    @ConditionalOnMissingBean(SlackViewOpenDispatcher::class)
    fun slackViewOpenDispatcher(messageDispatcher: MessageDispatcher): SlackViewOpenDispatcher =
        SlackViewOpenDispatcher(messageDispatcher = messageDispatcher)

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
    fun slackIntentResolver(): SlackIntentResolver = SlackIntentResolver()

    @Bean
    @ConditionalOnMissingBean(SlackOutboundStager::class)
    fun slackOutboundStager(
        slackApiEventConstructor: SlackApiEventConstructor,
        standupRepository: StandupRepository,
    ): SlackOutboundStager =
        SlackOutboundStager(
            slackEventBuilder = slackApiEventConstructor,
            standupRepository = standupRepository,
        )

    @Bean
    @ConditionalOnMissingBean(OutboundRenderer::class)
    fun slackOutboundRenderer(slackApiEventConstructor: SlackApiEventConstructor): OutboundRenderer =
        SlackOutboundRenderer(slackEventBuilder = slackApiEventConstructor)

    @Bean
    @ConditionalOnMissingBean(OutboundMessagePort::class)
    fun outboundMessagePort(): OutboundMessagePort = CodecOutboundMessagePort()

    @Bean
    @ConditionalOnMissingBean(OutboxPayloadRenderer::class)
    fun outboxPayloadRenderer(outboundRenderer: OutboundRenderer): OutboxPayloadRenderer =
        OutboxPayloadRenderer(renderers = mapOf(Transport.SLACK to outboundRenderer))

    @Bean
    @ConditionalOnMissingBean(CommandExecutor::class)
    fun commandExecutor(
        slackIntentResolver: SlackIntentResolver,
        slackOutboundStager: SlackOutboundStager,
        eventPublisher: EventPublisher,
    ): CommandExecutor =
        CommandExecutor(
            intentResolver = slackIntentResolver,
            outboundStager = slackOutboundStager,
            eventPublisher = eventPublisher,
        )
}
