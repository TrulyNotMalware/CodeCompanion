# CodeCompanion

CodeCompanion is a Slack bot built with Kotlin and Spring Boot for side-project teams. It turns Slack slash commands, mentions, and interactive components into reliable, event-driven workflows — meeting orchestration, standups, and more — backed by a transactional outbox and Kafka so nothing is lost between Slack and the database.

## Features
- **Slack Event Handling** — Process mentions, messages, and interactive components (buttons, dropdowns, modals).
- **Slash Commands** — Execute custom `/` commands such as `/meetup` directly from Slack.
- **Meeting Orchestration** — Request, approve/decline, cancel, and list team meetings through interactive Slack modals, with host-only authorization enforced at the repository layer.
- **Standup Automation** — Schedule recurring standups, collect answers, and post summaries.
- **AI Assistant** — `@bot ask <question>` runs one agent turn against a claude/codex sidecar (HTTP+SSE) and replies in a thread; mentioning again in the thread continues the same session.
- **Role-Based Access Control** — Commands are gated by per-user roles (`user` → `ai_user` → `developer` → `admin`) stored in `user_command_role`; admins manage grants in-chat via `@bot grant / revoke / roles`, and bootstrap admins come from configuration.
- **Event-Driven Architecture** — Asynchronous processing over Kafka with a **transactional outbox** and **Debezium-driven CDC relay** for exactly-once-style, ordered delivery.
- **History Tracking** — Persist command/interaction history for auditing and replay.
- **Operational Health** — Spring Boot Actuator endpoints plus a custom outbox health indicator reporting pending lag and stuck rows.
- **Security** — Slack request signature verification and retry de-duplication via a servlet filter.

## Tech Stack
- **Language / Runtime**: Kotlin `2.4.0`, Java `25` (Adoptium toolchain)
- **Framework**: Spring Boot `4.1.0` (Web on Jetty, Actuator, AOP/AspectJ, Data JPA)
- **Build**: Gradle `9.5.1` (multi-module), ktlint `14.2.0`
- **Messaging**: Apache Kafka (`spring-boot-starter-kafka`) + Debezium CDC outbox relay
- **Persistence**: JPA / Hibernate — MariaDB (runtime), H2 (local & tests)
- **Slack**: Slack Java SDK `1.49.0` (`slack-api-client`, `slack-api-model`, `slack-app-backend`)
- **Serialization**: Jackson 3 (`tools.jackson`, BOM `3.2.0`)
- **Logging**: kotlin-logging `8.0.4`
- **Testing**: Kotest `6.2.0` (`BehaviorSpec`) + MockK `1.14.11`, with `EmbeddedKafka` and H2 for self-contained integration tests

## Architecture
CodeCompanion follows a DDD-inspired, three-module layering. Dependencies flow **application → infrastructure → domain** (and **application → domain**). The `domain` module is framework-free Kotlin — no Spring, no Jakarta, no JPA. It is also **transport-agnostic**: `domain/command` carries zero Slack types. Inbound requests are normalized into a neutral model (`InboundCommand` / `InboundInteraction`) and outbound results are expressed as transport-neutral `OutboundMessage`s, so a future adapter (e.g. Discord) can be added without touching the domain.

```
CodeCompanion/
├── domain/                      # Pure Kotlin core — no framework, no transport types
│   ├── command/                 # Transport-neutral command core — inbound/outbound models, intents, contexts, parsers
│   ├── meet/                    # Meeting aggregate & DTOs
│   ├── standup/                 # Standup routines, sessions, answers
│   ├── history/                 # History entities & mappers
│   ├── user/                    # User aggregate
│   └── common/                  # Shared value objects & validation DSL
│
├── application/                 # Spring Boot bootstrap + use-case orchestration
│   ├── controllers/             # Slack event / slash / interaction endpoints
│   ├── service/                 # Use cases: meeting, standup, interaction, relay, ops, ...
│   ├── security/                # Slack signature verification & retry dedup filter
│   ├── health/                  # Outbox health indicator
│   └── configurations/          # Beans, conditions, async/Kafka wiring
│
└── infrastructure/              # Concrete adapters — all Slack coupling lives here
    ├── impl/command/            # Slack adapter: request parsing, intent resolving, outbound staging
    │   ├── slack/               #   Slack wire DTOs & inbound mappers (payload → InboundCommand)
    │   └── event/               #   Slack event payloads, dispatch events, message dispatcher
    ├── repository/              # JPA repositories: meeting, outbox, standup, history, user
    ├── templates/               # Slack message & modal builders
    └── retry/                   # Retry support
```

Each module ships its own `src/testFixtures/kotlin/` factories (Gradle `java-test-fixtures`), reused cross-module via `testFixtures(project(":domain"))`.

## Getting Started
1. **Clone the repository**
   ```bash
   git clone https://github.com/TrulyNotMalware/CodeCompanion.git
   cd CodeCompanion
   ```

2. **Configure Slack API credentials**
   - Create a Slack App at [api.slack.com/apps](https://api.slack.com/apps)
   - Set up the required permissions and event subscriptions
   - Provide your credentials via the `slack.app.api.*` properties (token, signing secret)

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
- **미팅 오케스트레이션** — 상호작용 모달을 통한 미팅 요청·수락/거절·취소·조회. 리포지토리 계층에서 호스트 전용 권한을 원자적으로 강제
- **스탠드업 자동화** — 반복 스탠드업 스케줄링, 응답 수집, 요약 게시
- **AI 어시스턴트** — `@bot ask <질문>`이 claude/codex 사이드카(HTTP+SSE)로 에이전트 턴을 실행하고 스레드로 응답. 같은 스레드에서 재멘션하면 세션이 이어짐
- **역할 기반 접근 제어** — 사용자별 역할(`user` → `ai_user` → `developer` → `admin`, `user_command_role` 테이블)로 명령을 게이트. 관리자는 `@bot grant / revoke / roles`로 채팅에서 직접 권한을 관리하고, 부트스트랩 관리자는 설정으로 지정
- **이벤트 기반 아키텍처** — Kafka 비동기 처리 + **트랜잭셔널 아웃박스** + **Debezium 기반 CDC 릴레이**로 순서 보장 전달
- **히스토리 추적** — 명령/상호작용 이력 영속화(감사·재처리용)
- **운영 헬스 체크** — Spring Boot Actuator 엔드포인트와, 아웃박스 지연·정체 행을 보고하는 커스텀 헬스 인디케이터
- **보안** — 서블릿 필터를 통한 슬랙 요청 서명 검증 및 재시도 중복 제거

## 기술 스택
- **언어 / 런타임**: Kotlin `2.4.0`, Java `25` (Adoptium 툴체인)
- **프레임워크**: Spring Boot `4.1.0` (Jetty 기반 Web, Actuator, AOP/AspectJ, Data JPA)
- **빌드**: Gradle `9.5.1` (멀티 모듈), ktlint `14.2.0`
- **메시징**: Apache Kafka (`spring-boot-starter-kafka`) + Debezium CDC 아웃박스 릴레이
- **영속성**: JPA / Hibernate — MariaDB(런타임), H2(로컬·테스트)
- **슬랙**: Slack Java SDK `1.49.0` (`slack-api-client`, `slack-api-model`, `slack-app-backend`)
- **직렬화**: Jackson 3 (`tools.jackson`, BOM `3.2.0`)
- **로깅**: kotlin-logging `8.0.4`
- **테스트**: Kotest `6.2.0` (`BehaviorSpec`) + MockK `1.14.11`, `EmbeddedKafka`·H2 기반의 자족적 통합 테스트

## 아키텍처
CodeCompanion은 DDD 기반의 3개 모듈 계층 구조를 따릅니다. 의존성은 **application → infrastructure → domain** (및 **application → domain**) 방향으로만 흐릅니다. `domain` 모듈은 프레임워크에 의존하지 않는 순수 Kotlin입니다 — Spring·Jakarta·JPA 없음. 또한 **전송 계층에 비의존적(transport-agnostic)**입니다: `domain/command`에는 Slack 타입이 전혀 없습니다. 인바운드 요청은 중립 모델(`InboundCommand` / `InboundInteraction`)로 정규화되고, 아웃바운드 결과는 전송 중립적인 `OutboundMessage`로 표현되므로, 도메인을 건드리지 않고도 향후 어댑터(예: Discord)를 추가할 수 있습니다.

```
CodeCompanion/
├── domain/                      # 프레임워크·전송 타입 없는 순수 Kotlin 코어
│   ├── command/                 # 전송 중립 명령 코어 — 인바운드/아웃바운드 모델, 인텐트, 컨텍스트, 파서
│   ├── meet/                    # 미팅 애그리거트 및 DTO
│   ├── standup/                 # 스탠드업 루틴·세션·응답
│   ├── history/                 # 히스토리 엔티티 및 매퍼
│   ├── user/                    # 사용자 애그리거트
│   └── common/                  # 공용 값 객체 및 검증 DSL
│
├── application/                 # Spring Boot 부트스트랩 + 유스케이스 오케스트레이션
│   ├── controllers/             # 슬랙 이벤트 / 슬래시 / 상호작용 엔드포인트
│   ├── service/                 # 유스케이스: meeting, standup, interaction, relay, ops, ...
│   ├── security/                # 슬랙 서명 검증 및 재시도 중복 제거 필터
│   ├── health/                  # 아웃박스 헬스 인디케이터
│   └── configurations/          # 빈, 조건부 설정, 비동기/Kafka 와이어링
│
└── infrastructure/              # 구체 어댑터 — 모든 슬랙 결합은 여기에 격리
    ├── impl/command/            # 슬랙 어댑터: 요청 파싱, 인텐트 해석, 아웃바운드 스테이징
    │   ├── slack/               #   슬랙 wire DTO 및 인바운드 매퍼 (payload → InboundCommand)
    │   └── event/               #   슬랙 이벤트 페이로드, 디스패치 이벤트, 메시지 디스패처
    ├── repository/              # JPA 리포지토리: meeting, outbox, standup, history, user
    ├── templates/               # 슬랙 메시지 및 모달 빌더
    └── retry/                   # 재시도 지원
```

각 모듈은 자체 `src/testFixtures/kotlin/` 팩토리(Gradle `java-test-fixtures`)를 제공하며, `testFixtures(project(":domain"))` 형태로 모듈 간 재사용됩니다.

## 시작하기
1. **저장소 복제**
   ```bash
   git clone https://github.com/TrulyNotMalware/CodeCompanion.git
   cd CodeCompanion
   ```

2. **Slack API 자격 증명 구성**
   - [api.slack.com/apps](https://api.slack.com/apps)에서 슬랙 앱 생성
   - 필요한 권한 및 이벤트 구독 설정
   - `slack.app.api.*` 속성(토큰, 서명 시크릿)으로 자격 증명 주입

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
