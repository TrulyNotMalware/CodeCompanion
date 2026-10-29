# 코딩 스타일 가이드

_type: guide · updated: 2026-10-29_

> Kotlin답게, non-null 우선으로, 주석 대신 코드가 설명하게, 파일은 적게 — 그리고 리팩토링에는 반드시
> 테스트가 따라온다.

## 원칙

1. **파일 최소화 · 응집성 우선.** 파일을 여럿 만들지 않는다. 쓸데없이 쪼개졌거나 너무 작은 파일은 합친다. 합치기 전에
   두 가지를 본다 — 받는 파일이 같은 이름의 어노테이션을 import하고 있지 않은지(명시적 import가 같은 파일의 선언보다
   먼저 풀린다. `@Payload`를 import하는 `DebeziumLogTailingProcessor.kt`에 `DebeziumOutboxMessage.kt`의 `data class Payload`를
   옮기면 `Envelope.payload`가 어노테이션 타입이 되어 `.after`에서 컴파일이 깨진다 — 2026-10-29 실측. 필드를 읽지 않는
   파일이었다면 조용히 어노테이션 타입으로 컴파일된다), 톱레벨 함수·프로퍼티를 옮기면 그것들이 들어가는 JVM 파일 클래스
   (`XxxKt`)가 달라지므로(클래스 선언은 이름이 그대로다. `SlackMrkdwn.kt`를 `SlackBlockLimits.kt`에 합치면 `SlackMrkdwnKt`가
   사라지고 그 멤버가 `SlackBlockLimitsKt`로 옮겨진다) `@file:JvmName`·`@JvmMultifileClass`·리플렉션·Java 호출자가 없는지.
2. **Kotlin다운 코드.** 스코프 함수(`let`/`run`/`apply`/`also`/`with`), 고차 함수, DSL을 적극 쓴다. 자체
   DSL(`validate {}`, `exceptionDetails {}`, 모달 템플릿 빌더)이 이미 있으면 그것을 쓴다.
3. **non-null 우선.** nullable 변수·프로퍼티·파라미터를 지양한다. null 가능성은 경계(외부 입력·파싱·조회)에서
   기본값·조기 반환·`require`/`checkNotNull`로 가장 빨리 해소하고, 내부로는 non-null만 흘린다. nullable을
   유지하려면 "없을 수 있음"이 업무적으로 유효한 의미일 때만.
4. **주석 최소화.** 코드로 표현할 수 없는 제약(외부 API의 숨은 규칙, 순서 의존성, 왜 이렇게밖에 못 하는지)만
   주석으로 남긴다. 무엇을 하는지 서술, 과정 나레이션, 변경 이력은 금지. 기존 코드를 고칠 때 주변의 장황한
   주석을 보면 함께 줄인다.
5. **영어만.** 프로덕션·테스트 코드의 식별자·문자열·주석·로그 모두 영어. (설계 문서와 이 위키는 한국어.)
6. **순수 도메인.** `domain`에 프레임워크·전송 결합을 두지 않는다 — [ddd-layering.md](ddd-layering.md).

## 작성 규칙

| 규칙 | 내용 |
|------|------|
| named parameter | Kotlin → Kotlin 호출은 항상 named parameter를 쓴다 (`IdempotencyCreator.create(data = commandData)`). Java API 호출은 예외 |
| `this` 생략 | 불필요한 `this.`는 쓰지 않는다. 의도적인 스타일이며 ktlint 규칙이 아니다 |
| 인터페이스 + `Impl` | 유스케이스와 어댑터는 `MeetingService` / `MeetingServiceImpl`처럼 짝을 이루고, 다른 코드는 인터페이스에만 의존한다 |
| 스케줄 잡 | 목적은 테스트가 스케줄러 없이 잡 로직을 부르는 것이다. `@Scheduled` 메서드는 로직과 같은 클래스에 둘 수 있고(2026-10-26 기준 14개 중 10개), 그때는 공개 진입점 — 1회 실행 함수(`recoverOnce`/`purgeOnce`/`resumeOnce`)나 공개 tick(`pollPending`, `tick`, `immediateTick`/`digestTick`) — 을 둔다. 얇은 `*Scheduler`가 서비스를 부르는 분리는 스탠드업·회의 리마인더·일일 안건·캘린더 동기화 네 잡의 형태이고(서비스는 `*SchedulingService`, 캘린더만 `CalendarSyncService`), 스케줄러가 서비스 단계마다 `containFailure`로 실패를 가둔다. 단계별 실패 격리는 분리를 고려할 이유이지 분리 여부를 가르는 불변 기준은 아니다 — 미분리 잡도 `containFailure`로 단계를 가둔다(`AgentRetentionScheduler.purgeOnce`의 테이블별 purge). 어느 형태든 실패 격리는 `containFailure`로 한다(2026-10-26 정정) |
| 리포지토리 3종 | `XxxRepository`(인터페이스 + DTO) / `JpaXxxRepository` / `XxxRepositoryImpl`(`open class`, 변이 메서드 `@Transactional`, 엔티티를 매핑하는 읽기 메서드 `@Transactional(readOnly = true)` — open-in-view를 껐으므로 매핑은 트랜잭션 안에서), 스키마는 `schema/XxxSchema`. 스키마의 변경 가능한 컬럼은 `var ... protected set`이고 바꾸는 길은 행위 메서드(`redefine`, `changeRole`, `cancel` 등)나 CAS 문뿐이다(2026-10-01) |
| 빈 등록 | 유스케이스 서비스·스케줄러·리스너는 컴포넌트 스캔(`@Service`/`@Component`)으로 등록한다. 모드·기능 플래그로 갈리는 빈(릴레이 처리기·이벤트 발행기, CVE·캘린더·MCP 레인, `@ConditionalOnMissingBean`을 단 에이전트 레인 — 유스케이스 서비스 `AgentConverseService`도 여기)과 인프라 어댑터(저장소 어댑터 팩토리, Slack 파이프라인 어댑터와 `CommandExecutor`, 클라이언트, 실행기)는 `configurations/`(두 모듈)의 명시적 `@Bean`(named argument)으로 등록한다 — `@ConditionalOnBean`과 스캔의 순서 의존을 피하기 위해(예외: 캘린더 스케줄러 둘과 `GoogleOAuthCallbackController`는 스캔 대상에 `@Conditional`을, `SocketModeReceiver`는 `@Profile("local")`을 단다). 2026-10-26 기준 `application`은 스캔 대상 39개 파일, `@Bean` 59개(`configurations/` 56, `security/` 3)다(2026-10-26 정정) |
| 설정 바인딩 | `@ConfigurationProperties` 클래스는 `application/.../configurations/`에, 메인 클래스의 `@ConfigurationPropertiesScan`이 잡는다 |
| 시간 | 시간에 의존하는 서비스는 `Clock`을 기본값 없이 생성자로 받고, `@Bean` 팩토리는 컨텍스트의 `Clock` 빈을 넘긴다(2026-10-01). 생성자 기본값은 빈이 있으면 무시되고, 팩토리가 인자를 빼먹으면 조용히 다른 시계를 쓴다. 테스트가 시각을 고정할 수 있어야 한다 |
| 로깅 | 파일 수준 `private val log = KotlinLogging.logger {}`, 호출은 람다 형태 `log.info { ... }`. `logger`라는 이름은 쓰지 않는다 — `OncePerRequestFilter`·`ResponseEntityExceptionHandler`처럼 `protected logger`(commons-logging)를 가진 Spring 기반 클래스 안에서는 상속 멤버가 파일 수준 `logger`를 가려 `Log.warn(Object)`에 람다 객체가 넘어가고, 로그에는 사유 대신 `…$$Lambda@hash`가 찍힌다(2026-10-01 Slack 서명 필터에서 실제 발생) |
| Jackson | 모듈 공용 `dev.notypie.common.jsonMapper` 하나를 import한다. 별도 인스턴스는 다른 설정이 꼭 필요한 곳(MCP 전송, 스코프 토큰 코덱)에만 |
| 트랜잭션 | 경계는 명령 진입점이 연다 — 슬래시 서비스 메서드의 `@Transactional`, 멘션 핸들러의 `TransactionTemplate`, 인터랙션 핸들러의 `PlatformTransactionManager.getTransaction`. 답장 행을 쓰는 `SlackMessageRelayServiceImpl.saveOutboxMessage`는 `@TransactionalEventListener(phase = BEFORE_COMMIT)`(`fallbackExecution` 기본 false)라 그 트랜잭션에 묶이고, 트랜잭션 없이 발행된 답장은 행 없이 조용히 버려진다. DB를 쓰고 답장을 스테이징하는 이벤트 리스너도 서비스 메서드에 `@Transactional`을 건다 — REQUIRED라 진입점 트랜잭션에 합류하고, 경계가 커밋된 뒤 발행되는 모달 열기 실패 이벤트처럼 트랜잭션이 없으면 스스로 연다(2026-10-26 기준 리스너 7곳이 명시했고, 붙이지 않은 리스너는 진입점 트랜잭션에 합류하거나 `StandupSummaryService`처럼 `runInTx`로 스스로 경계를 연다). 그 밖의 DB 접근은 Repository `Impl`(`open class`)의 `@Transactional`이 경계이고, 앰비언트 트랜잭션이 없는 스케줄러·비동기 경로는 `TransactionTemplate.runInTx`로 경계를 명시한다(2026-10-26 정정, 결정 #24) |
| 예외 | `CodeCompanionRuntimeException` + `ErrorCode` enum + `exceptionDetails {}` — [error-handling-and-validation.md](error-handling-and-validation.md) |
| 실패 경계 | 경계에서 `runCatching`·`catch (Throwable)`로 `Error`를 삼키지 않는다. `containFailure` / `runCatchingExceptions`(`dev.notypie.common`) 또는 타입을 지정한 `catch`를 쓴다. `Throwable`은 정리 후 같은 인스턴스를 다시 던질 때만(결정 #48) |
| 검증 | 애그리거트 불변식은 `init`의 `validate(className = this.javaClass.simpleName) { ... }` |

## 테스트와 함께

- 코드 작성·리팩토링 전에 **테스트 존재 여부를 먼저 확인**하고, 없으면 그 작업과 함께 테스트를 쓴다.
- 반복되는 객체·JSON·DTO 생성은 `src/testFixtures/kotlin/`의 팩토리 함수(`createXxx`)로 뺀다. 인라인 중복
  금지. 상세는 [testing-guide.md](testing-guide.md).
- 플레이스홀더로 통과하는 스펙(빈 `given`, `test.skip`류)은 완료 증거가 아니라 미완성 표시다.

## 포맷 — ktlint가 결정한다

- `./gradlew ktlintCheck`가 CI(`feature/*`, `feat/*`, `features/*`, `dependabot/**` 푸시)에서 돈다.
  `./gradlew addKtlintCheckGitPreCommitHook`으로 pre-commit 훅을 설치해 두는 것이 기본 세팅이다.
- `.editorconfig`가 원본: 120 컬럼, LF, 4칸 들여쓰기(continuation도 4), 마지막 줄바꿈, 후행 공백 제거.
  의도적으로 **끈 규칙** 두 가지 — wildcard import 허용(`ktlint_standard_no-wildcard-imports = disabled`),
  `condition-wrapping` 비활성. 파라미터가 4개 이상이면 함수 시그니처를 여러 줄로 강제한다.
- 스타일 논쟁은 `.editorconfig`를 바꾸는 PR로 한다. 개별 파일에서 `@Suppress`로 우회하지 않는다.

## 커밋 메시지

`.gitmessage` 템플릿을 따른다: 제목은 `<타입> : <제목>`(콜론 앞뒤 공백), 50자 이내, 끝에 마침표 없음.
본문은 `-`로 항목 구분, 한 줄 72자 이내, 꼬릿말에 이슈 번호.

| 타입 | 용도 |
|------|------|
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `docs` | 문서 |
| `test` | 테스트 코드 |
| `refact` | 리팩토링 |
| `style` | 의미에 영향 없는 변경 |
| `chore` | 빌드·패키지 매니저 |

- 커밋 메시지 끝에 `Co-Authored-By` 라인을 넣지 않는다.
- Dependabot 커밋은 같은 형식(`chore : …`)이 되도록 `.github/dependabot.yml`에 프리픽스가 잡혀 있다.

## 작업 프로세스

- 코드 리팩토링·대량 탐색·빌드/테스트 반복처럼 오래 걸리는 작업은 워커(서브에이전트)에 나눈다.
- 작업 단위가 끝나면 Codex 교차검증(cross-review)을 받고, 지적 사항을 반영한 뒤 마무리한다.
- 커밋은 작성자가 명시적으로 결정한다. 에이전트가 임의로 커밋하지 않는다.
- `application-local.yaml`은 로컬 테스트용 실제 토큰을 담을 수 있다. **절대 스테이징·커밋하지 않고**, 되돌리지도
  않는다 — [dev-environment.md](dev-environment.md).

## 근거

- `STYLE_GUIDE.local.md`(로컬 전용 메모 — 이 페이지가 그 내용을 저장소 안으로 옮긴 것), `Refactor.md` §5
- `.editorconfig`, `.gitmessage`, `build.gradle.kts`(ktlint 플러그인·리포터), `README.md` Getting Started
- `application/AGENTS.md`(인터페이스+Impl, 빈 등록, 스케줄 잡, `Clock`, 설정 바인딩), `application/src/main/kotlin/dev/notypie/application/common/AGENTS.md`
- `infrastructure/src/main/kotlin/dev/notypie/configurations/AGENTS.md`(명시적 `@Bean`), `.../configurations/JpaConfiguration.kt`(저장소 어댑터 팩토리)
- 2026-10-26 실측(트랜잭션·빈 등록·스케줄 잡 행): `application/src/main/kotlin/dev/notypie/application/`의 `@Transactional`·`@Scheduled`·
  `@Service`/`@Component`(grep), `configurations/*.kt`·`security/SlackRequestVerificationConfiguration.kt`의 `@Bean`,
  `service/relay/SlackMessageRelayServiceImpl.kt`(`saveOutboxMessage`), `service/{mention,interaction}/*HandlerImpl.kt`,
  `infrastructure/src/main/kotlin/dev/notypie/impl/command/SlackViewOpenDispatcher.kt`(`ViewOpenDeferral`)
- `.github/dependabot.yml`
- 파일 병합 함정(원칙 1, PR #65): `application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt`(`@Payload` import)·
  `application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumOutboxMessage.kt`(`data class Payload`),
  `infrastructure/src/main/kotlin/dev/notypie/templates/SlackBlockLimits.kt`(구 `SlackMrkdwn.kt`). 2026-10-29 스크래치 worktree 에서 두 클래스를 옮기고
  `:application:compileKotlin`: `Unresolved reference 'after' on receiver of type 'Payload'`

## 관련 페이지

- [ddd-layering.md](ddd-layering.md)
- [testing-guide.md](testing-guide.md)
- [error-handling-and-validation.md](error-handling-and-validation.md)
- [dev-environment.md](dev-environment.md)
