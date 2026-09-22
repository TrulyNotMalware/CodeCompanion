# 에러 처리와 검증

_type: pattern · updated: 2026-08-28_

> `ErrorCode` · `exceptionDetails {}` · `validate {}`로 구조화된 예외를 만들고, 계층별 예외 소유권과 에러가 사용자에게
> 닿는 세 갈래(ephemeral / HTTP 상태 / Socket Mode 로그)를 정리한다. 알려진 공백은 마지막 절에 그대로 적었다.

## 에러 계약: `domain/common/error/Errors.kt`

- `ErrorCode { statusCode: Int; message: String }`가 유일한 공개 계약이다. 소유자마다 자기 enum을 둔다:
  `CommonErrorCode`(domain, `internal`, `VALIDATION_FAILED` 400 하나), `command/exceptions/CommandErrorCode`(domain,
  `internal`, 6종), `application/exception/PayloadParseErrorCode`, `infrastructure/exception/meeting/JpaErrorCode`.
- `ExceptionArgument(fieldName, value, reason = "")`가 세부 항목이고, `CodeCompanionRuntimeException(errorCode, details)`가
  모든 예외의 추상 베이스다. 베이스는 `details`와 `RuntimeException.message = errorCode.message`만 보존한다 —
  **`errorCode` 자체는 프로퍼티로 남지 않는다.** 이것이 아래 "알려진 공백"의 절반을 만든다.
- `details`는 `exceptionDetails { "field" value actual because "reason" }` DSL로만 만든다.
  `ExceptionDetailsBuilder.details`가 `internal`이라 다른 모듈은 이 DSL 외에 채울 길이 없다. 리터럴
  `ExceptionArgument(...)` 나열은 domain 안(`throwIfSchemaNotFound`처럼 infrastructure 내부 헬퍼 포함)에서만 보인다.
- **왜 문자열이 아니라 구조인가**: `RequestMeetingContext.meetingValidationMessage`가 `details`를
  `"fieldName: reason"` 줄로 이어 사용자 ephemeral로 렌더링하고, 핸들러는 `rawCommandType` 같은 원값을 로그에 남긴다.
  `reason` 문구는 계약이 아니다(테스트는 `fieldName`/`value`만 단언한다).
- `ErrorResponse`(sealed, `internal`)는 저장소 어디서도 참조되지 않는다. 응답 매퍼를 만들 때 이 위에 쌓지 말고
  의도적으로 승격하거나 지운다.

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
| domain | `ValidationException*`, `internal sealed CommandException` → `SubCommandParseException`, `UnSupportedCommandException` | 애그리거트 `init`, `Command.executeCommand`/`createSubCommand` | 불변식과 커맨드 파싱은 전송과 무관한 규칙이다. `internal`이라 바깥에서는 타입으로 잡을 수 없고 베이스로만 잡는다 |
| application | `AppIdNotFoundException`, `UnsupportedSlackCommandTypeException(rawCommandType)` | `SlackMentionEventHandlerImpl.resolveAppId` / `resolveCommandType` 두 곳뿐 | 오케스트레이션(페이로드 → 커맨드) 실패만 여기. 새 타입은 베이스 상속 + enum 값 + `ControllerAdvice` 핸들러를 **같은 변경**에 넣는다 |
| infrastructure | `DatabaseException(tableName)` + `JpaErrorCode.TABLE_NOT_FOUND`(404), `throwIfSchemaNotFound`, `schemaNotFound {}` | `MeetingRepositoryImpl.getMeeting`, `StandupRepositoryImpl.getRoutine` | JPA 관심사라 여기 두되 `:application`의 `ControllerAdvice`가 import한다 |

- `throwIfSchemaNotFound`는 `T?`에 대한 `inline reified` 확장이라 호출 뒤 값이 non-null로 남는다(null 경계 조기 해소
  규칙과 맞물린다). 단 `tableName`은 **수신자의 정적 타입**이라 두 호출자 모두 매핑 뒤에 부르므로 `MeetingDto`/`RoutineDto`로
  찍힌다. 엔티티 이름을 원하면 스키마 객체에서 불러야 한다. `schemaNotFound {}` DSL은 현재 호출자가 없다.
- `ErrorBroadcaster` 포트는 `ConsumerConfig`가 모드별로 빈을 등록하지만 **아무도 주입하지 않는다.** `KafkaErrorBroadcaster.broadcastError`는
  `TODO()`라 호출되면 `NotImplementedError`(`Error`, `Exception` 아님)가 난다. 첫 호출자가 되기 전에 구현부터 한다.

## 에러가 사용자에게 닿는 길

1. **도메인 컨텍스트 → ephemeral.** `CommandContext.createErrorResponse(errMessage)`는 `OutboundMessage.Ephemeral`
   (`target = commandBasicInfo.channel`, `recipient = null`, `MessageContent.Text(markdown = errMessage)`)을 큐에 넣고
   `CommandOutput.fail(reason)`을 돌려준다. `results` 오버로드는 이미 만들어진 출력(예: 회의는 생성됐지만 공지 실패)을
   그대로 반환하면서 ephemeral만 추가한다. `CommandExecutor.execute`가 **성공 여부와 무관하게** `drainIntents()`를
   발행하므로 에러 ephemeral은 항상 Slack에 도달한다. `RequestMeetingContext`는 `formInput.toMeeting()`을
   `catch (exception: CodeCompanionRuntimeException)`으로 감싸 `details`를 이 경로로 보낸다.
2. **컨텍스트 안의 예외 → 조용한 `fail`.** `Command.handleEvent()`는 `runCatching { executeCommand() }`로 모든 예외를
   `CommandOutput.fail(commandDetailType = ERROR_RESPONSE, reason = exception.toString())`로 바꾼다. 이 경로에는
   ephemeral이 없고, `errorReason`을 읽는 프로덕션 코드도 없다. `SlackInteractionHandlerImpl`은
   `result.takeIf { it.ok }`로 실패 출력을 버리고 `SlackEventController`는 200 본문으로 돌려줄 뿐이다 — 사용자도 로그도
   보지 못한다. 사용자에게 알려야 할 실패는 예외를 던지지 말고 `createErrorResponse`로 만든다.
3. **HTTP(`/api/slack/**`) → `ControllerAdvice`.** `UnsupportedSlackCommandTypeException`은 WARN 로그 + 400
   `{"error": "Unsupported Slack command type: <raw>"}`. `DatabaseException` 핸들러는 본문이 비어 있어 **빈 200**이 나간다.
   `AppIdNotFoundException`은 핸들러가 없어 Spring 기본 500이다. `app_mention`이 아닌 이벤트는 핸들러를 타지 않고 200으로
   ack해 Slack 재시도를 막는다. `handleInteractions`는 `response_action` 본문이 있으면 JSON 200, 아니면 빈 200.
4. **Socket Mode → 로그만.** `SocketModeReceiver.handleSlash`/`handleEvent`/`handleInteractive`는 전부 `runCatching` +
   `log.error`다. MVC 디스패치가 없으니 `ControllerAdvice`는 적용되지 않고, slash/event는 ack를 먼저 보내므로 실패가
   Slack에 전달될 방법이 없다. interactive만 handle → ack 순서라 `response_action`(인라인 검증 오류)을 ack에 실을 수 있다.
5. **서비스 내부 관례.** `TransactionTemplate.runInTx`는 예외를 `Result.failure`로 바꾸고 `setRollbackOnly`만 한다 —
   throw 여부는 호출자가 정한다. `StandupSchedulingService`/`MeetingReminderSchedulingService`는
   `DataIntegrityViolationException`을 "예상된 레이스"로 잡되 **행이 실제로 존재하는지 확인한 뒤에만** 삼킨다.
   `CveCollector`는 토픽마다 `runCatching`으로 격리한다. `CommandExecutor`는 로그 후 재throw하고 인텐트를 재큐잉하지
   않는다 — 재시도는 `idempotencyKey` 아래 상류(아웃박스 릴레이, Kafka, Slack 재전송)가 맡는다.
   `ApplicationMessageDispatcher`는 Slack `ok=false`를 예외가 아니라 `failOutput(reason)`으로 되돌리고 WARN을 남긴다.

## 재시도: `RetryService` / `RetryOptions`

- `RetryService.execute(action, recoveryCallBack?, maxAttempts = 3, initialDelay = 100ms, multiplier = 2.0,
  maxDelay = 10s, jitter = 10ms, exceptions = [Exception])`가 호출마다 `RetryPolicy`를 새로 만든다. 기본값은
  `RetryOptions` enum(`internal val default`)에 있고 `RetryConfiguration.retryTemplate()`도 같은 값으로 빈을 만든다.
- **함정 1 — 공유 템플릿의 정책을 갈아끼운다.** `retryTemplate.retryPolicy = policy`는 `@ConditionalOnMissingBean`으로
  등록된 싱글턴 `RetryTemplate`을 변경한다. `SlackMessageRelayServiceImpl.updateMessage`가 `maxAttempts = 5`로 부르면
  동시에 실행 중인 `MeetingServiceImpl`/`ApplicationMessageDispatcher` 호출도 그 정책을 볼 수 있고, `RetryConfiguration`의
  기본 정책은 첫 호출에서 덮인다. Spring 문서는 `RetryTemplate`을 호출마다 가볍게 생성하라고 하므로 고칠 때는
  `RetryTemplate(policy)`를 호출 단위로 만든다.
- **함정 2 — `maxAttempts`는 사실 `maxRetries`다.** 인자가 그대로 `RetryPolicy.Builder.maxRetries`에 들어가는데, Spring
  Framework 7의 `maxRetries`는 최초 시도 이후의 재시도 횟수다. 총 시도는 `maxAttempts + 1`(기본 4회, 릴레이 6회).
  `RetryServiceTest`는 `maxAttempts = maxFailures + 1`을 주기 때문에 이 off-by-one을 잡지 못한다.
- `recoveryCallBack`은 재시도가 소진돼 `RetryException`이 났을 때만 실행되고, 없으면 그 `RetryException`(마지막 원인을 감쌈)이
  전파된다. 기본 `includes(Exception)`은 `DataIntegrityViolationException`·검증 예외처럼 재시도해도 소용없는 실패까지
  재시도하므로 비일시적 실패가 섞이는 호출은 `exceptions`를 명시한다.
- `@EnableResilientMethods`는 켜져 있지만 저장소에 `@Retryable`은 없다. `createFixedBackOffPolicy`는 미사용 private이다.
  `RetryServiceTest`는 맨 `RetryTemplate()`로 돌아 `RetryConfiguration`의 기본 정책은 어떤 스펙도 보지 않는다.

## 알려진 공백

- `AppIdNotFoundException` → 핸들러 없음 → 500. `PayloadParseErrorCode.APP_ID_NOT_FOUND`의 400은 메타데이터일 뿐이다.
- `DatabaseException` → `ControllerAdvice.handleDatabaseException` 본문이 비어 **빈 200**. `JpaErrorCode`의 404는 도달하지 않는다.
- `CodeCompanionRuntimeException`이 `errorCode`를 보존하지 않아 상태 코드를 일반 매핑하는 핸들러를 만들 수 없다. 네 enum의
  `statusCode`는 어떤 응답 경로도 읽지 않는다. 고치려면 domain에 프로퍼티를 추가하는 변경이 먼저다.
- `Command.handleEvent`의 `runCatching` 경로는 `errorReason`에만 남고 로그·ephemeral 어디에도 나타나지 않는다.
- `DetailErrorAlertContext`(`MessageContent.ErrorNotice`를 채널에 게시)는 스펙은 있지만 **생성하는 프로덕션 코드가 없다.**
- Socket Mode 실패는 로그로만 남는다. 로컬 프로파일 전용이라 감수하고 있는 상태다.
- `ErrorBroadcaster`는 주입처가 없고 Kafka 구현은 `TODO()`다. `ErrorResponse`는 참조가 없다.
- `RetryService`의 공유 정책 변경과 `maxRetries` off-by-one(위 절).
- `DatabaseExceptionTest`는 빈 스펙이고 `ControllerAdvice` 스펙은 없다 — 위 공백을 고칠 때 먼저 채운다
  ([testing-guide.md](testing-guide.md) 참고).

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
  `service/cve/collector/CveCollector.kt`)
- `infrastructure/src/main/kotlin/dev/notypie/exception/` (`ErrorBroadcaster.kt`, `KafkaErrorBroadcaster.kt`,
  `StdoutErrorBroadcaster.kt`, `meeting/DatabaseException.kt`, `AGENTS.md`),
  `infrastructure/src/main/kotlin/dev/notypie/repository/meeting/MeetingRepositoryImpl.kt`,
  `.../repository/standup/StandupRepositoryImpl.kt`
- `infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt`, `.../configurations/RetryConfiguration.kt`,
  `infrastructure/src/test/kotlin/dev/notypie/impl/retry/RetryServiceTest.kt`
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/ApplicationMessageDispatcher.kt`,
  `.../impl/command/event/SlackCommandOutputs.kt`
- Spring Framework 7 reference, Core › Resilience Features (`maxRetries`는 최초 시도를 제외한 재시도 횟수;
  `RetryTemplate`은 호출마다 생성 가능한 경량 객체)

## 관련 페이지

- [command-pipeline.md](command-pipeline.md) — `createErrorResponse`의 ephemeral이 stager/renderer를 거쳐 나가는 경로
- [ddd-layering.md](ddd-layering.md) — 예외 소유권이 계층 경계를 따르는 이유
- [events-and-outbox.md](events-and-outbox.md) — `CommandExecutor`가 재큐잉하지 않고 상류 멱등성에 맡기는 배경
- [testing-guide.md](testing-guide.md) — `shouldThrow<ValidationExceptionWithName>`, 빈 `DatabaseExceptionTest`
- [coding-style.md](coding-style.md) — null 경계 조기 해소와 `throwIfSchemaNotFound`의 관계
- [architecture-overview.md](architecture-overview.md), [decisions.md](decisions.md)
- [`domain/common/AGENTS.md`](../../domain/src/main/kotlin/dev/notypie/domain/common/AGENTS.md),
  [`application/exception/AGENTS.md`](../../application/src/main/kotlin/dev/notypie/application/exception/AGENTS.md),
  [`infrastructure/exception/AGENTS.md`](../../infrastructure/src/main/kotlin/dev/notypie/exception/AGENTS.md)
