package dev.notypie.domain.command

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_MESSAGE_TS
import dev.notypie.domain.TEST_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AgentConversePayload
import dev.notypie.domain.command.entity.event.AgentConverseRequestEvent
import dev.notypie.domain.command.entity.event.CreateStandupRoutineEvent
import dev.notypie.domain.command.entity.event.CreateStandupRoutinePayload
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

fun createCommandBasicInfo(
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
    idempotencyKey: UUID = UUID.randomUUID(),
) = CommandBasicInfo(
    appId = appId,
    appToken = appToken,
    publisherId = publisherId,
    channel = channel,
    idempotencyKey = idempotencyKey,
)

fun createCreateStandupRoutineEvent(
    idempotencyKey: UUID = UUID.randomUUID(),
    name: String = "Daily Standup",
    creatorId: String = TEST_USER_ID,
    commandChannel: String = TEST_CHANNEL_ID,
    summaryChannel: String = TEST_CHANNEL_ID,
    questions: List<String> = listOf("What did you do?", "What are you doing?"),
    memberIds: List<String> = listOf("U_ALICE", "U_BOB"),
    weekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
    triggerLocalTime: LocalTime = LocalTime.of(10, 0),
    cutoffMinutes: Long = 120L,
    timezone: ZoneId = ZoneId.of("Asia/Seoul"),
    responseBasicInfo: dev.notypie.domain.command.dto.CommandBasicInfo =
        createCommandBasicInfo(idempotencyKey = idempotencyKey, channel = commandChannel),
) = CreateStandupRoutineEvent(
    idempotencyKey = idempotencyKey,
    payload =
        CreateStandupRoutinePayload(
            name = name,
            creatorId = creatorId,
            commandChannel = commandChannel,
            summaryChannel = summaryChannel,
            questions = questions,
            memberIds = memberIds,
            weekdays = weekdays,
            triggerLocalTime = triggerLocalTime,
            cutoffMinutes = cutoffMinutes,
            timezone = timezone,
            responseBasicInfo = responseBasicInfo,
        ),
    type = CommandDetailType.STANDUP_SETUP_SUBMIT,
)

fun createAgentConverseRequestEvent(
    idempotencyKey: UUID = UUID.randomUUID(),
    prompt: String = "What meetings do I have today?",
    threadId: String? = TEST_MESSAGE_TS,
    responseBasicInfo: CommandBasicInfo = createCommandBasicInfo(idempotencyKey = idempotencyKey),
) = AgentConverseRequestEvent(
    idempotencyKey = idempotencyKey,
    payload =
        AgentConversePayload(
            prompt = prompt,
            threadId = threadId,
            responseBasicInfo = responseBasicInfo,
        ),
    type = CommandDetailType.AGENT_CONVERSE,
)

fun createApprovalContents(
    idempotencyKey: UUID = UUID.randomUUID(),
    commandDetailType: CommandDetailType = CommandDetailType.SIMPLE_TEXT,
    reason: String = "Test reason",
    publisherId: String = TEST_USER_ID,
    headLineText: String? = null,
) = ApprovalContents(
    idempotencyKey = idempotencyKey,
    commandDetailType = commandDetailType,
    reason = reason,
    publisherId = publisherId,
    headLineText = headLineText ?: "Approval Requests",
)
