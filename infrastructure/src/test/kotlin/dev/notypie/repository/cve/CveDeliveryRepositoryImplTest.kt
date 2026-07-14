package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.schema.createUndeliveredCveEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime

/**
 * Delegation-only: `claim` is a native `INSERT IGNORE` (MariaDB-specific), so its affected-row
 * semantics cannot be exercised on H2 — this asserts the impl maps the affected-row count to the
 * boolean claim signal and forwards its arguments unchanged. `findUndelivered` (whose JPQL is covered
 * end-to-end in `JpaCveDeliveryRepositoryTest`) is asserted the same way, including the limit ->
 * Pageable conversion and the forwarded scan bounds.
 */
class CveDeliveryRepositoryImplTest :
    BehaviorSpec({
        given("a delivery this instance wins") {
            val jpa = mockk<JpaCveDeliveryRepository>()
            val repository = CveDeliveryRepositoryImpl(jpaCveDeliveryRepository = jpa)
            every { jpa.claim(eventId = 1L, userId = "U1") } returns 1

            `when`("claiming") {
                val claimed = repository.claim(eventId = 1L, userId = "U1")

                then("an affected-row count of 1 maps to true") {
                    claimed shouldBe true
                    verify(exactly = 1) { jpa.claim(eventId = 1L, userId = "U1") }
                }
            }
        }

        given("a delivery another instance already sent") {
            val jpa = mockk<JpaCveDeliveryRepository>()
            val repository = CveDeliveryRepositoryImpl(jpaCveDeliveryRepository = jpa)
            every { jpa.claim(eventId = 2L, userId = "U2") } returns 0

            `when`("claiming") {
                val claimed = repository.claim(eventId = 2L, userId = "U2")

                then("an affected-row count of 0 maps to false") {
                    claimed shouldBe false
                }
            }
        }

        given("undelivered pairs for a mode") {
            val jpa = mockk<JpaCveDeliveryRepository>()
            val repository = CveDeliveryRepositoryImpl(jpaCveDeliveryRepository = jpa)
            val since = LocalDateTime.of(2026, 7, 7, 9, 0)
            val doneBefore = LocalDateTime.of(2026, 7, 14, 9, 0)
            every {
                jpa.findUndelivered(
                    deliveryMode = CveDeliveryMode.IMMEDIATE,
                    since = since,
                    doneBefore = doneBefore,
                    pageable = PageRequest.of(0, 5),
                )
            } returns listOf(createUndeliveredCveEvent(eventId = 7L, userId = "U7"))

            `when`("querying with a limit") {
                val pairs =
                    repository.findUndelivered(
                        deliveryMode = CveDeliveryMode.IMMEDIATE,
                        since = since,
                        doneBefore = doneBefore,
                        limit = 5,
                    )

                then("the limit is wrapped into a page request and the bounds are forwarded unchanged") {
                    pairs.map { it.eventId } shouldContainExactly listOf(7L)
                    verify(exactly = 1) {
                        jpa.findUndelivered(
                            deliveryMode = CveDeliveryMode.IMMEDIATE,
                            since = since,
                            doneBefore = doneBefore,
                            pageable = PageRequest.of(0, 5),
                        )
                    }
                }
            }
        }

        given("the database clock") {
            val jpa = mockk<JpaCveDeliveryRepository>()
            val repository = CveDeliveryRepositoryImpl(jpaCveDeliveryRepository = jpa)
            val dbNow = LocalDateTime.of(2026, 7, 14, 7, 30)
            every { jpa.dbNow() } returns dbNow

            `when`("reading dbNow") {
                val result = repository.dbNow()

                then("it delegates unchanged") {
                    result shouldBe dbNow
                    verify(exactly = 1) { jpa.dbNow() }
                }
            }
        }
    })
