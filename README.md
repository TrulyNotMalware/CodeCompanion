# CodeCompanion

CodeCompanion is a Slack bot built with Kotlin and Spring Boot for side-project teams. It turns Slack slash commands, mentions, and interactive components into reliable, event-driven workflows — meeting orchestration, standups, and more — backed by a transactional outbox and Kafka so nothing is lost between Slack and the database.

## Features
- **Slack Event Handling** — Process mentions, messages, and interactive components (buttons, dropdowns, modals).
- **Slash Commands** — Execute custom `/` commands such as `/meetup` directly from Slack.
- **Meeting Orchestration** — Request, approve/decline, cancel, and list team meetings through interactive Slack modals, with host-only authorization enforced at the repository layer. Attendees get reminder DMs 15 and 5 minutes before a meeting (`slack.app.meeting.reminder.offsets-minutes`, default `15,5`) and a daily agenda DM of that day's meetings at 08:00 Asia/Seoul (`slack.app.meeting.agenda.*`). On `/meetup list`, the host's own rows carry **Reschedule**, **Add participant** and **Cancel** buttons.
- **Standup Automation** — `/standup setup|list|stop` creates, lists and stops a channel's recurring standup routines. Members answer a DM prompt, members who have not answered get a nudge DM 30 minutes before the cutoff (`slack.app.standup.nudge.offset-minutes`), and at the cutoff the summary is posted to the routine's summary channel.
- **AI Assistant** — `@bot ask <question>` runs one agent turn against a claude/codex sidecar (HTTP+SSE) and replies in a thread; mentioning again in the thread continues the same session.
- **CVE Watch** — Watched topics are polled from external sources (NVD, GitHub Releases) on a schedule, summarized once each by the AI lane, then delivered to subscribers as an immediate DM or a daily digest. Users follow topics with `/subscribe` and `/unsubscribe`, list them with `/subscriptions`, and read recent summaries with `/latest [topic-key]`; all four reply by DM. Admins manage topics in-chat with `@bot cve ...`. The feature is off unless `slack.app.cve.enabled=true`.
- **MCP Server** — A role-gated Model Context Protocol endpoint (`/mcp`, streamable HTTP) exposing seven read-only domain tools (`get_status`, `list_meetings`, `list_roles`, `list_standups`, `list_cve_subscriptions`, `cve_latest`, `get_ai_usage`) to the AI agent, authenticated per turn and audited to `mcp_tool_call_history`.
- **Role-Based Access Control** — Commands are gated by per-user roles (`user` → `ai_user` → `developer` → `admin`) stored in `user_command_role`; admins manage grants in-chat via `@bot grant / revoke / roles`, and bootstrap admins come from configuration.
- **Event-Driven Architecture** — Asynchronous processing over Kafka with a **transactional outbox** and **Debezium-driven CDC relay** for durable, at-least-once delivery with idempotent consumers (see `docs/wiki/events-and-outbox.md` for the exact guarantees).
- **Turn Auditing** — Every AI turn is persisted to `agent_turn_history` (token usage, duration, outcome) for cost tracking and debugging. `@bot usage [days]` and the MCP tool `get_ai_usage` read it back as a summary of turns, tokens, top requesters and MCP tool calls.
- **Operational Health** — Spring Boot Actuator endpoints plus a custom outbox health indicator reporting pending lag and stuck rows.
- **Security** — Slack request signature verification and retry de-duplication via a servlet filter.

## Tech Stack
- **Language / Runtime**: Kotlin `2.4.10`, Java `25` (Adoptium toolchain)
- **Framework**: Spring Boot `4.1.1` (Web on Jetty, Actuator, AOP/AspectJ, Data JPA)
- **Build**: Gradle `9.8.0` (multi-module), ktlint `14.2.0`
- **Messaging**: Apache Kafka (`spring-boot-starter-kafka`) + Debezium CDC outbox relay
- **Persistence**: JPA / Hibernate — MariaDB (runtime, every profile), H2 (tests only)
- **Slack**: Slack Java SDK `1.52.0` (`slack-api-client`, `slack-api-model`, `slack-app-backend`)
- **AI / MCP**: Spring AI `2.0.1` (BOM; MCP server modules `spring-ai-autoconfigure-mcp-server-webmvc`, `spring-ai-mcp`, `spring-ai-mcp-annotations`, `mcp-spring-webmvc`)
- **Serialization**: Jackson 3 (`tools.jackson`, BOM `3.2.3`)
- **Logging**: kotlin-logging `8.0.4`
- **Testing**: Kotest `6.2.5` (`BehaviorSpec`) + MockK `1.14.11`, with `EmbeddedKafka` and H2 for self-contained integration tests

## Architecture
CodeCompanion follows a DDD-inspired, three-module layering. Dependencies flow **application → infrastructure → domain** (and **application → domain**). The `domain` module is framework-free Kotlin — no Spring, no Jakarta, no JPA. It is also **transport-agnostic**: `domain/command` carries zero Slack types. Inbound requests are normalized into a neutral model (`InboundCommand` / `InboundInteraction`) and outbound results are expressed as transport-neutral `OutboundMessage`s, so a future adapter (e.g. Discord) can be added without touching the domain.

```
CodeCompanion/
├── domain/                      # Pure Kotlin core — no framework, no transport types
│   ├── command/                 # Transport-neutral command core
│   │   ├── authorization/       #   Role & permission model (UserRole, CommandPermission)
│   │   ├── inbound/             #   Neutral inbound models (InboundCommand, InboundInteraction)
│   │   ├── outbound/            #   Neutral outbound messages & stager contract
│   │   ├── intent/              #   CommandIntent hierarchy (meeting, notice, agent, roles, ...)
│   │   ├── entity/              #   Command aggregate: contexts, parsers, events, slash commands
│   │   └── dto/                 #   Modal & response DTOs
│   ├── meet/                    # Meeting aggregate & DTOs
│   ├── standup/                 # Standup routines, sessions, answers
│   └── common/                  # Shared value objects & validation DSL
│
├── application/                 # Spring Boot bootstrap + use-case orchestration
│   ├── controllers/             # Slack event / slash / interaction endpoints
│   ├── service/                 # Use cases: agent, command (roles), cve, interaction, meeting, mention, ops, relay, standup
│   │   └── cve/                 #   Collector, AI summary worker, subscription, query, notification, ops
│   ├── socket/                  # Slack Socket Mode connector
│   ├── security/                # Slack signature verification & retry dedup filter
│   │   └── mcp/                 #   Scoped per-turn tokens for the MCP endpoint
│   ├── mcp/                     # Role-gated MCP tools (McpToolGate, DomainReadTools)
│   ├── health/                  # Outbox health indicator
│   └── configurations/          # Beans, conditions, async/Kafka wiring
│
└── infrastructure/              # Concrete adapters — all Slack coupling lives here
    ├── impl/
    │   ├── agent/               # AI sidecar client (HTTP + SSE)
    │   ├── command/             # Slack adapter: inbound mapping, intent resolving, outbound rendering & staging
    │   │   ├── slack/           #   Slack wire DTOs & inbound mappers (payload → InboundCommand)
    │   │   └── event/           #   Slack event payloads, dispatch events, message dispatcher
    │   ├── cve/                 # CVE feed adapters (NVD, GitHub Releases)
    │   └── retry/               # Retry support
    ├── repository/              # JPA repositories: agent, authorization, cve, mcp, meeting, outbox, standup
    └── templates/               # Slack message & modal builders
```

Each module ships its own `src/testFixtures/kotlin/` factories (Gradle `java-test-fixtures`), reused cross-module via `testFixtures(project(":domain"))`.

Design philosophy, layering rules, the outbox/event model, coding style and the decision log live in the
project wiki: [`docs/wiki/index.md`](docs/wiki/index.md). Per-directory working notes for AI agents are the
`AGENTS.md` files.

## Bot Commands & Roles
Commands are gated by per-user roles. Roles are cumulative — `user` ⊂ `ai_user` ⊂ `developer` ⊂ `admin`, each level includes everything below it.

| Command | Surface | Minimum role |
|---------|---------|--------------|
| `/meetup`, `/meetup list [today\|tomorrow\|week\|month]` | Slash command | `user` |
| `/standup setup` — create a standup routine (modal) | Slash command | `user` |
| `/standup list` — this channel's active standup routines | Slash command | `user` |
| `/standup stop <routine-name>` — only the routine's creator or an admin may stop it | Slash command | `user` |
| `/subscribe` — pick CVE topics to follow (modal, confirmation by DM) | Slash command | `user` |
| `/unsubscribe` — pick CVE topics to stop following (modal, confirmation by DM) | Slash command | `user` |
| `/subscriptions` — your CVE topic subscriptions (DM) | Slash command | `user` |
| `/latest [topic-key]` — latest summarized CVE updates, optionally for one topic (DM) | Slash command | `user` |
| `@bot help` | Mention | `user` |
| `@bot ask <question>` — any free-text mention also falls back to `ask` | Mention | `ai_user` |
| `@bot status` | Mention | `developer` |
| `@bot usage [days]` — AI turns, tokens and MCP tool calls for the last N days (default 7, max 90) | Mention | `developer` |
| `@bot notice @user1 @user2 <message>` | Mention | `developer` |
| `@bot grant @user <user\|ai_user\|developer\|admin>` | Mention | `admin` |
| `@bot revoke @user` | Mention | `admin` |
| `@bot roles` | Mention | `admin` |
| `@bot cve topics` | Mention | `admin` |
| `@bot cve topic activate\|deactivate <topic-key>` | Mention | `admin` |
| `@bot cve retry all\|<event-id>` | Mention | `admin` |

Replies to mention commands (`help`, `status`, `usage`, role and CVE management) are ephemeral — visible only to the person who sent the command.

Slash commands reach the app by URL, not by name: `/meetup` posts to `/api/slash/meet` and every other command to
`/api/slash/<command>`, as declared in [`docs/slack-app-manifest.yaml`](docs/slack-app-manifest.yaml).

A user's role is resolved in order: `slack.app.authorization.bootstrap-admins` (config-managed admins, immutable from chat) → `user_command_role` table row → default `user`. Admins manage grants entirely in-chat via `grant` / `revoke` / `roles`; interactive components and slash commands are `user`-level surfaces, so modals keep working for everyone.

## Getting Started
1. **Clone the repository**
   ```bash
   git clone https://github.com/TrulyNotMalware/CodeCompanion.git
   cd CodeCompanion
   ```

2. **Create the Slack app and configure credentials**
   - Replace the `https://<your-host>` placeholders in [`docs/slack-app-manifest.yaml`](docs/slack-app-manifest.yaml) with the app's public base URL
   - At [api.slack.com/apps](https://api.slack.com/apps), choose *Create New App → From an app manifest*, paste the edited manifest, and install the app to your workspace. Slack verifies the Events URL against the running app, so it passes only once step 5 is done
   - Copy the Bot User OAuth Token into `slack.app.api.token` and the Signing Secret into `slack.app.api.signing-secret`
   - The `local` profile uses a separate dev app with Socket Mode instead of Request URLs: create it from the same manifest with Socket Mode on, and put an app-level token with the `connections:write` scope into `slack.app.api.app-token` (see the manifest header)

3. **Set up the development environment**
   ```bash
   ./gradle-config/apply.sh                          # apply shared Gradle properties
   ./gradlew addKtlintCheckGitPreCommitHook          # install ktlint pre-commit hook
   ```

4. **Build**
   ```bash
   ./gradlew :application:build
   ```

5. **Run**
   ```bash
   ./run JAR_FILE_PATH
   ```

6. **Verify**
   - Invite the bot to a Slack channel
   - Trigger a slash command (e.g. `/meetup`) or mention the bot

7. **(Optional) Enable the AI assistant**
   - Run [agent-sidecar](https://github.com/TrulyNotMalware/agent-sidecar) next to the app and pick a backend with `PROVIDER=claude|codex` (subscription OAuth token or API key — see that repo's README for the auth options)
   - Point the app at it: `slack.app.agent.sidecar.base-url` (default `http://127.0.0.1:7300`) and `slack.app.agent.sidecar.bearer-secret` (must match the sidecar's `BEARER_SECRET`)
   - Grant access: add your Slack user id to `slack.app.authorization.bootstrap-admins`, then `@bot grant @teammate ai_user`
   - Container recipe (verified 2026-10-07 with the `codex` provider; `claude` also needs `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY`):
     ```bash
     docker build -t agent-sidecar <path-to-agent-sidecar-checkout>
     docker run -d -p 127.0.0.1:7300:7300 -v sidecar-state:/var/lib/claude-sidecar -v "$PWD/CLAUDE.md":/workspace/CLAUDE.md:ro \
       -e PROVIDER=codex -e BEARER_SECRET=<same value as slack.app.agent.sidecar.bearer-secret> \
       -e MCP_SERVER_URL=http://host.docker.internal:9000/mcp -e MCP_SERVER_NAME=domain-tools agent-sidecar
     ```
   - For the agent to call the MCP tools, start the app with `--slack.app.mcp.enabled=true --spring.ai.mcp.server.enabled=true --spring.ai.mcp.server.protocol=STREAMABLE` and set `slack.app.mcp.signing-secret`. The full recipe, including volume ownership, codex credentials and timeouts, is in [`docs/wiki/dev-environment.md`](docs/wiki/dev-environment.md)

### Testing
```bash
./gradlew build                 # full pipeline: compile + ktlint + tests
./gradlew :domain:test          # module-scoped tests while iterating
./gradlew :application:test
./gradlew :infrastructure:test
```
Integration tests run against `EmbeddedKafka` and H2 — no external infrastructure is required.

---

# CodeCompanion (한국어)

CodeCompanion은 사이드 프로젝트 팀을 위한 Kotlin · Spring Boot 기반 슬랙 봇입니다. 슬랙의 슬래시 명령어, 멘션, 상호작용 컴포넌트를 신뢰성 있는 이벤트 기반 워크플로우(미팅 관리, 스탠드업 등)로 전환하며, 트랜잭셔널 아웃박스와 Kafka를 통해 슬랙과 데이터베이스 사이에서 메시지 유실이 없도록 보장합니다.

## 기능
- **슬랙 이벤트 처리** — 멘션, 메시지, 상호작용 컴포넌트(버튼·드롭다운·모달) 처리
- **슬래시 명령어** — `/meetup` 등 사용자 정의 `/` 명령어를 슬랙에서 직접 실행
- **미팅 오케스트레이션** — 상호작용 모달을 통한 미팅 요청·수락/거절·취소·조회. 리포지토리 계층에서 호스트 전용 권한을 원자적으로 강제. 참석자는 미팅 15분·5분 전에 리마인더 DM(`slack.app.meeting.reminder.offsets-minutes`, 기본값 `15,5`)을, 매일 08:00(Asia/Seoul)에 그날 미팅을 모은 일일 아젠다 DM(`slack.app.meeting.agenda.*`)을 받음. `/meetup list`에서 호스트 본인의 미팅 행에는 **Reschedule**·**Add participant**·**Cancel** 버튼이 붙음
- **스탠드업 자동화** — `/standup setup|list|stop`으로 채널의 반복 스탠드업 루틴을 생성·조회·중지. 멤버는 DM 프롬프트로 답하고, 아직 답하지 않은 멤버는 마감 30분 전에 재촉 DM(`slack.app.standup.nudge.offset-minutes`)을 받으며, 마감 시각에 요약이 루틴의 요약 채널에 게시됨
- **AI 어시스턴트** — `@bot ask <질문>`이 claude/codex 사이드카(HTTP+SSE)로 에이전트 턴을 실행하고 스레드로 응답. 같은 스레드에서 재멘션하면 세션이 이어짐
- **CVE 감시** — 등록된 토픽을 외부 소스(NVD, GitHub Releases)에서 주기적으로 수집하고, AI 레인이 이벤트당 정확히 한 번 요약한 뒤, 구독자에게 즉시 DM 또는 일일 다이제스트로 전달. 사용자는 `/subscribe`·`/unsubscribe`로 토픽을 구독·해지하고 `/subscriptions`로 목록을, `/latest [topic-key]`로 최근 요약을 조회하며 네 명령 모두 DM으로 답함. 관리자는 `@bot cve ...`로 채팅에서 토픽을 관리. `slack.app.cve.enabled=true`일 때만 동작
- **MCP 서버** — 역할로 게이트되는 Model Context Protocol 엔드포인트(`/mcp`, streamable HTTP)로 읽기 전용 도메인 도구 7종(`get_status`, `list_meetings`, `list_roles`, `list_standups`, `list_cve_subscriptions`, `cve_latest`, `get_ai_usage`)을 AI 에이전트에 노출. 턴 단위로 인증하고 `mcp_tool_call_history`에 감사 기록
- **역할 기반 접근 제어** — 사용자별 역할(`user` → `ai_user` → `developer` → `admin`, `user_command_role` 테이블)로 명령을 게이트. 관리자는 `@bot grant / revoke / roles`로 채팅에서 직접 권한을 관리하고, 부트스트랩 관리자는 설정으로 지정
- **이벤트 기반 아키텍처** — Kafka 비동기 처리 + **트랜잭셔널 아웃박스** + **Debezium 기반 CDC 릴레이**로 내구성 있는 at-least-once 전달(멱등 소비자; 정확한 보장은 `docs/wiki/events-and-outbox.md` 참고)
- **턴 감사 기록** — 모든 AI 턴을 `agent_turn_history`에 영속화(토큰 사용량·소요 시간·결과)하여 비용 추적과 디버깅에 활용. `@bot usage [days]`와 MCP 도구 `get_ai_usage`로 턴·토큰·상위 요청자·MCP 도구 호출 요약을 조회
- **운영 헬스 체크** — Spring Boot Actuator 엔드포인트와, 아웃박스 지연·정체 행을 보고하는 커스텀 헬스 인디케이터
- **보안** — 서블릿 필터를 통한 슬랙 요청 서명 검증 및 재시도 중복 제거

## 기술 스택
- **언어 / 런타임**: Kotlin `2.4.10`, Java `25` (Adoptium 툴체인)
- **프레임워크**: Spring Boot `4.1.1` (Jetty 기반 Web, Actuator, AOP/AspectJ, Data JPA)
- **빌드**: Gradle `9.8.0` (멀티 모듈), ktlint `14.2.0`
- **메시징**: Apache Kafka (`spring-boot-starter-kafka`) + Debezium CDC 아웃박스 릴레이
- **영속성**: JPA / Hibernate — MariaDB(런타임, 모든 프로파일), H2(테스트 전용)
- **슬랙**: Slack Java SDK `1.52.0` (`slack-api-client`, `slack-api-model`, `slack-app-backend`)
- **AI / MCP**: Spring AI `2.0.1` (BOM, MCP 서버 모듈 `spring-ai-autoconfigure-mcp-server-webmvc`, `spring-ai-mcp`, `spring-ai-mcp-annotations`, `mcp-spring-webmvc`)
- **직렬화**: Jackson 3 (`tools.jackson`, BOM `3.2.3`)
- **로깅**: kotlin-logging `8.0.4`
- **테스트**: Kotest `6.2.5` (`BehaviorSpec`) + MockK `1.14.11`, `EmbeddedKafka`·H2 기반의 자족적 통합 테스트

## 아키텍처
CodeCompanion은 DDD 기반의 3개 모듈 계층 구조를 따릅니다. 의존성은 **application → infrastructure → domain** (및 **application → domain**) 방향으로만 흐릅니다. `domain` 모듈은 프레임워크에 의존하지 않는 순수 Kotlin입니다 — Spring·Jakarta·JPA 없음. 또한 **전송 계층에 비의존적(transport-agnostic)**입니다: `domain/command`에는 Slack 타입이 전혀 없습니다. 인바운드 요청은 중립 모델(`InboundCommand` / `InboundInteraction`)로 정규화되고, 아웃바운드 결과는 전송 중립적인 `OutboundMessage`로 표현되므로, 도메인을 건드리지 않고도 향후 어댑터(예: Discord)를 추가할 수 있습니다.

```
CodeCompanion/
├── domain/                      # 프레임워크·전송 타입 없는 순수 Kotlin 코어
│   ├── command/                 # 전송 중립 명령 코어
│   │   ├── authorization/       #   역할·권한 모델 (UserRole, CommandPermission)
│   │   ├── inbound/             #   중립 인바운드 모델 (InboundCommand, InboundInteraction)
│   │   ├── outbound/            #   중립 아웃바운드 메시지 및 스테이저 계약
│   │   ├── intent/              #   CommandIntent 계층 (미팅, 노티스, 에이전트, 역할, ...)
│   │   ├── entity/              #   명령 애그리거트: 컨텍스트, 파서, 이벤트, 슬래시 명령
│   │   └── dto/                 #   모달 및 응답 DTO
│   ├── meet/                    # 미팅 애그리거트 및 DTO
│   ├── standup/                 # 스탠드업 루틴·세션·응답
│   └── common/                  # 공용 값 객체 및 검증 DSL
│
├── application/                 # Spring Boot 부트스트랩 + 유스케이스 오케스트레이션
│   ├── controllers/             # 슬랙 이벤트 / 슬래시 / 상호작용 엔드포인트
│   ├── service/                 # 유스케이스: agent, command(역할), cve, interaction, meeting, mention, ops, relay, standup
│   │   └── cve/                 #   수집기, AI 요약 워커, 구독, 조회, 알림, 운영
│   ├── socket/                  # 슬랙 Socket Mode 커넥터
│   ├── security/                # 슬랙 서명 검증 및 재시도 중복 제거 필터
│   │   └── mcp/                 #   MCP 엔드포인트용 턴 단위 스코프 토큰
│   ├── mcp/                     # 역할 게이트 MCP 도구 (McpToolGate, DomainReadTools)
│   ├── health/                  # 아웃박스 헬스 인디케이터
│   └── configurations/          # 빈, 조건부 설정, 비동기/Kafka 와이어링
│
└── infrastructure/              # 구체 어댑터 — 모든 슬랙 결합은 여기에 격리
    ├── impl/
    │   ├── agent/               # AI 사이드카 클라이언트 (HTTP + SSE)
    │   ├── command/             # 슬랙 어댑터: 인바운드 매핑, 인텐트 해석, 아웃바운드 렌더링·스테이징
    │   │   ├── slack/           #   슬랙 wire DTO 및 인바운드 매퍼 (payload → InboundCommand)
    │   │   └── event/           #   슬랙 이벤트 페이로드, 디스패치 이벤트, 메시지 디스패처
    │   ├── cve/                 # CVE 피드 어댑터 (NVD, GitHub Releases)
    │   └── retry/               # 재시도 지원
    ├── repository/              # JPA 리포지토리: agent, authorization, cve, mcp, meeting, outbox, standup
    └── templates/               # 슬랙 메시지 및 모달 빌더
```

각 모듈은 자체 `src/testFixtures/kotlin/` 팩토리(Gradle `java-test-fixtures`)를 제공하며, `testFixtures(project(":domain"))` 형태로 모듈 간 재사용됩니다.

설계 철학, 계층 규칙, 아웃박스/이벤트 모델, 코딩 스타일, 기술 결정 기록은 프로젝트 위키
[`docs/wiki/index.md`](docs/wiki/index.md)에 있습니다. 디렉터리별 작업 지침(AI 에이전트용)은 각 `AGENTS.md`입니다.

## 봇 명령어와 역할
모든 명령은 사용자별 역할로 게이트됩니다. 역할은 누적 구조입니다 — `user` ⊂ `ai_user` ⊂ `developer` ⊂ `admin`, 상위 역할은 하위 역할의 모든 권한을 포함합니다.

| 명령 | 진입점 | 최소 역할 |
|------|--------|-----------|
| `/meetup`, `/meetup list [today\|tomorrow\|week\|month]` | 슬래시 명령 | `user` |
| `/standup setup` — 스탠드업 루틴 생성(모달) | 슬래시 명령 | `user` |
| `/standup list` — 이 채널의 활성 스탠드업 루틴 목록 | 슬래시 명령 | `user` |
| `/standup stop <routine-name>` — 루틴 생성자 또는 관리자만 중지 가능 | 슬래시 명령 | `user` |
| `/subscribe` — 구독할 CVE 토픽 선택(모달, 결과는 DM) | 슬래시 명령 | `user` |
| `/unsubscribe` — 해지할 CVE 토픽 선택(모달, 결과는 DM) | 슬래시 명령 | `user` |
| `/subscriptions` — 내 CVE 토픽 구독 목록(DM) | 슬래시 명령 | `user` |
| `/latest [topic-key]` — 최근 CVE 요약, 토픽 지정 가능(DM) | 슬래시 명령 | `user` |
| `@bot help` | 멘션 | `user` |
| `@bot ask <질문>` — 명령이 아닌 자유 텍스트 멘션도 `ask`로 처리 | 멘션 | `ai_user` |
| `@bot status` | 멘션 | `developer` |
| `@bot usage [days]` — 최근 N일(기본 7, 최대 90)의 AI 턴·토큰·MCP 도구 호출 | 멘션 | `developer` |
| `@bot notice @user1 @user2 <메시지>` | 멘션 | `developer` |
| `@bot grant @user <user\|ai_user\|developer\|admin>` | 멘션 | `admin` |
| `@bot revoke @user` | 멘션 | `admin` |
| `@bot roles` | 멘션 | `admin` |
| `@bot cve topics` | 멘션 | `admin` |
| `@bot cve topic activate\|deactivate <topic-key>` | 멘션 | `admin` |
| `@bot cve retry all\|<event-id>` | 멘션 | `admin` |

멘션 명령(`help`, `status`, `usage`, 역할·CVE 관리)의 답장은 ephemeral 메시지로, 명령을 보낸 사람에게만 보입니다.

슬래시 명령은 이름이 아니라 URL로 앱에 도달합니다. `/meetup`은 `/api/slash/meet`로, 나머지 명령은
`/api/slash/<명령>`으로 보내며, [`docs/slack-app-manifest.yaml`](docs/slack-app-manifest.yaml)에 모두 선언되어 있습니다.

역할은 다음 순서로 결정됩니다: `slack.app.authorization.bootstrap-admins`(설정으로 관리되는 관리자, 채팅에서 변경 불가) → `user_command_role` 테이블 행 → 기본값 `user`. 관리자는 `grant` / `revoke` / `roles`로 채팅에서 직접 권한을 관리합니다. 상호작용 컴포넌트와 슬래시 명령은 `user` 수준이므로 모달은 모든 사용자에게 열려 있습니다.

## 시작하기
1. **저장소 복제**
   ```bash
   git clone https://github.com/TrulyNotMalware/CodeCompanion.git
   cd CodeCompanion
   ```

2. **슬랙 앱 생성과 자격 증명 구성**
   - [`docs/slack-app-manifest.yaml`](docs/slack-app-manifest.yaml)의 `https://<your-host>` 플레이스홀더를 앱의 공개 기본 URL로 교체
   - [api.slack.com/apps](https://api.slack.com/apps)에서 *Create New App → From an app manifest*를 고르고 수정한 매니페스트를 붙여 넣은 뒤 워크스페이스에 설치. 슬랙은 Events URL을 실행 중인 앱으로 검증하므로 5단계를 마친 뒤에야 통과함
   - Bot User OAuth Token은 `slack.app.api.token`에, Signing Secret은 `slack.app.api.signing-secret`에 주입
   - `local` 프로파일은 Request URL 대신 Socket Mode를 쓰는 별도의 개발용 앱을 사용: 같은 매니페스트로 Socket Mode를 켠 앱을 만들고, `connections:write` 스코프의 app-level token을 `slack.app.api.app-token`에 주입(매니페스트 머리 주석 참고)

3. **개발 환경 설정**
   ```bash
   ./gradle-config/apply.sh                          # 공용 Gradle 속성 적용
   ./gradlew addKtlintCheckGitPreCommitHook          # ktlint pre-commit 훅 설치
   ```

4. **빌드**
   ```bash
   ./gradlew :application:build
   ```

5. **실행**
   ```bash
   ./run JAR_FILE_PATH
   ```

6. **확인**
   - 슬랙 채널에 봇 초대
   - 슬래시 명령어(예: `/meetup`)나 멘션으로 테스트

7. **(선택) AI 어시스턴트 활성화**
   - [agent-sidecar](https://github.com/TrulyNotMalware/agent-sidecar)를 앱 옆에서 실행하고 `PROVIDER=claude|codex`로 백엔드 선택 (구독 OAuth 토큰 또는 API 키 — 인증 옵션은 해당 저장소 README 참고)
   - 앱 연결: `slack.app.agent.sidecar.base-url`(기본값 `http://127.0.0.1:7300`)과 `slack.app.agent.sidecar.bearer-secret`(사이드카의 `BEARER_SECRET`과 일치해야 함)
   - 접근 권한 부여: 본인 슬랙 사용자 ID를 `slack.app.authorization.bootstrap-admins`에 추가한 뒤 `@bot grant @teammate ai_user`
   - 컨테이너 레시피(2026-10-07 `codex` 공급자로 확인, `claude`는 `CLAUDE_CODE_OAUTH_TOKEN` 또는 `ANTHROPIC_API_KEY`가 추가로 필요):
     ```bash
     docker build -t agent-sidecar <agent-sidecar 체크아웃 경로>
     docker run -d -p 127.0.0.1:7300:7300 -v sidecar-state:/var/lib/claude-sidecar -v "$PWD/CLAUDE.md":/workspace/CLAUDE.md:ro \
       -e PROVIDER=codex -e BEARER_SECRET=<slack.app.agent.sidecar.bearer-secret과 같은 값> \
       -e MCP_SERVER_URL=http://host.docker.internal:9000/mcp -e MCP_SERVER_NAME=domain-tools agent-sidecar
     ```
   - 에이전트가 MCP 도구를 호출하게 하려면 앱을 `--slack.app.mcp.enabled=true --spring.ai.mcp.server.enabled=true --spring.ai.mcp.server.protocol=STREAMABLE`로 띄우고 `slack.app.mcp.signing-secret`을 설정. 볼륨 소유권, codex 자격 증명, 타임아웃을 포함한 전체 레시피는 [`docs/wiki/dev-environment.md`](docs/wiki/dev-environment.md) 참고

### 테스트
```bash
./gradlew build                 # 전체 파이프라인: 컴파일 + ktlint + 테스트
./gradlew :domain:test          # 반복 작업 시 모듈 단위 테스트
./gradlew :application:test
./gradlew :infrastructure:test
```
통합 테스트는 `EmbeddedKafka`와 H2로 동작하므로 외부 인프라가 필요 없습니다.

## License
MIT License
