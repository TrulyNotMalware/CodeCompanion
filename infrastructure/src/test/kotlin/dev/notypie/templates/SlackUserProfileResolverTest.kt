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
import java.time.ZoneId
import java.time.ZoneOffset

class SlackUserProfileResolverTest :
    BehaviorSpec({
        val start = Instant.ofEpochSecond(1_714_280_000L)

        class MutableClock(
            var now: Instant,
        ) : Clock() {
            override fun instant(): Instant = now

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId) = this
        }

        fun profileOk(displayName: String = "junho"): Result<ResponseEntity<SlackUserProfileDto>> =
            Result.success(
                ResponseEntity.ok(SlackUserProfileDto(ok = true, profile = createProfile(displayName = displayName))),
            )

        fun requesterReturning(result: Result<ResponseEntity<SlackUserProfileDto>>): RestRequester =
            mockk {
                every {
                    safeGet(
                        uri = any(),
                        authorizationHeader = TEST_BOT_TOKEN,
                        responseType = SlackUserProfileDto::class.java,
                        uriVariables = any(),
                    )
                } returns result
            }

        fun RestRequester.verifyLookups(times: Int, userId: String? = null) =
            verify(exactly = times) {
                safeGet(
                    uri = any(),
                    authorizationHeader = any(),
                    responseType = SlackUserProfileDto::class.java,
                    uriVariables = if (userId == null) any() else mapOf("user" to userId),
                )
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
                    requester.verifyLookups(times = 1)
                }

                then("the user id travels as a URI template variable, not interpolated into the query") {
                    verify(exactly = 1) {
                        requester.safeGet(
                            uri = "users.profile.get?user={user}",
                            authorizationHeader = TEST_BOT_TOKEN,
                            responseType = SlackUserProfileDto::class.java,
                            uriVariables = mapOf("user" to TEST_USER_ID),
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
            val clock = MutableClock(now = start)
            val requester = requesterReturning(Result.failure(RestClientException("429 Too Many Requests")))
            val resolver =
                SlackUserProfileResolver(restRequester = requester, slackApiToken = TEST_BOT_TOKEN, clock = clock)

            `when`("resolved twice within the failure TTL and once after it") {
                val first = resolver.resolve(userId = TEST_USER_ID)
                clock.now = start.plusSeconds(59L)
                resolver.resolve(userId = TEST_USER_ID)
                clock.now = start.plusSeconds(61L)
                resolver.resolve(userId = TEST_USER_ID)

                then("it degrades to the bare mention and asks Slack only once per failure TTL") {
                    first shouldBe PublisherView(displayName = "<@$TEST_USER_ID>", thumbnailUrl = null)
                    requester.verifyLookups(times = 2)
                }
            }
        }

        given("a profile response with ok=false") {
            val requester =
                requesterReturning(
                    Result.success(
                        ResponseEntity.ok(SlackUserProfileDto(ok = false, profile = createProfile(displayName = "x"))),
                    ),
                )
            val resolver =
                SlackUserProfileResolver(
                    restRequester = requester,
                    slackApiToken = TEST_BOT_TOKEN,
                    clock = Clock.fixed(start, ZoneOffset.UTC),
                )

            `when`("resolved twice") {
                val first = resolver.resolve(userId = TEST_USER_ID)
                resolver.resolve(userId = TEST_USER_ID)

                then("it ignores the profile, renders the bare mention and caches that fallback") {
                    first shouldBe PublisherView(displayName = "<@$TEST_USER_ID>", thumbnailUrl = null)
                    requester.verifyLookups(times = 1)
                }
            }
        }

        given("a cached profile older than the TTL") {
            val clock = MutableClock(now = start)
            val requester = requesterReturning(profileOk())
            val resolver =
                SlackUserProfileResolver(
                    restRequester = requester,
                    slackApiToken = TEST_BOT_TOKEN,
                    clock = clock,
                    ttl = Duration.ofMinutes(30L),
                )

            `when`("resolved again after expiry") {
                resolver.resolve(userId = TEST_USER_ID)
                clock.now = start.plus(Duration.ofMinutes(31L))
                resolver.resolve(userId = TEST_USER_ID)

                then("Slack is asked again") {
                    requester.verifyLookups(times = 2)
                }
            }
        }

        given("a full cache holding an expired failure entry") {
            val clock = MutableClock(now = start)
            val requester: RestRequester =
                mockk {
                    every {
                        safeGet(
                            uri = any(),
                            authorizationHeader = TEST_BOT_TOKEN,
                            responseType = SlackUserProfileDto::class.java,
                            uriVariables = mapOf("user" to "UFAIL"),
                        )
                    } returns Result.failure(RestClientException("user_not_found"))
                    every {
                        safeGet(
                            uri = any(),
                            authorizationHeader = TEST_BOT_TOKEN,
                            responseType = SlackUserProfileDto::class.java,
                            uriVariables = neq(mapOf("user" to "UFAIL")),
                        )
                    } returns profileOk()
                }
            val resolver =
                SlackUserProfileResolver(
                    restRequester = requester,
                    slackApiToken = TEST_BOT_TOKEN,
                    clock = clock,
                    maxEntries = 2,
                )

            `when`("a new user arrives after the failure entry expired") {
                resolver.resolve(userId = "UFAIL")
                resolver.resolve(userId = "UA")
                clock.now = start.plus(Duration.ofMinutes(2L))
                resolver.resolve(userId = "UB")
                resolver.resolve(userId = "UA")

                then("the expired entry is evicted and the live one survives") {
                    requester.verifyLookups(times = 1, userId = "UA")
                    requester.verifyLookups(times = 1, userId = "UB")
                }
            }
        }

        given("a full cache with no expired entries") {
            val clock = MutableClock(now = start)
            val requester = requesterReturning(profileOk())
            val resolver =
                SlackUserProfileResolver(
                    restRequester = requester,
                    slackApiToken = TEST_BOT_TOKEN,
                    clock = clock,
                    maxEntries = 2,
                )

            `when`("a third user arrives") {
                resolver.resolve(userId = "UA")
                clock.now = start.plusSeconds(1L)
                resolver.resolve(userId = "UB")
                clock.now = start.plusSeconds(2L)
                resolver.resolve(userId = "UC")
                resolver.resolve(userId = "UB")
                resolver.resolve(userId = "UA")

                then("only the oldest entry is evicted instead of clearing the whole cache") {
                    requester.verifyLookups(times = 1, userId = "UB")
                    requester.verifyLookups(times = 2, userId = "UA")
                }
            }
        }
    })
