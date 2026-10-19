# 에러 처리와 검증

_type: pattern · updated: 2026-10-19_

> `ErrorCode` · `exceptionDetails {}` · `validate {}`로 구조화된 예외를 만들고, 계층별 예외 소유권과 에러가 사용자에게
> 닿는 세 갈래(ephemeral / HTTP 상태 / Socket Mode 로그)를 정리한다. 알려진 공백은 마지막 절에 그대로 적었다.

## 에러 계약: `domain/common/error/Errors.kt`

- `ErrorCode { message: String }`가 유일한 공개 계약이다(HTTP 상태 코드는 담지 않는다). 소유자마다 자기 enum을 둔다:
  `CommonErrorCode`(domain, `internal`, `VALIDATION_FAILED` 하나), `command/exceptions/CommandErrorCode`(domain,
  `internal`, `SUBCOMMAND_NOT_VALID` / `SUBCOMMAND_NOT_FOUND` / `UNSUPPORTED_COMMAND_TYPE` 3종),
  `application/exception/PayloadParseErrorCode`(3종), `infrastructure/exception/meeting/JpaErrorCode`(`TABLE_NOT_FOUND`).
- `ExceptionArgument(fieldName, value, reason = "")`가 세부 항목이고, `CodeCompanionRuntimeException(errorCode, details)`가
  모든 예외의 추상 베이스다. `errorCode`와 `details`는 공개 프로퍼티이고 `RuntimeException.message = errorCode.message`다.
  어느 응답 경로도 `errorCode`로 상태 코드를 고르지 않는다 — 상태는 `ControllerAdvice`의 핸들러가 예외 타입별로 정한다.
- `details`는 `exceptionDetails { "field" value actual because "reason" }` DSL로만 만든다.
  `ExceptionDetailsBuilder.details`가 `internal`이라 다른 모듈은 이 DSL 외에 채울 길이 없다. 리터럴
  `ExceptionArgument(...)` 나열은 domain과 infrastructure의 `DatabaseException` 헬퍼(`throwIfSchemaNotFound`,
  `NotFoundExceptionBuilder`)에서만 보인다.
- **왜 문자열이 아니라 구조인가**: `RequestMeetingContext.meetingValidationMessage`가 `details`를
  `"fieldName: reason"` 줄로 이어 사용자 ephemeral로 렌더링하고, 핸들러는 `rawCommandType` 같은 원값을 로그에 남긴다.
  `reason` 문구는 계약이 아니다(테스트는 `fieldName`/`value`만 단언한다).

## 검증 DSL: `validate {}`

- 모든 애그리거트가 `init`에서 `validate(className = this.javaClass.simpleName) { ... }`를 부른다: `Meeting`,
  `MeetingReminder`, `Routine`, `RoutineMember`, `StandupSession`, `SessionDispatch`, `StandupAnswer`.
- **누적 후 일괄 throw.** 연산자는 `errors`에 쌓기만 하고 블록 끝의 `ValidationBuilder.validate(className)`이 한 번 던진다.
  `className`이 비어 있지 않으면 `ValidationExceptionWithName(className, ...)`, 아니면 `ValidationException`. 둘 다
  `internal`이고 `CommonErrorCode.VALIDATION_FAILED`를 담으므로 모듈 밖에서는 `CodeCompanionRuntimeException`으로 잡는다.
- 체이닝 의미: `and { }`는 무조건 실행, `or { }`는 실행 후 **그 블록이 추가한 오류를 되돌림**, `shouldNotBeNullAnd { }`는
  null이면 "must not be null"을 기록하고 블록을 건너뜀, `ifNotNull { }`은 조용히 건너뜀.
- 경계가 타입마다 다르다: `shouldBeShorterThan(max)`는 `length > max`에서만 실패(같으면 통과)지만
  `shouldBeLessThan(max)`는 `value >= max`에서 실패한다. `Meeting.MAX_TITLE_LENGTH = 20`은 20자 제목을 허용한다.
- `validateAndReturn {}`는 `internal`이고 프로덕션 호출자가 없다. `ValidationBuilderTest`가 오류 목록을 세기 위해
  쓰는 테스트 전용 진입점이니 도메인 코드에서는 `validate {}`만 쓴다.
- 문구 함정: `notBlank`의 reason은 `Field` 객체 전체를 문자열 보간하고, `shouldBeNegative`는 "must be positive"라고
  말한다. 스펙에서 `reason`을 단언하지 않는 이유다.

## 계층별 예외 소유권

| 계층 | 타입 | 던지는 곳 | 왜 여기인가 |
|------|------|-----------|-------------|
| domain | `ValidationException*`, `internal sealed CommandException` → `SubCommandParseException`, `UnSupportedCommandException` | 애그리거트 `init`; 커맨드 파싱(throw site 목록은 [`command/exceptions/AGENTS.md`](../../domain/src/main/kotlin/dev/notypie/domain/command/exceptions/AGENTS.md)) | 불변식과 커맨드 파싱은 전송과 무관한 규칙이다. `internal`이라 바깥에서는 타입으로 잡을 수 없고 베이스로만 잡는다 |
| application | `AppIdNotFoundException`, `UnsupportedSlackCommandTypeException(rawCommandType)`, `InvalidEventPayloadException`(cause 보존) | `SlackMentionEventHandlerImpl.resolveAppId` / `resolveCommandType` / `convertBodyData` 세 곳뿐 | 오케스트레이션(페이로드 → 커맨드) 실패만 여기. 새 타입은 베이스 상속 + enum 값 + `ControllerAdvice` 핸들러를 **같은 변경**에 넣는다 |
| infrastructure | `DatabaseException(tableName)` + `JpaErrorCode.TABLE_NOT_FOUND`, `throwIfSchemaNotFound`, `schemaNotFound {}` | `MeetingRepositoryImpl.getMeeting`, `StandupRepositoryImpl.getRoutine` | JPA 관심사라 여기 두되 `:application`의 `ControllerAdvice`가 import한다 |

- `throwIfSchemaNotFound`는 `T?`에 대한 `inline reified` 확장이라 호출 뒤 값이 non-null로 남는다(null 경계 조기 해소
  규칙과 맞물린다). 단 `tableName`은 **수신자의 정적 타입**이라 두 호출자 모두 매핑 뒤에 부르므로 `MeetingDto`/`RoutineDto`로
  찍힌다. 엔티티 이름을 원하면 스키마 객체에서 불러야 한다. `schemaNotFound {}` DSL은 현재 호출자가 없다.
- `ErrorBroadcaster` 포트의 구현은 `StdoutErrorBroadcaster`(`logger.error`) 하나이고, `ConsumerConfig.kt`의
  `ErrorBroadcasterConfig`가 모드와 무관하게 빈으로 등록하지만 **아무도 주입하지 않는다.** `TODO()` 스텁이던
  `KafkaErrorBroadcaster`는 `f9971700`(#26)에서 삭제됐다. Kafka 구현을 다시 만들면 실제 에러 토픽이 필요하고, 에러 경로에서 불리므로 던지면 안 된다.

## 에러가 사용자에게 닿는 길

1. **도메인 컨텍스트 → ephemeral.** `CommandContext.createErrorResponse(errMessage)`는 `OutboundMessage.Ephemeral`
   (`target = commandBasicInfo.channel`, `recipient = null`, `MessageContent.Text(markdown = errMessage)`)을 큐에 넣고
   `CommandOutput.fail(reason)`을 돌려준다. `results` 오버로드는 이미 만들어진 출력(예: 회의는 생성됐지만 공지 실패)을
   그대로 반환하면서 ephemeral만 추가한다. `CommandExecutor.execute`가 **성공 여부와 무관하게** `drainIntents()`를
   발행하므로 에러 ephemeral은 항상 Slack에 도달한다. `RequestMeetingContext`는 `formInput.toMeeting()`을
   `catch (exception: CodeCompanionRuntimeException)`으로 감싸 `details`를 이 경로로 보낸다.
2. **컨텍스트 안의 예외 → `ERROR_RESPONSE` + WARN (슬래시면 ephemeral도).** `Command.handleEvent()`는
   `catch (exception: Exception)`으로 예외를 `CommandOutput.fail(commandDetailType = ERROR_RESPONSE, reason =
   exception.toString())`로 바꾸고(`InterruptedException`은 플래그 복원 후 재throw, `Error`는 전파), `CommandExecutor`가 이 출력을 명령 클래스·`commandId`·`idempotencyKey`·`kind`·`actorId`·
   사유와 함께 WARN으로 남긴다(토큰은 남기지 않음). 2026-10-08부터 슬래시 명령이면 `handleEvent()`가 요청자 ephemeral을
   intent 큐에 넣는다(2026-10-15부터는 그 전에 컨텍스트가 쌓은 효과를 버린다 — 결정 #44):
   `SubCommandParseException`은 "Unknown subcommand `토큰`." + 명령의 usage 줄(또는 그 서브커맨드의
   usage), 그 밖은 "Something went wrong handling `/명령`. Please try again.". 인터랙션(`SlackInteractionHandlerImpl`은
   `result.takeIf { it.ok }`)과 멘션은 여전히 사용자에게 답이 없다(멘션은 HTTP 컨트롤러도 WARN을 남김). 무엇이 틀렸는지
   알려야 할 실패는 예외를 던지지 말고 `createErrorResponse`로 만든다.
3. **HTTP(`/api/slack/**`, `/api/slash/**`) → `ControllerAdvice`.** 아래 직접 구현한 핸들러의 본문은 `{"error": "<고정 마커>"}`이고
   원문을 되돌려 보내지 않는다(상속한 Spring MVC 핸들러의 본문은 `ProblemDetail`이다).
   `UnsupportedSlackCommandTypeException`은 WARN(제어문자 `?` 치환) + 400 `unsupported_command_type` + `X-Slack-No-Retry: 1`.
   `AppIdNotFoundException` / `InvalidEventPayloadException`은 WARN + 400 `invalid_payload` + 같은 no-retry 헤더.
   `DatabaseException`은 ERROR + 500 `internal_error`(재시도 허용). 그 밖의 `Exception`은 `handleUnexpected`가 ERROR + 500
   `internal_error`로 받고, `ResponseEntityExceptionHandler`가 처리하는 Spring MVC 예외는 자기 상태를 유지한다.
   `app_mention`이 아닌 이벤트는 핸들러를 타지 않고 200으로 ack해 Slack 재시도를 막는다. `handleInteractions`는
   `response_action` 본문이 있으면 JSON 200, 아니면 빈 200.
4. **Socket Mode → 로그(인터랙션은 ack 생략).** `SocketModeReceiver.handleSlash`/`handleEvent`는 `containFailure` + `log.error`이고
   `handleInteractive`는 `catch (Exception)` + `log.error` 뒤 ack 없이 돌아온다. MVC 디스패치가 없으니 `ControllerAdvice`는
   적용되지 않고, slash/event는 ack를 먼저 보내므로 실패가 Slack에 전달될 방법이 없다. interactive만 handle → ack 순서라
   `response_action`(인라인 검증 오류)을 ack에 실을 수 있다. 세 경로 모두 `Error`는 전파한다 — 엔벨로프 리스너의
   `logErrorThenRethrow`가 envelopeId와 함께 ERROR로 남긴 뒤 다시 던지고, Slack SDK는 그 메시지 루프(10개 중 하나)를 조용히
   끝낸다.
5. **서비스 내부 관례.** `TransactionTemplate.runInTx`는 `Exception`을 `Result.failure`로 바꾸고 `setRollbackOnly`만 한다 —
   throw 여부는 호출자가 정한다(`InterruptedException`은 플래그를 복원한 뒤 실패로 돌려주고, `Error`는 롤백된 뒤 전파). `StandupSchedulingService`/`MeetingReminderSchedulingService`는
   `DataIntegrityViolationException`을 "예상된 레이스"로 잡되 **행이 실제로 존재하는지 확인한 뒤에만** 삼킨다.
   스탠드업 스케줄러는 루틴·세션·단계 단위로 `containFailure`(`Exception`만 잡아 로그, `InterruptedException`은
   플래그를 복원해 다시 던지고 `Error`는 전파)로 격리해 한 루틴의 나쁜 데이터가 다른 루틴과 이후 단계를 막지 않게 한다.
   `CveCollector`는 토픽마다 `containFailure`로 격리한다. `CommandExecutor`는 로그 후 재throw하고 인텐트를 재큐잉하지
   않는다 — 재시도는 `idempotencyKey` 아래 상류(아웃박스 릴레이, Kafka, Slack 재전송)가 맡는다.
   `ApplicationMessageDispatcher`는 Slack `ok=false`를 예외가 아니라 `failOutput(reason)`으로 되돌리고 WARN을 남긴다.
   액션이 던진 `Error`는 콜백에서 기록해 두었다가 롤백 자체가 어떤 예외로 실패하더라도(JPA는 번역된 `DataAccessException` 또는
   `TransactionSystemException`) 그 `Error`를 다시 던진다(롤백 실패는 suppressed로, 2026-10-19). relay의 상태 기록(`writeTerminal`)도
   `runInTx`를 거쳐 재시도 정책이 `Error`를 그대로 보게 한다. 다른 raw `TransactionTemplate.execute`(캘린더 store, ops 리포트 등)는
   이 경우 Spring이 ERROR("Application exception overridden by rollback exception")를 남기고 롤백 예외를 던지며, 그것이
   `catch (Exception)`에 잡히는 곳에서는 `Error`가 격하된다 — 수용(이중 장애이고 로그는 남는다).
6. **`Error`는 삼키지 않는다(2026-10-15, 결정 #48).** 경계(스케줄러 틱, 행 단위 루프, 리스너·전송 가장자리, MCP 도구 본문과
   감사 쓰기)는 `infrastructure/.../common/ExceptionBoundary.kt`의 두 헬퍼로 `Exception`만 잡는다: 부수효과 블록은
   `containFailure(onFailure) { }`, `Result`가 필요한 곳은 `runCatchingExceptions { }`. 둘 다 `InterruptedException`은 플래그를
   복원해 다시 던지고 `Error`는 그대로 전파한다. 경계마다 실제로 일어나는 일:
   - **스케줄러 틱:** 그 틱이 끝나고 Spring의 `TaskUtils` 처리기가 ERROR를 남긴 뒤 다음 틱은 그대로 돈다(행 하나의
     `StackOverflowError`도 틱 전체를 끝내고, 롤백된 행은 다음 틱에 다시 앞에 온다). 인터럽트도 모든 틱에서 같다 — 플래그를 단
     채 틱을 떠나고 같은 처리기가 로그를 남긴다(보통 종료 때만).
   - **릴레이 실행기(`relayTaskExecutor`):** 그 작업의 스레드가 죽고 풀이 새 스레드를 만든다. 스레드마다 단
     `RELAY_UNCAUGHT_ERROR_LOGGER`가 스레드 이름과 함께 ERROR를 남긴다(eventId는 없다 — `IN_PROGRESS`에 남은 행이 단서). 행은
     스윕이 다시 보낸다.
   - **CDC 리스너(유일한 예외 조항):** spring-kafka 4.1은 `Error`를 오류 처리기에 넘기지 않고 컨테이너 전체를 멈추며 아무도
     다시 띄우지 않는다. 그래서 `DebeziumLogTailingProcessor.consume`만 `Error`를 잡아 레코드 좌표·eventId와 함께 ERROR로
     남기고 `ListenerErrorException`으로 감싸 다시 던진다. 이 래퍼는 poison·재시도 없음으로 분류돼 DLT로 한 번 가고(파싱 중
     `Error`는 이 변경 전과 같은 결과), 컨테이너는 다음 레코드를 계속 소비한다. 디스패치 중 `Error`였다면 행은 `IN_PROGRESS`로
     남아 스윕이 처리한다.
   - **Socket Mode(로컬 전용):** 위 4번 — 로그 한 줄 뒤 SDK 메시지 루프 하나가 영구히 멈춘다. 열 번이면 앱 재시작이 필요하다.
   - **MCP 도구:** 본문의 `Error`·인터럽트는 Spring AI MCP 서버로 전파되고, 그 전에 `finally`(아무것도 잡지 않음)가
     `FAILED`/`Aborted` 감사 행을 한 줄 남기려 한다 — 최선 노력이다(인터럽트 플래그가 선 채 쓰므로 풀이 비어 기다려야 하면
     HikariCP가 거절한다). 감사 쓰기 자체의 `Error`는 행 없이 전파된다.
   - **`/standup setup` 리스너, `views.open`:** 동기 리스너의 `Error`는 명령 트랜잭션을 롤백하고 HTTP 요청까지 올라가 500이 된다
     (사용자에게 거절 DM·열기 실패 DM이 가지 않는다; 코드 추적, 미실행).

   `Throwable`/`Error`를 이름으로 잡는 곳은 같은 인스턴스를 정리·로그 후 다시 던지는 다섯 곳(`SlackRequestVerificationFilter`,
   `SlackInteractionHandlerImpl`의 수동 트랜잭션, `SlackUserProfileResolver`의 in-flight future,
   `SocketModeReceiver.logErrorThenRethrow`, `runInTx`의 기록 후 재던짐)과 위 CDC 리스너의 번역 한 곳뿐이다. 순수 파싱(enum 조회, JSON 엿보기 등; relay·CDC 리스너의 `UUID.fromString`은
   `IllegalArgumentException`만 잡는다)과 워치독의 `stream.close()` `runCatching`은 그대로 둔다.

## 재시도: `RetryService` / `RetryOptions`

- `RetryService.execute(action, recoveryCallBack?, maxAttempts = 3, initialDelay = 100ms, multiplier = 2.0,
  maxDelay = 10s, jitter = 10ms, exceptions = [Exception])`. 기본값은 `RetryOptions` enum(`internal val default`)에
  있고, 빈은 `RetryConfiguration.retryService()` 하나다.
- **정책마다 템플릿 하나(예전 함정, 해소됨).** `RetryTemplate.retryPolicy`는 바꿀 수 있는 공유 상태라, 예전처럼
  싱글턴 템플릿의 정책을 호출마다 갈아끼우면 다른 스레드의 호출이 그 정책을 본다. 지금 `RetryService`는 정책 키(시도 수·
  지연·배수·상한·jitter·예외 목록)마다 템플릿을 `ConcurrentHashMap`에 하나씩 두고, `RetryServiceTest`가 서로 다른 정책의
  동시 호출이 섞이지 않음을 고정한다.
- **`maxAttempts`는 총 실행 횟수다.** Spring Framework 7의 `maxRetries`는 최초 시도 뒤의 재실행 횟수라 `RetryService`가
  `maxAttempts - 1`을 넘긴다(예전 off-by-one은 고쳐졌고 `RetryServiceTest`가 고정). 기본 3회, 릴레이 상태 기록도
  `STATUS_WRITE_ATTEMPTS` = 3회다(시도마다 한 트랜잭션, 연쇄 발송의 다음 조각 저장 포함). `/ask` 전달 리스너의 아웃박스 재조회와
  레저 기록도 이 상수를 일부러 함께 쓴다(2026-10-13, 결정 #40 — 올리면 세 루프가 함께 길어진다). 최악 소요는
  `retryTimeBound(attemptTimeout, maxAttempts)`가 코드로 계산한다.
- `recoveryCallBack`은 재시도가 소진돼 `RetryException`이 났을 때 실행되고, 없으면 그 `RetryException`(마지막 원인을 감쌈)이
  전파된다. 예외: spring-core 7 `RetryTemplate`은 `Throwable`을 잡아 `Error`도 (재시도 없이) `RetryException`으로 감싸는데,
  `RetryService`는 원인이 `Error`면 그 `Error`를 그대로 던지고 `recoveryCallBack`을 돌리지 않는다(2026-10-15,
  `RetryServiceTest`가 고정). 정책에는 `excludes(Error)`도 걸려 있다 — Spring은 includes/excludes를 원인 체인까지
  대조하므로(`ExceptionTypeFilter.match(…, true)`) `Error(cause = IOException)`이 `includes(Exception)`에 걸려 재시도되던 구멍을 막는다(2026-10-19). 기본 `includes(Exception)`은 `DataIntegrityViolationException`·검증 예외처럼 재시도해도 소용없는 실패까지
  재시도하므로 비일시적 실패가 섞이는 호출은 `exceptions`를 명시한다.
- `@EnableResilientMethods`는 켜져 있지만 저장소에 `@Retryable`은 없다.
- **MariaDB 스냅숏 격리(2026-10-03).** MariaDB ≥ 11.6.2의 REPEATABLE READ는 `innodb_snapshot_isolation`이 기본 ON이다
  (운영 12.0.2, 로컬 실측 12.3.3 = 1). 같은 트랜잭션에서 일관 읽기 뒤에 다른 트랜잭션이 그 뒤 커밋한 행을 잠금 읽기·
  UPDATE·DELETE·FK 부모 검사·PK 중복 검사로 건드리면 ER_CHECKREAD 1020이 나고 트랜잭션 전체가 롤백된다(보조 unique 중복은
  계속 1062). 규칙: CAS·잠금 문장을 트랜잭션의 첫 문장으로 두거나 자기 트랜잭션을 준다(`CveTopicRepositoryImpl.upsert`,
  `MeetingReminderRepositoryImpl.ensureReminder`, `AgentSessionRepositoryImpl.saveProviderSessionId`의 단일 upsert).
  잠금 읽기를 먼저 두는 예: `/standup stop`은 슬래시 트랜잭션 안에서 채널의 활성 루틴을
  `StandupRepository.lockActiveRoutinesByChannel`(`SELECT … FOR UPDATE`)로 먼저 읽고 그다음 `deactivateRoutine`으로
  UPDATE한다. 그 앞에 일반 읽기 `findActiveRoutinesByChannel`을 두지 않는다(이 일반 읽기는 읽은 행을 갱신하지 않는
  `/standup list`와 setup의 이름 중복 검사에서만 쓴다). 1020은
  `SnapshotIsolationExceptionTranslator`(`JpaConfiguration`의 유일한 `SQLExceptionTranslator` 빈 — Boot가 유일할 때만
  `HibernateJpaDialect`와 `JdbcTemplate`에 연결)가 `OptimisticLockingFailureException` 하위인
  `SnapshotIsolationConflictException`으로 바꾼다. 서버가 이미 롤백했으므로 잡으면 트랜잭션을 통째로 다시 실행해야 한다 —
  회의 쓰기의 `executeRetryingOnConflict`가 그렇게 한 번 재시도한다. H2는 1020을 낼 수 없어 testFixtures의
  `SnapshotIsolationTransactionManager`로 경계를 시험한다.

## 알려진 공백

- 새 예외 타입은 `ControllerAdvice`에 핸들러를 직접 추가해야 하고,
  없으면 `handleUnexpected`의 500 `internal_error`로 떨어진다.
- `DatabaseException`은 500이고 `tableName`은 로그에만 쓰이며, 수신자의 정적 타입이라 매핑된 DTO(`MeetingDto`/`RoutineDto`) 이름이 찍힌다(위 절).
- `Command.handleEvent`가 잡은 예외의 `errorReason`은 `CommandExecutor` WARN과, HTTP 멘션이면 `SlackEventController`의 WARN에만
  남는다(2026-10-11부터 둘 다 제어문자를 `?`로 치환, 2026-10-15부터는 C1 제어문자·서식 문자(bidi 등)·U+2028/U+2029까지
  `application/common/LogSanitizing.kt`의 `neutralizeControlCharacters` 하나로). 슬래시 요청자 ephemeral은 고정 문구(또는
  escape된 토큰 + usage)이지
  예외 문구가 아니다. `Error`는 잡지 않는다.
- `DetailErrorAlertContext`(`MessageContent.ErrorNotice`를 채널에 게시)는 스펙은 있지만 **생성하는 프로덕션 코드가 없다.**
- Socket Mode 실패는 로그로만 남는다(인터랙션은 ack하지 않아 Slack이 사용자에게 오류를 보인다). 로컬 프로파일 전용이라 감수하고 있는 상태다.
- `ErrorBroadcaster`는 주입처가 없다(구현은 `StdoutErrorBroadcaster`뿐).
- (해소, 2026-10-15) 경계 곳곳의 `runCatching`이 `Error`까지 `Result.failure`로 바꾸던 공백과, `RetryTemplate`이 `Error`를
  `RetryException`으로 감싸 호출자에게 일반 예외처럼 보이던 공백은 닫혔다 — 위 6번과 결정 #48.

## 근거

- `domain/src/main/kotlin/dev/notypie/domain/common/` (`error/Errors.kt`, `Validation.kt`, `AGENTS.md`)
- `domain/src/main/kotlin/dev/notypie/domain/command/exceptions/` (`CommandErrorCode.kt`, `CommandException.kt`)
- `domain/src/main/kotlin/dev/notypie/domain/command/entity/Command.kt` (`handleEvent`),
  `.../entity/context/CommandContext.kt` (`createErrorResponse`, `errorEphemeral`),
  `.../entity/context/form/RequestMeetingContext.kt`, `.../entity/context/DetailErrorAlertContext.kt`
- `domain/src/main/kotlin/dev/notypie/domain/meet/entity/Meeting.kt` (`validate` in `init`, `MAX_TITLE_LENGTH`),
  `domain/src/main/kotlin/dev/notypie/domain/command/dto/response/CommandOutput.kt`, `.../response/Status.kt`
- `application/src/main/kotlin/dev/notypie/application/exception/` (`PayloadParseException.kt`,
  `ControllerAdvice.kt`, `AGENTS.md`)
- `application/src/main/kotlin/dev/notypie/application/` (`service/mention/SlackMentionEventHandlerImpl.kt`,
  `service/interaction/SlackInteractionHandlerImpl.kt`, `service/command/CommandExecutor.kt`,
  `controllers/SlackEventController.kt`, `socket/SocketModeReceiver.kt`, `common/TransactionTemplateExt.kt`,
  `service/standup/StandupSchedulingService.kt`, `service/meeting/MeetingReminderSchedulingService.kt`,
  `service/cve/collector/CveCollector.kt`), `application/src/test/.../exception/ControllerAdviceTest.kt`
- `infrastructure/src/main/kotlin/dev/notypie/exception/` (`ErrorBroadcaster.kt`, `StdoutErrorBroadcaster.kt`,
  `meeting/DatabaseException.kt`, `AGENTS.md`), `infrastructure/src/test/kotlin/dev/notypie/exception/DatabaseExceptionTest.kt`,
  `application/.../configurations/ConsumerConfig.kt` (`ErrorBroadcasterConfig`),
  `infrastructure/src/main/kotlin/dev/notypie/repository/meeting/MeetingRepositoryImpl.kt`,
  `.../repository/standup/StandupRepositoryImpl.kt`
- `infrastructure/src/main/kotlin/dev/notypie/common/ExceptionBoundary.kt` (`containFailure`, `runCatchingExceptions`),
  `infrastructure/src/test/kotlin/dev/notypie/common/ExceptionBoundaryTest.kt`
- `infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt`, `.../configurations/RetryConfiguration.kt`,
  `infrastructure/src/test/kotlin/dev/notypie/impl/retry/RetryServiceTest.kt`
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/ApplicationMessageDispatcher.kt`,
  `.../impl/command/event/SlackCommandOutputs.kt`
- spring-core 7.0.9 소스: `org/springframework/core/retry/RetryTemplate.java`(`:135-137,149,194-200`), `RetryException.java`
- Spring Framework 7 reference, Core › Resilience Features (`maxRetries`는 최초 시도를 제외한 재시도 횟수;
  `RetryTemplate`은 호출마다 생성 가능한 경량 객체)
- spring-kafka 4.1.1 `KafkaMessageListenerContainer.java`(`doInvokeRecordListener`의 `catch (RuntimeException)`, `ListenerConsumer.run`의 `catch (Error)`
  "Stopping container due to an Error"), `MessagingMessageListenerAdapter.java`(`Error` 재던짐), `FailedRecordProcessor.java`; spring-context 7.0.9
  `TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER`; spring-core 7.0.9 `RetryTemplate.java`(`catch (Throwable)`, `RetryException` 원인); Slack SDK 1.52.0
  `SocketModeClient.initializeMessageProcessorExecutor`(열 개의 `Exception`만 잡는 루프, `javap`) — 2026-10-19 Gradle 캐시 소스에서 확인

## 관련 페이지

- [command-pipeline.md](command-pipeline.md) — `createErrorResponse`의 ephemeral이 stager/renderer를 거쳐 나가는 경로
- [ddd-layering.md](ddd-layering.md) — 예외 소유권이 계층 경계를 따르는 이유
- [events-and-outbox.md](events-and-outbox.md) — `CommandExecutor`가 재큐잉하지 않고 상류 멱등성에 맡기는 배경
- [testing-guide.md](testing-guide.md) — `shouldThrow<ValidationExceptionWithName>`, `DatabaseExceptionTest`, `ControllerAdviceTest`
- [coding-style.md](coding-style.md) — null 경계 조기 해소와 `throwIfSchemaNotFound`의 관계
- [architecture-overview.md](architecture-overview.md), [decisions.md](decisions.md)
- [`domain/common/AGENTS.md`](../../domain/src/main/kotlin/dev/notypie/domain/common/AGENTS.md),
  [`application/exception/AGENTS.md`](../../application/src/main/kotlin/dev/notypie/application/exception/AGENTS.md),
  [`infrastructure/exception/AGENTS.md`](../../infrastructure/src/main/kotlin/dev/notypie/exception/AGENTS.md)
