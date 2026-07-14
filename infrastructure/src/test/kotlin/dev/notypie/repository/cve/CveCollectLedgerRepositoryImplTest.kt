package dev.notypie.repository.cve

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime

/**
 * Delegation-only: `claimWindow` is a native `INSERT IGNORE` (MariaDB-specific), so its
 * affected-row semantics cannot be exercised on H2 — this asserts the impl maps the affected-row
 * count to the boolean claim signal and forwards its arguments unchanged. `deleteOlderThan` is
 * asserted the same way for symmetry.
 */
class CveCollectLedgerRepositoryImplTest :
    BehaviorSpec({
        val windowStart = LocalDateTime.of(2026, 7, 13, 12, 0)

        given("a window this instance wins") {
            val jpa = mockk<JpaCveCollectLedgerRepository>()
            val repository = CveCollectLedgerRepositoryImpl(jpaCveCollectLedgerRepository = jpa)
            every { jpa.claimWindow(topicId = 1L, windowStart = windowStart) } returns 1

            `when`("claiming") {
                val claimed = repository.claimWindow(topicId = 1L, windowStart = windowStart)

                then("an affected-row count of 1 maps to true") {
                    claimed shouldBe true
                    verify(exactly = 1) { jpa.claimWindow(topicId = 1L, windowStart = windowStart) }
                }
            }
        }

        given("a window another instance already claimed") {
            val jpa = mockk<JpaCveCollectLedgerRepository>()
            val repository = CveCollectLedgerRepositoryImpl(jpaCveCollectLedgerRepository = jpa)
            every { jpa.claimWindow(topicId = 2L, windowStart = windowStart) } returns 0

            `when`("claiming") {
                val claimed = repository.claimWindow(topicId = 2L, windowStart = windowStart)

                then("an affected-row count of 0 maps to false") {
                    claimed shouldBe false
                }
            }
        }

        given("ledger rows past the retention horizon") {
            val jpa = mockk<JpaCveCollectLedgerRepository>()
            val repository = CveCollectLedgerRepositoryImpl(jpaCveCollectLedgerRepository = jpa)
            val cutoff = LocalDateTime.of(2026, 7, 6, 12, 0)
            every { jpa.deleteOlderThan(cutoff = cutoff) } returns 3

            `when`("pruning") {
                val deleted = repository.deleteOlderThan(cutoff = cutoff)

                then("the cutoff is forwarded unchanged and the deleted count returned") {
                    deleted shouldBe 3
                    verify(exactly = 1) { jpa.deleteOlderThan(cutoff = cutoff) }
                }
            }
        }
    })
