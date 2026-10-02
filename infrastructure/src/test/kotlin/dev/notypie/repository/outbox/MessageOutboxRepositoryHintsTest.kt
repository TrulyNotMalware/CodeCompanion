package dev.notypie.repository.outbox

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.data.jpa.repository.QueryHints

class MessageOutboxRepositoryHintsTest :
    BehaviorSpec({
        given("the reads behind the outbox health indicator and the Prometheus gauges") {
            val healthReads =
                setOf(
                    "findOldestPendingCreatedAt",
                    "countPending",
                    "countPendingOlderThan",
                    "countInProgress",
                    "countInProgressOlderThan",
                    "findOldestInProgressUpdatedAt",
                    "countInProgressWithSendsAtLeast",
                )

            `when`("their query hints are read") {
                val timeouts =
                    MessageOutboxRepository::class.java.methods
                        .filter { it.name in healthReads }
                        .associate { method ->
                            method.name to
                                method
                                    .getAnnotation(QueryHints::class.java)
                                    ?.value
                                    ?.singleOrNull { it.name == HEALTH_QUERY_TIMEOUT_HINT }
                                    ?.value
                        }

                then("each one carries the query timeout, so a stuck database cannot hang a probe or a scrape") {
                    timeouts.keys shouldContainExactlyInAnyOrder healthReads
                    timeouts.values.toSet() shouldBe setOf(HEALTH_QUERY_TIMEOUT_MILLIS)
                }
            }
        }
    })
