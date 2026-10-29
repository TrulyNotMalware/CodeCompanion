package dev.notypie.impl.command

import dev.notypie.repository.standup.StandupRepository
import io.mockk.mockk
import java.time.Clock

fun createSlackOutboundStager(
    slackEventBuilder: SlackApiEventConstructor = mockk(),
    standupRepository: StandupRepository = mockk(),
    clock: Clock = Clock.systemDefaultZone(),
): SlackOutboundStager =
    SlackOutboundStager(
        slackEventBuilder = slackEventBuilder,
        standupRepository = standupRepository,
        clock = clock,
    )
