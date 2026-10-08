package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnGoogleCalendarDisabled
import dev.notypie.application.configurations.conditions.OnGoogleCalendarEnabled
import dev.notypie.application.service.calendar.CalendarConnectionDisabledResponder
import dev.notypie.application.service.calendar.CalendarConnectionService
import dev.notypie.application.service.calendar.GoogleTokenRevocationWorker
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.calendar.GoogleOAuthClient
import dev.notypie.impl.calendar.TokenCipher
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.GoogleOAuthStateRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

private val log = KotlinLogging.logger {}

const val CALENDAR_GOOGLE_PROPERTIES_PREFIX = "slack.app.calendar.google"
const val GOOGLE_TOKEN_REVOCATION_EXECUTOR = "googleTokenRevocationExecutor"

@Configuration
@Conditional(OnGoogleCalendarEnabled::class)
class CalendarConfiguration(
    private val appConfig: AppConfig,
) {
    @Bean
    fun tokenCipher(): TokenCipher {
        val key = appConfig.calendar.google.tokenEncryptionKey
        check(key.isNotBlank()) {
            "$CALENDAR_GOOGLE_PROPERTIES_PREFIX.token-encryption-key is blank; " +
                "set GOOGLE_TOKEN_ENCRYPTION_KEY to a base64 32-byte key (openssl rand -base64 32)"
        }
        return TokenCipher(keyBase64 = key)
    }

    @Bean
    fun googleOAuthClient(): GoogleOAuthClient {
        val google = appConfig.calendar.google
        check(google.clientId.isNotBlank()) {
            "$CALENDAR_GOOGLE_PROPERTIES_PREFIX.client-id is blank; set GOOGLE_OAUTH_CLIENT_ID"
        }
        check(google.clientSecret.isNotBlank()) {
            "$CALENDAR_GOOGLE_PROPERTIES_PREFIX.client-secret is blank; set GOOGLE_OAUTH_CLIENT_SECRET"
        }
        check(google.redirectUri.startsWith("https://") || google.redirectUri.startsWith("http://")) {
            "$CALENDAR_GOOGLE_PROPERTIES_PREFIX.redirect-uri must be an absolute URL; set GOOGLE_OAUTH_REDIRECT_URI"
        }
        return GoogleOAuthClient(
            clientId = google.clientId,
            clientSecret = google.clientSecret,
            redirectUri = google.redirectUri,
            requestTimeout = Duration.ofSeconds(google.requestTimeoutSeconds),
        )
    }

    @Bean(name = [GOOGLE_TOKEN_REVOCATION_EXECUTOR])
    @DependsOn("entityManagerFactory")
    fun googleTokenRevocationExecutor(): ThreadPoolTaskExecutor =
        GoogleTokenRevocationExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 2
            queueCapacity = REVOCATION_QUEUE_CAPACITY
            setThreadNamePrefix("google-revoke-")
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
            setWaitForTasksToCompleteOnShutdown(false)
            setAwaitTerminationSeconds(0)
            initialize()
        }

    @Bean
    fun googleTokenRevocationWorker(
        googleOAuthClient: GoogleOAuthClient,
        tokenCipher: TokenCipher,
        outboundStager: OutboundMessageStager,
        eventPublisher: EventPublisher,
        connectionRepository: GoogleCalendarConnectionRepository,
        transactionManager: PlatformTransactionManager,
        @Qualifier(GOOGLE_TOKEN_REVOCATION_EXECUTOR) executor: Executor,
    ): GoogleTokenRevocationWorker =
        GoogleTokenRevocationWorker(
            oauthClient = googleOAuthClient,
            tokenCipher = tokenCipher,
            outboundStager = outboundStager,
            eventPublisher = eventPublisher,
            connectionRepository = connectionRepository,
            transactionManager = transactionManager,
            executor = executor,
        )

    @Bean
    fun calendarConnectionService(
        connectionRepository: GoogleCalendarConnectionRepository,
        stateRepository: GoogleOAuthStateRepository,
        googleOAuthClient: GoogleOAuthClient,
        tokenCipher: TokenCipher,
        outboundStager: OutboundMessageStager,
        eventPublisher: EventPublisher,
        applicationEventPublisher: ApplicationEventPublisher,
        clock: Clock,
        transactionManager: PlatformTransactionManager,
    ): CalendarConnectionService =
        CalendarConnectionService(
            connectionRepository = connectionRepository,
            stateRepository = stateRepository,
            oauthClient = googleOAuthClient,
            tokenCipher = tokenCipher,
            outboundStager = outboundStager,
            eventPublisher = eventPublisher,
            applicationEventPublisher = applicationEventPublisher,
            clock = clock,
            transactionManager = transactionManager,
            appConfig = appConfig,
        )

    companion object {
        private const val REVOCATION_QUEUE_CAPACITY = 100
    }
}

// Revokes are best-effort: a shutdown drops queued ones at once so the Pod's termination budget stays unchanged.
class GoogleTokenRevocationExecutor : ThreadPoolTaskExecutor() {
    override fun shutdown() {
        val queued = threadPoolExecutor.queue.size
        if (queued > 0) log.warn { "Dropping $queued queued Google token revoke(s) at shutdown" }
        super.shutdown()
    }
}

@Configuration
@Conditional(OnGoogleCalendarDisabled::class)
class CalendarDisabledConfiguration {
    @Bean
    fun calendarConnectionDisabledResponder(
        outboundStager: OutboundMessageStager,
        eventPublisher: EventPublisher,
        transactionManager: PlatformTransactionManager,
    ): CalendarConnectionDisabledResponder =
        CalendarConnectionDisabledResponder(
            outboundStager = outboundStager,
            eventPublisher = eventPublisher,
            transactionManager = transactionManager,
        )
}
