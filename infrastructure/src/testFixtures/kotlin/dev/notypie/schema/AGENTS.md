<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-22 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/schema

## Purpose
Builders for JPA schema rows and repository records in the meeting and CVE lanes. `@DataJpaTest` specs persist
the schema builders on H2; `*ImplTest` and `:application` specs use the record builders as mocked-repository
return values. No standup or outbox builders exist here.

## Key Files
| File | Description |
|------|-------------|
| `MeetingSchemaCreator.kt` | `createMeetingSchema(id = 0, meetingUid = random, idempotencyKey = random, name, startAt = now, endAt = null, isCanceled = false, publisherId = TEST_USER_ID, channel = TEST_CHANNEL_ID, participants = mutableListOf())`; `createParticipants(id = 0, meeting = createMeetingSchema(), userId = TEST_USER_ID, isAttending = true, absentReason = ATTENDING, createdAt, updatedAt)`; `createMeetingSchemaWithParticipant(publisherId, participantUserId, name, startAt)` — one participant row back-pointing to the meeting; overload `createMeetingSchema(member: Int, startIterator = 1)` — publisher `TEST_USER_ID + startIterator` and `member` participants `TEST_USER_ID + (startIterator + i)` |
| `OutboxMessageCreator.kt` | `createOutboxMessage(eventId = random, idempotencyKey = random, publisherId = TEST_USER_ID, payload = "{}", createdAt = now, status = PENDING)` — a real `OutboxMessage` row (not a mock) for `@DataJpaTest` specs; `status` is applied through `updateMessageStatus` because the column is not a constructor parameter |
| `CveTopicCreator.kt` | `createCveTopicSchema(id = 0, topicKey = "cve-java", displayName = "Java CVE", category = CVE, sourceType = NVD_CVE, sourceConfig = """{"cpe":"oracle:jdk"}""", deliveryMode = IMMEDIATE, active = true)`; `createCveTopicDefinition(...)` same fields minus `id`; `createCveTopic(id = 1, ...)` record; `createCveSubscriptionSchema(id = 0, userId = "U_SUBSCRIBER", topicId = 1)`; `createCveDeliverySchema(id = 0, eventId = 1, userId = "U_SUBSCRIBER", status = SENT)` |
| `CveEventCreator.kt` | `createCveEvent(id = 1, topicId = 1, externalId = "CVE-2026-0001", title, rawContent, aiSummary = null, summaryStatus = PENDING, retryCount = 0)` record; `createCveEventSchema(id = 0, topicId, externalId, title, rawContent, aiSummary, summaryStatus, claimToken = null, retryCount = 0, nextAttemptAt = null, publishedAt = null)`; `createRawSourceEvent(externalId = "R-0001", title, rawContent, publishedAt = null)` (`impl/cve/RawSourceEvent`); `createCveRecentEvent(topicDisplayName = "Java CVE", title, aiSummary = "Sample summary")`; `createUndeliveredCveEvent(eventId = 1, userId = "U_SUBSCRIBER", topicKey = "cve-java", topicDisplayName, title, aiSummary)` |

## For AI Agents

### Working In This Directory
- **Schema builders default `id = 0`** (IDENTITY assigns on save); record builders default `id = 1`. Pass an
  explicit `id` to a schema builder only in mapping specs that simulate a persisted row — never when the row
  will be saved on H2.
- **Unique keys are the caller's job.** `createCveTopicSchema` always says `"cve-java"` and
  `createCveEventSchema` always says `"CVE-2026-0001"`; `@DataJpaTest` rows persist across blocks, so every
  H2 spec must override `topicKey` / `externalId` / `userId` per block or hit the unique constraints.
- `createMeetingSchema(member =, startIterator =)` exists to mint distinct publisher / participant ids per
  block (`TEST_USER_ID + n`); prefer it over hand-building user ids in H2 specs.
- `createParticipants(meeting = ...)` sets only the child side; add the row to `meeting.participants`
  yourself (or use `createMeetingSchemaWithParticipant`) so the cascade persists it.
- Record builders (`createCveEvent`, `createCveTopic`, `createCveRecentEvent`, `createRawSourceEvent`) are
  consumed mainly from `:application`; `createUndeliveredCveEvent` is used on both sides.
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
- `infrastructure/src/main/kotlin/dev/notypie/repository/cve/` (+ `schema/`) — records, `CveTopicDefinition`,
  schema classes and enums
- `infrastructure/src/main/kotlin/dev/notypie/impl/cve/RawSourceEvent`
- `domain/meet/entity/RejectReason`; `domain/src/testFixtures/.../Constants.kt`

### External
None.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
