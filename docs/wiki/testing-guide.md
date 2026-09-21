# 테스트 가이드

_type: guide · updated: 2026-08-28_

> Spring 없는 Kotest `BehaviorSpec` + MockK를 기본으로, `testFixtures` 팩토리와 가드 테스트로 리팩토링을 지키는
> 이 프로젝트만의 테스트 관례.

## 철학: 여기서 실제로 지키는 것

- **테스트는 코드와 함께 간다.** 코드 작성·리팩토링 전에 대상 스펙이 있는지 먼저 확인하고, 없으면 같은 작업에서
  만든다(로컬 스타일 가이드 §3, git 미추적). 리팩토링 PR에 스펙이 없으면 그 자체가 리뷰 블로커다.
- **인라인 객체 대신 fixture 팩토리.** 반복되는 객체·JSON·DTO 생성은 `src/testFixtures/kotlin/`의 `create*`
  함수로 뽑는다(§6) — 생성자가 바뀌어도 고치는 곳이 한 군데다. 루트 빌드가 모든 서브프로젝트에 `java-test-fixtures`를
  적용하고, `:infrastructure`는 `testFixtures(project(":domain"))`, `:application`은 domain + infrastructure fixture를
  모두 소비한다.
- **도메인 테스트는 순수 JVM.** `:domain`에는 Spring도 DB도 없다. 느린 테스트는 `:infrastructure`의 H2/EmbeddedKafka
  슬라이스뿐이다.
- **아키텍처는 가드 테스트로 강제한다.** 문서로 "하지 말라"고 쓰는 대신 소스를 스캔하는 스펙이 빌드를 깨뜨린다.

## fixture 팩토리 목록

| 모듈 | 위치 | 대표 팩토리 |
|------|------|-------------|
| domain | `domain/src/testFixtures/kotlin/dev/notypie/domain/` | `Constants.kt`의 `TEST_APP_ID`·`TEST_USER_ID`·`TEST_CHANNEL_ID`·`TEST_BOT_TOKEN`·`TEST_BASE_URL`; `command/InboundCommandCreator.kt`의 `createMentionInboundCommand`·`createInteractionInboundCommand`·`createSlashInboundCommand`; `createCommandBasicInfo`·`createIntentQueue`·`createInboundInteraction`; `meet/MeetingTestFixtures.kt`의 `createMeeting`·`createCancelMeetingEvent`·`createRescheduleMeetingEvent`; `standup/StandupTestFixtures.kt`의 `createRoutine`·`createStandupSession`·`createSessionDispatch` |
| infrastructure | `infrastructure/src/testFixtures/kotlin/dev/notypie/` | `schema/`의 `createMeetingSchema`·`createCveTopicSchema`·`createCveTopicDefinition`·`CveEventCreator`; `impl/command/BlockActionPayloadCreator.kt`의 `createRoutingOnlyViewSubmissionJson`·`stateValuesJson`·`datepickerStateJson`; `impl/command/slack/`의 `createInteractionPayloadInput`·`SlackEventCallBackRequestCreator` |
| application | `application/src/testFixtures/kotlin/dev/notypie/application/` | `outbox/OutboxTestFixtures.kt`의 `createFixedUtcClock`·`createOutboxRow`·`createPollingProcessorFixture`·`MessageOutboxRepository.stubOutboxStatus`; `service/mention/AppMentionPayloadCreator.kt`의 `createAppMentionPayload`; `CveTopicConfigCreator`·`SummaryRequestCreator`·`ScopedTurnTokenCreator`·`AgendaItemCreator` |

`createPollingProcessorFixture`처럼 **SUT와 협력자를 묶은 data class**를 돌려주는 팩토리가 이 저장소의 상위 패턴이다.
스펙에서 `AppConfig`를 다시 조립하지 않는다.

## 스펙 스타일과 선택 기준

- 전 모듈 스펙 클래스 120개(파일 119개) 중 118개가 `BehaviorSpec`이다. `given` / `` `when` `` / `then`을 영어
  문장으로 쓰고, `` `when` ``은 Kotlin 키워드라 항상 백틱으로 감싼다.
- `StringSpec`은 둘뿐이다: 파일 시스템을 스캔하는 `DomainLayeringGuardTest`, 코덱 라운드트립인
  `OutboundMessageCodecTest`. "한 줄 이름 = 한 규칙"이 자연스러울 때만 쓴다.
- 공유 베이스는 상속이 아니라 **같은 파일의 두 번째 스펙**이다. `AbstractCommandContextTest.kt`는
  `AbstractCommandContextTest`와 `AbstractReactionCommandContextTest`를 한 파일에 두고 `object : CommandContext<NoSubCommands>`
  익명 서브클래스로 추상 계약을 검증한다. 새 컨텍스트 스펙은 이 파일을 흉내 내되 fixture는 재사용한다.
- Spring 슬라이스는 `@ApplyExtension(extensions = [SpringExtension::class])` + 생성자 `@Autowired` 조합으로 쓴다
  (`kotest-extensions-spring`, 7개 파일). 필드 주입은 쓰지 않는다.

## MockK 관례

- `mockk<T>(relaxed = true)`는 반환값을 보지 않는 협력자에만 쓴다(154회). 반환값이 흐름을 바꾸는 포트(`claimPending`,
  `claimDispatch`)는 반드시 `every { } returns`로 명시한다.
- **CAS 토큰은 `slot<String>()`으로 잡아 동일성을 단언한다.** `StandupSchedulingServiceTest`는 `claimDispatch`와
  `markDispatchSent`의 `claimToken`을 각각 캡처해 같은 토큰인지 보고, `CveSummaryWorkerTest`는 `claimForSummary`의
  `token`과 완료 호출의 토큰을 비교한다. 토큰 값을 `any()`로 흘려보내면 CAS가 깨져도 통과한다.
- **배치 크기는 캡처한 리스트의 `shouldHaveSize`로 본다.** `PollingMessageProcessorTest`는 `claimPending`이 후보보다
  적은 수를 돌려줄 때 그 수만 `batchPendingMessages`에 전달되는지 `slot<List<OutboxMessage>>()`로 확인한다.
- `Unit` 반환 스텁은 `just Runs`. `CveNotificationDispatcherTest.stubTransactionManager()`가 정석이다:
  `getTransaction` → `mockk<TransactionStatus>(relaxed = true)`, `commit`/`rollback` → `just Runs`. `runInTx`를
  쓰는 서비스는 `TransactionTemplate(transactionManager)`를 직접 만들므로 이 스텁이 있어야 실제 `runInTx`가 돈다.
- 시간은 항상 고정한다. `Clock.fixed` 또는 `createFixedUtcClock()`(기본 `2026-04-28T12:00`)을 주입하고,
  `IdempotencyCreatorTest`처럼 `currentTimeMillis`를 명시해 `999` vs `1000` ms 경계를 핀한다.
- 서비스 스펙은 Slack 페이로드가 아니라 **효과**를 단언한다: `verify(exactly = 1) { repo.markDispatchSent(...) }`,
  `effects.filterIsInstance<CommandIntent.RescheduleMeeting>().single()`.

## 통합 슬라이스 구성(`:infrastructure`에만 있다)

- `infrastructure/src/test/kotlin/dev/notypie/TestApplication.kt`는 빈 `@SpringBootApplication`이고,
  `src/test/resources/application.yaml`은 Kafka 직렬화기와 로깅만 정한다. 데이터소스 설정이 없으므로 Boot가 H2를
  자동 구성한다. `kafka: OFF` 로깅은 EmbeddedKafka 종료 레이스 로그를 죽이기 위한 것이다.
- `@DataJpaTest` 6개(`Jpa*RepositoryTest`와 `AddParticipantRepositoryTest`)는 사용자 `@Configuration`을 걸러내므로
  `JpaConfiguration`은 로드되지 않는다. Boot 기본 리포지토리 스캔 위에서 돈다.
- **Kotest 컨테이너 스코프는 테스트 트랜잭션 바깥에서 실행된다.** `given` 블록의 `saveAndFlush`는 롤백되지 않고
  스펙 간에 하나의 H2를 공유한다. 그래서 `JpaCveTopicRepositoryTest`는 `topicKey`를 블록마다 고유하게 만들고
  `countActive()`를 `baseline + 2`처럼 델타로 단언한다. 전역 정확 일치(`shouldBe 3`)는 다른 스펙 순서에 따라 깨진다.
- 리포지토리 스펙은 **레인마다 쌍**이다: `Jpa*RepositoryTest`(H2, 쿼리·CAS 의미)와 `*RepositoryImplTest`(MockK한
  `Jpa*` + 손으로 만든 어댑터, 매핑). 동시성 주장(`claimPending`, `insertIgnore`, `resetStuck`)은 **지는 쪽 경로**도
  스펙이 있어야 한다.
- `@SpringBootTest` + `@EmbeddedKafka`(KRaft)는 `impl/command/KafkaEventPublisherTest` 하나뿐이다. `configurations/`
  패키지의 `@Bean`이 잘못 엮이면 깨지는 유일한 스펙이 이것이다.
- `:application`에는 Spring 컨텍스트를 띄우는 스펙이 **없다**(`src/test/resources`도 없다). `application/AGENTS.md`의
  "outbox and Kafka paths use EmbeddedKafka and H2"는 현재 소스와 맞지 않는다 — 아웃박스 스펙은 전부 MockK 단위 스펙이다.

## 실행 명령

```bash
./gradlew build                       # compile + ktlintCheck + 전 모듈 테스트
./gradlew :domain:test                # 모듈 단위
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
./gradlew :application:test --tests '*Cve*' --tests '*Standup*'
```

- 루트 `build.gradle.kts`가 모든 `Test` 태스크에 `-Xmx4g`, `-XX:+EnableDynamicAgentLoading`,
  `--add-opens java.base/java.lang`·`java.base/java.util`(MockK 요구)을 건다. 모듈에서 덮어쓰지 않는다.
  버전은 `extra["kotestVersion"] = "6.2.0"`, `extra["mockkVersion"] = "1.14.11"`.
- CI(`.github/workflows/simple_test_action.yaml`)는 `feature/*` 푸시에서 **바뀐 모듈만** `:module:test`로 돌리고,
  `*.gradle.kts`/`gradle/**`가 바뀌면 전체 `test`를 돈다. `**/*.md`는 트리거에서 제외된다.

## 지워서는 안 되는 스펙(가드·회귀)

- `domain/.../architecture/DomainLayeringGuardTest`: `meet`/`standup`/`common`이 `command`를 import하지 않는지,
  도메인 소스에 Slack SDK·Jackson·Gson 참조가 없는지, **테스트 런타임 클래스패스**에 `ObjectMapper`/`Gson`/
  `com.slack.api.Slack`이 없는지(루트 빌드가 Jackson을 주입하던 회귀 방지), 식별자에 `responseUrl`/`triggerId`가
  없는지를 검사한다. 위반이 나오면 코드를 고치지 정규식을 완화하지 않는다.
- `infrastructure/.../impl/command/ViewSubmissionChannelRoutingRegressionTest`: 커밋 9963f80/2a5c006의
  `view_submission` 채널 오라우팅 회귀 가드. 실제 `ModalTemplateBuilder`가 낸 `private_metadata`를 실제 파서와 도메인
  `InteractionCommand`에 통과시켜 5개 모달 흐름의 채널·토큰 순서를 핀하고, 마지막 `given`은 토큰을 뒤집어
  **오라우팅이 실제로 일어남**을 증명하는 음성 대조군이다. 중복처럼 보여도 삭제 금지.
- `AbstractCommandContextTest`의 "Override runCommand function" 블록은 `runCommand()`의 `open`이 사라지는 것을 막는
  계약 테스트다. `IdempotencyCreatorTest`의 윈도 경계, `templates/ModalTemplateBuilderTest`의 JSON 핀도 같은 부류다.

## 채워야 할 빈 스펙과 빠진 스펙

- `infrastructure/.../exception/DatabaseExceptionTest`: `given` 하나에 `when`/`then`이 없어 공허하게 통과한다.
  `throwIfSchemaNotFound`의 non-null 반환과 null → `DatabaseException`(`details`의 `fieldName`/`value`, `tableName`이
  수신자 타입 simple name)을 여기에 채운다.
- `domain/.../command/CommandDomainTest`: 본문이 빈 `BehaviorSpec`이다.
- 컨트롤러·`ControllerAdvice` 스펙이 없다. `spring-boot-starter-test`와 `spring-restdocs-mockmvc`는 클래스패스에
  있고 `application/src/testFixtures/kotlin/dev/notypie/docs/DSL.kt`(`"field" type STRING means "..."` 형태의
  REST Docs 필드 DSL)와 `Utils.kt`도 준비돼 있지만 **소비자가 없다**. 첫 `@WebMvcTest` 슬라이스가 이 DSL의 자리다.
- `ErrorBroadcaster` 두 구현 모두 스펙이 없다(호출자도 없다).

## 무엇을 추가할 때 무엇을 테스트하는가

- **커맨드/컨텍스트 추가**: `domain/src/test/.../command/context/`에 컨텍스트 스펙(fixture는 `createCommandBasicInfo`·
  `createIntentQueue`), 파서 스펙(`AppMentionContextParserTest`/`InteractionContextParserTest`)에 분기 추가. 새
  `CommandIntent`/`OutboundMessage` 변형은 `:infrastructure`의 `SlackIntentResolverTest`·`SlackOutboundStagerTest`·
  `SlackOutboundRendererTest` 세 곳에 `when` 분기 스펙이 없으면 효과가 조용히 버려진다. 서비스 스펙은
  `application/src/test/.../service/`에서 포트를 MockK하고 효과를 단언한다. 모달 변경은 `templates/*BuilderTest`도 함께.
- **리포지토리 추가**: `schema/`에 `create*Schema` fixture → `Jpa*RepositoryTest`(H2, 이기는/지는 경로 모두) →
  `*RepositoryImplTest`(매핑, `slot`으로 저장된 스키마 필드 검증). 네이티브 SQL은 MariaDB에서 별도 확인.
- **아웃박스 경로 추가**: `OutboundMessageCodecTest`(직렬화 라운드트립), `PollingMessageProcessorTest`(claim 수 ≤ 후보 수
  의미), `SlackMessageRelayServiceImplTest`, `OutboxHealthIndicatorTest`(`stubOutboxStatus`), Kafka는 `KafkaEventPublisherTest`.
- **시간 의존 동작 추가**: 스탠드업 타임존·컷오프는 **날짜 경계를 넘는 케이스**를 반드시 넣는다(과거에 깨진 지점).
  보안 필터·토큰은 만료/변조 음성 케이스를 함께 둔다.

## 근거

- `build.gradle.kts`(테스트 JVM 인자, `java-test-fixtures`, kotest/mockk 버전), `application/build.gradle.kts`,
  `infrastructure/build.gradle.kts`, `.github/workflows/simple_test_action.yaml`
- 루트·`domain`·`application`·`infrastructure` 및 하위 `AGENTS.md`의 Testing Requirements 절
- `domain/src/test/kotlin/dev/notypie/domain/architecture/DomainLayeringGuardTest.kt`,
  `.../command/context/AbstractCommandContextTest.kt`, `.../command/CommandDomainTest.kt`
- `domain/src/testFixtures/kotlin/dev/notypie/domain/` (`Constants.kt`, `command/InboundCommandCreator.kt`,
  `meet/MeetingTestFixtures.kt`, `standup/StandupTestFixtures.kt`)
- `infrastructure/src/test/kotlin/dev/notypie/TestApplication.kt`, `infrastructure/src/test/resources/application.yaml`,
  `.../repository/cve/JpaCveTopicRepositoryTest.kt`, `.../repository/cve/CveTopicRepositoryImplTest.kt`,
  `.../impl/command/KafkaEventPublisherTest.kt`, `.../impl/command/ViewSubmissionChannelRoutingRegressionTest.kt`,
  `.../exception/DatabaseExceptionTest.kt`
- `application/src/test/kotlin/dev/notypie/application/` (`service/standup/StandupSchedulingServiceTest.kt`,
  `service/cve/ai/CveSummaryWorkerTest.kt`, `service/relay/PollingMessageProcessorTest.kt`,
  `service/cve/notification/CveNotificationDispatcherTest.kt`, `common/IdempotencyCreatorTest.kt`)
- `application/src/testFixtures/kotlin/dev/notypie/application/outbox/OutboxTestFixtures.kt`,
  `application/src/testFixtures/kotlin/dev/notypie/docs/DSL.kt`, `.../docs/Utils.kt`
- `STYLE_GUIDE.local.md` §3, §6 (git 미추적, 로컬 전용)

## 관련 페이지

- [coding-style.md](coding-style.md) — named parameter·non-null·주석 규칙은 테스트 코드에도 그대로 적용된다
- [ddd-layering.md](ddd-layering.md) — `DomainLayeringGuardTest`가 강제하는 경계의 이유
- [command-pipeline.md](command-pipeline.md) — 커맨드 추가 시 세 모듈에 걸쳐 스펙이 필요한 이유
- [events-and-outbox.md](events-and-outbox.md) — 아웃박스 claim/CAS 스펙이 지키는 의미
- [error-handling-and-validation.md](error-handling-and-validation.md) — 검증·`DatabaseException` 단언의 대상
- [architecture-overview.md](architecture-overview.md), [decisions.md](decisions.md)
- [`../../domain/AGENTS.md`](../../domain/AGENTS.md), [`../../infrastructure/AGENTS.md`](../../infrastructure/AGENTS.md),
  [`../../application/AGENTS.md`](../../application/AGENTS.md)
