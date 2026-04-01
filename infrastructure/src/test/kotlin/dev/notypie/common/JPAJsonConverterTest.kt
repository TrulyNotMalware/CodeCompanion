package dev.notypie.common

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class JPAJsonConverterTest :
    BehaviorSpec({
        val converter = JPAJsonConverter()

        given("convertToDatabaseColumn") {
            `when`("called with a non-empty map") {
                val input = mapOf("key1" to "value1", "key2" to 42)
                val result = converter.convertToDatabaseColumn(attribute = input)

                then("should return valid JSON string") {
                    result.shouldBeInstanceOf<String>()
                    result shouldBe """{"key1":"value1","key2":42}"""
                }
            }

            `when`("called with an empty map") {
                val result = converter.convertToDatabaseColumn(attribute = emptyMap())

                then("should return empty JSON object") {
                    result shouldBe "{}"
                }
            }

            `when`("called with null") {
                val result = converter.convertToDatabaseColumn(attribute = null)

                then("should return null string") {
                    result shouldBe "null"
                }
            }

            `when`("called with nested map") {
                val input = mapOf("outer" to mapOf("inner" to "value"))
                val result = converter.convertToDatabaseColumn(attribute = input)

                then("should serialize nested structure") {
                    result shouldBe """{"outer":{"inner":"value"}}"""
                }
            }
        }

        given("convertToEntityAttribute") {
            `when`("called with valid JSON string") {
                val json = """{"key1":"value1","key2":42}"""
                val result = converter.convertToEntityAttribute(content = json)

                then("should return map with correct entries") {
                    result["key1"] shouldBe "value1"
                    result["key2"] shouldBe 42
                }
            }

            `when`("called with empty JSON object") {
                val result = converter.convertToEntityAttribute(content = "{}")

                then("should return empty map") {
                    result shouldBe emptyMap()
                }
            }

            `when`("called with null") {
                val result = converter.convertToEntityAttribute(content = null)

                then("should return empty map") {
                    result shouldBe emptyMap()
                }
            }

            `when`("called with nested JSON") {
                val json = """{"outer":{"inner":"value"}}"""
                val result = converter.convertToEntityAttribute(content = json)

                then("should deserialize nested structure") {
                    result["outer"].shouldBeInstanceOf<Map<*, *>>()
                    (result["outer"] as Map<*, *>)["inner"] shouldBe "value"
                }
            }
        }

        given("roundtrip") {
            `when`("converting map to JSON and back") {
                val original = mapOf("name" to "test", "count" to 5, "active" to true)
                val json = converter.convertToDatabaseColumn(attribute = original)
                val restored = converter.convertToEntityAttribute(content = json)

                then("restored map should match original") {
                    restored["name"] shouldBe "test"
                    restored["count"] shouldBe 5
                    restored["active"] shouldBe true
                }
            }
        }
    })
