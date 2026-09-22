# 이벤트와 아웃박스

_type: architecture · updated: 2026-09-22_

> Slack API 호출과 DB 쓰기는 한 트랜잭션으로 묶을 수 없으므로, 아웃바운드 효과는 중립 봉투로 `outbox_message`에
> 먼저 커밋되고 릴레이(폴링 또는 Debezium CDC)가 배송 시점에 렌더·전송한다. 보장은 at-least-once + 멱등 소비자다.

## 왜 아웃박스인가

- 커맨드 하나가 "DB 상태 변경 + Slack 메시지"를 함께 만든다. 둘을 순서대로 실행하면 두 실패 모드가 남는다:
  DB 커밋 뒤 Slack 호출 실패(상태는 바뀌었는데 아무도 모름), Slack 호출 뒤 DB 롤백(메시지는 갔는데 상태가 없음).
  아웃박스는 효과를 **커맨드의 트랜잭션 안에서 row로 커밋**하고 전송은 나중에 한다. 요청 스레드에서 Slack HTTP를
  직접 호출하는 것은 이 durability를 우회하므로 금지다(`Refactor.md`, `AsyncConfig` 주석).
- 대가: 응답 지연(폴링 tick 5초 또는 CDC 지연)과, 렌더가 요청 트랜잭션 밖(배송 시점)에서 일어난다는 점.
  Approval 렌더의 `users.profile.get` HTTP도 이 결정으로 DB 트랜잭션 밖으로 나갔다(`Refactor.md` Phase 8b).

## 효과가 아웃박스에 들어가는 두 경로

1. **인터랙티브 경로(커맨드 파이프라인)** — `CommandExecutor.publishIntents`가 `OutboundMessage`를
   `OutboundMessageStager.stage`에 넘기고, `SlackOutboundStager`는 모달을 제외한 모든 가족을 **미렌더** 상태로
   `OutboundMessageEnqueued`(`isInternal = true`)에 감싼다. `EventPublisher.publishEvent` → Spring 이벤트 버스 →
   `SlackMessageRelayServiceImpl.saveOutboxMessage`(`@TransactionalEventListener(phase = BEFORE_COMMIT)`)가
   `OutboundMessagePort.toRow`로 row를 만들어 `MessageOutboxRepository.save`. 진입 핸들러
   (`MeetingServiceImpl.handleMeeting`, `SlackMentionEventHandlerImpl.handleEvent`,
   `SlackInteractionHandlerImpl.handleInteraction`)가 `@Transactional`이므로 도메인 쓰기와 row가 함께 커밋된다.
   - **리스너는 활성 트랜잭션이 있어야 동작한다.** `fallbackExecution`이 기본(false)이라 트랜잭션 밖에서 publish
     하면 이벤트가 조용히 버려진다. Codex 리뷰 1라운드의 "DM never sent"가 정확히 이 사고였다(`Handoff.md`).
     트랜잭션이 없는 곳(`@Async` 리스너, 스케줄러)은 `TransactionTemplate.runInTx { }` 안에서 publish 하거나
     (`AgentConverseService`), 리스너에 `@Transactional`을 명시하거나(`RoleManagementService`), 아래 2번 경로를 쓴다.
     같은 이유로 `AsyncConfig`는 `ApplicationEventMulticaster`를 비동기로 바꾸지 않는다(느린 리스너만 `@Async` opt-in).
2. **스케줄러 경로(직접 저장)** — 요청 트랜잭션이 없는 tick에서는 `outboundMessagePort.toRow(...)`를
   `outboxRepository.save(...)`로 **직접** 저장하되, 반드시 `transactionTemplate.runInTx { }` 안에서 CAS 갱신과 한
   트랜잭션으로 묶는다. 구현: `StandupSchedulingService`, `DailyAgendaSchedulingService`,
   `MeetingReminderSchedulingService`, `StandupSummaryService`, `CveNotificationDispatcher`. 커맨드 컨텍스트는
   `CommandBasicInfo.forOutbound(publisherId, channel)`로 합성한다(`appToken` 공란: 봇 토큰으로 인증). DM은
   별도 타입이 아니라 `channel = userId`인 `ChannelMessage`/`Approval`로 표현한다.
   - `runInTx`(`TransactionTemplateExt.kt`)는 예외를 `Result.failure`로 바꾸고 rollback-only를 찍는다. 호출자는
     `Result`를 보고 실패 감사(`markDispatchFailed` 등)를 **별도 트랜잭션**에서 기록한다.

## 렌더는 배송 시점에 정확히 한 번, 모달만 예외

- 폴링·CDC 공용 `OutboxPayloadRenderer`가 스키마 버전 검사 → `OutboundMessageCodec.decode` →
  `Map<Transport, OutboundRenderer>`에서 `SlackOutboundRenderer`를 골라 wire payload를 만든다. 렌더러와 스테이저는
  같은 `SlackApiEventConstructor`를 쓰므로 출력이 byte 동일하다. 두 번째 렌더 지점을 만들지 않는다.
- `OutboundMessage.OpenModal`은 `trigger_id`가 발급 후 3초에 만료되므로 아웃박스를 탈 수 없다.
  `SlackOutboundStager.stageModal`이 동기 렌더 → `OpenViewEvent` → `SlackViewOpenDispatcher`(`@EventListener`,
  의도적으로 non-`@Async`) → `MessageDispatcher.dispatchImmediate`. 실패하면 예외 대신
  `DeclineModalOpenFailedEvent`/`StandupModalOpenFailedEvent`를 발행해 폴백 안내를 보낸다.
  `ApplicationMessageDispatcher.dispatch`는 `OpenViewPayloadContents`를 받으면 `UnsupportedOperationException`.
- 렌더 실패(코덱/스키마)는 재시도하지 않고 `MessagePublishFailedEvent`로 바로 `FAILURE` 처리한다. 재시도는
  dispatch(Slack HTTP)에만, `ApplicationMessageDispatcher` 안의 `RetryService`가 담당한다.

## 아웃박스 행(`outbox_message`)

- PK는 `event_id`(저장 시 `CodecOutboundMessagePort.toRow`가 UUID 발급). `idempotency_key`는
  `idx_outbox_idempotency_key` **인덱스일 뿐 유니크가 아니다**: 다중 수신자 커맨드(참가자별 ApplyReject)가 같은
  키로 여러 행을 만들어 PK 충돌로 메시지가 유실됐고, V1 마이그레이션이 PK를 `event_id`로 옮겼다. 용도는 "커맨드
  X가 만든 모든 메시지 찾기"이지 중복 차단이 아니다.
- `payload`는 opaque TEXT. `OutboundMessageCodec`이 `OutboundEnvelope{message, basicInfo}`를 인코딩하며, 도메인은
  Jackson 주석 없이 유지하고 다형성은 코덱 쪽 mix-in으로 처리한다. `OpenModal`·`DirectMessage`는 의도적으로
  미등록이라 인코딩/디코딩 시 fail-fast 한다.
- `transport`는 문자열 컬럼(Debezium CDC가 enum을 null로 전달). 오늘은 `SLACK`뿐; 추가 = enum 상수 + 렌더러 등록.
- `schema_version`: 쓰기는 `OutboxSchemaVersion.CURRENT`(= V2), 읽기는 `SUPPORTED`(= {V2})만 허용
  (`OutboxPayloadRenderer.render`의 `require`). V1(렌더된 Slack 페이로드 + `metadata`/`type` 컬럼)은 big-bang
  drain으로 폐기했다(V11 마이그레이션, 데이터 이전 없음). 새 shape 도입 시 `CURRENT` 범프와 `SUPPORTED` 확장을
  같이 하고, drain 창이 보장된 뒤에만 옛 버전을 뺀다.
- `@Version version`(낙관적 락)이 상태 갱신의 read-modify-write를 보호한다(`updateMessage`, 최대 5회 재시도).
- 상태(`MessageStatus`): 새 행은 `PENDING`. 폴링은 `claimPending`으로 `IN_PROGRESS`, dispatch 결과 이벤트
  (`OutboxUpdateEvent`)로 `SUCCESS`/`FAILURE`. **CDC 경로는 claim 없이 `PENDING` → `SUCCESS`/`FAILURE`로 바로
  간다.** `INIT`은 어디서도 쓰이지 않는다. `FAILURE` 행을 다시 읽는 코드는 없다(재구동은 수동).
- `claimPending` CAS 계약: `UPDATE ... SET status = 'IN_PROGRESS', updated_at = CURRENT_TIMESTAMP
  WHERE event_id IN (:eventIds) AND status = 'PENDING'`. `WHERE status = 'PENDING'`이 진실의 원천이고, 실제로
  전이된 건수만 dispatch 한다. `updated_at`을 명시로 찍는 이유는 헬스가 `IN_PROGRESS`를 claim 시점부터 aging
  하기 위해서다.
- stuck 복구: `findStuckInProgress(updated_at < now - slack.app.outbox.polling.stuck-in-progress-seconds)`
  (기본 300초)를 tick마다 먼저 재전송한다. 재전송은 중복 게시를 감수한 선택이다(아래 보장 절).

## 릴레이 모드와 이벤트 퍼블리셔

| 키 (`slack.app.mode.*`) | 값 | 조건 클래스 → 설정 |
|---|---|---|
| `outbox-reading-strategy` | `polling`(코드 기본) / `cdc` | `OnPollingConsumer` → `PoolingPublisherConfig`, `OnCdcConsumer` → `CdcPublisherConfig` + `CdcConsumerConfiguration` |
| `event-publisher` | `application_event`(코드 기본) / `kafka` | `OnApplicationEventPublisher` → `AppEventPublisher`, `OnKafkaEventPublisher` → `KafkaEventPublisher` + 프로듀서 |
| `cdc.topic` | Debezium 토픽명 | `DebeziumLogTailingProcessor`의 `@KafkaListener(topics = ...)` |

- 프로파일: `local`/`dev`/`prod`는 `cdc` + `kafka`, 토픽 `cdc.code_companion.outbox_message`(prod는
  `${SLACK_CDC_TOPIC}`). `slack-live`는 `polling` + `application_event`로 Kafka/Debezium 없이 전체 릴레이가 in-process다.
- **POLLING** — `PollingMessageProcessor`, `@Scheduled(fixedRate = 5000)`(하드코딩). tick당 복구 → `PENDING`
  `batch-size`(기본 100)건 읽기 → `claimPending` → claim된 건수만 dispatch, 내부 루프 없음(스케줄러 스레드 독점
  방지). `SlackMessageRelayServiceImpl.batchPendingMessages`는 `@Async` 대신 `Executor`에 직접 submit 한다
  (같은 빈 내부 self-invocation은 AOP 프록시를 타지 않음). 이 `Executor`는 `AsyncConfig`의 `@Primary`
  `threadPoolTaskExecutor`(core 10)로 해소된다.
- **CDC** — Debezium MariaDB 커넥터(`table.include.list: code_companion.outbox_message`, `topic.prefix: cdc`;
  `cdc/docker-compose/debezium/connect_mariadb.sh`) → Kafka → `DebeziumLogTailingProcessor`. 리스너는
  `spring.json.use.type.headers:false` + 기본 타입 `Envelope`로 역직렬화하고, `payload.after.status == PENDING`인
  레코드만 후보로 본다(`IN_PROGRESS`/`SUCCESS` 갱신 레코드와 `after == null`인 삭제, tombstone은 무시). 그 뒤
  **DB의 현재 상태를 다시 읽어** `PENDING`이면 `claimPending` CAS로 `IN_PROGRESS`를 잡고 dispatch, `SUCCESS`/`FAILURE`면
  재전달로 보고 건너뛰며, `IN_PROGRESS`(크래시 잔재)는 재발송한다 — 2026-09-22 재설계, `DebeziumLogTailingProcessorTest`.
  파싱 실패·잘못된 `eventId`는 `CdcRecordParseException`으로 던져 `DefaultErrorHandler`가 재시도 없이 `<topic>.DLT`로
  보낸다(`KafkaTemplate`이 없는 조합에서는 ERROR 로그로 강등). 예전의 `KafkaErrorHandler`(null 레코드 skip)는 제거됐다.
  `ErrorHandlingDeserializer` 래핑은 그대로다.
- `SchedulingConfig`가 `@EnableScheduling`을 무조건 켠다. 과거엔 `PoolingPublisherConfig`에만 있어 CDC 모드에서
  `StandupScheduler` 등 모든 `@Scheduled`가 조용히 no-op이었다.
- `KafkaEventPublisher`는 `isInternal == false`인 이벤트만 Kafka(`event.destination` 토픽, key = `idempotencyKey`,
  5초 동기 대기)로 보내는데, 현재 모든 `CommandEvent`가 `isInternal = true`라 이 경로는 휴면이다. `kafka` 모드의
  실질 차이는 `KafkaErrorBroadcaster` 빈 등록뿐이고, 그 `broadcastError`는 `TODO()`이며 호출자도 없다.

## 멱등성

- **인바운드 키**: `IdempotencyCreator.create(data, now)` = `UUID.nameUUIDFromBytes("$data|${now / 1000}")`.
  같은 입력이 같은 **1초 창** 안에 오면 같은 키가 된다(`IdempotencyData`는 SHA-256으로 직렬화). 창을 넘긴
  재시도는 다른 키이며, `idempotency_key`가 유니크가 아니므로 아웃박스가 중복을 막아 주지도 않는다.
- **Slack 재시도**: `SlackRequestVerificationFilter`가 `InMemorySlackRetryDeduplicator`로 (method, uri,
  timestamp, signature) fingerprint를 기억하고, `X-Slack-Retry-Num`이 붙은 재시도가 이미 본 fingerprint면
  본문 처리 없이 200을 돌려준다. TTL 10분, in-memory이므로 **인스턴스별**이다.
- **소비자 측**: CDC는 상태 필터(`PENDING`만), 폴링은 `claimPending` CAS. 상태 갱신 이벤트는 항상 **row의**
  `event_id`로 키를 잡는다 — 렌더러가 새로 발급하는 payload `eventId`는 버린다. Slack API에는 멱등 키가 없으므로
  재전송은 그대로 중복 게시가 된다.

## 다중 인스턴스: 락 없이 DB 행 CAS

| 패턴 | 술어 | 구현 |
|---|---|---|
| claim-token CAS | claim: `... SET status='SENDING', claim_token=:token WHERE status='PENDING'`, 완료/실패: `WHERE status='SENDING' AND claim_token=:token` | `JpaSessionDispatchRepository`(standup DM), `JpaMeetingReminderRepository`, `JpaCveEventRepository`(summarize) |
| `INSERT IGNORE` once-per-window ledger | 유니크 키 + `INSERT IGNORE`, 반환 1이면 claim | `JpaAgendaDispatchRepository.claimAgenda(agenda_date)`, `JpaCveCollectLedgerRepository`(topic_id, window_start), `JpaCveDeliveryRepository`(event_id, user_id) |
| once-only stamp | `WHERE nudged_at IS NULL AND status='COLLECTING'` / `WHERE status='COLLECTING'` | `JpaStandupSessionRepository.claimNudge`, `markSummarized` |

- claim 토큰이 붙은 이유: `markDispatchFailed`가 **다른 tick의** claim을 덮어쓸 수 있다는 Codex 3라운드 지적.
  이후 모든 CAS 술어가 토큰을 함께 검사한다(`Handoff.md`). 4라운드는 `claim_token`에 마이그레이션이 없어 prod
  (`ddl-auto: none`)에서 깨질 뻔한 지적 — 새 컬럼은 반드시 `V*` 스크립트가 따라가야 한다.
- 트랜잭션 구조(`StandupSchedulingService.processDispatch`): claim은 자체 트랜잭션으로 커밋 → 메시지 빌드 +
  `outboxRepository.save` + `markDispatchSent`를 한 트랜잭션(`markDispatchSent`가 no-op이면 `error()`로 롤백:
  복구가 먼저 리셋한 경우) → 실패 시 `markDispatchFailed`는 새 트랜잭션. `CveNotificationDispatcher`는 claim과
  outbox save를 한 트랜잭션에 넣는다(claim 쪽 `@Transactional`이 REQUIRED라 join) — "저장 안 된 배송을 ledger가
  기록"하는 일을 막는다.
- 아웃박스 자체의 다중 폴러 안전은 `claimPending`의 `WHERE status = 'PENDING'` + `@Version`이 담당한다.

## 순서·파티션·보장

- Debezium 커넥터 설정에 `message.key.columns`가 없으므로 레코드 키는 기본값인 PK(`event_id`)다. 즉 채널이나
  커맨드 단위 순서는 파티션 간에 보장되지 않는다. 토픽 파티션 수는 저장소에 없다(미확인).
- 폴링은 `created_at ASC`로 읽지만 dispatch는 executor 병렬이라 배치 안에서도 순서가 없다.
  `PartitionKeyUtil`(6 버킷)은 테스트 외 호출자가 없다.
- `spring.kafka.consumer.enable-auto-commit: false` + 컨테이너 `AckMode.RECORD`(2026-09-22)라 오프셋은 리스너가
  그 레코드를 반환한 뒤에만 커밋된다. 크래시 시 재전달되며, 위의 현재 상태 확인이 중복 발송을 막는다(claim과
  상태 갱신 사이의 좁은 창은 at-least-once). `max-poll-records`는 100.
- 정직한 보장: README의 "exactly-once-style"은 지향 표현이다. 실제는 **at-least-once**(폴링 stuck 재전송, Kafka
  재전달) + **멱등 소비자**(상태 필터, `event_id` 기준 상태 갱신)이며, Slack 채널에는 중복 게시가 가능하다.

## 헬스와 `@bot status`

- `OutboxHealthIndicator`: `slack.app.outbox.health.stuck-threshold-seconds`(기본 300)보다 오래된 `PENDING`
  (`created_at` 기준) 또는 `IN_PROGRESS`(`updated_at` 기준)가 하나라도 있으면 DOWN. `PENDING`-stuck은 폴러
  지연/정지, `IN_PROGRESS`-stuck은 claim 뒤 크래시를 가리킨다. 디테일 키는 대시보드용으로 고정:
  `pendingCount`, `stuckPendingCount`, `stuckCount`(구 별칭), `oldestPendingAgeSeconds`, `inFlightCount`,
  `stuckInFlightCount`, `oldestInFlightAgeSeconds`, `stuckThresholdSeconds`.
- `OpsStatusService.renderReport`가 **같은 카운터**를 읽어 `@bot status` 답장(스테이저 경유 채널 메시지)과 MCP
  `get_status`를 만든다. 채팅과 actuator가 다른 숫자를 말하지 않게 하려는 의도다.
- `OutboxSchemaVersion` KDoc은 미지원 버전 행이 "stuck으로 드러난다"고 하지만 실제 경로는 렌더 실패 →
  `MessagePublishFailedEvent` → `FAILURE`다. 헬스에는 잡히지 않고 로그에만 남는다.

## 함정

- `OutboundMessage.DirectMessage`는 타입만 남아 있다: 코덱 미등록, 렌더러 `error(...)`. DM은
  `ChannelMessage`/`Approval` + `channel = userId`로 쓴다.
- BEFORE_COMMIT 리스너는 트랜잭션 밖 publish를 조용히 버린다(위 1번 경로). `stage()`가 null을 돌려줄 수 있는
  계약이라 `AgentConverseService.stageReply`처럼 `checkNotNull`로 응답 유실을 트랜잭션 실패로 바꾼다.
- `PollingMessageProcessor.claimAndDispatch`는 행마다 `claimPending(listOf(id)) == 1`로 claim해 이긴 행만 dispatch
  한다(2026-09-22; 이전의 `candidates.take(claimedCount)`는 다중 폴러에서 남의 행을 보냈다). stuck 복구도
  `reclaimStuck` CAS를 이긴 행만 재전송한다.
- CDC 모드도 이제 `claimPending`으로 `IN_PROGRESS`를 잡는다. 상태 갱신 실패·DLT로 남은 `IN_PROGRESS`/오래된 `PENDING`은
  모드와 무관한 `OutboxRecoveryScheduler`(60초, `reclaimStuck`/`claimPending` CAS)가 재발송한다 — CDC는 그런 행에
  두 번째 변경 이벤트를 만들지 않기 때문. 헬스의 `IN_PROGRESS` 지표도 이제 두 모드 모두에서 의미가 있다.
- 구 shape 로컬 행은 디코드에 실패하고 `ddl-auto: update`는 옛 컬럼을 안 지운다 → `outbox_message` DROP 후 재생성.
- `RetryService.execute`는 호출마다 공유 `RetryTemplate`의 `retryPolicy`를 덮어쓴다. 릴레이 executor 10스레드가
  동시에 dispatch 하면 정책이 섞일 수 있다.

## 근거

- `infrastructure/src/main/kotlin/dev/notypie/repository/outbox/` 전체 (repository, codec, port, `schema/*`)
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/` (`SlackOutboundStager`, `OutboundRenderer`,
  `SlackViewOpenDispatcher`, `ApplicationMessageDispatcher`, `KafkaEventPublisher`, `AppEventPublisher`, `event/*`),
  `impl/retry/RetryService.kt`, `exception/KafkaErrorBroadcaster.kt`
- `infrastructure/src/main/kotlin/dev/notypie/repository/{standup,meeting,cve}/Jpa*Repository.kt` (CAS·ledger·stamp)
- `domain/src/main/kotlin/dev/notypie/domain/command/` — `outbound/{OutboundMessage,OutboundMessageStager}.kt`,
  `entity/event/{EventPublisher,Event}.kt`, `dto/CommandBasicInfo.kt`
- `application/src/main/kotlin/dev/notypie/application/` — `service/relay/*`, `configurations/{ConsumerConfig,
  SchedulingConfig,KafkaConsumerConfiguration,AsyncConfig,AppConfig,SlackRequestBuilderConfiguration}.kt`,
  `configurations/conditions/Conditions.kt`, `health/OutboxHealthIndicator.kt`, `service/ops/OpsStatusService.kt`,
  `common/{IdempotencyCreator,TransactionTemplateExt}.kt`, `security/{SlackRetryDeduplicator,
  SlackRequestVerificationFilter}.kt`, `service/command/{CommandExecutor,RoleManagementService}.kt`,
  `service/{standup/StandupSchedulingService,cve/notification/CveNotificationDispatcher,agent/AgentConverseService}.kt`
- `application/src/main/resources/application*.yaml`, `resources/cdc/docker-compose/` (README, `connect_mariadb.sh`),
  `resources/db/migration/V1__outbox_pk_event_id.sql`, `V11__outbox_transport_neutral_envelope.sql`
- `README.md`(Event-Driven Architecture), `infrastructure/AGENTS.md`, `application/AGENTS.md`,
  `application/src/main/resources/AGENTS.md`; git 미추적 근거 문서 `Handoff.md`(Phase 2, Codex 리뷰 표), `Refactor.md`(8b·9)

## 관련 페이지

- [architecture-overview.md](architecture-overview.md) · [command-pipeline.md](command-pipeline.md) — 상류 흐름
- [ddd-layering.md](ddd-layering.md) — 도메인이 Jackson·Slack 타입을 모르는 채로 아웃박스에 실리는 이유
- [dev-environment.md](dev-environment.md) — 프로파일별 릴레이 모드, CDC 스택, `V*` 마이그레이션 관례
- [testing-guide.md](testing-guide.md) — `OutboxTestFixtures`, `EmbeddedKafka` 슬라이스
- [decisions.md](decisions.md) · [history.md](history.md)
- [`../../infrastructure/AGENTS.md`](../../infrastructure/AGENTS.md) · [`../../application/AGENTS.md`](../../application/AGENTS.md)
