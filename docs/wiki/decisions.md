# 기술 결정 기록

_type: decision · updated: 2026-10-11_

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

## 권한과 AI

13. **역할 계층 `user ⊂ ai_user ⊂ developer ⊂ admin`, 멘션 경로만 게이트.** 슬래시·인터랙션은 BASIC 전용이라
    게이트하지 않는다. 설정의 `bootstrap-admins`는 불변(채팅으로 grant/revoke 불가)이며 DB row보다 우선.
    이유: 부트스트랩 순환 문제 해소. 상태: 유지(2026-07-07, Phase 10). 근거: `Refactor.md` Phase 10,
    `README.md` Bot Commands & Roles.
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
24. **`@Transactional`은 Repository `Impl`(`open class`)에.** 이유: CGLIB 프록시가 걸리는 자리를 한 곳으로.
    상태: 유지. 근거: `Refactor.md` §5-1, Phase 10 노트.
25. **툴체인은 최신 GA를 따른다 — Java 25, Kotlin 2.4, Spring Boot 4.1, Gradle 9.8.0.** 이유: (근거 미기록 — 채워
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
    다음 마이그레이션 번호 V24, 보존 기간 정리 작업.
    근거: `application/.../security/SlackRetryDeduplicator.kt`, `application/src/main/resources/k8s/deployment.yaml`,
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
- Google Calendar(#36·#37): `git show --stat 3c0529bf 6ae0a047`(`archive/gap-closure` 태그 기준), `application/src/main/kotlin/dev/notypie/application/service/calendar/AGENTS.md`,
  `infrastructure/src/main/kotlin/dev/notypie/repository/calendar/AGENTS.md`, `db/migration/V24__…`·`V25__…`, git-ignored
  `.omc/plans/google-calendar-user-oauth.md`(설계와 리뷰 반영 결정 전부)
- 작업 트리: `.github/workflows/security_check.yaml`, `.github/dependabot.yml`, `.gitleaks.toml`,
  `build.gradle.kts` diff

## 관련 페이지

- [architecture-overview.md](architecture-overview.md) · [ddd-layering.md](ddd-layering.md) ·
  [events-and-outbox.md](events-and-outbox.md) · [command-pipeline.md](command-pipeline.md) ·
  [dev-environment.md](dev-environment.md)
