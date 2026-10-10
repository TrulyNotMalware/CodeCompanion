package dev.notypie.schema

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.repository.standup.RoutineStopCandidate
import java.util.UUID

fun createRoutineStopCandidate(
    routineUid: UUID = UUID.randomUUID(),
    name: String = "Daily Standup",
    creatorId: String = TEST_USER_ID,
): RoutineStopCandidate =
    RoutineStopCandidate(
        routineUid = routineUid,
        name = name,
        creatorId = creatorId,
    )
