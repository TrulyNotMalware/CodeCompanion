package dev.notypie.domain.common

import dev.notypie.domain.command.exceptions.ValidationException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ValidationBuilderTest :
    BehaviorSpec({

        given("Logical operations") {
            val test = "1234"
            `when`("assert AND operations with validateAndReturn") {
                val validationResult =
                    validateAndReturn {
                        "test" of test shouldBeLongerThan 1 and { it shouldBeShorterThan 10 }
                        "test" of test shouldBeLongerThan 1 and { it shouldBeShorterThan 3 }
                    }
                then("should return validation error") {
                    val error = validationResult.first()
                    validationResult.size shouldBe 1
                    error.fieldName shouldBe "test"
                }
            }

            `when`("assert AND operations with validate") {
                then("should throw validationExceptions") {
                    shouldThrowExactly<ValidationException> {
                        validate {
                            "test" of test shouldBeLongerThan 1 and { it shouldBeShorterThan 10 }
                            "test" of test shouldBeLongerThan 1 and { it shouldBeShorterThan 3 }
                        }
                    }
                }
            }

            `when`("assert OR operations with validateAndReturn") {
                val validationResult =
                    validateAndReturn {
                        "test" of test shouldBeShorterThan 1 or { it shouldBeLongerThan 10 }
                        "test" of test shouldBeShorterThan 5 or { it shouldBeLongerThan 10 }
                    }
                then("should return validation error") {
                    val error = validationResult.first()
                    validationResult.size shouldBe 1
                    error.fieldName shouldBe "test"
                }
            }

            `when`("assert OR operations with validate") {
                then("should throw validationExceptions") {
                    shouldThrowExactly<ValidationException> {
                        validate {
                            "test" of test shouldBeShorterThan 1 or { it shouldBeLongerThan 10 }
                            "test" of test shouldBeShorterThan 5 or { it shouldBeLongerThan 10 }
                        }
                    }
                }
            }
        }

        given("Strings with validateAndReturn") {
            val emptyString = ""
            val nullString: String? = null

            `when`("assert null or empty string") {
                val validationResult =
                    validateAndReturn(className = this.javaClass.simpleName) {
                        notBlank {
                            "emptyString" of emptyString
                        }
                        "nullString" of nullString shouldNotBeNullAnd {} and { it.shouldNotBeNullOrBlank() }
                    }
                then("should return validation error") {
                    validationResult.size shouldBe 3
                    validationResult.first().fieldName shouldBe "emptyString"
                    validationResult.last().fieldName shouldBe "nullString"
                }
            }

            val nullableStringButContainsData: String? = "test"
            `when`("assert nullable string but contains data") {
                val validationResult =
                    validateAndReturn(className = this.javaClass.simpleName) {
                        "nullableStringButContainsData" of nullableStringButContainsData shouldNotBeNullAnd {
                            notBlank {
                                "nullableStringButContainsData" of nullableStringButContainsData
                            }
                        }
                        "nullableStringButContainsData" of nullableStringButContainsData ifNotNull {
                            it shouldBeShorterThan 5
                        }
                    }
                then("should not return validation error") {
                    validationResult.size shouldBe 0
                }
            }

            val string = "teststr"
            `when`("assert string lengths") {
                val validationResult =
                    validateAndReturn(className = this.javaClass.simpleName) {
                        "string" of string shouldBeLongerThan 1 and { it shouldBeShorterThan 10 }
                        "string" of string shouldBeLongerThan 10 or { it shouldBeShorterThan 1 }
                    }
                then("should return validation error") {
                    val error = validationResult.first()
                    validationResult.size shouldBe 1
                    error.fieldName shouldBe "string"
                    error.value shouldBe string
                }
            }

            val pattern = Regex("^[a-zA-Z0-9_]+$")
            val patternString = "^[a-zA-Z0-9_]+$"

            val unmatchString = "####a:"
            val matchString = "testAB1"
            val emailString = "test123@example.ai"
            val invalidEmailString = "test123"
            val invalidEmailString2 = "test123@@example.ai"
            val invalidEmailString3 = "test123@example,com"
            `when`("assert regex pattern") {
                val emailValidationResult =
                    validateAndReturn {
                        ("emailString" of emailString).shouldBeEmail()
                        ("invalidEmailString" of invalidEmailString).shouldBeEmail()
                        ("invalidEmailString2" of invalidEmailString2).shouldBeEmail()
                        ("invalidEmailString3" of invalidEmailString3).shouldBeEmail()
                    }
                val validationResult =
                    validateAndReturn {
                        "unmatchString" of unmatchString shouldMatchPattern pattern
                        "matchString" of matchString shouldMatchPattern pattern

                        "unmatchString" of unmatchString shouldMatchPattern patternString
                        "matchString" of matchString shouldMatchPattern patternString
                    }
                then("should return validation error") {
                    emailValidationResult.size shouldBe 3

                    val error = validationResult.first()
                    validationResult.size shouldBe 2
                    error.fieldName shouldBe "unmatchString"
                    error.value shouldBe unmatchString
                }
            }

            val searchTarget = "string2"
            val unKnownTarget = "unknown"
            val stringCollection = listOf("string1", "string2", "string3")
            `when`("assert collection contains") {
                val validationResult =
                    validateAndReturn {
                        "searchTarget" of searchTarget shouldBeOneOf stringCollection
                        "unKnownTarget" of unKnownTarget shouldBeOneOf stringCollection
                    }
                then("should return validation error") {
                    assertSoftly {
                        validationResult.size shouldBe 1
                        val error = validationResult.first()
                        error.fieldName shouldBe "unKnownTarget"
                        error.value shouldBe "unknown"
                    }
                }
            }

            val searchTargetInt = 2
            val unKnownTargetInt = 99
            val intCollection = listOf(1, 2, 3)
            `when`("assert int collection contains") {
                val validationResult =
                    validateAndReturn {
                        "searchTargetInt" of searchTargetInt shouldBeOneOf intCollection
                        "unKnownTargetInt" of unKnownTargetInt shouldBeOneOf intCollection
                    }
                then("should return validation error") {
                    assertSoftly {
                        validationResult.size shouldBe 1
                        val error = validationResult.first()
                        error.fieldName shouldBe "unKnownTargetInt"
                        error.value shouldBe "99"
                    }
                }
            }
        }

        given("Integers with validateAndReturn") {
            val zero = 0
            val bigInt = Int.MAX_VALUE
            val smallInt = Int.MIN_VALUE

            val positiveInt = 1
            val negativeInt = -1
            `when`("assert integer comparison") {
                val validationResult =
                    validateAndReturn {
                        ("zero" of zero).shouldBeNonNegative() and { it shouldBeLessThan bigInt } and
                            { it shouldBeLessThanOrEqualTo 0 }
                        ("bigInt" of bigInt).shouldBePositive() and { it shouldBeGreaterThan smallInt }
                        ("smallInt" of smallInt).shouldBeNegative()
                        ("positiveInt" of positiveInt).shouldBePositive()
                        ("negativeInt" of negativeInt).shouldBeNegative() and
                            { it shouldBeGreaterThanOrEqualTo smallInt }

                        ("zero" of zero) shouldBeBetween (smallInt..bigInt)
                    }
                then("should not return validation error") {
                    validationResult.size shouldBe 0
                }
            }
        }

        given("LocalDateTime with validateAndReturn") {
            val now = LocalDateTime.now()
            val future = now.plusHours(1)
            val past = now.minusHours(1)
            `when`("assert time comparison") {
                val validationResult =
                    validateAndReturn {
                        "now" of now shouldBeAfter past and { it shouldBeBefore future }
                        ("future" of future).shouldBeInFuture()
                        ("past" of past).shouldBeInPast()
                    }
                then("should not return validation error") {
                    validationResult.size shouldBe 0
                }
            }
        }

        given("Collections with validateAndReturn") {
            val collection = listOf(1, 2, 3)
            `when`("assert collection size comparison") {
                val validateResult =
                    validateAndReturn {
                        "collection" of collection shouldHaveSize 3 and { it shouldHaveMinSize 1 } and
                            { it.shouldNotBeEmpty() }
                        "collection" of collection shouldHaveMaxSize 1
                    }
                then("should return validation error") {
                    validateResult.size shouldBe 1
                }
            }
        }

        given("Custom validations with validateAndReturn") {

            `when`("asserting various types with shouldSatisfy") {
                val number = 10
                val text = "TestValue"
                val customObj =
                    object {
                        val id = "CC-123"

                        override fun toString() = id
                    }

                val validationResult =
                    validateAndReturn {
                        "number" of number shouldSatisfy { it % 2 == 0 }
                        "text" of text shouldSatisfy { it.startsWith("Test") }

                        "failNumber" of number shouldSatisfy { it % 2 != 0 }

                        "failText" of text shouldSatisfy { it.length < 5 }

                        "customObj" of customObj shouldSatisfy { it.id.startsWith("SO") }
                    }

                then("should return accurate errors for all unsatisfied conditions") {
                    assertSoftly {
                        validationResult.size shouldBe 3

                        validationResult[0].fieldName shouldBe "failNumber"
                        validationResult[0].value shouldBe "10"
                        validationResult[0].reason shouldBe "does not satisfy the required condition"

                        validationResult[1].fieldName shouldBe "failText"
                        validationResult[1].value shouldBe "TestValue"

                        validationResult[2].fieldName shouldBe "customObj"
                        validationResult[2].value shouldBe "CC-123"
                    }
                }
            }

            `when`("using shouldSatisfy with a custom message") {
                val score = 40
                val customMessage = "Score must be at least 50 points"

                val member =
                    object {
                        val role = "GUEST"

                        override fun toString() = role
                    }
                val roleErrorMessage = "Only ADMIN can access this feature"

                val validationResult =
                    validateAndReturn {
                        ("score" of score).shouldSatisfy(customMessage) { it >= 50 }

                        ("member" of member).shouldSatisfy(roleErrorMessage) { it.role == "ADMIN" }
                    }

                then("the custom message and object value should be returned correctly") {
                    assertSoftly {
                        validationResult.size shouldBe 2

                        validationResult[0].fieldName shouldBe "score"
                        validationResult[0].reason shouldBe customMessage
                        validationResult[0].value shouldBe "40"

                        validationResult[1].fieldName shouldBe "member"
                        validationResult[1].reason shouldBe roleErrorMessage
                        validationResult[1].value shouldBe "GUEST"
                    }
                }
            }
        }
    })
