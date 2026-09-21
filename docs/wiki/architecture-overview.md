# 아키텍처 개요

_type: architecture · updated: 2026-09-21_

> Slack 요청을 `domain` / `infrastructure` / `application` 세 모듈로 나눠 처리하는 단일 Spring Boot 앱이며,
> 의존성은 `application → infrastructure → domain`(그리고 `application → domain`) 한 방향으로만 흐른다.

## 세 모듈의 책임

| 모듈 | 책임 | 산출물 | 의존 |
|------|------|--------|------|
| `domain` | 순수 Kotlin. 명령 애그리거트(`command`), 미팅·스탠드업 엔티티, 중립 인바운드/아웃바운드 모델, `validate {}` DSL, 에러 계약 | plain `jar` | 없음 — `domain/build.gradle.kts`의 `dependencies {}`가 비어 있다 |
| `infrastructure` | **모든 Slack 결합**(wire DTO, 인바운드 매퍼, 인텐트 해석, 렌더러, 템플릿), JPA 리포지토리와 스키마, Kafka 퍼블리셔, AI 사이드카 클라이언트, CVE 소스 어댑터, 재시도 | plain `jar` | `domain` |
| `application` | Spring Boot 부트스트랩, HTTP/Socket Mode 진입점, 서명 검증 필터, 유스케이스 서비스, 스케줄러, 아웃박스 릴레이, MCP 서버, 빈 배선 | `bootJar` (배포 단위) | `domain`, `infrastructure` |

세 모듈이 하나의 프로세스로 뜬다. 계층 분리는 배포 단위가 아니라 **컴파일 단위**로 강제된다 — 왜 이렇게
나눴는지는 [ddd-layering.md](ddd-layering.md)에 있다.

## 요청이 흐르는 길

```
Slack ──HTTP(prod)──▶ SlackEventController / SlashCommandController ─┐
Slack ──WebSocket(local)──▶ SocketModeReceiver ──────────────────────┤ 같은 서비스 인터페이스
                                                                     ▼
              [application] AppMentionEventHandler · InteractionHandler · *SlashService
                                                                     ▼
              [infrastructure] Slack payload → InboundCommand / InboundInteraction (정규화)
                                                                     ▼
              [domain] Command → CommandContext 파싱·실행 → CommandIntent 누적 → CommandOutput
                                                                     ▼
              [infrastructure] intent → OutboundMessage → 아웃박스 행 (트랜잭션 BEFORE_COMMIT에 저장)
                                                                     ▼
              [application] 릴레이(Debezium CDC → Kafka 컨슈머, 또는 폴링 + claim)가 행을 집어 든다
                                                                     ▼
              [infrastructure] 전달 시점에 한 번 Slack 페이로드로 렌더 → Slack Web API 호출
```

- 진입점이 둘(HTTP, Socket Mode)이지만 호출하는 서비스 빈은 같다. 명령을 추가하면 **양쪽**에 배선해야
  로컬과 프로덕션이 갈라지지 않는다.
- 서명·타임스탬프 검증과 재시도 중복 제거는 `SlackRequestVerificationFilter`가 HTTP 경로에서만 수행한다.
- 도메인은 응답을 **보내지 않고** 의도(`CommandIntent`)만 낸다. 실제 전송은 아웃박스와 릴레이의 몫이다.
  상세는 [command-pipeline.md](command-pipeline.md)와 [events-and-outbox.md](events-and-outbox.md).

## 횡단 관심사 — 기존 파이프라인 위에 얹힌 것들

| 관심사 | 어디에 | 요점 |
|--------|--------|------|
| 역할 기반 권한 | `domain/command/authorization`, 멘션 파서 | `user ⊂ ai_user ⊂ developer ⊂ admin`, 설정의 bootstrap admin이 최우선 |
| 멱등성 | `application/common/IdempotencyCreator` | 인바운드 데이터 + 1초 창으로 키 파생, Slack 재전송을 한 키로 접는다 |
| 스케줄링 | `application/service/{standup,meeting,cve}` | 공유 락 없이 DB 행 CAS로 다중 인스턴스 안전 |
| AI 레인 | `application/service/agent`, `infrastructure/impl/agent`, `application/mcp` | 사이드카(HTTP+SSE) + 앱 내 MCP 서버, 턴 단위 스코프 토큰 |
| CVE 감시 | `application/service/cve`, `infrastructure/impl/cve` | 수집 → 1회 요약 → 아웃박스 DM/다이제스트 |
| 운영 가시성 | `application/health/OutboxHealthIndicator`, `@bot status` | 아웃박스 지연·정체 행 |

## 실행 형태

- 런타임: MariaDB(모든 프로파일), 릴레이는 Kafka + Debezium CDC(`local`/`dev`/`prod`) 또는 폴링(`slack-live`, Kafka
  없이 실기 워크스페이스 e2e). 선택은 `slack.app.mode.*` 키가 결정한다 — [dev-environment.md](dev-environment.md).
- 배포: 머지된 PR → 멀티 아키텍처 이미지 → Oracle OKE(ARM 노드) — [`../../.github/AGENTS.md`](../../.github/AGENTS.md).

## 지도

- 디렉터리 트리는 [`../../README.md`](../../README.md)의 Architecture 절.
- 디렉터리별 작업 지침은 `AGENTS.md` 계층: [`../../AGENTS.md`](../../AGENTS.md) → 모듈 → 패키지.

## 근거

- `README.md` — Architecture 절, Features
- `build.gradle.kts`, `domain/build.gradle.kts`, `infrastructure/build.gradle.kts`, `application/build.gradle.kts`
- `AGENTS.md`, `domain/AGENTS.md`, `infrastructure/AGENTS.md`, `application/AGENTS.md`
- `application/src/main/kotlin/dev/notypie/application/security/SlackRequestVerificationFilter.kt`

## 관련 페이지

- [ddd-layering.md](ddd-layering.md) — 왜 이렇게 나눴고 무엇을 금지하는가
- [command-pipeline.md](command-pipeline.md) — 인바운드 정규화부터 인텐트까지
- [events-and-outbox.md](events-and-outbox.md) — 아웃박스와 릴레이
- [dev-environment.md](dev-environment.md) — 프로파일과 실행 모드
- [history.md](history.md) — 이 구조에 이르기까지의 변천
