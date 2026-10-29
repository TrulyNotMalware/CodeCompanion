package dev.notypie.templates

import ch.qos.logback.classic.Level
import dev.notypie.common.jsonMapper
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.dto.SlackUserProfileDto
import dev.notypie.impl.command.dto.createMinimalUserProfileResponseJson
import dev.notypie.impl.command.dto.createProfile
import dev.notypie.impl.command.dto.createUserProfileErrorJson
import dev.notypie.impl.command.dto.createUserProfileResponseJson
import dev.notypie.logging.captureLogs
import dev.notypie.logging.eventsAt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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

        given("a users.profile.get body without an email key, as Slack sends it without users:read.email") {
            val dto =
                jsonMapper.readValue(
                    createUserProfileResponseJson(displayName = "junho", email = null),
                    SlackUserProfileDto::class.java,
                )
            val resolver =
                SlackUserProfileResolver(
                    restRequester = requesterReturning(Result.success(ResponseEntity.ok(dto))),
                    slackApiToken = TEST_BOT_TOKEN,
                )

            then("the body still decodes, so the publisher is named instead of the bare mention") {
                dto.profile?.displayName shouldBe "junho"
                resolver.resolve(userId = TEST_USER_ID) shouldBe
                    PublisherView(displayName = "junho", thumbnailUrl = "https://example.com/img24.png")
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

        given("a lookup whose request fails with an exception") {
            val requester = requesterReturning(Result.failure(RestClientException("429 Too Many Requests")))
            val resolver =
                SlackUserProfileResolver(
                    restRequester = requester,
                    slackApiToken = TEST_BOT_TOKEN,
                    clock = Clock.fixed(start, ZoneOffset.UTC),
                )

            `when`("resolved once") {
                val (view, logs) =
                    captureLogs(loggerName = "dev.notypie.templates") { resolver.resolve(userId = TEST_USER_ID) }

                then("it logs exactly one WARN naming the exception class and carrying no throwable") {
                    view shouldBe PublisherView(displayName = "<@$TEST_USER_ID>", thumbnailUrl = null)
                    val warnings = logs.eventsAt(level = Level.WARN)
                    warnings shouldHaveSize 1
                    warnings.single().formattedMessage shouldContain "RestClientException"
                    warnings.single().throwableProxy.shouldBeNull()
                    logs.eventsAt(level = Level.ERROR).shouldBeEmpty()
                }
            }
        }

        given("a users.profile.get body Slack sends for an unknown user") {
            val dto =
                jsonMapper.readValue(
                    createUserProfileErrorJson(error = "user_not_found"),
                    SlackUserProfileDto::class.java,
                )
            val requester = requesterReturning(Result.success(ResponseEntity.ok(dto)))
            val resolver =
                SlackUserProfileResolver(
                    restRequester = requester,
                    slackApiToken = TEST_BOT_TOKEN,
                    clock = Clock.fixed(start, ZoneOffset.UTC),
                )

            `when`("resolved twice") {
                val (first, logs) =
                    captureLogs(loggerName = "dev.notypie.templates") { resolver.resolve(userId = TEST_USER_ID) }
                resolver.resolve(userId = TEST_USER_ID)

                then("the body decodes without a profile") {
                    dto shouldBe SlackUserProfileDto(ok = false, error = "user_not_found", profile = null)
                }

                then("it renders the bare mention, caches it and logs Slack's error code once at WARN, no stack") {
                    first shouldBe PublisherView(displayName = "<@$TEST_USER_ID>", thumbnailUrl = null)
                    requester.verifyLookups(times = 1)
                    val warnings = logs.eventsAt(level = Level.WARN)
                    warnings shouldHaveSize 1
                    warnings.single().formattedMessage shouldContain "error=user_not_found"
                    warnings.single().throwableProxy.shouldBeNull()
                    logs.eventsAt(level = Level.ERROR).shouldBeEmpty()
                }
            }
        }

        given("a minimal ok=true body carrying only the display name") {
            val dto =
                jsonMapper.readValue(
                    createMinimalUserProfileResponseJson(displayName = "junho"),
                    SlackUserProfileDto::class.java,
                )
            val resolver =
                SlackUserProfileResolver(
                    restRequester = requesterReturning(Result.success(ResponseEntity.ok(dto))),
                    slackApiToken = TEST_BOT_TOKEN,
                )

            then("it decodes and names the publisher without a thumbnail") {
                dto.error.shouldBeNull()
                resolver.resolve(userId = TEST_USER_ID) shouldBe
                    PublisherView(displayName = "junho", thumbnailUrl = null)
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

        given("several renders that miss the same user while its lookup is in flight") {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val lookups = AtomicInteger(0)
            val requester: RestRequester =
                mockk {
                    every {
                        safeGet(
                            uri = any(),
                            authorizationHeader = TEST_BOT_TOKEN,
                            responseType = SlackUserProfileDto::class.java,
                            uriVariables = any(),
                        )
                    } answers {
                        lookups.incrementAndGet()
                        entered.countDown()
                        release.await(5L, TimeUnit.SECONDS)
                        profileOk()
                    }
                }
            val resolver = SlackUserProfileResolver(restRequester = requester, slackApiToken = TEST_BOT_TOKEN)

            `when`("three more threads resolve the user before the first lookup returns") {
                val views = ConcurrentLinkedQueue<PublisherView>()
                val first = thread { views.add(resolver.resolve(userId = TEST_USER_ID)) }
                entered.await(5L, TimeUnit.SECONDS)
                val others = List(size = 3) { thread { views.add(resolver.resolve(userId = TEST_USER_ID)) } }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
                while (others.any { it.state !in PARKED } && System.nanoTime() < deadline) Thread.onSpinWait()
                release.countDown()
                (listOf(first) + others).forEach { it.join(5_000L) }

                then("Slack is asked once and every caller gets the same view") {
                    lookups.get() shouldBe 1
                    views.size shouldBe 4
                    views.distinct().size shouldBe 1
                }
            }
        }

        given("a lookup that throws instead of returning a failed Result") {
            val requester: RestRequester =
                mockk {
                    every {
                        safeGet(
                            uri = any(),
                            authorizationHeader = TEST_BOT_TOKEN,
                            responseType = SlackUserProfileDto::class.java,
                            uriVariables = any(),
                        )
                    } throws IllegalStateException("boom") andThen profileOk()
                }
            val resolver = SlackUserProfileResolver(restRequester = requester, slackApiToken = TEST_BOT_TOKEN)

            `when`("the user is resolved twice") {
                val thrown = shouldThrow<IllegalStateException> { resolver.resolve(userId = TEST_USER_ID) }
                val second = resolver.resolve(userId = TEST_USER_ID)

                then("the in-flight entry is released, so the next render asks Slack again") {
                    thrown.message shouldBe "boom"
                    second.displayName shouldBe "junho"
                    requester.verifyLookups(times = 2)
                }
            }
        }
    })

private val PARKED = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
