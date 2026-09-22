package dev.notypie.exception

import dev.notypie.exception.meeting.DatabaseException
import dev.notypie.exception.meeting.JpaErrorCode
import dev.notypie.exception.meeting.schemaNotFound
import dev.notypie.exception.meeting.throwIfSchemaNotFound
import dev.notypie.repository.meeting.schema.MeetingSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class DatabaseExceptionTest :
    BehaviorSpec({
        given("throwIfSchemaNotFound on a null lookup") {
            val missing: MeetingSchema? = null

            `when`("the receiver is null") {
                val exception =
                    shouldThrow<DatabaseException> {
                        missing.throwIfSchemaNotFound(fieldName = "meetingId", fieldValue = 42L)
                    }

                then("the exception names the static type as the table and carries the error code") {
                    exception.tableName shouldBe "MeetingSchema"
                    exception.errorCode shouldBe JpaErrorCode.TABLE_NOT_FOUND
                    exception.message shouldBe JpaErrorCode.TABLE_NOT_FOUND.message
                    exception.details.single().let { detail ->
                        detail.fieldName shouldBe "meetingId"
                        detail.value shouldBe "42"
                        detail.reason shouldBe "MeetingSchema with meetingId=42 not found."
                    }
                }
            }

            `when`("the receiver is present") {
                val present = "row"

                then("it is returned unchanged") {
                    present.throwIfSchemaNotFound(fieldName = "id", fieldValue = 1) shouldBe "row"
                }
            }
        }

        given("schemaNotFound builder DSL") {
            `when`("a table, field and reason are supplied") {
                val exception =
                    shouldThrow<DatabaseException> {
                        schemaNotFound {
                            table(MeetingSchema::class)
                            field("meetingUid") withValue "abc"
                            reason("no such meeting")
                        }
                    }

                then("the built exception reflects every builder call") {
                    exception.tableName shouldBe "MeetingSchema"
                    exception.details.single().reason shouldBe "no such meeting"
                    exception.details.single().value shouldBe "abc"
                }
            }
        }
    })
