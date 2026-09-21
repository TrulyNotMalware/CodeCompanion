# 기술 결정 기록

_type: decision · updated: 2026-09-21_

> 근거가 있는 결정만 남긴다. 결정이 뒤집히면 항목을 지우지 말고 상태를 바꾸고 이력을 덧붙인다.

형식 — **결정** / 이유 / 상태(`유지` · `열림` · `폐기`) / 근거. 날짜는 확인 가능한 것만 적는다.

## 아키텍처

1. **세 모듈 계층을 Gradle 컴파일 단위로 강제한다.** `domain`은 의존성 0, 가드 테스트가 전송·직렬화 결합을
   막는다. — 이유: 도메인 순수성을 사람의 주의력이 아니라 빌드에 맡기기 위해. 상태: 유지.
   근거: `domain/build.gradle.kts`, `DomainLayeringGuardTest.kt`. → [ddd-layering.md](ddd-layering.md)
2. **전송 중립성은 점진적으로, 단계마다 자체 이득이 있을 때만.** 멀티 전송(Discord)은 장기 로드맵에 있지만
   구현 대상은 아니므로 빅뱅 이식성 작업을 하지 않는다. 상태: 유지(Phase 0~6 완료, 2026-06~07).
   근거: `Refactor.md` 머리말·§2. → [history.md](history.md)
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
    사이드카는 도메인 무지로 유지. 바인드는 루프백 전용. 상태: 유지(1차 2026-07-13). 근거: `Refactor.md`
    "후속 플랜 — Agent lane MCP".
15. **턴 단위 스코프 토큰 + 호출 시점 역할 재-resolve.** 이유: 사이드카의 사용자 사칭 방지, 대화 중 revoke
    즉시 반영. 채팅 명령과 **같은 `CommandPermission` 상수**를 공유해 두 경로의 drift를 막는다. 상태: 유지.
16. **MCP 1차는 조회 도구만. 쓰기 도구는 미리보기+확인 플로우·멱등성·rate limit 이후.** 이유: 프롬프트
    인젝션 — 스레드의 타인 텍스트가 모델을 조종할 수 있으므로 권한은 항상 멘션 작성자 기준. 상태: 유지
    (2차 미착수). 근거: `Refactor.md` MCP 설계 결정 4.
17. **키워드 파서는 유지하고 자연어는 추가 경로로만.** 이유: 결정적 명령은 빠르고 예측 가능해야 하며 LLM
    대체는 비용·지연·비결정성 문제. 상태: 유지.
18. **CVE 봇은 새 저장소가 아니라 이 앱의 기능으로.** MariaDB·아웃박스·CAS·모달 파이프라인을 재사용. 이유:
    별도 앱은 DB·Slack 수신·DM 발송을 전부 중복 구현하게 된다. 상태: 유지(M1~M6 완료 2026-07-14).
    근거: `CveBotPlan.md` §0.

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
25. **툴체인은 최신 GA를 따른다 — Java 25, Kotlin 2.4, Spring Boot 4.1, Gradle 9.5.** 이유: (근거 미기록 — 채워
    넣을 것). 상태: 유지. 근거: `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`.
26. **`gradle.properties`는 생성물(git-ignore). 공유 설정은 `gradle-config/` 프리셋을 고친다.** 상태: 유지.
    근거: `gradle-config/README.md`.

## CI/CD와 공급망

27. **문서만 바뀐 변경은 lint·test·deploy를 태우지 않고, 프로덕션에 절대 배포되지 않는다.** 그 세 워크플로우는
    `!**/*.md`로 경로 필터링한다. 예외는 `security_check.yaml`: 시크릿은 `.md`에도 숨을 수 있으므로 gitleaks는
    `main` 대상 모든 push/PR에서 돌고, CodeQL·의존성 그래프만 소스 변경에 게이트된다. 상태: 유지(2026-08-26).
    근거: `.github/workflows/*.yaml`, `.github/AGENTS.md`.
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

## 근거

- 각 항목에 명시. 로컬 전용 계획 문서(`Refactor.md`, `Handoff.md`, `CveBotPlan.md`)는 작성자의 작업 트리에만
  있으며, 여기 옮긴 요지가 저장소에 남는 기록이다.

## 관련 페이지

- [ddd-layering.md](ddd-layering.md) · [events-and-outbox.md](events-and-outbox.md) ·
  [command-pipeline.md](command-pipeline.md) · [dev-environment.md](dev-environment.md) · [history.md](history.md)
