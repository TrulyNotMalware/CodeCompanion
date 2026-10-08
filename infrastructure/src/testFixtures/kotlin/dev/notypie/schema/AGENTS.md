<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-08 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/schema

## Purpose
Builders for JPA schema rows and repository records in the meeting, calendar-mirror, CVE, outbox, AI-usage and standup-stop lanes. `@DataJpaTest` specs persist
the schema builders on H2; `*ImplTest` and `:application` specs use the record builders as mocked-repository
return values. Standup schema rows have no builder here: H2 specs persist the domain `createRoutine` through
`StandupRepositoryImpl`; the one standup builder is the `RoutineStopCandidate` record.

## Key Files
| File | Description |
|------|-------------|
| `MeetingSchemaCreator.kt` | `createMeetingSchema(id = 0, meetingUid = random, idempotencyKey = random, name, startAt = now, endAt = null, isCanceled = false, publisherId = TEST_USER_ID, channel = TEST_CHANNEL_ID, participants = mutableListOf(), reason = null)`; `createParticipants(id = 0, meeting = createMeetingSchema(), userId = TEST_USER_ID, isAttending = true, absentReason = ATTENDING, createdAt, updatedAt)`; `createMeetingSchemaWithParticipant(publisherId, participantUserId, name, startAt)` — one participant row back-pointing to the meeting; overload `createMeetingSchema(member: Int, startIterator = 1)` — publisher `TEST_USER_ID + startIterator` and `member` participants `TEST_USER_ID + (startIterator + i)` |
| `MeetingCalendarEventCreator.kt` | Row: `createMeetingCalendarEventSchema(meeting, slackUserId = TEST_USER_ID, status = PENDING, claimToken = null, nextAttemptAt = 2031-01-01T00:00Z)` (a row at seq 1; `status` / `claimToken` set up states no transition produces, such as a non-`SYNCING` row holding a token; the unique key `(meeting_id, slack_user_id)` is the caller's job). Records: `createMeetingCalendarEvent(id = 1, meetingId = 1, slackUserId = TEST_USER_ID, googleEventId = null, status = PENDING, changeSeq = 1, attempts = 0, nextAttemptAt = 2031-01-01T00:00Z, lastError = null)` and `createCalendarMeetingView(meetingId = 1, meetingUid = fixed, title = "test meeting schema", reason = "", startAt = 2031-01-01T10:00, endAt = startAt + 1 h, isCanceled = false, hostId = TEST_USER_ID, attendingUserIds = empty)` — expected values in `JpaMeetingCalendarEventRepositoryTest`, mocked-port return values for `:application` |
| `OutboxMessageCreator.kt` | `createOutboxMessage(eventId = random, idempotencyKey = random, publisherId = TEST_USER_ID, payload = "{}", createdAt = now, status = PENDING)` — a real `OutboxMessage` row (not a mock) for `@DataJpaTest` specs; `status` is applied through `updateMessageStatus` because the column is not a constructor parameter; `createOutboxColumnMap(eventId, createdAt, updatedAt?)` — the snake_case column map a Debezium after-image carries, for `toOutboxMessage()` specs |
| `CveTopicCreator.kt` | `createCveTopicSchema(id = 0, topicKey = "cve-java", displayName = "Java CVE", category = CVE, sourceType = NVD_CVE, sourceConfig = """{"cpe":"oracle:jdk"}""", deliveryMode = IMMEDIATE, active = true)`; `createCveTopicDefinition(...)` same fields minus `id`; `createCveTopic(id = 1, ...)` record; `createCveSubscriptionSchema(id = 0, userId = "U_SUBSCRIBER", topicId = 1)`; `createCveDeliverySchema(id = 0, eventId = 1, userId = "U_SUBSCRIBER", status = SENT)` |
| `CveEventCreator.kt` | `createCveEvent(id = 1, topicId = 1, externalId = "CVE-2026-0001", title, rawContent, aiSummary = null, summaryStatus = PENDING, retryCount = 0)` record; `createCveEventSchema(id = 0, topicId, externalId, title, rawContent, aiSummary, summaryStatus, claimToken = null, retryCount = 0, nextAttemptAt = null, publishedAt = null)`; `createRawSourceEvent(externalId = "R-0001", title, rawContent, publishedAt = null)` (`impl/cve/RawSourceEvent`); `createCveRecentEvent(topicDisplayName = "Java CVE", title, aiSummary = "Sample summary")`; `createUndeliveredCveEvent(eventId = 1, userId = "U_SUBSCRIBER", topicKey = "cve-java", topicDisplayName, title, aiSummary)` `createNvdPageJson(cveIds, totalResults)`: an NVD 2.0 response page with `totalResults` and one minimal vulnerability per id. |
| `StandupRecordCreator.kt` | `createRoutineStopCandidate(routineUid = random, name = "Daily Standup", creatorId = TEST_USER_ID)` — the `lockActiveRoutinesByChannel` record, for `StandupRoutineOpsServiceTest` stubs and `StandupRepositoryImplTest` expectations |
| `UsageHistoryCreator.kt` | Rows: `createAgentTurnHistorySchema(id = 0, sessionKey = "thread-1", requesterId = TEST_USER_ID, channel = TEST_CHANNEL_ID, idempotencyKey = random, outcome = COMPLETED, errorCode = null, inputTokens = 100, outputTokens = 10, durationMs = 1000)`; `createMcpToolCallHistorySchema(id = 0, toolName = "get_status", requesterId = TEST_USER_ID, sessionKey = "thread-1", turnId = random, resolvedRole = DEVELOPER, outcome = COMPLETED, errorCode = null, argumentsJson = null, durationMs = 50)`. Records: `createAgentTurnOutcomeUsage(outcome = COMPLETED, turns = 1, inputTokens = 100, outputTokens = 10, totalDurationMs = 1000)`, `createRequesterTurnUsage(requesterId = TEST_USER_ID, turns = 1, inputTokens = 100, outputTokens = 10)`, `createToolCallUsage(toolName = "get_status", outcome = COMPLETED, calls = 1)` |

## For AI Agents

### Working In This Directory
- **Schema builders default `id = 0`** (IDENTITY assigns on save); record builders default `id = 1`. Pass an
  explicit `id` to a schema builder only in mapping specs that simulate a persisted row — never when the row
  will be saved on H2.
- The two history builders take no `createdAt`: `@CreationTimestamp` overwrites any constructor value on insert.
  Window specs age a row with a JDBC `UPDATE … SET created_at` after `persistAndFlush`.
- **Unique keys are the caller's job.** `createCveTopicSchema` always says `"cve-java"` and
  `createCveEventSchema` always says `"CVE-2026-0001"`; `@DataJpaTest` rows persist across blocks, so every
  H2 spec must override `topicKey` / `externalId` / `userId` per block or hit the unique constraints.
- `createMeetingSchema(member =, startIterator =)` exists to mint distinct publisher / participant ids per
  block (`TEST_USER_ID + n`); prefer it over hand-building user ids in H2 specs.
- `createParticipants(meeting = ...)` sets only the child side; add the row to `meeting.participants`
  yourself (or use `createMeetingSchemaWithParticipant`) so the cascade persists it.
- Record builders (`createCveEvent`, `createCveTopic`, `createCveRecentEvent`, `createRawSourceEvent`, the three
  usage records) are consumed mainly from `:application`; `createUndeliveredCveEvent` is used on both sides.
- Missing here: `RoutineSchema` / `StandupSessionSchema` builders (the standup lane has no repository spec)
  and any `OutboxMessage` builder (`CodecOutboundMessagePort.toRow` is used directly instead).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
The `@DataJpaTest` specs in `repository/cve` and `repository/meeting` are the persistence check for the
schema builders; a builder default that violates a column constraint fails there first.

### Common Patterns
- One top-level `create<Type>` per class with every constructor parameter defaulted; `TEST_USER_ID` /
  `TEST_CHANNEL_ID` from `:domain` `Constants.kt`.
- Parallel `create<X>Schema` / `create<X>` / `create<X>Definition` triples for the CVE topic so the same
  defaults describe the row, the record and the yaml shape.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/repository/meeting/schema/` — `MeetingSchema`, `ParticipantsSchema`
- `infrastructure/src/main/kotlin/dev/notypie/repository/calendar/` (+ `schema/`) — `CalendarMeetingView`,
  `MeetingCalendarEvent`, `MeetingCalendarEventSchema`, `CalendarSyncStatus`
- `infrastructure/src/main/kotlin/dev/notypie/repository/cve/` (+ `schema/`) — records, `CveTopicDefinition`,
  schema classes and enums
- `infrastructure/src/main/kotlin/dev/notypie/repository/agent/` and `repository/mcp/` (+ `schema/`) — history schemas,
  outcome enums and usage aggregate records; `domain/command/authorization/UserRole`
- `infrastructure/src/main/kotlin/dev/notypie/impl/cve/RawSourceEvent`
- `domain/meet/entity/RejectReason`; `domain/src/testFixtures/.../Constants.kt`

### External
None.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
