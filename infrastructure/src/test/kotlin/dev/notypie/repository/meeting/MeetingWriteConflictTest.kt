package dev.notypie.repository.meeting

import dev.notypie.repository.createSnapshotIsolationFailure
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DataRetrievalFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.sql.SQLIntegrityConstraintViolationException

class MeetingWriteConflictTest :
    BehaviorSpec({
        given("isMeetingWriteConflict") {
            `when`("the failure is a concurrency failure") {
                then("optimistic, pessimistic and lock-acquisition failures are all retryable") {
                    ObjectOptimisticLockingFailureException("meetings", 1L).isMeetingWriteConflict() shouldBe true
                    PessimisticLockingFailureException("deadlock").isMeetingWriteConflict() shouldBe true
                    CannotAcquireLockException("lock wait timeout").isMeetingWriteConflict() shouldBe true
                }
            }

            `when`(
                "the failure is a MariaDB snapshot-isolation conflict (1020) as the repository proxy hands it over",
            ) {
                then("it is retryable") {
                    createSnapshotIsolationFailure(table = "meetings").isMeetingWriteConflict() shouldBe true
                }
            }

            `when`("the failure is a data integrity violation") {
                then("only the participant unique key, found anywhere in the cause chain, is retryable") {
                    DataIntegrityViolationException(
                        "could not execute statement",
                        SQLIntegrityConstraintViolationException(
                            "Duplicate entry '7-U_A' for key 'uk_meeting_participants_meeting_user'",
                        ),
                    ).isMeetingWriteConflict() shouldBe true
                    DataIntegrityViolationException(
                        "Unique index or primary key violation: \"PUBLIC.UK_MEETING_PARTICIPANTS_MEETING_USER_INDEX_4\"",
                    ).isMeetingWriteConflict() shouldBe true
                    DataIntegrityViolationException(
                        "could not execute statement",
                        SQLIntegrityConstraintViolationException("Column 'user_id' cannot be null"),
                    ).isMeetingWriteConflict() shouldBe false
                }
            }

            `when`("the failure is anything else") {
                then("it is not retried") {
                    val unrelated = DataRetrievalFailureException("uk_meeting_participants_meeting_user")
                    unrelated.isMeetingWriteConflict() shouldBe false
                    IllegalStateException("boom").isMeetingWriteConflict() shouldBe false
                }
            }
        }
    })
