package dev.notypie.application.configurations

import dev.notypie.application.controllers.GoogleOAuthCallbackController
import dev.notypie.application.service.calendar.CalendarConnectionDisabledResponder
import dev.notypie.application.service.calendar.CalendarConnectionService
import dev.notypie.application.service.calendar.CalendarSyncScheduler
import dev.notypie.application.service.calendar.CalendarSyncService
import dev.notypie.application.service.calendar.GoogleAccessTokenProvider
import dev.notypie.application.service.calendar.GoogleTokenRevocationWorker
import dev.notypie.application.service.calendar.MeetingCalendarMirror
import dev.notypie.application.service.calendar.MeetingCalendarMirrorService
import dev.notypie.application.service.calendar.NoopMeetingCalendarMirror
import dev.notypie.domain.command.createCalendarConnectionRequestEvent
import dev.notypie.domain.command.entity.event.CalendarConnectionAction
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.calendar.GoogleCalendarClient
import dev.notypie.impl.calendar.GoogleOAuthClient
import dev.notypie.impl.calendar.TokenCipher
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.GoogleOAuthStateRepository
import dev.notypie.repository.calendar.MeetingCalendarEventRepository
import dev.notypie.repository.meeting.MeetingRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.util.Base64

class CalendarConfigurationTest :
    BehaviorSpec({
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val stager = mockk<OutboundMessageStager>()
        val connections = mockk<GoogleCalendarConnectionRepository>()
        val contextRunner =
            ApplicationContextRunner()
                .withUserConfiguration(
                    AppConfigBinding::class.java,
                    CalendarConfiguration::class.java,
                    CalendarDisabledConfiguration::class.java,
                    GoogleOAuthCallbackController::class.java,
                    CalendarSyncScheduler::class.java,
                ).withBean(Clock::class.java, { Clock.systemUTC() })
                .withBean(PlatformTransactionManager::class.java, { mockk(relaxed = true) })
                .withBean(OutboundMessageStager::class.java, { stager })
                .withBean(EventPublisher::class.java, { mockk(relaxed = true) })
                .withBean(GoogleCalendarConnectionRepository::class.java, { connections })
                .withBean(GoogleOAuthStateRepository::class.java, { mockk(relaxed = true) })
                .withBean(MeetingCalendarEventRepository::class.java, { mockk(relaxed = true) })
                .withBean(MeetingRepository::class.java, { mockk(relaxed = true) })
                .withBean("entityManagerFactory", Any::class.java, { Any() })

        val fullyConfigured =
            arrayOf(
                "slack.app.calendar.google.client-id=cid",
                "slack.app.calendar.google.client-secret=secret",
                "slack.app.calendar.google.redirect-uri=https://bot.example.com/oauth/google/callback",
                "slack.app.calendar.google.token-encryption-key=$key",
            )

        fun calendarListenerBeans(context: ApplicationContext): List<String> =
            context
                .getBeansOfType(Any::class.java)
                .filter { (_, bean) ->
                    bean is CalendarConnectionService || bean is CalendarConnectionDisabledResponder
                }.keys
                .sorted()

        fun statusRequestReplies(context: ApplicationContext): Int {
            clearMocks(stager, connections)
            val staged = mutableListOf<OutboundMessage>()
            every { stager.stage(message = capture(staged), basicInfo = any()) } returns mockk(relaxed = true)
            every { connections.find(userId = any()) } returns null
            context.publishEvent(createCalendarConnectionRequestEvent(action = CalendarConnectionAction.STATUS))
            return staged.size
        }

        given("every spelling of the enabled flag") {
            listOf(
                "true" to "enabled",
                "yes" to "enabled",
                "on" to "enabled",
                "1" to "enabled",
                "false" to "disabled",
                "no" to "disabled",
            ).forEach { (value, expected) ->
                `when`("slack.app.calendar.google.enabled=$value") {
                    then(
                        "exactly one listener bean exists, it is the $expected one, and one status request gets one reply",
                    ) {
                        contextRunner
                            .withPropertyValues("slack.app.calendar.google.enabled=$value", *fullyConfigured)
                            .run { context ->
                                context.startupFailure shouldBe null
                                calendarListenerBeans(context) shouldBe
                                    if (expected == "enabled") {
                                        listOf("calendarConnectionService")
                                    } else {
                                        listOf("calendarConnectionDisabledResponder")
                                    }
                                statusRequestReplies(context) shouldBe 1
                            }
                    }
                }
            }

            `when`("the flag is absent") {
                then("only the disabled responder exists and it answers once") {
                    contextRunner.run { context ->
                        context.startupFailure shouldBe null
                        calendarListenerBeans(context) shouldBe listOf("calendarConnectionDisabledResponder")
                        statusRequestReplies(context) shouldBe 1
                    }
                }
            }

            `when`("the flag is present but blank (an empty environment variable)") {
                then("startup fails on the binding instead of silently answering nothing") {
                    contextRunner
                        .withPropertyValues("slack.app.calendar.google.enabled=")
                        .run { context ->
                            val failure = checkNotNull(context.startupFailure)
                            generateSequence<Throwable>(failure) { it.cause }
                                .map { it.message.orEmpty() }
                                .joinToString() shouldContain "slack.app.calendar.google.enabled"
                        }
                }
            }

            `when`("the profile's placeholder meets an empty GOOGLE_CALENDAR_ENABLED") {
                then("the empty value wins over the placeholder default and startup fails on the binding") {
                    contextRunner
                        .withPropertyValues(
                            "GOOGLE_CALENDAR_ENABLED=",
                            "slack.app.calendar.google.enabled=\${GOOGLE_CALENDAR_ENABLED:false}",
                        ).run { context ->
                            val failure = checkNotNull(context.startupFailure)
                            generateSequence<Throwable>(failure) { it.cause }
                                .map { it.message.orEmpty() }
                                .joinToString() shouldContain "slack.app.calendar.google.enabled"
                        }
                }
            }
        }

        given("the integration enabled") {
            `when`("the context starts with complete credentials") {
                then("the cipher, OAuth client, worker, service and callback controller are all wired") {
                    contextRunner
                        .withPropertyValues("slack.app.calendar.google.enabled=true", *fullyConfigured)
                        .run { context ->
                            context.startupFailure shouldBe null
                            context.getBean(TokenCipher::class.java)
                            context.getBean(GoogleOAuthClient::class.java)
                            context.getBean(GoogleTokenRevocationWorker::class.java)
                            context.getBean(CalendarConnectionService::class.java)
                            context.getBean(GoogleOAuthCallbackController::class.java)
                            context.getBeanNamesForType(CalendarConnectionDisabledResponder::class.java).size shouldBe 0
                        }
                }

                then("the mirror sync lane is wired and the meeting hooks get the queueing mirror") {
                    contextRunner
                        .withPropertyValues("slack.app.calendar.google.enabled=true", *fullyConfigured)
                        .run { context ->
                            context.startupFailure shouldBe null
                            context
                                .getBeansOfType(MeetingCalendarMirror::class.java)
                                .values
                                .single()
                                .shouldBeInstanceOf<MeetingCalendarMirrorService>()
                            context.getBean(CalendarSyncService::class.java)
                            context.getBean(GoogleCalendarClient::class.java)
                            context.getBean(GoogleAccessTokenProvider::class.java)
                            context.getBean(CalendarSyncScheduler::class.java)
                        }
                }
            }

            `when`("the token encryption key is blank") {
                then("the context refuses to start and names the environment variable") {
                    contextRunner
                        .withPropertyValues(
                            "slack.app.calendar.google.enabled=true",
                            "slack.app.calendar.google.client-id=cid",
                            "slack.app.calendar.google.client-secret=secret",
                            "slack.app.calendar.google.redirect-uri=https://bot.example.com/oauth/google/callback",
                        ).run { context ->
                            val failure = checkNotNull(context.startupFailure)
                            generateSequence<Throwable>(failure) { it.cause }
                                .last()
                                .message shouldContain "GOOGLE_TOKEN_ENCRYPTION_KEY"
                        }
                }
            }

            `when`("the redirect URI is not absolute") {
                then("the context refuses to start") {
                    contextRunner
                        .withPropertyValues(
                            "slack.app.calendar.google.enabled=true",
                            "slack.app.calendar.google.client-id=cid",
                            "slack.app.calendar.google.client-secret=secret",
                            "slack.app.calendar.google.redirect-uri=/oauth/google/callback",
                            "slack.app.calendar.google.token-encryption-key=$key",
                        ).run { context ->
                            val failure = checkNotNull(context.startupFailure)
                            generateSequence<Throwable>(failure) { it.cause }
                                .last()
                                .message shouldContain "GOOGLE_OAUTH_REDIRECT_URI"
                        }
                }
            }
        }

        given("the integration disabled") {
            `when`("the context starts without any Google credentials") {
                then("no Google bean is created and the callback controller is absent") {
                    contextRunner
                        .withPropertyValues("slack.app.calendar.google.enabled=false")
                        .run { context ->
                            context.startupFailure shouldBe null
                            context.getBeanNamesForType(TokenCipher::class.java).size shouldBe 0
                            context.getBeanNamesForType(GoogleOAuthClient::class.java).size shouldBe 0
                            context.getBeanNamesForType(CalendarConnectionService::class.java).size shouldBe 0
                            context.getBeanNamesForType(GoogleOAuthCallbackController::class.java).size shouldBe 0
                            context.getBean(CalendarConnectionDisabledResponder::class.java)
                        }
                }

                then("the meeting hooks get the no-op mirror and no sync bean exists") {
                    contextRunner
                        .withPropertyValues("slack.app.calendar.google.enabled=false")
                        .run { context ->
                            context.startupFailure shouldBe null
                            context
                                .getBeansOfType(MeetingCalendarMirror::class.java)
                                .values
                                .single() shouldBeSameInstanceAs NoopMeetingCalendarMirror
                            context.getBeanNamesForType(CalendarSyncService::class.java).size shouldBe 0
                            context.getBeanNamesForType(GoogleCalendarClient::class.java).size shouldBe 0
                            context.getBeanNamesForType(GoogleAccessTokenProvider::class.java).size shouldBe 0
                            context.getBeanNamesForType(CalendarSyncScheduler::class.java).size shouldBe 0
                        }
                }
            }
        }
    })
