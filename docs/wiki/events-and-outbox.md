# 이벤트와 아웃박스

_type: architecture · updated: 2026-10-02_

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
     트랜잭션이 없는 곳(executor 스레드, 스케줄러)은 `TransactionTemplate.runInTx { }` 안에서 publish 하거나
     (`AgentConverseService`), 리스너에 `@Transactional`을 명시하거나(`RoleManagementService`), 아래 2번 경로를 쓴다.
     AFTER_COMMIT 리스너 안(`afterCompletion`)은 예외다: 커밋된 트랜잭션이 아직 묶여 있어 REQUIRED는 거기에 합류하고
     아무것도 커밋되지 않으므로 `PROPAGATION_REQUIRES_NEW`를 쓴다(`AgentConverseService.publishOverloaded`, 2026-10-01).
     같은 이유로 `AsyncConfig`는 `ApplicationEventMulticaster`를 비동기로 바꾸지 않는다(느린 일은 전용 executor에 직접 넘긴다).
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
  의도적으로 non-`@Async`) → `MessageDispatcher.dispatchImmediate`. 단, 트랜잭션 경계를 가진 호출자(슬래시 컨트롤러,
  Socket Mode, interaction 핸들러)는 `ViewOpenDeferral.afterBoundary { }`로 감싸므로 `views.open`은 그 트랜잭션이
  커밋되고 커넥션을 돌려준 **뒤에** 같은 요청 스레드에서 호출된다(2026-10-01). 경계가 실패하면 열지 않는다. 실패하면 예외 대신
  `DeclineModalOpenFailedEvent`/`StandupModalOpenFailedEvent`를 발행해 폴백 안내를 보낸다.
  `ApplicationMessageDispatcher.dispatch`는 `OpenViewPayloadContents`를 받으면 `UnsupportedOperationException`.
- 렌더 실패(코덱)는 재시도하지 않고 `MessagePublishFailedEvent`로 바로 `FAILURE` 처리한다. 단 이 바이너리가 읽을
  수 없는 `schema_version`(새 릴리스가 쓴 행)은 실패가 아니다: `dispatchClaimed`가 `renewClaim` 전에 걸러 ERROR 로그만
  남기고 행을 `IN_PROGRESS`로 둔다(발송 예산 미차감). 스윕이 stuck 임계마다 회수하다가 그 버전을 읽는 바이너리가
  보내거나 24시간 상한에서 포기한다. 재시도는
  dispatch(Slack HTTP)에만 있다: 짧은 재시도는 `ApplicationMessageDispatcher` 안의 `RetryService`(3회, 약 0.3초),
  긴 재시도는 복구 스윕이다.
- dispatcher는 릴레이에 **세 가지 결과**를 돌려준다. ① 완료(성공 또는 영구 실패: `fatal_error`, `ok=false` 오류,
  3xx/4xx, 거부된 `response_url`) → `completeClaim`으로 `SUCCESS`/`FAILURE`. ② rate limit(`RateLimitedOutput`,
  `retryAfter()`) → 행을 `IN_PROGRESS`로 두고 `deferClaim`으로 `Retry-After` 이후까지 미룬다. ③ 일시 오류
  소진(`TRANSIENT_EXHAUSTED_REASON`: 5xx, `IOException`·call timeout, `internal_error`/`service_unavailable`가 짧은
  재시도를 다 쓴 경우) → 아무것도 쓰지 않고 `IN_PROGRESS`로 둬 stuck 임계 뒤 스윕이 재발송한다. 예전에는 ③이
  예외로 올라가 `FAILURE`가 되어 1초짜리 Slack 장애에도 메시지를 잃었다. dispatcher가 예상 밖 예외를 던져도 ③과
  같이 다룬다. ④ 접근 차단(`isAccessBlocked()`: 토큰·스코프·워크스페이스 전역 거부, 분류는 dispatcher) → 행 단위
  `FAILURE` 대신 ②와 같은 `deferClaim`으로 `ACCESS_BLOCKED_DEFER`(15분) 뒤로 미루고(24시간 상한까지 보류),
  `AccessBlockedTracker`에 시각을 남긴다. 토큰을 고치면 보류된 행이 다음 회수 때 나간다.
- **한 번의 dispatch는 시간 상한이 있다.** Slack SDK 클라이언트와 `response_url` 클라이언트 모두 OkHttp
  `callTimeout` 6초(`SLACK_CALL_TIMEOUT`), SDK stats는 끈다(stats가 켜져 있으면 SDK가 `Retry-After`를
  `Long.valueOf`로 먼저 읽어 HTTP-date에서 예외가 나고, 팀 ID 해석용 `auth.test`를 호출마다 추가로 부른다).
  Slack HTTP 쪽 최악값(렌더의 프로필 조회 + 디스패치 재시도)의 산식과 합계는 `infrastructure/.../impl/command/AGENTS.md`
  한 곳에만 둔다(여기에 숫자를 복사하지 않는다). 레코드 1건에는 SQL 대기가 더해진다: 풀이 고갈되면
  `findById`·`claimPending`·`renewClaim`·`completeClaim`(최대 3회) 시도마다 Hikari `connection-timeout`까지 기다릴 수
  있다. 이 몫과 결과(리밸런스는 나도 중복 발송은 없음, stuck 임계 300초 조건)는
  `application/.../service/relay/AGENTS.md`의 "Per-record time budget"에 있다. `response_url` 클라이언트는 리다이렉트를
  따라가지 않으므로 호스트 허용 목록(`https`, 443, `hooks.slack.com`/`hooks.slack-gov.com`)이 최종이다.

## 아웃박스 행(`outbox_message`)

- PK는 `event_id`(저장 시 `CodecOutboundMessagePort.toRow`가 UUID 발급). `idempotency_key`는
  `idx_outbox_idempotency_key` **인덱스일 뿐 유니크가 아니다**: 다중 수신자 커맨드(참가자별 ApplyReject)가 같은
  키로 여러 행을 만들어 PK 충돌로 메시지가 유실됐고, V1 마이그레이션이 PK를 `event_id`로 옮겼다. 용도는 "커맨드
  X가 만든 모든 메시지 찾기"이지 중복 차단이 아니다.
- `payload`는 opaque `MEDIUMTEXT`(V23; `TEXT`의 65,535바이트를 넘는 행은 strict 모드에서 쓰기가 롤백됐다). `OutboundMessageCodec`이 `OutboundEnvelope{message, basicInfo}`를 인코딩하며, 도메인은
  Jackson 주석 없이 유지하고 다형성은 코덱 쪽 mix-in으로 처리한다. `OpenModal`·`DirectMessage`는 의도적으로
  미등록이라 인코딩/디코딩 시 fail-fast 한다.
- `transport`는 문자열 컬럼(Debezium CDC가 enum을 null로 전달). 오늘은 `SLACK`뿐; 추가 = enum 상수 + 렌더러 등록.
- `schema_version`: 쓰기는 `OutboxSchemaVersion.CURRENT`(= V2), 읽기는 `SUPPORTED`(= {V2})만 허용
  (`OutboxPayloadRenderer.render`의 `require`). V1(렌더된 Slack 페이로드 + `metadata`/`type` 컬럼)은 big-bang
  drain으로 폐기했다(V11 마이그레이션, 데이터 이전 없음). 새 shape 도입 시 `CURRENT` 범프와 `SUPPORTED` 확장을
  같이 하고, drain 창이 보장된 뒤에만 옛 버전을 뺀다.
- 상태(`MessageStatus`): 새 행은 `PENDING`. 폴링·CDC·복구 스윕 모두 `claimPending`/`reclaimStuck`으로
  `IN_PROGRESS`를 잡고, dispatch 결과를 `completeClaim`으로 `SUCCESS`/`FAILURE`에 기록한다. `INIT`은 어디서도
  쓰이지 않는다. `FAILURE` 행을 다시 읽는 코드는 없다(재구동은 수동).
- `attempt_count`(V20)가 **소유권 토큰**이다. claim(`PENDING` → `IN_PROGRESS`)과 reclaim(stuck 행 회수)만
  `attempt_count + 1`을 하며, 둘 다 호출자가 읽은 값을 `WHERE attempt_count = :attemptCount`로 검사하므로
  이긴 쪽은 자기 차례가 `읽은 값 + 1`임을 안다(`OutboxClaim.attempt`). 그 뒤의 모든 쓰기 —
  발송 직전 lease 갱신 `renewClaim`, rate limit 유예 `deferClaim`, 종결 기록 `completeClaim`, 스윕의
  `abandonStuck` — 가 같은 attempt를 조건으로 건다. 그래서 executor 큐에서 stuck 임계를 넘겨 기다린 작업은 이미 회수된 행을 보내지도, 새 소유자가
  쓴 `SUCCESS`를 `FAILURE`로 덮지도 못한다. `@Version`은 엔티티에 남아 있지만 JPA로 기존 행을 저장하는 경로가
  없어져(구 `updateMessage` 제거) 쓰이지 않는다.
- **`updated_at`은 호출자가 넘긴 `now`로만 쓴다(`CURRENT_TIMESTAMP` 금지).** 스윕·보존·헬스의 컷오프는 앱의
  `Clock` 빈(JVM 존, `Asia/Seoul`)으로 계산하고 `@CreationTimestamp`/`@UpdateTimestamp`도 JVM 시계인데,
  `CURRENT_TIMESTAMP`는 DB 세션 존(배포 매니페스트상 UTC)으로 평가된다. 섞으면 방금 claim한 행이 9시간
  묵은 것으로 보여 발송 중에 회수·재발송됐다(review 13장 S2). H2 테스트는 같은 JVM이라 이 차이를 못 본다.
- **재시도 예산은 `send_count`(V22)로 센다, `attempt_count`가 아니다.** `renewClaim`(발송 직전)만 `+1`하고
  `deferClaim`(rate limit)이 그 1을 되돌린다. 그래서 429를 맞은 발송과 executor 큐에서 기다리다 회수된 claim은
  예산을 쓰지 않는다. 예전 규칙(`attempt_count >= max-attempts`)은 50분 남짓의 연속 429나 느린 executor만으로
  멀쩡한 메시지를 `FAILURE`로 버렸다(review 13장 재검수 M1).
- stuck 복구(`OutboxRecoveryScheduler`, 60초, 두 모드 공통): `updated_at < now - stuck-in-progress-seconds`
  (기본 300초)인 `IN_PROGRESS`를 회수해 재전송하고, 같은 임계보다 오래된 `PENDING`도 claim한다. 재전송은 중복
  게시를 감수한 선택이다(아래 보장 절). 단 `send_count >= slack.app.outbox.polling.max-sends`(기본 10)이거나
  `created_at`이 `give-up-after-hours`(24)를 넘긴 행은 `FAILURE`로 포기한다 — 예전엔 reclaim이 `updated_at`을
  리셋해 poison 행이 300초마다 24시간(≈288회) 재발송됐다. 24시간 상한은 rate limit 행에도 걸리는 최종 정지선이다.
  stale `PENDING`도 24시간을 넘겼으면 claim 없이 `abandonPending`으로 `FAILURE`가 되고(릴레이 자리가 없어도),
  `dispatchClaimed`도 `renewClaim` 전에 같은 상한을 검사해 폴러나 장애 뒤 재생된 CDC 백로그가 만료된 행을 보내지 않는다.
- rate limit 유예는 `updated_at = now + 대기 - stuck 임계`로 쓴다. 스윕의 나이 조건이 그대로 `Retry-After`를
  기다리게 하는 셈이다. 대기 = `Retry-After`(없으면 60초) + 분산값(`eventId` 해시를 2분으로 나눈 나머지)이라,
  한꺼번에 429를 맞은 CVE 알림·standup 팬아웃이 같은 스윕에 몰려 다시 429를 맞지 않는다. 몇 시간짜리
  `Retry-After`도 그대로 지키되, 재개 시각은 `created_at + give-up-after-hours`를 넘지 않게 자른다.
- Slack `error` 코드 분류는 `chat.*`와 `response_url` 응답이 하나의 함수(`raiseIfRetryable`)를 공유한다:
  `ratelimited` → rate limit, `internal_error`/`service_unavailable` → 일시 오류, 그 밖 → 영구 실패.

## 릴레이 모드와 이벤트 퍼블리셔

| 키 (`slack.app.mode.*`) | 값 | 조건 클래스 → 설정 |
|---|---|---|
| `outbox-reading-strategy` | `polling`(코드 기본) / `cdc` | `OnPollingConsumer` → `PoolingPublisherConfig`, `OnCdcConsumer` → `CdcPublisherConfig` + `CdcConsumerConfiguration` |
| `event-publisher` | `application_event`(코드 기본) / `kafka` | `OnApplicationEventPublisher` → `AppEventPublisher`, `OnKafkaEventPublisher` → `KafkaEventPublisher` + 프로듀서 |
| `cdc.topic` | Debezium 토픽명 | `DebeziumLogTailingProcessor`의 `@KafkaListener(topics = ...)` |

- 프로파일: `local`/`dev`/`prod`는 `cdc` + `kafka`, 토픽 `cdc.code_companion.outbox_message`(prod는
  `${SLACK_CDC_TOPIC}`). `slack-live`는 `polling` + `application_event`로 Kafka/Debezium 없이 전체 릴레이가 in-process다.
- **POLLING** — `PollingMessageProcessor`, `@Scheduled(fixedRate = 5000)`(하드코딩). tick당 `PENDING`
  `batch-size`(기본 100)건 읽기 → 행마다 `claimPending` → 이긴 claim만 `batchPendingMessages`, 내부 루프 없음(스케줄러
  스레드 독점 방지). 복구는 여기 없고 `OutboxRecoveryScheduler`가 한다. `SlackMessageRelayServiceImpl.batchPendingMessages`는
  `@Async` 대신 `@Qualifier("relayTaskExecutor")`(4스레드, 큐 = batch-size, `AbortPolicy`, EntityManagerFactory보다 먼저 파괴)에 직접 submit 한다
  (같은 빈 내부 self-invocation은 AOP 프록시를 타지 않음). 폴러와 스윕은 스케줄러 스레드를 다른 잡과 나눠 쓰므로 넘친
  작업을 제출 스레드에서 돌리지 않는다: 둘 다 `claimWithReservedSlots`로 relay 자리(큐 크기만큼, 풀 스레드가 작업을
  시작하면 반납)를 먼저 원자적으로 예약하고 그만큼만 claim한다. 그래도 거절된 claim은 발송 없이 `IN_PROGRESS`로 남아
  스윕이 회수한다. 종료가 시작되면 릴레이(`SmartLifecycle`, 가장 먼저 멈춤)가 큐에 남은 claim을 비우고 새 발송을 막는다:
  그 claim들과 이후 들어온 claim은 발송 없이 `IN_PROGRESS`로 남아 다른 파드의 스윕이 회수하고, 실행 중인 디스패치만 executor
  종료 대기가 기다린다. 큐에서 오래 기다린 작업의 안전은 큐 크기가 아니라 위의
  `renewClaim` 검사가 보장한다.
- **CDC** — Debezium MariaDB 커넥터(`table.include.list: code_companion.outbox_message`, `topic.prefix: cdc`;
  `cdc/docker-compose/debezium/connect_mariadb.sh`) → Kafka → `DebeziumLogTailingProcessor`. 리스너는
  `spring.json.use.type.headers:false` + 기본 타입 `Envelope`로 역직렬화하고, `payload.after.status == PENDING`인
  레코드만 후보로 본다(`IN_PROGRESS`/`SUCCESS` 갱신 레코드와 `after == null`인 삭제, tombstone은 무시). 그 뒤
  **DB의 현재 상태를 다시 읽어** `PENDING`일 때만 `claimPending`으로 잡고 리스너 스레드에서 `dispatchClaimed`를
  호출한다. `IN_PROGRESS`는 **건너뛴다** — 다른 소유자가 발송 중이거나 복구 스윕이 맡은 stuck 행이기 때문이다
  (2026-09-22 재설계의 "IN_PROGRESS면 재발송" 분기는 스윕과 경합해 중복 발송해서 2026-09-28 제거, review 13장 S3).
  `SUCCESS`/`FAILURE`는 재전달로 보고 건너뛴다. 상태 기록(`completeClaim`)이 재시도 끝에 실패해도 예외는 리스너 밖으로
  나가지 않는다: ERROR 로그를 남기고 행을 `IN_PROGRESS`로 둬 스윕에 맡긴다. 예외가 나가면 `DefaultErrorHandler`가
  이미 보낸 레코드를 재전달하기 때문이다(S4).
  파싱 실패·잘못된 `eventId`는 `CdcRecordParseException`으로 던져 재시도 없이 dead-letter 토픽으로 보낸다. 토픽
  이름은 코드에서 명시한 **`<cdc topic>-dlt`**(spring-kafka 4.1.1의 기본 접미사와 같지만 기본값에 기대지 않는다;
  예전 문서의 `.DLT`는 틀렸다), 파티션은 지정하지 않는다. 역직렬화에 실패한 레코드는 `ByteArraySerializer` 템플릿으로
  **원본 바이트 그대로** 실리고(JSON 템플릿으로 보내면 Base64 문자열이 되어 replay 불가), 역직렬화된 `Envelope`는 JSON
  템플릿으로 간다. `NewTopic` 빈이 기동 시 `<cdc topic>-dlt`를 만들되(브로커 기본 파티션·복제), 생성 권한이 없는
  환경에서는 수동으로 만들어야 한다. dead-letter 전송이 실패해도(토픽 없음 등) `failIfSendResultIsError = false`라
  레코드는 복구된 것으로 처리되어 파티션이 막히지 않는다 — 행은 `PENDING`으로 남아 스윕이 구한다.
  `KafkaTemplate`이 없는 조합에서는 ERROR 로그로 강등. `ErrorHandlingDeserializer` 래핑은 그대로다.
- `SchedulingConfig`가 `@EnableScheduling`을 무조건 켠다. 과거엔 `PoolingPublisherConfig`에만 있어 CDC 모드에서
  `StandupScheduler` 등 모든 `@Scheduled`가 조용히 no-op이었다.
- `KafkaEventPublisher`는 `isInternal == false`인 이벤트만 Kafka(`event.destination` 토픽, key = `idempotencyKey`,
  5초 동기 대기)로 보내는데, 현재 모든 `CommandEvent`가 `isInternal = true`라 이 경로는 휴면이다. `ErrorBroadcaster`는
  모드와 무관하게 `StdoutErrorBroadcaster` 하나뿐이다(구 `KafkaErrorBroadcaster`는 삭제됨).

## 멱등성

- **인바운드 키**: `IdempotencyCreator.create(data, now)` = `UUID.nameUUIDFromBytes("$data|${now / 1000}")`.
  같은 입력이 같은 **1초 창** 안에 오면 같은 키가 된다(`IdempotencyData`는 SHA-256으로 직렬화). 창을 넘긴
  재시도는 다른 키이며, `idempotency_key`가 유니크가 아니므로 아웃박스가 중복을 막아 주지도 않는다.
- **Slack 재시도**: `SlackRequestVerificationFilter`는 `/api/slack/events` 경로에만 `InMemorySlackRetryDeduplicator`를
  적용한다. fingerprint는 (method, 요청 경로, 본문 SHA-256 해시)이고, 항목은 in-flight → completed 상태를 가진다.
  `X-Slack-Retry-Num`이 붙은 재시도가 처리 중인 원본과 같으면 503(Slack이 다시 시도하게), 이미 완료된 원본과 같으면
  본문 처리 없이 200을 돌려준다. 원본 처리가 실패하면 항목을 지워(forget) 다음 재시도가 새로 처리된다.
  TTL 10분, in-memory이므로 **인스턴스별**이다.
- **소비자 측**: CDC는 상태 필터(`PENDING`만), 폴링은 `claimPending` CAS. 상태 갱신 이벤트는 항상 **row의**
  `event_id`로 키를 잡는다 — 렌더러가 새로 발급하는 payload `eventId`는 버린다. Slack API에는 멱등 키가 없으므로
  재전송은 그대로 중복 게시가 된다. 그래서 `chat.postMessage`·`chat.postEphemeral`·`response_url`은 요청이 쓰이기
  전에 실패한 경우(OkHttp `EventListener`로 판정)와 `service_unavailable`·HTTP 503만 재시도하고, 쓰인 뒤의 I/O
  실패·`internal_error`·그 밖의 5xx는 `outcome_unknown`으로 `FAILURE`를 남긴다. OkHttp 자체 재전송
  (`retryOnConnectionFailure`)도 끈다(2026-10-01). 멱등인 `chat.update`만 모든 일시 오류를 재시도한다.

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
- 아웃박스 자체의 다중 인스턴스 안전은 같은 claim-token 패턴이다. 토큰이 별도 컬럼 대신 `attempt_count`이고,
  claim·reclaim·renew·defer·complete·abandon이 모두 그 값을 검사한다(위 "아웃박스 행" 절).
- **배포 제약: V20/V22를 쓰는 릴리스는 그 이전 릴리스와 나란히 돌리면 안 된다.** 구 파드는 `updated_at`을
  `CURRENT_TIMESTAMP`(UTC)로 쓰고, CDC로 `IN_PROGRESS` 행을 재발송하며, JPA로 attempt 조건 없이 상태를 덮는다.
  섞이면 새 파드의 스윕이 구 파드가 발송 중인 행을 9시간 묵은 행으로 보고 다시 보내고, `SUCCESS`가 `FAILURE`로
  바뀔 수 있다. 구 파드를 모두 내리고(0으로 스케일 또는 `Recreate`) V20·V22를 적용한 뒤 새 릴리스를 올린다.

## 순서·파티션·보장

- Debezium 커넥터 설정에 `message.key.columns`가 없으므로 레코드 키는 기본값인 PK(`event_id`)다. 즉 채널이나
  커맨드 단위 순서는 파티션 간에 보장되지 않는다. 토픽 파티션 수는 저장소에 없다(미확인).
- 폴링은 `created_at ASC`로 읽지만 dispatch는 executor 병렬이라 배치 안에서도 순서가 없다.
  `PartitionKeyUtil`(6 버킷)은 테스트 외 호출자가 없다.
- `spring.kafka.consumer.enable-auto-commit: false` + 컨테이너 `AckMode.RECORD`(2026-09-22)라 오프셋은 리스너가
  그 레코드를 반환한 뒤에만 커밋된다. 크래시 시 재전달되며, 위의 현재 상태 확인이 중복 발송을 막는다(claim과
  상태 기록 사이에 크래시하면 행은 `IN_PROGRESS`로 남고 스윕이 재발송 — at-least-once). CDC 프로파일의
  `max-poll-records`·`max.poll.interval.ms`는 프로파일 YAML이 정한다.
- 정직한 보장: README의 "exactly-once-style"은 지향 표현이다. 실제는 **at-least-once**(폴링 stuck 재전송, Kafka
  재전달) + **멱등 소비자**(상태 필터, `event_id` 기준 상태 갱신)이며, Slack 채널에는 중복 게시가 가능하다.

## 헬스와 `@bot status`

- `OutboxHealthIndicator`: `slack.app.outbox.health.stuck-threshold-seconds`(기본 300)보다 오래된 `PENDING`
  (`created_at` 기준), 그 임계에 스윕 주기(60초)를 더한 것보다 오래된 `IN_PROGRESS`(`updated_at` 기준), 또는
  `send_count`가 `slack.app.outbox.health.retrying-send-threshold`(기본 3) 이상인 `IN_PROGRESS`가 있으면 DOWN.
  `PENDING`-stuck은 폴러 지연/정지, `IN_PROGRESS`-stuck은 스윕이 한 주기 안에 가져가지 못한 행, retrying은 실제
  발송이 거듭 실패하는 행을 가리킨다. rate limit만 맞은 행은 유예 중이고 `send_count`도 돌려받으므로 DOWN을 만들지
  않는다. 디테일 키: `pendingCount`, `stuckPendingCount`, `stuckCount`(구 별칭), `oldestPendingAgeSeconds`,
  `inFlightCount`, `stuckInFlightCount`, `oldestInFlightAgeSeconds`, `stuckThresholdSeconds`, `retryingCount`,
  `retryingSendThreshold`, `accessBlocked`, `lastAccessBlockedAt`, `accessBlockedWindowSeconds`.
- 접근 차단으로 보류된 행은 어떤 카운트에도 잡히지 않는다(`deferClaim`이 발송을 돌려받고 `updated_at`을 stuck 임계 너머로
  옮긴다). 그래서 릴레이가 보류할 때마다 `AccessBlockedTracker`(메모리, 레플리카별)에 시각을 남기고, 마지막 보류가
  `slack.app.outbox.health.access-blocked-window-seconds`(기본 1200, 15분 대기 + 2분 분산 + 스윕 한 주기보다 길게)
  안이면 DOWN이다. 재시작한 레플리카는 첫 보류 전까지 UP이다. 게이지 `outbox_access_blocked`(0/1)와 `@bot status`의
  "*Slack access blocked:*" 줄이 같은 값을 보인다. 이 인디케이터는 readiness 그룹에 없으므로 배포 게이트를 막지 않는다.
- 헬스 인디케이터, Prometheus 게이지, `OpsStatusService.renderReport`(`@bot status` 답장과 MCP `get_status`)가 모두
  `readOutboxHealth`가 만든 `OutboxHealthSnapshot` 하나를 읽는다. 판정(stuck PENDING, 임계 + 스윕 주기를 넘긴 in-flight,
  retrying 행)도 한 곳에만 있어 채팅과 actuator가 다른 결론을 내지 않는다. 게이지 여섯 개는 한 스크레이프에서 스냅샷
  하나를 함께 쓴다(1초 재사용).
- 미지원 `schema_version` 행은 `IN_PROGRESS`로 남지만 헬스에는 잡히지 않는다: 회수할 때마다 `updated_at`이 갱신되고
  `send_count`는 0이다. ERROR 로그(`not in [...]; leaving it IN_PROGRESS unsent`)로 본다.

## 메트릭과 알림 (2026-10-01)

- 헬스 디테일은 `show-details: when_authorized`인데 Spring Security가 없어 아무에게도 보이지 않는다. 그래서 같은
  숫자를 `health/OutboxMetrics`가 게이지로 내보내고 `dev`·`prod`가 `/actuator/prometheus`를 연다(공개 라우트에는
  `/actuator`가 없다). 게이지는 스크레이프마다 같은 저장소 쿼리를 실행하고, 쿼리가 실패하면 `NaN`이 된다.
  - `outbox_messages{status="pending"|"in_progress"}`, `outbox_pending_oldest_age_seconds`(`created_at` 기준),
    `outbox_in_progress_oldest_claim_age_seconds`(`updated_at` = 마지막 claim·갱신 기준), `outbox_retrying_messages`
  - `kafka_dead_letter_handoffs_total{topic}`: 리스너 컨테이너의 recoverer가 레코드를 DLT 발행기에 **넘긴** 수다.
    `setFailIfSendResultIsError(false)`라 DLT 전송 실패(토픽 없음, ACL 거부)는 여기서는 성공처럼 세어지고, 대신
  - `kafka_dead_letter_publish_failures_total{topic}`: DLT 전송이 동기 예외로 실패하거나 나중에 실패한 수(ERROR 로그 함께).
    핸드오프에서 이 수를 빼면 실제로 DLT에 남은 레코드다. DLT 전용 producer 둘(JSON·bytes, 앱 템플릿의 설정에서 파생)은
    `max.block.ms` 5초(`DEAD_LETTER_MAX_BLOCK`)라 토픽 메타데이터를 못 얻어도 리스너 스레드가 레코드마다 60초씩 막히지 않는다. `KafkaTemplate`이 없으면 DLT로 보낼 길이 없으므로 기동이 실패한다(2026-10-01, 이전에는
    로그만 남기고 버렸다).
- 모든 레플리카가 같은 테이블을 세므로 알림은 `max()`로 건다. 권장(저장소에 프로비저닝되어 있지 않음):
  `max(outbox_pending_oldest_age_seconds) > 120` 10분 지속(커넥터 또는 폴러 정지 — 이때 전달은 stuck 임계 뒤
  스윕이 맡아 조용히 늦어진다), `max(outbox_retrying_messages) > 0` 15분, `increase(kafka_dead_letter_handoffs_total[15m]) > 0`, `increase(kafka_dead_letter_publish_failures_total[15m]) > 0`.
- Debezium 커넥터 상태(`/connectors/<name>/status`)는 앱이 볼 수 없다. 커넥터 등록 스크립트에
  `heartbeat.interval.ms: 10000`을 넣어, outbox 테이블이 조용해도 오프셋이 binlog 보존(7일) 밖으로 밀려나지 않게
  했다. 위치를 이미 잃었을 때의 복구 절차는 `application/src/main/resources/cdc/docker-compose/debezium/AGENTS.md`의
  런북(`snapshot.mode: when_needed`로 재스냅샷, 스냅샷 읽기 이벤트는 상태 필터와 CAS를 그대로 거친다)에 있다.

## 함정

- `OutboundMessage.DirectMessage`는 타입만 남아 있다: 코덱 미등록, 렌더러 `error(...)`. DM은
  `ChannelMessage`/`Approval` + `channel = userId`로 쓴다.
- BEFORE_COMMIT 리스너는 트랜잭션 밖 publish를 조용히 버린다(위 1번 경로). `stage()`가 null을 돌려줄 수 있는
  계약이라 `AgentConverseService.stageReply`처럼 `checkNotNull`로 응답 유실을 트랜잭션 실패로 바꾼다.
- `PollingMessageProcessor.claimAndDispatch`는 행마다 claim해 이긴 행만 dispatch 한다(2026-09-22; 이전의
  `candidates.take(claimedCount)`는 다중 폴러에서 남의 행을 보냈다). 그러나 행 단위 claim만으로는 부족했다:
  100건을 먼저 claim하고 4스레드 executor에 넣으면 뒤쪽 작업이 stuck 임계를 넘겨 스윕에 회수되고 원래 작업도
  실행돼 두 번 나갔다(review 13장 Codex #3). 그래서 실행 직전 `renewClaim`이 attempt로 소유권을 확인한다.
- CDC 리스너가 재시도(1초 간격 2회)를 넘겨 실패한 레코드 중 DLT로 가는 것은 읽을 수 없는 레코드뿐이다(`CdcRecordParseException`,
  `DeserializationException`, `MessageConversionException`). DB 장애 같은 나머지는 로그를 남기고 ack 하며 행은 스윕이
  보낸다(2026-10-02, 이전에는 정상 레코드가 2초 만에 DLT로 섞여 들어갔다).
- 상태 기록 실패·429·일시 오류·DLT로 남은 `IN_PROGRESS`/오래된 `PENDING`은 모드와 무관한
  `OutboxRecoveryScheduler`가 재발송한다 — CDC는 그런 행에 두 번째 변경 이벤트를 만들지 않기 때문.
  `renewClaim`/`deferClaim`/`completeClaim`도 CDC UPDATE 이벤트를 만들지만 `PENDING`이 아니라서 리스너가 무시한다
  (CDC 경로는 claim 직후 `renewClaim`을 한 번 더 써서 메시지당 UPDATE 레코드가 하나 더 생긴다. 이 갱신이
  `send_count`를 세므로 남겨 둔다).
- 결과 이벤트(`OutboxUpdateEvent`)는 `completeClaim`이 1을 돌려준 소유자만 발행한다. 0이면 다른 소유자가 행을
  가져간 것이고 그쪽이 자기 결과를 발행한다. 유일한 리스너는 `StandupSummaryService`(성공 시 `messageTs` 기록)다.
  리스너 예외는 상태 기록 실패와 별도로 로그한다 — 행은 이미 종결 상태다.
- 잘못된 `eventId`는 결정적 오류라 폴링·스윕 경로에서 곧바로 `completeClaim(FAILURE)`한다(CDC는 DLT).
- 구 shape 로컬 행은 디코드에 실패하고 `ddl-auto: update`는 옛 컬럼을 안 지운다 → `outbox_message` DROP 후 재생성.

## 근거

- `infrastructure/src/main/kotlin/dev/notypie/repository/outbox/` 전체 (repository, codec, port, `schema/*`)
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/` (`SlackOutboundStager`, `OutboundRenderer`,
  `SlackViewOpenDispatcher`, `ApplicationMessageDispatcher`, `KafkaEventPublisher`, `AppEventPublisher`, `event/*`),
  `impl/retry/RetryService.kt`, `exception/StdoutErrorBroadcaster.kt`
- `infrastructure/src/main/kotlin/dev/notypie/repository/{standup,meeting,cve}/Jpa*Repository.kt` (CAS·ledger·stamp)
- `domain/src/main/kotlin/dev/notypie/domain/command/` — `outbound/{OutboundMessage,OutboundMessageStager}.kt`,
  `entity/event/{EventPublisher,Event}.kt`, `dto/CommandBasicInfo.kt`
- `application/src/main/kotlin/dev/notypie/application/` — `service/relay/*`, `configurations/{ConsumerConfig,
  SchedulingConfig,KafkaConsumerConfiguration,AsyncConfig,AppConfig,SlackRequestBuilderConfiguration}.kt`,
  `configurations/conditions/Conditions.kt`, `health/{OutboxHealthIndicator,OutboxMetrics}.kt`, `service/ops/OpsStatusService.kt`,
  `common/{IdempotencyCreator,TransactionTemplateExt}.kt`, `security/{SlackRetryDeduplicator,
  SlackRequestVerificationFilter}.kt`, `service/command/{CommandExecutor,RoleManagementService}.kt`,
  `service/{standup/StandupSchedulingService,cve/notification/CveNotificationDispatcher,agent/AgentConverseService}.kt`
- `application/src/main/resources/application*.yaml`, `resources/cdc/docker-compose/` (README, `connect_mariadb.sh`),
  `resources/db/migration/V1__outbox_pk_event_id.sql`, `V11__outbox_transport_neutral_envelope.sql`,
  `V20__add_outbox_attempt_count.sql`, `V22__add_outbox_send_count.sql`; `review.md` 13장(S2·S3·S4·S9·S17, Codex #3);
  `infrastructure/src/main/kotlin/dev/notypie/impl/command/AGENTS.md`(dispatch 시간 상한 산식);
  Slack SDK 1.51.0 소스 `SlackHttpClient.buildOkHttpClient`, `MethodsClientImpl`(stats 경로의 `Long.valueOf`,
  `TeamIdCache`); spring-web 7.0.9 `JdkClientHttpRequest`(read timeout이 본문까지 적용)
- `README.md`(Event-Driven Architecture), `infrastructure/AGENTS.md`, `application/AGENTS.md`,
  `application/src/main/resources/AGENTS.md`; git 미추적 근거 문서 `Handoff.md`(Phase 2, Codex 리뷰 표), `Refactor.md`(8b·9)

## 관련 페이지

- [architecture-overview.md](architecture-overview.md) · [command-pipeline.md](command-pipeline.md) — 상류 흐름
- [ddd-layering.md](ddd-layering.md) — 도메인이 Jackson·Slack 타입을 모르는 채로 아웃박스에 실리는 이유
- [dev-environment.md](dev-environment.md) — 프로파일별 릴레이 모드, CDC 스택, `V*` 마이그레이션 관례
- [testing-guide.md](testing-guide.md) — `OutboxTestFixtures`, `EmbeddedKafka` 슬라이스
- [decisions.md](decisions.md) · [history.md](history.md)
- [`../../infrastructure/AGENTS.md`](../../infrastructure/AGENTS.md) · [`../../application/AGENTS.md`](../../application/AGENTS.md)
