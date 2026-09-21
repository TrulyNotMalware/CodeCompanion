# 명령 파이프라인

_type: architecture · updated: 2026-08-28_

> Slack 요청은 인프라 경계에서 중립 `InboundCommand`가 되고, 도메인 `Command`/`CommandContext`가 이를 `CommandIntent`와
> `OutboundMessage`로 바꾸며, 응답은 스테이저 → 아웃박스 → 렌더러를 거쳐 배달 시점에 한 번만 렌더되어 나간다.

## 한눈에 보기

```
Slack ─▶ controllers/socket ─▶ impl/command(slack) ─▶ InboundCommand ─▶ Command.handleEvent() ─▶ CommandContext
                                                                        addIntent / addOutbound ─▶ IntentQueue
CommandExecutor.drainIntents() ─┬─ CommandIntent ──▶ SlackIntentResolver ─▶ CommandEvent ─▶ 리스너 ─▶ DB + stage(답장)
                                └─ OutboundMessage ─▶ SlackOutboundStager ─┬─ OpenModal ─▶ OpenViewEvent ─▶ views.open
                                                                          └─ 그 외 ─▶ OutboundMessageEnqueued ─▶ outbox
                       relay ─▶ OutboxPayloadRenderer ─▶ SlackOutboundRenderer ─▶ ApplicationMessageDispatcher ─▶ Slack
```

## 1. 인바운드 정규화 — Slack 어휘가 멈추는 곳

세 진입 표면이 하나의 봉투 `InboundCommand`(`domain/command/inbound/InboundCommand.kt`)로 수렴한다. `kind`
(`SLASH`/`MENTION`/`INTERACTION`)와 sealed `InboundPayload`가 라우팅 축이다.

| 표면 | 진입 | 변환(모두 `impl/command/`) | payload |
|------|------|---------------------------|---------|
| 슬래시 | `SlashCommandController` `/api/slash/*` | `SlashCommandRequestBody.toInboundCommand()` | `SlashInvocation` |
| 멘션 | `SlackEventController` `/api/slack/events` | `toMentionInboundCommand()` (`slack/SlackMentionMapper.kt`) | `MentionInvocation` |
| 인터랙션 | `SlackEventController` `/api/slack/interaction` | `SlackInteractionRequestParser` → `toInboundCommand()` (`SlackInboundMapper.kt`) | `InboundInteraction` |

- 도메인은 `trigger_id`/`response_url`/`message_ts`를 `TriggerHandle`/`ReplyHandle`/`MessageHandle`(value class)로만
  본다. `DomainLayeringGuardTest`가 도메인 소스의 `responseUrl`/`triggerId` 식별자와 Slack SDK·Jackson import를 막는다.
- 멘션의 rich_text 순회는 `SlackMentionMapper`가 맡는다. `authorizations`에서 봇 id를 얻어 멘션 목록에서 빼고 텍스트를
  공백으로 쪼갠다. `hasCommandStructure`는 "지원하지 않는 형식"과 "빈 명령"을 구분하기 위한 플래그다.
- 인터랙션 라우팅 토큰은 `idempotencyKey,CommandDetailType,extras...` 문자열이며 메시지 텍스트·버튼 값·모달
  `private_metadata`에 실려 돌아온다. 파서는 `CommandDetailType.valueOf`로 **fail-fast** 파싱한다.
- `view_submission`에는 채널이 없다. `recoverDeliveryChannel`이 reschedule/add-participant 제출에서만 `private_metadata`의
  채널을 되살리고, 그 외 플로우는 `InboundSubmission`의 명명 필드(`noticeChannel`, `commandChannel`)로 업무 채널을 받는다.
- `SlackInboundMapper.buildSubmission`이 플로우별 typed `InboundSubmission`을 한 번 만들어 주므로 컨텍스트는 해석
  정책만 가진다. 단 `InboundForm.fields` **순서는 의미가 있다**(`MeetingFormInput`이 TIME 픽커를 위치로 읽음).
  `SocketModeReceiver`(`@Profile("local")`)는 같은 서비스 인터페이스를 호출하는 두 번째 전송일 뿐이다.

## 2. Command 애그리거트

`Command<T : SubCommandDefinition>`(`entity/Command.kt`)은 `idempotencyKey`와 `InboundCommand`를 쥐고
`handleEvent()` 한 번으로 끝난다: `createSubCommand()` → `parseContext()` → `InboundInteraction`이면
`ReactionContext.handleInteraction()`, 아니면 `runCommand()`. 전체가 `runCatching`이라 예외는 `CommandOutput.fail`이 된다.

- **`SubCommandDefinition`**: `subCommandIdentifier`/`requiresArguments`/`minRequiredArgs`/`usage`. 슬래시 명령은
  enum으로 구현한다(`MeetingSubCommandDefinition {NONE, LIST}`, `StandupSubCommandDefinition {NONE, SETUP}`).
  `NONE`의 식별자가 `""`인 이유는 `SlashCommandRequestBody.subCommandList()`가 빈 텍스트를 `[""]`로 만들기 때문이다.
- **구현체**: 슬래시는 명령당 하나(`RequestMeetingCommand`, `SetupStandupCommand`, `CveLatestSlashCommand`,
  `CveSubscribe/Unsubscribe/SubscriptionsSlashCommand`). 멘션과 인터랙션은 `InteractionCommand` 하나가 받아 페이로드
  타입으로 `AppMentionContextParser` / `InteractionContextParser`를 고른다. `ReplaceTextResponseCommand`는 레거시용.
- **`CommandContext<T>`**(`entity/context/`, internal)는 `commandBasicInfo`·`subCommand`·`intents`를 받고 효과를
  `addIntent()`/`addOutbound()`로 큐에 넣기만 한다. `createErrorResponse`는 에러 `Ephemeral`을 큐에 넣고 `fail`
  출력을 돌려준다. 인터랙션을 받는 컨텍스트는 `ReactionContext` 하위뿐이며, 아니면 `UnSupportedCommandException`이다.
- **기능별 컨텍스트**: 멘션 → `Notice/ApprovalForm/TextResponse/Status/AgentChat/RoleManagement/CveOpsContext`;
  슬래시 → `RequestMeeting/RequestStandupSetup/RequestCve*Context`; 모달 제출 → `context/form/*SubmissionContext`.
  인터랙션 → 컨텍스트 매핑은 `CommandDetailType.createContext()`(`entity/CommandType.kt`), 미매핑은 `EmptyContext`.
- **`CommandOutput` + `Status`**(`dto/response/`): `ok`, `status`(`IN_PROGRESSED/SUCCESS/FAILED/DO_NOTHING`),
  `commandDetailType` 등 실행 메타데이터. 특이점: `RequestMeetingContextResult`는 `CommandOutput`을 상속해 `Meeting`을
  나르고, `SlackInteractionHandlerImpl`이 `ok`일 때 Spring 이벤트로 발행하면 `MeetingServiceImpl.createNewMeeting`
  (BEFORE_COMMIT)이 저장한다. 미팅 생성만 이 사이드 채널을 쓴다.

## 3. CommandIntent와 OutboundMessage — 두 종류의 효과

`IntentQueue`는 `CommandEffect`를 담는다(sealed가 아닌 이유: 두 구현이 다른 패키지). 구분 기준은 Refactor.md Phase 3
— 옛 `CommandIntent`는 순수 업무 요청·표현 연산·Slack 라우팅 enum을 한 sealed 타입에 섞은 "god type"이었다.

- **`CommandIntent`**(`intent/CommandIntent.kt`, sealed): 상태를 바꾸거나 저장소를 읽어야 하는 요청(`CancelMeeting`,
  `RecordStandupAnswer`, `GrantRole`, `AgentConverse` 등). KDoc에 "누가 트리거하고 불변식은 어디서 강제되는가"를 적는다.
- **`OutboundMessage`**(`outbound/OutboundMessage.kt`, sealed): 사용자에게 보이는 결과. `ChannelMessage`, `Ephemeral`,
  `UpdateMessage`, `ReplaceMessage`, `OpenModal`, `Approval`, `Notice`(그리고 `DirectMessage`, §8 참고). 내용은
  `MessageContent`(Text/ErrorNotice/Schedule/Form/MeetingRequest/MeetingList/StandupSummary), 모달은 `ModalForm`.
- **의도 → 이벤트**: `SlackIntentResolver.resolveAll`이 변종별로 `CommandEvent`와 라우팅용 `CommandDetailType`을
  붙인다(`MeetingListRequest` → `GetMeetingListEvent`). 이벤트는 `isInternal = true`라 Spring 버스로 가고, 리스너
  (`MeetingServiceImpl`, `RoleManagementService`, `OpsStatusService`, `AgentConverseService`, `CveOpsService`)
  가 DB 작업 후 답장을 `OutboundMessageStager.stage` + `EventPublisher.publishOne`으로 스테이징한다. 도메인은
  "무엇을 원하는지"만 말하고 답장 문구는 리스너가 만든다. `CommandIntent.Nothing`은 `null`로 사라진다.
- **잔존 누수**: `ApprovalContents.commandDetailType`은 표현 모델에 남은 라우팅 enum이다(Refactor.md §4.4가
  `interactionValue` 문자열은 걷어냈지만 필드는 남김). `Approval` 렌더 시 버튼 값의 라우팅 타입이 여기서 나온다.

## 4. 아웃바운드 — 두 개의 스테이징 문, 한 번의 렌더

1. **`OutboundMessageStager.stage()`**(도메인 포트, 구현 `SlackOutboundStager`): 요청 트랜잭션 안에서 명령이 낳은
   효과를 처리한다. `OpenModal`만 지금 렌더해 `OpenViewEvent`로 만들고, 나머지는 **렌더하지 않은 채**
   `OutboundMessageEnqueued`로 감싼다. 이를 `SlackMessageRelayServiceImpl.saveOutboxMessage`(BEFORE_COMMIT)가 받아
   `OutboundMessagePort.toRow` + `outboxRepository.save`로 행을 만든다. 명령의 DB 쓰기와 답장 행이 한 트랜잭션에
   묶이는 것이 이 문의 존재 이유다.
2. **`OutboundMessagePort.toRow()` + 저장소 직접 save**(`repository/outbox/OutboundMessagePort.kt`): 명령이 없는
   스케줄러·디스패처(`Standup*`, `DailyAgenda*`, `MeetingReminder*SchedulingService`, `StandupSummaryService`,
   `CveNotificationDispatcher`)가 쓴다. 포트는 순수 값 빌더이고 영속은 호출자 트랜잭션 책임이다.

- **렌더는 배달 시점에 한 번**: relay(폴링 또는 CDC)가 행을 집으면 `OutboxPayloadRenderer.render`가 schema 버전을
  검사하고 `OutboundMessageCodec.decode` 후 `Transport`별 `OutboundRenderer`(`SlackOutboundRenderer`)에 넘긴다.
  스테이저와 렌더러는 같은 `SlackApiEventConstructor`를 쓰므로 wire 출력이 동일하다. 세 번째 렌더 경로는 금지다.
- **모달 예외의 이유**: `views.open`은 요청 스레드의 `trigger_id`가 필요하고 발급 후 약 3초에 만료된다. 그래서
  `OpenViewEvent`는 아웃박스를 거치지 않고 `SlackViewOpenDispatcher`(`@EventListener`, 의도적으로 `@Async` 없음)가
  `MessageDispatcher.dispatchImmediate`로 즉시 호출한다. 실패는 재시도 대신 `DeclineModalOpenFailedEvent` /
  `StandupModalOpenFailedEvent`로 알려 안내 ephemeral을 보낸다. 빈 핸들이면 스테이저는 경고 후 `null`을 돌려준다.
- `SlackOutboundRenderer`는 `OpenModal`/`DirectMessage`에서 **일부러** `error()`를 던진다. 조용한 폴백을 넣지 말 것.
- `CommandExecutor`는 resolve/publish 실패를 로그 후 재던져 호출자 `@Transactional`을 롤백시키며 큐를 재적재하지
  않는다(재시도는 relay·Kafka·Slack 재전송이 `idempotencyKey`로). 발행 빈은 `ConsumerConfig` 조건에 따라
  `KafkaEventPublisher` 또는 `AppEventPublisher`다.

## 5. 역할 게이트

- `CommandPermission {BASIC, AI, OPERATIONS, ADMINISTRATION}`, `UserRole {USER ⊂ AI_USER ⊂ DEVELOPER ⊂ ADMIN}`.
  `ADMIN`은 `CommandPermission.entries.toSet()`이라 앞으로 추가되는 권한도 자동 포함한다.
- 멘션 명령 → 권한 매핑은 `CommandSet`(internal)이 갖고, `UNKNOWN`(자유 텍스트 → `ask` 폴백)은 `AI`다. 게이트는
  `AppMentionContextParser.parseContext`가 라우팅 직전에 `actorRole.grants(...)`로 검사하고, 거부 시
  `TextResponseContext`로 "You don't have permission..."을 답한다.
- **해석 순서**는 `CommandRoleResolver.resolve`: `slack.app.authorization.bootstrap-admins`(설정, 채팅에서 불변) →
  `user_command_role` 행(`UserCommandRoleRepository.findRole`) → `USER`. `SlackMentionEventHandlerImpl`이 매 턴
  새로 해석하므로 revoke가 즉시 반영된다. 부트스트랩 admin은 DB 행 없이 첫 admin을 만들기 위한 닭-달걀 해소다.
- **게이트되지 않는 표면**: 인터랙션(`SlackInteractionHandlerImpl.buildCommand`가 `UserRole.USER` 고정)과 슬래시
  서비스(해석기를 호출하지 않음). 둘 다 `BASIC` 표면이라 모달이 모두에게 계속 동작해야 한다는 결정이다. 슬래시에
  권한을 붙이려면 서비스가 직접 `CommandRoleResolver`를 불러야 한다.
- `grant`/`revoke`/`roles`는 `RoleManagementService`(`@Transactional @EventListener`)가 처리한다. `@Transactional`을
  명시한 이유는 역할 쓰기와 확인 답장의 아웃박스 행이 한 트랜잭션에 묶여야 하기 때문이다(Codex 교차검증 반영).

## 6. 새 명령 추가하기 — 슬래시 명령이 실제로 동작하려면 건드려야 하는 지점

1. `SlashCommandController`에 `@PostMapping` 추가. HTTP 경로(`/api/slash/meet`)와 Slack 명령 이름(`/meetup`)은
   독립이다. `/api/slash/` 밖의 경로는 `SlackRequestVerificationFilter`가 서명 검증을 하지 않는다.
2. `SocketModeReceiver.handleSlash`의 `when`에 분기 추가, `AppConfig.Socket`에 명령 이름 프로퍼티 추가
   (`slack.app.socket.*`). 빠뜨리면 로컬에서만 "Unmapped slash command" 경고로 사라진다.
3. `domain/command/entity/slash/`에 `Command` 서브클래스 + `SubCommandDefinition` enum(하위 명령이 있으면) 또는
   `NoSubCommands`. `findSubCommandDefinition()`은 `findSubCommandByIdentifier<T>()`를 쓰고, 첫 토큰이 없으면 `NONE`.
4. `CommandContext`(인터랙션을 받으면 `ReactionContext`) 추가. 모달을 열면 `SlashInvocation.trigger.raw`를 넘겨
   `OutboundMessage.OpenModal`을 내고, `ModalForm` 변종 + `SlackOutboundStager.stageModal` 분기 +
   `SlackApiEventConstructor.open*ModalRequest`를 짝으로 추가한다.
5. 서비스(`@Transactional`)에서 `IdempotencyCreator.create(data = commandData)` → `Command` 생성 →
   `commandExecutor.execute`. 도메인은 저장소를 못 보므로 선행 데이터는 서비스가 조회해 생성자로 넘긴다
   (`CveSubscriptionSlashServiceImpl`의 `topics`).
6. 상태를 바꾸면 `CommandIntent` 변종 + `SlackIntentResolver` 분기 + `CommandEvent`/`EventPayload` + 리스너. 새
   `OutboundMessage`/`MessageContent`를 만들면 `SlackOutboundRenderer` 분기. 빠지면 효과가 **조용히 버려진다**.
7. 모달 제출이 돌아와야 하면 `CommandDetailType` 항목 + `CommandDetailType.createContext` 분기 +
   `SlackInboundMapper.buildSubmission`의 `InboundSubmission` 변종 + `InboundFieldKeys`의 block id 상수(템플릿과 공유).
8. README "Bot Commands & Roles" 표와 `AppMentionContextParser.HELP_MESSAGE` 갱신(파서 테스트가 문구를 고정한다).

**멘션 하위 명령** — `CommandSet`에 항목과 `requiredPermission` 추가 → `AppMentionContextParser.parseContext`의
`when` 분기와 usage 상수 → 컨텍스트 → (의도가 있으면) 6번 → `HELP_MESSAGE`/README. 인터랙션이 필요하면 7번.

## 7. `internal` 가시성과 미룬 누수

- 공개 API는 `Command` 서브클래스, `InboundCommand`/`InboundInteraction`, `CommandIntent`, `OutboundMessage`,
  `CommandOutput`, `UserRole`/`CommandPermission` 정도다. `CommandContext`, `ReactionContext`, `SubCommand`,
  `parseContext`/`findSubCommandDefinition`, `CommandSet`, `MeetingListRange`, `createContext`는 `internal`이다.
  응용 계층은 `Command`를 만들어 `CommandExecutor`에 넘길 뿐 컨텍스트를 직접 만들 수 없다는 것이 의도이며, 새 배관은
  응용 계층이 정말 필요로 하지 않는 한 `internal`로 둔다.
- **의도적으로 미룬 누수 두 가지**(`DomainLayeringGuardTest` KDoc, `domain/AGENTS.md`): ① `CommandDetailType` —
  도메인 enum이지만 값 `name`이 아웃박스 컬럼·버튼 값·`private_metadata`에 직렬화되어 Slack까지 나간다. 이름을 바꾸면
  로컬 DB 리셋이 필요하고 이미 게시된 버튼이 `valueOf`에서 깨진다. ② Slack 사용자/팀 id를 업무 식별자로 쓰는 것 —
  문서는 `slackUserId`/`slackTeamId`라 부르지만 현재 소스에 그 이름은 없고 `actorId`/`teamId`/`publisherId` 같은
  중립 이름 뒤에 Slack id가 그대로 들어 있다. 둘 다 가드 대상이 아니며, 지나가는 길에 "고치지" 말라는 것이 합의다.

## 8. 함정

- **조용한 실패**: 컨텍스트가 만들어지기 전의 예외(`SubCommandParseException` — `/meetup foo`,
  `IllegalArgumentException("Command Queue is empty")` — 토큰 없는 멘션, `UnSupportedCommandException`)는
  `CommandOutput.fail`로 흡수될 뿐 아웃바운드를 하나도 남기지 않고, 슬래시 서비스는 반환값을 버리므로 사용자는 아무
  답도 받지 못한다. 사용자에게 보여야 할 오류는 컨텍스트 안에서 `createErrorResponse`로 내라.
- 파서의 `CommandDetailType.valueOf`는 알 수 없는 토큰에 예외 → enum 리네임 후 남은 옛 버튼은 500. 미매핑은 무동작.
- `LEGACY_AUTO_REJECT_TYPES`(`APPLY_REQUEST`, `APPROVAL_REQUEST`)의 거절 버튼은 핸들러 수준에서 "Canceled."로
  대체된다. 새 타입을 여기에 넣지 말고 각 `ReactionContext`가 자기 거절을 처리하게 한다.
- `SlackMentionEventHandlerImpl.parseAppMentionEvent`는 `payload["channel_name"]`/`payload["user_name"]`을 읽지만
  Events API `app_mention` 페이로드에는 그 키가 없다(`SlackEventCallBackRequest`에도 필드 없음). 결과 `actorName`/
  `channelName`은 문자열 `"null"`이 되어 `AgentConversePayload`를 통해 에이전트 프롬프트에 들어간다(코드에 `FIXME`).
- `IdempotencyCreator.create(InboundCommand)` = 페이로드 JSON SHA-256 + **1초 창** 시드. 같은 초의 재전송만 같은 키다.
- 죽었거나 반쯤 죽은 조각(본보기로 삼지 말 것): `OutboundMessage.DirectMessage`(생산자 없음, 렌더러는 `error()`),
  `EphemeralTextResponseContext`/`DetailErrorAlertContext`(테스트에서만 생성), `/api/slash/task`(파싱 후 폐기),
  `controllers/dto/*`, `InteractionCommand.appName`, `InboundCommand.teamId`, `containsExternalEvent`(호출처 없음).

## 근거

- `domain/src/main/kotlin/dev/notypie/domain/command/**`(`AGENTS.md` 포함), `domain/AGENTS.md`,
  `domain/src/test/kotlin/dev/notypie/domain/architecture/DomainLayeringGuardTest.kt`
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/**`(`SlackInboundMapper`, `SlackIntentResolver`,
  `SlackOutboundStager`, `OutboundRenderer`, `SlackInteractionRequestParser`, `SlackViewOpenDispatcher`,
  `slack/*`, `event/*`), `impl/AGENTS.md`, `infrastructure/.../repository/outbox/OutboundMessagePort.kt`
- `application/src/main/kotlin/dev/notypie/application/` — `controllers/*`, `socket/*`, `common/*`,
  `configurations/{AppConfig,SlackRequestBuilderConfiguration,ConsumerConfig}.kt`,
  `service/{command,mention,interaction}/*`, `service/meeting/MeetingServiceImpl.kt`,
  `service/standup/StandupSlashServiceImpl.kt`, `service/cve/{query,subscription}/*SlashServiceImpl.kt`,
  `service/relay/{SlackMessageRelayServiceImpl,OutboxPayloadRenderer}.kt`, `service/agent/*`
- `README.md` "Bot Commands & Roles"; `Refactor.md`(git 미추적) §1, Phase 3, Phase 4, Phase 10 — 설계 의도의 출처

## 관련 페이지

- [architecture-overview.md](architecture-overview.md) — 세 모듈의 책임과 요청이 흐르는 길
- [ddd-layering.md](ddd-layering.md) — 전송 중립성을 어디까지, 왜 강제하는가
- [events-and-outbox.md](events-and-outbox.md) — 아웃박스 행 이후의 relay·멱등성·CAS
- [error-handling-and-validation.md](error-handling-and-validation.md) — `CommandException`·`exceptionDetails`
- [testing-guide.md](testing-guide.md) — `AbstractCommandContextTest`, testFixtures 입력 빌더
- [decisions.md](decisions.md) · [history.md](history.md)
- [`domain/command/AGENTS.md`](../../domain/src/main/kotlin/dev/notypie/domain/command/AGENTS.md) ·
  [`impl/AGENTS.md`](../../infrastructure/src/main/kotlin/dev/notypie/impl/AGENTS.md) ·
  [`controllers/AGENTS.md`](../../application/src/main/kotlin/dev/notypie/application/controllers/AGENTS.md) ·
  [`service/AGENTS.md`](../../application/src/main/kotlin/dev/notypie/application/service/AGENTS.md)
