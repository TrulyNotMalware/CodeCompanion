# 수정 브랜치 리뷰: 보안·역할·회의·JPA·스탠드업 (`feature/review-round3-fixes` @ 7c70c5d)

맡은 항목은 대부분 실제로 고쳐졌습니다. T25(OSIV 끄기)로 트랜잭션 밖에서 LAZY 연관을 읽는 곳은 정적 전수 확인 결과 없습니다. 브랜치가 새로 만든 결함은 Low~Medium 수준입니다. High는 G1 하나인데, main에서 이미 있던 결함이고 T4 수정이 놓친 것입니다.

**조사 방법**
- 보안·역할·회의·JPA와 T25 전수 확인은 직접 했습니다.
- 스탠드업 영역은 하위 레인에 맡겼습니다. 하위 레인은 Hibernate 7.4.5 + H2 단독 프로브를 실행했고, 위치는 `scratchpad/rev2/standup/src/probe/BagProbe.java`입니다.
- 하위 레인 결과 중 G1(요약 저장 경로)·G3·G7·T19·T28의 라인은 제가 다시 열어 확인했습니다. 나머지 스탠드업 라인은 하위 레인 보고 그대로이며 표에 "(하위레인)"으로 표시했습니다.
- 파일 수정과 Gradle 실행은 하지 않았습니다. Hibernate 의미는 hibernate-core 7.4.5 jar를 javap로 확인했습니다.

## 1. 항목 판정표

| 항목 | 판정 | 근거 file:line | 설명 |
|---|---|---|---|
| T10 | FIXED | `CommandRoleResolver.kt:26-30,35-49,58-64` | `USER`만 캐시하고 `AI_USER`·`DEVELOPER`·`ADMIN`은 매번 DB에서 읽습니다. 조회 실패 시의 USER는 캐시하지 않습니다(`:43-46`). 권한 집합이 단조 포함 관계라(`UserRole.kt:6-12`) 오래된 USER 캐시는 거부 쪽으로만 틀립니다. 다른 파드에서 grant가 최대 60초 늦는 것은 fail-closed라 수용 가능합니다. 축출 경로 `RoleManagementService.kt:97-107`(afterCommit)은 남아 있고, grant를 로컬에서 바로 반영하는 데 여전히 필요합니다. 결정 #15(`decisions.md:59-64`)와 AGENTS 서술이 일치합니다. |
| T11 | FIXED | `SlackInteractionHandlerImpl.kt:58-61,74-92`, `SlackMentionEventHandlerImpl.kt:37,74-88` | 역할 조회는 트랜잭션 시작 전에 합니다. Spring Data 선언 쿼리 메서드는 기본으로 트랜잭션이 없으므로 조회 실패가 rollback-only를 만들지 않습니다. 실제 `JpaTransactionManager` 테스트는 `RoleLookupJpaTransactionTest.kt:98-159`입니다. 인라인 분기(`:65-68`, 이미 트랜잭션이 있을 때)를 쓰는 운영 호출자는 없습니다. 컨트롤러와 `SocketModeReceiver`에 트랜잭션이 없습니다. |
| M8 (핸들러) | FIXED | `SlackInteractionHandlerImpl.kt:79-89` | 모든 `Throwable`에 롤백하고, rollback 실패는 `addSuppressed`로 붙입니다. |
| M8 (서비스) | FIXED | `MeetingServiceImpl.kt:359-370`(호출 `:163`, `:223`), `MeetingRescheduleService.kt:66-75` | 지연 경로에서는 실패 회신마저 실패해도 로그만 남기고 500으로 번지지 않습니다. |
| T27 | FIXED | `SlackRequestVerificationFilter.kt:19-21,53,67,75,89` | 로거 이름을 `log`로 바꿨습니다. `McpTurnTokenFilter`에는 로깅이 없어 같은 패턴이 없습니다. |
| T23 | FIXED | `application.yaml:39-44` | 어떤 프로파일 yaml도 이 값을 덮어쓰지 않습니다. `ForwardedHeaderFilter`나 직접 XFF를 읽는 코드도 0건입니다. `McpTurnTokenFilterTest.kt:70-106`은 의미 있는 테스트이고, `:44-54`는 G11에서 다룹니다. |
| W3 | FIXED | `Validation.kt:23-28,52-64,66-89` | 블록마다 자식 오류 집합을 두고, 살아남은 오류만 부모로 병합합니다. `p1 AND (p2 OR p3)`를 따라가 보면 정확합니다. |
| W4 | FIXED | `SlackRetryDeduplicator.kt:99-110` | 헤더 유무와 관계없이 기존 항목의 상태로 판정합니다. `retryNum` 파라미터는 이제 쓰이지 않습니다. |
| W5 | PARTIAL | 문서 `security/AGENTS.md:57-62`, 코드 `SlackRequestVerificationFilter.kt:73-79` | 문서만 고쳤습니다. 위조 요청도 여전히 1 MiB까지 버퍼링됩니다. 권고한 게이트웨이 제한은 샘플 매니페스트(`k8s/route/`)에 없습니다. |
| W6 | FIXED | `SlackSignatureVerifier.kt:56-64` | `subtractExact`를 쓰고 넘치면 EXPIRED로 처리합니다. |
| W7 | FIXED | `application-local.yaml:49-53` | `server.address: 127.0.0.1`. |
| R3-S20 | FIXED | `SlackRetryDeduplicator.kt:69-72,123,144-159` | 플래그 `getAndSet`·재설정 순서에 경합 문제가 없습니다. |
| R3-07 | PARTIAL(의도) | `SocketModeReceiver.kt:54-58,141-151` | interactive는 실패 시 ACK를 보내지 않도록 고쳤습니다. slash·event의 선 ACK(`:49-52,59-62`)는 문서화된 설계대로 유지됩니다(local 전용). |
| T22 | FIXED | `ParsedSubmissions.kt:88-121`, `RejectReason.kt:21`, `MeetingSchema.kt:170`, `ModalTemplateBuilder.kt:72,377`, `MeetingServiceImpl.kt:67-105` | 255자 초과 메모는 버리고 거절은 기록합니다. 판정은 UTF-16 길이 기준이라, 통과한 값은 VARCHAR(255)(문자 수 기준)에 항상 들어갑니다. 입력 위젯에 max_length 255가 있습니다. BEFORE_COMMIT 리스너 두 개에서 RetryService를 걷어 냈습니다. 같은 패턴이 남은 곳은 G9입니다. |
| T25 | FIXED | `application.yaml:10-18` | 프로파일 덮어쓰기 없음. 4절의 전수 확인 결과 트랜잭션 밖 LAZY 접근이 0건입니다. |
| T11+T25+지연 쓰기 | 정상 | `SlackInteractionHandlerImpl.kt:69-70`, `MeetingWriteDeferral.kt:6-15`, `MeetingServiceImpl.kt:347-357` | 상호작용 트랜잭션이 실패하면 예외가 `collecting` 밖으로 나가 `forEach`(`:70`)에 도달하지 않으므로 쓰기 큐가 버려집니다. `commit()`이 던지는 경우도 같습니다. 커밋 성공 후 쓰기는 OSIV가 꺼져 있어 바인딩된 EM이 없으므로 시도마다 새 EM·트랜잭션(REQUIRES_NEW)에서 돌고, 실패 회신도 새 트랜잭션입니다. afterCommit 콜백은 역할 evict 하나뿐이라(`RoleManagementService.kt:102-104`) "DB는 커밋됐는데 commit()이 던져 쓰기가 버려지는" 경로는 실질적으로 없습니다. |
| M4 | FIXED(코드·주석) | `JpaMeetingReminderRepository.kt:21-35`, `V18__…sql:1-14`, `V21__…sql` 헤더 | LEFT JOIN FETCH로 통일하고 DISTINCT를 없앴습니다. 새 문서 드리프트는 3절에 적었습니다. |
| M5 | 수정 불필요(검증) | `JpaMeetingReminderRepository.kt:17-35`, `MeetingReminderRepositoryTest.kt:22,225-285` | javap 확인 결과 hibernate-core 7.4.5에 `isPaginationPushedToDerivedTable`이 있어 LIMIT를 파생 테이블로 내립니다. 테스트에서 가장 이른 행이 취소된 회의 것인데도 `limit=2`가 first·second를 돌려주므로, 취소 필터가 LIMIT 안쪽에 적용됩니다. 따라서 취소 행이 배치를 막지 않습니다. |
| M6 | FIXED(잔여 G10) | `MeetingReminderRepository.kt:22-28`, `MeetingReminderSchedulingService.kt:111-121`, `MeetingReminderRepositoryImpl.kt:29-53`, `JpaMeetingReminderRepository.kt:108-131` | 발송 시점에 시각을 다시 확인해 폐기하고, materialize가 재정렬합니다. |
| M7 | FIXED | `JpaMeetingReminderRepository.kt:37-75`, `MeetingReminderSchedulingService.kt:124,142-154` | claim과 markSent에 미취소 `EXISTS`를 걸었습니다. claim 실패 행은 `findPendingBefore`가 취소 회의를 거르므로 큐를 막지 않습니다. |
| M9 | FIXED(문서) | `k8s/README.md:124-125` | — |
| T21 회의 싱크 | FIXED(잔여 G8) | `MeetingRescheduleService.kt:117-122`, `MeetingReminderSchedulingService.kt:184-189`, `DailyAgendaSchedulingService.kt:131`, `ModalTemplateBuilder.kt:140,239,269,356` | 이중 이스케이프는 없습니다. 헤드라인은 plain_text 헤더(`ModalBlockBuilder.kt:20-23`)라 이스케이프가 필요 없습니다. |
| T2 | FIXED | (하위레인) `Routine.kt:39-44,63-65`, `ParsedSubmissions.kt:170-174`, `StandupScheduler.kt:14-26`, `StandupSchedulingService.kt:59-67` | 클래스 수준 `@Transactional`이 없어 루틴별 `runCatching` 격리가 실제로 작동합니다. DB에 이미 있는 큰 cutoff 행은 검증 없는 DTO로 읽혀, 그 루틴만 실패합니다. |
| U6 | FIXED | (하위레인) `StandupSchedulingService.kt:270-287` | — |
| T5 | FIXED | (하위레인) `StandupRoutineSetupService.kt:45-58` | 회신을 `commandChannel`로 보냅니다. 픽스처도 `""`로 맞췄습니다. |
| U8 | FIXED | (하위레인) `ParsedSubmissions.kt:170-174`, `Routine.kt:78-85` | — |
| T9 | 순차 FIXED / 동시 잔여(G4) | `StandupRepositoryImpl.kt:96-114` | — |
| T18 | FIXED(잔여 G6) | `StandupSchedulingService.kt:126-131`(cutoff 뒤 SKIPPED) + (하위레인) `:159-202,233-253` | 재시도는 cutoff까지로 제한됩니다. 넛지는 `claimNudge`의 `status='COLLECTING'` 조건과 `cutoffAt > :now` 후보 조건으로 제한됩니다. |
| T19 | FIXED(잔여 G5·G7) | `StandupRepositoryImpl.kt:88-95`, `StandupAnswerService.kt:36-57`, `StandupSchedulingService.kt:126-131` | `AnswerRecordResult`의 유일한 호출부가 세 결과를 모두 처리합니다. 마감 판정은 주입 Clock을 씁니다. `markSkipped`의 CAS 조건은 `dm_status='PENDING'`뿐이라 SENDING·SENT를 덮지 않습니다(하위레인 `JpaSessionDispatchRepository.kt:62-76`). |
| T28 | FIXED | `StandupSchedulingService.kt:133-143` | 루틴이 없거나 비활성이면 둘 다 `routine == null` → SKIPPED로 끝납니다. |
| T21 스탠드업 | PARTIAL(G3) | `ModalTemplateBuilder.kt:483,510,688,726`은 정상 | 이중 이스케이프는 없고, 호출자 책임인 `Text` 두 곳에서 누락됐습니다. |

## 2. 결함

### 2.1 main 기존 High — 브랜치가 만든 것은 아니지만 T4 수정이 놓침

**G1 · High · 스탠드업 요약 payload가 멤버 수의 제곱으로 불어나 outbox TEXT를 넘고, 요약이 영원히 게시되지 않음**
- **위치**
  - 쿼리: `JpaStandupSessionRepository.kt:16-28,42-52,55-66,83-97`. Set인 `dispatches`와 List(bag)인 `answers`를 한 쿼리로 함께 JOIN FETCH합니다.
  - 매핑·저장: `StandupSessionSchema.kt:51-64,229-236`, `StandupSummaryService.kt:45-58`(중복 목록을 그대로 `MessageContent.StandupSummary`에 담음), `OutboxMessage.kt:39`(`payload TEXT`), `V11__…sql:28`.
- **실패 시나리오**
  - Hibernate 7.4.5 실측(하위레인 프로브): dispatch 3건 × 답변 2건에서 `answers` 원소가 6개입니다.
  - 템플릿의 `associateBy`는 렌더 때만 중복을 거르고, 저장되는 payload에는 n×m개가 들어갑니다.
  - 한국어 50자 × 3문항이면 약 11명에서 65,535바이트를 넘는다고 추정합니다. MariaDB 기본 strict 모드에서는 `Data too long` → 요약 저장 트랜잭션 롤백 → 세션이 COLLECTING에 머물러 매 분 재시도하지만 영원히 게시되지 않습니다.
  - 중복을 없애도 30명 × 상한 길이 한국어 답변이면 TEXT 한도를 넘습니다.
  - H2에서는 CLOB이라 테스트로 잡히지 않습니다.
- **원인 커밋**: main 기존. 970ac12·f7f5dbe(T4)가 저장 경로를 다루지 않았습니다.
- **확신도**: 중복 자체는 HIGH(실측), 임계 인원은 MEDIUM(추정).
- **수정 방향**: `answers`를 Set으로 바꾸거나 매핑에서 `distinctBy { it.id }`를 적용합니다. payload를 MEDIUMTEXT로 바꾸거나 sessionUid만 저장하고 렌더 때 로드합니다. dispatch 2건 이상 + 답변으로 회귀 테스트를 추가합니다.

### 2.2 브랜치가 새로 만들었거나 남긴 결함

| ID | 심각도 | 제목 | file:line | 실패 시나리오 | 원인 커밋 | 확신도 | 수정 방향 |
|---|---|---|---|---|---|---|---|
| G2 | Low~Medium | 새 enum 값 `DispatchStatus.SKIPPED`를 이전 바이너리가 읽지 못해 롤백하면 스탠드업이 정지 | `DispatchStatus.kt:10`, `StandupSchedulingService.kt:126-143` | 첫 틱에 비활성 루틴의 오래된 PENDING이 곧바로 SKIPPED가 됩니다. 그 뒤 헬스 게이트 실패로 `rollout undo`가 되면, 이전 바이너리가 dispatch를 fetch하는 네 쿼리에서 `Enum.valueOf` IllegalArgumentException을 냅니다. 이전 바이너리는 틱 네 단계가 한 `runCatching`이라 날짜가 바뀔 때까지 모든 루틴이 멈춥니다. | 77768ae, 97f9daf | MEDIUM(하위레인, `EnumJavaType.fromName` javap) | "SKIPPED 기록 이후 롤백 불가"를 문서화합니다. 또는 이번 릴리스는 `FAILED` + 사유 접두어로 쓰고, SKIPPED는 다음 릴리스에서 도입합니다. |
| G3 | Low | 스탠드업 `MessageContent.Text` 두 곳에서 루틴 이름 이스케이프 누락 | `StandupSchedulingService.kt:323-325`(넛지 `*$routineName*`), `StandupRoutineSetupService.kt:94-95`(셋업 확인) | 루틴 이름에 `<https://evil|Fill in standup>`을 넣으면 넛지를 받는 미응답자 전원의 DM에 위장 링크가 뜹니다. 테스트 `StandupDispatchMessageBuilderTest.kt:78`, `StandupSchedulingServiceTest.kt:818`(하위레인)이 이스케이프 안 된 문구를 정답으로 고정합니다. | fa862c4 누락(같은 패턴의 회의 쪽은 813c51d가 고침) | HIGH | 두 곳에 `escapeMrkdwn()`을 적용하고 테스트 기대값을 바꿉니다. |
| G4 | Low | 같은 사용자의 동시 최초 제출 두 건은 여전히 유니크 위반 | `StandupRepositoryImpl.kt:99-113` | 모달 두 개를 동시에 제출하면 둘 다 "기존 행 없음"을 보고 INSERT → `uk_standup_answer_session_user` 위반 → 두 번째 상호작용이 롤백되고 일반 오류가 뜹니다. 첫 답변은 저장됩니다. | 2fc9d9c 이후 잔존 | HIGH(메커니즘), 빈도 낮음 | 네이티브 `INSERT … ON DUPLICATE KEY UPDATE`를 씁니다. |
| G5 | Low | cutoff 직전 답변이 "submitted"로 안내되지만 요약에서 빠질 수 있음 | `StandupRepositoryImpl.kt:88-95`, (하위레인) `StandupSummaryService.kt:32-70` | 세션 행을 잠그지 않아, 요약 읽기 뒤에 답변 트랜잭션이 커밋되면 RECORDED로 안내되지만 요약에는 없습니다. 파드 간 시계 차이만큼 창이 넓어집니다. | 77768ae 잔여 | MEDIUM | 세션 행에 `PESSIMISTIC_WRITE`를 겁니다. |
| G6 | Low | T18 재시도에 횟수 제한·백오프 없음 | (하위레인) `StandupSchedulingService.kt:159-202`, `JpaSessionDispatchRepository.kt:16-28` | 행 단위 영구 실패가 batch(50)만큼 쌓이면 cutoff까지 같은 행만 고릅니다(T28과 같은 패턴, 기간은 제한됨). 현재 알려진 영구 실패 원인은 없습니다. | 865ba20 | LOW | 시도 횟수 컬럼을 두거나 `dm_trigger_at`을 뒤로 미룹니다. |
| G7 | Low | 세션을 못 찾으면 DM 공지가 갱신되지 않음 | `StandupAnswerService.kt:53-56` | 예전에는 도메인이 무조건 "submitted"로 바꿨는데, 이제는 피드백 없이 버튼이 그대로 남습니다. | 77768ae | HIGH | 안내 문구로 공지를 갱신합니다. |
| G8 | Low | 거절 사유 상세가 DM 공지 갱신에 이스케이프 없이 들어감(T21 싱크 누락) | `ParsedSubmissions.kt:99`, `DeclineReasonSubmissionContext.kt:35-48`, `OutboundRenderer.kt:121-129`(UpdateMessage는 이스케이프 안 함) | Other 메모 `<!channel>`이나 위장 링크가 원 요청 메시지 갱신에 들어갑니다. 대상이 참가자 본인 DM(`chatPostMessageBuilder`가 `channel(targetUserId ?: channel)`)이라 영향은 본인에게 한정됩니다. 도메인 계층은 `escapeMrkdwn`을 import할 수 없습니다. | fa862c4 누락 | HIGH(경로), 영향 낮음 | 도메인에 이스케이프 함수를 두거나, 해당 UpdateMessage 생성을 이스케이프 가능한 계층으로 옮깁니다. |
| G9 | Low | T22와 같은 BEFORE_COMMIT 재시도가 outbox 저장에 남음 | `SlackMessageRelayServiceImpl.kt:240-253` | `saveOutboxMessage`가 같은 트랜잭션 안에서 `retryService.execute`(기본으로 모든 Exception, 3회)를 돌립니다. 실패가 이미 공유 트랜잭션을 rollback-only로 만들었으므로 재시도는 커넥션을 쥔 채 잠만 잡니다. | main 기존(T22 범위 밖에 잔존) | MEDIUM | 재시도를 제거합니다. 상호작용 전체가 재시도 단위입니다. |
| G10 | Low | M6 재정렬이 동시 틱에서 올바른 행을 옛 시각으로 되돌릴 수 있음 | `MeetingReminderRepositoryImpl.kt:39-44` | 틱 A가 옛 startAt T를 읽음 → reschedule 커밋 → 틱 B(다른 파드)가 T'-10 행을 생성 → 틱 A의 `ensureReminder`가 그 행을 T-10으로 재정렬합니다. 예전에는 이 순서에서 UNIQUE가 올바른 행을 지켰습니다. 발송 시 `isArmedFor` 폐기나 다음 materialize(60초, `MeetingReminderScheduler.kt:13`)가 바로잡아 최대 약 60초 지연에 그칩니다. | 1f012ff | MEDIUM | 수용 가능합니다. 원하면 재정렬 조건에 "현재 startAt 기준 시각과 일치할 때만"을 추가합니다. |
| G11 | Low(테스트) | XFF 거부 테스트가 실제로는 아무것도 검증하지 않음 | `McpTurnTokenFilterTest.kt:44-54` | `MockHttpServletRequest`에 remoteAddr를 직접 넣는데, 필터는 원래 XFF를 읽지 않으므로 수정 전에도 통과합니다. 실질적인 회귀 방지는 `:70-106`(Jetty customizer)뿐입니다. | fbf79e9 | HIGH | 삭제하거나, Jetty `ForwardedRequestCustomizer`를 거치는 형태로 바꿉니다. |
| G12 | Low(테스트) | bag 중복을 못 잡는 픽스처 | (하위레인) `StandupRepositoryImplJpaTest.kt:54,93` | dispatch가 1건이라 `answers.size shouldBe 1`이 중복 여부와 상관없이 통과합니다(G1을 놓침). | 2fc9d9c·77768ae | HIGH | dispatch 2건 이상으로 단언합니다. |

**Open Questions**: 낮은 확신도의 Critical/High는 없습니다.

## 3. 문서 모순

- `docs/wiki/events-and-outbox.md:23-25`: "`SlackMentionEventHandlerImpl.handleEvent`, `SlackInteractionHandlerImpl.handleInteraction`가 `@Transactional`"이라고 적었습니다. 실제로 멘션은 `TransactionTemplate`(16fca45)이고, 상호작용은 수동 트랜잭션입니다. 지연 회의 쓰기의 outbox 행이 상호작용과 별도 트랜잭션으로 커밋된다는 점도 빠졌습니다.
- `application/.../service/meeting/AGENTS.md:30`: "`@Transactional` boundary of … `handleInteraction`" → 실제는 수동 트랜잭션입니다(경미).
- `infrastructure/.../repository/meeting/AGENTS.md:98-102`: `MeetingReminderRepositoryTest`가 `findDueBefore`만 다루고 claim 경합은 application 목 스펙에서만 다룬다고 적었습니다. 실제 테스트에는 M6·M7 케이스(`MeetingReminderRepositoryTest.kt:75-223`)가 있고, 테스트 디렉터리 AGENTS(`:17`)도 그렇게 적었습니다. 1f8e3e2가 cherry-pick 순서상 뒤 커밋의 서술을 덮은 것으로 보입니다.
- (하위레인) 스탠드업 문서:
  - `repository/standup/AGENTS.md:45-46`과 `schema/AGENTS.md:18-20`: Set+List를 한 쿼리로 fetch해도 된다고 적었지만 실제로는 bag이 중복됩니다(G1).
  - `repository/standup/AGENTS.md:38-39`: "concurrent … last-writer-wins" → 동시 최초 제출은 유니크 위반입니다(G4).
  - `domain/.../standup/entity/enums/AGENTS.md:27-28`: "no migration"만 적고 롤백 비호환을 빠뜨렸습니다(G2).
  - `entity/AGENTS.md:23`: `SENT | FAILED`만 적었고 FAILED가 더는 기록되지 않는 점이 빠졌습니다(경미).
  - `application/src/test/AGENTS.md:8`: "no H2"라고 하지만 `StandupSchedulingServiceTest.kt:593-691`이 H2를 씁니다(경미, 기존).
- 일치 확인: `decisions.md:59-64`(#15), `security/AGENTS.md:57-62`(W5), `resources/AGENTS.md:63-82`와 `application-prod.yaml:13-14`(요청당 커넥션 1개), `mcp/AGENTS.md:30-33`, `service/command/AGENTS.md:17,34-42`는 코드와 일치합니다.

## 4. 확인했고 문제 없던 것

**T25 지연 로딩 전수 확인 — 트랜잭션 밖 LAZY 접근 0건**

- **LAZY 연관 전체 목록**
  - `MeetingSchema.participants`(`MeetingSchema.kt:38-45`), `ParticipantsSchema.meeting`(`:160`), `MeetingReminderSchema.meeting`(`MeetingReminderSchema.kt:26-28`)
  - `RoutineSchema.members`·역참조(`RoutineSchema.kt:52-54,79`)
  - `StandupSessionSchema.dispatches`·`answers`(`StandupSessionSchema.kt:51-64`), `SessionDispatchSchema.session`(`:92`), `StandupAnswerSchema.session`(`:126`)
  - CVE·agent·mcp·outbox·authorization 스키마에는 연관이 없습니다.
- **트랜잭션 없는 읽기는 모두 접근 대상을 fetch join**
  - `MeetingRepositoryImpl.kt:26-50`(→ `JpaMeetingRepository.kt:16-72,107-116`, 전부 `LEFT JOIN FETCH m.participants`)
  - `MeetingReminderRepositoryImpl.kt:15-27,83-98`(`JOIN FETCH r.meeting` + participants)
  - `AgendaDispatchRepositoryImpl.kt:14-27`
  - `StandupRepositoryImpl.kt:45-59,71-79,131-170`(루틴 members, 세션 dispatches+answers, dispatch `JOIN FETCH d.session`)
  - 매퍼(`toMeetingDto`, `toMeetingReminderDto`, `toRoutineDto`, `toStandupSessionDto`)는 역참조 ManyToOne에 접근하지 않습니다.
- **LAZY 프록시를 만드는 호출은 트랜잭션 안에서만**: `getReferenceById`는 `ensureReminder`(`@Transactional`, `:32-53`) 안에서만 쓰입니다. 파생 `findByMeetingIdAndOffsetMinutes`는 meeting에 접근하지 않습니다. `recordAnswer`와 회의 쓰기 세 개는 `@Transactional` 안에서만 관리 엔티티를 다룹니다.
- **application 계층**: 스키마는 enum만 import하고, 리포지토리 밖으로는 DTO만 나갑니다. MCP 도구·렌더러·템플릿은 DTO만 받습니다. `REQUIRES_NEW`는 `isolatedWriteTemplate` 한 곳(`MeetingServiceImpl.kt:349`)뿐이라 HTTP 요청당 커넥션 1개 주장도 맞습니다.
- 메인 세션의 런타임 확인(빈 데이터에서 `/meetup list`·`/standup list` 200)을 보완하면: 매퍼가 fetch join된 컬렉션만 건드리므로 데이터가 있어도 `LazyInitializationException`은 나지 않습니다.

**그 밖에 확인한 것**
- **OSIV 테스트**: `MeetingWriteJpaTransactionTest`의 `throughHandler` 대조(OSIV 끔이면 별도 EM, 바인딩되면 stale 회신)와 프로파일 4종 `false` 고정은 실제 핸들러·실제 `JpaTransactionManager`로 의미 있게 검증합니다.
- **스탠드업 이스케이프·절단**(하위레인)
  - 요약 템플릿은 한 번만 이스케이프합니다(이중 이스케이프 없음).
  - 2,900자 절단은 이스케이프 뒤 길이 기준이고, `takeSafely`는 엔티티 중간을 자르지 않습니다.
  - 답변 max_length는 이스케이프된 질문 길이와 줄 오버헤드 6으로 계산합니다.
- **CAS 조건**: `markDispatchSkipped`·`markDispatchSent`·`claimNudge`·`claimReminder`·`markSent`가 모두 `== 1` CAS라 복제본 간 중복 발송이 없습니다.
- **네이티브 쿼리의 Instant 바인딩**: `realignPending` 테스트가 재조회 값의 정확한 일치를 단언합니다(`MeetingReminderRepositoryTest.kt:90`).
- **T10 시나리오**: revoke는 파드와 무관하게 다음 호출에 즉시 반영됩니다. MCP 게이트(`McpToolGate.kt:54`)도 같은 resolver를 씁니다.
