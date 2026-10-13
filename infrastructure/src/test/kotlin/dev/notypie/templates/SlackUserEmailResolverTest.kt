package dev.notypie.templates

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.notypie.common.jsonMapper
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.dto.SlackUserInfoDto
import dev.notypie.impl.command.dto.createUserInfoErrorJson
import dev.notypie.impl.command.dto.createUserInfoResponseJson
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClientException

class SlackUserEmailResolverTest :
    BehaviorSpec({
        fun decoded(json: String): Result<ResponseEntity<SlackUserInfoDto>> =
            Result.success(ResponseEntity.ok(jsonMapper.readValue(json, SlackUserInfoDto::class.java)))

        fun requesterReturning(result: Result<ResponseEntity<SlackUserInfoDto>>): RestRequester =
            mockk {
                every {
                    safeGet(
                        uri = "users.info?user={user}",
                        authorizationHeader = TEST_BOT_TOKEN,
                        responseType = SlackUserInfoDto::class.java,
                        uriVariables = mapOf("user" to TEST_USER_ID),
                    )
                } returns result
            }

        fun warningsDuring(block: () -> Unit): List<String> {
            val logger = LoggerFactory.getLogger("dev.notypie.templates") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
            }
            return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        }

        given("a users.info body with the e-mail and many fields the DTO does not model") {
            val requester = requesterReturning(result = decoded(json = createUserInfoResponseJson()))
            val resolver = SlackUserEmailResolver(restRequester = requester, slackApiToken = TEST_BOT_TOKEN)

            `when`("the user is resolved twice") {
                lateinit var emails: List<String?>
                val warnings =
                    warningsDuring { emails = List(size = 2) { resolver.resolve(userId = TEST_USER_ID) } }

                then("the unknown fields are ignored and the profile e-mail comes back without a warning") {
                    emails shouldBe listOf("test@example.com", "test@example.com")
                    warnings.shouldBeEmpty()
                }

                then("each resolve asks Slack, with the user id as a URI template variable, nothing cached") {
                    verify(exactly = 2) {
                        requester.safeGet(
                            uri = "users.info?user={user}",
                            authorizationHeader = TEST_BOT_TOKEN,
                            responseType = SlackUserInfoDto::class.java,
                            uriVariables = mapOf("user" to TEST_USER_ID),
                        )
                    }
                }
            }
        }

        given("a users.info body whose profile has no email key, as without users:read.email") {
            val resolver =
                SlackUserEmailResolver(
                    restRequester =
                        requesterReturning(result = decoded(json = createUserInfoResponseJson(email = null))),
                    slackApiToken = TEST_BOT_TOKEN,
                )

            then("there is no e-mail, and the resolver itself does not warn") {
                var email: String? = "unresolved"
                val warnings = warningsDuring { email = resolver.resolve(userId = TEST_USER_ID) }
                email.shouldBeNull()
                warnings.shouldBeEmpty()
            }
        }

        given("a users.info body with a blank e-mail") {
            val resolver =
                SlackUserEmailResolver(
                    restRequester = requesterReturning(result = decoded(json = createUserInfoResponseJson(email = ""))),
                    slackApiToken = TEST_BOT_TOKEN,
                )

            then("the blank e-mail counts as none") {
                resolver.resolve(userId = TEST_USER_ID).shouldBeNull()
            }
        }

        given("users.info answering ok=false") {
            val resolver =
                SlackUserEmailResolver(
                    restRequester =
                        requesterReturning(result = decoded(json = createUserInfoErrorJson(error = "missing_scope"))),
                    slackApiToken = TEST_BOT_TOKEN,
                )

            then("there is no e-mail and one WARN names the user id and Slack's error code") {
                val warnings = warningsDuring { resolver.resolve(userId = TEST_USER_ID).shouldBeNull() }
                warnings shouldHaveSize 1
                warnings.single() shouldContain TEST_USER_ID
                warnings.single() shouldContain "error=missing_scope"
            }
        }

        given("a users.info call that fails") {
            val resolver =
                SlackUserEmailResolver(
                    restRequester =
                        requesterReturning(result = Result.failure(RestClientException("connect timed out"))),
                    slackApiToken = TEST_BOT_TOKEN,
                )

            then("there is no e-mail and one WARN names the user id and the failure type, not its message") {
                val warnings = warningsDuring { resolver.resolve(userId = TEST_USER_ID).shouldBeNull() }
                warnings shouldHaveSize 1
                warnings.single() shouldContain TEST_USER_ID
                warnings.single() shouldContain "RestClientException"
                warnings.single() shouldNotContain "connect timed out"
            }
        }
    })
