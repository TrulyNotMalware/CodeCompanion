package dev.notypie.application.common

import dev.notypie.common.jsonMapper
import dev.notypie.domain.common.IdempotencyData
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

data class TestIdempotencyData(
    val value: String,
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
        }

        given("JacksonIdempotencyDataSerializer") {
            val serializer = JacksonIdempotencyDataSerializer(mapper = jsonMapper)

            `when`("serializing the same data twice") {
                val data = TestIdempotencyData(value = "hello")
                val result1 = serializer.serialize(data = data)
                val result2 = serializer.serialize(data = data)

                then("should produce the same hash") {
                    result1 shouldBe result2
                }
            }

            `when`("serializing different data") {
                val result1 = serializer.serialize(data = TestIdempotencyData(value = "a"))
                val result2 = serializer.serialize(data = TestIdempotencyData(value = "b"))

                then("should produce different hashes") {
                    result1 shouldNotBe result2
                }
            }

            `when`("result format") {
                val result = serializer.serialize(data = TestIdempotencyData(value = "test"))

                then("should be a hex string (SHA-256 = 64 hex chars)") {
                    result.length shouldBe 64
                    result.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
                }
            }
        }
    })
