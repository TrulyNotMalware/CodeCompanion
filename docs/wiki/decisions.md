# 기술 결정 기록

_type: decision · updated: 2026-10-26_

> 근거가 있는 결정과 2024-06부터의 연혁(마일스톤·설계 전환점)·폐기 목록을 남기며, 결정이 뒤집히면 항목을 지우지
> 말고 상태를 바꾸고 이력을 덧붙인다.

형식 — **결정** / 이유 / 상태(`유지` · `열림` · `폐기`) / 근거. 날짜는 확인 가능한 것만 적는다.

## 아키텍처

1. **세 모듈 계층을 Gradle 컴파일 단위로 강제한다.** `domain`은 의존성 0, 가드 테스트가 전송·직렬화 결합을
   막는다. — 이유: 도메인 순수성을 사람의 주의력이 아니라 빌드에 맡기기 위해. 상태: 유지.
   근거: `domain/build.gradle.kts`, `DomainLayeringGuardTest.kt`. → [ddd-layering.md](ddd-layering.md)
2. **전송 중립성은 점진적으로, 단계마다 자체 이득이 있을 때만.** 멀티 전송(Discord)은 장기 로드맵에 있지만
   구현 대상은 아니므로 빅뱅 이식성 작업을 하지 않는다. 상태: 유지(Phase 0~6 완료, 2026-06~07).
   근거: `Refactor.md` 머리말·§2. → [연혁](#연혁--마일스톤과-전환점)
3. **도메인이 아웃바운드 포트와 중립 모델을 소유하고, 인프라가 어댑터를 소유한다. 포트는 staging이며 eager IO가
   아니다.** 이유: eager HTTP는 `BEFORE_COMMIT` 아웃박스 저장을 우회해 전달 보장을 깬다. 상태: 유지.
   근거: `Refactor.md` Phase 3 "포트 소유권", `domain/command/outbound`.
4. **리포지토리 인터페이스는 `infrastructure/repository/<lane>/`에 둔다(도메인이 아님).** 이유: 도메인이 영속성
   계약을 알아야 할 이유를 납득하지 못함. 상태: **열림** — 영속성이 도메인의 관심사인지는 결론 미정. 결론이
   나면 여기와 [ddd-layering.md](ddd-layering.md)를 갱신한다. 근거: `infrastructure/repository/**`,
   `infrastructure/src/main/kotlin/dev/notypie/configurations/AGENTS.md`.
5. **`Command`/`CommandContext`는 "명령 실행" 도메인일 뿐, 도메인 로직은 엔티티에 둔다.** 상태: 유지.
   근거: `Refactor.md` §5-6.
6. **Slack 렌더링은 전달 시점에 단 한 번(`SlackOutboundRenderer`). 모달만 예외로 동기 렌더.** 이유: 아웃박스는
   전송 중립으로 저장하고, `trigger_id`는 약 3초 만에 만료된다. 상태: 유지. 근거: `infrastructure/AGENTS.md`.
   → [events-and-outbox.md](events-and-outbox.md)
7. **의도적으로 남긴 누수 — Slack 사용자/팀 id를 업무 식별자로 쓰는 것, `CommandDetailType` 라우팅 enum.** 이유:
   Slack 전용 제품의 자연키이고, enum은 두 번째 전송이 실제로 시작될 때 스테이저 안으로 내리는 것이 싸다.
   아웃박스 wire 포맷은 한때 이 목록에 있었으나 Phase 8b(V11)에서 중립 envelope로 해소됐다. 상태: 유지.
   근거: `Refactor.md` 잔여 항목·Phase 8b, `DomainLayeringGuardTest.kt` KDoc, `OutboundMessagePort.kt`.

## 이벤트와 전달

8. **트랜잭셔널 아웃박스 + `BEFORE_COMMIT` 스테이징.** 이유: Slack API 호출과 DB 쓰기를 원자적으로 묶을 수
   없으므로, 효과를 먼저 행으로 남기고 릴레이가 전달한다. 상태: 유지. 근거: `README.md` EDA 항목,
   `infrastructure/repository/outbox/**`. → [events-and-outbox.md](events-and-outbox.md)
9. **릴레이 두 모드 — Debezium CDC → Kafka(`local`/`dev`/`prod`) / 폴링 + `application_event`(`slack-live`).** 이유:
   실기 워크스페이스 e2e를 Kafka·Debezium 없이 돌리기 위해. 코드 기본값은 폴링이라 키를 생략한 프로파일은 CDC를
   읽지 않는다. 상태: 유지. 근거: `RealTestSetup.md`, `application-*.yaml`의 `slack.app.mode.*`, `AppConfig.Mode`.
10. **다중 인스턴스 안전은 공유 락(ShedLock 등) 없이 DB 행 CAS 세 가지로.** ① 작업항목별 claim-token CAS
    ② 윈도우당 1회 `INSERT IGNORE` 레저 ③ once-only stamp CAS. 이유: 외부 락 인프라 없이 멱등성을 얻는다.
    상태: 유지. 근거: `CveBotPlan.md` §0.5-4, `repository/{standup,meeting,cve}` CAS 쿼리.
11. **아웃박스 `idempotency_key`는 인덱스일 뿐 unique가 아니다.** 재발송 방지는 기능별 unique(예: CVE
    `deliveries(event_id, user_id)`)가 맡는다. 상태: 유지. 근거: `CveBotPlan.md` §0.5-3.
12. **멱등성 키는 인바운드 데이터 + 1초 창의 결정적 UUID.** 이유: Slack 재전송을 같은 키로 접는다. 창을
    바꾸면 아웃박스·Kafka·Slack 재시도 의미가 바뀐다. 상태: 유지. 근거: `IdempotencyCreator.kt`.
44. **슬래시 명령의 실패는 원자적이다 — 효과 전부, 아니면 오류 안내만 (2026-10-15).** `Command.handleEvent()`가 슬래시
    payload의 예외를 잡으면 오류 ephemeral을 넣기 **전에** `intents.clear()`로, 컨텍스트가 던지기 전에 쌓은 intent·outbound를
    버린다. 이유: 이전에는 그 효과가 그대로 drain·발행되고(`handleEvent`가 예외를 삼키므로 슬래시 트랜잭션도 롤백되지 않는다)
    그 옆에 "Please try again."이 붙어, 사용자가 다시 시도하면 같은 효과가 두 번 일어날 수 있었다. 지금은 쌓은 뒤 던지는
    컨텍스트가 없어(2026-10-15 트리아지와 리뷰의 대조) 잠복한 경로를 미리 막은 것이다. 원자성은 executor에 넘어가는 큐의 내용에 대한
    것이다 — 그 뒤의 스테이징·발행·Slack 전달은 이전처럼 따로 실패할 수 있다. **멘션·인터랙션은 의도적으로 범위 밖이다** —
    사용자는 B7을 슬래시 한정으로 승인했고, 두 경로는 쌓인 효과를 그대로 남긴다(`CommandTest`가 둘 다 고정). 다시 시도하라는 안내는
    없지만 답이 없는 버튼 실패도 재클릭을 부를 수 있어 같은 위험이 잠복해 있다. 인터랙션까지 넓히면 `OpenModal`을 먼저 쌓는 버튼
    핸들러(`entity/context/form/AGENTS.md`의 "Effect order is load-bearing")가 그 뒤에 던질 때 모달도 사라지므로, 쌓은 뒤 던지는 인터랙션
    컨텍스트가 생기면 그 순서와 함께 다시 본다. 기각: 부분 효과를 남기고 문구만 바꾸기. "부분 성공"을 알리고 싶은 컨텍스트는 던지지
    말고 결과를 돌려주되(`createErrorResponse`), 이미 일어난 일을 다시 하라고 권하면 같은 중복을 부르므로 문구는 무엇이 됐고 무엇이
    안 됐는지를 말한다. 상태: 유지. 근거: `domain/.../command/entity/AGENTS.md`, `CommandTest`("a command whose context queues an
    intent and then throws").

## 권한과 AI

13. **역할 계층 `user ⊂ ai_user ⊂ developer ⊂ admin`, 멘션 경로만 게이트(보완: 2026-10-12부터 `/ask`도).** 슬래시·인터랙션은 BASIC 전용이라
    게이트하지 않는다. 설정의 `bootstrap-admins`는 불변(채팅으로 grant/revoke 불가)이며 DB row보다 우선.
    이유: 부트스트랩 순환 문제 해소. 상태: 유지(2026-07-07, Phase 10). 근거: `Refactor.md` Phase 10,
    `README.md` Bot Commands & Roles. **보완(2026-10-12):** 슬래시도 명령별로 게이트할 수 있다 — `/ask`는
    `AskSlashService`가 `CommandRoleResolver`로 역할을 해석해 도메인 `AskCommand`에 넘기고, `AskSlashContext`가 `AI` 권한을
    검사한다(거부 문구는 멘션 경로와 동일). 그 밖의 슬래시·인터랙션은 그대로 BASIC이다. 근거: `command-pipeline.md` §5, 결정 #38.
    **명확화(2026-10-26):** 인터랙션 경로도 2026-10-06(#26, `f9971700`)부터 `SlackInteractionHandlerImpl.buildCommand`가
    `CommandRoleResolver`로 실제 역할을 해석해 `InteractionCommand`에 넘긴다 — `UserRole.USER` 고정이 아니다. 다만
    `InteractionCommand`는 그 역할을 멘션 페이로드의 `AppMentionContextParser`에만 주고 `InteractionContextParser`에는 주지 않으므로
    인터랙션을 게이트하지 않는다는 결정은 그대로다. 근거: `SlackInteractionHandlerImplTest`("the actor's role is resolved from the
    repository instead of being assumed USER"), `InteractionCommand.kt`(`resolveRoute`), 리뷰 E-07(2026-10-23).
14. **MCP 서버는 사이드카가 아니라 앱 안에.** 이유: `CommandRoleResolver`·스테이저·기존 서비스를 재사용하고
    사이드카는 도메인 무지로 유지. 바인드는 루프백 전용 — 판정은 소켓의 `remoteAddr`로 하므로 `server.forward-headers-strategy: none`을 고정하고, 루프백 전용 모드에서는 forwarding 헤더가 붙은 요청을 거부한다(2026-10-01, Kubernetes에서 Boot가 `X-Forwarded-For`를 신뢰해 위조한 `127.0.0.1`이 통과할 수 있었음). 상태: 유지(1차 2026-07-13). 근거: `Refactor.md`
    "후속 플랜 — Agent lane MCP".
15. **턴 단위 스코프 토큰 + 호출 시점 역할 재-resolve.** 이유: 사이드카의 사용자 사칭 방지, 대화 중 revoke
    즉시 반영. 채팅 명령과 **같은 `CommandPermission` 상수**를 공유해 두 경로의 drift를 막는다. 상태: 유지.
    2026-10-02: `CommandRoleResolver`의 60초 캐시는 `USER`(행 없음) 결과만 담는다. 상승 역할은 매 호출 DB에서
    읽으므로 revoke는 레플리카와 무관하게 다음 호출에 반영되고, grant만 다른 레플리카에서 최대 60초 늦을 수 있다(거부
    쪽으로만 틀림). 이전에는 ADMIN도 캐시돼 축출이 커밋한 레플리카에서만 일어났고, 회수된 관리자가 다른 레플리카에서
    60초 안에 스스로 재부여할 수 있었다. 근거: `service/command/AGENTS.md`.
16. **MCP 1차는 조회 도구만. 쓰기 도구는 미리보기+확인 플로우·멱등성·rate limit 이후.** 이유: 프롬프트
    인젝션 — 스레드의 타인 텍스트가 모델을 조종할 수 있으므로 권한은 항상 멘션 작성자 기준. 상태: 유지
    (2차 미착수). 근거: `Refactor.md` MCP 설계 결정 4.
17. **키워드 파서는 유지하고 자연어는 추가 경로로만.** 이유: 결정적 명령은 빠르고 예측 가능해야 하며 LLM
    대체는 비용·지연·비결정성 문제. 상태: 유지.
18. **CVE 봇은 새 저장소가 아니라 이 앱의 기능으로.** MariaDB·아웃박스·CAS·모달 파이프라인을 재사용. 이유:
    별도 앱은 DB·Slack 수신·DM 발송을 전부 중복 구현하게 된다. 상태: 유지(M1~M6 완료 2026-07-14).
    근거: `CveBotPlan.md` §0.

## 외부 연동

36. **Google Calendar 연동은 사용자별 OAuth 미러다 (2026-10-07~08).** 사용자가 `/calendar connect`로 자기 Google
    계정을 연결하면 봇이 그 사용자의 `primary` 캘린더에 미팅 사본을 둔다. 호스트의 이벤트는 미팅 생성 시, 참석자의 이벤트는
    슬랙에서 Approve를 누른 시점에 생기고, 거절·취소는 삭제, 일정 변경은 patch다. 출석의 원본은 슬랙 하나이고(캘린더에서 바꾼
    응답은 슬랙으로 돌아오지 않는다) 이메일 매핑·추가 Slack scope가 없으며, 연결하지 않은 사용자는 아무것도 받지 않는다. 기각:
    초대 메시지에 캘린더 추가 링크 삽입(요구는 "자동으로 연동되는 것"), 공용 팀 캘린더, Workspace 도메인 전체 위임. 뒤의 둘을
    기각한 사유는 기록되지 않았다(미확인) — 차이만 적으면 공용 캘린더는 참석 여부와 무관하게 모두가 일정 하나를 공유하고, 위임은
    Workspace 관리자 설정과 한 도메인의 계정을 전제한다. 상태: 유지(`3c0529bf`·`6ae0a047`; `main`은 squash 머지라 원본 커밋은 `archive/gap-closure` 태그에서 도달).
    근거: `service/calendar/AGENTS.md`, `V24__add_google_calendar_tables.sql` 헤더.
    - **명령 표면 (2026-10-08 변경):** 자체 슬래시 명령 `/calendar connect | disconnect | status`(`POST /api/slash/calendar`,
      Socket Mode는 `slack.app.socket.calendar-command`)이고 `/meetup calendar …`는 없앴다(이제 모르는 서브커맨드). 이유(사용자):
      캘린더 연결은 미팅 전용이 아니며 나중에 스탠드업 등 다른 것도 캘린더에 둘 수 있다. 처음(10-07)의 "`/meetup` 서브커맨드라 새
      슬래시 명령·manifest 항목이 없다"는 결정은 이것으로 대체됐다 — 운영·개발 Slack 앱에 `/calendar` 명령을 추가해야 한다.
    - **같은 Google 계정을 연결한 두 Slack 사용자 (2026-10-11 수용):** 봇의 revoke 판단(워커의 재조회, `SCOPE_DENIED` 검사)은
      요청한 Slack 사용자의 연결만 본다. Google의 revoke는 그 계정·클라이언트의 grant 전체를 취소하므로, 한 사람의 disconnect·
      다른 계정으로의 교체·`SCOPE_DENIED` revoke가 상대의 grant도 취소하고, 상대는 다음 동기화의 토큰 갱신에서 `invalid_grant` →
      `REVOKED` DM을 받아 재연결한다(Google 쪽 동작은 문서 기준이고 실측은 (미확인)). 이유(사용자 판단): 한 도메인 계정 전제라
      수용. 지적: Codex 교차 리뷰(2026-10-10). 근거: `service/calendar/AGENTS.md`.
    - **신원 확인 추가 (2026-10-13):** 위의 "이메일 매핑·추가 Slack scope가 없다"는 결정 #41로 바뀌었다 — 연결은 Google 계정의
      검증된 이메일이 요청자의 슬랙 이메일(`users.info`)과 같을 때만 되고, 봇 scope `users:read`·`users:read.email`이 필요하다.
    - **같은 Google 계정 문제는 #41 이후 새로 생기지 않는다 (2026-10-15):** 위 수용 항목은 #41로 사실상 대체됐다. 연결은 계정의
      검증된 이메일이 요청자의 슬랙 이메일과 같을 때만 되므로(`CalendarConnectionService.completeConnection`), 두 슬랙 사용자가
      같은 Google 계정을 새로 연결하는 경로는 막혔다(한 워크스페이스에서 슬랙 이메일이 사용자마다 다르다는 전제는 슬랙 동작이고 여기서
      검증하지 않았다). 이 대체는 슬랙 이메일이 사용자마다 유일하고 안정적이라는 전제에 기댄다. 남는 것은 #41 이전에 저장된 행(한계 ④)과, 이메일이
      나중에 바뀌거나 다른 사람에게 재할당된 경우다 — 사용자가 직접 바꾸는 이메일(확인을 거친다)도, 관리자·SCIM이 넣는 임의 주소도
      가능하고(한계 ②), 기존 연결은 다시 검사하지 않으므로(한계 ④) 같은 상황이 다시 생길 수 있다.
      "같은 `google_subject`의 활성 연결이 있으면 revoke를 생략"하는 가드는 만들지 않는다.
    - **만료 state 정리 (2026-10-21):** V24 헤더가 적은 "콜백이 성공한 consume의 트랜잭션에서 만료 state를 지운다"는 결정 #51로
      바뀌었다 — 매시간 `GoogleOAuthStateRetentionScheduler`가 지우고, 콜백은 consume만 한다.
37. **미러는 아웃박스 `Transport`가 아니라 전용 더티 마커 테이블 + CAS 스케줄러로 at-least-once.** `meeting_calendar_event`(V25)는
    (미팅, 사용자)당 한 행이고 "원하는 동작"이 아니라 "다시 보라"는 표시다. 훅이 미팅 쓰기 트랜잭션 안에서 행을 upsert/touch해
    `change_seq`를 올리면, 60초 워커가 claim(CAS) 뒤 미팅을 다시 읽어 이벤트가 있어야 하는지 계산하고 Google을 호출한다. 완료
    CAS는 `change_seq`가 claim 때와 같을 때만 `SYNCED`, 아니면 `PENDING`이라 호출 중 들어온 취소·일정 변경이 다시 동기화된다
    (리마인더와 같은 결정 #10의 claim-token 패턴). 이유: Google 호출은 미팅 쓰기 트랜잭션 안에서 할 수 없고, 큐에 적힌 동작을
    믿으면 취소와 수락이 엇갈려 커밋될 때 취소된 미팅의 이벤트가 남는다. 상태: 유지(2026-10-08). 근거:
    `V25__add_meeting_calendar_event.sql` 헤더, `repository/calendar/AGENTS.md`, `service/calendar/AGENTS.md`
    (`CalendarSyncService`, `CalendarConnectionService`).
    - **이벤트 id는 클라이언트가 정한다:** SHA-256(`"$meetingUid:$slackUserId"`)의 소문자 hex 64자(Google의 base32hex id 알파벳
      안). insert가 멱등이 되어(409 → patch) stuck 리셋·크래시·완료 기록 실패가 중복 이벤트를 만들지 않고, 응답을 잃은 insert의
      이벤트도 같은 id로 지울 수 있다. 사용자까지 넣는 이유: 두 Slack 사용자가 같은 Google 계정을 연결하면 미팅 uid만으로는 한
      사람의 거절이 다른 사람의 사본을 지운다.
    - **연결 시 백필은 호스트 미팅만:** 진행 중이거나 다가오는 미취소 호스트 미팅은 `enqueue`하고, 참석 미팅은 이미 있는 큐 행만
      되살린다(`touchForUser`, `FAILED` 포함). `meeting_participants.is_attending`이 초대 직후 기본 `true`라 "수락"과 "미응답"을
      구분할 수 없어서, 참석 미팅까지 넣으면 Approve 전의 초대가 미러된다. 응답 시각 컬럼(`responded_at`, 새 마이그레이션)은 첫
      연결의 백필까지 가능하게 하지만 보류한 후보다.
    - **claim을 잃은 쓰기는 재큐잉한다 (2026-10-08):** 운영은 레플리카 2개라 한 워커가 미팅을 읽은 뒤 stuck 임계보다 오래 멈추면
      다른 워커가 행을 가져가 더 새로운 상태로 정리하고, 늦게 도착한 쓰기가 그것을 뒤집을 수 있다(고아 이벤트, 낡은 시각, 방금
      되살린 이벤트 삭제). 그래서 Google 쓰기를 보낸 뒤 자기 완료 CAS가 빗나가면, 사용자가 아직 연결돼 있을 때 그 (미팅, 사용자)를
      `enqueue`로 다시 표시하고 다음 패스가 미팅에서 원하는 상태를 다시 계산해 수렴한다. 결정적 id는 중복을 막을 뿐 낡은 쓰기는 막지 못한다.
    - **알려진 한계:** disconnect 뒤에도 이미 미러된 이벤트는 캘린더에 남는다(지울 토큰이 없다. disconnect는 `PENDING` 행만
      `FAILED`로 두고 나머지 행은 재연결 때 되살리려고 보존한다). 다른 계정으로 재연결하면 옛 계정의 사본도 남는다. 연결되지 않은
      동안(첫 연결 전, 또는 disconnect 뒤 재연결 전) 수락한 미팅은 큐 행이 없어 미러되지 않는다.
38. **`/ask`는 질문을 채널 메시지로 올리고 답은 그 스레드에 달며, 스레드 id는 아웃박스 전달 이벤트에서 얻는다 (2026-10-12).**
    슬래시 호출은 채널에 보이지 않고 `response_url`은 도메인에 들어오지 않으므로, `AskSlashContext`는
    `MessageContent.AgentQuestion`을 담은 `ChannelMessage`(detailType `AGENT_ASK_QUESTION`) 하나만 아웃박스에 넣는다. Slack이 그
    메시지를 받으면 relay가 발행하는 `MessageDeliveredEvent`의 `messageTs`를 `AgentAskThreadStarter`가 받아 아웃박스 행을 디코딩하고
    `AgentConverseRequestEvent(threadId = ts)`를 만들어 `AgentConverseService`에 넘기면, 그 뒤는 멘션 `ask`와 같은 턴이다(세션 키
    `channel:ts:user`라 그 스레드에서 멘션하면 이어진다). 기각: ① 동기 `chat.postMessage`로 ts를 먼저 얻기 — 모달 외 유일한
    아웃박스 우회가 되고 at-least-once를 잃는다; ② `response_url`을 도메인까지 전달 — 5회·30분 제한이고 스레드가 없다; ③ 사용자별 세션 키(`channel:user`) — 톱레벨
    멘션은 호출마다 새 스레드라 의미가 어긋난다. 알려진 한계: 봇이 없는 채널에서는 루트 메시지가 `not_in_channel`로 실패해 아무
    응답이 없다(슬래시 공통, 후속 3-1 → 2026-10-14 결정 #42: 거절되면 요청자 DM으로 안내, 턴은 시작하지 않음); 상태 쓰기를 잃으면 recovery가 같은 행을 reclaim해 루트 메시지를 **다시 게시**한다(수용) — 턴은
    질문(아웃박스 `event_id`)당 하나라 스레드는 ts가 레저에 먼저 적힌 게시물 하나에만 달린다. 상태: 유지. 근거:
    `service/agent/AGENTS.md`, `StandupSummaryService`의 `outbox:<eventId>` 치환 패턴, `.omc/plans/slash-slice1-ask-standup-answer.md`.
    - **전달 훅과 한계의 변경 (2026-10-13):** 처음(10-12)에는 relay가 터미널 상태를 기록한 **뒤**의 `MessagePublishSuccessEvent`가 턴을
      시작했고, 그 뒤는 메모리 안(리스너 → 턴 executor)이라 리스너의 DB 읽기 실패나 턴 전 재시작이면 질문만 남고 답이 오지 않았다
      (`@bot ask`도 턴 중 재시작이면 같지만 `/ask`는 전달 뒤 창이 하나 더 있었다. Codex 2026-10-12 HIGH, 당시 "영속 레저 + 재개 스윕은
      후속"으로 기록). 그 한계는 결정 #40으로 닫혔고, 훅이 상태 기록 **앞**의 `MessageDeliveredEvent`로 바뀐 것도 그 결정이다. 그때의
      "두 번째 게시물에만 스레드가 달린다"도 이제 위와 같이 바뀌었다.
39. **`/standup answer`는 서비스가 후보를 미리 조회해 도메인에 넘기고, 슬래시 기원 제출은 DM 갱신 대신 ephemeral로 확인한다
    (2026-10-12).** 도메인은 저장소를 못 보므로 `StandupSlashServiceImpl`이 루틴마다 후보 세션 하나를 골라 `StandupAnswerCandidate`로
    넘기고, 컨텍스트는 0개면 안내, 1개면 기존 `StandupFill` 모달, 여럿이면 이름을 요구한다(같은 이름이 여럿이면 DM 버튼으로 안내).
    선택 규칙: 요청자가 멤버인 활성 루틴에서, **멤버 로컬 날짜 d**(`member.userTimezone`)의 전날과 당일 세션 중 `COLLECTING`이고 컷오프
    전인 것을 모아, 둘이면 ① 그 멤버의 프롬프트가 이미 나갔고(`dispatch.dmTriggerAt <= now`) 미답변인 세션 → ② 미답변 세션 → ③ 더
    이른 날짜 순으로 하나를 고른다. 날짜를 루틴 타임존으로 잡지 않는 이유: 스케줄러가 멤버 DM을
    `LocalDateTime.of(sessionDate, trigger).atZone(member.userTimezone)`에 보내므로 멤버의 날짜가 곧 세션 날짜다(서쪽 멤버가 다음 날
    세션에 답하는 사고, 코드 리뷰 HIGH-1). 전날까지 보는 이유: `cutoffOffset`이 최대 1,440분이라 전날 세션이 자정을 넘겨 열려 있을 수
    있다(Codex HIGH). 프롬프트 전 조기 답변은 그 세션이 유일할 때만 허용된다. DM 알림이 없는 제출(`noticeMessageTs` 공백)은 `UpdateMessage` 대신 요청자에게 ephemeral로
    결과를 알린다. (2026-10-14: 그 ephemeral에는 결정 #42의 DM 폴백이 붙는다.) 이유: 기존 모달·제출 파이프라인(`STANDUP_PROMPT`/`STANDUP_ANSWER_SUBMIT`)을 그대로 쓰기 위해. 상태: 유지.
    근거: `service/standup/AGENTS.md`, `.omc/plans/slash-slice1-ask-standup-answer.md`.
40. **`/ask` 턴의 시작은 전용 레저 `agent_ask_turn`(V26)에 먼저 적고, 턴은 그 행의 lease 아래에서 돌며, 60초 스윕이 결과가 날 때까지
    다시 돌린다 (2026-10-13).** 결정 #38에 적어 둔 한계(전달 뒤가 메모리뿐)를 닫는다. 질문 하나에 행 하나이고 PK는 질문 아웃박스 행의
    `event_id`, `message_ts`가 답 스레드다. 프롬프트·요청자·채널은 복사하지 않고 턴마다 아웃박스 행에서 다시 읽는다. 턴의 결과는 넷 중
    하나다 — 답, 실패 안내, 과부하 안내, 포기 안내. 이유: 크래시·재시작·사이드카 busy·결과 트랜잭션 실패가 "질문만 남고 답이 없는" 상태로
    끝나지 않게 하되, 아웃박스의 상태 기록 경로에는 실패 지점을 더하지 않기 위해. 멘션(`@bot ask`) 턴에는 레저가 없다(아래 한계).
    상태: 유지. 근거: `service/agent/AGENTS.md`, `service/relay/AGENTS.md`, `repository/agent/AGENTS.md`,
    `V26__add_agent_ask_turn.sql` 헤더, git-ignored `.omc/plans/ask-turn-ledger.md`.
    - **write-ahead 순서:** relay는 디스패치 결과가 `ok`이면 상태를 쓰기 **전에** `MessageDeliveredEvent`를 동기 발행한다. 뜻은 "전송
      수단이 메시지를 받았고 행은 아직 기록되지 않았다"이고, 결과 이벤트(`OutboxUpdateEvent`)가 아니라서 뒤에 claim을 잃는 소유자도
      발행할 수 있다. 리스너 예외는 ERROR 로그로 남고 상태 쓰기는 그대로 진행된다. 리스너(`AgentAskThreadStarter.onDelivered`)는
      `AGENT_ASK_QUESTION`이고 ts가 있을 때만 아웃박스 행을 다시 읽고, 레저 행을 넣고, 턴을 executor에 넘긴다. 그래서 크래시가 레저
      insert 앞이면 아웃박스 행이 `IN_PROGRESS`로 남아 아웃박스 스윕이 질문을 다시 게시하고 그 전달이 레저를 쓰며, insert 뒤면 레저 행이
      남아 ask 스윕이 첫 게시물의 스레드에서 턴을 이어 간다. **기각: 레저 쓰기를 상태 기록 트랜잭션 안에 넣기**(연쇄 조각의 다음 행을
      넣는 방식) — 레저 실패가 `SUCCESS`를 롤백시켜 스윕이 질문을 다시 게시한다. 지금 순서에서는 레저 실패가 그 턴의 내구성만 잃는다
      (턴은 lease 없이 그대로 시작). 이 순서는 리스너가 발행 스레드에서 동기로 돌 때만 성립한다 — `@Async`·비동기 multicaster·성공
      이벤트 뒤로의 이동은 조용히 깨뜨리며, `ApplicationContextSmokeTest`가 "`publishEvent`가 돌아왔을 때 행이 있다"로 고정한다.
    - **상태 기계와 카운터 둘:** `PENDING → IN_PROGRESS → COMPLETED | ABANDONED`, 모든 전이는 관측한 `attempt_count`를 조건으로 건
      네이티브 UPDATE 하나다(결정 #10의 claim-token 패턴, 토큰은 아웃박스처럼 `attempt_count`). `begin`(PENDING → IN_PROGRESS)은
      `attempt_count`와 `run_count`를 함께 올리고, busy 반납 `release`는 `run_count`만 하나 돌려주며, 고아 회수 `releaseOrphan`과
      `finish`는 둘 다 건드리지 않는다. `attempt_count`는 소유권 토큰일 뿐이고 포기 예산은 `run_count`다 — 사이드카의 busy·429가 예산을
      쓰지 않게 하려는 분리다(아웃박스의 `attempt_count`/`send_count`와 같은 이유). `finish`의 조건은 `status IN ('PENDING',
      'IN_PROGRESS')` + 같은 attempt라서, 고아로 회수됐지만 아무도 다시 시작하기 전에 끝난 실행은 결과를 남기고, 다른 실행이 시작한
      뒤에는 진다. `recordDelivered`는 조회 없이 insert부터 하고 중복이면 저장된 행을 읽어 돌려주며, 이번 호출이 행을 넣었는지 이미
      있던 행을 찾았는지를 함께 알린다(`RecordedAskTurn.storedNow`). 트랜잭션 안에서 불리면
      `IllegalStateException`으로 거부한다(거부된 insert의 새 인스턴스가 영속성 컨텍스트에 남아 뒤의 조회가 저장된 행 대신 그것을
      돌려주기 때문). 두 시각 컬럼은 호출자의 `Clock`에서 온다.
    - **lease:** `AgentConverseService`는 턴마다 `AgentTurnLease`를 받는다. 멘션과 "레저 쓰기에 실패한 `/ask`"는 `Unleased`(아무것도
      쓰지 않음)다. lease가 있으면 사이드카 호출 전에 `begin()`(지면 호출 없이 끝), 답·실패 안내 트랜잭션의 마지막 쓰기로 `settle()`,
      busy면 `giveBack()`을 먼저 쓴다. lease 쓰기가 CAS에서 지면 예외를 던지므로 그 트랜잭션이 롤백돼, 턴을 잃은 실행은 아무것도
      게시하지 않는다. `giveBack()`은 그 실행이 빚진 안내를 `BusyNotice`로 돌려준다 — lease 없는 턴은 `CONVERSATION_BUSY`(기존
      `BUSY_MESSAGE`), lease 있는 턴의 **첫** 실행은 `ANSWER_LATER`(`ANSWER_LATER_MESSAGE`: "I'm busy right now — I'll answer your
      question in its thread as soon as I can."), 이후 실행은 `NONE`(조용히 반납만). `BUSY` 감사 행은 안내가 나갈 때만 함께 쓴다.
      문구를 나눈 이유(2026-10-13, Codex 교차 리뷰): 새 `/ask` 스레드에는 "이 대화의 이전 요청"이 없고, 이 ephemeral은 별도 아웃박스
      행이라 rate limit으로 보류되면 재개된 실행의 답보다 **늦게** 도착할 수 있다 — 새 문구는 진행 중이라고 말하지 않으므로 이미
      달린 답과 모순되지 않는다(보장은 아니다: 그 턴이 답 대신 실패 안내로 끝날 수는 있다). **행을 방금 넣은 전달**에서 executor가 가득 차면 과부하 안내와 `settle()`을 한 `REQUIRES_NEW` 트랜잭션에
      쓴다(실행 없이 `COMPLETED`). 이미 있던 행을 찾은 전달은 아래 스윕과 같은 `offer`다. executor가 이미 종료
      중이면(`ThreadPoolTaskExecutor`의 풀이 `isShutdown`) 안내 없이 행을 `PENDING`으로 두고, 종료 대기가 끝나 시작하지 못한 채 버려진
      큐의 턴도 같다 — 다음 기동의 스윕이 맡는다. 셀별 동작 표는 `service/agent/AGENTS.md`에 있다.
    - **스윕:** `AgentAskThreadStarter.resume()`이 60초 fixedDelay(첫 실행도 60초 뒤)로, 60초 넘게 기다린 `PENDING`과 `sidecar.
      requestTimeoutSeconds`(기본 120) + 60초 넘게 갱신되지 않은 `IN_PROGRESS`를 오래된 순으로 20건 읽는다. 고아 `IN_PROGRESS`는 먼저
      `PENDING`으로 회수하고, `run_count`가 5 이상이거나 전달 뒤 1시간이 지난 행은 실패 안내와 함께 `ABANDONED`로 끝내며(한 트랜잭션,
      감사 행 없음), 나머지는 executor에 `offer`한다. **스윕은 executor가 가득 찼다는 이유로 턴을 끝내지 않는다** — 거절된 `offer`는
      아무것도 쓰지 않고 다음 스윕이 다시 낸다. 이유: 운영은 레플리카 2개라 한쪽 스윕의 거절이 다른 레플리카가 큐에 넣은 턴이나 busy로
      반납된 턴을 닫으면 안 된다. **같은 규칙이 전달 경로에도 적용된다(2026-10-13, Codex 교차 리뷰 MEDIUM):** 과부하 안내로 턴을
      끝내는 것은 **이번 호출이 행을 넣었을 때뿐**이다. 이미 있던 `PENDING` 행(상태 쓰기를 잃어 다시 게시된 질문, 또는 같은 리스너
      호출 안에서 insert는 커밋됐는데 응답을 잃은 뒤의 재시도)은 `offer`로 넘기고, 거절되면 아무것도 쓰지 않은 채 스윕에 맡긴다.
      고치기 전의 순서: A가 전달·레저 기록 뒤 상태 쓰기 전에 죽고 → B의 스윕이 턴을 큐에 넣고 → 아웃박스 복구가 A에서 다시 전달 →
      A의 executor가 가득 차 과부하 안내 + `COMPLETED` → B의 큐에 있던 턴이 `begin`에서 진다. 첫 전달의 거절을 그대로 둔 이유는
      그것이 이 레인의 입장 제어이기 때문이다(가득 찬 executor는 새 질문을 한 시간까지 내구성 있게 쌓아 두지 않고 바로 돌려보낸다).
      그때는 안전하다 — 방금 넣은 행은 스윕을 통해서는 다른 레플리카의 큐에 있을 수 없다. 스윕은 `ASK_PENDING_GRACE`(60초)보다 오래된
      `PENDING`만 읽는다(레플리카 시계가 그보다 훨씬 작게 어긋난다는 전제). 행마다 실패를 따로 가두므로(ERROR 로그) 한 행의 예외가 배치의 나머지를 막지 않는다. 상수는
      `AgentAskThreadStarter.kt`의 `ASK_*`이고 YAML 키는 없다. 이 JVM의 큐에 이미 있는 턴은 메모리 집합 `queued`로 건너뛴다(레플리카
      사이에는 보이지 않으므로 두 레플리카가 한 턴을 각자 큐에 넣을 수 있고, `begin` CAS가 하나만 통과시킨다).
    - **시간 예산:** 리스너는 relay의 `renewClaim` → `completeClaim` 사이, 디스패치 스레드에서 돈다. 정상 경로는 커넥션 획득 2회(아웃박스
      재조회, 레저 insert), 행을 방금 넣은 전달에서 턴이 거절되면 `REQUIRES_NEW` 안내로 1회 더. 최악은 10회다 — 재조회 3회, insert
      3회와 중복 뒤 저장 행 조회 3회, 안내 1회(이 경로의 안내는 기록이 세 번 다 실패한 뒤 lease 없는 폴백이 거절될 때의 것이다. 행을
      찾은 전달은 안내를 쓰지 않는다). 재시도 루프 둘의 백오프는 각 0.33초, 합 0.66초다. ask 행에 대해 `service/relay/AGENTS.md`의 (a)는
      16 × `connection-timeout` + 0.99초(≈ 81초), (b)의 SQL 쪽은 13 × `connection-timeout` + 0.99초(≈ 66초)가 된다
      (`connection-timeout` 5초 기준의 산술이며 실측은 아니다. 5초는 `local`·`dev`·`slack-live` YAML의 값이고 `prod`는 환경 변수가
      정한다). **`RELAY_RECORD_TIME_BOUND`(45.99초)와 `RECORD_SHUTDOWN_WAIT`(46초)는 바꾸지 않는다**: 리스너의 DB 시간과 재시도
      백오프는 커밋·연쇄 조각 insert처럼 그 상한 밖에 둔다. 넘겼을 때의 결과도 같다 — 상태가
      기록되지 않아 스윕이 질문을 다시 게시하지만, 레저 키가 아웃박스 `event_id`라 턴은 하나다. 리스너의 재시도 횟수는 relay의
      `STATUS_WRITE_ATTEMPTS`(3)를 일부러 공유한다(레저 쓰기가 뒤따르는 상태 쓰기만큼 시도된다. 올리면 두 루프가 함께 길어진다).
    - **메트릭:** `agent.turns{outcome}`에 `resumed`(스윕의 `offer`가 받아들여진 수)와 `abandoned`(커밋된 포기, 안내 유무 무관)가
      더해졌다. lease가 있는 턴의 busy는 안내가 없는 실행에서도 `busy`를 올린다. executor가 종료 중이라 거절된 턴은 `rejected`가 아니라
      `discarded`로 세고, 종료 때 `PENDING`으로 남긴 `/ask` 턴은 어느 쪽으로도 세지 않는다.
    - **남은 한계:** ① 리스너의 아웃박스 재조회가 세 번 모두 실패하고 뒤의 상태 쓰기가 성공하면 그 턴은 여전히 사라진다(질문은
      게시됐고 레저 행은 없다). ② lease 없는 폴백은 한 스레드에 두 번 답할 수 있다 — 레저 insert가 커밋됐는데 그 응답을 잃고 같은 리스너
      호출의 나머지 시도도 실패한 경우, lease 없는 턴이 답하고 60초 뒤 스윕이 저장된 행을 다시 돌린다. ③ (2026-10-14 해소) lease를 잃은 실행은 결과
      트랜잭션 실패와 같은 ERROR로만 남고 메트릭은 그 실행을 `completed`로 셌다 → lease 쓰기가 `AgentTurnLeaseLostException`을 던지고, 다섯 결과
      트랜잭션(`publishAnswer`·`publishBusy`·`publishFailure`·`publishOverloaded`·`abandon`)은 그 예외만 WARN "superseded"(스택 없음)로 남기며
      `recordMetrics`는 `outcome=superseded`로 센다(토큰은 사이드카가 실제로 썼으므로 `agent.tokens`에 그대로 센다; 다른 실패는 예전처럼 ERROR·원래 outcome).
      ④ (2026-10-14 해소) 스윕의 디코드에는 relay의 `OutboxSchemaVersion.SUPPORTED` 검사가 없어, 디코드에 실패한 행은 안내 없이 즉시
      `ABANDONED`가 됐고, 이 바이너리가 모르는 버전의 행은 검사 없이 디코드됐다(jackson-databind 3.2.3은 `FAIL_ON_UNKNOWN_PROPERTIES`가 기본 false이고
      코덱도 켜지 않으므로, 모르는 필드는 조용히 버려진다 — 예: V6 질문 행을 V6 이전 바이너리가 `fallback` 없이 디코드해 답한다. 이 수정은 과거
      바이너리를 바꾸지 못하므로 그 롤백 시나리오 자체는 그대로다) → 이 바이너리부터는 스윕과 전달 리스너가 디코드 **전에** `row.schemaVersion in
      SUPPORTED`를 보고, 미지원 버전·`OutboundMessageCodecException`인 행은 포기하지 않고 스윕마다 ERROR만 남기며 쓰기·집계 없이 보류하다가
      포기 조건(5회/1시간)에서 안내 없이 `ABANDONED`(CAS가 이겼을 때만 ERROR·집계; 스레드 안내는 채널이 읽을 수 없는 행에만 있어 만들 수 없고, 아웃박스 행의
      `publisherId`로 DM을 보내는 것은 별도 기능이다). 행이 없거나 질문이 아닌 내용이면 예전처럼 즉시 `ABANDONED`.
      이것은 relay와 같은 트레이드오프다: 더 새 버전의 행은 그 버전을 아는 레플리카가 돌리도록 기다리게 하는 대신(롤아웃 중), 1시간 넘게 새 레플리카가
      돌아오지 않으면 예전 같으면 필드를 버리고라도 답했을 질문이 안내 없이 포기되고, 디코드 자체가 안 되는 행은 즉시 포기되는 대신 1시간을 기다린다. ⑤ (2026-10-15 해소) 진행할 수 없는
      행(이 JVM 큐에 있는 턴, 거절된 offer, 안내 쓰기가 실패한 포기, 읽을 수 없는 질문 행은 최대 1시간)은 `updated_at`이 가장 오래돼 20건 배치의 앞자리를 계속 차지한다
      → 오래 붙잡는 것은 읽을 수 없는 질문 행뿐이다(큐의 턴은 곧 `begin`하고, 거절된 offer는 executor가 가득 찼다는 뜻이라 뒤의 행도 못 들어가며,
      안내 쓰기 실패는 DB 장애라 어차피 전부 막힌다). 그래서 스윕은 직전 스윕에서 읽을 수 없던 행 수만큼 다음 읽기를 넓힌다 —
      `limit = ASK_RESUME_BATCH_SIZE + min(그 수, ASK_UNREADABLE_LOOK_AHEAD_MAX)`(상한 80행 추가, 한 스윕에 최대 100행). 그 수는 이 JVM의
      메모리(`@Volatile`)에만 있고, 그 행들이 포기되거나 읽을 수 있게 되면 0으로 돌아온다. look-ahead 때문에 읽을 수 없는 행에 새로 쓰는 것은
      없다 — 기다리는 `PENDING` 행은 포기 조건 전까지 그대로 두고 스윕마다 ERROR 하나만 남긴다. 다만 고아 `IN_PROGRESS` 행은 질문을 읽기
      **전에** `releaseOrphan`으로 `PENDING`이 되며 `updated_at`이 바뀌고, 포기 조건에서는 `ABANDONED`로 쓰인다(둘 다 이전부터의 동작). 남는 것:
      재시작 직후 첫 스윕은 다시 한 배치만 읽는다(다음 스윕부터 넘어간다); 한 스윕은 최대 100행만 읽으므로 맨 앞의 100행이 모두 읽을 수 없는
      행이면 그 뒤의 행은 포기 시점까지 막힌다(81개면 남은 19자리로 뒤의 행에 닿는다); 넓힌 스윕은 그 사이 앞의 행이 다른 곳에서 끝났다면
      20건 넘게 offer할 수 있다(executor의 bounded 큐가 넘치는 것을 거절한다); look-ahead는 포기 기한을 늦추지 않으므로 늦게 닿은 읽을 수 있는
      턴은 이미 1시간이 지나 포기될 수 있다.
      지금은 잠복한 문제였다 — 이 바이너리가 쓰는 최고 버전이 V6이고 `SUPPORTED`가 V2~V6이라 읽을 수 없는 행은 미래 V7 행을 남긴 롤백(2026-10-20 결정 #50으로 V7이 생겨 이제 실제 롤백 비용이다)이나
      디코드할 수 없는 payload(`OutboundMessageCodecException`, 같은 코덱이 썼으므로 드물다)에서 생기고,
      prod는 지금 `strategy: Recreate`라 혼합 플릿이 없다 — 다만 그 블록은 V20 전환용 임시 설정이다(`k8s/deployment.yaml` 주석: "For this release only",
      후속 PR이 지운다). 그 뒤에는 롤링 배포마다 잠깐 혼합 플릿이 생기므로, 아래 touch 기각의 근거가 실제 상황이 된다. **기각: `updated_at` touch**(B-1 계획의 "touch 없음" 결정을 유지) — 읽을 수 있는지는 행이 아니라
      (행, 바이너리)의 성질이다. 혼합 플릿에서 읽지 못하는 레플리카가 스윕마다 touch하면, 위상이 어긋난 읽을 수 있는 레플리카의 스윕 시점에 그 행은
      늘 `ASK_PENDING_GRACE`(60초)보다 새것이라 집히지 않고, 1시간 뒤 읽지 못하는 쪽이 안내 없이 포기한다(touch가 없으면 읽을 수 있는 레플리카가 다음
      스윕에 재개한다). `next_attempt_at` 컬럼(같은 공유 상태 결함에 마이그레이션까지), 고정된 넓은 스캔(임계가 20에서 100으로 옮겨 갈 뿐),
      로그한 행을 `NOT IN`으로 빼기(스윕마다 ERROR가 사라지고 빈 목록을 따로 다뤄야 한다)도 기각. 근거: `AgentAskThreadStarter.kt`,
      `AgentAskThreadStarterTest`(배치보다 많은 읽을 수 없는 행 뒤의 읽을 수 있는 턴, 상한 80), `service/agent/AGENTS.md`.
      ⑥ 첫 실행이 중단되고 뒤의 실행이 busy면 busy 안내가 나가지 않는다 — 첫 실행의 안내 트랜잭션이 롤백된 경우도 같다(고아 회수
      뒤의 실행은 lease의 두 번째 실행이다. 안내는 첫 반납과 함께만 커밋되므로 두 번 나가지는 않는다). ⑦ 디스패치 결과를 모르는 질문(`outcome_unknown`)은 전달 이벤트도 ts도
      없어 턴이 없다(변함없음). ⑧ `@bot` 멘션 턴은 여전히 메모리뿐이라 턴 중 재시작이면 사라진다 — **보류, 수용된 한계(2026-10-15 사용자 결정).**
      이유: 손실 창은 크래시·배포 순간에 실행 중이거나 대기 중인 턴뿐이고(정상 종료에서는 대기 중인 턴이 종료 예산 3초 안에서 최선 노력으로 안내를 받고, 크래시나 예산 초과면 안내 없이 사라진다), 내구성이 필요한 질문에는
      이미 `/ask`가 있다 — **내구성이 필요하면 `/ask`를 쓴다**가 정책이다. 멘션 턴의 영속화는 프롬프트를 새로 저장해야 해서 개인정보·보존
      표면이 늘고(멘션의 질문은 우리 DB에 원문이 없다), 같은 스레드에서 이어지는 멘션의 순서 역전을 풀어야 하며, `agent_ask_turn`을 재사용하든
      새 테이블을 두든 V27 이상의 마이그레이션이 든다. 나중에 한다면 프롬프트를 저장하지 않는 **알림 전용 설계**(멘션 트랜잭션에 마커만
      기록하고, 스윕이 오래된 미완료 마커에 "다시 물어봐 주세요" 스레드 답을 남긴다)부터 시작한다. 다시 볼 조건: 멘션 사용량이 `/ask`를 크게
      앞서거나 롤링 배포로 재시작이 잦아질 때. ⑨ (2026-10-15 해소 → 결정 #43) 레저 행은 지우지 않는다(보존 정리 없음).
      ⑩ 첫 busy 안내는 Slack이 늦게 배달하면 답보다 뒤에 도착할 수 있다 — 문구는 더 이상 작업이 진행 중이라고 말하지 않는다.
      ⑪ 한 질문의 전달 둘이 동시에 겹치면(아웃박스 소유자 둘이 모두 보낸 경우) 행을 넣은 쪽이 가득 차 과부하 안내로 끝내는 사이
      다른 쪽이 막 큐에 넣은 턴은 아무 일도 하지 않는다(코드를 읽어 확인, 스펙 없음).
    - **운영과 미검증:** `ddl-auto: none`인 환경은 이 코드를 올리기 전에 V26을 적용한다(새 테이블 하나, `CREATE TABLE IF NOT EXISTS`).
      테이블이 없으면 `recordDelivered`가 매번 실패해 `/ask`는 lease 없는 폴백으로 답하되 내구성이 없고 질문마다 ERROR가 남으며, 스윕도
      매 주기 ERROR를 남긴다(코드에서 추론, 미측정). 스펙은 네이티브 문장을 H2에서 실행한다(인프라 스펙은 `MODE=MariaDB`, 애플리케이션
      스펙은 기본 모드, 테이블은 Hibernate가 생성). MariaDB에서는 2026-10-13에 한 번 손으로 확인했다 — 로컬 12.3.3
      (`innodb_snapshot_isolation` ON)의 빈 DB에 V26을 두 번 적용했고(두 번째는 변화 없음), `mariadb` 클라이언트의 prepared statement로
      다섯 문장의 이기는 경로·지는 경로, `GREATEST` 바닥, 고아 컷오프의 1마이크로초 경계, 바인딩한 `LIMIT`을 실행했다. lease를 잃은
      소유자의 `finish`는 그 트랜잭션이 아직 읽지 않았으면 0행, 읽은 뒤면 오류 1020이고 어느 쪽이든 롤백이다. JPA·Connector/J를 거친
      경로(V26 테이블 위의 엔티티 매핑, `recordDelivered`의 중복 키 변환)는 MariaDB에서 실행해 보지 않았다(미확인). CDC 경로 종단, 레플리카 두 개의 동시 동작, 실제 사이드카의
      429 반복도 실행해 보지 않았다(미확인). `@Scheduled`의 실제 발화는 2026-10-20 로컬 MariaDB(Hibernate가 만든 테이블)에서 봤다 — 재개
      스윕이 기동 약 1분 뒤 돌아, 원본 질문 행이 없는 오래된 `PENDING`·`IN_PROGRESS` 행을 `ABANDONED`로 바꿨다(WARN 로그, 새 아웃박스 행 없음).
41. **`/calendar connect`는 동의한 Google 계정의 검증된 이메일이 요청한 슬랙 사용자의 이메일과 같을 때만 연결한다
    (2026-10-13).** state는 슬랙 사용자에 묶이고 브라우저에는 묶이지 않는다. 그래서 요청자가 자기 동의 링크를 남에게 열게 하면 그
    사람의 Google 계정이 요청자의 슬랙 사용자에 연결되고, 요청자는 상대의 캘린더에 미팅을 써 넣으며 확인 DM과 `status`에서 상대의
    이메일을 본다(보안 스캔 F22. 그전에는 `service/calendar/AGENTS.md`에 "자기 동의 DM을 전달한 사용자"의 수용 위험으로 적혀 있었다).
    콜백은 코드 교환 직후, scope 검사 전에 `SlackUserEmailResolver`로 state 주인의 이메일을 `users.info`에서 읽어(캐시 없음)
    id_token의 `email`과 대소문자를 무시하고 비교한다(`email_verified`가 참일 때만). 슬랙 이메일이 없으면(조회 실패, `ok=false`, 키 없음,
    빈 값) `IDENTITY_UNAVAILABLE`(503), Google 이메일이 없거나 검증되지 않았거나 다르면 `ACCOUNT_MISMATCH`(403)다. 둘 다 아무것도
    저장하지 않고 **revoke도 하지 않는다** — 그 grant는 다른 사람 계정의 것일 수 있고 Google의 revoke는 grant 전체라, 그 계정을
    정당하게 연결한 다른 슬랙 사용자의 연결까지 끊을 수 있다(refresh token은 버린다). 로그는 서비스의 WARN 한 줄에 사용자 id와
    사유(`reason=missing|unverified|different`, 또는 확인할 scope·설정)만 남기고(슬랙이 `ok=false`로 답했거나 호출이 실패했으면
    resolver의 WARN이 한 줄 더 붙는다) 페이지는 고정 문구라 어느 쪽 이메일도 드러나지 않는다.
    조회가 예외를 던지면 그대로 컨트롤러의 500 페이지로 가고 아무것도 저장되지 않는다. 기각: ① 쿠키·nonce·PKCE로 흐름을 브라우저에
    묶기 — 공격자가 넘기는 것은 *시작* 링크이고 피해자의 브라우저가 공격자의 슬랙 id에 묶인 흐름을 끝까지 수행하므로 막지 못한다;
    ② 요청자에게 "슬랙에서 확인" DM 보내기 — 공격자가 곧 요청자다(Codex 교차 리뷰); ③ `users.profile.get`의 `email`(처음 구현) —
    슬랙 문서에서 `users:read.email`이 영향을 주는 메서드는 `users.list`·`users.info`뿐이고, `users.profile.get` 문서는 봇 토큰에
    `email`을 약속하지 않으며 Email Display 관리자 설정이 숨길 수 있다고 적는다(2026-10-13 리뷰). 이유(사용자 결정): 신원 비교만이
    공격자와 피해자를 구분한다. 운영: 슬랙 앱에 봇 scope `users:read`와 `users:read.email`이 함께 필요하다(슬랙은 두 번째를 첫 번째와
    함께만 허용한다. manifest에 추가, 기존 앱은 둘 다 추가하고 다시 설치). 상태: 유지. 근거: `service/calendar/AGENTS.md`,
    `impl/calendar/AGENTS.md`(`emailVerified`), `templates/AGENTS.md`(`SlackUserEmailResolver`), `impl/command/dto/AGENTS.md`
    (`SlackUserInfoDto`), `docs/slack-app-manifest.yaml`, git-ignored `.omc/plans/security-scan-2026-10.md`.
    - **알려진 한계:** ① Google 이메일과 슬랙 이메일이 다른 사용자는 연결할 수 없다. 비교는 글자 그대로(대소문자만 무시)라 Gmail의
      점·`+태그` 변형과 Workspace 별칭 도메인도 다른 주소로 본다. ② 슬랙의 이메일을 믿는다 — 사용자가 직접 바꾸는 이메일은 메일 확인을
      거치지만 관리자나 SCIM은 아무 주소나 넣을 수 있다. ③ 워크스페이스의 Email Display 설정이 앱에 이메일을 숨기면 모든 연결이
      `IDENTITY_UNAVAILABLE`이 된다(슬랙 문서 기준, 미측정). ④ 검사는 연결 시점에만 하고 기존 연결은 다시 검사하지 않는다. 그래서
      수정 전 흐름으로 저장된 행(한 슬랙 사용자의 state를 다른 사람의 Google 계정으로 완료한 연결)은 `ACTIVE`로 남아 계속 동기화된다 —
      캘린더를 한 번이라도 켰던 환경은 한 번 감사한다: `google_calendar_connection.google_email`을 각 주인의 슬랙 이메일과 비교하거나,
      사용자가 `/calendar disconnect` 후 다시 연결한다. ⑤ 실제 `users.info` 응답은 측정하지 않았다(스펙은 문서 모양의 JSON을
      디코딩한다) — 앱을 다시 설치한 뒤의 첫 `/calendar connect`가 실측이다. ⑥ `email_verified`는 Google의 주장만큼만 강하다 —
      Gmail·Workspace(`hd`) 밖의 주소에서는 "계정에 붙일 때 확인했다"는 뜻이지 지금도 그 메일함을 가졌다는 뜻이 아니다(Google
      문서 "Authenticate with a backend server", 2026-10-13 확인: `@gmail.com` 접미사이거나 `email_verified`와 `hd`가 함께 있을 때만
      Google이 그 주소에 권위적이고, 제3자 주소는 "ownership of the third party email account may have since changed"). 수용한 잔여 공격: 재활용된 외부 메일함을 넘겨받은 사람이 그 주소를 자기
      슬랙 이메일로 두고(슬랙의 확인 메일이 그에게 간다) 동의 링크를 메일함의 이전 주인에게 보내면, 그 주소가 아직 붙은 이전 주인의
      Google 계정으로 비교가 통과한다. 재활용된 메일함과 이전 주인의 동의가 함께 필요하므로, 수정 전의 "워크스페이스 멤버면 누구나"보다는
      여전히 훨씬 높은 장벽이다(Codex 최종 리뷰).

42. **봇이 없는 채널에서 Slack이 거절한 슬래시 응답은 같은 상태 쓰기 CAS로 요청자 DM에 다시 보낸다 (2026-10-14).**
    슬래시 명령은 봇이 멤버가 아닌 채널에서도 호출되는데 응답은 전부 채널 ephemeral(또는 `/ask`·`/meetup`의 채널 메시지)이라
    `chat.postEphemeral`/`chat.postMessage`가 `not_in_channel`(또는 `no_permission`)로 영구 실패하고 사용자는 아무것도 못 봤다
    (#38의 알려진 한계, 후속 3-1). 멘션 답장의 "요청자 전용 ephemeral"(51233034)은 Slack이 봇이 있는 채널의 `app_mention`만
    보내기 때문에 안전했는데, 그 전제 없이 모양만 슬래시로 복사된 것이 원인이다. 해법: 채널 응답을 내는 생산자가 `fallback: DirectReply?`
    (`UserRef` + `MessageContent.Text`, `OutboundMessage`가 아닌 값 타입)를 붙이고, relay는 디스패치가 채널 단위 영구 거절
    (`isChannelRefused()` — `impl/command/AGENTS.md`의 채널 단위 영구 거절 목록 + `channel_not_found` + `user_not_in_channel`(요청자가 전달 전에
    채널을 떠난 경우, Codex 교차 리뷰). `restricted_action*`·`access_denied`는 채널 단위가 아닐 수 있지만 DM 시도가 사용자에게 닿는 유일한 길이고
    최악의 경우 폴백 행이 같은 거절로 끝날 뿐이라 집합에 둔다)로 끝났을 때만 그 DM을 `next` 행으로
    싣는다 — `ReplaceMessage.fallback`(V4, `archive/gap-closure`의 `6ebd8dca` 2026-10-08 작성·#29로 2026-10-11 머지)이 쓰는 `completeAndChain` CAS 그대로라, 상태 쓰기를 이긴 소유자만 한 번 싣고
    레플리카 둘이어도 중복이 없다. DM 행은 `basicInfo.channel = 요청자 id`로 만든다(렌더러는 `target`을 읽지 않고 `basicInfo.channel`에
    게시한다 — 기존 DM과 같은 방식). 폴백이 있는 행은 **V6**이고 스탬프는 머리·continuation 모든 조각이 필요로 하는 가장 높은 버전으로
    찍는다(이전 first-match `when`은 V5 질문에 폴백이 있어도 V5로 찍어 옛 바이너리가 폴백을 버렸을 것이고, continuation 안의 `ReplaceMessage.fallback`을 V3으로 찍던
    누락 — 2026-10-11 캘린더 리뷰 후속 목록의 A8 — 도 같은 버그였다). 대상: `/standup list|stop`·`answer` 안내와 슬래시 기원 확인·모달 열기 실패 안내, `/meetup list`와 인자 오류, `/meetup` 단독,
    `/ask` 질문·usage·권한 거부, `/subscribe`·`/unsubscribe`의 빈 상태 안내, `/calendar status|disconnect`(connect의 "DM을 보냈다" 채널 안내 줄만 예외 —
    동의 링크가 이미 DM이라 두 번 보내지 않는다), `Command.replyToSlashFailure`의 공통 오류. 본문이 있는 응답은 원문 앞에 한 줄
    (`_(Sent here because my reply in <#C> was refused.)_`)을 붙여 보내고, 채널에 있어야 의미 있는 것(`/ask` 질문, `/meetup` 폼,
    `/meetup list` 목록)은 안내문만 보낸다 — 문구는 거절 사유가 멤버십이 아닐 수도 있어(`is_archived`, `restricted_action*`) 봇 이름과
    사유를 단정하지 않고, 도메인 문장이라 Slack도 지칭하지 않는다. `/ask` 질문이 거절되면 `MessageDeliveredEvent`가 없으므로 레저 행도 턴도 생기지 않고 안내 DM만 간다(DM은
    `SIMPLE_TEXT`라 `AgentAskThreadStarter`가 보지 않는다).
    기각: ① 슬래시 `response_url`을 핸들로 도메인에 실어 ephemeral을 그 URL로 보내기 — 봇이 없는 채널에서도 보이지만 5회·30분
    제한이라 아웃박스 지연(CDC 정지·access-blocked 15분·rate limit·스윕) 뒤에는 `expired_url`로 실패해 결국 DM 폴백이 필요하고,
    `/ask` 질문 실패 안내와 모달 기원 확인(`private_metadata` 운반)도 따로 필요하다 — UX 개선으로 나중에 얹을 수 있다(#38의 기각 ②는
    답변 표면에 한한 것이라 그때 범위를 명시해 수정한다); ② 거절 시 `conversations.join` 후 재전송 — `channels:join` 스코프·재설치,
    공개 채널만, 봇이 사용자 명령의 부작용으로 채널에 들어가는 정책 문제; ③ `chat:write.public` — `chat.postEphemeral` 적용 여부가
    문서에 없고 스레드 멘션은 어차피 멤버십이 필요하다; ④ 슬래시 HTTP ack 본문에 답장 — 빈 200을 테스트가 고정하고 Socket Mode는
    처리 전에 ack 하며 아웃박스 우회다(#38 ①). 알려진 한계: 폴백은 한 홉 더 걸린다(폴링 5초, CDC는 즉시, 커넥터 정지 시 stale 스윕
    5~6분); 거의 모든 슬래시 응답 행이 V6이 되므로 V6을 모르는 바이너리(롤백·롤아웃 중 구 레플리카)는 그 행을 `IN_PROGRESS`로 쥔다
    (24시간 상한); `/meetup list`는 목록이 아니라 안내만 DM으로 간다(`MeetingList`는 `ChannelMessage`에 렌더되지 않음, 후속 후보);
    Messages 탭이 읽기 전용이라 DM에 답할 수는 없다. 안내문의 `<#C>`는 채널 id 멘션이라 봇이 없는 1:1 DM(`D…`)·그룹 DM(`G…`, `C…` 가능성은 미확인 — #49)에서 거절된 경우에는 어색하게 렌더되고 "초대" 안내도 맞지 않는다(그 대화에서 거절될 때만 보인다). 어느 채널 유형이 `not_in_channel`과 `channel_not_found` 중 무엇을 돌려주는지는
    미확인이라 둘 다 처리한다. 상태: 유지. 근거: `service/relay/AGENTS.md`, `impl/command/AGENTS.md`,
    `domain/.../command/outbound/AGENTS.md`, `.omc/plans/b2-not-in-channel-slash-replies.md`(git 미추적).

43. **에이전트 레인의 레저와 감사 행은 보존 기간이 지나면 지운다 — `agent_ask_turn`의 끝난 행은 14일, `agent_turn_history`와
    `mcp_tool_call_history`는 180일 (2026-10-15).** `AgentRetentionScheduler`가 매시간(fixedDelay 1시간; 첫 실행은 인스턴스마다 기동 뒤 10~20분 사이에서
    무작위 — `initialDelayString = "${random.long[600000,1200000]}"`. 아웃박스 purge의 5분보다 늦고, `Recreate`로 함께 기동한 두 레플리카의 틱이
    매시간 같은 순간에 맞물리지 않게 어긋난다, 2026-10-19 리뷰 반영) 세 테이블을 차례로 지운다. 레저는 `status IN ('COMPLETED', 'ABANDONED') AND updated_at < now − ask-turn-days`, 두 감사
    테이블은 `created_at < now − history-days`이고, 모두 아웃박스 purge와 같은 네이티브 `DELETE … LIMIT :batch-size`(기본 1000)를 짧은 배치가
    나올 때까지 틱당 최대 20회 반복한다. 테이블마다 실패를 따로 가둬(`containFailure`: `Exception`은 테이블을 이름으로 남기는 ERROR 하나, 다음
    테이블은 그대로 진행, `Error`는 전파) 한 테이블의 장애가 나머지를 막지 않는다. 키는 `slack.app.agent.retention.{ask-turn-days, history-days,
    batch-size}`이고 prod YAML에 아웃박스 보존처럼 값을 적어 둔다. 이유: 세 테이블 모두 정리가 없어 무한히 자랐고(결정 #40 ⑨), 감사 행에는
    요청자·채널·세션 키와 툴 인자(`arguments_json`, 최대 2000자)가 들어 있어 무기한 보존은 개인정보 면에서도 맞지 않다(사용자 결정: 14일 / 180일).
    - **기간의 하한은 기동 때 검증한다.** `history-days`는 사용량 보고(`@bot usage [days]`, MCP `get_ai_usage`)가 읽는 최대 범위
      `CommandIntent.AgentUsageReport.MAX_DAYS`(90일)보다 하루 이상 길어야 한다(`MIN_HISTORY_DAYS` = 91, 2026-10-19 Codex 교차 리뷰 반영). 보고는
      `since = now − days`를 한 번 정하고 세 쿼리를 차례로 읽는데 공유 스냅샷이 없다(MCP는 `renderReport`를 바로 부르고, Slack 경로의
      `detachedTemplate`은 `PROPAGATION_NOT_SUPPORTED`). 기간이 정확히 90일이면 그 사이에 시작한 purge가 `[since, since + Δ)`의 행을 지워
      합계·순위·툴 호출이 서로 어긋날 수 있다. 하루의 여유는 보고 한 번이 하루 안에 끝난다는 전제의 여유다. 그보다 짧으면 보고가 조용히 줄어든다. `ask-turn-days`는 아웃박스의
      재전송 상한 `outbox.polling.give-up-after-hours`(24시간)보다 **길어야** 한다(같으면 거부) — 레저 행은 같은 질문의 재전달을 한 턴으로 묶는
      중복 키라서(`recordDelivered`가 insert부터 한다), 아웃박스가 그 질문을 아직 다시 보낼 수 있을 때 행이 지워지면 재전달이 두 번째 턴을 만든다.
      레저 행의 `updated_at`은 질문 아웃박스 행의 `created_at`보다 늦으므로 이 부등식이면 지울 때 그 행은 이미 재전송 창 밖이다. 둘 다
      `AppConfig` 바인딩 실패(키 이름을 담은 메시지)로 기동을 막는다 — `history-days`는 `Agent.Retention`의 `init`, `ask-turn-days`는 두 하위
      트리를 함께 보는 루트 `AppConfig`의 `init`. 상한도 있다(2026-10-19 리뷰 반영): 두 기간은 3,650일 이하, `batch-size`는 1~10,000이고, 범위를
      벗어나면 키 이름과 범위를 담은 메시지로 바인딩이 실패한다 — 터무니없는 값이 `Duration` 계산에서 키 이름 없는 `ArithmeticException`이 되지
      않게 하고, 한 문장이 지우는 행 수(트랜잭션 크기)를 묶어 둔다 — `LIMIT`이 묶는 것은 지우는 행이지 훑거나 잠그는 행이 아니다. 교차 검사는 `TimeUnit.DAYS.toHours`(넘치면 `Long.MAX_VALUE`로 포화)로 비교해
      `give-up-after-hours`가 아무리 커도 넘침 없이 같은 메시지로 거부한다. 두 감사 테이블은 `mcp_tool_call_history.turn_id` ↔
      `agent_turn_history.idempotency_key`로 논리적으로 이어지므로 같은 기간을 쓴다. 다만 같은 기간이 함께 지워짐을 보장하지는 않는다 — 테이블마다
      배치 예산(틱당 20회)과 실패 격리가 따로라, 밀린 양이 다르거나 한쪽 실패가 반복되면 한쪽 행이 몇 시간·며칠, 실패가 이어지는 동안은 계속 더
      남을 수 있다. 두 테이블을 조인하는 운영 쿼리와 선언된 FK는 없다.
    - **레저는 끝난 행만 지운다.** `PENDING`/`IN_PROGRESS` 행은 아무리 오래돼도 남긴다 — 지우면 진행 중인 lease의 다음 CAS가 0행이 되어
      `AgentTurnLeaseLostException`으로 답이 롤백되고, 스윕도 그 턴을 다시 찾지 못한다. 그런 행은 전달 1시간(`ASK_GIVE_UP_AFTER`) 뒤부터 포기
      대상이 되지만, 언제 끝나는지는 보장되지 않는다(스윕 순서, 배치 크기, 실패가 늦출 수 있다). 그동안은 purge 대상이 아니므로 기다릴 뿐이다. 끝난 행을 읽는 코드는 `recordDelivered`의 중복 경로뿐이다. 문장의 상태 목록은 리터럴이라, 스펙이 `AgentAskTurnStatus`의 모든
      상수를 `else` 없는 `when`으로 분류해 상수마다 한 행씩 지워 보고 끝난 상태만 사라지는지 본다 — 새 상수는 분류하기 전에는 스펙이 컴파일되지
      않는다.
    - **인덱스는 이미 있다(마이그레이션 없음).** 레저는 V26의 `(status, updated_at)`(아웃박스 purge처럼 상태별 range 둘), 감사 테이블은 V10·V13의
      `created_at` 인덱스. V10·V13 헤더의 "append-only"는 "갱신하지 않는다"는 뜻이라 삭제와 모순되지 않으며, 출하된 스크립트는 고치지 않는다.
      CDC는 `outbox_message`만 캡처하므로 이 삭제는 Kafka로 흐르지 않는다. 시계: 감사 행의 `created_at`은 `@CreationTimestamp`(JVM 시스템 존)이고
      앱 `Clock` 빈이 `Clock.systemDefaultZone()`이라 `LocalDateTime.now(clock)` 컷오프가 둘 다에 맞는다.
    - **기각:** ① `OutboxRetentionScheduler`를 일반 `RetentionScheduler`로 — relay 레인이 agent 저장소를 알게 되고 리뷰 표면이 커진다(대신 배치
      루프 약 10줄이 겹친다. 그 루프를 `application/common`의 작은 함수로 합치는 것은 아웃박스 purge의 오류 처리를 고치는 PR 뒤의 후속으로 둔다); ② 기존 스윕에 얹기(`CveCollector` 방식) — 60초마다 DELETE가 돌고 감사 테이블에는 얹을 스케줄러가 없다; ③ DB EVENT나
      k8s CronJob SQL — 테스트할 수 없고 수동 마이그레이션 관례와 충돌한다. `agent_session`(V9, 스레드마다 한 행)은 지우지 않는다 — 지우면 그
      스레드의 사이드카 세션 연속성이 끊기고, 사이드카 쪽 세션 만료 정책은 확인하지 않았다(미확인, 사용자 결정으로 범위 밖).
    - **운영:** 오래 돈 DB의 첫 실행은 쌓인 행을 한 번에 다 지우지 못한다 — 테이블마다 틱당 최대 20 × 1000 = 2만 행이고, 나머지는 매시간 같은
      양씩 줄어든다. 두 레플리카는 첫 실행이 어긋나 대개 다른 시각에 지우고, 겹치더라도 아웃박스 purge와 같은 노출(락 대기, 드문 데드락 → 다음
      틱 재시도)로 수용한다. **purge는 트랜잭션 밖에서 돌아야 한다** — 각 `DELETE … LIMIT`이 자기 트랜잭션의 처음이자 유일한 문장이다.
      `purgeOnce`나 여러 배치를 한 `@Transactional`로 감싸면 락을 배치마다가 아니라 끝까지 쥔다. DELETE 앞에 읽기를 넣으면
      `innodb_snapshot_isolation`이 켜진 MariaDB에서, 그 읽기 뒤 다른 트랜잭션이 **갱신**하고 커밋한 대상 행을 DELETE가 만날 때 그 문장이
      ER_CHECKREAD 1020으로 실패한다 — 커밋 때가 아니라 DELETE 문에서, 0행(2026-10-20 실측; 그전 문구 "기다리던 쪽이 커밋 시 1020"은 결정 #40의
      관찰에서 한 추론이었다). **MariaDB 실측(2026-10-20, 로컬 12.3.3, 스크립트·출력 `.omc/artifacts/mariadb-verify-2026-10-20/`):**
      ① 세 문장의 `EXPLAIN DELETE …`는 테이블당 2만 행에서 대상이 적을 때·없을 때·많을 때 모두 레저는 `idx_agent_ask_turn_status_updated_at`의
      range(상태별 둘), 감사 테이블은 `created_at` 인덱스의 range였고, 롤백한 `ANALYZE DELETE`가 실제로 훑은 행은 10·0·1000(`LIMIT`에서
      멈춤)이었다 — 대상이 없을 때 전체 스캔은 없다. 계획이 range가 아니게 되면 배치를 줄여서는 고쳐지지 않는다 — `LIMIT`은 지우는 행만 묶고
      REPEATABLE READ에서 훑고 잠그는 행은 묶지 않으므로, 그때는 인덱스·문장(인덱스 힌트 등)을 고친다. ② 한 세션이 1000행 배치를 3초 쥔 동안
      다른 세션의 autocommit 루프는 앞 세션이 락을 놓을 때까지(약 2초) 기다린 뒤 이어 지웠고, 두 세션 배치들의 `ROW_COUNT()`(각 1000 이하)
      합계가 대상 10,136행과 같았다(데드락·오류 없음). 대상 아닌 행은 모두 남았고 그중에 오래된 `PENDING`·`IN_PROGRESS` 행이 있으므로 상태
      조건도 MariaDB에서 지켜졌다. 실험한 겹침(감사 테이블에서 DELETE 앞에 읽기를 넣은 트랜잭션과 같은 범위를 지우는 다른 세션)에서는 1020 없이
      상대가 지운 행을 건너뛰었다 — 1020은 위의 '읽은 뒤 갱신된 행'에서만 났고, 끝난 레저 행과 감사 행은 갱신되지 않으므로 지금 코드에는 그
      경로가 없다. 규칙은 그대로 둔다(락 보유 시간, 읽기를 끼워 넣는 변경을 막는다). ③ 연속 배치 계약 — 실제 앱(스케줄러 → Spring Data 네이티브
      쿼리 → Hibernate → MariaDB Connector/J)을 `batch-size` 3으로 띄워 대상 7·6·8행을 넣자 ROW binlog의 트랜잭션별 삭제 수가
      `[3, 3, 1]`·`[3, 3]`·`[3, 3, 2]`(한 번도 limit 초과 없음), 틱 로그의 합계 7·6·8이 실제 삭제 수와 같았고 쌓인 행이 다 비워졌다. 호출별
      반환값은 기록하지 않았으므로 합만 맞는 잘못된 수(`[3, 3, 1]`을 `[3, 4, 0]`으로 보고)는 이 경로에서 배제되지 않았다. 이 실행에 넣은 오래된
      `PENDING`·`IN_PROGRESS` 행은 틱 전에 재개 스윕이 `ABANDONED`로 바꿔(위 결정 #40에 적은 관찰과 같은 일) 상태 조건은 이 실행에서 검증되지
      않았다(②가 검증). 앱은 Hibernate가 만든 로컬 테이블 위에서 돌았고, V26 스크립트로 만든 테이블 위의 실행은 여전히 미확인이다(위 결정 #40의
      같은 항목). MariaDB는 `ORDER BY` 없는 `DELETE … LIMIT`을 statement
      기반 binlog에서 unsafe로 표시하지만, 저장소의 MariaDB 설정은 `binlog_format=ROW`라 해당 없다(아웃박스 purge와 같은 노출).
    - **H2 스펙의 한계(2026-10-17, 스펙 실행으로 확인):** 스펙은 H2 2.4.240(`MODE=MariaDB`, 테이블은 Hibernate가 생성)에서 돈다. Hibernate가 보낸
      그대로의 문장(`DELETE FROM agent_ask_turn WHERE status IN ('COMPLETED', 'ABANDONED') AND updated_at < ? LIMIT ?`, `TIMESTAMP` 컷오프와
      `INTEGER` 2를 바인딩)에 정확한 수를 단언해 돌린 `AgentAskTurnRepositoryTest`에서, H2는 첫 호출만 맞게 셌다. 컷오프를 지난 `COMPLETED` 3행:
      `[보고 2, 남은 1, 보고 0, 남은 0]`(기대 `[2, 1, 1, 0]`) — 두 번째 호출이 마지막 행을 지우면서 0을 보고했다. `COMPLETED` 3 + `ABANDONED` 3행:
      `[(2, 남은 4), (2, 남은 1), (0, 0), (0, 0)]`(기대 `[(2, 4), (2, 2), (2, 0), (0, 0)]`) — 두 번째 호출이 `LIMIT 2`에서 3행을 지우며 2를
      보고했다. 테스트 트랜잭션 안에서도, 호출마다 커밋해도, 같은 Hibernate 경로로 `VARCHAR` 사본 테이블에 실행해도 같았다. 같은 jar에서 JDBC로
      직접 실행한 같은 문장은 맞게 셌다는 리뷰 관찰이 있어 원인은 밝히지 못했다(미확인). 그래서 레저 스펙은 첫 호출의 수와 남은 행만 정확히 보고,
      그 뒤 호출은 H2에서도 참인 성질만 본다 — 보고한 수가 0~limit이고, 남은 대상 행이 늘지 않다가 0이 되며, 대상이 아닌 행은 남는다. **따라서
      연속 배치 계약(호출마다 batch 이하를 지우고 참 수를 돌려주며, 루프가 그 수로 끝까지 비우는 것)은 스펙으로 증명되지 않았다.** 스케줄러
      스펙은 수를 MockK로 주므로 루프만 본다. MariaDB의 실제 JPA·드라이버 경로에서는 배치마다 limit 이하로 지우고 합계가 실제 삭제 수와
      같았다(위 ③, 2026-10-20; 호출별 반환값은 기록하지 않음). H2 오계수의 원인은 위와 같이 여전히 미확인이다. H2에서는 스케줄러 루프가 일찍 멈추거나 한 배치에 limit보다 많이 지울 수 있다. 상태: 유지. 근거: `service/agent/AGENTS.md`(`AgentRetentionScheduler.kt`), `repository/agent/AGENTS.md`,
      `repository/mcp/AGENTS.md`, `configurations/AGENTS.md`(`AppConfig.kt`), `application-prod.yaml`, `AgentRetentionSchedulerTest`,
      `AppConfigBindingTest`, `AgentAskTurnRepositoryTest`, `AgentTurnHistoryRepositoryImplTest`, `McpToolCallHistoryRepositoryImplTest`.
45. **응답 핸들은 `toString()`에서 원값을 가린다 (2026-10-15).** `ResponseReplaceHandle`과 `ReplyHandle`(둘 다 Slack
    `response_url`)의 `toString()`은 `ResponseReplaceHandle(redacted)` / `ReplyHandle(redacted)`다. 그 URL은 30분 안에 5회까지 그 대화에
    답할 수 있는 bearer 값으로 다룬다 — 슬랙 문서가 설명하는 사용법은 그 URL로의 POST뿐이다(토큰이 필요 없다는 것은 이 설명에서 나온
    추론이다). 핸들을 담은 data class(`ReplaceMessage`, `ModalForm.Reschedule`/`AddParticipant`, `CommandIntent.CancelMeeting`/
    `RescheduleMeeting`/`AddParticipant`, 파스된 제출 모델, `InboundInteraction`)의 생성된 `toString`이 그것을 그대로 찍었다. 정상 경로에서
    그런 객체를 로그에 찍는 곳은 없었지만, 렌더러의 `OpenModal` 가드(`OutboundRenderer.kt`의 `error("OpenModal is not a renderer
    concern; …: $message")`)는 메시지 전체를 예외 메시지에 넣어 `ModalForm.Reschedule`/`AddParticipant`의 `listHandle`을 찍을 수
    있었다 — 이 결정으로 그 경로도 가려진다. value class 한 곳만 바꾸면 모든 운반 data class에 퍼진다 — Kotlin 2.4.21이 만든
    바이트코드에서 non-null 프로퍼티는 `ResponseReplaceHandle.toString-impl`을 바로, nullable 프로퍼티는 null 검사 뒤 같은 메서드를
    부른다(`javap`으로 확인, `OutboundMessageTest`가 두 경우와 컬렉션 안의 박스 값을 고정). 도메인 이벤트 payload 3종
    (`CancelMeetingPayload`·`RescheduleMeetingPayload`·`AddParticipantPayload`)은 data class가 아니라 `toString`이 클래스 이름과 해시뿐이고,
    data class가 되더라도 가려진다. 전송 코드는 `.raw`를 읽는다. 아웃박스 코덱(`OutboundMessageCodec`이 자기 `JsonMapper`를 만든다)과
    멱등 키(`IdempotencyCreator` → `infrastructure/.../common/JsonMapper.kt`의 `jsonMapper`)는 서로 다른 매퍼지만 둘 다 `KotlinModule`을
    등록한 Jackson이라 `toString`이 아니라 unboxed 원값을 쓴다 — 저장 형식은 `OutboundMessageCodecTest`의 고정 JSON 단언이, 키가 원값을
    따른다는 것은 `IdempotencyCreatorTest`(reply URL만 다른 두 명령은 다른 키, 같은 URL은 같은 키)가 고정한다. `TriggerHandle`/`ModalOpenHandle`
    (trigger id)과 `MessageHandle`(메시지 ts)은 가리지 않는다 — trigger id는 토큰을 요구하는 `views.open`에만 쓰이고 3초 안에 만료되며
    한 번만 쓸 수 있고, ts는 비밀이 아니다. 비용: 로그와 디버거에서 원값이 보이지 않고, `toString`이 상수라 테스트 실패 메시지와
    data class 비교의 diff에서도 서로 다른 두 핸들이 똑같이 찍혀 구분되지 않는다 — 값이 다른지는 `.raw`로 비교한다. Jackson 같은
    직렬화 덤프는 `toString`을 거치지 않으므로 원값을 그대로 보인다. 남은 노출(이번에 고치지 않음): ① 모달이 나르는 `/meetup list`
    핸들은 컨텍스트가 파싱하기 전까지 평범한 `String`이라(`InboundSubmission.*.listHandleRaw`, `routingExtras[2]`) 그 제출과
    `InboundInteraction`을 찍으면 보인다. ② 같은 핸들이 모달 `private_metadata`에 URL 인코딩돼 들어가므로(`SlackOutboundStager` →
    `ModalTemplateBuilder`) 그 뷰 JSON을 든 `OpenViewPayloadContents.viewJson`(data class)을 찍어도 보인다. ③ 인프라의 Slack 요청 DTO
    (`SlashCommandRequestBody`, `InteractionPayload`, `ActionEventPayloadContents`)가 `responseUrl`을 문자열로 든다. ④ 인접한 다른 값:
    `InboundCommand.appToken`·`CommandBasicInfo.appToken`은 Slack 요청 본문의 `token`(슬랙이 deprecated한 verification token)이고, 두
    data class의 생성된 `toString`이 그대로 찍는다(`CommandExecutor`의 WARN은 일부러 빼 둔다). 운영은 서명 검증을 쓰므로 위험은 낮다.
    (2026-10-20 해소 → 결정 #50: ①~③은 가렸고, ④는 필드째 지우며 아웃박스 스키마를 V7로 올렸다.)
    상태: 유지. 근거: `domain/.../command/outbound/AGENTS.md`, `domain/.../command/inbound/AGENTS.md`.

46. **Google refresh token 암호문은 그 행의 슬랙 사용자에 묶는다 — `v2` = AES-GCM + AAD `slack_user_id` (2026-10-15).**
    `TokenCipher.encrypt(plaintext, userId)`는 `v2.<iv>.<ciphertext>`를 쓰고 사용자 id(UTF-8)를 GCM의 추가 인증 데이터로 넣는다.
    `decrypt(token, userId)`는 `v2`를 그 id로 인증하고, AAD 없이 쓰인 `v1`(이전 형식)은 지금처럼 AAD 없이 읽는다. 호출부 셋 —
    연결 저장(`CalendarConnectionService`, 소비한 state의 사용자), 토큰 갱신(`GoogleAccessTokenProvider`, 행의 `slackUserId`),
    revoke(`GoogleTokenRevocationWorker`, 교체·삭제된 행의 `slackUserId`를 담은 이벤트의 `userId`) — 이 모두 행의 사용자 id를 넘기고,
    빈 id는 거부한다. 키·IV 처리는 그대로다. 막는 것: 키 없이 DB 쓰기만 가진 공격자가 행 사이에서 암호문을 바꿔 넣는 것 — 바꿔 넣은
    `v2`는 복호화에 실패해 동기화 경로에서는 `TOKEN_UNREADABLE`(연결 `REVOKED`, 재연결 DM), revoke 경로에서는 수동 제거 안내가 된다.
    `v2` 본문의 접두어를 `v1`로 고쳐도 태그가 AAD를 덮으므로 실패한다. 이유(사용자 결정, 2026-10-15): 트리아지는 "그대로 두고
    기록"을 권했지만(DB 쓰기 권한자는 이미 `outbox_message`로 봇 이름의 임의 메시지를 보낼 수 있어 AAD가 막는 위협은 더 큰 능력에
    포함된다는 반론), V24가 아직 어디에도 적용되지 않았다고 기록돼 있어(`db/migration/AGENTS.md`, 2026-10-08 기준; 운영 DB는 보지 않았다)
    지금이 가장 싸므로 한다. 기각: ① `v2`만 읽기(`v1` 읽기 없음) — 개발 DB의
    기존 연결이 모두 끊긴다; ② 일괄 재암호화 잡 — 운영에 `v1` 행이 없고, 있더라도 재연결이 같은 일을 한다; ③ AAD에
    `"google_calendar_connection:"` 같은 접두어 — 이 키는 이 열에만 쓰여 도메인 분리가 필요 없다. 상태: 유지. 근거:
    `impl/calendar/AGENTS.md`(`TokenCipher`), `service/calendar/AGENTS.md`, `TokenCipherTest`(이전 코드가 만든 고정 `v1` 토큰 포함).
    - **알려진 한계:** ① `v1` 행은 묶이지 않아 그 사용자가 다시 연결해 `saveActive`가 새 토큰을 `v2`로 쓸 때까지 바꿔치기가 가능하다
      (토큰 갱신은 행을 다시 쓰지 않는다 — 이 클라이언트의 refresh 응답에는 새 refresh token이 없고 provider는 쓰지 않는다). ② **롤백:**
      이 릴리스 이전 바이너리는 `v1`만 읽으므로, 그 바이너리로 되돌리면(배포 워크플로의 `rollout undo` 포함) 이 릴리스에서 저장·재연결된
      모든 토큰이 그 사용자의 다음 동기화에서 `TOKEN_UNREADABLE` → `REVOKED` + 재연결 DM이 되고, disconnect의 revoke는 수동 제거
      안내로 끝난다. 혼합 버전 롤아웃도 같다 — `Recreate`를 지운 뒤 이 릴리스 이전 파드와 겹치면 옛 파드의 동기화 틱이 새 파드가 저장한
      `v2` 토큰을 못 읽어 그 연결을 `REVOKED`로 만든다(`k8s/README.md`의 `Recreate` 제거 조건, `deployment.yaml` 주석).
      운영에 캘린더가 아직 켜지지 않았으므로 현재 영향은 없다(미확인: 운영 DB를 보지 않았다).
47. **`google_calendar_connection`에 `@Version`을 둔다 — V28 (2026-10-15).** `saveActive`는 행을 읽고 다시 써서 교체한 grant를
    돌려주는데(호출자가 다른 계정이면 revoke), 같은 사용자의 콜백 둘이 같은 행을 읽으면 나중 커밋이 먼저 저장된 grant를 "교체됨"으로
    보고하지 않은 채 덮어써 그 grant가 Google에 살아 남았다. 막는 것은 MariaDB snapshot isolation의 1020뿐이었다(H2·구버전·설정 차이에서는
    last-write-wins). 이제 `@field:Version var version: Long = 0L`(protected set, `MeetingSchema`와 같은 모양 — 원시 타입이라 `save()`의 신규
    판정은 그대로 `id == 0`)이 어느 DB에서든 늦은 쪽을 `ObjectOptimisticLockingFailureException`으로 실패시키고, 그것은 1020과 같은
    `ConcurrencyFailureException`이라 콜백의 기존 1회 재시도가 그대로 받는다 — 재시도는 행을 다시 읽어 사이에 저장된 grant를
    교체된 것으로 보고하고 계정이 다르면 revoke를 요청한다. `delete(userId, observedEncryptedRefreshToken)`는 행을 다시 읽어 disconnect가 봤던
    암호문과 같을 때만 그 엔티티를 remove 하므로(아니면 `ObjectOptimisticLockingFailureException`), 재연결이 커밋되기 전에 행을 읽은
    disconnect는 새 grant의 행을 지우는 대신 실패하고 사용자가 다시 실행한다 — 같은 행이 덮어써진 경우는 버전 검사가(0행이면
    `ObjectOptimisticLockingFailureException`, MariaDB 1020이면 `SnapshotIsolationConflictException` — 둘 다 `OptimisticLockingFailureException`),
    다른 disconnect가 지운 뒤 connect가 새 PK로 다시 넣은 경우(READ COMMITTED에서 파생 `deleteBySlackUserId`는 새 행을 통과시켰다,
    2026-10-19 Codex 교차 리뷰)는 암호문 비교가 막는다. **`markRevoked`(벌크
    JPQL `UPDATE`)는 version을 올리지 않는다** — 그 CAS는 암호문 조건이 대신하고, revoke 사이에 끼어든 재연결(새 grant 저장)과
    disconnect(어차피 삭제)는 이기는 것이 맞으며, 올리면 둘을 실패시키기만 한다. 이 테이블의 다른 쓰기는 모두 엔티티를 거친다.
    기각: 그대로 두고 문서화(트리아지 권고) — 남는 피해가 고아 grant 하나지만 1020 전제가 어디에도 적혀 있지 않았고 비용이 컬럼 하나다.
    운영: V28(`ALTER TABLE … ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0`, V27과 같은 5초 `lock_wait_timeout` 온라인 형식,
    재실행 가능)은 **새 바이너리 배포 전에** 적용한다 — 엔티티가 컬럼을 매핑하므로 없으면 연결 조회가 모두 실패한다. 이전 바이너리는
    컬럼을 무시하므로 롤백에도 그대로 둬도 된다. MariaDB 12.3.3에서 실행했다(2026-10-20, 로컬 일회용 스키마): 첫 실행 성공(기존 행
    `version` = 0), 재실행은 note 1060, 테이블을 읽은 트랜잭션 뒤에서는 약 5초(5.1초) 기다린 뒤 하루가 아니라 그때 1205로 포기, 같은 `ADD COLUMN`은 `ALGORITHM=INSTANT`로도
    받아들여졌다(`db/migration/AGENTS.md`, `.omc/artifacts/mariadb-verify-2026-10-20/`). 상태: 유지.
    근거: `V28__add_google_calendar_connection_version.sql` 헤더, `repository/calendar/AGENTS.md`, `GoogleCalendarConnectionRepositoryImplTest`
    (두 스레드 경합), `CalendarConnectionServiceTest`(실제 JPA 경합 뒤 재시도).
50. **결정 #45가 남긴 노출을 닫는다 — 응답 핸들은 출력에서 가리고, 쓰이지 않던 Slack verification token은 필드째 지우며 아웃박스
    스키마를 V7로 올린다 (2026-10-20).** #45의 "남은 노출" ①~④에 대한 처리다. ① `InboundSubmission.RescheduleMeeting`/`AddParticipant`는
    `listHandleRaw: String` 대신 `listHandle: ResponseReplaceHandle?`(`toString`이 가려진 값)를 든다. 인프라 매퍼(`SlackInboundMapper`)가
    `routingExtras[2]`에서 만들고(공백이면 `null` — 도메인 `replaceHandleOrNull`과 같은 규칙이고, 그 함수는 `internal`로 둔다)
    `ParsedSubmissions`는 그대로 넘긴다. 매퍼는 이 두 제출의 `InboundInteraction.routingExtras`에서 그 자리(`[2]`)만 빼고
    넘기므로 interaction을 찍어도 URL이 없다 — 제출 라우팅은 `submission`만 보고 이 두 타입의 `routingExtras`를 읽는 도메인 코드는 없다.
    ② `OpenViewPayloadContents.toString()`은 `viewJson=redacted`(재조정·참가자 추가 뷰는 목록 URL을 `private_metadata`에 URL 인코딩해 든다).
    ③ `ActionEventPayloadContents`는 `responseUrl=redacted`, `InteractionPayload`는 `responseUrl`·`routingExtras`·`privateMetadata`를
    `redacted`로 찍는다(뒤의 둘은 제출에서 같은 목록 URL을 평문·인코딩으로 든다). `SlashCommandRequestBody.responseUrl`은 읽는 곳이 없어
    필드를 지웠다. 세 `toString`은 손으로 쓴 것이라 새 필드를 자동으로 찍지 않는다(빠질 뿐 새지는 않는다). ④ 사용자 결정으로
    verification token을 지운다: `InboundCommand.appToken`, `CommandBasicInfo.appToken`(`forOutbound` 포함; 호출처 없던 `withNewKey`는 지웠다), `CommandOutput.token`과
    그 `FIXME`, 세 매퍼의 대입, 읽는 곳이 없어진 Slack DTO의 `token`(`SlashCommandRequestBody`, `InteractionPayload`,
    `SlackEventCallBackRequest`). 읽는 곳이 없었음은 검색으로 확인했다(`CommandOutput.token`을 채우던 `ApprovalCallbackContext`·
    `RequestMeetingContextResult`도 읽지는 않았다). 들어오는 본문에는 `token`이 그대로 있다 — 슬래시 폼과 멘션 본문은 공용 `jsonMapper`
    (jackson-databind 3.2.3, `FAIL_ON_UNKNOWN_PROPERTIES` 기본 false)가 모르는 키로 버리고, 인터랙션은 Slack SDK가 파싱한 페이로드에서
    우리 파서가 복사하지 않을 뿐이다. `UrlVerificationRequest.token`과 `AppConfig.api.appToken`(Socket Mode `xapp-` 토큰)은 다른 값이라 그대로다.
    - **왜 V7인가(사용자 결정 A, 2026-10-20).** `CommandBasicInfo`는 모든 아웃박스 행의 `basicInfo`로 인코딩되고, 인바운드에서 온 행은
      `extractBasicInfo`가 복사한 verification token을 `outbox_message.payload`(DB)와 CDC를 거쳐 Kafka에까지 실어 왔다 — 필드를 지우면
      `toString`과 저장 노출이 함께 닫힌다. 그러나 V7 이전 바이너리는 `appToken`을 non-null 생성자 인자로 요구해 새 행을 하나도 디코드하지
      못하므로(미지원 버전 가드가 없다면 렌더 실패 → `FAILURE`로 잃는다), "읽을 수 있는 가장 오래된 버전" 규칙대로 **모든 행**을 V7로
      찍는다 — 행별 선택(V2~V6)은 상수로 접혔고, V8이 필요한 shape가 생기면 V7을 바닥으로 되살린다. `SUPPORTED`는 V2~V7이고, V7
      바이너리는 `appToken`이 든 이전 행을 모르는 키로 무시하고 읽는다. Flyway 마이그레이션은 없다(다음 번호는 그대로 V29) — 페이로드
      형식만 바뀌며, `schema_version` 값을 고정하는 DB 제약이나 Debezium 설정은 없다(커넥터는 `outbox_message` 행 전체를 캡처한다).
      부수 효과: `IdempotencyCreator`가 해시하는 `InboundCommand` JSON에서 토큰이 빠져 같은 요청의 키가 배포 전후로 다르다(키에 1초 창이
      섞이므로 배포를 사이에 둔 비교는 원래 맞지 않는다).
    - **기각:** B — 도메인에서는 지우고 코덱이 상수 `"appToken":""`을 계속 쓰는 shim(mix-in + 가상 속성 writer): 버전을 올리지 않아
      롤백이 안전하지만 죽은 키와 코덱 전용 장치가 남는다. C — `CommandBasicInfo.appToken`만 남겨 늘 `""`로 둔다: 형식은 그대로(값만
      바뀜)라 가장 작지만 죽은 필드가 도메인에 남고 제거를 다음 PR로 미룬다.
    - **대가와 롤아웃:** 배포 뒤 V7 이전 바이너리로 롤백하면(배포 워크플로의 `rollout undo` 포함) 그 바이너리는 배포 뒤에 쓰인 **모든**
      행을 `IN_PROGRESS`로 쥔다(발송 예산 미차감, 회수마다 ERROR). V7 바이너리가 돌아오면 스윕이 이어 보내지만, 24시간 안에 돌아오지 않으면
      `FAILURE`로 잃는다. 그 바이너리의 `/ask` 재개 스윕은 V7 질문 행을 읽을 수 없는 행으로 보류하다 포기 조건(5회/1시간)에서 안내 없이
      `ABANDONED`로 끝낸다. 지금은 `strategy: Recreate`라 혼합 플릿이 없지만, 그 블록을 지운 뒤의 두 레플리카 롤아웃에서는 구 레플리카가
      새 행을 같은 방식으로 쥐고 새 레플리카의 스윕이 회수한다. V6과 다른 점은 범위다(V6은 폴백이 붙은 행만, V7은 모든 행). 규칙:
      **V7을 넘는 롤백은 하지 않는다. 해야 하면 먼저 쓰기를 멈추고 아웃박스를 비우고(`PENDING`·`IN_PROGRESS` 0), V7 질문 행에 매달린 미완료
      `/ask` 턴(`agent_ask_turn` `PENDING`·`IN_PROGRESS`)도 끝낸다** — 질문 행이 이미 `SUCCESS`여도 턴이 남아 있으면 구 바이너리의 재개 스윕이 그
      행을 읽지 못해 포기 조건(5회/1시간)에서 안내 없이 `ABANDONED`로 끝내므로, 아웃박스만 비운 drain은 통과해도 턴을 잃는다. 예외는 규칙으로 막을 수
      없다: 배포 워크플로는 롤아웃·readiness 실패 시 이전 리비전으로 자동 `rollout undo`한다(`.github/workflows/deploy_action.yaml`). readiness는
      트래픽만 막으므로 V7 파드의 `@Scheduled` 생산자(스탠드업·리마인더·CVE 발송)가 이미 V7 행을 썼을 수 있다. 그때는 V7 빌드를 다시 배포해(fix forward) 보류된 행을 회수하는데, 기한은 아웃박스 행의
      `give-up-after-hours`(기본 24시간)가 아니라 **미완료 `/ask` 턴의 1시간**이 먼저 온다. 남은 것은 두 쿼리로 확인한다: `SELECT count(*) FROM
      outbox_message WHERE schema_version = 7 AND status IN ('PENDING','IN_PROGRESS')`와 `SELECT count(*) FROM agent_ask_turn t JOIN outbox_message o
      ON o.event_id = t.event_id WHERE o.schema_version = 7 AND t.status IN ('PENDING','IN_PROGRESS')`. 열 기본값 `DEFAULT 2`는 그대로 둔다(네이티브 INSERT가 없다); 대신
      `OutboxMessage` 생성자의 Kotlin 기본값(V2)을 없애 행을 만드는 코드가 버전을 반드시 고르게 했다. 가린 `toString`의 비용은
      #45와 같다(로그·디버거에서 원값이 보이지 않는다).
    상태: 유지. 근거: `repository/outbox/schema/AGENTS.md`(`OutboxSchemaVersion`), `repository/outbox/AGENTS.md`(`OutboundMessagePort`),
    `impl/command/AGENTS.md`(`SlackInboundMapper`), `impl/command/slack/AGENTS.md`, `impl/command/event/AGENTS.md`,
    `domain/.../command/inbound/AGENTS.md`, `domain/.../command/dto/AGENTS.md`.

51. **`google_oauth_state`의 만료 행은 전용 스케줄러가 매시간 지우고, OAuth 콜백은 더 이상 지우지 않는다 (2026-10-21).**
    `GoogleOAuthStateRetentionScheduler`(`@Conditional(OnGoogleCalendarEnabled)` — `CalendarSyncScheduler`와 같은 조건이다. 꺼진 환경에는
    V24가 없을 수 있어 매시간 ERROR가 나지 않게 한다)가 매시간(fixedDelay 1시간; 첫 실행은 인스턴스마다 기동 뒤 10~20분 사이에서 무작위 —
    `initialDelayString = "${random.long[600000,1200000]}"`, 결정 #43과 같은 이유로 `Recreate`로 함께 뜬 두 레플리카의 틱이 어긋난다)
    `expires_at`이 `clock.instant()`보다 이른 행을 네이티브 `DELETE FROM google_oauth_state WHERE expires_at < :before LIMIT :limit`(1000)로
    짧은 배치가 나올 때까지 틱당 최대 20회 지운다(`relay/purgeInBatches`, 아웃박스·에이전트 purge와 같은 루프). 실패는 `containFailure`로
    가둔다 — `Exception`은 테이블과 이미 지운 행 수를 남기는 ERROR 하나, `Error`는 전파(결정 #48). `completeConnection`의 쓰기 트랜잭션은
    이제 `consume`만 한다. 이유: 그전까지 만료 행은 콜백이 성공했을 때 그 consume 트랜잭션 안에서만 지워져(결정 #36, V24 헤더), 버려진
    `/calendar connect`의 state가 누군가의 콜백이 성공할 때까지 쌓였다(`service/calendar/AGENTS.md`가 2026-10-15부터 열린 후속으로 적어 둔 것).
    스케줄러가 테이블을 묶으면 콜백의 정리는 겹치는 일이고, 빼면 사용자의 브라우저 응답 경로에서 range `DELETE`와 그 락이 사라진다.
    - **컷오프가 `now`여도 안전하다 — 같은 순간에 잡은 컷오프끼리.** `consume`은 `expires_at > :now`인 행만 받으므로 같은 `now`에서
      `expires_at < now`인 행은 claim될 수 없다. 두 시각이 다르면 겹칠 수 있다 — 콜백이 `now`를 잡은 뒤 `UPDATE` 전에 멈춘 사이 더 늦은
      `now`의 정리가 만료 직전이던 행을 지우거나, 레플리카 사이 시계가 δ만큼 어긋나 앞선 쪽의 정리가 state 유효 시간의 마지막 δ를 줄인다
      (NTP라면 밀리초). 어느 쪽이든 사용자는 만료 직전의 링크에서 `INVALID_STATE`를 받을 뿐이고, 옛 콜백 정리(`deleteExpired(before = now)`를
      콜백을 받은 레플리카가 돌림)도 같았으므로 유예를 두지 않는다.
      소비된 행도 만료 시각이 지나면 함께 지워지는데, 그 state로 다시 온 콜백은 행이 있을 때와 똑같이 0행 `UPDATE` → `INVALID_STATE`다.
    - **기능을 끄면 정리도 멈춘다.** 꺼진 배포에는 스케줄러도 콜백도 없어 이미 쌓인 state가 남는다 — 행이 작고, 다시 켜면 첫 틱들이 비운다.
    - **배치 크기는 상수(1000)이고 설정 키는 없다.** 행이 작고(문자열 둘과 시각 셋) connect 한 번에 한 행이 생기므로 틱당 최대 2만 행이면
      충분하다(YAGNI). 필요해지면 `slack.app.calendar.google` 아래에 키를 더한다.
    - **트랜잭션 밖에서 돈다.** 결정 #43의 규칙 그대로 각 `DELETE … LIMIT`이 자기 트랜잭션의 처음이자 유일한 문장이다
      (`GoogleOAuthStateRepositoryImpl.deleteExpired`의 `@Transactional`, 결정 #24). 스케줄러나 `purgeOnce`에 `@Transactional`을 달면 락을
      배치마다가 아니라 끝까지 쥔다.
    - **락 노출 (추론, 미측정).** 아래는 문장이 `expires_at` 인덱스 range를 탄다는 전제다(그 실행 계획은 아래 "마이그레이션 없음"처럼
      `EXPLAIN`으로 확인하지 않았다; InnoDB는 실제로 훑은 레코드를 잠그므로 계획이 다르면 잠그는 범위도 다르다). 스케줄러의 `DELETE`는
      REPEATABLE READ에서 훑은 범위를 next-key로 잠근다. 새 state의 `INSERT`(`expires_at = now + state-ttl-minutes`)는 대개 그 범위 밖이라
      무관하지만, 살아 있는 state가 하나도 없고 그 배치의 스캔이 마지막 만료 행을 지나 supremum에 닿으면(만료 행이 `LIMIT`보다 많으면 앞
      배치들은 거기까지 가지 않는다) 그 간격 때문에 그 배치가 커밋될 때까지 짧게 기다린다. connect 트랜잭션은 그 뒤 `google_oauth_state`를 더 잠그지 않으므로 순환 대기는
      없다. 반대 방향도 있다 — 살아 있는 state가 없을 때 아직 커밋되지 않은 connect의 `INSERT`가 범위 바로 다음 항목이면 스케줄러가
      그 슬래시 트랜잭션의 커밋까지 기다린다(순환 없음). consume `UPDATE`는 기본 키로 그 state 한 행만 잠그고(없는 state면 그 자리의 기본 키
      간격; 만료된 state로 온 콜백이면 그 행이 스케줄러의 범위 안이라 둘 중 하나가 짧게 기다린다) 같은 트랜잭션에서 이 테이블을 더 잠그지
      않는다. 두 레플리카의 정리가 겹치면, 두 문장이 같은 range 경로를 쓰는 한 같은 인덱스를 같은 순서로 훑으므로 뒤쪽이 기다렸다가 이어
      지운다(결정 #43의 MariaDB 실측과 같은 형태). `/calendar disconnect`의 사용자별 `DELETE`(슬래시 트랜잭션)가 같은 만료 행을 두고 겹치면 두 `DELETE`가 다른 인덱스
      (`slack_user_id`·`expires_at`)로 같은 행의 락을 반대 순서로 잡으므로 드물게 데드락이 날 수 있다 — 결정 #36 시절 콜백 정리(consume
      트랜잭션의 range `DELETE`)와 같은 노출이다. InnoDB는 대개 가벼운 쪽을 롤백하므로 보통 disconnect 슬래시가 지고(결정 #44의 오류 안내 →
      사용자가 다시 실행), 스케줄러가 지면 ERROR 뒤 다음 틱이다. 수용한다.
    - **기각:** ① 콜백의 정리도 함께 두기 — 스케줄러와 겹치는 일이고, 사용자 응답 경로에 range `DELETE`와 그 락을 남긴다; ② 60초
      `CalendarSyncScheduler` 틱에 얹기 — 결정 #43이 ②를 기각한 것과 같은 이유로 60초마다 `DELETE`가 돌고, 미러 틱의 시간 예산
      (`sync-tick-budget-seconds`)을 나눠 쓴다; ③ 배치 크기 설정 키 — 위의 YAGNI.
    - **마이그레이션 없음.** `idx_google_oauth_state_expires_at`는 V24에 있다(다음 번호는 그대로 V29). 출하된 스크립트는 고치지 않는다 —
      V24 헤더의 "Expired state rows are purged by the OAuth callback, in the transaction that consumes a state and only after a successful
      consume."와 V27 헤더의 "Size" 문단("Expired states are deleted only when some callback succeeds …")은 이제 옛 동작을 적은 것이다. 이
      릴리스 전부터 돈 DB에는 버려진 state가 쌓여 있을 수 있고, 첫 실행들이 틱당 최대 2만 행씩 비운다. 이 문장이 MariaDB에서
      `expires_at` 인덱스 range를 타는지는 `EXPLAIN`으로 확인하지 않았다(미측정; 결정 #43 ①의 다른 테이블들은 range였다).
    - **H2 스펙의 한계는 결정 #43과 같다.** `JpaGoogleOAuthStateRepositoryTest`(H2 기본 모드)는 배치 삭제를 테스트 케이스마다 한 번만
      부른다 — H2 2.4.240이 두 번째 호출부터 `DELETE … LIMIT`의 수를 잘못 셌다(그 관찰은 `MODE=MariaDB`에서였고 기본 모드에서는 확인하지
      않았다). 스케줄러 스펙은 수를 MockK로 준다.
    상태: 유지. 근거: `service/calendar/AGENTS.md`(`GoogleOAuthStateRetentionScheduler.kt`, `CalendarConnectionService.kt`),
    `repository/calendar/AGENTS.md`, `GoogleOAuthStateRetentionSchedulerTest`, `CalendarConnectionServiceTest`, `CalendarConfigurationTest`,
    `JpaGoogleOAuthStateRepositoryTest`.
52. **스탠드업 프롬프트의 Skip은 멤버의 dispatch 행에 `skipped_at`을 기록하고, DM을 접고, 넛지에서 빼고, 요약에 `_(skipped)_`로
    보인다. 그 뒤에 답하면 답이 그대로 기록된다 (2026-10-26).** 프롬프트 DM(`buildDmNotice`)의 두 버튼 "Fill in standup"/"Skip"은 같은
    라우팅(`STANDUP_PROMPT`, `routingExtras = [sessionUid, routineUid]`)을 싣고, Skip은 `REJECT` 액션(`isCanceled()`)으로 온다. 그전까지
    `StandupFillContext`는 `isCanceled()`를 보지 않아 Skip도 답변 모달을 열었고, 아무것도 기록되지 않아 DM은 남고, 컷오프 전에 넛지를
    받고, 요약에는 `_(no response)_`로 나왔다(리뷰 F-01, 독립 리뷰 두 건이 확인; 그전에는 Skip을 정의한 결정이 없었다). 이제 Skip →
    `CommandIntent.RecordStandupSkip`(확인 = DM 프롬프트의 `UpdateNotice`) → `RecordStandupSkipEvent` → `StandupAnswerService.recordSkip`
    → `StandupRepository.recordSkip`이 답변과 같은 세션 행 락과 같은 규칙(`COLLECTING`이고 컷오프 전, 아니면 `SESSION_CLOSED`)으로
    `markMemberSkipped`를 부르고, 결과를 답변과 같은 확인 경로(`confirmOutcome`)로 DM에 접는다 — `RECORDED` → "Standup skipped for today.
    You can still answer with `/standup answer` in the routine's channel before the cutoff.", `SESSION_CLOSED` → "This standup has already closed.",
    `SESSION_NOT_FOUND` → "This standup could no longer be found, so nothing was recorded." 넛지 대상은 `sentMemberIds - answeredUserIds -
    skippedUserIds`이고, 요약은 답이 없는 skip 멤버를 `_(skipped)_`로 그린다. 이유: 버튼을 지우는 안과 Skip을 실제 상태로 만드는 안 중
    사용자가 뒤를 골랐다(2026-10-26).
    - **답이 이긴다.** skip 뒤의 `/standup answer`는 그대로 기록되고(`recordAnswer`는 `skipped_at`을 보지 않는다) 요약은 그 답을 보인다.
      `/standup answer`로 답해 프롬프트가 접히지 않은 채 남아 있다가 Skip을 누른 경우도 답은 지워지지 않는다 — 그때 DM이 "Standup
      skipped for today."로 접히는 어색함은 수용한다. `skipped_at`은 지우지 않는다. 넛지는 어느 쪽이든 그 멤버를 빼고, 요약은 답을 먼저
      본다.
    - **멱등.** `UPDATE … WHERE skipped_at IS NULL`이라 두 번째 클릭은 0행을 바꾸고 결과는 그대로 `RECORDED`다(첫 시각 유지). `dm_status`는
      조건에도 값에도 넣지 않는다 — DM 전달 상태를 설명하는 열이고, 스케줄러가 보내지 못한 dispatch를 `FAILED` + `"skipped: …"`로
      은퇴시키는 `markDispatchSkipped`와는 별개다.
    - **이벤트 타입은 `STANDUP_ANSWER_SUBMIT`을 재사용한다.** 내부 이벤트(`isInternal = true`)의 `type`은 어디서도 라우팅에 쓰이지 않고,
      확인 `UpdateMessage`도 답변과 같은 `confirmOutcome`을 거친다. 새 상수는 `CommandDetailType.createContext`의 exhaustive `when`에
      자리를 요구할 뿐 얻는 것이 없다.
    - **아웃박스 shape — 스키마 버전은 그대로 V7.** `MessageContent.StandupSummary`에 `skippedUserIds: Set<String> = emptySet()`이 붙었다.
      필드가 없는 이전 행은 기본값으로 읽히고(`OutboundMessageCodecTest`의 고정 행), 이 필드를 모르는 V7 바이너리(롤아웃 중 구 레플리카,
      롤백)는 Jackson 3이 모르는 키를 무시하므로 skip 멤버를 `_(no response)_`로 그릴 뿐이다. V3 연쇄처럼 내용이 사라지는 것이 아니라
      표시만 옛 동작으로 돌아가므로 보류(새 버전)할 이유가 없다([events-and-outbox.md](events-and-outbox.md)의 버전 규칙).
    - **마이그레이션 V29.** `standup_session_dispatch.skipped_at DATETIME NULL`, 이 릴리스 전에 적용한다(모든 dispatch 읽기가 열을
      고른다). 이전 릴리스 옆에서도 안전하고, 롤백하면 이전 바이너리의 Skip은 다시 모달을 연다. MariaDB 12.3.3 실측(2026-10-26, 일회용 DB `cc_verify_1026`, V4·V6 위에 적용): 첫 적용 오류·경고 없음, 재적용은 Note 1060만, `skipped_at datetime DEFAULT NULL`, 기존 행 NULL 유지, `markMemberSkipped` UPDATE 는 1행→0행(첫 시각·`dm_status` 유지). 기록 `.omc/handoffs/2026-10-26-review-high-fixes/mariadb-v29/`.
    - **기각:** ① Skip 버튼 제거 — 사용자 선택에서 기각; ② `dm_status`에 새 값 — DM 전달 상태와 멤버의 의사를 한 열에 섞고, 기존 CAS
      조건(`dm_status = 'PENDING'` 등)과 `DispatchStatus` 분기를 모두 건드린다; ③ 빈 답변 행으로 skip 표시 — 요약은 `(blank)` 답으로,
      `/standup answer` 후보 선택(결정 #39)은 "답함"으로 읽는다.
    상태: 유지. 근거: `entity/context/form/AGENTS.md`(`StandupFillContext.kt`), `service/standup/AGENTS.md`(`StandupAnswerService.kt`,
    `StandupSchedulingService.kt`, `StandupSummaryService.kt`), `repository/standup/AGENTS.md`, `db/migration/AGENTS.md`(V29),
    `StandupFillContextTest`, `StandupAnswerServiceTest`, `StandupSchedulingServiceTest`, `StandupSummaryServiceTest`,
    `StandupRepositoryImplTest`, `ModalTemplateBuilderTest`, `OutboundMessageCodecTest`.
53. **`/meetup list`의 Reschedule 버튼은 회의의 현재 시작 시각을 버튼 값의 네 번째 토큰으로 싣고, 재조정 모달은 그 값으로 날짜·시간
    선택기를 채운다. 그 토큰이 없는 이전 릴리스의 버튼만 지금 시각으로 연다 (2026-10-26).** 호스트 버튼
    (`ModalBlockBuilder.hostMeetingActionsBlock`)의 Reschedule 값은 `<listKey>,MEETING_RESCHEDULE_REQUEST,<meetingUid>,<startAt>`이고
    `<startAt>`은 `MeetingDto.startAt`을 `DateTimeFormatter.ISO_LOCAL_DATE_TIME`으로 쓴 것이다(`2026-11-03T10:00:00`). Add participant·Cancel
    값은 세 토큰 그대로다. 파서는 타입 뒤의 토큰을 모두 `routingExtras`로 넘기고(토큰마다 `URLDecoder`를 거치지만 ISO 문자열에는 바뀌는
    문자가 없다), 매퍼(`SlackInboundMapper`)는 목록 핸들을 든 제출에서만 `[2]`를 빼므로 block action의 `routingExtras`는
    `[meetingUid, startAt]` 그대로 도메인에 닿는다. `RescheduleMeetingContext`가 `[1]`을 `LocalDateTime.parse`로 읽어(`runCatching`,
    실패하면 `null`) `ModalForm.Reschedule.currentStartAt`에 싣고, `SlackOutboundStager`가 그 값을
    `openRescheduleMeetingModalRequest(currentStartAt = …)`에 넘긴다. 이유: 그전까지 스테이저는 늘 `LocalDateTime.now()`를 넘겨 모달이
    오늘 날짜·지금 시각으로 열렸다. 시간만 고친 호스트는 회의를 오늘로 옮겼고, `MeetingRescheduleService`는 과거 시각만 거절하므로
    "오늘, 더 늦은 시각"은 통과해 참가자에게 공지되고 Google Calendar 미러에도 반영됐다(리뷰 G-01; 검증 레인 V4a가 실제 시작을 주는 다른
    경로가 없고 스테이저에는 `MeetingRepository`가 없음을 확인했다).
    - **이력.** 처음 커밋 `dc2b9044`(2026-08-25)는 그 호출 위에 "Message carries no stored start; the host adjusts both pickers anyway."라는
      주석으로 이 한계를 적었지만, 주석 정리 `90c747ab`(2026-09-21)가 그 줄을 지워 기록이 남지 않았고 매개변수 이름 `currentStartAt`은
      받는 값과 반대로 읽혔다. 이름은 그대로 둔다 — 이제 받는 값이 이름과 맞는다.
    - **`currentStartAt`은 nullable이다 — 이전 버튼 때문에.** 이전 릴리스가 그린 목록 메시지는 배포 뒤에도 눌리고 세 토큰만 싣는다. 그
      부재를 나타내야 하므로 도메인은 `null`을 싣고 `now()`로 메우지 않는다. 파싱할 수 없는 네 번째 토큰도 `null`이다. `null`이면
      스테이저가 회의 uid를 남기는 WARN 하나와 함께 `LocalDateTime.now()`로 옛 동작을 지킨다(스테이저에는 `Clock`이 없다). 이 폴백은 이전
      버튼만을 위한 것이다 — 새 목록의 버튼은 늘 시작을 싣는다.
    - **값은 목록을 그린 시점의 시작이다.** 목록을 그린 뒤 다른 목록에서 재조정했다면 옛 목록의 버튼은 옛 시작을 채운다. 선택기의
      초기값일 뿐이고 호스트가 보고 고치므로 수용한다.
    - **마이그레이션·아웃박스 영향 없음.** `ModalForm`은 아웃박스로 가지 않고(모달은 trigger 만료 때문에 스테이저가 그 자리에서 렌더링한다)
      버튼 값은 Slack 메시지에만 있다. Reschedule 값은 약 120자다.
    - **기각:** ① 스테이저에서 `MeetingRepository`로 시작을 조회 — `views.open`의 trigger가 약 3초 안에 쓰여야 하는 동기 경로에 DB 읽기를
      더하고 스테이저를 회의 저장소에 묶는다. 목록을 그릴 때 이미 가진 값을 다시 읽을 이유가 없다; ② `Clock` 주입만 — 테스트에서 시각을
      고정할 수 있을 뿐 모달은 여전히 오늘로 열려 결함이 남는다.
    상태: 유지. 근거: `templates/AGENTS.md`(`ModalBlockBuilder.kt`, `ModalTemplateBuilder.kt`), `impl/command/AGENTS.md`
    (`SlackOutboundStager.kt`), `entity/context/form/AGENTS.md`(`RescheduleMeetingContext.kt`), `command/outbound/AGENTS.md`(`ModalForm.kt`),
    `RescheduleMeetingContextTest`, `OutboundMessageTest`, `SlackOutboundStagerTest`, `ModalTemplateBuilderTest`,
    `SlackInteractionRequestParserTest`, `ViewSubmissionChannelRoutingRegressionTest`.

54. **에이전트 턴은 사이드카 호출 전 구간을 `containFailure`로 가두고, 풀 스레드로 새는 것은 uncaught 처리기가 스레드 이름과 함께 남기며, `BoundedHttp`는 본문 읽기 인터럽트를 `InterruptedException`으로 되돌려 던진다 (2026-10-26, 결정 #48 보강).**
    `AgentConverseService.converse`의 사이드카 호출 전 구간(`lease.begin()`, `findProviderSessionId`, 컨텍스트 프롬프트, 턴 토큰 발급)은 `containFailure`로 가둔다.
    `Exception`은 `sessionKey`·`idempotencyKey`를 남기는 ERROR 하나와 `agent.turns{outcome=failed}`(지속 시간·이력 행 없음)로 끝나고, 리스가 없는 턴(멘션, 레저 기록에
    실패한 `/ask`)만 best-effort `FAILURE_MESSAGE` 스레드 답장을 남긴다. `/ask` 리스 턴은 아무것도 쓰지 않는다 — 여기서 `publishFailure`를 부르면 `settle`과 경합하므로
    행은 ask 스윕(고아 해제 뒤 재제공, `PENDING`이면 바로 재제공)에 맡긴다. 그래도 풀 스레드까지 새는 것(`Error`, 다시 던진 인터럽트, 사이드카 호출 뒤의 `Exception`)은
    `AgentTurnExecutor.newThread`가 붙이는 `AGENT_TURN_UNCAUGHT_LOGGER`가 스레드 이름과 함께 ERROR로 남기고 풀이 스레드를 대체한다(릴레이 실행기의
    `RELAY_UNCAUGHT_ERROR_LOGGER`와 같은 모양). 이유: 이전에는 DB 실패 하나가 답장·메트릭·키가 붙은 로그 없이 stderr 스택만 남기고 사라졌다(리뷰 D-01).
    같은 날, `BoundedHttp.sendWithinDeadline`은 본문 읽기 중 인터럽트를 `InterruptedException`(플래그 복원, 원인은 그 `IOException`)으로 바꿔 던진다. JDK의
    `BodyHandlers.ofInputStream` 본문은 버퍼 큐에서 받은 `InterruptedException`을 플래그를 세운 뒤 `IOException`으로 감싸므로, 그대로 다시 던지면 네 호출자
    (`GoogleOAuthClient` revoke·폼 POST, `GoogleCalendarClient`, NVD·GitHub 어댑터)가 일반 전송 실패로 셌다 — 이미 200을 받은 revoke가 `false`가 되어 수동 삭제 안내
    DM이 나가고, 캘린더 동기화 시도 하나를 소모(리뷰 H-01). 네 호출자는 이미 `InterruptedException`을 먼저 잡아 되던지므로 바뀌지 않았다.
    - **대가.** 사이드카 전 실패로 센 `/ask` 런은 스윕이 다시 돌리면 나중에 `completed`로도 세어진다(런 단위 카운터라 `busy`와 같은 성격). 별도 태그는 두지 않았다.
    - **기각.** ① 리스 턴에서 `publishFailure` 호출 — `settle`과 경합; ② 실행기에 `TaskDecorator`만 추가 — 사이드카 전 구간의 안내·메트릭을 못 만든다.
    상태: 유지. 근거: `application/.../service/agent/AgentConverseService.kt`, `application/.../configurations/AgentConfiguration.kt`, `infrastructure/.../impl/http/BoundedHttp.kt`,
    `AgentConverseServiceTest`, `AgentTurnExecutorTest`, `BoundedHttpTest`, `GoogleOAuthClientTest`(루프백 서버에서 본문 읽기 중 인터럽트).

55. **회의 리마인더의 아웃박스 트랜잭션이 일시적으로 실패하면 claim을 `PENDING`으로 돌려주고 다음 틱이 다시 시도한다. 회의가 이미 시작된 리마인더는 보내지 않고 지운다 (2026-10-26).**
    `MeetingReminderSchedulingService.processReminder`는 claim 뒤의 `runInTx`(참석자별 아웃박스 DM 행 + `markReminderSent`)가 실패하면 예외를 가른다.
    `TransientDataAccessException`이거나 `CannotCreateTransactionException`이면 `releaseReminder(id, claimToken, now)`로 claim을 돌려주고 WARN을 남긴다. 여기에는 `ConcurrencyFailureException` 전부(락 대기
    초과·데드락으로 번역되는 `CannotAcquireLockException`/`PessimisticLockingFailureException`, 번역된 MariaDB 1020 `SnapshotIsolationConflictException`)와
    `QueryTimeoutException`, `TransientDataAccessResourceException`이 든다. `releaseReminder`의 쿼리는 `UPDATE meeting_reminder SET status = 'PENDING',
    claim_token = NULL, updated_at = :now WHERE id = :id AND status = 'SENDING' AND claim_token = :token`이다. 그 밖의 실패는 전처럼 `markReminderFailed`(`FAILED`)로
    간다. 이유: `FAILED` 행은 아무것도 다시 처리하지 않는다(`findDueBefore`는 `PENDING`만, `resetStuckSending`은 `SENDING`만 본다). 그래서 데드락이나 락 대기 한 번이
    리마인더를 영영 잃게 했다(리뷰 CX-01). DM 행은 같은 트랜잭션에서 롤백되므로 되돌려도 중복 발송이 없다. 커밋이 끝난 뒤에 예외가 났다면 행은 이미 `SENT`(토큰 null)라서
    release의 토큰 CAS가 0행으로 끝난다.
    - **재시도 상한은 회의 시작 시각이다(리뷰 E-01).** claim 전에 `item.startAt`이 `LocalDateTime.now(clock)`보다 뒤가 아니면, claim하지 않고 `discardReminder(id, 읽은
      scheduledAt)`로 지우고 WARN(`Discarded meeting reminder after its meeting started`)을 남긴다. 돌려준 행은 매 틱(60초) 다시 시도되다가 회의가 시작되면 지워진다.
      15분 오프셋이면 최대 약 15회다. 장애 전에 만들어졌거나 `SENDING`에서 리셋된 `PENDING` 행이 회의 시작 뒤에 "starts in N minutes"로 나가던 것도 같은 검사가 막는다.
      `scheduledAt` 나이에 상한을 두지 않는다 — 돌려준 재시도까지 버린다. `findDueBefore`에 하한을 두지도 않는다 — 하한 밖의 행이 영원히 `PENDING`으로 남는다.
    - **넛지 수신자는 claim 뒤 세션 락 아래에서 다시 읽는다(리뷰 CX-02).** `StandupSchedulingService.nudgeCandidate`는 스냅샷으로 사전 필터만 하고, `runInTx` 안에서
      `claimNudge`(locking UPDATE)가 성공하면 `findLockedNudgeRecipients(sessionUid)`(`MANDATORY`, `findLockedBySessionUid` 뒤 lazy 컬렉션)로 sent/answered/skipped를
      다시 읽어 그 결과의 non-responder에게만 DM을 큐에 넣는다. 남은 사람이 없으면 claim은 그대로 커밋하고 INFO만 남긴다. 그 사이 커밋된 답·Skip은 답변 경로가 같은 세션 락을
      잡으므로 재읽기에 보인다(H2 핸드셰이크 스펙; MariaDB `innodb_snapshot_isolation` 아래 read view 시점은 미측정).
    - **기각.** ① 시도 횟수 컬럼(`attempt_count`) + N회 뒤 `FAILED` — 스키마 변경이 필요하고 시작 시각이 이미 자연스러운 상한이다. ② 모든 `DataAccessException`을
      일시적으로 보는 안 — 무결성 위반 같은 결정적 실패가 시작 시각까지 매분 반복된다. ③ 같은 틱 안의 즉시 재시도 — 락 경합이 풀릴 시간을 주지 못한다.
    - **남은 것.** 같은 행에서 결정적으로 반복되는 일시적 예외는 시작 시각까지 매분 WARN을 남긴다. 시작 직전에 늦게 나가는 리마인더 문구는 여전히 "starts in <offset>
      minutes"다. 커넥션 풀 고갈(`CannotCreateTransactionException`, `TransactionException` 계열)은 쓰기 전에 실패하므로 함께 돌려주지만(리뷰 L2), 트랜잭션 도중 연결이 끊긴 `DataAccessResourceFailureException`은 일시적으로 보지 않아 `FAILED`로 간다. 오프셋을 `stuckSendingThresholdMinutes`보다
      작게 두면 이미 시작한 회의의 행이 lookback(기본 2분) 안에서 다시 만들어졌다 지워질 수 있다(기본 설정에서는 1초 경계 한 번). MariaDB에서 실측하지 않았다.
    상태: 유지. 근거: `application/.../service/meeting/MeetingReminderSchedulingService.kt`, `application/.../service/standup/StandupSchedulingService.kt`,
    `infrastructure/.../repository/meeting/{MeetingReminderRepository,MeetingReminderRepositoryImpl,JpaMeetingReminderRepository}.kt`,
    `infrastructure/.../repository/standup/{StandupRepository,StandupRepositoryImpl}.kt`, `MeetingReminderSchedulingServiceTest`, `MeetingReminderRepositoryImplTest`,
    `StandupSchedulingServiceTest`, `StandupRepositoryImplTest`.

56. **빈 등록과 스케줄 잡의 규칙을 실제 관행에 맞춘다 — 유스케이스 서비스·스케줄러·리스너는 컴포넌트 스캔, 모드·기능 플래그로 갈리는 빈과 인프라 어댑터는 `configurations/`의 `@Bean`이고, `@Scheduled`는 테스트가 부를 공개 진입점이 있으면 로직과 같은 클래스에 둔다 (2026-10-26).**
    [coding-style.md](coding-style.md)는 "어댑터·서비스는 스캔보다 `@Bean`"과 "`@Scheduled`는 얇은 `*Scheduler`에, 로직은 `*SchedulingService`에"를 규칙으로 적었지만
    코드는 반대였다(리뷰 P-03·P-04, 2026-10-23). 2026-10-26 실측(`fddb1fc3`): `application`의 스캔 대상은 39개 파일(`@Service` 25·`@Component` 15,
    `SlackMessageRelayServiceImpl.kt`에 둘)이고 `@Bean`은 59개(`configurations/` 56, `security/` 3)다. `@Bean`은 모드·기능 플래그로 갈리는 빈(폴링/Debezium 처리기,
    Kafka/앱 이벤트 발행기, CVE·캘린더·MCP 레인, 그리고 스펙이 바꿔 끼우도록 `@ConditionalOnMissingBean`을 단 에이전트 레인 `AgentConfiguration` —
    유스케이스 서비스 `AgentConverseService`도 여기)과 인프라 어댑터(`infrastructure` `JpaConfiguration`의 저장소 어댑터 16개, Slack 파이프라인 어댑터와 `CommandExecutor`,
    HTTP 클라이언트, 실행기·스케줄러 풀)에 쓰였다. `@Scheduled` 14개(13개 클래스) 중 분리 형태는 네 잡(`StandupScheduler`, `MeetingReminderScheduler`,
    `DailyAgendaScheduler`, `CalendarSyncScheduler` → `CalendarSyncService`)이고, 나머지 10개는 로직과 같은 클래스에서 공개 1회 실행 함수(`recoverOnce`/`purgeOnce`/
    `resumeOnce`, 다섯 클래스)나 공개 tick(`pollPending`, `CveCollector.tick`, `CveSummaryWorker.tick`, `CveNotificationDispatcher.immediateTick`/`digestTick`)을
    테스트 진입점으로 둔다. 이유: 규칙이 관행과 반대면 규칙을 따른 새 코드가 주변과 어긋나므로, 관행을 규칙으로 올렸다.
    - **분리는 단계별 실패 격리가 필요할 때 고려하는 형태이지 불변 기준은 아니다.** 트랜잭션 경계 수로는 가를 수 없다 — 분리된
      `DailyAgendaSchedulingService.sendDailyAgenda`는 틱마다 `runInTx` 하나이고, 분리되지 않은 `CveNotificationDispatcher`는 쌍·사용자마다 `runInTx`를
      연다. 분리된 넷의 공통점은 얇은 스케줄러가 서비스 단계마다 `containFailure`로 실패를 가둔다는 것이다(스탠드업 4단계, 리마인더 2단계, 안건·캘린더
      1단계). 다만 미분리 잡도 같은 격리를 한다(`AgentRetentionScheduler.purgeOnce`는 테이블마다, CVE 수집은 수집과 정리를 따로 가둔다) — 실패 격리는
      클래스 배치와 무관하게 `containFailure`로 요구한다.
    - **예외.** `CalendarSyncScheduler`와 `GoogleOAuthStateRetentionScheduler`, `GoogleOAuthCallbackController`는 스캔 대상이면서
      `@Conditional(OnGoogleCalendarEnabled::class)`을 달고, `SocketModeReceiver`는 `@Component` + `@Profile("local")`이다.
    - **기각.** ① 코드를 옛 규칙에 맞춰 스캔 대상을 `@Bean`으로 옮기는 안 — 얻는 것 없이 배선 변경만 크다; ② 스케줄 잡 10개를 `*Scheduler` + 서비스로 쪼개는 안 —
      공개 1회 실행 함수와 공개 tick이 이미 같은 테스트 진입점을 준다.
    상태: 유지. 근거: [coding-style.md](coding-style.md)의 빈 등록·스케줄 잡 행, `application/AGENTS.md`, `application/src/main/kotlin/dev/notypie/application/{configurations,service}/**`,
    `application/.../security/SlackRequestVerificationConfiguration.kt`, `infrastructure/src/main/kotlin/dev/notypie/configurations/JpaConfiguration.kt`.

## 빌드·런타임

19. **`io.spring.dependency-management` 플러그인을 쓰지 않는다.** 이유: BOM 버전(kotlinx-coroutines 등)을
    Gradle platform으로 직접 통제하기 위해. 상태: 유지. 근거: `build.gradle.kts` `subprojects` 위 주석.
20. **Jackson은 모듈별 선언, 루트 `subprojects`에 넣지 않는다.** 이유: `domain` 클래스패스를 Jackson-free로.
    상태: 유지. 근거: `infrastructure/build.gradle.kts`, `application/build.gradle.kts` 주석, 가드 테스트.
21. **Web 컨테이너는 Jetty(Tomcat 제외).** 이유: Spring Boot 4가 Undertow를 지원하지 않아 대안으로 선택.
    상태: 유지. 근거: `application/build.gradle.kts` 주석.
22. **Flyway를 쓰지 않는다. `V*.sql`은 수동 적용 관례.** 번호 충돌 주의 — 새 번호를 잡기 전에 origin 브랜치가
    선점했는지 확인한다. 프로덕션은 `ddl-auto: none`. 상태: 유지. 2026-09-21: `local`/`real`(현 `slack-live`)에 남아 있던 무효
    `spring.flyway.enabled: false` 키를 제거 — 설정 어디에도 flyway 키가 없다. 근거: `application/src/main/resources/`,
    `CveBotPlan.md` §0.5-8, `Handoff.md` 리뷰 표. → [dev-environment.md](dev-environment.md)
23. **Socket Mode는 로컬 전용, 프로덕션 인바운드는 HTTP.** 이유: 프로덕션은 Request URL 기반이 이미 서 있고
    Socket Mode는 터널 없이 로컬 테스트하기 위한 장치. 두 진입점은 같은 서비스 빈을 호출한다. 상태: 유지.
    근거: `application/AGENTS.md`, `CveBotPlan.md` §0.5-5.
24. **`@Transactional`의 자리 — 경계는 명령 진입점(슬래시 서비스 메서드, 멘션·인터랙션 핸들러)이 열고, 그 밖의 DB 접근은 Repository `Impl`(`open class`)에 (2026-10-26 정정).** 이유: 답장 행은 진입점 트랜잭션의 BEFORE_COMMIT 리스너가 쓰고, 리포지토리의 CGLIB 프록시가 걸리는 자리는 한 곳으로.
    상태: 유지(2026-10-26 범위 정정). 근거: `Refactor.md` §5-1, Phase 10 노트.
    **정정(2026-10-26):** `Impl`은 유일한 자리가 아니다 — 이 규칙대로 서비스에서 `@Transactional`을 빼면 슬래시 답장이 조용히
    사라진다. 경계는 명령 진입점이 연다: 슬래시 서비스 메서드의 `@Transactional`(`MeetingServiceImpl.handleMeeting`,
    `StandupSlashServiceImpl.handleStandup`, `CveSubscriptionSlashServiceImpl` 3개, `CveQuerySlashServiceImpl.handleLatest`,
    `AskSlashService.handleAsk`, `CalendarSlashService.handleCalendar`), 멘션 핸들러의 `TransactionTemplate`, 인터랙션 핸들러의
    `PlatformTransactionManager.getTransaction`. DB를 쓰고 답장을 스테이징하는 리스너도 서비스 메서드에 건다(`RoleManagementService`,
    `CveSubscriptionService`, `CveLatestQueryService`, `CveOpsService`, `StandupRoutineOpsService`, 모달 열기 실패 리스너
    `MeetingServiceImpl.onDeclineModalOpenFailed`·`StandupAnswerService.onStandupModalOpenFailed`) — 2026-10-26 기준 `application`의
    `@Transactional` 15개 전부가 이 두 부류다. 이유: 답장 행을 쓰는 `SlackMessageRelayServiceImpl.saveOutboxMessage`가
    `@TransactionalEventListener(phase = BEFORE_COMMIT)`이고 `fallbackExecution`이 기본(false)이라 트랜잭션 없이 발행된
    `OutboundMessageEnqueued`는 행을 남기지 않는다([events-and-outbox.md](events-and-outbox.md)의 "DM never sent"). 모달 열기 실패
    이벤트는 `ViewOpenDeferral.afterBoundary`가 `views.open`을 경계 커밋 뒤로 미루어 트랜잭션 밖에서 발행되므로, 그 두 리스너의
    `@Transactional`은 합류가 아니라 트랜잭션을 새로 연다.
    CGLIB 이유는 리포지토리 `Impl`에 그대로 남는다 — `JpaConfiguration`의 `@Bean`으로 등록돼 스테레오타입이 없으므로
    `@Transactional`을 단 13개는 `open class`이고, 없는 3개(`AgentSessionRepositoryImpl` 등)는 아니다. 리뷰 P-01(2026-10-23).
    → [coding-style.md](coding-style.md)의 트랜잭션 행
25. **툴체인은 최신 GA를 따른다 — Java 25, Kotlin 2.4, Spring Boot 4.1, Gradle 9.8.1.** 이유: (근거 미기록 — 채워
    넣을 것). 상태: 유지. 주의: 루트 `build.gradle.kts`의 `java { toolchain }` / `kotlin { compilerOptions }` 블록은
    `subprojects` 밖이라 세 모듈에 적용되지 않는다(2026-09-22 실측: 모듈 `freeCompilerArgs=[]`, 로컬 데몬 JDK 21로
    컴파일). `subprojects`로 옮기는 작업은 `-Xjsr305=strict` 활성화 파급 때문에 단독 PR로 남겨 둔다(review.md C6). 근거: `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`.
26. **`gradle.properties`는 생성물(git-ignore). 공유 설정은 `gradle-config/` 프리셋을 고친다.** 상태: 유지.
    근거: `gradle-config/README.md`.
35. **Jackson 버전은 Boot BOM이 아니라 루트 `extra["jacksonVersion"]`(3.2.3 — 2026-10-06 Dependabot #20, jackson-core·databind High 권고 GHSA-7hhh-6rmp-j9qf·GHSA-cxp5-3px4-pw24·GHSA-wv8q-qhhj-9h54 수정판)이 정한다.** Boot 4.1.1 BOM은 3.1.5를
    관리하지만 모듈의 `api(platform(jackson-bom))`가 그 위를 덮는다. Spring이 시험한 조합에서 벗어난다는 지적
    (`review_skill.md` 3.1)을 소유자가 2026-10-01에 보류하고 덮어쓰기를 유지하기로 했다. 이유: (근거 미기록 — 채워
    넣을 것). 상태: 유지. 주의: Boot를 올릴 때 BOM의 Jackson 버전과 이 값을 함께 비교한다. 근거: `build.gradle.kts`,
    #20.
48. **경계는 `Exception`만 잡고 `Error`는 전파한다 — 행 단위 루프도 예외가 아니다 (2026-10-15).** 스케줄러 틱·행 루프·리스너와
    전송 가장자리·MCP 도구 본문과 감사 쓰기는 `containFailure` / `runCatchingExceptions`(`infrastructure/.../common`)를 쓰고,
    `runInTx`는 안쪽·바깥쪽 모두 `catch (Exception)`(액션의 `Error`를 기록해 롤백이 어떤 예외로 실패해도 그 `Error`를 다시 던짐; relay 상태 기록도 `runInTx` 경유),
    `RetryService`는 `RetryException`의 원인이 `Error`면 그것을 던지고 정책은 `excludes(Error)`로 원인 체인에 `Error`가 있는 실패를 재시도하지 않는다. 이유:
    OOM은 `-XX:+ExitOnOutOfMemoryError`로 이미 JVM을 끝내고, `LinkageError`는 배포 전체의 문제라 행 격리가 얻는 것이 없으며,
    `Error`를 `Result.failure`로 바꾸면 사용자 거절 문구·`FAILED` 감사·재시도 복구로 위장된다. 대가: 데이터에 따라 결정적으로
    나는 `Error`(예: 한 행의 `StackOverflowError`)는 그 틱을 끝내고, 롤백되는 claim(스탠드업 DM, CVE 쌍, 일일 안건)은 다음 틱에도
    맨 앞에 와서 매분 ERROR를 남긴다. 그런 일이 실제로 생기면 답은 그 행에 시도 횟수를 다는 것이지 `Error`를 잡는 것이 아니다.
    경계별 결과: 스케줄러 틱은 Spring이 로그를 남기고 다음 틱이 돈다; 릴레이 실행기는 그 스레드가 죽고 풀이 대체하며
    `RELAY_UNCAUGHT_ERROR_LOGGER`가 스레드 이름과 함께 ERROR를 남긴다(행은 스윕이 처리); Socket Mode(로컬)는 로그 한 줄 뒤 SDK
    메시지 루프 하나가 영구히 멈춘다; MCP 도구는 `FAILED`/`Aborted` 감사 행을 남기고 전파한다; `/standup setup`·`views.open`은
    요청까지 올라가 500이 된다(코드 추적, 미실행). **예외 조항 — CDC 리스너 경계 한 곳(2026-10-19 사용자 결정 "리스너에서 번역").**
    `DebeziumLogTailingProcessor.consume`은 `Error`를 잡아 레코드 좌표·eventId와 함께 ERROR로 남기고 `ListenerErrorException`으로
    감싸 다시 던진다. 이유: spring-kafka 4.1은 리스너의 `Error`를 오류 처리기에 넘기지 않고 컨테이너를 멈추며
    (`Stopping container due to an Error`) 재시작하지 않으므로, 행 하나의 `Error`가 파드 재시작 전까지 CDC 전달 전체를 멈추고
    파싱 중 `Error`는 오프셋이 커밋되지 않아 재시작할 때마다 다시 멈추는 poison pill이 된다. 래퍼는 poison·재시도 없음으로
    분류돼 DLT로 한 번 가고(파싱 중 `Error`는 이 변경 전과 같은 결과) 컨테이너는 계속 돈다. OOM은 플래그가 있는 곳(Dockerfile,
    `run`)에서는 여전히 `-XX:+ExitOnOutOfMemoryError`로 JVM을 끝내고, 플래그 없는 `bootRun`·IDE·테스트 JVM에서는 다른 `Error`와
    같이 DLT로 간다. 이 조항을 다른 경계로 넓히지 않는다.
    기각: 행 루프만 `Error`를 격리(대안 B) — 위 이유. 상태: 유지. 근거: `infrastructure/src/main/kotlin/dev/notypie/common/AGENTS.md`,
    [error-handling-and-validation.md](error-handling-and-validation.md), `service/relay/AGENTS.md`.

49. **거절 폴백 문구는 대화 종류(DM·그룹 DM·채널)를 가르고, `/ask` 질문이 거절되면 "답하지 않았다"와 질문 원문을 함께 보내며, 인용은 유니코드 줄바꿈 전부에서 나눈다 (2026-10-20).**
    2026-10-20 라이브 실측(`.omc/handoffs/2026-10-20-live-slack.md`): 봇이 없는 비공개 채널과 1:1 DM은 `channel_not_found`로 거절되고 폴백 DM은
    도착하지만, `<#D…>`를 Slack이 상대 이름으로 렌더해 "I couldn't reply in TrulyMalware … invite me"가 됐다. `/ask`는 질문 행이 `FAILURE`가 되면
    `MessageDeliveredEvent`가 없어 `AgentAskThreadStarter`가 턴을 시작하지 않으므로(전달 훅 절) 요청자는 답도 질문 원문도 못 본다.
    해법: `ConversationTarget.kind()`가 id 첫 글자로 `DIRECT`(`D`)·`GROUP`(`G`)·`CHANNEL`을 가르고, `channelRefusedNotice`·`directFallbackOf`는
    DIRECT·GROUP에서 `<#…>` 없이 "그 대화에는 쓸 수 없다 — 봇이 있는 채널에서 다시"로 말한다. 어떤 문구도 봇 DM으로 안내하지 않는다 —
    운영 매니페스트는 메시지 탭이 읽기 전용(`messages_tab_read_only_enabled`)이고 앱은 `app_mention`만 구독하므로 "여기서 물어보라"는 성립하지 않는다
    (code-reviewer가 잡음; 개발 앱은 DM 탭이 열려 있어 라이브에서 못 걸렀다). `G` id는 그룹 DM과 2021년 3월 전에 만든 비공개 채널을 모두 가리키므로
    (둘 다 Slack 문서) GROUP 문구는 "that conversation"이다. `/ask`의 폴백은 `askRefusedNotice`가 "답하지 않았다"를 명시하고 질문을 `>` 인용(이스케이프, 1,500자 상한 —
    `takeEntitySafe`로 엔티티·서로게이트를 자르지 않음)으로 돌려준다 — 요청자가 봇이 있는 채널에 붙여넣을 수 있게. 한계(2026-10-22 정정):
    그룹 DM이 `C` id를 받는지는 확인되지 않았다(미확인) — Slack 문서에는 `G` id 그룹 DM(레거시 MPIM 객체 예시 `G024BE91L`)과 2021년 3월
    전에 만든 비공개 채널의 `G` id만 있고(그 뒤 비공개 채널이 `C`인 것은 라이브 `C0CA3FYCAT1`로 확인) `C` id 그룹 DM은 없으며, 라이브는 워크스페이스에 사람이 둘뿐이라 봇 없는 그룹 DM을 만들 수
    없어 재지 못했다. 받는다면 채널 문구가 나간다. 문서에 정의된 신호는 `conversations.info`의 `is_mpim`(Web API 호출과 읽기 스코프 추가)이다.
    슬래시 `channel_name`의 `mpdm-` 접두사는 Slack이 "이름은 바뀔 수 있으니 ID에 의존하라"고 하고, 문구를 만드는 호출처 13곳(처음 기록한
    14곳은 오기)이 채널 이름 없이 id만으로 대상을 만들므로 보류 — 11곳은 `CommandBasicInfo.channel`(아웃박스 payload에 직렬화되는 타입),
    슬래시 실패 응답(`Command.kt`) 1곳은 `InboundCommand.channel`(여기만 곁에 `channelName`이 있다), 스탠드업 응답 확인 1곳은 모달 메타데이터의
    채널 id다.
    기각(후속 후보): 폴백이 `AgentQuestion` 자체를 요청자 DM에 실어 턴을 DM 스레드에서 잇는 안 — `DirectReply.content`를 `Text`에서 `MessageContent`로
    넓히고 `U…` 채널 + `thread_ts` 게시를 라이브로 확인해야 한다. 인용 분할: Slack은 U+2028/U+2029를 줄바꿈으로 렌더하고 그 뒤 텍스트는 `>` 밖으로
    나오지만 Kotlin `lines()`는 CR/LF만 자른다 → `askedBy`·멘션 매퍼의 인용 복원·`askRefusedNotice`는 `domain/common/SafeText.kt`의 `splitLineBreaks()`(`Regex("\\R")`, LS·PS·NEL·VT·FF 포함)로 나눈다.
    `askedBy`는 2026-10-22부터 줄을 `templates/hardWrap`으로 바로 자른다 — 그 전에는 `splitSectionText`가 조각을 `\n`으로 묶은 뒤 다시 나눠서,
    두 분할을 함께 되돌려야만 테스트가 실패했다. 이때 1,450자를 넘는 줄의 공백뿐인 조각은 버리지 않고 남긴다(전에는 `packLines`가 버렸다) — 대가로 아주 긴 공백 줄이
    11,000자 본문 한도를 차지해 그 뒤 글이 채널의 인용에서 잘릴 수 있다(Codex 지적; 에이전트가 받는 질문 원문은 그대로).
    Slack 클라이언트는 슬래시 텍스트의 LS/PS를 보내기 전에 LF로 바꾸므로 UI 경로로는 재현되지 않고 API로 게시된 멘션 텍스트만 남는 경로다(심층 방어).
    빈 프롬프트 판정(`AgentChatContext`·`AskSlashContext`)은 2026-10-20에 `\R` 분할을 쓰는 `isBlankAcrossLineBreaks()`로 맞췄다 —
    `isBlank()`는 NEL(U+0085, `Cc`)을 공백으로 보지 않아 NEL만 있는 프롬프트가 통과했다. 2026-10-22에 `isVisiblyBlank()`로 바꿔 `Cc` 제어
    문자와 `Cf` 서식 문자(`[\p{Cc}\p{Cf}]`, U+E0001 같은 보충 평면 태그 포함)를 지운 뒤 `isBlank()`로 판정한다 — 그 전에는 U+200B나
    U+0001만 있는 질문도 사이드카로 넘어갔다. `\R` 줄바꿈은 모두 `Cc`이거나 `isBlank`가 공백으로 보는 U+2028·U+2029라 분할 없이도 같다.
    범주 판정이라 양쪽으로 어긋난다: 글자를 그리는 `Cf`(U+0600–U+0605, U+06DD 등)는 빈 것으로, 공백도 `Cc`·`Cf`도 아닌 보이지 않는 문자(이체
    선택자 U+FE0F, 한글 채움 문자 U+3164, U+2800)는 텍스트로 본다 — 어느 쪽도 혼자서는 질문이 되지 않아 받아들인다.

## CI/CD와 공급망

27. **문서만 바뀐 변경은 프로덕션에 절대 배포되지 않는다.** deploy 트리거와 lint·test의 push 트리거는 `!**/*.md`로 경로
    필터링한다. 예외는 `security_check.yaml`: 시크릿은 `.md`에도 숨을 수 있으므로 gitleaks는 `main` 대상 모든 push/PR에서
    돌고, CodeQL·의존성 그래프만 소스 변경에 게이트된다. 상태: 유지(2026-08-26). 정정(2026-09-28 기록, 2026-09-22 변경분): lint·test의
    `pull_request` 트리거는 required check가 pending으로 남지 않도록 일부러 `paths` 필터가 없다 — 문서만 바뀐 PR도 lint는 전체
    `ktlintCheck`를 돌고 test는 모듈 0개로 끝난다. 근거: `.github/workflows/*.yaml`, `.github/AGENTS.md`.
28. **보안 워크플로우 — CodeQL은 `build-mode: manual`.** 이유: `none` 모드는 Kotlin을 분석하지 않는다. 컴파일은
    `--no-daemon --no-build-cache`로 추적 가능한 JVM에서. 상태: 유지(2026-08-26).
29. **gitleaks 허용목록은 템플릿 파일 경로에만.** 실제 유출은 rotate + 히스토리 정리로 대응한다. 상태: 유지.
    근거: `.gitleaks.toml`.
30. **의존성 버전은 루트 `extra["x"] = "…"` 선언 + `$x` 참조.** 이유: Dependabot의 Gradle 파서가 `ext { set() }`
    블록과 `${rootProject.extra.get()}` 참조를 읽지 못한다 — dependabot-core `gradle/lib/dependabot/gradle/
    file_parser.rb`의 `PROPERTY_REGEX`(`${property(x)}` / `${x}` / `$x`만)와 `file_parser/property_value_finder.rb`의
    선언 정규식(`extra["x"] = "…"`, `extra.set("x", "…")`, `<ns>.apply { set(...) }`, Groovy `x = "…"`)이 근거.
    상태: 유지(2026-08-28). 근거: `build.gradle.kts`, `.github/AGENTS.md`, github.com/dependabot/dependabot-core.
31. **Dependabot 커밋은 `chore : …` 프리픽스, Kotlin 플러그인·Spring·테스트 라이브러리는 그룹 PR.** 상태: 유지.
    근거: `.github/dependabot.yml`.
32. **제출 라우팅은 sealed 변종이 결정하고, leaf는 파스 모델만 받는다 (Phase 11, 2026-09-21).** `view_submission`은
    `SubmissionRouter`가 변종 exhaustive `when`으로 라우팅하며 파싱(`*Parsed.from`)을 컨텍스트 생성 전에 끝낸다.
    잘못된/누락 제출은 `IgnoredSubmissionContext`(성공·무효과)로 fail-open — 모달은 3초 안에 200을 받아야 닫히고
    실제 불변식은 repository가 지킨다 — 하고 `SubmissionParseObserver` 카운터로 관측한다. 봉투 detailType이 아닌
    변종에서 판별자를 유도한다(불일치 쌍은 mapper가 만들지 않는다). `domain/command`의 명시적 캐스트는
    `EnvelopeCastGuardTest`가 축소 전용 baseline으로 금지한다(가드는 회귀 억제 장치이지 정합성 증명이 아니다).
    상태: 유지. 근거: `domain/.../entity/SubmissionRouting.kt`, `form/ParsedSubmissions.kt`,
    `SubmissionPipelineCharacterizationTest`, Codex 리뷰 2건(`.omc/artifacts/ask/`), `Refactor.md` Phase 11.
33. **배포 검증은 클러스터 내부 readiness로, 롤백은 리비전 단위로 (2026-09-28).** 공개 호스트 앞에는 이 저장소 밖의
    bearer 인증 계층이 있다(2026-09-28 실측: 존재하지 않는 경로를 포함해 조사한 모든 경로가 `401` +
    `WWW-Authenticate: Bearer`, `GET /actuator/health`만 이 앱 형식이 아닌 JSON `404`). 헬스에 200을 준 공개 URL이 없으므로
    공개 URL 게이트는 정상 배포도 실패로 판정하고, 그 계층이 무엇을 앱으로 넘기는지는 밖에서 알 수 없다(게이트웨이 운영자 확인
    사항). 배포 잡은 `/actuator/health/readiness`를 API 서버 service proxy로, 실패하면 `kubectl exec … wget`으로 확인하고
    `jq`로 `status == "UP"`을 판정한다. 집계 `/actuator/health`는 아웃박스 인디케이터가 릴리스와 무관하게 DOWN일 수 있어(커넥터
    지연, 반복 실패 메시지, 롤아웃 자체가 남긴 행) 로그로만 남긴다 — 게이트로 쓰면 아웃박스를 고치는 릴리스조차 롤백된다.
    readiness 그룹에 `db`를 넣는 안은 채택하지 않았다: DB 장애는 두 레플리카에 동시에 닿으므로 30초(readiness 10s × 3)를 넘는
    장애면 Service 엔드포인트가 모두 빠져 서비스 전체가 사라진다. 롤백은 apply·rollout·verify·health 단계가 실패했을 때만,
    배포 전 백업보다 파드 템플릿 해시나 리비전이 달라졌을 때 `kubectl rollout undo --to-revision`으로 되돌린다(리비전 주석은
    컨트롤러가 비동기로 쓰므로 판단 근거가 템플릿이고, 조회 실패는 '변경 없음'이 아니라 비교 없는 undo로 처리한다). 배포 워크플로는 머지된 PR만 `deploy-production`
    그룹으로 직렬화한다(머지 안 된 close는 별도 그룹). 대안이던 `management.server.port` 분리는 프로브·헬스 체크·configmap을 함께
    바꿔야 해 보류하고, 샘플 라우트를 `/api/slack`·`/api/slash` 두 접두로 좁혔다(`/api` 전체는 dev·local·slack-live의 `/api/actuator`를 연다). prod의 actuator base path는 env 대신 `/actuator`로 고정했다.
    상태: 유지. 근거: `.github/workflows/deploy_action.yaml`, `.github/AGENTS.md`, `application/src/main/resources/k8s/AGENTS.md`,
    review.md 13장.
34. **미결 — 레플리카 간 Slack 재시도 중복 제거 (2026-09-28 기록).** `SlackRetryDeduplicator`의 상태는 JVM 하나에만 있는데
    Deployment는 레플리카 2개(롤아웃 중 3개)라, 원본이 파드 A에서 처리된 뒤 Slack 재시도가 파드 B로 가면 다시 처리된다.
    선택지: (1) 공유 `event_id` 저장소 — DB 테이블 + 유니크 키 + 원자적 상태 전이. 레플리카 수와 무관하게 맞지만 마이그레이션,
    3초 ack 예산 안의 요청당 DB 왕복, 보존 기간 정리 작업이 든다. (2) 단일 레플리카 — 코드 변경이 없지만 이중화가 사라지고,
    전략이 `Recreate`가 아니면 롤아웃 중에는 여전히 파드 두 개가 겹치며(`Recreate`면 매 배포 중단), `replicas: 1`에서는 PDB
    `minAvailable: 1`이 노드 drain을 막는다. 결정 전까지 `replicas`는 바꾸지 않는다. 상태: 미결.
    **2026-09-28 검토 메모(결정 아님).** Slack이 재시도하는 것은 Events API뿐이다(3초 안에 2xx가 없으면 즉시·1분·5분,
    최대 3회. 본문은 같고 타임스탬프·서명은 새로 계산). 슬래시 커맨드와 interaction은 재시도하지 않으므로 대상이 아니다.
    `/api/slack/events`의 요청 경로는 파싱·역할 조회·커맨드 실행·outbox 저장을 한 트랜잭션에서 동기로 돌리고, AI 턴은
    `AgentConverseService`(AFTER_COMMIT 리스너가 제한된 `agentTurnExecutor`에 직접 넘김, 2026-10-01부터 멘션 트랜잭션이 커밋된 뒤에만 시작)가 요청 스레드 밖에서 실행한다. 따라서 재시도는 DB가 느리거나 장애일 때의 드문
    경우로 추정한다(응답 시간 미측정). 현재 구현이 못 막는 것: 다른 파드로 간 재시도, 파드 재시작 직후의 재시도, 마지막
    재시도(5분) 뒤에 실패한 원본, 그리고 `IdempotencyCreator`가 1초 시간 창을 키에 섞어 DB 쪽에서도 중복이 안 잡히는 점.
    outbox는 나가는 메시지의 유실을 막을 뿐 들어오는 이벤트의 중복은 막지 않는다(같은 이벤트가 두 번 오면 답장이 두 줄
    쌓여 둘 다 발송된다). 선택지 (3) 수신함(inbox) 패턴 — 이벤트를 저장하고 즉시 200, 처리는 비동기. 3초 제약과 in-flight
    재시도 문제가 함께 사라지고 파드가 죽어도 턴을 복구할 수 있지만 이벤트 처리 흐름 전체를 바꿔야 한다. 그 이점의 절반
    (무거운 작업을 응답 뒤로)은 비동기 AI 턴으로 이미 확보돼 있다. **권고: 선택지 (1)**, 단 이벤트 ID 기록을 핸들러의 DB
    변경과 **같은 트랜잭션**에 넣는다 — 처리 결과와 처리 표시가 함께 커밋·롤백되므로 실패한 원본은 표시가 남지 않아
    재시도가 정상 처리된다. 메모리 맵은 같은 파드 안의 1차 필터로 남긴다. 착수 전 조사할 것: 이벤트 핸들러가 트랜잭션
    밖에서 하는 일(비동기 AI 턴은 메모리 이벤트라 파드가 죽으면 사라짐 — dedup과는 별개 문제), Slack `event_id` 추출 위치,
    다음 마이그레이션 번호(기록 당시 V24, 2026-10-15 기준 V29), 보존 기간 정리 작업.
    **슬래시·인터랙션 재전송 — 보류, 그때까지 수용된 위험 (2026-10-15 사용자 결정).** 위 중복 제거는 `/api/slack/events`만 다룬다(`SlackRequestVerificationFilter`는
    서명·타임스탬프 검증 뒤 그 경로가 아니면 바로 체인으로 넘긴다). 그래서 캡처한 서명된 슬래시·인터랙션 요청은 타임스탬프 허용치
    (`request-timestamp-tolerance-seconds`, 기본 300초) 안에서 다시 보낼 수 있다. 공격자가 먼저 TLS·인그레스·터널 경로에서 요청을
    캡처해야 한다는 점이 수용의 이유다. 고치려면 (a) 지문 승인을 이 경로들로 넓히되 파드 하나에만 듣고(위의 레플리카 간 공백이 그대로
    적용), 본문이 요청마다 다르다는 것(`trigger_id`)도 아직 확인하지 못했다. (b) 모든 파드에 듣게 하려면 공유 저장소, 곧 새 테이블
    (V27 이상)이 필요하고 이 결정의 선택지 (1)과 겹친다. 이 결정을 풀 때 함께 다룬다. 상태: 보류(사용자 결정), 공유 저장소는 #34와 함께 미결.
    근거: `application/.../security/SlackRequestVerificationFilter.kt`, `application/.../security/AGENTS.md`,
    `application/.../security/SlackRetryDeduplicator.kt`, `application/src/main/resources/k8s/deployment.yaml`,
    `application/src/main/resources/k8s/AGENTS.md`.

## 연혁 — 마일스톤과 전환점

2024-06 Slack 봇 골격에서 출발해 아웃박스·CDC 릴레이, 미팅/스탠드업, transport 중립 리팩토링, 역할·에이전트·
MCP, CVE 봇을 거쳐 2026-08 문서/CI 하드닝에 이르는 시간순 기록이며, 각 국면이 무엇에 반응한 결정이었는지를 남긴다.
2026-10-07까지 별도 연혁 페이지였던 내용을 이 페이지로 합쳤다.

### 읽기 전에 — 브랜치 토폴로지

- `main`은 PR squash로만 전진한다: `116755b` (#10, 2026-04-08) → `dc2b904` (#11, 2026-08-25). #11의 트리는
  `develop` 끝 커밋 `639aaf2`와 **바이트 동일**하고, 그 사이 `develop` 62개 커밋(2026-04-13~07-14)은 `main`
  first-parent 이력에 나타나지 않는다.
- 아래 표의 2026-04~07 해시는 이 로컬 clone의 `develop` 브랜치에서 확인한 것이다. 현재 remote에는
  `origin/main`·`origin/feature/agents-md`만 있어 **새 clone에서는 이 해시들이 resolve되지 않는다**
  (계획 문서가 말하는 `origin/develop`은 #11 병합 후 삭제된 것으로 보인다 — 미확인).
- 2025-09~2026-03 구간은 `feat/update` 등 리베이스된 사본 때문에 같은 제목의 커밋이 최대 4개 존재한다.
  표에는 `main`에 도달한 해시만 적는다.

### 마일스톤

| 날짜 | 마일스톤 | 근거 |
|------|----------|------|
| 2024-06-05 | 저장소 초기화, 06-11 `domain`/`application`/`infrastructure` 멀티모듈 분리 | `86d4286`, `f56a411` |
| 2024-07 | command 도메인·모달 응답·템플릿·인터랙션 골격. Slack 어휘가 도메인 타입명에 직접 박힘 | `828bc5b`, `188ce0d`, `2c347f6`, `50a466f` |
| 2024-07-22 | History 애그리거트 도입(2026-06-30 삭제, 아래 "폐기" 참조) | `589f5f6` |
| 2024-08-01 | Idempotency key 도입 | `9999d38` |
| 2024-09-04 | 아웃박스 리포지토리+디스패처 첫 도입 | `85963d3` |
| 2024-09~11 | Approval 요청, slash 명령, `/meetup`, 이벤트 디스패처 | `60e6668`, `c5753b1`, `897fef9`, `0c1b06b` |
| 2024-12 | Outbox 리포지토리 재작업 + 재시도 테스트 | `9d69567`, `7d21244` |
| 2025-01-16 | k8s 배포 제어(Dockerfile, LogController) | `ade246a` |
| 2025-03 | 아웃박스 폴링 퍼블리셔(03-06) → Debezium CDC 릴레이·로그 테일링(03-18~31) | `35590ee`, `169a9cb`, `8259119`, `fa5e1da`, `73f5e2b` |
| 2025-04-09 | Meeting 기능(요청 폼 04-02 포함) | `8501062`, `e33aba5` |
| 2025-06~08 | subcommand 체계와 meeting 서브커맨드 | `35464e5`, `5bb0307`, `d2d4579` |
| 2025-08-08 | 1차 MCP 서버 실험 — Spring AI 1.0.1 SSE `/mcp`, `MeetupToolProvider` | `cdc0da2`, `cde52ad` |
| 2025-09 | GitHub Actions CI/CD(`deploy_action`, `simple_test_action`) + ktlint | `d372712`, `bb5517f` |
| 2025-10 | MariaDB k8s 리소스, context 도메인 분리, event→command entity | `6160d2d`, `11d8672`, `163a561` |
| 2026-04-08 | PR #10 — Kotlin/Spring 버전 갱신 (`main`) | `116755b` |
| 2026-04-17~23 | domain intents, 런타임 버그·attendance 영속, decline-reason 모달 | `a03ee8f`, `bc216e1`, `777a445` |
| 2026-04-28~05-01 | Handoff Phase 1(#1~#5)·Phase 2(#6~#9): teamId 보존, OutboxHealthIndicator, 원자적 cancel, schemaVersion | `8726536`, `5653a67`, `df7609e`, `90673d6`, `6319531` |
| 2026-05-08 | Handoff Phase 3 — Standup #10~#13 (도메인·스케줄러·DM 모달·요약, Codex 리뷰 4라운드) | `b966f96` |
| 2026-06-18 | 의존성 갱신, 1차 MCP 실험 제거. `Handoff.md` 마지막 갱신 | `154f340` |
| 2026-06-29 | Phase 4 #14 미팅 리마인더 + standup setup/reschedule/nudge/agenda, Socket Mode 로컬 수신기, 로컬 문서 untrack | `fb4fbd7`, `e55e1ad`, `73e2f7e`, `4d5ab94` |
| 2026-06-30 | Refactor Phase 0(History 삭제)·Phase 1(순환 제거+`DomainLayeringGuardTest`)·Phase 3a~3d-1 | `9998d67`, `6cd1ea7`, `795e873`, `9b467b7`, `c62c160`, `1dad858` |
| 2026-07-01 | Phase 3d-2/3d-3/3e → Phase 4 인바운드 순수화(단일 커밋 164파일), README 갱신 | `4d77684`, `6878b6f`, `8b2450e`, `3639969`, `b26b075` |
| 2026-07-02 | Phase 5(교차검증 후속)·Phase 6(검증 갭)·Phase 6d~7(wireValue 폐기, 스테이저 단일 창구) | `0b5f08b`, `cf8f38b`, `12b9e39`, `2985257` |
| 2026-07-03 | 원격 세션: `@bot ask` 에이전트 lane(claude-sidecar), per-request context + 턴 감사 | `bd942a5`, `0d8bd99` |
| 2026-07-06 | Phase 8 transport 중립 outbox + user 슬라이스 삭제, Phase 9 에이전트 lane 리베이스 통합 | `6b1083e`, `f6953c6` |
| 2026-07-07 | Phase 10 역할 기반 권한 + 채팅 역할 관리; 사이드카 HTTP/1.1 고정 | `ac58a76`, `35516aa`, `2af6249` |
| 2026-07-13 | MCP 도메인 도구 Phase 1(읽기 전용, 턴 스코프 토큰) + fail-closed | `a239cd1`, `bf4da5b` |
| 2026-07-13~14 | CVE-Bot M1~M6 + 교차검증 하드닝(V14~V17) | `823934e`, `1ab3dc5`, `49c4f86`, `ca44c68`, `bf06773`, `639aaf2` |
| 2026-08-25 | PR #11 "Main branch update 0825" — `develop` squash | `dc2b904` |
| 2026-08-25 | `feature/agents-md`: 계층적 AGENTS.md 19개, docs CI 제외·README 교정, `gradle-common` 프리셋 | `65c41ed`, `c3e7ad9`, `47f0993`, `32731d9` |
| 2026-08-28 | (작업 트리, 미커밋) `security_check.yaml`(CodeQL·dependency-review·gitleaks), Dependabot, `ext{}`→`extra[]`, 위키 신설 | `git status` |

### 설계가 움직인 흐름

1. **Slack 형상의 도메인(2024)** — `domain/command`가 `SlackApiRequester`, `SlackApprovalContext`, Slack wire
   DTO(`@JsonProperty`)를 직접 품었고 History가 모든 명령을 감사 기록했다. 이 시기의 관심사는 "Slack API가
   실패해도 메시지를 잃지 않기"였고 그 답이 2024-09 아웃박스, 2025-03 Debezium CDC 릴레이다.
2. **기능 확장기(2025)** — 미팅·서브커맨드·MCP 실험·CI가 얹히면서 context/파서가 커졌고, 2025-10 "context
   도메인 분리"가 응집성 문제를 처음 손댄 지점이다.
3. **신뢰성 하드닝(Handoff, 2026-04~06)** — 서명 검증·재시도 dedup, outbox `IN_PROGRESS` claim/lock과
   stuck 복구, payload `schemaVersion`, 그리고 스탠드업 스케줄러. Codex 리뷰 4라운드에서 **claim-token CAS +
   `updated_at` 명시 갱신** 패턴이 확정됐고(결정 #10) 이후 리마인더·agenda·CVE 워커가 전부 이 패턴을 복제한다.
4. **transport 중립 리팩토링(Refactor Phase 0~8, 2026-06-30~07-06)** — 진단 결과가 출발점이다: `CommandIntent`
   24변종 중 순수 도메인은 5개, 도메인 안에 Jackson 어노테이션 6곳, History를 거치는 양방향 순환. History
   삭제(Phase 0)로 순환의 anchor를 없애고, `DomainIntent`/`OutboundMessage`/`SlackOutboundStager`로 god-type을
   쪼개고(Phase 3), `InboundCommand`·`InboundForm`으로 인바운드를 중립화(Phase 4)했다. Phase 5·6는 "완전
   중립"이라는 **과장을 교차검증으로 바로잡는 정직화 단계**였다(`BASE_URL` 잔존, Jackson이 컴파일 클래스패스에
   잔존, negative control 미구현). Phase 8b에서 outbox가 렌더된 Slack 페이로드 대신 중립 envelope +
   `transport` 컬럼을 저장하게 되어 렌더 시점이 deliver로 이동했다(결정 #6·#7).
5. **에이전트·권한·MCP(2026-07-03~13)** — 다른 세션이 만든 `@bot ask` lane은 pre-8b 계약(즉시 렌더 이벤트)이라
   리베이스만으로는 응답이 조용히 유실됐고, Phase 9에서 스테이저 경로로 이식했다. Phase 10 역할 계층(결정 #13)은 k8s
   e2e 중 "아무나 명령을 쓰면 안 된다"는 결정에서 나왔다. MCP는 사이드카를 도메인 무지로 두고 앱 안에
   읽기 전용 도구만 여는 형태로 돌아왔다(이유는 결정 #14·#16).
6. **CVE 봇(2026-07-13~14)** — 원본 기획을 기존 원시 요소(outbox, CAS 레저, 역할 게이트, typed submission)
   위에 재배치했다(결정 #18). 착수 전 탐색 3기로 Phase 8~10 이후 코드를 다시 검증해 낡은 전제를 고쳤다.
7. **문서·CI 하드닝(2026-08)** — 코드 변경 없이 AGENTS.md 계층, docs 제외 트리거, 보안 워크플로,
   Dependabot, 이 위키가 추가됐다(결정 #27~#31).

### 계획 문서

git-ignored 계획 문서 `Handoff.md`·`Refactor.md`·`CveBotPlan.md`·`review.md`·`review_skill.md`는 2026-10-07 로컬
작업 트리에서도 삭제되어 이 페이지와 `AGENTS.md` 트리가 유일한 기록이 됐고, `RealTestSetup.md`와
`STYLE_GUIDE.local.md`(요약은 [coding-style.md](coding-style.md))만 git-ignored 로컬 파일로 남아 있다.

## 폐기·대체된 것

| 대상 | 도입 → 폐기 | 이유 |
|------|-------------|------|
| History 애그리거트 | `589f5f6`(2024-07) → `9998d67`(Refactor Phase 0) | 영속화가 끝내 배선되지 않은 감사 골격이자 domain↔command 순환의 anchor. `Status`만 `command.dto.response`로 이전. Handoff #16은 무효화 |
| User/Team 슬라이스 | `e62c13a`·`84f94dc`(2025-01~02) → `6b1083e`(Phase 8a) | 비즈니스 로직 없는 스키마. 채널 기준 분할이 기본이라 16파일·테이블 3개 삭제 |
| 1차 MCP 서버(Spring AI 1.0.1 SSE) | `cdc0da2`(2025-08) → `154f340`(2026-06-18) | 제거 사유 미기록(미확인). 2026-07-13 `a239cd1`이 역할 게이트·턴 토큰·감사가 있는 streamable HTTP `/mcp`로 대체 |
| `CommandDetailType.wireValue` seam | `0b5f08b`(Phase 5c) → `12b9e39`(Phase 6d) | 레거시 토큰 호환용 seam이었으나 로컬 DB뿐이고 in-flight 메시지가 없어 토큰=`name`, 읽기=`valueOf` fail-fast로 단일화. 로컬 outbox 초기화 필요했음 |
| 렌더된 Slack 페이로드를 저장하는 outbox | 2024-09 → `6b1083e`(Phase 8b, V11) | 중립 envelope `{OutboundMessage, CommandBasicInfo}` + `transport` 컬럼. `SendSlackMessageEvent`는 인프라 렌더 봉투로 축소, `SlackEventAsyncDispatcher`·`NewMessagePublishedEvent` 삭제 |
| `SlackCommandData`/`SlackCommandType`/`SlackRequestHeaders` | 2024 → `3639969`(Phase 4f) | `InboundCommand`/`InboundKind`로 대체, headers는 dead code라 삭제 |
| `SlackIntentResolver`의 Slack 렌더링 | → `c62c160`(3c)·`12b9e39`(7) | `SlackOutboundStager`가 렌더 단일 창구. 리졸버는 DomainIntent→도메인 이벤트 매퍼만 남음 |
| delay-dispatch 경로·`commandType` 배관 | → `11515af`(2026-04-27) | 호출자 없는 dead path |
| Refactor 4g "outbox transport 컬럼" | 연기 → Phase 8b에 흡수 | Discord 착수 전까지 미루려 했으나 사용자 결정으로 일괄 처리 |

미착수로 남은 것: MCP 쓰기 도구(Phase 2, 설계만), 2nd transport(Discord — `Transport.DISCORD` +
`DiscordOutboundRenderer` 구현이 남은 전부라는 것이 Phase 8b의 결론). Handoff #15 Prometheus 메트릭은
`f9971700`(#26, 2026-10-06)으로 들어왔다 — `micrometer-registry-prometheus` 의존성, `dev`·`prod`의 `prometheus`
엔드포인트 노출, `OutboxMetrics`의 아웃박스 게이지, Kafka DLT·에이전트 턴·CVE 수집·Slack 디스패치 카운터.

## 근거

- 결정: 각 항목에 명시.
- 로컬 계획 문서: `Handoff.md`, `Refactor.md`, `CveBotPlan.md`, `STYLE_GUIDE.local.md`,
  `.omc/plans/mcp-domain-tools-phase1.md` (모두 git-ignored, 작성 당시 로컬 작업 트리 기준). 앞의 셋은 2026-10-07에
  삭제됐으며, 여기 옮긴 요지가 저장소에 남는 기록이다.
- 연혁: `git log --all --date=short --format='%ad %h %s'` (225 커밋), `git log --first-parent main`,
  `git diff --quiet 639aaf2 dc2b904`(트리 동일), `git branch -a`
- `git show --stat` : `589f5f6`, `cdc0da2`, `154f340`, `d372712`, `ade246a`, `dc2b904`, `65c41ed`, `c3e7ad9`
- `.gitignore`, `README.md`, `application/src/main/resources/db/migration/V1..V17`
- Prometheus: `git log -S micrometer-registry-prometheus -- application/build.gradle.kts`(`f9971700`),
  `application/build.gradle.kts`, `application-{dev,prod}.yaml`(`management.endpoints.web.exposure.include`),
  `application/src/main/kotlin/dev/notypie/application/health/OutboxMetrics.kt`,
  `configurations/{KafkaConsumerConfiguration,CveConfiguration,SlackRequestBuilderConfiguration}.kt`,
  `service/agent/AgentConverseService.kt`
- `/ask` 턴 레저(#38·#40): `application/src/main/kotlin/dev/notypie/application/service/agent/{AgentAskThreadStarter,AgentConverseService}.kt`,
  `service/relay/SlackMessageRelayServiceImpl.kt`, `infrastructure/src/main/kotlin/dev/notypie/repository/agent/{AgentAskTurnRepository.kt,
  schema/AgentAskTurnSchema.kt}`, `repository/outbox/dto/OutboxMessageEvents.kt`, `db/migration/V26__add_agent_ask_turn.sql`, 스펙
  `AgentAskTurnRepositoryTest`·`AgentAskThreadStarterTest`·`AgentConverseServiceTest`·`SlackMessageRelayServiceImplTest`·
  `ApplicationContextSmokeTest`·`AgentTurnShutdownContextTest`, 위 디렉터리들의 `AGENTS.md`(2026-10-13, 미커밋 작업 트리
  `feature/ask-turn-ledger`)
- Google Calendar(#36·#37): `git show --stat 3c0529bf 6ae0a047`(`archive/gap-closure` 태그 기준), `application/src/main/kotlin/dev/notypie/application/service/calendar/AGENTS.md`,
  `infrastructure/src/main/kotlin/dev/notypie/repository/calendar/AGENTS.md`, `db/migration/V24__…`·`V25__…`, git-ignored
  `.omc/plans/google-calendar-user-oauth.md`(설계와 리뷰 반영 결정 전부)
- 연결 신원 확인(#41): `application/src/main/kotlin/dev/notypie/application/service/calendar/{CalendarConnectionService,CalendarConnectionCallback}.kt`,
  `controllers/GoogleOAuthCallbackController.kt`, `configurations/CalendarConfiguration.kt`,
  `infrastructure/src/main/kotlin/dev/notypie/impl/calendar/GoogleOAuthClient.kt`, `templates/SlackUserProfileResolver.kt`
  (`SlackUserEmailResolver`), `impl/command/dto/SlackUserInfoDto.kt`, 스펙 `CalendarConnectionServiceTest`·
  `GoogleOAuthCallbackControllerTest`·`GoogleOAuthClientTest`·`SlackUserEmailResolverTest`, 슬랙 API 문서(`users:read.email` scope,
  `users.info`, `users.profile.get`, 2026-10-13 리뷰에서 확인)
  (2026-10-13, 미커밋 작업 트리 `feature/security-scan-2026-10`), git-ignored `.omc/plans/security-scan-2026-10.md`
- 봇이 없는 채널의 슬래시 응답 DM 폴백(#42): `domain/src/main/kotlin/dev/notypie/domain/command/outbound/{DirectReply,OutboundMessage}.kt`(`DirectReply`와 헬퍼, `fallback`),
  `entity/Command.kt`(`replyToSlashFailure`), `entity/context/form/{AskSlash,StandupSlash,RequestMeeting,CalendarSlash}Context.kt`,
  `application/src/main/kotlin/dev/notypie/application/service/relay/{OutboxPayloadRenderer,SlackMessageRelayServiceImpl}.kt`,
  `service/standup/{StandupRoutineOpsService,StandupAnswerService}.kt`, `service/meeting/MeetingServiceImpl.kt`, `service/calendar/CalendarReplies.kt`,
  `infrastructure/src/main/kotlin/dev/notypie/impl/command/ApplicationMessageDispatcher.kt`(`isChannelRefused`),
  `repository/outbox/{OutboundMessagePort,OutboundMessageCodec}.kt`, `repository/outbox/schema/OutboxSchemaVersion.kt`(V6),
  git-ignored `.omc/plans/b2-not-in-channel-slash-replies.md`(선택지 A~D와 아키텍트 검토 반영)
- 에이전트 보존 정리와 스윕 look-ahead(#43·#40 ⑤): `application/src/main/kotlin/dev/notypie/application/service/agent/{AgentRetentionScheduler,AgentAskThreadStarter}.kt`,
  `configurations/AppConfig.kt`(`Agent.Retention`, 루트 `init`), `application-prod.yaml`(`slack.app.agent.retention`),
  `infrastructure/src/main/kotlin/dev/notypie/repository/agent/{AgentAskTurnRepository,JpaAgentTurnHistoryRepository,AgentTurnHistoryRepository}.kt`,
  `repository/mcp/{JpaMcpToolCallHistoryRepository,McpToolCallHistoryRepository}.kt`, `db/migration/V10__…`·`V13__…`·`V26__…`(인덱스),
  `domain/.../command/intent/CommandIntent.kt`(`AgentUsageReport.MAX_DAYS`), `application/src/main/resources/k8s/deployment.yaml`(`Recreate`),
  `repository/outbox/schema/OutboxSchemaVersion.kt`(`SUPPORTED`), 스펙 `AgentRetentionSchedulerTest`·`AgentAskThreadStarterTest`·
  `AppConfigBindingTest`·`ApplicationContextSmokeTest`·`AgentAskTurnRepositoryTest`·`AgentTurnHistoryRepositoryImplTest`·
  `McpToolCallHistoryRepositoryImplTest`(2026-10-15, 미커밋 작업 트리 `feature/agent-retention-sweep`); H2 2.4.240 `DELETE … LIMIT`의 갱신 수는
  `AgentAskTurnRepositoryTest`에 정확한 수를 단언해 돌린 실행의 실패 출력과 Hibernate SQL·바인딩 로그로 확인(2026-10-17); 리뷰 반영(지터,
  상한, 상태 분류 스펙) 2026-10-19; Codex 교차 리뷰 반영(2026-10-19): `service/agent/AgentUsageReportService.kt`(`since` 한 번, 쿼리 셋),
  `common/TransactionTemplateExt.kt`(`detachedTemplate` = `PROPAGATION_NOT_SUPPORTED`), `resources/cdc/k8s/yamls/mariadb/mariadb-config.yaml`·
  `resources/cdc/docker-compose/mariadb/my.cnf`(`binlog_format=ROW`); MariaDB 실측(2026-10-20, 로컬 12.3.3): 일회용 스키마의
  `EXPLAIN`/`ANALYZE DELETE`, 두 `mariadb` 세션의 동시 삭제·1020 대조 실험, `batch-size` 3으로 띄운 앱의 틱 로그와 `mariadb-binlog` 트랜잭션별
  삭제 수(`.omc/artifacts/mariadb-verify-2026-10-20/REPORT.md`)
- 원자적 슬래시 실패·응답 핸들 마스킹(#44·#45): `domain/src/main/kotlin/dev/notypie/domain/command/entity/Command.kt`
  (`replyToSlashFailure`), `intent/IntentQueue.kt`(`clear`), `outbound/InteractionHandles.kt`, `inbound/InboundInteraction.kt`,
  `entity/event/Event.kt`, `infrastructure/.../impl/command/{OutboundRenderer,SlackOutboundStager}.kt`, `impl/command/event/SlackEventPayloads.kt`,
  `application/.../common/IdempotencyCreator.kt`, `infrastructure/.../common/JsonMapper.kt`, `repository/outbox/OutboundMessageCodec.kt`, 스펙
  `CommandTest`·`OutboundMessageTest`·`InboundInteractionTest`·`IdempotencyCreatorTest`·`OutboundMessageCodecTest`, `javap -c`로 본
  `CommandIntent$CancelMeeting`·`OutboundMessage$ReplaceMessage`의 `toString`(Kotlin 2.4.21), 슬랙 문서 "Handling user interaction"
  (response_url은 그 URL로 POST, 30분 안에 5회; trigger는 3초 만료·1회)·`views.open`(토큰 필수)(2026-10-15 조회), "Implementing slash
  commands"(`token`은 deprecated verification token, 2026-10-19 조회)
- 토큰 AAD·연결 낙관적 락(#46·#47): `infrastructure/src/main/kotlin/dev/notypie/impl/calendar/TokenCipher.kt`,
  `repository/calendar/{GoogleCalendarConnectionRepositoryImpl,JpaGoogleCalendarConnectionRepository}.kt`,
  `repository/calendar/schema/GoogleCalendarConnectionSchema.kt`, `application/src/main/kotlin/dev/notypie/application/service/calendar/
  {CalendarConnectionService,GoogleAccessTokenProvider,GoogleTokenRevocationWorker}.kt`, `GoogleOAuthClient.refresh`(새 refresh token을
  읽지 않음), `db/migration/V28__add_google_calendar_connection_version.sql`, 스펙 `TokenCipherTest`·`GoogleCalendarConnectionRepositoryImplTest`·
  `CalendarConnectionServiceTest`·`GoogleAccessTokenProviderTest`·`GoogleTokenRevocationWorkerTest`, 세션 스크래치의 트리아지 B1·B2
  (2026-10-15, 미커밋 작업 트리 `feature/calendar-token-aad-version`)
- `Error`를 삼키지 않는 경계와 CDC 예외 조항(#48): `infrastructure/src/main/kotlin/dev/notypie/common/ExceptionBoundary.kt`,
  `application/.../common/TransactionTemplateExt.kt`, `infrastructure/.../impl/retry/RetryService.kt`, `service/relay/DebeziumLogTailingProcessor.kt`
  (`ListenerErrorException`), `configurations/KafkaConsumerConfiguration.kt`(poison·재시도 없음 목록), `configurations/AsyncConfig.kt`
  (`RELAY_UNCAUGHT_ERROR_LOGGER`), `mcp/McpToolGate.kt`(`finally` 감사), `socket/SocketModeReceiver.kt`(`logErrorThenRethrow`); 라이브러리 소스
  (2026-10-19, Gradle 캐시): spring-kafka 4.1.1 `KafkaMessageListenerContainer.java`(`doInvokeRecordListener` `catch (RuntimeException)`,
  `ListenerConsumer.run` `catch (Error)` "Stopping container due to an Error"), `MessagingMessageListenerAdapter.java`(`Error` 재던짐),
  `FailedRecordProcessor.java`(원인 체인 탐색); spring-context 7.0.9 `TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER`,
  `ExecutorConfigurationSupport`(스레드 팩토리); spring-core 7.0.9 `RetryTemplate.java`(`catch (Throwable)`, `RetryException`의 원인·suppressed);
  Slack SDK 1.52.0 `SocketModeClient.initializeMessageProcessorExecutor`(`javap`, `Exception`만 잡는 루프 10개); 스펙
  `DebeziumLogTailingProcessorTest`(실제 `KafkaMessageListenerContainer` + `MockConsumer`: DLT 두 건·재시도 없음·컨테이너 유지),
  `McpToolGateTest`, `ExceptionBoundaryTest`, `RetryServiceTest`, `SlackMessageRelayServiceImplTest`(실제 풀 스레드의 핸들러 동일성),
  `SocketModeReceiverTest`; 변이 검사 7건(2026-10-15, 헬퍼가 `Throwable`을 잡게·모든 경계를 `runCatching`으로·L2 분기 제거 등)
- 대화 종류별 거절 문구·`/ask` 거절 안내·유니코드 줄바꿈 인용(#49): `domain/src/main/kotlin/dev/notypie/domain/command/outbound/{OutboundTargets,DirectReply}.kt`(`ConversationKind`, `kind()`, `askRefusedNotice`), `domain/common/SafeText.kt`(`splitLineBreaks`, `takeEntitySafe`),
  `domain/.../entity/context/form/AskSlashContext.kt`, `infrastructure/.../impl/command/OutboundRenderer.kt`(`askedBy`), `impl/command/slack/SlackMentionMapper.kt`;
  실측 기록 `.omc/handoffs/2026-10-20-live-slack.md`(git-ignored); 2026-10-22 정정: Slack 문서 "Conversation object"(2021년 3월 전에 만든 비공개
  채널은 `G` id, 종류 판정은 `is_private`·`is_mpim`)·레거시 "MPIM object"(예시 `"id": "G024BE91L"`, `"is_mpim": true`)·"Implementing slash commands"(`*_name`은 바뀔 수 있으니 ID에 의존)(2026-10-22 조회),
  `templates/SlackBlockLimits.kt`(`hardWrap`), `domain/common/SafeText.kt`(`isVisiblyBlank`), JDK 25 `Character.getType`(jshell 2026-10-22:
  U+200B·U+FEFF·U+E0001·U+0600·U+06DD는 `FORMAT`, U+0001·U+007F·U+009B는 `CONTROL`, U+FE0F는 `Mn`, U+3164는 `Lo`, U+2800은 `So`),
  스펙 `SafeTextTest`·`AgentChatContextTest`·`AskCommandTest`·`SlackOutboundRendererTest`·`SlackBlockLimitsTest`·`OutboxMessageTest`
- 남은 응답 핸들 노출·verification token 삭제·아웃박스 V7(#50): `domain/src/main/kotlin/dev/notypie/domain/command/inbound/{InboundCommand,InboundInteraction}.kt`,
  `dto/CommandBasicInfo.kt`, `dto/response/CommandOutput.kt`, `entity/context/form/ParsedSubmissions.kt`, `infrastructure/.../impl/command/SlackInboundMapper.kt`
  (`listHandle()`, `carriesListHandle()`), `SlackInteractionRequestParser.kt`, `impl/command/slack/{InteractionPayload,SlashCommandRequestBody,SlackEventCallBackRequest,SlackMentionMapper}.kt`,
  `impl/command/event/SlackEventPayloads.kt`, `repository/outbox/{OutboundMessagePort,schema/OutboxSchemaVersion}.kt`, `application/.../common/SlackRequestParser.kt`
  (`jsonMapper.convertValue`), Debezium 커넥터 설정 `resources/cdc/docker-compose/debezium/connect_mariadb.sh`(열 필터 없음), 스펙
  `OutboundMessageCodecTest`(고정 V6 행)·`OutboxMessageTest`·`ViewSubmissionChannelRoutingRegressionTest`·`SlackInteractionRequestParserTest`·
  `SlackApiEventConstructorTest`·`SlackControllersTest`·`SlackMentionEventHandlerImplTest`·`OutboxChainJpaTransactionTest`, 미지원 버전 보류는
  기존 `SlackMessageRelayServiceImplTest`("schema version this binary cannot read")·`OutboxPayloadRendererTest`·`AgentAskThreadStarterTest`;
  선택지 A/B/C와 사용자 결정(2026-10-20, 세션 기록)
- OAuth state 만료 정리(#51): `application/src/main/kotlin/dev/notypie/application/service/calendar/{GoogleOAuthStateRetentionScheduler,CalendarConnectionService,CalendarSyncScheduler}.kt`,
  `service/relay/OutboxRetentionScheduler.kt`(`purgeInBatches`), `infrastructure/src/main/kotlin/dev/notypie/repository/calendar/{GoogleOAuthStateRepository,GoogleOAuthStateRepositoryImpl,JpaGoogleOAuthStateRepository}.kt`,
  `repository/calendar/schema/GoogleOAuthStateSchema.kt`(`idx_google_oauth_state_expires_at`), `db/migration/V24__…`·`V27__…` 헤더, 스펙
  `GoogleOAuthStateRetentionSchedulerTest`·`CalendarConnectionServiceTest`·`CalendarConfigurationTest`·`JpaGoogleOAuthStateRepositoryTest`,
  git-ignored `.omc/plans/oauth-state-retention-2026-10-21.md`(리드 결정과 락 노출 추론)(2026-10-21, 미커밋 작업 트리 `feature/oauth-state-retention`)
- 스탠드업 Skip 기록(#52): `domain/src/main/kotlin/dev/notypie/domain/command/entity/context/form/StandupFillContext.kt`,
  `intent/CommandIntent.kt`(`RecordStandupSkip`), `entity/event/Event.kt`(`RecordStandupSkip*`), `outbound/MessageContent.kt`(`skippedUserIds`),
  `domain/standup/{entity/SessionDispatch,dto/StandupSessionDto}.kt`, `infrastructure/.../impl/command/{SlackIntentResolver,OutboundRenderer,SlackApiEventConstructor}.kt`,
  `repository/standup/{StandupRepository,StandupRepositoryImpl,JpaSessionDispatchRepository}.kt`, `repository/standup/schema/StandupSessionSchema.kt`,
  `templates/{ModalTemplateBuilder,SlackTemplateBuilder}.kt`, `application/.../service/standup/{StandupAnswerService,StandupSchedulingService,StandupSummaryService}.kt`,
  `db/migration/V29__add_standup_session_dispatch_skipped_at.sql`, 스펙 `StandupFillContextTest`·`SlackIntentResolverTest`·`StandupRepositoryImplTest`·
  `ModalTemplateBuilderTest`·`OutboundMessageCodecTest`·`SlackOutboundRendererTest`·`StandupAnswerServiceTest`·`StandupSchedulingServiceTest`·
  `StandupSummaryServiceTest`; 리뷰 F-01과 사용자 선택(2026-10-26, 세션 기록)
- 재조정 모달의 현재 시작(#53): `infrastructure/src/main/kotlin/dev/notypie/templates/{ModalBlockBuilder,ModalTemplateBuilder}.kt`
  (`hostMeetingActionsBlock`, `meetingListFormTemplate`), `impl/command/{SlackOutboundStager,SlackInteractionRequestParser,SlackInboundMapper}.kt`,
  `domain/src/main/kotlin/dev/notypie/domain/command/entity/context/form/RescheduleMeetingContext.kt`, `outbound/ModalForm.kt`,
  `application/.../service/meeting/MeetingRescheduleService.kt`(과거 시각만 거절), `git log -S 'Message carries no stored start'`
  (`dc2b9044` 추가, `90c747ab` 삭제), 스펙 `RescheduleMeetingContextTest`·`OutboundMessageTest`·`SlackOutboundStagerTest`·
  `ModalTemplateBuilderTest`·`SlackInteractionRequestParserTest`·`ViewSubmissionChannelRoutingRegressionTest`; 리뷰 G-01과 검증 레인 V4a
  (2026-10-26, 세션 기록)
- 에이전트 턴 경계·본문 읽기 인터럽트(#54): `application/src/main/kotlin/dev/notypie/application/service/agent/AgentConverseService.kt`,
  `application/.../configurations/AgentConfiguration.kt`(`AgentTurnExecutor.newThread`, `AGENT_TURN_UNCAUGHT_LOGGER`), `infrastructure/.../impl/http/BoundedHttp.kt`,
  스펙 `AgentConverseServiceTest`·`AgentTurnExecutorTest`·`BoundedHttpTest`·`GoogleOAuthClientTest`; 리뷰 D-01·H-01(2026-10-26, 세션 기록)
- 리마인더 일시 실패 복귀·시작 뒤 폐기·넛지 락 재읽기(#55): `application/src/main/kotlin/dev/notypie/application/service/meeting/MeetingReminderSchedulingService.kt`,
  `application/.../service/standup/StandupSchedulingService.kt`, `infrastructure/.../repository/meeting/{MeetingReminderRepository,MeetingReminderRepositoryImpl,JpaMeetingReminderRepository}.kt`,
  `infrastructure/.../repository/standup/{StandupRepository,StandupRepositoryImpl}.kt`, 스펙 `MeetingReminderSchedulingServiceTest`·`MeetingReminderRepositoryImplTest`·
  `StandupSchedulingServiceTest`·`StandupRepositoryImplTest`; 리뷰 E-01·CX-01·CX-02(2026-10-26, 세션 기록)
- 빈 등록·스케줄 잡 규칙 정정(#56): `docs/wiki/coding-style.md`(빈 등록·스케줄 잡 행), `application/AGENTS.md`, `application/src/main/kotlin/dev/notypie/application/configurations/AGENTS.md`,
  `application/src/main/kotlin/dev/notypie/application/{configurations,service,security}/**`의 `@Bean`·`@Service`/`@Component`·`@Scheduled`(2026-10-26 grep),
  `infrastructure/src/main/kotlin/dev/notypie/configurations/JpaConfiguration.kt`, 리뷰 P-03·P-04(`.omc/handoffs/2026-10-23-full-review/findings-P.md`, git-ignored)
- 작업 트리: `.github/workflows/security_check.yaml`, `.github/dependabot.yml`, `.gitleaks.toml`,
  `build.gradle.kts` diff

## 관련 페이지

- [architecture-overview.md](architecture-overview.md) · [ddd-layering.md](ddd-layering.md) ·
  [events-and-outbox.md](events-and-outbox.md) · [command-pipeline.md](command-pipeline.md) ·
  [dev-environment.md](dev-environment.md)
