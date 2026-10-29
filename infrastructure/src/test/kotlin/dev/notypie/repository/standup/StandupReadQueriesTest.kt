package dev.notypie.repository.standup

import dev.notypie.domain.standup.createRoutine
import dev.notypie.domain.standup.createRoutineMember
import dev.notypie.domain.standup.createSessionDispatch
import dev.notypie.domain.standup.createStandupAnswer
import dev.notypie.domain.standup.createStandupSession
import dev.notypie.domain.standup.entity.enums.SessionStatus
import dev.notypie.exception.meeting.DatabaseException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val READ_QUERIES_H2_URL = "jdbc:h2:mem:standup_reads;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"

@DataJpaTest(properties = ["spring.datasource.url=$READ_QUERIES_H2_URL"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ApplyExtension(extensions = [SpringExtension::class])
class StandupReadQueriesTest
    @Autowired
    constructor(
        private val jpaRoutineRepository: JpaRoutineRepository,
        private val jpaStandupSessionRepository: JpaStandupSessionRepository,
        private val jpaSessionDispatchRepository: JpaSessionDispatchRepository,
        transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val repository =
                StandupRepositoryImpl(
                    jpaRoutineRepository = jpaRoutineRepository,
                    jpaStandupSessionRepository = jpaStandupSessionRepository,
                    jpaSessionDispatchRepository = jpaSessionDispatchRepository,
                )
            val transactionTemplate = TransactionTemplate(transactionManager)
            val boundary = Instant.parse("2031-03-03T03:00:00Z")

            fun <T : Any> inTx(action: () -> T): T = transactionTemplate.execute { action() }

            fun routineWith(commandChannel: String, userIds: List<String>) =
                inTx {
                    repository.createRoutine(
                        routine =
                            createRoutine(
                                name = "Routine ${UUID.randomUUID()}".take(60),
                                commandChannel = commandChannel,
                                members = userIds.map { createRoutineMember(userId = it) },
                            ),
                    )
                }

            fun sessionAt(
                cutoffAt: Instant,
                status: SessionStatus = SessionStatus.COLLECTING,
                routineUid: UUID = UUID.randomUUID(),
                sessionDate: LocalDate = LocalDate.of(2031, 3, 3),
            ): UUID =
                inTx {
                    repository
                        .createSession(
                            session =
                                createStandupSession(
                                    routineUid = routineUid,
                                    sessionDate = sessionDate,
                                    cutoffAt = cutoffAt,
                                    status = status,
                                    summaryMessageTs = if (status == SessionStatus.SUMMARIZED) "outbox:1" else null,
                                    dispatches =
                                        listOf(
                                            createSessionDispatch(userId = "U_A"),
                                            createSessionDispatch(userId = "U_B"),
                                        ),
                                    answers = listOf(createStandupAnswer(userId = "U_A")),
                                ),
                        ).sessionUid
                }

            given("the routine reads") {
                val channel = "C_READ_${UUID.randomUUID()}"
                val active = routineWith(commandChannel = channel, userIds = listOf("U_1", "U_2"))
                val stopped = routineWith(commandChannel = channel, userIds = listOf("U_3"))
                val elsewhere = routineWith(commandChannel = "${channel}_OTHER", userIds = listOf("U_4"))
                inTx { repository.deactivateRoutine(routineUid = stopped.routineUid) }

                `when`("getRoutine is called outside a transaction for a routine with two members") {
                    val routine = repository.getRoutine(routineUid = active.routineUid)

                    then("the members are fetched with it and belong to that routine only") {
                        routine.routineUid shouldBe active.routineUid
                        routine.members.map { it.userId } shouldContainExactlyInAnyOrder listOf("U_1", "U_2")
                    }
                }

                `when`("getRoutine is called with an unknown uid") {
                    then("it throws instead of returning an empty routine") {
                        shouldThrow<DatabaseException> { repository.getRoutine(routineUid = UUID.randomUUID()) }
                    }
                }

                `when`("the active routines of a channel are read outside a transaction") {
                    val routines = repository.findActiveRoutinesByChannel(commandChannel = channel)

                    then("only the active routine of that channel comes back once, with its members") {
                        routines.map { it.routineUid } shouldContainExactly listOf(active.routineUid)
                        routines.single().members.map { it.userId } shouldContainExactlyInAnyOrder
                            listOf("U_1", "U_2")
                    }
                }

                `when`("every active routine is listed outside a transaction") {
                    val routines = repository.listActiveRoutines()

                    then("active routines of every channel appear once and the stopped one does not") {
                        val uids = routines.map { it.routineUid }
                        uids.count { it == active.routineUid } shouldBe 1
                        uids.count { it == elsewhere.routineUid } shouldBe 1
                        uids shouldNotContain stopped.routineUid
                        routines.single { it.routineUid == elsewhere.routineUid }.members.map { it.userId } shouldBe
                            listOf("U_4")
                    }
                }
            }

            given("findSession by routine and date") {
                val routineUid = UUID.randomUUID()
                val target = sessionAt(cutoffAt = boundary, routineUid = routineUid)
                sessionAt(cutoffAt = boundary, routineUid = routineUid, sessionDate = LocalDate.of(2031, 3, 4))
                sessionAt(cutoffAt = boundary)

                `when`("the session of one date is read outside a transaction") {
                    val found = repository.findSession(routineUid = routineUid, sessionDate = LocalDate.of(2031, 3, 3))
                    val missing =
                        repository.findSession(routineUid = routineUid, sessionDate = LocalDate.of(2031, 3, 5))

                    then("it is that routine's session of that date with both collections, and a free date is null") {
                        found.shouldNotBeNull().sessionUid shouldBe target
                        found.dispatches.map { it.userId } shouldContainExactlyInAnyOrder listOf("U_A", "U_B")
                        found.answers.map { it.userId } shouldContainExactly listOf("U_A")
                        missing.shouldBeNull()
                    }
                }
            }

            given("the sessions past their cutoff") {
                val before = boundary.plus(30L, ChronoUnit.DAYS)
                val microBefore = sessionAt(cutoffAt = before.minus(1L, ChronoUnit.MICROS))
                val exact = sessionAt(cutoffAt = before)
                val microAfter = sessionAt(cutoffAt = before.plus(1L, ChronoUnit.MICROS))
                val summarized = sessionAt(cutoffAt = before.minusSeconds(3_600L), status = SessionStatus.SUMMARIZED)

                `when`("they are read outside a transaction with the cutoff exactly at the bound") {
                    val found = repository.findCollectingSessionsPastCutoff(before = before)
                    val mine = found.filter { it.sessionUid in setOf(microBefore, exact, microAfter, summarized) }

                    then(
                        "a cutoff at or before the bound is due, one a microsecond later is not, a closed one is not",
                    ) {
                        mine.map { it.sessionUid } shouldContainExactlyInAnyOrder listOf(microBefore, exact)
                        mine.forEach { session ->
                            session.dispatches.map { it.userId } shouldContainExactlyInAnyOrder listOf("U_A", "U_B")
                            session.answers.map { it.userId } shouldContainExactly listOf("U_A")
                        }
                    }
                }
            }

            given("the sessions to nudge") {
                val now = boundary.plus(60L, ChronoUnit.DAYS)
                val windowEnd = now.plusSeconds(900L)
                val atNow = sessionAt(cutoffAt = now)
                val justAfterNow = sessionAt(cutoffAt = now.plus(1L, ChronoUnit.MICROS))
                val atWindowEnd = sessionAt(cutoffAt = windowEnd)
                val justAfterWindowEnd = sessionAt(cutoffAt = windowEnd.plus(1L, ChronoUnit.MICROS))
                val alreadyNudged = sessionAt(cutoffAt = now.plusSeconds(300L))
                val summarized = sessionAt(cutoffAt = now.plusSeconds(300L), status = SessionStatus.SUMMARIZED)
                val nudgedId = repository.findSession(sessionUid = alreadyNudged).shouldNotBeNull().sessionId
                inTx { repository.claimNudge(sessionId = nudgedId) } shouldBe true
                val all = setOf(atNow, justAfterNow, atWindowEnd, justAfterWindowEnd, alreadyNudged, summarized)

                `when`("they are read outside a transaction for the window after now") {
                    val found = repository.findCollectingSessionsForNudge(now = now, nudgeWindowEnd = windowEnd)
                    val mine = found.filter { it.sessionUid in all }

                    then("the window is open at now and closed at its end, and nudged or closed sessions stay out") {
                        mine.map { it.sessionUid } shouldContainExactlyInAnyOrder listOf(justAfterNow, atWindowEnd)
                        mine.forEach { it.answeredUserIds shouldBe setOf("U_A") }
                    }
                }
            }
        })
