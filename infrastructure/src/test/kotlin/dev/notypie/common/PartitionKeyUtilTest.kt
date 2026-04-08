package dev.notypie.common

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe

class PartitionKeyUtilTest :
    BehaviorSpec({

        given("createPartitionKey with Int") {
            `when`("called with positive number") {
                val result = PartitionKeyUtil.createPartitionKey(number = 10)

                then("should return value in range [0, 6)") {
                    result shouldBeGreaterThanOrEqual 0
                    result shouldBeLessThan 6
                }

                then("should return 10 % 6 = 4") {
                    result shouldBe 4
                }
            }

            `when`("called with negative number") {
                val result = PartitionKeyUtil.createPartitionKey(number = -10)

                then("should return non-negative value") {
                    result shouldBeGreaterThanOrEqual 0
                    result shouldBe 4
                }
            }

            `when`("called with zero") {
                val result = PartitionKeyUtil.createPartitionKey(number = 0)

                then("should return 0") {
                    result shouldBe 0
                }
            }

            `when`("called with same number twice") {
                val result1 = PartitionKeyUtil.createPartitionKey(number = 42)
                val result2 = PartitionKeyUtil.createPartitionKey(number = 42)

                then("should return the same partition key") {
                    result1 shouldBe result2
                }
            }
        }

        given("createPartitionKey with String") {
            `when`("called with a string") {
                val result = PartitionKeyUtil.createPartitionKey(string = "test")

                then("should return value in range [0, 6)") {
                    result shouldBeGreaterThanOrEqual 0
                    result shouldBeLessThan 6
                }
            }

            `when`("called with same string twice") {
                val result1 = PartitionKeyUtil.createPartitionKey(string = "hello")
                val result2 = PartitionKeyUtil.createPartitionKey(string = "hello")

                then("should return the same partition key") {
                    result1 shouldBe result2
                }
            }

            `when`("called with different strings") {
                val results =
                    (1..100)
                        .map { i ->
                            PartitionKeyUtil.createPartitionKey(string = "key_$i")
                        }.toSet()

                then("should produce values only in range [0, 6)") {
                    results.all { it in 0..5 } shouldBe true
                }
            }

            `when`("called with empty string") {
                val result = PartitionKeyUtil.createPartitionKey(string = "")

                then("should return valid partition key") {
                    result shouldBeGreaterThanOrEqual 0
                    result shouldBeLessThan 6
                }
            }
        }
    })
