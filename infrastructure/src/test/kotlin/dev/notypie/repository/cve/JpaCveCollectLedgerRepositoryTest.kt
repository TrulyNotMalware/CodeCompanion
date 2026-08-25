package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveCollectLedgerSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import java.time.LocalDateTime

/**
 * Runs the ledger's JPQL reads against a real database (`claimWindow` itself is a MariaDB-native
 * INSERT IGNORE, delegation-tested elsewhere). No other spec commits ledger rows on the shared H2,
 * and afterSpec clears this spec's own writes, so the empty-table branch is assertable first.
 */
@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class JpaCveCollectLedgerRepositoryTest
    @Autowired
    constructor(
        private val repository: JpaCveCollectLedgerRepository,
    ) : BehaviorSpec({
            afterSpec { repository.deleteAll() }

            val base = LocalDateTime.of(2026, 7, 14, 12, 0)

            given("an empty ledger") {
                `when`("findLatestWindowStart runs") {
                    then("nothing was ever collected, so it is null") {
                        repository.findLatestWindowStart().shouldBeNull()
                    }
                }
            }

            given("claimed windows across two topics") {
                repository.saveAndFlush(CveCollectLedgerSchema(topicId = 1L, windowStart = base.minusMinutes(10)))
                repository.saveAndFlush(CveCollectLedgerSchema(topicId = 2L, windowStart = base))
                repository.saveAndFlush(CveCollectLedgerSchema(topicId = 1L, windowStart = base.minusMinutes(5)))

                `when`("findLatestWindowStart runs") {
                    then("the newest window across all topics comes back") {
                        repository.findLatestWindowStart() shouldBe base
                    }
                }

                `when`("deleteOlderThan prunes at a cutoff between the rows") {
                    val deleted = repository.deleteOlderThan(cutoff = base.minusMinutes(7))

                    then("only the older window is removed and the newest still answers") {
                        deleted shouldBe 1
                        repository.count() shouldBe 2
                        repository.findLatestWindowStart() shouldBe base
                    }
                }
            }
        })
