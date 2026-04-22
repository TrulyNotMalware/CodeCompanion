package dev.notypie.application.common

import dev.notypie.domain.command.createAppMentionSlackCommandData
import dev.notypie.domain.command.createInteractionSlackCommandData
import dev.notypie.domain.common.IdempotencyData
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

data class TestIdempotencyData(
    val value: String,
) : IdempotencyData

data class NonSerializableInner(
    val text: String,
)

data class NestedNonSerializableIdempotencyData(
    val wrapper: NonSerializableInner,
) : IdempotencyData

class IdempotencyCreatorTest :
    BehaviorSpec({

        given("IdempotencyCreator.create with String") {
            `when`("called with same data and same time window") {
                val timeMillis = 1000L
                val result1 = IdempotencyCreator.create(data = "test", currentTimeMillis = timeMillis)
                val result2 = IdempotencyCreator.create(data = "test", currentTimeMillis = timeMillis)

                then("should return the same UUID") {
                    result1 shouldBe result2
                }
            }

            `when`("called with same data but different time window") {
                val result1 = IdempotencyCreator.create(data = "test", currentTimeMillis = 1000L)
                val result2 = IdempotencyCreator.create(data = "test", currentTimeMillis = 2000L)

                then("should return different UUIDs") {
                    result1 shouldNotBe result2
                }
            }

            `when`("called with different data but same time window") {
                val timeMillis = 1000L
                val result1 = IdempotencyCreator.create(data = "data1", currentTimeMillis = timeMillis)
                val result2 = IdempotencyCreator.create(data = "data2", currentTimeMillis = timeMillis)

                then("should return different UUIDs") {
                    result1 shouldNotBe result2
                }
            }

            `when`("called within the same 1-second window") {
                val result1 = IdempotencyCreator.create(data = "test", currentTimeMillis = 1001L)
                val result2 = IdempotencyCreator.create(data = "test", currentTimeMillis = 1999L)

                then("should return the same UUID because seed is timeMillis / 1000") {
                    result1 shouldBe result2
                }
            }

            `when`("called at the boundary of time windows") {
                val result1 = IdempotencyCreator.create(data = "test", currentTimeMillis = 999L)
                val result2 = IdempotencyCreator.create(data = "test", currentTimeMillis = 1000L)

                then("should return different UUIDs across window boundary") {
                    result1 shouldNotBe result2
                }
            }
        }

        given("IdempotencyCreator.create with IdempotencyData") {
            `when`("called with same data and same time window") {
                val data = TestIdempotencyData(value = "test")
                val timeMillis = 5000L
                val result1 = IdempotencyCreator.create(data = data, currentTimeMillis = timeMillis)
                val result2 = IdempotencyCreator.create(data = data, currentTimeMillis = timeMillis)

                then("should return the same UUID") {
                    result1 shouldBe result2
                }
            }

            `when`("called with different data") {
                val timeMillis = 5000L
                val result1 =
                    IdempotencyCreator.create(
                        data = TestIdempotencyData(value = "a"),
                        currentTimeMillis = timeMillis,
                    )
                val result2 =
                    IdempotencyCreator.create(
                        data = TestIdempotencyData(value = "b"),
                        currentTimeMillis = timeMillis,
                    )

                then("should return different UUIDs") {
                    result1 shouldNotBe result2
                }
            }

            `when`("IdempotencyData contains a field whose type does NOT implement java.io.Serializable") {
                val data =
                    NestedNonSerializableIdempotencyData(
                        wrapper = NonSerializableInner(text = "nested-value"),
                    )
                val timeMillis = 5000L

                then("Jackson-based serializer should succeed without NotSerializableException") {
                    val result1 = IdempotencyCreator.create(data = data, currentTimeMillis = timeMillis)
                    val result2 = IdempotencyCreator.create(data = data, currentTimeMillis = timeMillis)
                    result1 shouldBe result2
                }
            }

            `when`("called twice with the same real SlackCommandData (app_mention) in the same time window") {
                val data = createAppMentionSlackCommandData()
                val timeMillis = 5000L

                then("should return the same UUID — idempotency preserved (regression for seeds jitter)") {
                    val key1 = IdempotencyCreator.create(data = data, currentTimeMillis = timeMillis)
                    val key2 = IdempotencyCreator.create(data = data, currentTimeMillis = timeMillis)
                    key1 shouldBe key2
                }
            }

            `when`("called with real SlackCommandData wrapping an InteractionPayload body") {
                val data = createInteractionSlackCommandData()
                val timeMillis = 5000L

                then("Jackson should serialize the nested Slack DTOs without NotSerializableException") {
                    val key = IdempotencyCreator.create(data = data, currentTimeMillis = timeMillis)
                    key.shouldNotBeNull()
                }
            }
        }

        given("DefaultIdempotencyDataSerializer") {
            `when`("serializing the same data twice") {
                val data = TestIdempotencyData(value = "hello")
                val result1 = DefaultIdempotencyDataSerializer.serialize(data = data)
                val result2 = DefaultIdempotencyDataSerializer.serialize(data = data)

                then("should produce the same hash") {
                    result1 shouldBe result2
                }
            }

            `when`("serializing different data") {
                val result1 = DefaultIdempotencyDataSerializer.serialize(data = TestIdempotencyData(value = "hello"))
                val result2 = DefaultIdempotencyDataSerializer.serialize(data = TestIdempotencyData(value = "world"))

                then("should produce different hashes") {
                    result1 shouldNotBe result2
                }
            }

            `when`("result format") {
                val result = DefaultIdempotencyDataSerializer.serialize(data = TestIdempotencyData(value = "test"))

                then("should be a hex string (SHA-256 = 64 hex chars)") {
                    result.length shouldBe 64
                    result.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
                }
            }

            `when`("serializing an IdempotencyData with a non-Serializable nested field") {
                val data =
                    NestedNonSerializableIdempotencyData(
                        wrapper = NonSerializableInner(text = "value"),
                    )

                then("should serialize without throwing NotSerializableException") {
                    val result = DefaultIdempotencyDataSerializer.serialize(data = data)
                    result.length shouldBe 64
                }
            }
        }
    })
