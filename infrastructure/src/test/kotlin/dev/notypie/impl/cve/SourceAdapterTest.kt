package dev.notypie.impl.cve

import dev.notypie.common.jsonMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime

/**
 * Pins the shared parsing helpers both adapters depend on: `stringOrNull` must fold absent, JSON
 * null, non-scalar, and blank values to null (so `?:` fallback chains fire), and
 * `parseSourceTimestamp` must accept both source timestamp shapes and never throw.
 */
class SourceAdapterTest :
    BehaviorSpec({
        fun field(json: String): JsonNode? = jsonMapper.readTree(json)["a"]

        given("stringOrNull over the JSON value shapes") {
            `when`("the field is text") {
                then("the text is returned") {
                    field("""{"a":"hello"}""")?.stringOrNull() shouldBe "hello"
                }
            }

            `when`("the field is a number") {
                then("it coerces to its text form — GitHub release ids are numeric") {
                    field("""{"a":344656767}""")?.stringOrNull() shouldBe "344656767"
                }
            }

            `when`("the field is blank text") {
                then("blank folds to null so fallback chains fire") {
                    field("""{"a":""}""")?.stringOrNull().shouldBeNull()
                    field("""{"a":"   "}""")?.stringOrNull().shouldBeNull()
                }
            }

            `when`("the field is JSON null") {
                then("null is returned") {
                    field("""{"a":null}""")?.stringOrNull().shouldBeNull()
                }
            }

            `when`("the field is an object or an array") {
                then("non-scalars fold to null instead of coercing") {
                    field("""{"a":{"nested":1}}""")?.stringOrNull().shouldBeNull()
                    field("""{"a":[1,2]}""")?.stringOrNull().shouldBeNull()
                }
            }
        }

        given("parseSourceTimestamp over the source timestamp shapes") {
            `when`("the value carries an offset (GitHub)") {
                then("the local part of the offset time is kept") {
                    parseSourceTimestamp(value = "2026-01-02T03:04:05Z") shouldBe
                        LocalDateTime.of(2026, 1, 2, 3, 4, 5)
                }
            }

            `when`("the value is offset-free (NVD)") {
                then("it parses as a plain local date-time") {
                    parseSourceTimestamp(value = "2026-01-01T00:00:00.000") shouldBe
                        LocalDateTime.of(2026, 1, 1, 0, 0, 0)
                }
            }

            `when`("the value is malformed or absent") {
                then("null is returned instead of throwing") {
                    parseSourceTimestamp(value = "not-a-date").shouldBeNull()
                    parseSourceTimestamp(value = "2026-13-45T99:99:99Z").shouldBeNull()
                    parseSourceTimestamp(value = null).shouldBeNull()
                }
            }
        }
    })
