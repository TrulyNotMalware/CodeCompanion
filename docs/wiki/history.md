# 프로젝트 연혁과 설계 전환점

_type: history · updated: 2026-09-21_

> 2024-06 Slack 봇 골격에서 출발해 아웃박스·CDC 릴레이, 미팅/스탠드업, transport 중립 리팩토링, 역할·에이전트·
> MCP, CVE 봇을 거쳐 2026-08 문서/CI 하드닝에 이르는 시간순 기록이며, 각 국면이 무엇에 반응한 결정이었는지를 남긴다.

## 읽기 전에 — 브랜치 토폴로지

- `main`은 PR squash로만 전진한다: `116755b` (#10, 2026-04-08) → `dc2b904` (#11, 2026-08-25). #11의 트리는
  `develop` 끝 커밋 `639aaf2`와 **바이트 동일**하고, 그 사이 `develop` 62개 커밋(2026-04-13~07-14)은 `main`
  first-parent 이력에 나타나지 않는다.
- 아래 표의 2026-04~07 해시는 이 로컬 clone의 `develop` 브랜치에서 확인한 것이다. 현재 remote에는
  `origin/main`·`origin/feature/agents-md`만 있어 **새 clone에서는 이 해시들이 resolve되지 않는다**
  (계획 문서가 말하는 `origin/develop`은 #11 병합 후 삭제된 것으로 보인다 — 미확인).
- 2025-09~2026-03 구간은 `feat/update` 등 리베이스된 사본 때문에 같은 제목의 커밋이 최대 4개 존재한다.
  표에는 `main`에 도달한 해시만 적는다.

## 마일스톤

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

## 설계가 움직인 흐름

1. **Slack 형상의 도메인(2024)** — `domain/command`가 `SlackApiRequester`, `SlackApprovalContext`, Slack wire
   DTO(`@JsonProperty`)를 직접 품었고 History가 모든 명령을 감사 기록했다. 이 시기의 관심사는 "Slack API가
   실패해도 메시지를 잃지 않기"였고 그 답이 2024-09 아웃박스, 2025-03 Debezium CDC 릴레이다.
2. **기능 확장기(2025)** — 미팅·서브커맨드·MCP 실험·CI가 얹히면서 context/파서가 커졌고, 2025-10 "context
   도메인 분리"가 응집성 문제를 처음 손댄 지점이다.
3. **신뢰성 하드닝(Handoff, 2026-04~06)** — 서명 검증·재시도 dedup, outbox `IN_PROGRESS` claim/lock과
   stuck 복구, payload `schemaVersion`, 그리고 스탠드업 스케줄러. Codex 리뷰 4라운드에서 **claim-token CAS +
   `updated_at` 명시 갱신** 패턴이 확정됐고 이후 리마인더·agenda·CVE 워커가 전부 이 패턴을 복제한다.
4. **transport 중립 리팩토링(Refactor Phase 0~8, 2026-06-30~07-06)** — 진단 결과가 출발점이다: `CommandIntent`
   24변종 중 순수 도메인은 5개, 도메인 안에 Jackson 어노테이션 6곳, History를 거치는 양방향 순환. History
   삭제(Phase 0)로 순환의 anchor를 없애고, `DomainIntent`/`OutboundMessage`/`SlackOutboundStager`로 god-type을
   쪼개고(Phase 3), `InboundCommand`·`InboundForm`으로 인바운드를 중립화(Phase 4)했다. Phase 5·6는 "완전
   중립"이라는 **과장을 교차검증으로 바로잡는 정직화 단계**였다(`BASE_URL` 잔존, Jackson이 컴파일 클래스패스에
   잔존, negative control 미구현). Phase 8b에서 outbox가 렌더된 Slack 페이로드 대신 중립 envelope +
   `transport` 컬럼을 저장하게 되어 렌더 시점이 deliver로 이동했다.
5. **에이전트·권한·MCP(2026-07-03~13)** — 다른 세션이 만든 `@bot ask` lane은 pre-8b 계약(즉시 렌더 이벤트)이라
   리베이스만으로는 응답이 조용히 유실됐고, Phase 9에서 스테이저 경로로 이식했다. Phase 10 역할 계층은 k8s
   e2e 중 "아무나 명령을 쓰면 안 된다"는 결정에서 나왔다. MCP는 사이드카를 도메인 무지로 두고 앱 안에
   읽기 전용 도구만 여는 형태로 돌아왔다(프롬프트 인젝션 방어가 1차를 조회 도구로 제한한 이유).
6. **CVE 봇(2026-07-13~14)** — 원본 기획을 기존 원시 요소(outbox, CAS 레저, 역할 게이트, typed submission)
   위에 재배치했다. 착수 전 탐색 3기로 Phase 8~10 이후 코드를 다시 검증해 낡은 전제를 고쳤다.
7. **문서·CI 하드닝(2026-08)** — 코드 변경 없이 AGENTS.md 계층, docs 제외 트리거, 보안 워크플로,
   Dependabot, 이 위키가 추가됐다.

## 상세 이력이 있는 계획 문서

| 문서 | 상태 | 내용 |
|------|------|------|
| `Handoff.md` | git-ignored(`73e2f7e`), 최종 2026-06-18 | 로드맵 Phase 1~4, 항목 #1~#16 상태, Standup #12 Codex 리뷰 표 |
| `Refactor.md` | git-ignored(`73e2f7e`), 최종 2026-07-13 | Refactor Phase 0~10 + MCP 후속 플랜, 커밋 해시, 교차검증 정직화 기록 |
| `RealTestSetup.md` | git-ignored(`73e2f7e`) | `slack-live` 프로파일(구 `real`)용 Slack 앱 설정 체크리스트 |
| `CveBotPlan.md` | git-ignored(`4d5ab94`), 최종 2026-07-14 | CVE-Bot M1~M6, §0.5 현행 코드 검증, 롤아웃 체크리스트 |
| `STYLE_GUIDE.local.md` | git-ignored(`2985257`) | 코딩 규칙 원본 — [coding-style.md](coding-style.md)가 요약 |
| `.omc/plans/mcp-domain-tools-phase1.md` | `.omc/` 전체 ignored, 2026-07-08 | MCP Phase 1 실행 계획(A1~A7, 사이드카 B1~B5, e2e) |
| `README.md`, `AGENTS.md` | 추적됨 | 기능 목록·아키텍처, 디렉터리별 에이전트 지침(`AGENTS.md`는 `65c41ed`까지 ignored였음) |

위 ignored 문서는 **로컬 작업 트리에만 존재**하므로 새 clone에서는 이 페이지와 [decisions.md](decisions.md)가 유일한
기록이다. 문서가 인용한 커밋 해시는 이 clone에서 전부 확인됐다(`git show --oneline -s`).

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

미착수로 남은 것: Handoff #15 Prometheus 메트릭, MCP 쓰기 도구(Phase 2, 설계만), 2nd transport(Discord —
`Transport.DISCORD` + `DiscordOutboundRenderer` 구현이 남은 전부라는 것이 Phase 8b의 결론).

## 근거

- `git log --all --date=short --format='%ad %h %s'` (225 커밋), `git log --first-parent main`,
  `git diff --quiet 639aaf2 dc2b904`(트리 동일), `git branch -a`
- `git show --stat` : `589f5f6`, `cdc0da2`, `154f340`, `d372712`, `ade246a`, `dc2b904`, `65c41ed`, `c3e7ad9`
- `Handoff.md`, `Refactor.md`, `CveBotPlan.md`, `STYLE_GUIDE.local.md`,
  `.omc/plans/mcp-domain-tools-phase1.md` (모두 git-ignored, 로컬 작업 트리 기준)
- `.gitignore`, `README.md`, `application/src/main/resources/db/migration/V1..V17`
- 작업 트리: `.github/workflows/security_check.yaml`, `.github/dependabot.yml`, `.gitleaks.toml`,
  `build.gradle.kts` diff

## 관련 페이지

- [architecture-overview.md](architecture-overview.md) — 현재 모듈 구조(리팩토링 종착점)
- [ddd-layering.md](ddd-layering.md) — Refactor Phase 0~8이 남긴 순수성 규칙과 가드 테스트
- [command-pipeline.md](command-pipeline.md) — `InboundCommand` → `CommandEffect` → `OutboundMessage` 파이프라인
- [events-and-outbox.md](events-and-outbox.md) — 아웃박스 envelope V2, 릴레이 모드, CAS 패턴
- [decisions.md](decisions.md) — 이 페이지의 전환점을 ADR 형식으로 정리
- [dev-environment.md](dev-environment.md) — 프로파일·마이그레이션 관례·CI 워크플로
