# DDD와 계층 원칙 — 도메인 순수성과 전송 중립성

_type: decision · updated: 2026-08-30_

> 도메인 순수성은 규율이 아니라 **빌드 제약과 가드 테스트**로 강제하고, 전송 중립성은 **각 단계가 그 자체로
> 이득을 낼 때만** 점진적으로 밀어붙인다.

## 두 문장 원칙

1. **순수 비즈니스 로직은 `domain`에 응집하고, 계층 경계는 편의 때문에 무너뜨리지 않는다.** 경계는 사람의
   주의력이 아니라 컴파일러와 테스트가 지킨다.
2. **경계를 지키는 비용이 이득을 넘어 보이면 규칙을 느슨하게 할 것이 아니라, 그 분리가 이 도메인에 필요한지
   되묻는다.** 이 프로젝트에서는 두 번째 전송(Discord)이 로드맵에 실재하므로 전송 중립성이 정당화된다 —
   그래서 지키되, "완전 이식성"을 한 번에 노리는 빅뱅은 하지 않는다 (`Refactor.md` 방향성).

## 무엇을 금지하는가

| 금지 대상 | 이유 | 강제 수단 |
|-----------|------|-----------|
| `domain`의 Spring · Jakarta · JPA 의존 | 도메인은 프레임워크 없이 컴파일·테스트되어야 한다 | `domain/build.gradle.kts`의 `dependencies {}`가 비어 있음. 루트 `subprojects`는 Kotlin reflect, kotlin-logging, Kotest, MockK만 주입 |
| `domain`의 Jackson · Gson | 직렬화는 전송 관심사 | 가드 테스트(import·어노테이션·**클래스패스** 검사) + Jackson을 모듈별로 선언 |
| `domain`의 Slack SDK 타입 · `slack.com` URL · SDK 페이로드 타입 | 전송 중립성 | 가드 테스트(정규식 소스 스캔) |
| `domain` 식별자에 Slack 어휘(`responseUrl`, `triggerId`) | 이름까지 중립이어야 두 번째 어댑터가 자연스럽다 | 가드 테스트. 주석에서 "was Slack trigger_id"라고 설명하는 것은 허용 |
| `meet` · `standup` · `common` → `command` import | 한때 있었던 패키지 순환의 재발 방지 (`command`는 이들에 의존해도 됨, 역방향 금지) | 가드 테스트 |
| 루트 `subprojects` 블록에 의존성 추가 | `domain`을 포함한 모든 모듈에 퍼진다 | 코드 리뷰 + 클래스패스 가드 |

### 가드 테스트가 왜 클래스패스까지 보는가

`domain/src/test/kotlin/dev/notypie/domain/architecture/DomainLayeringGuardTest.kt`는 네 가지를 검사한다:
순수 패키지의 `command` import, 소스의 전송/직렬화 결합, **테스트 런타임 클래스패스에 Jackson·Gson·Slack
클래스가 존재하는지**, Slack 어휘 식별자. 세 번째가 있는 이유는 실제로 겪은 일 때문이다 — 루트 빌드가
한때 Jackson을 모든 서브프로젝트에 주입해서, import가 0건인데도 `domain`이 Jackson에 대해 컴파일된 적이
있다. 소스 스캔은 의존 그래프를 보지 못하므로 `Class.forName`으로 직접 찔러 본다.

## 모델을 분리한다 — 엔티티와 스키마

- 도메인 엔티티(`Meeting`, `Routine`, `StandupSession`, …)는 JDK와 자체 `validate {}` DSL만 쓰고 불변식을
  `init`에서 검증한다. JPA `@Entity`는 `infrastructure/repository/<lane>/schema/`의 `*Schema` 클래스이며,
  `*RepositoryImpl`이 둘 사이를 매핑한다.
- "도메인 객체와 스키마가 사실상 같은데 변환 계층이 필요한가"라는 유혹은 시간이 지나며 스스로 답이 났다.
  스키마 쪽에만 자동 생성 PK, `idempotency_key`, `created_at`/`updated_at`, 연관관계 페치 전략 같은
  **영속성 관심사**가 계속 쌓였고, 도메인 모델에는 그것들이 없어야 한다. 변환 계층은 그 차이를 흡수하는
  자리다.
- 같은 이유로 리포지토리는 **3종 세트**다: `XxxRepository`(인터페이스 + DTO) / `JpaXxxRepository`(Spring Data
  쿼리) / `XxxRepositoryImpl`(매핑, `open class`, 변이 메서드에 `@Transactional`).

## 포트 소유권 — 도메인이 포트와 중립 모델을, 인프라가 어댑터를

- 아웃바운드: 도메인이 `OutboundMessage`(중립 표현 모델, `domain/command/outbound`)와 `OutboundMessageStager`,
  `EventPublisher`(`domain/command/entity/event`) 계약을 소유하고, `infrastructure/impl/command`의 Slack
  어댑터(`SlackOutboundStager`)가 구현한다. 미래의 Discord 어댑터는 같은 계약을 옆에 나란히 구현한다.
  스케줄러 주도 발송이 쓰는 `OutboundMessagePort.toRow(...)`는 도메인 포트가 아니라
  `infrastructure/repository/outbox`의 아웃박스 행 생성 헬퍼다.
- **staging이지 eager IO가 아니다.** 포트는 HTTP를 즉시 쏘지 않고 아웃박스에 올릴 행을 만든다. eager HTTP는
  `BEFORE_COMMIT` 아웃박스 저장을 우회해 전달 보장을 깨기 때문에 금지다.
- **RENDER와 DELIVER는 별개 단계다.** 렌더(중립 내용 → Slack 페이로드)는 전달 시점에 `SlackOutboundRenderer`가
  한 번만 수행한다. 유일한 예외는 모달 — `views.open`은 요청 스레드의 `trigger_id`(약 3초 만료)가 필요해서
  `SlackOutboundStager`가 동기적으로 렌더·스테이징한다. 두 번째 렌더 지점을 만들지 않는다.
- **비대칭 하나**: 리포지토리 인터페이스는 도메인이 아니라 `infrastructure/repository/<lane>/`에 있다.
  도메인은 영속성 계약의 존재조차 모른다. 의도적인 선택이지만 "영속성은 도메인의 관심사인가"는 아직 열린
  질문이다 — 결론이 나면 [decisions.md](decisions.md)의 해당 항목을 갱신한다.

## 응집성 — 명령 실행과 도메인 로직을 구분한다

- `Command` / `CommandContext`는 **"명령 실행" 도메인**이지 도메인 로직 전체의 대표가 아니다. 미팅·스탠드업의
  규칙은 각 엔티티에 두고, 컨텍스트는 파싱·라우팅·의도 발행에 그친다 (`Refactor.md` §5).
- 애그리거트는 자기 것만 수정하고 다른 애그리거트에 미치는 효과는 이벤트로 위임한다. 이벤트 리스너가 DB를
  쓰고 응답을 스테이징할 때는 `@Transactional`을 명시해 **역할 쓰기와 응답 아웃박스가 한 트랜잭션**이 되게
  한다 — 호출자 트랜잭션에 암묵적으로 기대다 응답이 유실될 뻔한 사례가 Phase 10에 있다.
- `@Transactional`은 Repository `Impl`에 건다. 스케줄러·비동기 경로처럼 앰비언트 트랜잭션이 없는 곳은
  `TransactionTemplate.runInTx`로 경계를 명시한다.

## 의도적으로 남긴 누수 — 드라이브바이로 고치지 말 것

| 항목 | 상태 | 이유 |
|------|------|------|
| `slackUserId` / `slackTeamId` | 유지 | Slack 전용 제품의 비즈니스 자연키. 가드하지 않음 |
| `CommandDetailType` 라우팅 enum | 유지 | 폼·모달 라우팅 토큰. `wireValue` seam은 Phase 6d에서 폐기하고 `.name`/`valueOf`(unknown은 fail-fast)로 통일 |
| 아웃박스 wire 포맷 | 해소(Phase 8b) | V11부터 중립 `OutboundEnvelope`(V2) + `transport` 컬럼을 저장한다. 두 번째 전송에 남은 일은 `Transport` 상수와 렌더러 등록뿐 |
| `ApprovalContents`가 `CommandDetailType` 소유 | 유지 | 표현 모델에 라우팅이 남는 지점. 완전 정리는 후속 |

"완전 transport-agnostic"은 과장이었다는 정직화(Phase 5)가 이 표의 출발점이다. 새 누수를 더하지 않는 것이
규칙이고, 기존 누수는 그것을 없앨 실제 필요(두 번째 전송)가 생길 때 정리한다.

## 판단 기준 요약

- 새 타입을 어디에 둘지 모르겠으면: Slack을 알면 `infrastructure`, Spring을 알면 `application`, 둘 다
  모르면 `domain`.
- 도메인에 무언가를 넣고 싶은데 가드가 막는다면 가드를 고치지 말고 위치를 다시 본다. 가드를 바꾸는 변경은
  그 자체가 설계 결정이므로 [decisions.md](decisions.md)에 남긴다.
- 이식성을 위한 리팩토링은 단계마다 "지금 이 단계만으로도 가독성·응집성이 좋아졌는가"를 통과해야 한다.

## 근거

- `domain/build.gradle.kts`, `build.gradle.kts`(`subprojects` 블록과 `io.spring.dependency-management` 부재 주석)
- `domain/src/test/kotlin/dev/notypie/domain/architecture/DomainLayeringGuardTest.kt`
- `infrastructure/build.gradle.kts`, `application/build.gradle.kts` — 모듈별 Jackson 선언 주석
- `infrastructure/AGENTS.md`(렌더 1회·모달 예외·아웃박스 계약), `domain/AGENTS.md`, `AGENTS.md`
- `Refactor.md`(로컬 전용 계획 문서) — 방향성, §1 목표 아키텍처, Phase 3 포트 소유권, Phase 5 정직화, Phase 10, §5 코딩 원칙
- `README.md` — Architecture 절

## 관련 페이지

- [architecture-overview.md](architecture-overview.md)
- [command-pipeline.md](command-pipeline.md) — 중립 인바운드/아웃바운드 모델의 실제 모습
- [events-and-outbox.md](events-and-outbox.md) — staging과 전달 보장
- [error-handling-and-validation.md](error-handling-and-validation.md) — `validate {}`와 에러 계약
- [decisions.md](decisions.md) — 결정 기록
- [history.md](history.md) — 이 원칙에 도달한 리팩토링 단계
