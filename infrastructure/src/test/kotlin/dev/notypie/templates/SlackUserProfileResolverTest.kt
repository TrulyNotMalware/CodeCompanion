package dev.notypie.templates

import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.dto.SlackUserProfileDto
import dev.notypie.impl.command.dto.createProfile
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClientException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SlackUserProfileResolverTest :
    BehaviorSpec({
        val start = Instant.ofEpochSecond(1_714_280_000L)

        fun requesterReturning(result: Result<ResponseEntity<SlackUserProfileDto>>): RestRequester =
            mockk {
                every {
                    safeGet(
                        uri = any(),
                        authorizationHeader = TEST_BOT_TOKEN,
                        responseType = SlackUserProfileDto::class.java,
                    )
                } returns result
            }

        given("a profile that resolves") {
            val requester =
                requesterReturning(
                    Result.success(
                        ResponseEntity.ok(
                            SlackUserProfileDto(
                                ok = true,
                                profile = createProfile(displayName = "junho", imageSize24 = "https://img/24.png"),
                            ),
                        ),
                    ),
                )
            val resolver =
                SlackUserProfileResolver(
                    restRequester = requester,
                    slackApiToken = TEST_BOT_TOKEN,
                    clock = Clock.fixed(start, ZoneOffset.UTC),
                )

            `when`("the same user is resolved twice") {
                val first = resolver.resolve(userId = TEST_USER_ID)
                val second = resolver.resolve(userId = TEST_USER_ID)

                then("Slack is called once and both calls return the display name and thumbnail") {
                    first shouldBe PublisherView(displayName = "junho", thumbnailUrl = "https://img/24.png")
                    second shouldBe first
                    verify(exactly = 1) {
                        requester.safeGet(
                            uri = any(),
                            authorizationHeader = any(),
                            responseType = SlackUserProfileDto::class.java,
                        )
                    }
                }
            }
        }

        given("a profile whose display name is blank") {
            val requester =
                requesterReturning(
                    Result.success(
                        ResponseEntity.ok(
                            SlackUserProfileDto(
                                ok = true,
                                profile = createProfile(displayName = "", realName = "Jun Ho", imageSize24 = ""),
                            ),
                        ),
                    ),
                )
            val resolver = SlackUserProfileResolver(restRequester = requester, slackApiToken = TEST_BOT_TOKEN)

            `when`("resolved") {
                then("it falls back to the real name and omits the empty thumbnail") {
                    resolver.resolve(userId = TEST_USER_ID) shouldBe
                        PublisherView(displayName = "Jun Ho", thumbnailUrl = null)
                }
            }
        }

        given("a lookup that fails") {
            val requester = requesterReturning(Result.failure(RestClientException("429 Too Many Requests")))
            val resolver = SlackUserProfileResolver(restRequester = requester, slackApiToken = TEST_BOT_TOKEN)

            `when`("resolved") {
                then("it degrades to the bare mention and does not cache the failure") {
                    resolver.resolve(userId = TEST_USER_ID) shouldBe
                        PublisherView(displayName = "<@$TEST_USER_ID>", thumbnailUrl = null)
                    resolver.resolve(userId = TEST_USER_ID)
                    verify(exactly = 2) {
                        requester.safeGet(
                            uri = any(),
                            authorizationHeader = any(),
                            responseType = SlackUserProfileDto::class.java,
                        )
                    }
                }
            }
        }

        given("a cached profile older than the TTL") {
            val ticking = mutableListOf(start, start.plus(Duration.ofMinutes(31L)))
            val clock =
                object : Clock() {
                    override fun instant(): Instant = ticking.removeFirst()

                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId) = this
                }
            val requester =
                requesterReturning(
                    Result.success(
                        ResponseEntity.ok(
                            SlackUserProfileDto(ok = true, profile = createProfile(displayName = "junho")),
                        ),
                    ),
                )
            val resolver =
                SlackUserProfileResolver(
                    restRequester = requester,
                    slackApiToken = TEST_BOT_TOKEN,
                    clock = clock,
                    ttl = Duration.ofMinutes(30L),
                )

            `when`("resolved again after expiry") {
                resolver.resolve(userId = TEST_USER_ID)
                resolver.resolve(userId = TEST_USER_ID)

                then("Slack is asked again") {
                    verify(exactly = 2) {
                        requester.safeGet(
                            uri = any(),
                            authorizationHeader = any(),
                            responseType = SlackUserProfileDto::class.java,
                        )
                    }
                }
            }
        }
    })
