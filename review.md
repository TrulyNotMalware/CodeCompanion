# CodeCompanion 코드 리뷰

> **작성일**: 2026-09-22
> **대상**: `main` @ `90c747a` (Agents markdown update #12)
> **범위**: 전체 레포 — `domain` / `infrastructure` / `application` 3개 모듈, 빌드·CI·설정·운영 스크립트·문서
> **규모**: Kotlin 450파일 / 46,267줄 (main 20,458줄)
> **스택**: Kotlin 2.4.10 · Spring Boot 4.1.1 · Java 25 · Gradle 9.7.1 · Kotest 6.2.5 · Slack SDK 1.51.0 · Jackson 3.2.2 · Spring AI 2.0.1

---

## 목차

1. [리뷰 방법](#1-리뷰-방법)
2. [요약](#2-요약)
3. [Critical](#3-critical)
4. [High](#4-high)
5. [Medium](#5-medium)
6. [구조 · 리팩토링](#6-구조--리팩토링)
7. [테스트 커버리지](#7-테스트-커버리지)
8. [검증했고 문제 없던 것](#8-검증했고-문제-없던-것)
9. [정정 사항](#9-정정-사항)
10. [Codex 독립 리뷰](#10-codex-독립-리뷰)
11. [실행 우선순위](#11-실행-우선순위) — **12장으로 대체됨**
12. [교차 검증 종합 · 최종 우선순위 (2026-09-22)](#12-교차-검증-종합--최종-우선순위-2026-09-22)
13. [2차 교차 검증 — 브랜치 반영 결과 재검수 (2026-09-24)](#13-2차-교차-검증--브랜치-반영-결과-재검수-2026-09-24)
14. [3차 교차 검증 — 커밋된 브랜치 전수 재검수 (2026-09-28)](#14-3차-교차-검증--커밋된-브랜치-전수-재검수-2026-09-28)
15. [14장 반영 — 수정 브랜치 `feature/review-round3-fixes` (2026-09-30 ~ 10-02)](#15-14장-반영--수정-브랜치-featurereview-round3-fixes-2026-09-30--10-02)
- [부록 A: 재현용 확인 명령](#부록-재현용-확인-명령)
- [부록 B: 레인별 기여 요약](#부록-b-레인별-기여-요약)

---

## 1. 리뷰 방법

### 1.1 구성

4개 독립 리뷰 레인을 병렬로 돌린 뒤 교차 검증했습니다.

| 레인 | 범위 | 산출 |
|---|---|---|
| 메인 (직접 정독) | 보안·릴레이·설정·컨트롤러·CI | Critical/High 다수 |
| `domain` 전담 에이전트 | `domain/src/main` 96파일 + 테스트 | Critical 2, High 8, Medium 8, Low 4 |
| `infrastructure` 전담 에이전트 | `infrastructure/src` 전체 | Critical 4, High 10, Medium 8, Low 2 + 분해안 2건 |
| 빌드/CI/테스트/문서 에이전트 | 빌드·워크플로·스크립트·docs | Gradle init-script 실측 포함 |
| **Codex (독립, 앵커링 없음)** | 핵심 25파일 | 15건 — 신규 4건 + 반론 2건 (10장) |

**에이전트가 보고한 주요 주장은 전부 제가 직접 파일을 열어 재검증했습니다.** 검증 과정에서 사실이 아니었던 초기 가설은 9장에 명시했습니다.

### 1.2 기준 커밋 확인

리뷰 도중 `feature/agents-md`(`ea6900d`) → `main`(`90c747a`) 전환이 있었습니다. 차이를 확인한 결과:

```
$ git diff --stat ea6900d HEAD -- domain/src application/src infrastructure/src build.gradle.kts .github/workflows
 .github/workflows/*                                  (액션 메이저 버전만 상승)
 application/src/main/resources/application-local.yaml | 6 ----
 build.gradle.kts                                      | 25 ++++++-----
```

**Kotlin 소스는 한 줄도 변경되지 않았습니다.** 따라서 모든 코드 findings가 `main`에 그대로 유효합니다. 변경된 3곳은 재검증하여 아래에 반영했습니다(9.6 참조).

### 1.3 이 코드베이스의 강점

지적 목록을 읽기 전의 기준선입니다. 나쁜 코드가 아닙니다.

- **스케줄러 동시성 설계가 정석입니다.** 분산 락 대신 per-row claim-token CAS를 일관되게 씁니다 (3.1 표). `// claim() must keep REQUIRED propagation, or a save failure can't roll it back — leaking a false-delivered row.`(`CveNotificationDispatcher.kt:24`) 같은 주석이 이유까지 남깁니다.
- **Outbox 저장이 원자적입니다.** `SlackMessageRelayServiceImpl.kt:89-90`의 `@TransactionalEventListener(BEFORE_COMMIT)`는 이 패턴의 교과서적 구현입니다.
- **도메인 레이어가 실제로 깨끗합니다.** `domain/src/main`에 Spring·Jakarta·infra import **0건**. `!!`·`lateinit`·명시적 캐스트도 **0건**이며 `EnvelopeCastGuardTest`가 baseline=0으로 강제합니다.
- **MCP 인증이 이중 방어입니다.** 필터(`McpTurnTokenFilter`)와 `contextExtractor`(`McpServerConfiguration.kt:91-102`)가 독립 검증하고, 토큰 부재 시 `McpToolGate.kt:29-31`이 다시 막습니다.
- **비활성화된 테스트 0건, `Thread.sleep` 0건.**
- **`EnvelopeCastGuardTest`의 shrinking-baseline 패턴**은 다른 가드 테스트가 본받을 만한 모범입니다.

---

## 2. 요약

| 심각도 | 건수 | 성격 |
|---|---:|---|
| Critical | 8 | 배포 게이트 부재, 공유 상태 레이스, 런타임 예외, 설정 플레이스홀더, 데이터 절단, 빌드 설정 무효화, k8s probe 부재, 검증 DSL 오동작 |
| High | 21 | 메시지 유실·중복, 예외 은폐, 사용자 노출 버그, 인덱스 부재, 운영 스크립트 파손, 클래스패스 누출 |
| Medium | 27 | 트랜잭션 경계, 보안 하드닝, JPA 위생, 설정 무효화, 문서 드리프트, 데드 코드 |
| 구조 | 7 | God-class 2건(753L·667L), stringly-typed 뷰, 중복 패턴 |

4개 레인이 **독립적으로 같은 결론에 도달한 항목**(C1 CI 게이트, C2 RetryService 레이스, C5 JOIN FETCH 절단, C3 `TODO()`, H1 outbox claim, H5 빈 예외 핸들러)은 신뢰도가 특히 높습니다.

### 지금 당장 고칠 8가지 (총 35줄 미만)

| # | 항목 | 수정량 |
|---|---|---|
| C1 | `main` PR에 테스트·린트 게이트 추가 (+ `-x test`, `required_contexts` 제거) | yaml ~15줄 |
| C3 | `KafkaErrorBroadcaster`의 `TODO()` 제거 | 1줄 |
| C4 | `"kafka:port"` → 환경변수 | 2줄 |
| H5 | `ControllerAdvice`의 빈 예외 핸들러 | 8줄 |
| H6 | `payload["channel_name"].toString()` → `"null"` 버그 | 2줄 |
| H7 | `notBlank`의 `"$field"` → `"must not be blank"` (Slack 노출) | 1줄 |
| X2 | `PollingMessageProcessor`에 `AppConfig` 주입 (설정 무효화) | 3줄 |
| H17 | `run:267-268` 함수 밖 `local` 제거 (기본 사용법 파손) | 2줄 |

---

## 3. Critical

### 3.1 먼저: 스케줄러 동시성은 **문제가 아닙니다**

아래 지적을 읽기 전에 알아야 할 사실입니다. `grep -rni "shedlock|@SchedulerLock"` 결과가 0건이라 처음에는 결함으로 보였으나, 이 코드베이스는 **분산 락 대신 DB 레벨 per-row CAS**를 의도적으로 씁니다.

| 경로 | claim 메커니즘 | 근거 |
|---|---|---|
| Standup dispatch | `claim_token` CAS + 단일 tx | `StandupSchedulingService.kt:139-168`, `JpaSessionDispatchRepository.kt:36,52,69` |
| Standup nudge | `claimNudge(sessionId)` CAS | `StandupSchedulingService.kt:214` |
| Standup session 생성 | unique 제약 위반 포착 후 재확인 | `StandupSchedulingService.kt:96-105` |
| Meeting reminder | `claim_token` CAS + 단일 tx | `MeetingReminderSchedulingService.kt:109-149` |
| Daily agenda | `claimAgenda(date)` CAS | `AgendaDispatchRepositoryImpl.kt:12` |
| CVE summary | `claim_token` CAS (`SUMMARIZING`) | `JpaCveEventRepository.kt:61,83,101,119` |
| CVE notification | `claim(eventId, userId)` + REQUIRED 전파 | `CveNotificationDispatcher.kt:104,110` |
| **Outbox polling** | **count 기반 추측 — 유일한 예외** | **`PollingMessageProcessor.kt:38-40`** → H1 |

분산 락보다 세밀하고 견고한 선택입니다. 락 서비스 의존성도 없습니다. **문제는 이 패턴에서 벗어난 단 한 곳**(H1)뿐입니다.

---

### C1. `main`으로 가는 PR에 테스트·린트 게이트가 없고, 배포 빌드조차 `-x test`

**근거**

`.github/workflows/lint.yaml:2-4`
```yaml
on:
  push:
    branches: ["feature/*", "feat/*", "features/*", "dependabot/**"]
```

`.github/workflows/simple_test_action.yaml:2-8`
```yaml
on:
  push:
    branches:
      - "feature/*"
      - "feat/*"
      - "features/*"
      - "dependabot/**"
```

두 워크플로 모두 **`pull_request` 트리거가 없습니다.** `main`에 대한 PR에서 도는 유일한 워크플로는 `security_check.yaml:2-6`(CodeQL / gitleaks / dependency-review)이며 **테스트를 실행하지 않습니다.**

그리고 결정타 — `.github/workflows/deploy_action.yaml:129`
```yaml
run: ./gradlew :application:build -x test -PjarName=${{ env.JAR_FILE_NAME }} --parallel
```

**배포 빌드가 명시적으로 테스트를 건너뜁니다.**

그리고 `.github/workflows/deploy_action.yaml:98`
```yaml
required_contexts: []
```
Deployment 생성 시 required status check를 **명시적으로 비웁니다.**

**실패 시나리오**

`hotfix/...`, `chore/...`, `refactor/...`, `fix/...` 브랜치로 PR을 올리면 → 테스트 0회, ktlint 0회 → 머지 → `deploy_action.yaml`이 `-x test`로 빌드, `required_contexts: []`로 아무 검사도 요구하지 않음 → **프로덕션**. 커밋 히스토리에 `refactor : Strip non-essential comments`, `chore : Drop Flyway keys`가 있으니 `feature/*` 외 접두사를 실제로 사용합니다.

게다가 **실행되지 않는 체크는 GitHub 브랜치 보호에서 required로 지정할 수 없어** 조직 정책으로도 막을 수 없습니다.

**심지어 패턴에 맞는 브랜치에서도 불완전합니다** — `simple_test_action.yaml:54-75`가 **변경된 모듈의 테스트만** 실행합니다(X3). 의존 방향이 application → infrastructure → domain인데 `domain`만 바꾸면 `:domain:test`만 돌아서, 도메인 계약 변경이 application을 깨뜨려도 잡히지 않습니다.

> **이 문제는 이미 인지되어 문서화돼 있습니다.** `docs/wiki/dev-environment.md:154`:
> > *"lint·test는 `feature/*` 계열 push에서만 돈다. `main`으로 가는 PR 자체는 `security_check`만 트리거하고 배포 빌드는 `-x test`다."*
>
> `docs/wiki/testing-guide.md:90`도 모듈 선택 실행을 정확히 기술합니다. 즉 **지식의 공백이 아니라 우선순위의 공백**입니다 — 그래서 이 항목을 1순위로 둡니다. 수정은 yaml 몇 줄인데 방치 비용이 가장 큽니다.

> 현재 작업 브랜치가 `feature/agents-md`였기에 우연히 패턴에 걸려 지금까지 드러나지 않았을 가능성이 높습니다.

**수정**

```yaml
# lint.yaml, simple_test_action.yaml 양쪽
on:
  push:
    branches: ["feature/*", "feat/*", "features/*", "dependabot/**"]
  pull_request:
    branches: ["main"]
```
`deploy_action.yaml:129`의 `-x test`를 제거하거나, 최소한 `build` job에 테스트 job을 `needs`로 걸어 통과를 전제로 만드세요. 그 뒤 브랜치 보호에 required status check로 등록해야 실효가 생깁니다.

---

### C2. `RetryService`가 싱글턴 `RetryTemplate`의 정책을 매 호출마다 덮어쓴다 — 동시성 레이스

**근거**

`infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt:23-38`
```kotlin
val policy = RetryPolicy.builder()
    .maxRetries(maxAttempts).delay(...).multiplier(...).includes(exceptions).build()
retryTemplate.retryPolicy = policy          // ← 공유 싱글턴의 가변 상태를 매 호출마다 변경
return try { retryTemplate.execute { action() } } catch (e: RetryException) { ... }
```

`infrastructure/src/main/kotlin/dev/notypie/configurations/RetryConfiguration.kt:15-17, 35-36`
```kotlin
@Bean
@ConditionalOnMissingBean(RetryTemplate::class)
fun retryTemplate(): RetryTemplate { ... }                 // 싱글턴 1개

@Bean
fun retryService(retryTemplate: RetryTemplate): RetryService = RetryService(retryTemplate = retryTemplate)
```

**실패 시나리오**

모든 프로파일에서 `spring.threads.virtual.enabled: true`입니다(`application-prod.yaml:9-11`). Slack 요청 스레드, 릴레이 executor, 스케줄러 풀(size 4)이 동시에 `RetryService.execute`를 호출합니다.

스레드 A가 `maxAttempts=5`(`SlackMessageRelayServiceImpl.kt:76`)로 정책을 세팅한 직후, 스레드 B가 `maxAttempts=3`(`:100`)으로 덮어씁니다. → **A가 B의 정책으로 실행**됩니다. Slack 재시도 횟수와 백오프가 비결정적으로 섞이고, `execute` 실행 중 정책이 바뀌는 상황도 가능합니다. 아웃박스 상태 갱신(5회)과 아웃박스 저장(3회)이 서로의 정책을 침범합니다.

**수정**

정책 조합별로 `RetryTemplate`을 캐시해 인스턴스마다 정책을 고정하세요.

```kotlin
class RetryService(private val defaultTemplate: RetryTemplate) {
    private val cache = ConcurrentHashMap<PolicyKey, RetryTemplate>()

    fun <T> execute(action: () -> T, recoveryCallBack: (() -> T)? = null, ...): T {
        val key = PolicyKey(maxAttempts, initialDelay, multiplier, maxDelay, jitter, exceptions)
        val template = cache.computeIfAbsent(key) {
            RetryTemplate().apply { retryPolicy = buildPolicy(it) }
        }
        return try { template.execute { action() } } catch (e: RetryException) { recoveryCallBack?.invoke() ?: throw e }
    }
}
```

부수: `RetryService.kt:41`의 `createFixedBackOffPolicy`는 호출되지 않는 데드 코드입니다.

---

### C3. `KafkaErrorBroadcaster`가 에러 처리 경로에서 예외를 던진다

**근거**

`infrastructure/src/main/kotlin/dev/notypie/exception/KafkaErrorBroadcaster.kt:8`
```kotlin
override fun broadcastError(message: String): Unit = TODO("Not yet implemented")
```

`application/src/main/kotlin/dev/notypie/application/configurations/ConsumerConfig.kt:72-73`
```kotlin
fun kafkaErrorBroadcaster(kafkaTemplate: KafkaTemplate<String, Any>): ErrorBroadcaster =
    KafkaErrorBroadcaster(kafkaTemplate = kafkaTemplate)
```

안전한 대체 구현 `StdoutErrorBroadcaster`는 `ConsumerConfig.kt:85-86`에서 `@ConditionalOnMissingBean(ErrorBroadcaster::class)`로 등록되므로, **이 빈이 존재하는 한 fallback이 비활성화**됩니다.

**실패 시나리오**

`broadcastError` 호출 시 Kotlin의 `TODO()`가 `NotImplementedError`를 던집니다. 이름 그대로 **이미 에러를 처리 중인 경로**에서 호출되므로, 원래 에러가 `NotImplementedError`로 덮여 진단이 불가능해집니다.

**수정**

```kotlin
override fun broadcastError(message: String) {
    kafkaTemplate.send(errorTopic, message)
        .whenComplete { _, ex -> if (ex != null) logger.error(ex) { "Error broadcast failed: $message" } }
}
```
에러 경로이므로 **절대 예외를 전파시키지 마세요.** 구현 전까지는 `ConsumerConfig.kt:72-73`의 빈 등록을 제거해 `StdoutErrorBroadcaster`가 선택되게 하는 것이 안전합니다.

---

### C4. prod / dev Kafka 부트스트랩이 플레이스홀더 리터럴

**근거**

`application/src/main/resources/application-prod.yaml:59-60`
```yaml
  kafka:
    bootstrap-servers: [ "kafka:port" ]
```
`application-dev.yaml:39-40` — 동일.

`"kafka:port"`는 환경변수 참조가 아니라 **문자열 리터럴**입니다. `application-local.yaml:37-40`만 실제 주소를 갖습니다.

**실패 시나리오**

prod의 outbox 릴레이 전략은 `application-prod.yaml:115`에서 `cdc`이고, 이는 Kafka 컨슈머(`DebeziumLogTailingProcessor`)를 씁니다. 부트스트랩 주소가 해석되지 않으면 **아웃박스 메시지가 Slack으로 전혀 나가지 않습니다.**

**수정**
```yaml
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
```
기본값 없이 선언해 미설정 시 기동이 실패하게 하고, `application/src/main/resources/k8s/configmap.yaml`에 키를 추가하세요.

---

### C5. JOIN FETCH 대상 alias를 WHERE에서 필터링 → `participants`가 잘린 채 로딩된다

**근거**

`infrastructure/src/main/kotlin/dev/notypie/repository/meeting/JpaMeetingRepository.kt:31-33`
```
JOIN FETCH m.participants p
WHERE m.publisherId = :userId
OR p.userId = :userId
```
`:42-43` — `findMeetingsByUserIdAndDateRange`도 동일 패턴.

**실패 시나리오**

Hibernate는 fetch join의 결과 행으로 컬렉션을 초기화합니다. WHERE가 그 alias를 제한하면 **조건을 만족하는 행만 채워진 채로** 영속성 컨텍스트에 캐시됩니다.

- 내가 **주최자**인 미팅 → `m.publisherId = :userId`가 참이라 모든 participant 행 생존 (정상)
- 내가 **참석자일 뿐인** 미팅 → `p.userId = :userId`인 행만 남음 → **참가자 목록에 나 혼자만** 들어옴

이 값이 `MeetingRepositoryImpl.kt:32-46` → `toMeetingDto()` → `ModalTemplateBuilder.kt:224-226`으로 흘러갑니다.
```kotlin
val totalCount = 1 + meeting.participants.size
val acceptedCount = 1 + meeting.participants.count { it.isAttending }
val participantsLine = "Participants: $acceptedCount/$totalCount"
```
→ **참석자 5명인 미팅이 `/meetup` 목록에서 "Participants: 2/2"로 표시**되고, 불참자 라인(`:233`)도 사라집니다.

기존 테스트가 못 잡는 이유: `JpaMeetingRepositoryTest.kt:99`가 `participants.any { it.userId == participantId } shouldBe true` — **존재 여부만** 보고 `size`를 검증하지 않습니다.

> 같은 파일의 `:19`, `:60`, `:141`은 alias 없는 `JOIN FETCH m.participants`라 **안전합니다.** 문제는 alias로 필터링하는 두 쿼리뿐입니다.

**수정**

필터 조건을 서브쿼리로 분리해 fetch join과 떼어냅니다.
```
SELECT DISTINCT m FROM meetings m
JOIN FETCH m.participants
WHERE (m.publisherId = :userId
       OR EXISTS (SELECT 1 FROM meeting_participants p2
                  WHERE p2.meeting = m AND p2.userId = :userId))
  AND m.startAt >= :startAt AND m.startAt < :endAt
ORDER BY m.startAt ASC
```
테스트에 `participants.size shouldBe <전체 수>` 단언을 반드시 추가하세요.

---

### C6. 루트 `kotlin {}` / `java { toolchain }` 설정이 **세 모듈 어디에도 적용되지 않는다** (실측)

**근거 — Gradle init-script로 직접 측정**

```
$ ./gradlew -I probe.gradle help -q

PROBE|:              |freeArgs=[-Xjsr305=strict, -Xannotation-default-target=param-property,
                                -java-parameters, -Xjvm-default=all]|jvmTarget=JVM_25
TOOLCHAIN|:          |lang=25|vendor=Eclipse Temurin

PROBE|:application   |freeArgs=[]|jvmTarget=JVM_25
TOOLCHAIN|:application|lang=null|vendor=any vendor
PROBE|:domain        |freeArgs=[]|jvmTarget=JVM_25
TOOLCHAIN|:domain     |lang=null|vendor=any vendor
PROBE|:infrastructure|freeArgs=[]|jvmTarget=JVM_25
TOOLCHAIN|:infrastructure|lang=null|vendor=any vendor
```

`build.gradle.kts`의 `java { toolchain { ... } }`와 `kotlin { jvmToolchain(25); compilerOptions { freeCompilerArgs.addAll(...) } }`는 **`allprojects`/`subprojects` 바깥의 최상위 블록**이라 **루트 프로젝트 확장만** 설정합니다. 루트에는 Kotlin 소스가 0개이므로 전부 dead configuration입니다.

**왜 중요한가**

1. **`-Xjsr305=strict`가 단 한 줄의 소스에도 적용되지 않습니다.** Slack SDK·Spring·Jackson의 JSR-305 `@Nullable`이 platform type으로 흘러들어오고, 팀이 믿고 있는 null 안전 계약이 컴파일 시점에 검증되지 않습니다. `AGENTS.md:89`는 *"Kotlin compiler args are strict: `-Xjsr305=strict`, …"* 라고 기술하지만 **사실이 아닙니다.**
2. **`-Xannotation-default-target=param-property`도 적용되지 않습니다.** `AGENTS.md:90-91`의 *"Annotation targets on constructor properties resolve to `param-property`, so `@field:` prefixes are usually unnecessary"* 역시 사실이 아니며, 실제로 코드 전반이 `@field:Id`·`@field:Column`처럼 **명시적 `@field:`로 보완**하고 있습니다.
3. **툴체인이 모듈에 안 걸립니다**(`lang=null`, `vendor=any vendor`). 모듈은 **Gradle 데몬이 도는 JDK**로 컴파일됩니다. 위 측정의 `jvmTarget=JVM_25`는 이 머신의 데몬이 JDK 25이기 때문일 뿐이고, 로컬에 JDK 21이 깔린 개발자는 `JVM_21`로 빌드합니다 → **재현 불가능한 빌드**. `vendor=any vendor`이므로 `README.md:20`의 "Adoptium toolchain" 고정도 무효입니다.

**수정**
```kotlin
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    // …
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.ADOPTIUM
        }
    }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjvm-default=all",
                                    "-Xannotation-default-target=param-property")
        }
    }
}
```
루트의 `java {}` / `kotlin {}` 블록은 삭제하세요.

> ⚠️ **별도 PR로 분리하세요.** `-Xjsr305=strict`를 실제로 켜는 순간 지금까지 숨어 있던 플랫폼 타입 관련 컴파일 에러가 한꺼번에 드러날 수 있습니다. 그게 지금까지 이 문제가 보이지 않았던 이유이기도 합니다.

---

### C7. k8s Deployment에 probe·resources가 전무하다 — `graceful shutdown`이 무력화

**근거**

```bash
$ grep -n "Probe\|resources\|limits\|requests\|terminationGracePeriod" \
    application/src/main/resources/k8s/deployment.yaml
(출력 없음)
```

`readinessProbe` / `livenessProbe` / `startupProbe` / `resources` / `terminationGracePeriodSeconds`가 **하나도 없습니다.**

**실패 시나리오**

1. **`graceful shutdown`이 무의미해집니다.** `application-prod.yaml:78`의 `shutdown: graceful`과 `:6-7`의 `timeout-per-shutdown-phase: 10s`는 readiness probe가 있어야 의미가 있습니다. probe가 없으면 Pod가 Terminating에 들어가도 Endpoints에서 늦게 빠져 **종료 중인 Pod로 트래픽이 계속 들어오고**, 반대로 부팅 중인 Pod에도 트래픽이 들어갑니다. 설정으로만 존재하는 안전장치입니다.
2. **배포 검증이 통과해 버립니다.** `deploy_action.yaml:231`의 `kubectl rollout status`는 probe가 없으면 Ready 대신 Running만 봅니다. 여기에 `:235`의 판정 로직이 겹칩니다 — H21 참조.
3. **QoS가 `BestEffort`** 입니다. `resources`가 없어 노드 압박 시 최우선 eviction 대상이고, `application/Dockerfile:19`에도 힙 플래그가 없어 컨테이너 메모리 상한 자체가 없습니다.

**수정**

Boot 4는 `management.endpoint.health.probes.enabled=true`로 `/actuator/health/readiness`·`/liveness`를 제공합니다.
```yaml
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 80 }
  initialDelaySeconds: 10
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 80 }
  initialDelaySeconds: 30
resources:
  requests: { memory: "1Gi", cpu: "250m" }
  limits:   { memory: "2Gi" }
```
`terminationGracePeriodSeconds`를 `timeout-per-shutdown-phase`(10s)보다 크게 잡고, Dockerfile에 `-XX:MaxRAMPercentage=75`를 추가하세요.

---

### C8. `ValidationBuilder.or`가 OR이 아니라 "우변 에러 무시기"

**근거**

`domain/src/main/kotlin/dev/notypie/domain/common/Utils.kt:24-31`
```kotlin
infix fun <T> Field<T>.or(block: ValidationBuilder.(Field<T>) -> Unit): Field<T> {
    val before = errors.size
    block(this)
    if (before < errors.size) { repeat(times = errors.size - before) { errors.removeLast() } }
    return this
}
```

이 구현은 "우변 블록이 추가한 에러를 무조건 제거"할 뿐입니다. **좌변이 이미 넣은 에러는 절대 걷어내지 않습니다.** 진짜 OR이라면 한쪽이 통과하면 양쪽 에러가 모두 사라져야 합니다.

**반례 (진리표 4칸 중 좌변 실패 / 우변 성공)**
```kotlin
validateAndReturn { "x" of "12345678901" shouldBeShorterThan 5 or { it shouldBeLongerThan 10 } }
// 기대: 에러 0건 (길이 11 > 10 이므로 우변 충족)
// 실제: 에러 1건 ("length must be less than 5") — 좌변 에러가 남는다
```

`ValidationBuilderTest.kt:41-60`은 `test = "1234"`로 (실패,실패)·(성공,실패)만 검사해 **우연히 통과**합니다. 하필 OR을 쓰는 주된 이유인 칸이 틀렸습니다.

**현재 영향**: `or`는 프로덕션 미사용(전체 grep 0건)이라 지금 터지지는 않습니다. 다음에 누가 쓰는 순간 조용히 잘못된 검증 결과를 냅니다.

**수정**

현재 시그니처로는 좌변 결과를 알 수 없는 것이 근본 원인입니다. 검증 결과를 값으로 들고 다니세요.
```kotlin
@JvmInline value class Check(val errors: List<ExceptionArgument>)
infix fun Check.or(other: () -> Check): Check =
    if (errors.isEmpty() || other().errors.isEmpty()) Check(emptyList()) else this
```
당장 고치기 어렵다면 **`or`/`and` 삭제**가 안전합니다 (둘 다 프로덕션 미사용, `and`는 `block(this)`만 하는 no-op 래퍼).

---

## 4. High

### H1. Outbox 폴링이 claim 하지 않은 행을 발송한다

`application/.../service/relay/PollingMessageProcessor.kt:35-42`
```kotlin
val candidates = outboxRepository.findPendingMessages(limit = batchSize, offset = 0)
val claimedCount = outboxRepository.claimPending(eventIds = candidates.map { it.eventId })
val toDispatch = candidates.take(n = claimedCount)   // ← 결함
```

`claimPending`(`MessageOutboxRepository.kt:40-42`)은 **갱신된 행 수**만 반환합니다. 어느 행이 갱신됐는지는 알 수 없는데 `take(claimedCount)`는 **앞에서부터 N개**를 집습니다.

**실패 시나리오**: A가 `[e0…e9]`를 읽는 사이 B가 `e3`, `e7`을 claim → A의 `claimedCount = 8` → A는 `[e0…e7]` 발송. `e3`,`e7`은 **중복 발송**, `e8`,`e9`는 claim 됐으나 미발송으로 **고착**(stuck 임계 300초 후에야 복구).

`:25-32`의 `recoverStuckInProgress()`는 **claim 없이 바로 dispatch**합니다 — 두 인스턴스가 동시에 복구하면 무조건 중복입니다.

**영향 범위**: `PollingMessageProcessor`는 `outbox-reading-strategy: polling`에서만 활성(`Conditions.kt:22`). 현재 prod/dev/local은 `cdc`라 **프로덕션 영향 없음**. 단 `AppConfig.kt:34` 기본값이 `POLLING`이라 설정 누락 시 이 경로로 떨어지고, `slack-live` 프로파일은 이 경로를 씁니다.

**수정**: 3.1 표의 다른 스케줄러와 동일하게 claim-token으로 통일.
```kotlin
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""UPDATE outbox_message SET status='IN_PROGRESS', claim_token=:token, updated_at=CURRENT_TIMESTAMP
          WHERE event_id IN (:eventIds) AND status='PENDING'""", nativeQuery = true)
fun claimPending(eventIds: List<String>, token: String): Int

@Query("SELECT * FROM outbox_message WHERE claim_token=:token AND status='IN_PROGRESS'", nativeQuery = true)
fun findClaimed(token: String): List<OutboxMessage>
```

---

### H2. Slack 429 / `Retry-After`를 전혀 다루지 않고, 재시도 대상이 반대로 잡혀 있다

`infrastructure/.../impl/command/ApplicationMessageDispatcher.kt:43-69, 180-196`

**두 방향 모두 틀렸습니다.**

1. **재시도해야 할 것이 재시도되지 않음.** Slack SDK는 rate limit을 `ok=false, error="ratelimited"`로 돌려줍니다. 그러면 예외 없이 `failOutput`이 반환되고 `retryService`는 **성공으로 간주** → 메시지 영구 유실, outbox row는 FAILURE 마감. `Retry-After` 헤더는 어디서도 읽지 않습니다.
2. **재시도하면 안 될 것이 재시도됨.** `invalid_auth`, `channel_not_found` 같은 영구 실패나 `:60-66`의 `UnsupportedOperationException`이 예외로 던져져 백오프를 태우며 3회 반복됩니다 (`exceptions` 기본값이 `listOf(Exception::class.java)`).

**수정**
```kotlin
private val RETRYABLE = setOf("ratelimited", "service_unavailable", "internal_error", "fatal_error", "request_timeout")

if (!result.isOk && result.error in RETRYABLE) {
    throw SlackRetryableException(result.error,
        retryAfterSeconds = result.httpResponseHeaders?.get("Retry-After")?.firstOrNull()?.toLongOrNull())
}
```
`retryService.execute(exceptions = listOf(SlackRetryableException::class.java, IOException::class.java))`로 좁히고, `UnsupportedOperationException` 분기는 retry **바깥**으로 빼세요.

---

### H3. `enable-auto-commit: true` — CDC 릴레이가 메시지를 조용히 잃는다

`application-prod.yaml:69`, `-dev:49`, `-local:49`
```yaml
      enable-auto-commit: true
      max-poll-records: 1000
```

`DebeziumLogTailingProcessor.kt:30-51`에는 **4개의 `return` 스킵 지점**이 있습니다 — 파싱 실패(`:37-40`), 상태 불일치(`:41`), eventId 파싱 실패(`:45-51`), payload 부재(`:33-36`).

**실패 시나리오**

1. **확정 — 조용한 스킵**: 4개 `return` 지점에서 레코드가 버려집니다. 오토커밋이라 오프셋은 전진하고 아웃박스 행은 `PENDING`으로 영원히 남습니다. DLT가 없어 사후 복구 수단이 전혀 없습니다. 배포 버전과 CDC 스키마가 일시적으로 어긋나면 해당 레코드는 listener 관점에서 "성공 처리"되고, **코드를 고친 뒤에도 자동 재처리되지 않습니다.**

2. **확정 — 재전달 시 현재 상태 미확인**: `:41`이 검사하는 `status`는 **CDC 레코드에 담긴 과거 값**입니다. Slack 전송과 DB 상태 갱신까지 성공한 뒤 오프셋 커밋 전에 프로세스가 죽으면, 재전달된 레코드로 **다시 전송**합니다. DB에 이미 성공이 기록돼 있어도 건너뛰지 못합니다.

3. **조건부 — 배치 중 크래시**: 오토커밋은 `auto.commit.interval.ms`(기본 5초)마다 `poll()` 안에서 커밋합니다. 동기 listener이므로 커밋이 미처리 레코드를 추월하는 창은 좁지만, `max-poll-records: 1000`으로 처리가 길어져 컨테이너가 consumer를 pause한 상태로 `poll()`을 계속 호출하는 구간에서는 fetch된 미처리 오프셋이 커밋될 수 있습니다.
   > 독립 리뷰(Codex)가 이 3번에 대해 *"이 설정 하나만으로 유실을 단정할 수 없다"*고 반론했고 **수용했습니다.** 1·2번이 확정 결함이고, 3번은 컨테이너 pause/poll 동작에 의존하는 조건부 위험입니다.

1·2번만으로도 `AGENTS.md`의 *"nothing is lost between Slack and the database"* 와 충돌합니다.

**수정**
```yaml
      enable-auto-commit: false
      max-poll-records: 100
```
```kotlin
factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL
factory.setCommonErrorHandler(DefaultErrorHandler(
    DeadLetterPublishingRecoverer(kafkaTemplate), ExponentialBackOff(1_000L, 2.0)))
```
4개 스킵 지점은 `return` 대신 DLT로 보내세요.

---

### H4. Outbox 핫패스에 인덱스가 없고, OFFSET 페이징이 행을 건너뛰며, 보존 정책이 없다

`infrastructure/.../repository/outbox/schema/OutboxMessage.kt:17-22` — 선언된 인덱스는 `idx_outbox_idempotency_key` 하나뿐입니다. 그런데 모든 쿼리는 `status` 기준입니다 (`MessageOutboxRepository.kt`의 8개 메서드 전부).

**세 가지가 겹칩니다.**

1. **인덱스 부재**: `(status, created_at)` / `(status, updated_at)`가 없어 폴링·헬스체크마다 풀 테이블 스캔 + filesort.
2. **OFFSET 행 유실**: `findPendingMessages(limit, offset)`(`:15-22`)로 페이지 0을 읽고 claim 하면 조건 집합이 줄어듭니다. 페이지 1(`offset=limit`)을 읽으면 **아직 안 읽은 PENDING 행이 통째로 건너뛰어집니다.**
3. **보존 정책 부재**: 이 리포지토리에 DELETE/purge가 **하나도 없습니다.** CVE 쪽은 `CveCollector`가 7일 지난 ledger를 정리하는데 outbox는 무한 증식합니다.

부가: `idempotency_key`는 인덱스만 있고 **UNIQUE가 아니라** 멱등성 키로서 중복을 DB가 막지 못합니다.

**마이그레이션까지 확인해 확정**했습니다. 엔티티 매핑만으로는 "운영 DB에 인덱스가 없다"를 단정할 수 없다는 지적(Codex)이 타당해 17개 마이그레이션을 전수 조사했습니다:
```bash
$ grep -rn -i "index" application/src/main/resources/db/migration/*.sql | grep -i outbox
V1__outbox_pk_event_id.sql:35:CREATE INDEX IF NOT EXISTS idx_outbox_idempotency_key ON outbox_message (idempotency_key);
```
**`(status, …)` 복합 인덱스를 만드는 마이그레이션은 없습니다.** 엔티티와 마이그레이션이 일치하며 양쪽 모두 부재이므로 확정입니다.

**수정**
```kotlin
indexes = [
    Index(name = "idx_outbox_idempotency_key", columnList = "idempotency_key"),
    Index(name = "idx_outbox_status_created_at", columnList = "status, created_at"),
    Index(name = "idx_outbox_status_updated_at", columnList = "status, updated_at"),
]
```
```sql
-- OFFSET 제거, keyset 페이징
WHERE status='PENDING' AND (created_at, event_id) > (:lastCreatedAt, :lastEventId)
ORDER BY created_at ASC, event_id ASC LIMIT :limit
```
`SUCCESS`/`FAILURE` 행을 N일 후 삭제하는 purge 배치를 추가하세요. 동반 마이그레이션 `V18__add_outbox_status_indexes.sql`이 필요합니다.

> ⚠️ H8(마이그레이션 수단 복구)이 선행되어야 안전하게 적용됩니다.

---

### H5. `ControllerAdvice`가 DB 예외를 통째로 삼킨다

`application/.../exception/ControllerAdvice.kt:14-16`
```kotlin
@ExceptionHandler(value = [DatabaseException::class])
fun handleDatabaseException(e: DatabaseException) {
}
```

**본문이 비어 있습니다.** 로깅도 상태 코드도 없습니다. `DatabaseException` 발생 시 **HTTP 200 OK + 빈 본문**이 반환되고, Slack은 정상 처리로 간주하며, 로그에 아무것도 남지 않습니다. DB 장애가 완전히 은폐됩니다.

또한 범용 `@ExceptionHandler(Exception::class)`가 없어 그 외 예외는 500이 나가고 → **Slack이 최대 3회 재시도** → 부분 커밋된 작업이 중복 수행될 수 있습니다.

**수정**
```kotlin
@ExceptionHandler(DatabaseException::class)
fun handleDatabaseException(e: DatabaseException): ResponseEntity<Map<String, String>> {
    logger.error(e) { "Database failure while handling a Slack request" }
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "internal_error"))
}

@ExceptionHandler(Exception::class)
fun handleUnexpected(e: Exception): ResponseEntity<Map<String, String>> { ... }
```

---

### H6. app_mention의 `channelName` / `actorName`이 항상 문자열 `"null"`

`application/.../service/mention/SlackMentionEventHandlerImpl.kt:48-49`
```kotlin
channelName = payload["channel_name"].toString(), // FIXME
actorName = payload["user_name"].toString(),      // FIXME
```

`payload`는 `Map<String, Any>`이고, Slack의 `app_mention` 이벤트에는 최상위 `channel_name` / `user_name`이 **없습니다**(그건 슬래시 커맨드 form body 필드). 없는 키는 `null`을 반환하고, nullable 수신자의 `.toString()`은 **리터럴 `"null"`** 을 만듭니다.

→ 프로덕션에서 이 두 값은 사실상 항상 `"null"`이며 그대로 도메인 커맨드에 실려 저장·표시됩니다. 원본의 `// FIXME`가 이를 뒷받침합니다.

**수정**
```kotlin
channelName = payload["channel_name"] as? String ?: body.event.channel,
actorName   = payload["user_name"] as? String ?: body.event.user,
```
표시 이름이 실제로 필요하면 `conversations.info` / `users.info`로 조회하고, 필요 없으면 필드를 제거하세요.

---

### H7. `notBlank` 에러 메시지에 `Field` data class의 `toString()`이 Slack 사용자에게 그대로 노출

`domain/.../common/Utils.kt:66`
```kotlin
reason = "$field must not be blank",   // field 는 Field<String> (data class, :12)
```

`field.name`이 아니라 `field` 자체를 보간합니다. 생성 문자열: `Field(name=title, value=) must not be blank`

**사용자 노출 경로 추적**
```
Utils.kt:66  →  ExceptionArgument.reason
  →  RequestMeetingContext.kt:174  "${detail.fieldName}: ${detail.reason}"
  →  RequestMeetingContext.kt:128  createErrorResponse(...)
  →  CommandContext.kt:30-49  →  OutboundMessage.Ephemeral
```

Slack에 이렇게 출력됩니다:
```
title: Field(name=title, value=) must not be blank
```
필드명이 두 번 나오고, 내부 클래스 구조가 노출되며, 읽을 수도 없습니다.

**수정**: `reason = "must not be blank"`. 다른 모든 연산자는 이미 필드명을 `fieldName`에 담고 `reason`에는 사유만 씁니다 — `shouldBeShorterThan`(`:86`)과 대조하면 이 줄만 관례에서 벗어나 있습니다.

**테스트 갭**: `ValidationBuilderTest.kt:71, 88`이 `notBlank`를 호출하지만 `reason` 문자열을 한 번도 단언하지 않아 잡히지 않았습니다.

---

### H8. 수동 마이그레이션 관례의 리스크가 완화되지 않은 채로 남아 있다

> **먼저 공정을 기하면**: Flyway 미사용은 사고가 아니라 **의도적이고 문서화된 결정**입니다 — `docs/wiki/decisions.md:78`: *"Flyway를 쓰지 않는다. `V*.sql`은 수동 적용 관례."* 따라서 "관리 주체가 실종됐다"는 서술은 부정확합니다. 문제는 결정 자체가 아니라 **그 결정이 수반하는 리스크가 어떤 수단으로도 완화되지 않았다**는 점입니다.

**근거**

`application/src/main/resources/db/migration/`에 Flyway 네이밍 마이그레이션 **17개**(`V1__outbox_pk_event_id.sql` ~ `V17__add_cve_event_query_indexes.sql`)가 있습니다. 그런데:

```bash
$ grep -rn "flyway" --include="*.kts" --include="*.yaml" . | grep -v build/ | grep -v docs/
(결과 없음)
```

**Flyway는 의존성도 설정 키도 아닙니다.** `main`에서는 `application-local.yaml`의 `flyway.enabled: false` 블록마저 제거됐습니다.

| 프로파일 | `ddl-auto` | 스키마 출처 |
|---|---|---|
| local (`:25`) | `update` | Hibernate |
| dev (`:30`) | `update` | Hibernate |
| slack-live (`:37`) | `update` | Hibernate |
| **prod (`:32`)** | **`none`** | **`V*.sql` 수작업 적용** |

`application-slack-live.yaml:9-10`이 현재 운영 방식을 명시합니다:
> `# ddl-auto=update stands the schema up (the V* migrations are incremental patches applied by hand, same as the local profile).`

그리고 가장 직접적인 증거는 마이그레이션 자신의 주석입니다 — `db/migration/V1__outbox_pk_event_id.sql:20-22`:
> `-- Apply this script BEFORE rolling out the application code that expects event_id to be the PK.`
> `-- For environments with auto-ddl enabled (dev/local) this migration is applied automatically by Hibernate.`
> `-- In prod, execute manually — schema auto-migration is disabled.`

**실패 시나리오**

1. prod는 `ddl-auto: none`이므로 **Flyway도 Hibernate도 스키마를 만들지 않습니다.** 전부 수동 적용이며, **적용 이력을 기록하는 테이블이 없어** 어느 환경에 몇 번까지 적용됐는지 알 수 없습니다.
2. dev는 Hibernate가, prod는 손으로 쓴 SQL이 스키마를 만듭니다. **두 스키마가 일치한다는 보장이 없습니다.** 인덱스 이름·컬럼 길이·기본값이 조용히 갈립니다.
3. `ddl-auto: update`는 컬럼 삭제·타입 축소·인덱스 제거를 하지 않으므로, dev DB는 과거 스키마 잔해가 누적된 상태로 수렴합니다.
4. 마이그레이션 스크립트를 **어떤 테스트도 실행하지 않습니다**.

H4(인덱스 추가) 같은 변경을 안전하게 롤아웃할 수단이 현재 없습니다.

**수정**

Flyway를 실제 의존성으로 추가하고 `baseline-on-migrate` + `baselineVersion`으로 현재 prod 스키마에 기준선을 잡으세요. 파일이 이미 Flyway 규약을 따르므로 전환 비용이 낮습니다. 전환 전 임시 조치로 dev를 `ddl-auto: validate`로 내리면 엔티티↔DDL 드리프트를 기동 시점에 잡을 수 있습니다. Testcontainers MariaDB로 마이그레이션을 순차 적용하는 테스트를 추가하면 M-L1(MariaDB 전용 쿼리 미검증)도 함께 해결됩니다.

---

### H9. `CommandDetailType.createContext()`의 `else -> EmptyContext` — 새 커맨드가 조용히 no-op

`domain/.../command/entity/CommandType.kt:65-152`

`CommandDetailType`은 **30개** 상수인데(`:29-63`) `createContext`의 `when`은 **9개만** 처리하고 나머지는 전부 `else -> EmptyContext`(`:146-151`)로 떨어집니다. `EmptyContext`는 `runCommand()` 오버라이드가 없어 `CommandOutput.empty()`를 반환하는 **완전한 no-op**입니다.

**실패 시나리오**: 새 `CommandDetailType`을 추가하고 `createContext` 등록을 잊으면 **컴파일도 통과, 테스트도 통과, 런타임에 아무 일도 안 일어납니다.** Slack에는 "무반응"으로 나타나 디버깅이 최악입니다.

**수정**: `else`를 없애고 no-op 타입을 명시적으로 나열 → enum 상수 추가 시 컴파일 에러.
```kotlin
when (this) {
    CommandDetailType.APPROVAL_REQUEST -> ApprovalFormContext(...)
    // 컨텍스트가 없는 타입은 의도적으로 명시 — 새 상수는 여기서 컴파일 에러를 낸다
    CommandDetailType.NOTHING, CommandDetailType.SIMPLE_TEXT, ... -> EmptyContext(...)
}
```
같은 패턴이 `SubmissionRouting.kt:40`, `InteractionCommand.kt:76`에도 있습니다. 반대로 `SubmissionRouter.route()`(`SubmissionRouting.kt:66-114`)와 `detailType()`(`:43-52`)는 이미 `else` 없이 exhaustive해 **좋은 참고 사례**입니다.

---

### H10. 에러 코드가 예외에 저장되지 않아 전부 유실된다

`domain/.../common/error/Errors.kt:54-57`
```kotlin
abstract class CodeCompanionRuntimeException(
    errorCode: ErrorCode,                    // ← val 이 아님. 버려진다
    val details: List<ExceptionArgument> = emptyList(),
) : RuntimeException(errorCode.message)
```

`errorCode`는 프로퍼티가 아니라 생성자 파라미터입니다. `errorCode.message`만 넘기고 **`errorCode` 자체와 `statusCode`는 소실**됩니다. 6개 호출부(`Command.kt:58,80`, `InteractionCommand.kt:83`, `SlashInvocations.kt:16`, `SetupStandupCommand.kt:42`, `RequestMeetingCommand.kt:45`)가 코드를 지정하는데 **아무도 읽을 수 없습니다.**

연쇄 결과:
- `CommandErrorCode` 6개 중 `COMMAND_NOT_FOUND`, `UNKNOWN_SUBCOMMAND_TYPE`, `VALIDATION_FAILED` **사용처 0건**
- `ErrorResponse`(`Errors.kt:47-52`)는 `sealed class`인데 **서브클래스 0개** — 완전한 데드 코드
- `ErrorCode.statusCode`(`Errors.kt:4`)는 **HTTP 상태 코드**. 도메인이 전송 프로토콜 어휘를 들고 있습니다

**수정**: `val errorCode: ErrorCode`로 보존. `statusCode`는 도메인에서 제거(HTTP 매핑은 application의 `@ControllerAdvice` 몫). 미사용 enum 3개와 `ErrorResponse` 삭제.

---

### H11. 슬래시 커맨드 경로만 권한 검사를 건너뛴다

`commandRoleResolver.resolve(...)` 호출처는 전체 레포에서 두 곳뿐입니다:
```
application/mcp/McpToolGate.kt:54                               → MCP 툴
application/service/mention/SlackMentionEventHandlerImpl.kt:58  → app_mention
    → domain/.../parsers/AppMentionContextParser.kt:73 에서 grants() 검사
```

`SlashCommandController`의 7개 엔드포인트(`/meet`, `/standup`, `/task`, `/subscribe`, `/unsubscribe`, `/subscriptions`, `/latest`)에는 **권한 검사가 없습니다.**

**정확한 위험도**: 현재 슬래시 커맨드는 모두 BASIC 성격이고, 행위자 신원도 Slack 서명된 `user_id`에서 오며 조회가 `actorId`로 스코프됩니다(`CveSubscriptionSlashServiceImpl.kt:79`) — **IDOR도, 당장 악용 가능한 취약점도 없습니다.** 문제는 **구조적 게이트의 부재**로, 향후 ops/admin 성격의 슬래시 커맨드를 추가하면 조용히 무인증으로 출시된다는 점입니다.

추가 우려: `SlackMentionEventHandlerImpl.kt:32`의 `// FIXME Remove AppMention Events.` — 이 레인은 권한을 검사하는 두 곳 중 하나입니다. 제거 전에 검사 지점을 이관할 곳을 만들어야 합니다.

**수정**: `McpToolGate`와 대칭되는 `SlashCommandGate`를 도입해 세 경로가 모두 `CommandSet.requiredPermission`을 통과하게 하세요.

---

### H12. 템플릿 렌더링 도중 동기 Slack API 호출 — 타임아웃·캐시 없음, 실패 시 전체 실패

`infrastructure/.../templates/ModalTemplateBuilder.kt:98-103`
```kotlin
override fun approvalTemplate(...): LayoutBlocks {
    val user = restRequester.get(                       // ← 블로킹 HTTP
        uri = "users.profile.get?user=${approvalContents.publisherId}", ...)
```

승인 메시지를 **한 건 만들 때마다** `users.profile.get`을 호출합니다.
- **캐시 없음**: `users.profile.get`은 Tier 4(분당 ~100회). 승인 알림 팬아웃 시 즉시 429.
- **타임아웃 없음**: H13 참조.
- **fail-closed**: `bodyOrThrow`(`RestClientRequester.kt:210`)가 실패 시 예외를 던져 **승인 메시지 전체가 발송되지 않습니다.** 사용자 이름 표시라는 장식 요소 때문에 핵심 기능이 죽습니다.

**수정**: 프로필 조회를 템플릿 빌더 밖으로 빼고(도메인/애플리케이션에서 `ApprovalContents`에 displayName을 채움), 최소한 TTL 캐시 + `<@userId>` mention fallback으로 degrade 하세요.

---

### H13. `RestClientRequester`에 타임아웃이 없고 Authorization 헤더가 중복될 수 있다

`infrastructure/.../impl/command/RestClientRequester.kt:22-34, 190-194`

1. **타임아웃 부재**: `RestClient.builder()`(정적 팩토리)는 Boot의 `spring.http.client.*` 설정을 상속하지 않습니다. connect/read 타임아웃이 없어 Slack이 응답하지 않으면 호출 스레드가 **무한 대기**합니다. H12와 결합하면 승인 메시지 렌더링이 스레드를 영구 점유합니다.
2. **Authorization 이중 추가**: 생성자 `authorization`은 **prefix 없이** `add`(`:31`), 메서드 파라미터는 **`Bearer ` prefix를 붙여** `add`(`:192`). `add`는 `set`이 아니므로 둘 다 설정되면 헤더가 두 개 나가고 Slack이 거부합니다.
3. **상태 코드 유실**: `bodyOrThrow`(`:210`)가 모든 실패를 `RestClientException`으로 뭉개 401/429/500을 구분할 수 없습니다.

**수정**
```kotlin
RestClient.builder()
    .requestFactory(JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    ).apply { setReadTimeout(Duration.ofSeconds(10)) })
```
Authorization 규약을 한쪽으로 통일하고 `add` → `set`으로 바꾸세요.

---

### H14. SSE 스트림에 idle timeout이 없다 — sidecar가 멈추면 스레드·커넥션 영구 누수

`infrastructure/.../impl/agent/SidecarAgentClient.kt:50-70, 114-124`

`HttpRequest.timeout()`(`:53`)은 **응답 헤더 수신까지**만 적용됩니다. 헤더 도착 후 body를 스트리밍하는 구간은 보호받지 못합니다. sidecar가 `event: session`만 보내고 멈추면 `for (line in lines.iterator())`(`:114`)가 **무한 블로킹**되고 호출 스레드 + HTTP 커넥션이 영구히 잡힙니다.

> 리소스 해제 자체는 정확합니다 — `:64`의 `response.body().use {}`가 조기 return 경로를 포함해 항상 스트림을 닫습니다. 문제는 **닫히는 시점이 오지 않는 경우**입니다.

**수정**: 스트림 전체 데드라인 + 워치독, 또는 `sendAsync` + `orTimeout(requestTimeout)`.
```kotlin
private fun <T> withDeadline(stream: Stream<String>, deadline: Duration, block: () -> T): T {
    val watchdog = scheduler.schedule({ runCatching { stream.close() } }, deadline.toMillis(), MILLISECONDS)
    return try { block() } finally { watchdog.cancel(false) }
}
```

부수: `:83`의 `body.reduce("") { acc, line -> acc + line }`는 O(n²) 문자열 연결이고 **개행이 제거**되어 JSON 파싱이 깨질 수 있으며 크기 상한도 없습니다. `application-prod.yaml:124`의 `SIDECAR_BEARER_SECRET:` 기본값이 빈 문자열이라 `Authorization: Bearer `(빈 토큰)가 그대로 나갑니다.

---

### H15. `meetings` / `meeting_participants` / `standup_routine`에 인덱스가 하나도 없다

`MeetingSchema.kt:14`, `:113`, `RoutineSchema.kt:19` — 모두 `@Entity`만 있고 `@Table(indexes = ...)`가 없습니다. CVE 계열은 인덱스를 꼼꼼히 선언한 반면(`CveEventSchema.kt:24-29`) 미팅/스탠드업 계열은 `unique = true`가 만드는 인덱스 외에 아무것도 없습니다.

| 쿼리 | 필요한 인덱스 |
|---|---|
| `JpaMeetingRepository:56-70 findActiveByStartAtBetween` (리마인더 스케줄러가 주기적 전수 스윕) | `meetings(is_canceled, start_at)` |
| `:38-53 findMeetingsByUserIdAndDateRange` | `meetings(publisher_id, start_at)` |
| `:92-103 existsParticipant`, `:28-36 findAllMeetingByUserId` | `meeting_participants(user_id)` |
| `JpaRoutineRepository:25-35 findActiveByCommandChannel` | `standup_routine(is_active, command_channel)` |

리마인더 스케줄러는 `fixedDelay`로 계속 도는데 매번 `meetings` 전수 스캔입니다.

**수정**: H4의 `V18` 마이그레이션에 묶어서 처리하세요. `meeting_participants`에는 `UNIQUE(meeting_id, user_id)`도 함께 겁니다(H16 해결).

---

### H16. 미팅 계열에 낙관적 락이 없어 정원 초과·중복 참석자가 발생한다

`MeetingRepositoryImpl.kt:94-143`의 `addParticipants`는 read-modify-write인데 `MeetingSchema`에 `@Version`이 없습니다. 전체 레포에서 `@field:Version`은 `OutboxMessage.kt:55` **한 곳뿐**입니다.

두 사용자가 동시에 참석자를 추가하면 둘 다 같은 스냅샷을 읽어 **정원 검사를 각자 통과** → 합쳐서 `MAX_PARTICIPANTS = 20` 초과. 같은 userId를 동시에 추가하면 `meeting_participants`에 unique 제약이 없어 **중복 행이 INSERT**됩니다.

또한 `:125-129`의 `runCatching{...}.isSuccess`는 **모든 예외를 "정원 초과"로 해석**합니다 → H17과 결합해 거짓 메시지를 냅니다.

**수정**: `MeetingSchema`에 `@Version`, `meeting_participants`에 `UNIQUE(meeting_id, user_id)` 추가.

---

### H17. `./run -m 2g <jar>` 가 즉시 죽는다 — 함수 밖 `local` 선언 (재현 완료)

`run:267-268`
```bash
if [[ "$MEMORY" =~ ^([0-9]+)g$ ]]; then
    local mem_gb="${BASH_REMATCH[1]}"        # ← 함수 밖
    local initial_gb=$((mem_gb / 4))         # ← 함수 밖
```

두 줄이 함수가 아니라 **탑레벨 `if`/`case` 블록**(`run:258` `else` → `:263` `*)`) 안에 있습니다. bash에서 함수 밖 `local`은 에러이고 `run:9`의 `set -e`가 스크립트를 종료시킵니다.

**재현**
```bash
$ touch /tmp/fake.jar
$ ./run -m 2g /tmp/fake.jar
./run: line 267: local: can only be used in a function
$                                    # ← JVM이 뜨지 않고 끝

$ ./run -m 2g -e prod /tmp/fake.jar  # 대조군: 정상 진행
[INFO] Features    : JMX monitoring, Production endpoints only
[WARN] You are about to start the application in PRODUCTION mode.
```

트리거: `-m`을 `^[0-9]+g$` 형태로 주고 환경이 `local`/`dev`(**기본값**)일 때. `prod` 분기(`run:260-261`)는 `local`을 타지 않아 무사합니다. 즉 **기본 환경에서 메모리를 지정하는 사용법이 깨져 있습니다.**

**수정**: `local` 키워드 2개 제거, 또는 메모리 계산 블록을 함수로 추출. 아울러 `run:9`를 `set -euo pipefail`로 올리면 이 부류가 더 빨리 드러납니다(`run:429`의 `java -version | awk` 파이프 실패도 현재 감춰집니다).

---

### H18. `api(...)`로 Spring·JPA·Kafka가 application 컴파일 클래스패스에 누출

`infrastructure/build.gradle.kts:23, 26, 30`
```kotlin
api(platform("tools.jackson:jackson-bom:$jacksonVersion"))
api("org.springframework.boot:spring-boot-starter-kafka")
api("org.springframework.boot:spring-boot-starter-data-jpa")
```

> **먼저 정확히: `domain`은 안전하고 `DomainLayeringGuardTest`도 유효합니다.** `./gradlew :domain:dependencies --configuration compileClasspath` 실측 결과 kotest-bom / kotlin-stdlib / kotlin-reflect / kotlin-logging 뿐이며 **Spring·Jackson·Hibernate가 전혀 없습니다.** domain은 infrastructure에 의존하지 않으므로 `api`가 닿을 경로가 없습니다.

실제 누출은 **application 방향**입니다. `./gradlew :application:dependencies --configuration compileClasspath`에 `spring-boot-starter-kafka → kafka-clients`, `spring-boot-starter-data-jpa → hibernate-core`가 올라옵니다. `application/build.gradle.kts:20`은 `implementation(project(":infrastructure"))`만 선언하는데도 **application에서 `EntityManager`·`KafkaTemplate`을 직접 import해도 빌드가 통과**합니다. 어댑터 경계를 우회하는 코드가 조용히 들어올 수 있습니다.

**수정**: 두 `api` → `implementation`. (application이 실제로 필요한 것은 각자 선언하게 하세요.)

---

### H19. 빈 테스트 스펙 2개가 녹색으로 통과한다

```kotlin
// domain/src/test/kotlin/dev/notypie/domain/command/CommandDomainTest.kt:5-7
class CommandDomainTest :
    BehaviorSpec({
    })
```
```kotlin
// infrastructure/src/test/kotlin/dev/notypie/exception/DatabaseExceptionTest.kt:9-10
given("Nullable Schema") {
}
```

둘 다 단언이 0개이며 통과합니다. 테스트 **파일 수를 부풀려** 커버리지 인식을 왜곡합니다. `DatabaseExceptionTest`는 이름 그대로 H5(빈 `@ExceptionHandler`)가 다루는 `DatabaseException`의 테스트여야 하는데 비어 있다는 점이 특히 아픕니다.

**수정**: 구현하거나 삭제하세요. 7.6의 JaCoCo 도입이 이 부류를 자동으로 드러냅니다.

---

### H20. `lint.yaml`에 `permissions:` 블록이 없다

```bash
$ grep -c permissions .github/workflows/lint.yaml          # → 0
$ grep -c permissions .github/workflows/simple_test_action.yaml  # → 1
$ grep -c permissions .github/workflows/deploy_action.yaml       # → 1
$ grep -c permissions .github/workflows/security_check.yaml      # → 5
```

4개 워크플로 중 `lint.yaml`만 권한을 선언하지 않습니다. 저장소 기본값이 write면 `ktlintCheck`만 하는 잡이 쓰기 토큰을 갖습니다. 게다가 `.github/AGENTS.md:124`는 *"the workflow default is `contents: read`"* 라고 기술하는데 **사실이 아닙니다** — 없는 안전장치를 있다고 주장하는 문서입니다.

**수정**: `permissions: { contents: read }` 2줄 추가. 관련해서 `deploy_action.yaml:21-24`의 `check-changes` 잡은 `:50`에서 `dorny/paths-filter@v4`를 `pull_request` 이벤트로 쓰는데 `pull-requests: read`가 없습니다 — 같은 레포의 `security_check.yaml:18-21`은 주석까지 달아 이 권한을 부여합니다. 권한이 없어 필터가 조용히 `false`가 되면 **배포가 무음 스킵**되고 워크플로는 녹색으로 끝납니다.

---

### H21. 배포 검증이 Ready가 아니라 Running을 세고, 헬스 체크 URL이 깨져 있다

`deploy_action.yaml:235`
```bash
READY_PODS=$(kubectl get pods -l app=... -o jsonpath='{.items[?(@.status.phase=="Running")].metadata.name}' | wc -w)
```
변수명은 `READY_PODS`인데 **`phase=="Running"`** 을 셉니다. C7의 probe 부재와 겹치면 **컨텍스트 로딩에 실패해 부팅 중인 Pod도 "Ready"로 집계**됩니다.

`deploy_action.yaml:27-28, 251`
```yaml
K8S_APP_INGRESS_HOST: https://api.notypie.dev
HEALTH_CHECK_ENDPOINT: /api/slack/actuator/health
```
```bash
if curl -f -s "${{ env.K8S_APP_INGRESS_HOST }}/${{ env.HEALTH_CHECK_ENDPOINT }}" > /dev/null; then
```
→ `https://api.notypie.dev//api/slack/actuator/health` (**이중 슬래시**). 또한 `:256`:
```bash
echo "Attempt $i failed, HTTP code: $(curl -o /dev/null -s -w "%{http_code}" ...)"
```
URL 자리에 **리터럴 `...`** 이 들어가 있어 실패 시 HTTP 코드를 절대 알 수 없습니다.

> 부가: `ACTUATOR_BASE_PATH`(configmap의 `/actuator`)와 이 엔드포인트의 `/api/slack` 접두가 맞는지도 확인이 필요합니다. `docs/wiki/dev-environment.md:145`가 이 불일치를 "미확인"으로 남겨두고 있습니다.

---

## 5. Medium

### 도메인 모델링

**M1. `Meeting` 생성자가 DB 재구성에 쓸 수 없다** — `Meeting.kt:30`
```kotlin
"meeting start time" of startAt shouldBeAfter LocalDateTime.now()
```
생성자 불변식이 **현재 시각에 의존**합니다. 이 생성자는 재구성 경로에서도 호출됩니다(`MeetingSchema.kt:79-88`, `MeetingRepositoryImpl.kt:19-23`, `:125-129`). 세 갈래로 터집니다:
1. **쓰기 후 읽기**: `createNewMeeting`이 저장 직후 `toDomainEntity()`로 되돌리는데, `startAt`이 "지금+수 초"면 저장은 성공했는데 되돌리는 순간 예외 — **행은 들어갔는데 호출자는 예외를 받습니다.**
2. **오분류**: `runCatching{}.isSuccess`(H16)가 모든 검증 실패를 "정원 초과"로 뭉갭니다. 레거시 데이터에 빈 title이나 `endAt <= startAt`이 하나라도 있으면 사용자는 *"참석자 정원이 초과되었습니다"* 라는 **거짓말**을 받습니다.
3. "지난 회의 조회/복원" 기능은 엔티티를 만들 수 없습니다.

**수정**: 생성 정책과 상태 불변식을 분리.
```kotlin
class Meeting private constructor(...) {
    init { validate { /* 항상 참인 불변식만: notBlank, title 길이, endAt > startAt */ } }
    companion object {
        fun schedule(..., now: LocalDateTime): Meeting { validate { "start" of startAt shouldBeAfter now }; return Meeting(...) }
        fun rehydrate(...): Meeting = Meeting(...)   // 시각 정책 없음
    }
}
```
그리고 `MeetingRepositoryImpl.kt:125-129`는 **정원만 명시적으로 검사**하도록 바꾸세요.

**M2. `Meeting.addParticipant`가 중복을 제거하지 않는다** — `Meeting.kt:17, 43-48, 55-67`
`Member`는 plain class라 equality가 identity 기반인데 `MutableSet<Member>`에 담깁니다. 같은 `userId`로 두 번 부르면 항목이 2개가 됩니다. 정원 검사(`:45`)가 중복을 세어 실제보다 일찍 거부합니다.
> **대조군이 바로 옆에 있습니다**: `Routine.addMember`(`standup/entity/Routine.kt:69-75`)는 정확히 이 함정을 알고 `members.removeIf { it.userId == member.userId }`로 방어하며 `RoutineTest.kt:112-124`에 테스트까지 있습니다. `Meeting`에는 방어도 테스트도 없습니다.

**M3. `StandupSession`에 상태 전이 메서드가 없다** — `StandupSession.kt:13`
`status`가 `val`이고 전이 메서드가 없어 `COLLECTING → SUMMARIZED`가 도메인 밖(JPA 직접 수정)에서 일어납니다. `:22-27`의 불변식은 생성 시점만 검증합니다.

**M4. 상태 머신이 인프라 SQL 문자열 리터럴에만 존재** — `DispatchStatus.kt`, `SessionStatus.kt`, `MeetingReminderStatus.kt`
세 enum 모두 전이 규칙이 없는 라벨 나열이고, 실제 전이는 전부 SQL입니다(`JpaSessionDispatchRepository.kt:36,52,69,84` 등).
> **원자적 CAS를 SQL `WHERE`에 두는 것 자체는 옳습니다.** 주석이 이유까지 명시하고 있고 동의합니다. 진짜 문제는 (a) 합법 전이 표가 **어디에도 명시되어 있지 않고** SQL 6곳을 읽어야 알 수 있으며 도메인 테스트가 불가능하다는 것, (b) SQL이 `'SENDING'` 같은 **문자열 리터럴**을 써서 enum을 리네임하면 **컴파일은 통과하고 SQL만 조용히 깨진다**는 것입니다.

**수정**: 전이 표를 도메인 enum의 `canTransitionTo`로 두고 도메인 테스트 대상으로 만들되, 인프라 CAS 쿼리는 그대로 유지하고 리터럴을 `:status` 바인딩 파라미터로 바꾸세요.

**M5. `Clock` 주입 관례가 application에만 있고 도메인은 `LocalDateTime.now()` 직접 호출**
도메인 직접 호출: `Meeting.kt:30`, `Utils.kt:291,305`, `MeetingFormInput.kt:129`, `RequestMeetingContext.kt:79`, `CommandIntent.kt:11-12`, `Event.kt` 13곳(`System.currentTimeMillis()`), `ApprovalContents.kt:17`. `UUID.randomUUID()`도 22곳.
> 일반론이 아니라 **프로젝트 내부 관례 불일치**입니다. application 레이어는 이미 `Clock`을 주입하고 테스트에서 `Clock.fixed(...)`로 고정합니다(`SlackSignatureVerifierTest.kt:15`, `StandupSchedulingServiceTest.kt:53`, `CveNotificationDispatcherTest.kt:61` 등). `DomainReadTools.kt:25`도 `Clock`을 받습니다. **도메인만 빠져 있습니다.**

**M6. `CommandEffect`가 sealed가 아니라 소비자가 런타임 `error()`로 방어** — `CommandEffect.kt:3`
`CommandExecutor.kt:38-52`가 주석까지 남기며 방어 중입니다. 컴파일 타임에 잡을 수 있는 것을 런타임 크래시로 미뤘습니다. `CommandIntent`와 `OutboundMessage`가 다른 패키지라 바로 봉인할 수 없으니, 큐를 타입으로 분리하세요.
```kotlin
data class DrainedEffects(val intents: List<CommandIntent>, val outbound: List<OutboundMessage>)
```

**M7. `RequestApprovalContext` 생성자가 주입받은 `Queue`를 파괴적으로 소비** — `RequestApprovalContext.kt:17-18, 26, 48-57`
`commands.poll()`(`:49`)이 **호출자가 넘긴 큐에서 원소를 제거**합니다. 객체를 만들었을 뿐인데 인자가 변형되고, 같은 큐로 두 번 만들면 다른 결과가 나옵니다. 또 `:26`이 프로퍼티 초기화 중 `commandDetailType`(`by lazy { parseCommandDetailType() }`)을 읽어 **미완성 서브클래스의 open 함수를 호출**합니다.
**수정**: `Queue<String>` → `List<String>`, `poll()` → `firstOrNull()`. 호출부(`AppMentionContextParser.kt:76-77`)가 이미 새 리스트를 만들어 넘기므로 손해가 없습니다.

### 트랜잭션 · JPA 위생

**M8. `@Modifying` 쿼리 18건 전부 `clearAutomatically`/`flushAutomatically` 누락**
`MessageOutboxRepository.kt:29`, `JpaCveEventRepository.kt:16,56,76,94,112,129,198,213`, `JpaSessionDispatchRepository.kt:31,46,62,79`, `JpaMeetingReminderRepository.kt:35,50,66,83`, `JpaMeetingRepository.kt:72,105,121`, 외.
벌크 UPDATE가 1차 캐시를 우회하므로 `claimForSummary` 직후 같은 트랜잭션에서 엔티티를 읽으면 **갱신 이전 값**이 보입니다. CVE 파이프라인은 claim → summarize → markDone을 연달아 수행하므로 실제로 밟기 쉬운 경로입니다.

**M9. `updateMessage`에 트랜잭션 경계가 없다** — `SlackMessageRelayServiceImpl.kt:79-86`
`findById` → 수정 → `save`가 `@Transactional` 없이 돕니다. `@field:Version` 덕에 lost update는 막히지만 `OptimisticLockException` + 5회 재시도 소음이 납니다. `:83`의 `orElseThrow { throw RuntimeException("Message Not Found.") }`는 람다가 예외를 **반환**해야 하는 자리에서 던지고 있고, "행 없음"은 재시도해도 성공 불가인데 5회 재시도 대상입니다.

**M10. 읽기 경로에 `@Transactional(readOnly = true)`가 없다**
`CveEventRepositoryImpl.kt:28-31,60-78`, `MeetingRepositoryImpl.kt:26-46,68-72,89-92`, `MeetingReminderRepositoryImpl.kt:14-26,47-51,71-86`, `StandupRepositoryImpl.kt:44-58,70-78,117-156`. 각 호출이 자기 트랜잭션을 열고, `StandupRepositoryImpl.findPendingDispatchesBefore`(`:117`)는 fetch join에 의존해 간신히 동작합니다 — 쿼리에서 fetch join을 하나만 빼도 즉시 `LazyInitializationException`입니다.

**M11. `@Transactional` 안에서 외부 I/O** — `CveSubscriptionSlashServiceImpl.kt:38,68,98`, `SlackMentionEventHandlerImpl.kt:33,61`
`commandExecutor.execute(...)` 전체를 감싸며 내부에서 Slack API를 칠 수 있습니다. `maximum-pool-size: 10`(`application-prod.yaml:16`) 커넥션을 네트워크 왕복 동안 점유합니다.

**M12. `ensureReminder`의 find-then-save 레이스** — `MeetingReminderRepositoryImpl.kt:28-45`
이 리포지토리의 다른 모든 동시성 지점은 CAS인데 여기만 check-then-act입니다. 두 인스턴스가 동시에 통과하면 unique 제약 위반으로 **`false` 반환이 아니라 예외**가 납니다. `AgendaDispatchRepositoryImpl`이 이미 쓰는 `INSERT IGNORE` 패턴으로 통일하세요.

**M13. Standup 세션 조회가 이중 JOIN FETCH 카테시안 곱 + 무제한** — `JpaStandupSessionRepository.kt:54-66, 83-97`
`dispatches`가 `Set`이라 `MultipleBagFetchException`은 피했지만 DB에서 오는 행 수는 세션당 **N×M**입니다(20명이면 400행). 더 심각한 건 **`Pageable`이 없어** 조건을 만족하는 모든 세션을 한 번에 로드한다는 점입니다. 같은 파일의 `findPendingBefore`는 `Pageable`을 받는데 여기만 빠져 있습니다.

**M14. CDC 역직렬화가 `status`/`version`을 항상 초기값으로 되돌린다** — `OutboxMessage.kt:54-67, 69-80`
`status`/`version`이 생성자 파라미터가 아니고 setter가 `protected`라 Jackson이 Debezium 페이로드 값을 주입할 수 없습니다. CDC로 복원된 모든 `OutboxMessage`는 `status="PENDING"`, `version=0`입니다. 커넥터가 INSERT만 흘리면 결과적으로 맞지만, UPDATE 이벤트가 섞이면 이미 SUCCESS 처리된 행이 PENDING으로 복원되어 **재발송**됩니다. **근본 원인은 JPA 엔티티를 CDC DTO로 겸용하는 것**이니 타입을 분리하세요.

### 보안 · 설정

**M15. dev 프로파일은 Slack 서명 검증이 꺼진 채로 뜬다** — `SlackRequestVerificationFilter.kt:33-40`
`signingSecret.isBlank()`면 경고 한 줄 남기고 통과시킵니다. `AppConfig.kt:42` 기본값이 `""`인데 **`application-dev.yaml`에는 `slack.app.api.signing-secret` 키가 아예 없습니다.**
> **정확한 범위**: prod는 안전합니다 — `application-prod.yaml:112`가 `${SLACK_SIGNING_SECRET}`를 기본값 없이 선언해 미설정 시 **기동이 실패**합니다. `local`(`:95`)·`slack-live`(`:66`)는 의도적 fail-open이며 주석에 명시돼 있습니다. **문제는 dev의 키 누락뿐입니다.**

**M16. 검증 필터가 경로 화이트리스트 opt-in** — `SlackRequestVerificationFilter.kt:25-26, 75-82`
목록에 없는 새 엔드포인트는 조용히 검증을 건너뜁니다. 기본값을 "검증한다"로 두세요. `"/api/slash/"`는 뒤에 슬래시가 있어 향후 `/api/slash`로 직접 매핑되는 핸들러가 생기면 빠져나갑니다.

**M17. 요청 본문을 크기 제한 없이 메모리에 적재** — `CachedBodyHttpServletRequest.kt:17`
`request.inputStream.readAllBytes()`에 상한이 없습니다. 이 필터는 **서명 검증 이전**에 실행되므로 인증되지 않은 대용량 POST로 힙을 압박할 수 있습니다.

**M18. Slack 재시도 중복 제거가 동작하지 않을 가능성** — `SlackRetryDeduplicator.kt:7-12`
fingerprint에 `timestamp`와 `signature`가 포함돼 있는데, Slack 재시도가 타임스탬프를 갱신하면 서명도 재계산되어 fingerprint가 매번 달라지고 **중복 판정이 성립하지 않습니다.**

> ⚠️ **이 항목은 X1(10.1)과 상호 배타적입니다.** 재시도가 새 타임스탬프를 쓰면 M18(중복 제거가 아예 동작 안 함), 같은 타임스탬프를 재사용하면 X1(실패한 요청이 조용히 200으로 마감). **어느 쪽이든 결함이며**, 코드만으로는 판정 불가하니 실제 재시도 페이로드를 한 번 로깅해 확인하세요.

어느 분기든 올바른 키는 `event_id`(Events API) / `trigger_id`(interaction)이고, 기록 시점은 **처리 성공 후**여야 합니다. 또한 in-memory라 멀티 레플리카에서 무의미하고, `:32-35`가 매 요청마다 맵 전체를 스캔합니다.

**M19. dev / local actuator가 `heapdump`까지 무인증 노출** — `application-dev.yaml:72`, `-local:72`
```yaml
include: health,loggers,metrics,mappings,threaddump,conditions,info,heapdump
```
Spring Security 의존성이 없어 `/api/actuator/heapdump`가 인증 없이 열립니다. 힙 덤프에는 `SLACK_API_TOKEN`, `SIDECAR_BEARER_SECRET`, DB 비밀번호가 들어 있습니다.
> prod(`:87-89`)는 `health,info,metrics`로 적절히 축소돼 있습니다.

**M20. `AppConfig`에 검증이 없고 인프라 enum에 의존** — `AppConfig.kt:3-5`
설정 클래스가 `dev.notypie.repository.cve.schema.*`(JPA enum)에 의존합니다. 또 `@Validated`/`@field:NotBlank`가 전혀 없고 모든 필드에 기본값이 있어(`:40` `token = ""`), 필수 값 누락이 기동이 아니라 **첫 API 호출 시점**에 드러납니다.
> 대비되는 좋은 사례: `McpServerConfiguration.kt:30-32`의 `require(...isNotBlank())`.

**M21. `relayTaskExecutor` 빈이 정의되어 있지 않다** — `SlackMessageRelayServiceImpl.kt:32`
메인 소스 어디에도 이 이름의 빈이 없어 타입 매칭으로 `AsyncConfig`의 `@Primary threadPoolTaskExecutor`가 주입됩니다. 즉 릴레이가 **모든 `@Async` 작업과 전역 풀을 공유**합니다. `Executor` 빈이 하나만 더 추가되면 조용히 바뀝니다.
관련: `AsyncConfig.kt:19-26`의 `queueCapacity = 10000`은 백프레셔를 차단하고, `setAwaitTerminationSeconds(10)`은 종료 시 큐에 남은 작업을 유실시킵니다.

**M22. `response_url` 응답의 실패 사유가 항상 비어 있고 바디를 읽기 전에 닫는다** — `ApplicationMessageDispatcher.kt:167-177, 198-206`
`result.close()`를 바디 읽기 전에 호출하고, `Response.message`는 **HTTP/2에서 항상 빈 문자열**입니다. Slack은 만료된 `response_url`에 HTTP 200 + 바디 에러를 주는 경우가 있는데 이 구현은 전부 "성공"으로 판정합니다. `response_url`의 **30분 만료 / 5회 제한을 추적하는 코드도 없습니다.**

**M23. CVE 소스 어댑터가 "결과 없음"과 "업스트림 실패"를 구분하지 못한다** — `SourceAdapter.kt:16-20`, `NvdCveSourceAdapter.kt:58-68`
`fetch`가 `List<RawSourceEvent>`를 반환해 429/403에서 `emptyList()`를 돌려줍니다.
> `CveCollector`가 claim-before-fetch + lookback 120분 중첩으로 유실은 완화합니다 — 설계가 방어하고 있습니다. **진짜 문제는 NVD 익명 호출이 30초당 5요청인데 `tick()`이 아무 페이싱 없이 연속 호출한다**는 점입니다. NVD 토픽이 6개 이상이면 매 틱마다 6번째 이후가 결정적으로 429를 맞고, `warn` 한 줄만 남아 아무도 눈치채지 못합니다. `application-prod.yaml:137`의 `NVD_API_KEY:` 기본값이 비어 있어 이게 기본 동작입니다.

**M24. `toMeetingDto()`가 `reason`을 통째로 버린다** — `MeetingSchema.kt:101`
```kotlin
reason = "",          // ← 항상 빈 문자열
```
바로 위 `toDomainEntity()`(`:87`)는 `reason = reason ?: ""`로 제대로 매핑합니다. DTO 경로만 하드코딩입니다.

**M25. `CommandDetailType.valueOf`가 알 수 없는 토큰에서 예외** — `SlackInteractionRequestParser.kt:52-58, 154-160`
`private_metadata` / `message.text`에서 뽑은 문자열을 그대로 `valueOf`에 넣습니다. enum 상수를 rename/삭제하면 **배포 이전에 만들어진 모든 버튼이 영구히 깨지고**, 핸들러 진입 전 예외라 사용자에게 피드백도 가지 않습니다. `?: NOTHING` fallback이 있는데 예외가 그 앞을 가로막습니다.

**M25. 버전·통계 축의 문서 드리프트 (Flyway·CI 축은 정확함)**

| 위치 | 기술 내용 | 실제 |
|---|---|---|
| `docs/wiki/decisions.md:87` | *"… Spring Boot 4.1, **Gradle 9.5**."* | 래퍼는 **9.7.1** (`gradle-wrapper.properties:3`). 같은 항목에 *"(근거 미기록 — 채워 넣을 것)"* TODO도 미해결 |
| `docs/wiki/testing-guide.md:37-38` | *"`StringSpec`은 **둘뿐이다**"* | 실제 **3개** — `EnvelopeCastGuardTest.kt` 누락 |
| `docs/wiki/testing-guide.md:3` | `updated: 2026-08-28` | 위키 나머지는 `2026-09-21` |
| `.github/AGENTS.md:37` | *"`some-with-excludes` … only exists in paths-filter v4, while the workflow pins `@v3`"* | 세 워크플로 모두 이미 `@v4` |
| `.github/AGENTS.md:120` | *"(`@v3` in test/deploy, `@v4` in security)"* | 전부 `@v4` |
| `.github/AGENTS.md:122` | `actions/github-script@v8` | 워크플로는 `@v9` |
| `.github/AGENTS.md:124` | *"the workflow default is `contents: read`"* | `lint.yaml`은 `permissions` 0개 (H20) |

`.github/AGENTS.md`의 4건이 특히 해롭습니다 — **에이전트가 이미 끝난 v4 업그레이드를 다시 시도하거나, 존재하지 않는 권한 기본값을 전제로 판단**합니다. AGENTS.md 트리가 에이전트의 1차 정보원이라는 점에서 우선 정리 대상입니다.

**M26. 액션이 전부 가변 메이저 태그로 고정돼 있다**

`checkout@v7`, `setup-java@v6`, `upload-artifact@v7`, `paths-filter@v4`, `github-script@v9`, `codeql-action@v4`, `build-push-action@v7` 등 14개. 커밋 SHA 핀 + `# vX.Y.Z` 주석이 공급망 관점에서 안전합니다(`docker/*`·`oracle-actions/*` 우선).

> **mis-bump 여부는 확인하지 못했습니다.** `gh api repos/<owner>/releases/latest`가 14개 전부 네트워크 불가로 응답해 "존재하지 않는 메이저"를 단정할 근거가 없습니다. 다만 `security_check.yaml:33`의 `predicate-quantifier: some-with-excludes`가 paths-filter v4 전용 기능이고 실사용 중이므로 **v4는 실존이 확인**됩니다. 나머지는 미검증 — 배포 전 한 번 확인하세요.

**M27. 모달 내부 `block_actions` 파싱 시 NPE / NumberFormatException** — `SlackInteractionRequestParser.kt:120-123, 145-151`
Slack의 `block_actions`는 모달(view) 안에서도 발생하며 그 경우 `channel`·`message`가 없고 `container.type == "view"`라 `message_ts`도 null입니다. 세 줄 모두 방어가 없습니다. CVE/standup 셋업 모달이 `multi_static_select`를 쓰므로 이론적 위험이 아닙니다.

---

## 6. 구조 · 리팩토링

### R1. `SlackApiEventConstructor` (753L, public 36개) — 분해안

`infrastructure/.../impl/command/SlackApiEventConstructor.kt`

한 클래스가 세 책임을 겸합니다.

| 라인 | 책임 | 메서드 |
|---|---|---|
| `:42-533` | 도메인 개념 → 이벤트 조립 (public API) | 16 |
| `:535-628` | 페이로드 종류별 헬퍼 | 3 |
| `:641-752` | Slack SDK 요청 빌드 + form 직렬화 (SDK·OkHttp 직접 결합) | 8 |

핵심 문제는 **`:641-752`가 Slack SDK/OkHttp에 결합**돼 있고 16개 public 메서드가 전부 이를 경유한다는 점입니다. `OpenViewEvent` 계열 6개(`:221-457`)는 SDK를 전혀 쓰지 않는데도 함께 묶여 있습니다.

**제안 — 3개 타입**
```
SlackRequestCodec.kt        (~120L)  현 :641-752  ← 유일한 SDK 결합 지점
SlackMessageEventFactory.kt (~180L)  현 :42-180, :459-533, :535-685
SlackViewEventFactory.kt    (~160L)  현 :221-457  ← SDK 의존 0
```

> `openCveTopicPickerModalRequest`(`:434-457`)가 이미 `OpenViewPayloadContents` 조립을 추출해 놓았는데, 그 패턴이 `:221-398`의 다섯 메서드에는 적용되지 않아 **동일한 12줄 블록이 5번 중복**되어 있습니다. 통일만 해도 ~60줄이 줄어듭니다.

**이행 순서** (각 단계가 독립 컴파일·테스트 통과)
1. `SlackRequestCodec` 추출 → public API 무변화, 기존 테스트 그대로 통과. **`buildRoutingText`(`:710-722`)의 단위 테스트를 처음으로 작성 가능**해집니다(현재 private).
2. `openViewEvent` private 헬퍼로 중복 5건 통합 — 순수 리팩터링.
3. `SlackViewEventFactory` 분리, 원본은 위임만.
4. `SlackMessageEventFactory` 분리.
5. 호출부 전환 — `SlackOutboundStager`(view만 사용) / `SlackOutboundRenderer`(message만 사용)가 각각 필요한 것만 주입받게. **이 시점에 `SlackOutboundStager`가 Slack SDK 전이 의존에서 해방되는 것이 이 분해의 핵심 이득입니다.**
6. `SlackApiEventConstructor` 삭제.

### R2. `ModalTemplateBuilder` (667L) — 분해안

성격이 다른 두 종류를 섞어 만듭니다.

| 라인 | 산출물 | 도구 | 메서드 |
|---|---|---|---|
| `:73-302`, `:607-666` | `LayoutBlocks` | `modalBlockBuilder` + DSL | 9 |
| `:304-605` | **`String` (모달 JSON)** | `modal {}` + `jsonMapper` | 6 |
| `:98-103` | — | `restRequester` (HTTP!) | H12 |

모달 뷰 8개가 **raw JSON 문자열**을 반환해 타입 안전성이 없고, 오타가 런타임(Slack 400)에야 드러납니다. 같은 패키지에 **`SlackViewDsl.kt`(178L)가 이미 있는데** 이들은 그것을 거치지 않습니다.

**제안 — 4개 타입 + 인터페이스 분리**
```
SlackTemplateBuilder.kt    인터페이스를 MessageTemplateBuilder + ModalViewJsonBuilder 로 분리
SlackMessageTemplateBuilder.kt (~230L)
MeetingListRenderer.kt         (~60L)   현 :212-245 (meetingListFormTemplate 전용)
SlackModalViewBuilder.kt       (~300L)  현 :304-605
UserProfileResolver.kt         (~40L)   현 :98-103 + 캐시/폴백  ← H12 해결
```

**함께 잡아야 할 것 — `privateMetadata` 인코딩 비대칭**
`listOf(...).joinToString(",")` 패턴이 6곳(`:314,363,399,436,471,582`)에 흩어져 있고, 이는 `SlackInteractionRequestParser.kt:51`의 `privateMetadata.split(",")`와 **암묵 결합된 와이어 포맷**입니다. 그런데 메시지 쪽 `buildRoutingText`(`SlackApiEventConstructor.kt:710`)는 **URL 인코딩을 하는데 모달 쪽은 하지 않습니다.** 같은 파서를 공유하면서 인코딩 규약이 다른 것은 실제 버그 소지입니다.
```kotlin
internal object RoutingMetadata {
    fun encode(vararg tokens: String) = tokens.joinToString(",") { URLEncoder.encode(it, UTF_8) }
    fun decode(raw: String) = raw.split(",").map { runCatching { URLDecoder.decode(it, UTF_8) }.getOrDefault(it) }
}
```
> ⚠️ 이 단계는 와이어 포맷을 바꾸므로 **단독 배포**하고, 배포 직후 기존 메시지의 버튼 동작을 확인하세요. 위 `getOrDefault(it)`가 하위호환 디코딩입니다.

**R1과 R2 중 R2를 먼저** 하세요. R2의 3단계(HTTP 분리)가 R1의 테스트 작성 비용을 크게 낮춥니다.

### R3. `Utils.kt`는 잡동사니가 아니라 이름이 잘못된 파일

`domain/.../common/Utils.kt`(444L)는 `ValidationBuilder` 하나(`:9-437`) + 진입점 2개뿐인 **응집도 높은 파일**입니다. 분해가 아니라 `Validation.kt`로 **rename**이 맞습니다.

다만 **사용률이 문제**입니다:

| 그룹 | 프로덕션 | 테스트 |
|---|---|---|
| `notBlank`, `shouldBeShorterThan`, `shouldBeLessThan(OrEqualTo)`, `shouldBeGreaterThan`, `shouldBeAfter`, `shouldHaveMin/MaxSize`, `shouldNotBeEmpty`, `shouldSatisfy` | ✅ 9종 | ✅ |
| `shouldBeLongerThan`, `shouldMatchPattern`×2, `shouldBeEmail`, `shouldBeBetween`, `shouldBePositive/Negative`, `shouldBeInFuture/InPast`, `shouldBeOneOf`, `and`, **`or`** 등 20종 | ❌ 0건 | ✅ |
| `addError`, `hasErrors`, `getErrors` | ❌ 0건 | ❌ 0건 |
| `validateAndReturn` (`:443`, internal) | ❌ 0건 | ✅ 21회 |

**30개 중 9개만 실제로 쓰이고**, 미사용 연산자 중 하나(`or`)는 **틀렸습니다**(C8) — 아무도 안 써서 안 잡힌 겁니다. `validateAndReturn`은 테스트를 위해서만 존재하는 프로덕션 코드이니 `testFixtures`로 옮기세요.

**권장**: `common/validation/` 아래 `ValidationBuilder.kt` / `StringRules.kt` / `NumberRules.kt` / `TimeRules.kt` / `CollectionRules.kt` / `PredicateRules.kt`로 나누고 **미사용 연산자 20여 종을 삭제**하세요. Kotlin 확장 함수는 같은 패키지면 import가 불필요해 호출부 변경이 전혀 없습니다.

### R4. 새 커맨드 타입 추가 시 shotgun surgery — 도메인 11파일 + 외부 5파일

`CveSubscribe` 기준 실측: 도메인 11개(`InboundInteraction.kt:139`, `SubmissionRouting.kt:36,50,102`, `CommandType.kt:57`, `ParsedSubmissions.kt:184`, 컨텍스트 2개, `CommandIntent.kt:82`, `ModalForm.kt:39`, 슬래시 커맨드), 외부 5개.

> **절반은 불가피합니다.** sealed 계층을 넓히면 exhaustive `when`이 컴파일 에러를 내는 건 설계 의도대로 동작하는 것이고 오히려 장점입니다. 줄일 수 있는 건 기계적 중복 2곳: `isSubmissionRoute`(`:36`)와 `detailType()`(`:50`)은 **같은 정보의 중복**이니 후자에서 파생시키고, `route()`(`:66-114`)의 7개 동형 분기는 R5로 해결됩니다.

### R5. `SubmissionContext` 하위 7개 클래스가 기계적 중복

`AddParticipantSubmissionContext.kt:9-27`, `RescheduleMeetingSubmissionContext.kt:9-27`, `StandupSetupSubmissionContext.kt:9-34`, `CveSubscriptionSubmissionContexts.kt:9-47` — **구조 100% 동일**. `DeclineReasonSubmissionContext.kt:24-54`와 `StandupAnswerSubmissionContext.kt:24-50`의 `NoticeTarget` 처리 8줄도 두 파일에 복제돼 있습니다.

**수정**: 베이스 클래스에 선언적 훅(`intentOf`, `noticeOf`)을 두면 각 구현체가 2~4줄로 줄고 R4의 추가 비용도 함께 내려갑니다.

### R6. `routingExtras` 인덱스 접근 + UUID 파싱 4중 복제

같은 5줄 관용구가 `AddParticipantContext.kt:32-36`, `RescheduleMeetingContext.kt:32-36`, `CancelMeetingContext.kt:29-37`, `StandupFillContext.kt:34-51`(2회)에 복사돼 있습니다.

> **아이러니**: 정확히 이 목적의 헬퍼가 **같은 패키지에 이미 있습니다** — `ParsedSubmissions.kt:12`의 `internal fun String.toUuidOrNull()`.

또 `routingExtras: List<String>`에 인덱스로 접근하는데 **주석이 유일한 스키마**입니다(`StandupFillContext.kt:32`). `MeetingApprovalResponseContext.kt:51`은 같은 `[0]`을 UUID가 아닌 `meetingTitle`로 읽습니다 — 같은 필드의 같은 인덱스가 컨텍스트마다 의미가 다릅니다.

### R7. 잔여 정리 항목

- **`/api/slash/task`가 빈 스텁** — `SlashCommandController.kt:46-52`. 파싱만 하고 아무것도 하지 않으며 미사용 변수 2개. 등록돼 있어 호출하면 200이 나갑니다.
- **`produces` 오용** — 7개 엔드포인트 전부 `produces = [APPLICATION_FORM_URLENCODED_VALUE]`. 이는 **응답** 타입 선언인데 핸들러는 `Unit`을 반환합니다. 의도한 건 `consumes`일 가능성이 높습니다.
- **URL verification 챌린지가 페이로드 전체를 에코** — `SlackEventController.kt:34`. `{"challenge": ...}`만 반환하면 됩니다.
- **반환값을 버리는 호출** — `SlackMentionEventHandlerImpl.kt:45`의 `resolveCommandType(...)`. `validateCommandType`으로 개명이 적절합니다.
- **`handleEvent`의 `runCatching`이 `Throwable`을 삼킨다** — `Command.kt:33-41`. `OutOfMemoryError`까지 "커맨드 실패"로 위장하고, `exception.toString()`이 그대로 `errorReason`으로 외부에 나갑니다. `try/catch (e: Exception)`로 좁히세요.
- **기능 비활성 시 무응답** — `CveSubscriptionSlashServiceImpl.kt:44-47,74-77,104-107`. 로그만 남겨 사용자에게는 먹통으로 보입니다. 가드 3곳이 복붙돼 있습니다.
- **미사용 파라미터** — `CveSubscriptionSlashService` 구현의 `headers`, `payload`가 세 메서드 모두에서 미사용.
- **침묵하는 기본값** — `StandupSchedulingService.kt:46-48`의 `ApplicationEventPublisher { }`(no-op). 같은 패턴인 `PollingMessageProcessor.kt:13`의 `appConfig = AppConfig()`는 **실제로 발현하는 버그**로 확인되어 X2로 승격했습니다(10.1 참조) — 빈 팩토리가 `AppConfig`를 넘기지 않아 운영 설정이 무시됩니다.
- **데드 코드** — `CommandBasicInfo.withNewKey()`, `ErrorResponse`, `CommandErrorCode` 3종, `RetryService.kt:41`, `common/PartitionKeyUtil.kt`(게다가 `:11`의 `abs(hashCode())`는 `Int.MIN_VALUE`에서 **음수 파티션 키**를 만듦), `common/JPAJsonConverter.kt`(`autoApply=true`인데 사용처 0 — 앞으로 `Map` 속성이 생기면 자동으로 끼어듦).
- **중복 인덱스** — `CveEventSchema.kt:25`의 `idx_cve_event_summary_status(summary_status)`는 아래 `(summary_status, created_at)`의 leftmost prefix로 완전히 커버됩니다.
- **`shouldBeShorterThan` 이름/동작 불일치** — `Utils.kt:81-92`가 `value.length > max`라 `length == max`를 허용합니다. `MAX_TITLE_LENGTH = 20`이면 20자 제목이 통과합니다.
- **시간대** — `OutboxMessage.kt:82-86`의 `toLocalDateTime()`이 `ZoneId.systemDefault()`를 씁니다. DB가 UTC이고 컨테이너 시간대가 다르면 어긋납니다.
- **`run` 스크립트** — `run:9`가 `set -e`만 사용합니다. `set -euo pipefail`로 강화하세요.
- **프로덕션 TODO/FIXME 13건** — 이 중 `SlackMentionEventHandlerImpl.kt:48-49`(H6), `KafkaErrorBroadcaster.kt:8`(C3), `MeetingRepositoryImpl.kt:48`은 주석이 아니라 실제 결함입니다.

---

## 7. 테스트 커버리지

### 7.1 정량 (파일명 매칭 기준, 인터페이스·DTO 포함이라 실제보다 보수적)

| 모듈 | main | 대응 테스트 | 비율 |
|---|---:|---:|---:|
| `domain` | 94 | 30 | ~32% |
| `application` | 80 | 40 | ~50% |
| `infrastructure` | 123 | 33 | ~27% |

### 7.2 가장 중요한 공백: **프로덕션이 쓰는 릴레이 경로가 미테스트**

```
application/src/test/.../service/relay/PollingMessageProcessorTest.kt      ✅ 존재
application/src/test/.../service/relay/DebeziumLogTailingProcessorTest.kt  ❌ 없음
```

| 프로파일 | `outbox-reading-strategy` | 프로세서 |
|---|---|---|
| prod (`:115`) / dev (`:96`) / local (`:104`) | `cdc` | **`DebeziumLogTailingProcessor`** |
| slack-live (`:69`) | `polling` | `PollingMessageProcessor` |

**테스트가 있는 쪽은 프로덕션이 쓰지 않는 경로이고, 프로덕션이 쓰는 CDC 경로는 테스트가 없습니다.** H3의 메시지 유실 시나리오가 방치된 이유이기도 합니다.

### 7.3 MariaDB 전용 네이티브 쿼리가 어디서도 실행 검증되지 않는다

인프라 테스트는 `@DataJpaTest` + 임베디드 H2(`MODE=MySQL` 아님)로 돕니다. 그런데 가장 위험한 쿼리들이 MariaDB 전용입니다:

| 쿼리 | 전용 요소 | 검증 |
|---|---|---|
| `MessageOutboxRepository.claimPending:31-38` | — | **전무** |
| `MessageOutboxRepository.findPendingMessages:15-22` | `LIMIT/OFFSET` | **전무** |
| `JpaCveEventRepository.insertIgnore:19-27` | `INSERT IGNORE`, `CURRENT_TIMESTAMP(6)` | mock만 |
| `JpaAgendaDispatchRepository` | `INSERT IGNORE` | **전무** |

**아웃박스 claim 로직 — 시스템에서 가장 중요한 동시성 지점 — 은 어떤 DB에서도 실행된 적이 없습니다.** `JpaCveEventRepositoryTest`가 `claimForSummary` 경합을 H2에서 잘 검증하는 좋은 선례를 만들었는데 outbox에는 적용되지 않았습니다.

**권장**: Testcontainers MariaDB 프로파일 추가. H8의 마이그레이션 검증 테스트와 인프라를 공유합니다.

### 7.4 테스트가 없는 주요 클래스

`ApplicationMessageDispatcher.kt`(206L, 모든 Slack 발송 경유점), `MessageOutboxRepository`, `StandupRepositoryImpl.kt`(165L), `Jpa*Repository` 다수, `MeetingReminderRepositoryImpl`, `repository/agent/*`·`repository/mcp/*`·`repository/authorization/*`(전 계층 무테스트), `StandupSlashServiceImpl`.

도메인 쪽 규칙 공백: `MeetingReminder` **전체**(테스트 파일 없음 — 자매 클래스 `SessionDispatch`는 `StandupSessionTest.kt:75-91`에서 검증됨), `Meeting.addParticipant` 중복(M2), `notBlank` 메시지 포맷(H7), `ValidationBuilder.or` 반례(C8), `CveOpsContext`/`RoleManagementContext`/`CancelMeetingContext` 등 참조 0건.

> `*Scheduler` 3종은 얇은 `@Scheduled` 위임이고 로직은 `*SchedulingService`에 있으며 그쪽엔 테스트가 있으므로 실질 위험은 낮습니다.

### 7.5 테스트 위생 — 대체로 양호하나 빈 스펙 2개

```bash
grep -rn "@Disabled|!given|!when|!should|\.only|\.config(enabled" */src/test   → 0건
grep -rn "Thread.sleep|delay\(" */src/test                                      → 0건
```
비활성화된 테스트와 타이밍 의존 flaky 테스트는 **정말로 0건**입니다. 검색에 걸린 `enabled = false` 6건은 전부 테스트 **데이터**(CVE 기능 플래그), `.only`는 `onlyTextTemplate` 메서드명입니다.

> ⚠️ **초기 "테스트 위생 완전 양호" 판정을 정정합니다.** 위 grep 패턴으로는 잡히지 않는 **빈 스펙 2개**가 있습니다 — `CommandDomainTest.kt:5-7`(본문 완전 공백), `DatabaseExceptionTest.kt:9-10`(`given` 본문 공백). 둘 다 단언 0개로 녹색 통과하며 파일 수를 부풀립니다. → H19

실측 규모: 테스트 블록 **1,593개**(domain 465 / infrastructure 689 / application 439), 스펙 파일 120개, `BehaviorSpec` 118 / `StringSpec` 3, `@SpringBootTest` 계열 7파일.

### 7.6 도구 공백

빌드에 **ktlint만** 있습니다. ktlint는 포매터이므로 이 리뷰에서 찾은 것 중 다음은 **하나도 못 잡습니다**: `TODO()` 프로덕션 빈 등록(C3), 빈 `@ExceptionHandler`(H5), 미사용 지역 변수·파라미터(R7).

**권장**: `detekt` + `jacoco`(모듈별 최소 커버리지). C1의 CI 게이트와 함께 도입하면 이 부류가 자동 차단됩니다. 의존성 취약점 스캔도 없습니다 — CodeQL은 코드만 보고 의존성 CVE는 보지 않습니다.

---

## 8. 검증했고 문제 없던 것

거짓 양성을 피하려 의심하고 확인했으나 **문제가 없었던** 항목입니다. 리뷰 커버리지의 증거이기도 합니다.

| 항목 | 확인 내용 | 결론 |
|---|---|---|
| 스케줄러 멀티 인스턴스 안전성 | claim-token CAS 7경로 (3.1 표) | ✅ 정석, 분산 락 불필요 |
| HMAC 서명 로직 | `SlackSignatureVerifier.kt:36-40,49-55` — `v0:{ts}:{body}`, raw body, `MessageDigest.isEqual`, 300초 | ✅ 정석 |
| MCP 토큰 검증 | `ScopedTurnTokenCodec.kt:48` 상수시간, 버전 태그, clock skew, 필터+contextExtractor 이중 방어 | ✅ 견고 |
| MCP 툴 권한 | `McpToolGate.kt:53-72` — 호출마다 역할 재해석(즉시 회수 가능), 감사 로그 | ✅ 의도적 설계 |
| Outbox 저장 원자성 | `SlackMessageRelayServiceImpl.kt:89-90` `BEFORE_COMMIT` | ✅ 정석 |
| Outbox 스키마 버전·dedup 키 | `OutboxSchemaVersion.kt`, `event_id` PK + `idempotency_key` | ✅ 존재 |
| 도메인 레이어 순수성 | `grep "^import org.springframework\|^import jakarta\." domain/src/main` → 0건 | ✅ 깨끗 |
| `!!` / `lateinit` / 캐스트 | 도메인 0건, `EnvelopeCastGuardTest`가 baseline=0 강제 | ✅ 우수 |
| `CommandExecutor` effect 라우팅 | `:38-53` `filterIsInstance` 대신 `when` + `error()` | ✅ 우수 |
| `SidecarAgentClient` 스트림 해제 | `:64` `response.body().use {}` — 조기 return 포함 항상 닫음 | ✅ 누수 없음 |
| alias 없는 JOIN FETCH | `JpaMeetingRepository.kt:19, 60, 141` | ✅ C5 해당 없음 |
| Jackson 2/3 혼용 | databind `tools.jackson`, 어노테이션 `com.fasterxml.jackson.annotation` — Jackson 3에서 정상 | ✅ 정상 |
| IDOR | actor가 Slack 서명 `user_id` 유래, 조회가 `actorId` 스코프 | ✅ 없음 |
| prod 필수 시크릿 | `application-prod.yaml:112,118` 기본값 없음 → 미설정 시 기동 실패 | ✅ fail-fast |
| MCP 시크릿 | `McpServerConfiguration.kt:30-32` `require(...)` | ✅ fail-fast |
| `.gitleaks.toml` | 정확히 1개 파일 경로만 허용(`:6`) | ✅ 과도하지 않음 |
| `k8s/secret.yaml` | 플레이스홀더만 | ✅ 안전 |
| `kotest-extensions-spring` 제거 | 루트 `subprojects`에서 빠졌으나 두 모듈 build 파일에 개별 선언(`application:33`, `infrastructure:41`) | ✅ 의도적 이동 |
| 테스트 위생 | 비활성 0건, `Thread.sleep` 0건 | ✅ 양호 |
| **Flyway·CI 문서 정합성** | `docs/wiki/decisions.md:78`이 Flyway 미사용을, `dev-environment.md:154`가 CI 공백을, `testing-guide.md:90`이 모듈 선택 실행을 정확히 기술. `log.md:8`에 2026-09-21 키 제거 기록. `grep -rn -i flyway application/src/main/resources/*.yaml` → 0건 | ✅ 이 축은 최신 |
| `domain` 컴파일 클래스패스 | `./gradlew :domain:dependencies --configuration compileClasspath` → kotest-bom / kotlin-stdlib / kotlin-reflect / kotlin-logging 뿐. Spring·Jackson·Hibernate 0건 | ✅ 가드 테스트 유효 |

> Flyway·CI 축의 문서는 특기할 만합니다. **위키가 자기 결함까지 정확히 기록**하고 있어, C1·H8·X3이 "몰라서 생긴 문제"가 아니라 **우선순위 문제**임을 뜻합니다.
>
> ⚠️ 다만 **버전·통계 축에는 드리프트가 있습니다** — 아래 M25 참조. "문서 전반이 정확하다"고 일반화하면 틀립니다.

### 8.1 오해하기 쉬운 지점 (지적 대상 아님)

- **`/mcp` 이중 스위치** — `spring.ai.mcp.server.enabled`와 `slack.app.mcp.enabled`가 별개이고 prod에서 둘 다 `${MCP_ENABLED}`로 연결됩니다(`:47, :151`). 전자만 켜면 이론상 `/mcp`가 인증 없이 뜨지만, **유일한 `@McpTool` 제공자인 `DomainReadTools`도 `McpServerConfiguration` 안에 있어** 툴이 하나도 등록되지 않은 빈 엔드포인트가 됩니다. 실질 위험 낮음. 다만 결합이 규약(같은 환경변수)에 의존하므로 두 스위치가 어긋나면 기동을 실패시키는 `require`를 추가하면 더 견고해집니다.
- **`apply(plugin = "org.springframework.boot")`가 `domain`에도 적용** — `build.gradle.kts`. 레이어링 위반처럼 보이지만 이 플러그인은 `bootJar` 태스크만 추가하며 컴파일 classpath에 Spring을 넣지 않습니다. `domain/build.gradle.kts`가 `bootJar.enabled = false`로 끄고 `dependencies {}`도 비어 있어 실제 오염은 없습니다.
- **`k8s/secret.yaml`에 `SLACK_SIGNING_SECRET` 없음** — 템플릿이 prod 요구사항과 어긋나지만, prod yaml이 기본값 없이 선언하므로 누락 시 **기동이 실패**합니다. 안전한 실패 모드이며 보안 구멍이 아니라 템플릿 최신화 누락입니다.
- **`CveCollector`의 claim-before-fetch** — fetch 전에 윈도우를 소진하는 것이 얼핏 유실처럼 보이나, NVD lookback 120분이 5분 틱을 중첩 커버하므로 설계가 방어하고 있습니다(M23 참조).

---

## 9. 정정 사항

초기 분석에서 제시했다가 **코드 확인 후 철회하거나 범위를 좁힌** 항목입니다.

**9.1 [철회] "스케줄러 8개에 분산 락이 없어 멀티 인스턴스에서 중복 실행된다"**
`grep -rni "shedlock|@SchedulerLock"` 0건을 근거로 심각한 결함으로 판단했으나 **틀렸습니다.** 이 코드베이스는 분산 락 대신 **per-row claim-token CAS**를 일관되게 씁니다(3.1 표). 분산 락보다 세밀하고 견고하며 락 서비스 의존성도 없습니다. → **`PollingMessageProcessor` 한 곳에만 해당**하며 H1로 범위를 좁혔습니다.

**9.2 [범위 축소] "Slack 서명 검증이 fail-open이다"**
fail-open 코드는 존재하지만 프로파일별 동작이 다릅니다. prod는 `${SLACK_SIGNING_SECRET}`(기본값 없음)이라 **미설정 시 기동 실패**로 안전하고, local/slack-live는 주석에 명시된 의도적 fail-open입니다. → 실제 문제는 **dev 프로파일의 키 누락**이며 M15로 재기재했습니다.

**9.3 [범위 축소] "Outbox claim 버그가 프로덕션에 영향"**
H1의 결함은 실재하나 `PollingMessageProcessor`는 `polling` 전략에서만 활성화됩니다. prod/dev/local은 `cdc`이므로 **현 시점 프로덕션 영향 없음**입니다(단 `AppConfig.kt:34` 기본값이 `POLLING`).

**9.4 [철회] "`Utils.kt` 444줄은 잡동사니 헬퍼 모음이니 분해해야 한다"**
열어 보니 `ValidationBuilder` 하나 + 진입점 2개인 **응집도 높은 파일**이었습니다. 분해가 아니라 rename이 맞습니다(R3). 파일명만으로 판단하면 틀리는 사례였습니다.

**9.5 [철회] "Jackson 2와 3이 혼용되고 있다"**
`OutboxMessage.kt:3`의 `com.fasterxml.jackson.annotation.JsonProperty`가 잔재로 보였으나, **Jackson 3는 databind만 `tools.jackson`으로 옮기고 어노테이션은 유지**합니다. 정상입니다.

**9.6 [범위 축소] "Flyway가 제거됐으니 문서에 stale한 서술이 남아 있을 것"**
Flyway·CI 축에서는 **정반대였습니다.** `docs/wiki/decisions.md:78`이 Flyway 미사용을 의도적 결정으로 기록하고, `log.md:8`에 2026-09-21자 키 제거 이력이 있으며, `dev-environment.md:154`는 CI 공백까지 정확히 적어 두었습니다. → H8을 "관리 주체 실종"이 아니라 **"의도적 결정의 리스크가 미완화"** 로 재서술했습니다.

**다만 "문서 전반에 드리프트가 없다"는 제 중간 결론은 과했고, 정정합니다.** 버전·통계 축에는 실재합니다(M25): `decisions.md:87`이 Gradle **9.5**라고 쓰지만 래퍼는 **9.7.1**이고, `testing-guide.md:37-38`은 `StringSpec`이 2개라 하지만 실제 3개이며, `.github/AGENTS.md:37/120/122/124`는 이미 끝난 v3→v4 업그레이드를 미완으로 기술하고 없는 권한 기본값을 주장합니다.

또 **C6이 역방향 드리프트**를 만듭니다: `AGENTS.md:89-91`과 `README.md:20`의 `-Xjsr305=strict`·`param-property`·"Adoptium toolchain"은 실측 결과 **어느 모듈에도 적용되지 않습니다.** 문서가 틀린 게 아니라 **빌드가 문서를 따라가지 못하는** 경우입니다.

**9.6.1 [정정] "테스트 위생 완전 양호"**
비활성화 테스트 0건·`Thread.sleep` 0건은 맞지만, 해당 grep 패턴으로는 잡히지 않는 **빈 스펙 2개**를 놓쳤습니다(H19). "위생 양호"를 "대체로 양호하나 빈 스펙 2개"로 수정했습니다.

**9.7 [갱신] 기준 커밋 변경에 따른 근거 갱신**
리뷰 도중 `main`(`90c747a`)으로 전환되어 다음을 재검증했습니다.
- CI 트리거: **변경 없음**(액션 메이저 버전만 상승). C1 유효하며, `deploy_action.yaml:129`의 `-x test` 발견으로 **오히려 강화**됐습니다.
- `application-local.yaml`: `flyway.enabled: false` 블록과 `stand-alone: true`가 **삭제**됐습니다. H8의 인용을 `application-slack-live.yaml:9-10`으로 교체했고, Flyway가 의존성·설정 어디에도 없음을 `grep`으로 재확인했습니다.
- `build.gradle.kts`: `by extra` → `extra["x"] as String`(Gradle 10 대비), 버전 상승, `kotest-extensions-spring`의 모듈 이동. 마지막 건은 두 모듈 build 파일에 개별 선언돼 있어 **문제 없음**을 확인했습니다.

---

## 10. Codex 독립 리뷰

> 위 분석에 앵커링되지 않도록 **findings를 공유하지 않은 상태**에서 `omc ask codex`(gpt-6-astra, reasoning effort high)로 동일 코드베이스를 독립 리뷰하도록 했습니다. 산출물: `.omc/artifacts/ask/codex-final-independent-senior-engineer-code-review-*.md`

Codex는 15건(High 7 / Medium 7 / Low 1)을 보고했습니다. **대부분이 위 분석과 독립적으로 일치**했고(C1·C2·C5·H1·H5·H14·C3·R7), 아래 4건은 **제가 놓친 신규 발견**입니다. 전부 직접 재검증했습니다.

### 10.1 Codex가 새로 찾은 것 (검증 완료)

**X1 (High). `SlackRetryDeduplicator`가 처리 *전에* fingerprint를 기록하고 실패 시 제거하지 않는다**

`SlackRetryDeduplicator.kt:24-30` + `SlackRequestVerificationFilter.kt:66-70`
```kotlin
val previous = seen.putIfAbsent(fingerprint, now)   // ← doFilter 이전에 기록
return previous != null && retryNum != null
```
```kotlin
if (retryDeduplicator.isDuplicateRetry(...)) {
    response.status = HttpServletResponse.SC_OK        // ← 컨트롤러 호출 없이 200
    return
}
filterChain.doFilter(cachedRequest, response)          // ← 실패해도 맵에서 제거되지 않음
```
최초 요청이 DB 오류로 실패해도 fingerprint는 맵에 남습니다. 동일 fingerprint의 Slack 재시도가 도착하면 **컨트롤러를 호출하지 않고 HTTP 200**을 반환합니다 → 요청은 처리되지 않았는데 Slack에는 성공으로 보입니다. H5(빈 예외 핸들러)와 결합하면 실패가 **두 겹으로** 은폐됩니다.

> ⚠️ **X1과 M18은 상호 배타적입니다.** fingerprint에 `timestamp`·`signature`가 포함되므로(`SlackRetryDeduplicator.kt:7-12`):
> - Slack 재시도가 **새 타임스탬프·서명**을 보낸다면 → fingerprint 불일치 → 중복 제거가 **한 번도 동작하지 않음**(M18, 데드 코드)
> - Slack 재시도가 **동일 타임스탬프·서명**을 재사용한다면 → fingerprint 일치 → **X1**(실패 요청이 조용히 200으로 마감)
>
> 어느 쪽이든 결함입니다. 코드만으로는 판정할 수 없고 Slack의 실제 재시도 헤더 동작에 달려 있으니, **실제 재시도 페이로드를 한 번 로깅해 확인한 뒤** 방향을 정하세요. 어느 경우든 올바른 키는 `event_id`(Events API) / `trigger_id`(interaction)이고, 기록 시점은 **처리 성공 후**여야 합니다.

**X2 (Medium). `PollingMessageProcessor` 빈이 바인딩된 `AppConfig`를 받지 못한다**

`application/.../configurations/ConsumerConfig.kt:32-41`
```kotlin
fun poolingOutboxMessageProcessor(
    outboxRepository: MessageOutboxRepository,
    messageRelayService: SlackMessageRelayServiceImpl,
) = PollingMessageProcessor(
    outboxRepository = outboxRepository,
    messageRelayService = messageRelayService,
)   // ← appConfig, clock 미전달
```
`PollingMessageProcessor.kt:12-13`의 생성자 기본값 `appConfig: AppConfig = AppConfig()`, `clock = Clock.systemDefaultZone()`이 그대로 쓰입니다. 따라서 운영자가 `slack.app.outbox.polling.batch-size` / `stuck-in-progress-seconds`를 조정해도 **조용히 무시**되고 항상 기본값(100 / 300초)으로 동작합니다.
→ R7의 "침묵하는 기본값"이 단순 스멜이 아니라 **실제 설정 무효화 버그**임이 확인됐습니다. 기본값을 제거하고 주입을 강제하세요.

**X3 (Medium). CI가 변경된 모듈의 테스트만 실행한다**

`.github/workflows/simple_test_action.yaml:54-75`
```bash
if [ "${{ steps.changes.outputs.domain }}" == "true" ]; then
  modules="$modules :domain:test"
fi
```
의존 방향은 **application → infrastructure → domain**입니다. 그런데 `domain`만 변경하면 `:domain:test`만 돕니다. **도메인 계약 변경이 application을 깨뜨려도 잡히지 않습니다** — 이 리뷰의 M1(`Meeting` 재구성), H10(`errorCode` 보존), H9(`else` 제거) 같은 수정이 정확히 이 부류입니다.
**수정**: `domain` 변경 → 3개 모듈 전체, `infrastructure` 변경 → infrastructure + application. 이 규모라면 PR마다 전체 `build`가 더 단순하고 안전합니다.

**X4 (Low→Medium). 배포 생성 시 `required_contexts: []`**

`.github/workflows/deploy_action.yaml:98`
```yaml
required_contexts: []
```
GitHub Deployment을 만들 때 required status check를 **명시적으로 비웁니다.** C1과 겹쳐 "어떤 검사도 배포를 막지 않는" 상태를 완성합니다.

### 10.2 Codex가 제기한 반론 (수용 및 반영)

**(a) Kafka 오토커밋 유실 — 제 서술이 과했습니다.**
> *"동기 listener이므로 이 설정 하나만으로 '처리 중 백그라운드 commit으로 유실된다'고 판정하지 않았습니다."*

타당한 지적입니다. `@KafkaListener`가 동기이므로 오토커밋이 미처리 레코드를 추월하는 창은 제가 시사한 것보다 좁습니다(컨테이너의 pause/poll 동작에 의존). **H3을 그에 맞게 수정**했습니다 — 확정 결함은 **4개 스킵 지점 + DLT 부재**이고, 크래시 유실은 조건부 위험으로 격하했습니다.

**(b) Outbox 인덱스 — 마이그레이션을 읽지 않아 확정 못 함.**
> *"prod는 `ddl-auto: none`이고 migration을 읽지 않았으므로 운영 DB에 해당 인덱스가 없다는 결함으로 확정하지 않았습니다."*

적절한 유보였습니다. **제가 17개 마이그레이션을 전수 확인해 해소했습니다**:
```bash
$ grep -rn -i "index" application/src/main/resources/db/migration/*.sql | grep -i outbox
V1__outbox_pk_event_id.sql:35:CREATE INDEX IF NOT EXISTS idx_outbox_idempotency_key ON outbox_message (idempotency_key);
```
**`(status, created_at)` / `(status, updated_at)` 인덱스를 만드는 마이그레이션은 없습니다.** 엔티티 매핑과 마이그레이션이 일치하며 둘 다 해당 인덱스가 없으므로 **H4는 확정**입니다.

덧붙여 `V1__outbox_pk_event_id.sql:20-22`가 H8(스키마 관리 공백)의 가장 직접적인 증거입니다:
> `-- For environments with auto-ddl enabled (dev/local) this migration is applied automatically by Hibernate. In prod, execute manually — schema auto-migration is disabled.`

**(c) 심각도 견해차.** Codex는 `RetryService` 레이스와 JOIN FETCH 절단을 **Medium**으로, 저와 인프라 레인은 **Critical**로 평가했습니다. 전자는 "정책 오적용"의 실제 피해 범위를, 후자는 "사용자에게 보이는 오답"의 가시성을 각각 강조한 차이입니다. **본 문서는 Critical을 유지**합니다 — 공유 가변 상태 경합은 재현·진단이 매우 어렵고, 참석자 수 오표시는 사용자가 즉시 보는 오류이기 때문입니다.

**(d) 새로운 세부 지적 — `maxRetries` 의미 불일치.**
`RetryService.kt:26`이 `maxAttempts`를 그대로 `.maxRetries(maxAttempts)`에 넘깁니다. Spring의 `maxRetries`는 **최초 실행 이후의 재시도 횟수**이므로 총 실행 횟수는 `maxAttempts + 1`입니다. `RetryOptions.MAX_ATTEMPTS(default = 3L)`이면 **4회 실행**됩니다. 총 시도 횟수를 의도했다면 `maxAttempts - 1`로 변환해야 합니다. → C2 수정 시 함께 처리하세요.

### 10.3 Codex가 "올바르다"고 확인한 것

서명 계산(raw body HMAC, 상수시간 비교), `BEFORE_COMMIT` 아웃박스 저장, executor 직접 제출(같은 빈 내부 `@Async` 회피), `RestClientRequester`의 호출별 request spec 생성, `SidecarAgentClient`의 `use` 기반 스트림 해제 및 terminal event 없는 EOF 실패 처리, 아웃박스 경로에서 modal open을 금지하고 즉시 전송으로 분리한 설계, 회의 취소·일정 변경 SQL의 requester + 취소 상태 동시 조건.

`ApplicationMessageDispatcher`가 response를 닫은 뒤 상태 코드만 읽는 부분은 **closed body 접근 오류가 아니라고** 명시적으로 오탐 제외했습니다 — M22는 "닫힌 바디 접근"이 아니라 **"바디를 읽지 않고 버린다 + HTTP/2에서 `Response.message`가 빈 문자열"** 이라는 별개 논점이므로 유지합니다.

---

## 11. 실행 우선순위

> ⚠️ **이 표는 12장의 교차 검증으로 대체되었습니다.** 이력 보존을 위해 남겨 둡니다. 실제 작업 순서는 [12.3](#123-최종-우선순위)을 따르세요.

| 순서 | 항목 | 근거 | 규모 |
|---|---|---|---|
| 1 | **C1 + X3 + X4** CI 게이트 · `-x test` · 모듈 선택 실행 · `required_contexts` | 이것이 없으면 이후 모든 수정이 검증 없이 프로덕션에 나갑니다 | yaml ~15줄 |
| 2 | **C3** `KafkaErrorBroadcaster` | 1줄, 에러 경로의 런타임 예외 제거 | 1줄 |
| 3 | **C4** `"kafka:port"` | 2줄, prod 릴레이 동작의 전제 | 2줄 |
| 4 | **H5** 빈 `@ExceptionHandler` | 장애 은폐 제거 | 8줄 |
| 5 | **H6** `"null"` 문자열 | 데이터 오염 중단 | 2줄 |
| 6 | **H7** `notBlank` 메시지 | 사용자 노출 버그, 1줄 | 1줄 |
| 6.3 | **H17** `run` 스크립트 `local` 2줄 제거 | 기본 사용법이 깨져 있음. 5분 | 2줄 |
| 6.5 | **X2** `PollingMessageProcessor` 설정 주입 | 운영 설정이 조용히 무시되는 상태 | 3줄 |
| 6.7 | **H20** `lint.yaml` `permissions` + `check-changes`의 `pull-requests: read` | 무음 배포 스킵 방지 | 4줄 |
| 7 | **C2** `RetryService` 레이스 (+ `maxRetries` off-by-one) | 한 파일, 명백한 공유 상태 경합 | ~20줄 |
| 7.5 | **X1 / M18** 재시도 중복 제거 | 먼저 Slack 재시도 헤더를 로깅해 어느 분기인지 확정 | 조사 후 결정 |
| 8 | **C5** JOIN FETCH 절단 | 사용자에게 보이는 오답. **테스트 선행 필수** | 쿼리 2개 |
| 9 | **H2 + M22** Slack 429 / `response_url` | 메시지 유실 직결 | ~40줄 |
| 10 | **H3** Kafka 수동 ack + DLT | 아웃박스 신뢰성의 근간 | 설정 + 핸들러 |
| 11 | **H8** 마이그레이션 수단 복구 | **H4·H15의 선행 조건** | Flyway baseline |
| 12 | **H4 + H15** 인덱스 + 보존 정책 | `V18`에 묶어서 | 마이그레이션 1개 |
| 13 | **C8** `ValidationBuilder.or` | 현재 미사용이나 다음 사용자가 조용히 당함 | 삭제 또는 재설계 |
| 14 | **H9 + H10** `else` 제거, `errorCode` 보존 | 향후 모든 기능 추가의 안전망 | ~30줄 |
| 15 | **M15 + M19** dev 서명 검증, actuator 축소 | 각 1줄 | 2줄 |
| 15.5 | **C7 + H21** k8s probe·resources 추가와 Ready 판정 수정 | 같은 실패 모드 — 한 PR로 | 매니페스트 + yaml |
| 16 | **C6** 툴체인·컴파일러 옵션을 `subprojects`로 이동 | **단독 PR 필수** — `-Xjsr305=strict`가 켜지며 숨어 있던 에러가 한꺼번에 드러남 | ~15줄 + 후속 수정 |
| 17 | **7.6** detekt + JaCoCo | 2·4·R7 부류를 자동 차단 | 빌드 설정 |
| 18 | **M25** 문서 드리프트 한 패스 | `.github/AGENTS.md:37/120/122/124`가 에이전트를 능동적으로 오도 | 문서 |
| 19 | **R2 → R1** God-class 분해 | 기능 추가 속도에 직접 영향. R2 먼저 | 점진적 |

> **C6의 순서에 대하여**: 심각도는 Critical이지만 **의도적으로 뒤에 배치**했습니다. `-Xjsr305=strict`를 켜면 컴파일 에러가 다수 발생할 가능성이 높아, 앞선 기능 수정들과 섞이면 리뷰가 불가능해집니다. C1(CI 게이트)이 먼저 자리잡은 뒤 단독 PR로 진행하세요.

**1~6번은 총 20줄 미만**이며 즉시 적용 가능합니다.

---

## 12. 교차 검증 종합 · 최종 우선순위 (2026-09-22)

> **방법**: 이 문서(1~11장)를 입력으로, ① Codex(gpt-6-astra, reasoning high)가 전 항목을 소스와 대조·심각도 재판정·누락 결함 탐색, ② Claude 검증 에이전트 3개(C1–C8 / H1–H11·X1–X4 / H12–H21·주요 M)가 인용 라인을 직접 열어 재검증, ③ "즉시 수정 8건"과 신규 결함 N1–N3는 메인 세션이 직접 재확인했습니다.
> 산출물: `.omc/artifacts/ask/codex-you-are-a-senior-reviewer-auditing-a-code-review-document-no-2026-09-22T02-26-40-509Z.md`
>
> **결론**: 결함이 실재하지 않는 항목은 3건(H18·M14·H4-OFFSET)뿐이지만, **심각도 과대평가**와 **수정안 자체가 틀린 항목**이 여럿입니다. 수정 스니펫을 그대로 적용하면 안 되는 항목 5건(C3·H3·H4-UNIQUE·H14·H18)은 12.1에 명시했습니다.

### 12.1 정정표 — 1~11장에서 고쳐 읽어야 할 것

| 항목 | 판정 | 정정 내용 | 근거 |
|---|---|---|---|
| **H2** | 방향 반전 | HTTP 429는 SDK `MethodsClientImpl`이 `SlackApiException`을 던져 **이미 재시도됨**(단 `Retry-After` 무시, ≤10s 백오프 3회). `invalid_auth`/`channel_not_found`는 `ok=false`로 반환되어 재시도 **안 됨**(올바른 동작). 진짜 결함: `Retry-After` 미반영, 일시적 `ok=false`(`internal_error` 등)의 즉시 FAILURE, `response_url` HTTP 실패 비재시도 | `ApplicationMessageDispatcher.kt:95,184-196`, SDK 클래스에 `retryAfterSeconds` 필드 존재 |
| **H18** | **오탐** | `api` 노출은 `infrastructure/AGENTS.md`에 의도로 문서화되어 있고 application이 `@KafkaListener`·`KafkaTemplate`을 직접 사용(`KafkaConsumerConfiguration.kt`, `ConsumerConfig.kt`, `DebeziumLogTailingProcessor.kt`). 제안대로 `api→implementation`이면 **컴파일 파손**. JPA 타입은 application에서 0건이므로 `data-jpa`만 내리는 것은 안전 | `:application:compileClasspath` 실측 |
| **M14** | **오탐** | `protected` setter도 Jackson 기본 setter 가시성(`ANY`)으로 주입 가능(바이트코드 확인). `DebeziumLogTailingProcessor.kt:41`의 PENDING 가드가 UPDATE 이벤트를 차단. 실제 갭은 `toOutboxMessage()` 테스트 0건 | |
| **H4** | 부분 오탐 | (2) OFFSET 행 유실은 **오탐** — 유일 호출부 `PollingMessageProcessor.kt:36`이 `offset=0` 고정, 루프 없음(`:34` 주석). `offset` 파라미터 **삭제**가 맞는 정리. `idempotency_key` UNIQUE 제안은 **역으로 버그** — `V1__outbox_pk_event_id.sql:5`·outbox `AGENTS.md`가 "한 커맨드 → 다중 행"을 정상으로 명시. (1) 인덱스·(3) 보존 정책은 유지 | |
| **C3** | 심각도 하향 + 수정안 위험 | `TODO()`는 사실이나 `broadcastError` **호출자 0건**(휴면 지뢰). `kafkaErrorBroadcaster`/`stdoutErrorBroadcaster`는 `Conditions.kt:32-44`로 **상호 배타** 설정 클래스 소속 → "빈을 제거하면 stdout fallback"은 틀림. 제거하면 prod(`event-publisher: kafka`)의 `ErrorBroadcaster` 빈이 **0개**가 됨. 올바른 조치: `KafkaEventPublisherConfig`가 `StdoutErrorBroadcaster`를 반환 | `ConsumerConfig.kt:71-86` |
| **H3** | 범위 축소 + 수정안 위험 | `after==null`(delete 이벤트)·non-PENDING(update 이벤트)은 **정상 스킵**. 확정 결함은 파싱 실패 스킵 + 재전달 시 현재 DB 상태 미확인 + DLT 부재. `:58-64`가 발송 예외를 실패 이벤트로 바꿔 정상 반환하므로 auto-commit만 꺼서는 해결 안 됨. 제안 패치는 리스너 `consume(envelope)`에 `Acknowledgment` 파라미터가 없어 **MANUAL로 바꾸면 커밋이 영영 안 일어남**. `KafkaErrorHandler`(null-record 처리)를 `DefaultErrorHandler`로 **교체**(병치 금지) 필요 | `KafkaConsumerConfiguration.kt:66-75,110-134` |
| **X1 / M18** | **M18이 정답** | Slack 재시도는 새 HTTP 전송이라 `X-Slack-Request-Timestamp`가 재전송 시점, 서명은 `v0:{ts}:{body}`로 재계산 → fingerprint 절대 불일치. 방증: 필터가 dedup **앞에서** 300초 tolerance를 검증하는데 Slack 재시도는 최대 1시간까지 감. ⇒ dedup은 **데드 코드**, 추가로 `seen` 맵 무한 증식 + 요청당 전수 스캔(`:32-35`). X1은 도달 불가 → Low | `SlackRetryDeduplicator.kt:6-12`, `SlackRequestVerificationFilter.kt:44-70` |
| **H13** | 범위 축소 | Authorization 이중 헤더: 프로덕션 생성부 2곳(`RestClientConfiguration.kt:12`, `ModalTemplateBuilder.kt:28`) 모두 `authorization` 미전달 → 미발현. 상태 코드: `bodyOrThrow`가 원 예외를 cause로 보존 → "구분 불가"는 틀림. **타임아웃 부재만 실재.** 수정은 `JdkClientHttpRequestFactory` 수동 조립보다 Boot 4 자동설정 `RestClient.Builder` 빈 주입(`spring.http.client.*` 적용) | |
| **H20** | 사유 정정 | `dorny/paths-filter`는 `pull_request`에서 `listFiles` 401 시 `setFailed`로 **스텝 실패(빨강)**. "조용히 false → 무음 스킵 → 녹색"은 틀림. `lint.yaml` permissions 부재, `.github/AGENTS.md:124` 허위 기술은 유지 | |
| **C6** | **리뷰보다 나쁨** | 재측정 결과 세 모듈 `jvmTarget=JVM_21` — 부록의 `JVM_25` 인용이 오류. 툴체인 미적용으로 로컬은 Gradle 데몬 JDK(Temurin 21)로 컴파일, CI는 `setup-java 25` → **재현 불가 빌드가 현재 진행형**. 이전 시 `-Xjvm-default=all`은 Kotlin 2.2+ deprecated → `-jvm-default=enable`. Codex 주장: Kotlin 2.4에서 `param-property`는 기본 동작(미검증) | probe 재실행 |
| **C8** | 수정안 위험 | `and`까지 삭제 권고는 `domain/.../common/AGENTS.md` 문서와 `ValidationBuilderTest.kt:105` 파손. **`or`만** 고치거나 삭제. `@JvmInline value class Check` 스케치는 `Field<T>` 체이닝과 연결되지 않아 드롭인 불가 | |
| **H21** | **리뷰보다 심각** | 이중 슬래시 이전에 **`/api/slack` 접두 자체가 어디에도 없음** — `application-prod.yaml:73-78` context-path 없음, actuator base-path `/actuator`(`configmap.yaml:7`), `k8s/route/ingress.yaml:20` `path: /` rewrite 없음. 헬스체크 URL 전체가 틀림. `:256`의 `...`는 `echo` 종료코드 0이라 `set -e`에도 안 걸림 | |
| **H8** | 의존 관계 정정 | H4·H15의 **필수 선행조건 아님** — 검증된 수동 `V18`로 인덱스 추가 가능. Flyway 도입은 `decisions.md:78` 결정 번복이므로 별도 의사결정. 임시 조치(dev `ddl-auto: validate`)는 결정을 건드리지 않음 | |
| **H1** | 맥락 보강 | outbox `AGENTS.md`에 *"accepted on the current single-instance deployment; a multi-poller deployment needs a claim that returns the winning ids"* 로 **수용된 트레이드오프**로 기록됨. 결함은 실재, 잠복(prod는 cdc) | |
| **H6** | 수정안 주의 | `body.event.channel`은 채널 **ID**이지 이름이 아님 → `channelName`에 담으면 같은 혼동 재발. 필드 제거 또는 `channelId`로 개명. 소비처 `AgentConverseService.kt:115`가 AI 컨텍스트에 `"null"`을 넣고 있음 | |
| **H9** | 개수 정정 | `CommandDetailType` 상수는 30이 아니라 **31개**. submission은 `SubmissionRouting.kt:66`에서 별도 처리되고 interaction에 `EmptyContext`가 오면 `Command.kt:52`에서 실패 결과가 되므로 "항상 순수 no-op"은 아님 | |
| **H10** | 범위 축소 | `errorCode.message`는 `RuntimeException(message)`로 보존됨. 유실은 코드 식별자와 `statusCode`뿐 — 데드 설계이지 런타임 오동작 아님 → Medium | |
| **H11** | 누락 추가 | 세 번째 경로: `SlackInteractionHandlerImpl.kt:107` `actorRole = UserRole.USER` **하드코딩** — 버튼/모달 레인은 역할을 안 보는 게 아니라 USER로 단정. 게이트 도입 시 함께 처리 | |
| **H14** | 부수 정정 | "개행 제거로 JSON 파싱이 깨진다"는 틀림 — `ofLines()` 출력의 재결합이고 JSON 문자열에 raw newline은 불가. O(n²)·크기 상한 없음은 유지. `sendAsync().orTimeout()`만으로는 body 소비를 제한 못 함 — 스트림 close 워치독 필요 | |
| **M1** | 하위 근거 2개 무효 | ①은 `MeetingRepositoryImpl.kt:19` `@Transactional`이 롤백 → "행 잔존" 아님. ②는 `:106` startAt 선가드로 시각 불변식 미발화. ③만 유효. 결론(생성 정책과 상태 불변식 분리)은 유지 | |
| **M8** | 실증 경로 없음 | `clearAutomatically` 0건은 사실(건수는 18이 아니라 **31**). 그러나 `CveSummaryWorker.tick()`에 tx가 없고 claim/markDone이 각각 독립 `@Transactional`이라 같은 1차 캐시를 공유하지 않음. 일괄 `clearAutomatically`는 회귀 위험 → Low | |
| **M27** | 잠복으로 재서술 | `SlackViewDsl.kt:70-74`의 모든 인터랙티브 요소가 `dispatch_action` 없는 `InputBuilder` 안 → 모달 select는 `block_actions`를 발화시키지 않음. 방어 부재는 실재하나 현재 미발현 | |
| **7.6** | 모순 | "의존성 취약점 스캔 없음"은 `security_check.yaml:123`의 `dependency-review`와 모순 | |
| **M25** | 번호 중복 | Medium 목록에서 M25가 두 번 사용됨(`valueOf` / 문서 드리프트) | |

심각도 조정 요약: **하향** C2(→High), C3(→Medium), C8(→Medium), H4(→Medium), H7(→Medium), H10(→Medium), H13(→Medium), H15(→Medium), H17(→Medium, `-m` + local/dev 한정), H18(→결함 아님), H19(→Low), H20(→Low), X1(→Low). **상향** M18(→Medium), H21(→High 유지, 범위 확대), C6(Critical 유지, 실측 강화).

### 12.2 이 문서가 놓친 결함 (Codex 발견 → 메인 세션 소스 재확인)

| ID | 심각도 | 결함 | 근거 |
|---|---|---|---|
| **N1** | **High** | **Daily agenda 발송 실패가 당일 영구 누락된다.** `agendaDispatchRepository.claim(today)`(`DailyAgendaSchedulingService.kt:52`)이 `AgendaDispatchRepositoryImpl.kt:11`의 **별도 `@Transactional`에서 먼저 커밋**되고, outbox 저장 tx는 `:65`에서 시작. 그 사이 조회 실패·프로세스 종료·outbox rollback이 나면 `:80`에서 `log.error`만 남고 다음 tick은 claim 실패로 종료. 3.1·8장의 "스케줄러 전부 안전" 판정의 예외 | `sendDailyAgenda()`에 tx 없음 |
| **N2** | **High** | **일정 변경이 `endAt < startAt` 상태를 저장한다.** `JpaMeetingRepository.rescheduleMeeting`(`:121-134`)은 `SET m.startAt = :newStartAt`만 실행, `MeetingRescheduleService.kt:38`에도 종료 시각 보정 없음. 10–11시 회의를 12시로 옮기면 12–11시. 이후 `toDomainEntity()`(`MeetingSchema.kt:82`)가 `Meeting.kt:31` `endAt shouldBeAfter startAt`에서 예외 | |
| **N3** | Medium (제품 결정) | **주최자에게 agenda·reminder가 발송되지 않는다.** `MeetingFormInput.kt:72`가 참가자에서 `publisher`를 제거하고, `AgendaDispatchRepositoryImpl.kt:22`·`MeetingReminderRepositoryImpl.kt:81`은 `participants.filter { isAttending }`만 수신자로 구성 | 의도인지 확인 필요 |
| **N4** | Medium (kubeconfig 조건부) | `deploy_action.yaml:227` `kubectl apply`에 `-n` 없음, `deployment.yaml:3`에도 namespace 없음. 조회·rollout·rollback은 `api-service` 지정 → kubeconfig 기본 ns가 다르면 엉뚱한 곳에 배포하고 기존 Deployment를 검사 | |
| **N5** | Medium | `SlackInteractionHandlerImpl.kt:107` `actorRole = UserRole.USER` 하드코딩 (H11의 세 번째 경로) | |
| **N6** | Medium | `SlackRetryDeduplicator.seen` 상한 없는 맵 + `evictExpired`(`:32-35`)가 매 요청 전수 스캔 (M18 부수) | |
| **N7** | Low | `deployment.yaml:36-44` `PodDisruptionBudget minAvailable: 1`도 readiness 없이는 무의미 (C7 부수) | |

### 12.3 최종 우선순위

세 레인(11장 / Codex top-15 / Claude 검증)을 합쳐 **"지금 깨져 있는 것 → 데이터·메시지 정합성 → 잠복 결함·운영 안정성 → 정리"** 순입니다.

> **진행 현황 (2026-09-22, 브랜치 `feature/review-critical-fixes`, 미커밋)**
> ⚠️ **2026-09-24 재검수(13장)로 아래 "완료" 중 M15 NOT RESOLVED · C8 REGRESSED · 14건 PARTIAL로 정정됨. 실제 남은 작업은 [13.3](#133-다음-반영-우선순위)을 따르세요.**
> - Tier 0 (1–7) **완료**. 헬스체크 URL은 서비스가 내려가 있어 미검증 — `main` 머지 전 확인.
> - Tier 1 (8–15) **완료**: N1 원자화, N2 `endAt` 보정, C5 EXISTS 서브쿼리, H16/H15 `@Version` + UNIQUE + 인덱스 + `V18`, H3 CDC 재설계(현재 상태 재확인·`claimPending` CAS·`AckMode.RECORD`·DLT), M18/N6 본문 해시 dedup + 실패 시 망각 + 상한, H12/H13 `SlackUserProfileResolver` + `RestClient` 타임아웃, H14 스트림 워치독.
> - Tier 1 리뷰 반영: CDC 모드용 stuck/stale 복구가 없던 blocker → 모드 독립 `OutboxRecoveryScheduler`(60초, CAS) 신설, reschedule `clearAutomatically`, `V18` 완전 멱등, dedup 캡 음수 방어, 레이스 테스트 결정성, 사이드카 본문 상한을 글자 수로, `DISTINCT` 제거.
> - Tier 2 **완료**: C7+N7 probe/resources/grace + `MaxRAMPercentage`, C2 정책별 `RetryTemplate` 캐시 + off-by-one, H2+M22 `Retry-After`·transient/permanent 분류·`response_url` 바디 판정(`ApplicationMessageDispatcherTest` 신설), H1 행 단위 claim/reclaim CAS, H9 `createContext` exhaustive, N5 interaction 레인 역할 해석, C3 TODO 브로드캐스터 삭제, H4 outbox 인덱스(`V19`) + 보존 purge(`OutboxRetentionScheduler`) + `MessageOutboxRepositoryTest`(H2에서 네이티브 쿼리 첫 검증).
> - Tier 2 리뷰 반영: **유일한 `Clock` 빈이 UTC라 `@Component`에 Kotlin 기본값 대신 UTC가 주입**되어 복구 임계값이 9시간 밀리던 회귀(기존 `OutboxHealthIndicator`·리마인더도 같은 결함) → 빈을 `systemDefaultZone()`으로; 429 대기를 1회·30s로 줄이고 초과분은 행을 `IN_PROGRESS`로 남겨 복구 스윕에 위임 + prod `max.poll.interval.ms`; 전용 bounded `relayTaskExecutor`(M21 동시 해결); purge 다중 배치; 24h give-up(`abandonStuck`); 힙 50%·startupProbe 3분; `response_url` 바디 JSON 파싱.
> - Tier 3 **완료**: C8 `or` 진리표 수정, H7 `notBlank` 메시지, H10 `val errorCode`·`statusCode` 도메인 제거·데드 enum/`ErrorResponse` 삭제, H19 빈 스펙(삭제 1·구현 1), M15 dev `SLACK_SIGNING_SECRET` 필수, M19 dev/local `heapdump` 제거, M25 문서 드리프트(Gradle 9.7.1·StringSpec 3·루트 AGENTS.md 컴파일 옵션 서술), R3 `Utils.kt`→`Validation.kt`. **H18은 되돌림** — `MessageOutboxRepository`가 `JpaRepository`라 application이 `save()`를 직접 쓰므로 `api` 노출이 실제로 필요(12.1 판정 확정).
> - **의도적으로 미착수**: C6(툴체인 이동, 단독 PR — `-Xjsr305=strict` 컴파일 에러 파급), N3(주최자 알림 포함 — 제품 결정), H11 `SlashCommandGate`(현재 슬래시 커맨드가 전부 BASIC이라 동작 없는 뼈대만 남음 — 첫 ops 커맨드 추가 시 함께), 7.6 detekt/JaCoCo(별도 빌드 도구 PR), R3 미사용 연산자 삭제·R2→R1 God-class 분해(기능 변경 없는 대규모 리팩토링 — 별도 브랜치), **application 모듈 Spring 배선 스모크 테스트**(Tier 2 리뷰가 지적: `Clock` 빈 주입 회귀는 단위 테스트로 잡히지 않음 — `@SpringBootTest`로 `Clock`·`relayTaskExecutor` 같은 빈 선택을 한 번 고정할 것).
> - 운영 적용 전 필요한 수동 작업: `V18`(중복 참가자 사전 점검)·`V19` 적용, configmap `KAFKA_BOOTSTRAP_SERVERS` 실제 값, 헬스체크 URL 확인(서비스 복구 후).

**Tier 0 — 즉시 (각 ≤10줄, PR 1~2개)**

| # | 항목 | 왜 먼저 | 주의 |
|---|---|---|---|
| 1 | **C1 + X3 + X4 + H20** — `lint.yaml`·`simple_test_action.yaml`에 `pull_request: [main]`(paths 필터 복제), `deploy_action.yaml:129` `-x test` 제거, 모듈 선택 실행 → 전체 `build`, `lint.yaml` `permissions: contents: read`, deploy `check-changes`에 `pull-requests: read` | 이후 모든 수정의 검증 게이트 | 브랜치 보호 required check 등록은 GitHub 설정(레포 밖) |
| 2 | **C4** `"kafka:port"` → `${KAFKA_BOOTSTRAP_SERVERS}` (prod·dev), `k8s/configmap.yaml` 키 추가, `k8s/AGENTS.md:47` 갱신 | 커밋된 설정 그대로는 prod 기동 불가 | 기본값 없이 선언 → 미설정 시 fail-fast |
| 3 | **H21 + N4** — 헬스체크 URL 전체 수정(`/api/slack` 접두 제거·이중 슬래시), `:256` `...` 리터럴, `READY_PODS`를 Ready 조건으로, `kubectl apply -n api-service` | 배포 검증이 현재 무의미 | |
| 4 | **H17** `run:267-268` `local` 2줄 제거 | 기본 환경 `-m` 사용법 파손, 5분 | `set -euo pipefail` 승격은 별도 |
| 5 | **H5** `ControllerAdvice` 빈 핸들러 → 로깅 + 500, 범용 `Exception` 핸들러 추가 | 장애 은폐 | |
| 6 | **H6** `"null"` 문자열 — 필드 제거 또는 `channelId`/`actorId`로 개명(ID를 이름 필드에 담지 말 것) | AI 컨텍스트(`AgentConverseService.kt:115`)까지 오염 | |
| 7 | **X2** `PollingMessageProcessor` 생성자 기본값 제거, `ConsumerConfig.kt:34-40`에서 `appConfig`·`clock` 주입 | 설정 무효화 | slack-live 한정 |

**Tier 1 — 데이터·메시지 정합성 (1–2주)**

| # | 항목 | 비고 |
|---|---|---|
| 8 | **N1** agenda claim + outbox 저장을 한 tx로 원자화 | 확정적 당일 누락 |
| 9 | **N2** reschedule 시 `endAt` 보정(duration 유지) — 리포지토리 UPDATE와 도메인 양쪽 | 저장 후 읽기 예외 |
| 10 | **C5** JOIN FETCH 절단 — 테스트 먼저(`participants.size` 단언 + `entityManager.clear()` 후 재조회) | 사용자에게 보이는 오답 |
| 11 | **H16 + H15** `MeetingSchema` `@Version`, `meeting_participants UNIQUE(meeting_id, user_id)`, 인덱스 4종 — 수동 `V18`에 묶기. `runCatching.isSuccess` 오분류 제거 | H8과 무관하게 진행 가능 |
| 12 | **H3 재설계** — `enable-auto-commit: false` + 리스너에 `Acknowledgment` + `DefaultErrorHandler`(기존 null-record 처리 이관) + 재전달 시 현재 DB 상태 확인 + 파싱 실패 DLT | **11장 패치 그대로 쓰지 말 것** |
| 13 | **M18 + N6** dedup — 삭제하거나 `event_id`/`trigger_id` 키 + 처리 상태(진행/성공/실패) 관리로 재작성, `seen` 상한 | 현재 데드 코드 + 메모리 누수 |
| 14 | **H12 + H13** 프로필 조회를 렌더링 밖으로 + TTL 캐시 + `<@userId>` fallback, `RestClient.Builder` 빈 주입으로 타임아웃 | |
| 15 | **H14** SSE 본문 데드라인(스트림 close 워치독) + `reduce` O(n²) 제거 + 크기 상한 | `orTimeout`만으론 부족 |

**Tier 2 — 잠복 결함·운영 안정성**

| # | 항목 | 비고 |
|---|---|---|
| 16 | **C7 + N7** probe/resources/`terminationGracePeriodSeconds` + `management.endpoint.health.probes.enabled=true` 명시 + Dockerfile `-XX:MaxRAMPercentage` | 매니페스트는 envsubst 통과 → 리터럴 `$` 금지 |
| 17 | **C2** 정책 조합별 `RetryTemplate` 캐시 + `maxRetries` off-by-one | 실변동은 3↔5회뿐 |
| 18 | **H2 재정의 + M22** — `SlackApiException`의 `retryAfterSeconds` 반영(row를 PENDING으로 되돌려 relay가 나중에 재집), 일시적 `ok=false` 재시도, `response_url` 바디 판정 | |
| 19 | **H1** polling claim-token 통일 | prod는 cdc라 잠복. outbox `AGENTS.md`의 "single-instance 수용" 문구도 갱신 |
| 20 | **H9 + H11 + N5** `else -> EmptyContext` 제거, `SlashCommandGate`, interaction role 하드코딩 해소 | |
| 21 | **C3** `KafkaEventPublisherConfig`가 `StdoutErrorBroadcaster`를 반환하도록 (빈 제거 금지) | 호출자 0건이라 급하지 않음 |
| 22 | **N3** 주최자 알림 포함 여부 — 제품 결정 후 반영 | |
| 23 | **C6 단독 PR** — `subprojects`로 toolchain·compiler args 이동, `-jvm-default=enable`, 부록 출력 갱신 | `-Xjsr305=strict` 활성화로 컴파일 에러 다수 예상 |
| 24 | **H4** outbox `(status, created_at)`/`(status, updated_at)` 인덱스 + `offset` 파라미터 삭제 + SUCCESS/FAILURE purge 배치 | UNIQUE는 걸지 말 것. H8 Flyway 전환은 별도 의사결정 |

**Tier 3 — 정리**

C8(`or`만 수정/삭제) · H7(`reason = "must not be blank"`) · H10(`val errorCode`, `statusCode` 도메인 제거, 데드 enum 삭제) · H19(빈 스펙 2개) · H18(`data-jpa`만 `implementation`) · M25 문서 드리프트(`.github/AGENTS.md:37/120/122/124`, `AGENTS.md:89-91`의 jsr305 서술, `k8s/AGENTS.md:47`) · M15/M19 · 7.6 detekt/JaCoCo(의존성 스캔 서술은 정정) · R3 rename + 미사용 연산자 삭제 · R2 → R1.

### 12.4 11장과의 차이

- **올린 것**: H21+N4(15.5 → 3), N1·N2(신규 → 8·9), H16(누락 → 11), H14(누락 → 15).
- **내린 것**: C3(2 → 21), H7(6 → Tier 3), C8(13 → Tier 3), C2(7 → 17).
- **H8을 H4·H15 선행조건에서 분리** — Flyway는 문서화된 결정의 번복이라 별도 판단.
- **수정 스니펫을 그대로 적용하면 안 되는 항목**: C3, H3, H4(UNIQUE), H14, H18.

---

## 13. 2차 교차 검증 — 브랜치 반영 결과 재검수 (2026-09-24)

> **방법**: `feature/review-critical-fixes`(3커밋, 125파일)를 대상으로 ① 영역별 Claude 리뷰 레인 5개(outbox·relay / Slack dispatch·retry / meeting·JPA·migration / web·security·domain / CI·k8s·ops) + 보안 전용 레인 1개(각각 소스 직접 열람, 관련 테스트 실행), ② Codex(gpt-6-astra)가 독립적으로 전 항목 재판정(테스트 3모듈 재실행, Hibernate·Boot 바인딩 재현 포함), ③ 메인 세션이 상위 주장을 직접 재현(Jetty 12.1.12 임베디드 프로브, 프로덕션 호스트 GET/POST 프로브, `spring-kafka-4.1.1.jar` 바이트코드 확인).
> 산출물: `.omc/artifacts/review-2026-09-24/` (`lane-*.md` 6개, `codex-final.md`), Codex 전문 `.omc/artifacts/ask/codex-you-are-a-senior-reviewer-doing-an-independent-second-pass-a-2026-09-24T04-47-24-049Z.md`.
> 빌드: `./gradlew build` 통과(2026-09-24).
>
> **결론**: 12.3의 "완료" 주장 중 **NOT RESOLVED 1(M15) · REGRESSED 1(C8) · PARTIAL 14**. 브랜치가 새로 만든 결함 중 High 이상이 6건이며, 그중 4건은 같은 뿌리(outbox 소유권·시계·재전달)에서 나온다. 머지 전 필수 항목은 13.3 Tier A.

### 13.1 12.3 판정 정정표

| 항목 | 12.3 주장 | 재판정 | 근거 |
|---|---|---|---|
| **H21** 헬스체크 URL | 완료(미검증) | **NOT RESOLVED** | `deploy_action.yaml:30` `api/slack/actuator/health` 그대로. 실측: `https://api.notypie.dev/actuator/health` → **404**, `/api/slack/actuator/health` → **401**. **두 URL 모두 200이 나오지 않으므로** 정상 배포가 매번 헬스체크 실패로 롤백된다. *(2026-09-28 정정: 이 401을 "앱의 서명 필터"로, 라우팅을 "`/api/slack/*`만 전달"로 적었던 것은 잘못된 추론이다. 재측정 결과 존재하지 않는 경로를 포함한 모든 경로가 `WWW-Authenticate: Bearer`·`server: istio-envoy`의 401을 돌려준다. 공개 호스트 앞단은 이 저장소 밖의 Bearer 인증 계층이고, 어떤 경로가 앱에 도달하는지는 밖에서 알 수 없다.)* `k8s/AGENTS.md:70-72`·`docs/wiki/dev-environment.md:146-147`의 "게이트웨이가 접두를 벗긴다" 가설은 Slack 이벤트 수신과 양립 불가(벗기면 `/api/slack/events`도 `/events`가 됨) |
| **M15** dev 서명 시크릿 필수 | 완료 | **NOT RESOLVED** | `${SLACK_SIGNING_SECRET}` 기본값 제거는 fail-fast가 아니다. `@ConfigurationProperties` 바인더는 미해결 플레이스홀더를 **리터럴 문자열로 보존**(Codex가 Boot 4.1.1로 재현) → `isBlank()` false → 공개 문자열 `${SLACK_SIGNING_SECRET}`로 HMAC 검증. 정상 Slack 요청은 401, 그 문자열로 서명한 요청은 통과. 빈 문자열이면 검증 통째로 비활성(`Filter:34`, WARN 1회) |
| **M19** heapdump 제거 | 완료 | PARTIAL | yaml은 제거됐으나 `run:390`이 dev 모드에 `-Dmanagement.endpoints.web.exposure.include=*`를 넘겨 시스템 프로퍼티가 yaml을 덮는다. dev/local의 `loggers`(POST 쓰기)·`threaddump`·`mappings`·`conditions`도 같은 논리로 남음 |
| **C8** `or` 진리표 | 완료 | **REGRESSED** | 진리표는 맞으나 `Field.errorMark` 기본값 0 때문에 `shouldNotBeNullAnd`/`ifNotNull`이 만든 파생 Field에 `or`를 쓰면 `keepUpTo=0` → **앞선 다른 필드의 에러까지 전부 삭제**(Codex 실행 재현: `BEFORE=[invalidA]`, `AFTER=[]`). 수정 전 `or`는 지역 `before` 로 우변 에러만 지웠으므로 폭발 반경이 넓어진 회귀. 현재 `or` 사용처 0건이라 잠복 |
| **H3** CDC 재설계 | 완료 | PARTIAL | 현재 상태 재확인·CAS·`AckMode.RECORD`·DLT는 들어갔으나 (a) `IN_PROGRESS → 무조건 재발송` 분기에 CAS 없음, (b) 상태 갱신 실패 → `DefaultErrorHandler` 재전달 → 같은 분기로 재발송, (c) DLT 배선 결함. 13.2 S3·S4·S9 |
| **H2 + M22** 429/transient/response_url | 완료 | PARTIAL | `ok=false error=ratelimited`가 transient 집합에 있어 `SlackRateLimitedException`이 아닌 `SlackTransientErrorException` → 3회 즉시 재시도 소진 → **FAILURE(영구 유실)**. HTTP 429와 정반대 결과. `Retry-After > 30s`는 30s로 잘라 헛호출 1회. `InterruptedException`은 FAILURE로 분류. response_url 경로에서는 `isRateLimited()`가 구조상 절대 참이 안 됨 |
| **H12** 프로필 조회 렌더 밖으로 | 완료 | PARTIAL | TTL 캐시·`<@id>` fallback은 구현. 그러나 `OutboundRenderer:97-107 → SlackApiEventConstructor:128 → ModalTemplateBuilder:99`로 **여전히 렌더 경로 안**이고 dispatch 직전에 실행됨. 캐시 미스면 릴레이·Kafka 스레드에서 동기 Slack 호출(3s+10s 상한만 생김). 실패 결과는 캐시 안 됨 |
| **H13** RestClient 타임아웃 | 완료 | RESOLVED(수단 상이) | 타임아웃은 적용됨. 권고였던 Boot `RestClient.Builder` 빈 대신 정적 builder + `JdkClientHttpRequestFactory` 하드코딩 — `spring.http.client.*`로 운영 조정 불가 |
| **H14** SSE 워치독·상한 | 완료 | PARTIAL | 워치독·`reduce` 제거는 됨. 8,192자 상한은 **에러 본문에만**, 그것도 한 줄을 다 만든 뒤 검사. 성공 경로 `accumulatedText`·`dataLines` 무제한 |
| **C5** JOIN FETCH 절단 | 완료 | PARTIAL | 두 쿼리의 EXISTS는 정확(생성 SQL 확인). `findActiveByStartAtBetween:61-75`에 `SELECT DISTINCT`가 남아 불일치. 네 쿼리 모두 INNER `JOIN FETCH`라 참가자 0명 회의가 사라짐(S10) |
| **H16** `@Version`·UNIQUE | 완료 | PARTIAL | `addParticipants`에서는 실동작(생성 SQL에 `version=? ... and version=?` 6회). **reschedule 경로는 무력화**(S8). 락 패자는 500(S8b) |
| **M18 + N6** dedup | 완료 | PARTIAL | 본문 해시·`putIfAbsent`·실패 시 망각·상한 모두 구현. 그러나 in-flight 재시도를 200으로 흡수 → 원본이 뒤늦게 5xx면 이벤트 유실(S5). 코드 주석 "Single-replica only" vs `deployment.yaml:6` `replicas: 2` |
| **C4** Kafka 주소 외부화 | 완료 | PARTIAL | `KAFKA_BOOTSTRAP_SERVERS`만 추가. 같은 부류 `SLACK_CDC_TOPIC`(`prod.yaml:129`)·`SLACK_SIGNING_SECRET`(`:123`)은 configmap/secret 샘플에 없어 `@KafkaListener` 토픽 플레이스홀더 해석 실패 → **샘플 매니페스트로는 prod 기동 불가** 상태 유지 |
| **Clock** `systemDefaultZone` | 완료 | PARTIAL | UTC 주입 문제는 해결. 그러나 DB `CURRENT_TIMESTAMP`와 JVM 시계의 분리는 남음(S2) |
| **M21** bounded executor | 완료 | PARTIAL | `@Qualifier` 매칭·`@Primary` 유지 정상. 그러나 100건을 먼저 전부 claim하고 worker 4개에 제출 → 429 대기 시 뒤쪽 작업이 5분 stuck 임계를 넘겨 recovery가 reclaim → 원래 Runnable도 실행 → 중복 발송(Codex #3) |
| **M25** 문서 드리프트 | 완료 | PARTIAL | 지정 4곳은 수정. 브랜치가 새로 만든 드리프트 다수: `impl/command/AGENTS.md:75`(스펙 없음 vs 73행에 추가), `impl/retry/AGENTS.md:35,50`(삭제된 `FixedBackOff` 서술), `domain/common/AGENTS.md:23`(`Utils.kt`), `k8s/AGENTS.md:58-60,70-72`(힙 근거·게이트웨이 가설), `AsyncConfig` "poller/listener thread" 주석(CDC 리스너는 executor 미경유), `lane-ops.md` C절 14건 |
| 24h give-up | 완료 | PARTIAL | `abandonStuck`에 관측 시각/토큰 조건이 없어 새 소유권과 경쟁 가능. 그 전까지 300초마다 재발송 ≈ **288회**, reclaim이 `updated_at`을 리셋해 헬스 지표는 플랩만 함 |

**RESOLVED로 확정**(레인·Codex 일치): C1/X3/X4/H20, N4, H17, H5, H6, X2, N1(propagation·self-invocation·rollbackOnly 전부 확인), N2(도메인에 reschedule 메서드가 없어 "양쪽" 요구는 공허), H15/V18/V19(컬럼명·멱등성·번호), H1(PENDING 단건 CAS), H4, H9(when 식, 31개 정확), N5, C2(spring-core 7.0.9 `RetryPolicy` 의미 일치), C3, C7/N7, H7, H10, H19, H18 되돌림, R3, 레이스 테스트 결정성, purge 다중 배치.

### 13.2 신규 결함 (레인·Codex 통합, 심각도순)

| ID | 심각도 | 결함 | 도입 | 근거 | 합의 |
|---|---|---|---|---|---|
| **S1** | **Critical**(코드) / prod 노출 미확인 | **Slack 서명 필터 우회**: `shouldNotFilter`가 raw `requestURI`를 `startsWith`로 비교하는데 Spring MVC는 디코딩·정규화된 경로로 라우팅. **Jetty 12.1.12 실측**: `/api/sla%63k/events`·`/api/x/../slack/events`·`/api/slack/./events`의 `requestURI`는 원문 그대로, `pathInfo`는 `/api/slack/events`. 즉 서명 없이 컨트롤러 도달. 이 브랜치의 N5(`actorRole = commandRoleResolver.resolve(actorId)`)가 위조 페이로드의 admin ID로 **ADMIN 권한 획득** 경로를 열어 증폭. *(2026-09-28 정정: "프로덕션 프로브가 401이므로 게이트웨이가 정규화해 막고 있다"는 서술은 근거가 없었다. 그 401은 앞단 Bearer 인증 계층의 응답이며 앱 도달 여부와 무관하다. 프로덕션 노출 여부는 미확인)* | 필터 기존 / 증폭 브랜치 | `SlackRequestVerificationFilter.kt:25-26`, 스크래치 `jettyprobe/Probe.java` | security C1, 메인 실측 |
| **S2** | **High** | **outbox `updated_at` 필자 분리 + 실제 9시간 어긋남**: `claimPending`/`reclaimStuck`/`abandonStuck`은 DB `CURRENT_TIMESTAMP`(세션 존), `@CreationTimestamp`/`@UpdateTimestamp`·모든 컷오프·헬스는 JVM 시계. 앱은 `Dockerfile:20` `-Duser.timezone=Asia/Seoul`, MariaDB StatefulSet(`cdc/k8s/yamls/mariadb/mariadb-sts.yaml`)은 `TZ`·localtime·`default_time_zone` 없음 → UTC. JDBC URL/`hibernate.jdbc.time_zone` 미설정. 결과: claim 직후 행이 즉시 stuck → **60초 스윕이 발송 중인 행을 재발송(상시 중복)**, `reclaimStuck` 가드 무력화, 헬스 상시 DOWN. H2 테스트는 같은 JVM이라 못 잡음. 남은 확인: prod Secret `SQL_DATABASE_URL`에 `connectionTimeZone` 파라미터 유무 | 구조 기존 / 위험화 브랜치 | `MessageOutboxRepository.kt:33,78,96`, `OutboxRecoveryScheduler.kt:37`, `OutboxHealthIndicator.kt:21` | outbox B3, Codex #7 |
| **S3** | **High** | **CDC `IN_PROGRESS` 분기 무조건 재발송**: 복구 스윕이 claim해 발송 중인 행에 지연·재전달된 CDC 레코드가 도착하면 CAS 없이 다시 발송. `DebeziumLogTailingProcessorTest:112-125`가 이 동작을 정답으로 고정 | 브랜치 | `DebeziumLogTailingProcessor.kt:95-98` | outbox B1, Codex #2 |
| **S4** | **High** | **상태 갱신 실패 → 레코드 재전달 → S3 분기로 재발송**: `publishEvent`가 동기라 `SlackMessageRelayServiceImpl:78-83`의 5회 재시도 소진 예외가 `consume()` 밖으로 → `DefaultErrorHandler` 2회 재전달 → 매번 IN_PROGRESS 재발송. 일시적 DB 장애 1회에 Slack 게시 최대 3회. auto-commit 시절엔 재전달 자체가 없던 경로 | 브랜치 | `DebeziumLogTailingProcessor.kt:83`, `KafkaConsumerConfiguration.kt:100` | outbox B2, Codex #2 |
| **S5** | **High** | **dedup이 in-flight 재시도를 200으로 흡수 → 원본이 뒤늦게 5xx면 이벤트 무음 유실**: 원본 3초 초과 → Slack retry#1 → `previous != null`로 200 → 원본 500·`markFailed` → Slack은 이미 200 받아 재시도 안 함. H5로 DB 예외가 500이 되면서 비로소 도달 가능해진 창. 게다가 `replicas: 2`인데 맵은 JVM 로컬 | 브랜치 | `SlackRequestVerificationFilter.kt:65-81`, `SlackRetryDeduplicator.kt:44-50`, `deployment.yaml:6` | web B-1, Codex #6, security M4 |
| **S6** | **High** | **429 대기(≤30s)가 CDC 리스너 스레드를 점유**: `max-poll-records: 100` × 31s ≈ 52분 ≫ `max.poll.interval.ms: 600000` → 약 19번째 레코드에서 컨슈머 축출·리밸런스 → 미커밋 레코드 재전달 → 그 행들은 이미 IN_PROGRESS → S3로 재발송 → 또 429 → **라이브락**. dev/local은 override가 없어 기본 5분. `ApplicationMessageDispatcher.kt:43-44` 주석은 레코드 단위로만 참. 폴링 경로도 100건 선claim + worker 4 → 뒤쪽 작업이 5분 임계 초과 → reclaim → 중복 | 브랜치 | `DebeziumLogTailingProcessor.kt:40,71`, `application-prod.yaml:74-78`, `AsyncConfig.kt:32-43` | dispatch B-1, outbox B5, Codex #3·#5 |
| **S7** | **High** | **서명 시크릿 바인딩 3중 결함**: (a) 미설정 시 플레이스홀더 리터럴 보존(13.1 M15), (b) 빈 문자열 시 검증 비활성 + WARN 1회, (c) `application-slack-live.yaml:66` `${SLACK_SIGNING_SECRET:}` 기본 공백 + 헤더가 ngrok 터널로 실제 Slack 앱 연결 안내 → 인터넷 노출 인스턴스가 무검증으로 기동 | (a)(b) 브랜치 미완 / (c) 기존 | `AppConfig.kt:39`, `Filter:33-40`, `application-slack-live.yaml:64-66` | Codex #1, web B-5, security H3 |
| **S8** | **High** | **reschedule 낙관 락 무력화**: `OPTIMISTIC_FORCE_INCREMENT`의 버전 증가는 커밋 직전 콜백(`EntityIncrementVersionProcess`)인데 `clearAutomatically=true`가 UPDATE 직후 `em.clear()` → 엔트리 없음 → 증가 건너뜀(Hibernate 7.4 소스 + Codex H2 재현 `UPDATED=1, VERSION=0`). `markMeetingCanceled`와 `addParticipants` 경합 시 취소된 회의에 참가자 추가 가능. `JpaMeetingRepository.kt:145-146` 주석·`repository/meeting/AGENTS.md` 서술이 허위. **S8b**: `addParticipants`는 `handleInteraction`의 바깥 tx(REQUIRED)에 참여해 강제 증가 UPDATE가 `MeetingServiceImpl.addParticipants`의 `runCatching` **밖**(바깥 커밋)에서 실행 → `ObjectOptimisticLockingFailureException`을 못 잡고 500 + 아웃박스 행 롤백 → **사용자 피드백 0**, 재시도 0건. `AddParticipantRepositoryTest:37-46` 주석이 존재하지 않는 tx 경계를 모델링 | S8 브랜치 / S8b 기존(H16이 의존) | `MeetingRepositoryImpl.kt:83-89`, `JpaMeetingRepository.kt:126-150`, `MeetingServiceImpl.kt:178-198`, `SlackInteractionHandlerImpl.kt:44` | meeting B-1·B-1b, Codex #11 |
| **S9** | Medium~High | **DLT 배선 3중 결함**: (a) `DeadLetterPublishingRecoverer(kafkaTemplate)`의 템플릿이 `JacksonJsonSerializer` → 역직렬화 실패 레코드의 원본 `byte[]`가 **Base64 JSON 문자열**로 DLT에 실려 replay 불가(Spring Kafka 문서는 `ByteArraySerializer` 분기 요구), (b) 기본 접미사는 **`-dlt`**(spring-kafka-4.1.1.jar 바이트코드 확인)인데 주석·위키는 `.DLT`, (c) `NewTopic`/`KafkaAdmin` 0건·매니페스트 언급 0건 → auto-create 꺼진 브로커에서 전송 실패 → `failIfSendResultIsError=true` → 파티션 무한 재전달 | 브랜치 | `KafkaConsumerConfiguration.kt:84-101`, `docs/wiki/events-and-outbox.md:99-100` | outbox B4, Codex #10 |
| **S10** | Medium~High | **참가자 0명 회의가 모든 조회에서 사라짐**: 네 쿼리 전부 INNER `JOIN FETCH m.participants`. `MeetingFormInput.parseParticipants`가 publisher를 제거하므로 본인만 고른 회의는 `emptySet()` → `getMeeting` 500, `/meetup list` 주최자에게도 비노출, 리마인더·아젠다 대상 제외. `findMeetingByUidWithParticipants:152-161`만 LEFT | 기존(C5가 못 닿음) | `JpaMeetingRepository.kt:21,34,46,65`, `Meeting.kt:27`(하한 없음) | meeting B-4, Codex #13 |
| **S11** | Medium | **`ratelimited`·`fatal_error` 분류 오류**: `ok=false ratelimited` → FAILURE 영구 유실(13.1 H2). `fatal_error`는 Slack 문서상 "일부 작업이 이미 성공했을 수 있음" → `chat.postMessage` 재시도 시 중복. `request_timeout`은 잘린 POST라 재시도 무의미 | 브랜치 | `ApplicationMessageDispatcher.kt:41,133-134,174-180,255-285` | dispatch B-2~B-5·B-9, outbox B7, Codex #4 |
| **S12** | Medium | **`errorMark` 회귀**(13.1 C8) + `Field`가 `data class`인데 `errorMark`가 생성자 밖 `var`라 `copy()` 시 0 리셋 | 브랜치 | `Validation.kt:17,20,29-37,50,58` | web B-2, Codex #15 |
| **S13** | Medium | **본문 크기 상한 없음**: `CachedBodyHttpServletRequest:17` `readAllBytes()`가 **서명 검증 전**에 실행, `server.*max*` 설정 0건 → 무인증 대용량 POST로 힙 1Gi 고갈(replicas 2 모두). **response_url SSRF**: 호스트 검증 없음(`hooks.slack.com` 강제 0건) + 이 브랜치가 `response.body?.string()` 전체 적재 후 `.take(200)` (main은 `close()`만). S1·S7(c)와 체이닝 | 상한 기존 / 본문 적재 브랜치 | `SlackRequestVerificationFilter.kt:42`, `ApplicationMessageDispatcher.kt:247-266` | security H1·H2, web B-6 |
| **S14** | Medium | **actuator 노출**: prod `health,info,metrics`가 `ingress.yaml` `path: /`로 공개 설계(실제 앞단 라우팅은 저장소 밖이라 미확인). dev/local `loggers`(POST 쓰기)·`threaddump`·`mappings`·`conditions` + `show-details: always`. `run:390` `include=*`(13.1 M19). `run:406-410` JMX 무인증·바인드 제한 없음 | 기존 | `application-dev.yaml:73`, `application-local.yaml:68`, `run:390,406-410` | security M1·M2, web B-8, ops B9, Codex #9 |
| **S15** | Medium | **버튼마다 DB 역할 조회, 캐시·실패 처리 없음**: `findRole`에 `runCatching` 없음 → DB 장애 시 모든 버튼 500 → S5 유실 창 직행. USER 하드코딩 시절엔 DB가 죽어도 버튼은 동작 | 브랜치(N5 부수) | `SlackInteractionHandlerImpl.kt:108`, `CommandRoleResolver.kt:18` | web B-3 |
| **S16** | Medium | **ops/k8s**: (a) `MaxRAMPercentage=50` × 2Gi = 힙 1Gi = request 1Gi → RSS 1.4~1.6Gi가 request 초과 → 축출 1순위(주석과 정반대), (b) 롤백이 `kubectl set image`라 probe/resources 변경은 안 되돌리고 같은 SHA 재배포 시 no-op 거짓 성공, (c) 워크플로 전부 `concurrency` 없음 → 연속 머지 시 backup/apply/rollback 교차, (d) configmap/secret에 `SLACK_CDC_TOPIC`·`SLACK_SIGNING_SECRET` 없음(13.1 C4), (e) `preStop` 없음, (f) grace 30s vs 429 대기 30s 충돌, (g) 컨테이너 root·securityContext 없음, (h) `secret.yaml` `data:`가 base64 아님 → apply 실패(`stringData:`로), (i) `envsubst` 무제한 치환 + job env에 OCI 개인키 5종, (j) CI `gradle-linux.properties` 8g+6g 데몬 on 16GB 러너(리스크), (k) `environment_url` 플레이스홀더, (l) `-jar`가 `-D` 앞(동작은 함) | (a)(f)(j) 브랜치 / 나머지 기존 | `Dockerfile:19-20`, `deployment.yaml:16,48-50`, `deploy_action.yaml:134-146,240-242,298-310`, `secret.yaml:6-10` | ops B2~B15, security L2, Codex #14 |
| **S17** | Medium | **reclaim이 `updated_at`을 리셋해 poison 행이 24h 동안 ≈288회 재발송되며 헬스는 플랩만** — `attempt_count` 컬럼(V20) + 낮은 상한 필요 | 브랜치 | `OutboxRecoveryScheduler.kt:33-35,49`, `OutboxHealthIndicator.kt:30` | outbox B6 |
| **S18** | Medium | **reschedule 미래 시각 검증 없음**(생성 경로는 2겹) → 과거로 옮기면 리마인더 삭제 후 미재생성·목록 소실인데 성공 ephemeral 발송. **V18에 기존 `end_at < start_at` 행 보정 없음** → 새 코드가 음수 duration을 그대로 유지, `toDomainEntity()`가 계속 던짐 | 기존 | `ParsedSubmissions.kt:74-83`, `MeetingRescheduleService.kt:36-54`, `MeetingRepositoryImpl.kt:84` | meeting B-2·B-3 |
| **S19** | Medium | **프롬프트 인젝션 표면**: `requesterName`/`channelName`이 이스케이프·길이 제한 없이 system prompt에 이어 붙음. 현재 app_mention은 `""`라 미발현, 다른 호출자가 채우면 열림. MCP 도구 게이트는 서버 발급 토큰 기반이라 독립 보호됨 | 기존 | `AgentConverseService.kt:111-133` | security M3 |
| **S20** | Low | dedup: 캡 도달 후 매 요청 O(n log n) 정렬(N6가 고치려던 전수 스캔의 귀환), `trimToCap` 비원자, 실행 중 엔트리 축출 가능, `require(maxEntries > 0)` 없음, `catch (Exception)`이 `Error` 놓침, 슬래시 경로(`retryNum` 항상 null)가 캡만 잠식, 영구 실패 페이로드에 `X-Slack-No-Retry: 1` 미사용 | 브랜치 | `SlackRetryDeduplicator.kt:48,66-71`, `Filter:75` | web B-7·B-9~B-11 |
| **S21** | Low | 프로필 리졸버: 실패 결과 negative cache 없음(Tier 4 API를 렌더마다 호출), `cache.size >= max`면 `clear()` 전체 삭제, `users.profile.get?user=$userId` 미인코딩 보간 | 브랜치 | `SlackUserProfileResolver.kt:37-49` | dispatch B-6·B-10, security L4 |
| **S22** | Low | 테스트 품질: `MessageOutboxRepositoryTest:44-45` `createdAt`이 `@CreationTimestamp`에 덮여 정렬 미검증; `DailyAgendaSchedulingServiceTest:266-281` 취소 회의 케이스가 공허(위 케이스와 동일); `:190-228` 목 tx 매니저라 N1 회귀 못 잡음; `ControllerAdviceTest:53-65`가 resolver 선택을 안 증명(`ExceptionHandlerMethodResolver`로 컨텍스트 없이 가능); `RetryServiceTest` `shouldThrow<Exception>` 동어반복, `RetryException.cause` 계약 미고정; `OutboxTestFixtures:40,57` 죽은 `stuckInProgressSeconds`; `PollingMessageProcessorTest:17` 죽은 문구; transient/permanent 표·폴링 위임·`ok=false` 프로필 케이스 미커버 | 브랜치 | — | outbox B9·B10, meeting B-7·B-8, web B-4, dispatch 테스트 관찰 |
| **S23** | Low | 스타일·문서: `ApplicationMessageDispatcher.kt:144,147` 위치 인자(같은 파일 138-142는 named); `ConsumerConfig.kt:84` 서술형 주석("was a TODO() that would have thrown…"); `KafkaConsumerConfiguration`이 lite 설정이라 `consumerFactory()` 호출이 빈이 아닌 새 인스턴스; hex 변환 2벌; `lint.yaml` paths에 `gradle/**` 없음·`timeout-minutes` 없음; 13.1 M25의 드리프트 목록 | 브랜치 | — | dispatch B-11·B-12, outbox B11, web B-12·B-13, ops B14·C절 |

### 13.3 다음 반영 우선순위

**Tier A — 머지·배포 전 필수** (없으면 배포가 롤백되거나 상시 중복 발송)

| # | 항목 | 비고 |
|---|---|---|
| A1 | **H21 재수정** — 공개 호스트에서는 헬스 200을 받을 수 없으므로 URL만 바꿔서는 안 된다. 배포 잡에서 `kubectl get --raw "/api/v1/namespaces/api-service/services/code-companion-svc:80/proxy/actuator/health"` 또는 `kubectl port-forward` 로 **클러스터 내부 검증**으로 전환. `k8s/AGENTS.md:70-72`·`dev-environment.md:146-147`·`.github/AGENTS.md:102-104`의 게이트웨이 가설 삭제 | S2 실측 |
| A2 | **S2 시계 통일** — 네이티브 3문장의 `CURRENT_TIMESTAMP`를 `:now` 바인딩(스케줄러와 같은 `Clock`)으로. `MessageOutboxRepositoryTest`에 세션 존을 `SET TIME ZONE`으로 어긋나게 한 케이스 추가. 배포 전 prod `SELECT @@session.time_zone, NOW()` 대조 | outbox `AGENTS.md:35-38` 의도 유지 |
| A3 | **S3+S4+S6 CDC 소유권** — `IN_PROGRESS` 분기를 **제거**(stuck은 `OutboxRecoveryScheduler` 전담), 상태 갱신 실패는 리스너 안에서 로그 후 스윕에 위임(DLT 대상 아님), 429는 리스너에서 자지 않고 즉시 IN_PROGRESS 위임 또는 `max-poll-records` ≤ 10 + 세 프로파일 `max.poll.interval.ms` 명시. `DebeziumLogTailingProcessorTest:112-125` 기대값 반전. 폴링은 실행 시점 claim 또는 실행 직전 소유권 재확인 | 한 PR |
| A4 | **S7 시크릿 fail-fast** — `local` 외 프로파일에서 blank·미해결 플레이스홀더(`startsWith("${")`) 거부하는 `@PostConstruct`/`ApplicationRunner`, `slack-live` 기본값 제거 | dev·prod·slack-live |
| A5 | **S5 dedup 상태 머신** — `IN_FLIGHT`/`DONE` 2상태, `IN_FLIGHT` 재시도는 503(Slack이 재시도 계속), `DONE`만 200. 2 replicas 해소는 `event_id`/`trigger_id` DB 유니크 또는 `replicas: 1` 명시 결정 | 12.3 #13이 요구한 그 설계 |
| A6 | **S1 필터 등록 방식** — `FilterRegistrationBean` + `urlPatterns`(컨테이너가 정규화 경로로 매칭, `McpTurnTokenFilter`와 동일) 또는 `ServletRequestPathUtils` 디코딩 경로 allowlist. `%63`·`..` 회귀 테스트 | 게이트웨이 의존 제거 |
| A7 | **S16(d)/C4 완결** — configmap에 `SLACK_CDC_TOPIC`, secret에 `SLACK_SIGNING_SECRET`, `secret.yaml` `stringData:` | 샘플로 기동 가능해야 함 |

**Tier B — 데이터·메시지 정합성**: S8(+S8b: `REQUIRES_NEW`로 커밋을 `runCatching` 안으로 + 1회 재시도, 또는 managed entity dirty checking으로 통일·`clearAutomatically` 제거; B-9 소유자 조건 동반) · S9(`ByteArraySerializer` 템플릿 분기, `-dlt` 문서 정정, `NewTopic` 빈 또는 `setFailIfSendResultIsError(false)`) · S10(`LEFT JOIN FETCH` 통일, DISTINCT 제거) · S11(`ratelimited`→`SlackRateLimitedException`, transient 집합을 `internal_error`/`service_unavailable`로 축소, `Retry-After > 30s`면 대기 없이 위임, `InterruptedException` 분리) · S12(파생 Field에 mark 상속 또는 (mark, field) 쌍 전달; 회귀 테스트) · S13(`contentLengthLong` 상한 + `readNBytes(limit+1)`, `response_url` 호스트 allowlist + `peekBody`) · S17(V20 `attempt_count`) · S18(`RescheduleMeetingParsed.from` 미래 검증, V20 보정 쿼리) · S15(역할 캐시 + 실패 시 USER 강등).

**Tier C — 운영·정리**: S14 · S16(a)(b)(c)(e)(f)(g)(i) · S19 · S20 · S21 · S22 · S23 · 13.1 M25 드리프트. **여전히 의도적 미착수**: C6, N3, `SlashCommandGate`, detekt/JaCoCo, R2→R1, Spring 배선 스모크 테스트(S8b·N1 회귀·S2 존 문제 모두 이 테스트 부재가 원인 — 우선순위 상향 권고).

### 13.4 이번 검증에서 확인한 방법론 교훈

- **완료 판정은 실행으로만 확정된다.** H21은 "서비스가 내려가 있어 미검증"으로 남긴 채 완료로 기록됐고, 실제 프로브 한 번으로 두 URL 모두 불가임이 드러났다. 12.3의 미검증 표기는 "미완료"로 읽어야 한다.
- **테스트 환경이 결함을 가리는 세 축**: H2 = 같은 JVM 시계(S2), 목 tx 매니저(N1 회귀), Spring 컨텍스트 부재(S8b·`Clock`). 세 번째 Spring 배선 스모크 테스트는 더 이상 "미착수 항목"이 아니라 선행 조건이다.
- **리뷰 레인이 정답으로 고정한 테스트**: `DebeziumLogTailingProcessorTest:112-125`처럼 브랜치가 결함 동작을 `verify(exactly = 1)`로 못 박은 경우, 재검수 레인은 테스트 기대값 자체를 의심해야 한다.
- 위키 `ai-review-remediation-pitfalls` 체크리스트에 위 세 항목을 추가할 것(Codify).

---

### 13.5 반영 현황 (2026-09-28, 작업 트리 미커밋)

> **방법**: 영역별 구현 워커(1차 5레인 → 재검수 → 2차 4레인), 재검수 레인 4개(코드 리뷰 3 + 보안 1), Codex 교차 검증 1회, 메인 세션의 최종 코드 품질 검수. 산출물: `.omc/artifacts/review-2026-09-28/` (`fix-*.md`, `fix2-*.md`, `rev2-*.md`, `codex-final2.md`).
> **검증**: `./gradlew build --rerun-tasks` 통과 — domain 358 · infrastructure 539 · application 426 테스트, 실패 0, 건너뜀 0, ktlint 통과. **실행하지 못한 것**: 실제 MariaDB(V20·V21·V22, REPEATABLE_READ, 중복 키 메시지), 실제 Kafka 브로커의 DLT 왕복, 부팅된 Spring 컨텍스트(새 생성자 파라미터 `Environment`·`MeterRegistry`·`PlatformTransactionManager`·`Clock` 배선), 클러스터에서의 배포 워크플로, actionlint·gitleaks.

| 13.3 항목 | 상태 | 구현 요지 |
|---|---|---|
| A1 헬스체크 | 반영 | 배포 게이트를 `/actuator/health/readiness` + `jq`로, service proxy 우선·`kubectl exec wget` 차선. 롤백은 `rollout undo`, 파드 템플릿 해시로 변경 여부 판정. **클러스터 RBAC(`services/proxy` get 또는 `pods/exec` create) 확인 필요** |
| A2 시계 통일 | 반영 | outbox 네이티브 쓰기 전부 `:now` 바인딩(주입된 `Clock`), `CURRENT_TIMESTAMP` 제거 |
| A3 CDC 소유권 | 반영 | `attempt_count`(V20) 소유권 토큰, CDC는 PENDING만 claim, 발송 직전 `renewClaim`, 완료·포기 쓰기 모두 토큰 조건부, 상태 기록 실패는 리스너 밖으로 나가지 않음. `send_count`(V22)로 실제 발송만 포기 예산에 계산, rate-limit은 `Retry-After`를 보존해 보류. 모든 Slack 호출에 6초 호출 타임아웃, 레코드당 최악 약 53초 < 60초(`max-poll-records: 5`) |
| A4 시크릿 fail-fast | 반영 | 필터 생성 시 검증: 미해결 플레이스홀더는 항상 거부, 공백은 활성 프로파일이 `local` 하나뿐일 때만 허용 |
| A5 dedup 상태 머신 | **부분** | in-flight 재시도 503·완료 200·실패 망각, 세대 토큰, 용량 상한. **복제본 간 공유는 미구현** — 열린 결정 `docs/wiki/decisions.md` #34 |
| A6 필터 경로 | 반영 | raw·컨테이너 디코딩·Spring 뷰 세 경로를 정규화해 default-deny. 본문 1 MiB 상한, 헤더 선검사, 쿼리 문자열 파라미터 무시 |
| A7 매니페스트 키 | 반영 | `SLACK_CDC_TOPIC`, `SLACK_SIGNING_SECRET`, `stringData` |
| Tier B | 반영 | S8(관리 엔티티 변경으로 `@Version` 증가)·S8b(회의 쓰기를 상호작용 트랜잭션 커밋 뒤 실행, 충돌 1회 재시도, 사용자 회신)·S9(DLT 원본 바이트·`-dlt`·`NewTopic`)·S10(`LEFT JOIN FETCH`)·S11(오류 코드 분류 공유)·S12(`or`가 자기 피연산자 오류만 제거)·S13(`response_url` 호스트 허용 목록·리다이렉트 금지·본문 상한)·S15(역할 캐시 60초·커밋 후 축출)·S17·S18(과거 시각 거부 회신, V21) |
| Tier C | 대부분 반영 | S14·S16·S19·S20·S21·S22·S23, 문서 드리프트. 미반영은 아래 |

**의도적으로 하지 않은 것**
- 복제본 간 dedup 공유 저장소(A5) — 설계 결정 필요. 검토 메모와 권고(이벤트 ID 유니크 테이블을 핸들러 트랜잭션에 포함)는 `docs/wiki/decisions.md` #34.
- 컨테이너 non-root 실행 — 80번 포트 바인딩 때문에 포트·Service·probe를 함께 바꿔야 함.
- `management.server.port` 분리 — 대신 샘플 라우트를 `/api/slack`·`/api/slash`로 좁힘.
- 롤아웃 전략 변경 — 메모리 요청 1536Mi로 rollout 시 3 × 1536Mi 필요. 노드 여유 확인은 사람 몫.
- `StandupSummaryService` 리스너의 마커 갱신 실패 재시도 — 이번 담당 범위 밖, `fix2-relay.md`에 수정안 3개.
- 12.3의 기존 미착수(C6, N3, `SlashCommandGate`, detekt/JaCoCo, R2→R1, Spring 배선 스모크 테스트).

**배포 전 사람이 해야 할 일**
1. 이 릴리스는 이전 릴리스와 **동시에 떠 있으면 안 된다**(이전 파드는 DB 시계로 outbox 시각을 쓰고 IN_PROGRESS를 재발송). 첫 배포는 기존 파드를 먼저 내린 뒤 진행(`k8s/README.md` 절차).
2. `V20` → `V22` → `V21` 수동 적용(V21은 V18 이후, 모든 복제본이 새 바이너리일 때).
3. 배포 계정 RBAC 확인, configmap/secret 새 키 적용, 노드 메모리 여유 확인, Kafka `<topic>-dlt` 생성 권한.
4. 공개 호스트 앞단이 앱으로 넘기는 경로 확인(밖에서는 측정 불가).

---

## 14. 3차 교차 검증 — 커밋된 브랜치 전수 재검수 (2026-09-28)

> **대상**: `feature/review-critical-fixes` @ `cca9984` (4커밋). 13.5가 "작업 트리 미커밋"이라고 적은 반영분이 `cca9984`로 커밋된 상태입니다.
> **방법**
> 1. **Opus 5.5 리뷰 레인 7개를 병렬로 실행**했습니다. 영역은 ① outbox·CDC·Kafka ② Slack 발송·외부 HTTP ③ 회의·JPA·마이그레이션 ④ 보안·웹·검증 DSL ⑤ CI·k8s·운영·문서, 그리고 브랜치가 거의 건드리지 않아 이전 회차에서도 덜 본 ⑥ CVE·스탠드업 ⑦ MCP·에이전트·Socket Mode·커맨드 파싱·템플릿입니다. 레인들은 소스와 jar 바이트코드(`javap`)를 대조했고, 일부는 H2·Hibernate·Spring 프로브를 직접 실행했습니다.
> 2. **Codex 독립 리뷰는 두 번 돌렸습니다.**
>    - 1차(09-28)는 약 24만 토큰 지점에서 사용량 한도로 중단됐습니다. 남긴 중간 판단 4건은 메인 세션이 소스로 재확인했습니다.
>    - 2차(09-30)는 이 장과 레인 산출물을 읽지 말라는 조건으로 다시 돌려 완주했습니다. 13.5 판정과 신규 결함 7건을 냈고, 메인 세션이 소스로 대조해 반영했습니다(14.5).
> 3. **메인 세션 검수**: 전체 빌드를 돌렸습니다. 이전 회차가 "실행하지 못한 것"으로 남긴 **Spring 컨텍스트 부팅을 실제로 수행**했습니다(14.1). 레인·Codex의 상위 주장은 인용 라인을 직접 열어 재판정했습니다.
>
> **산출물**: `.omc/artifacts/review-2026-09-28-r3/`에 `lane1-outbox.md` … `lane7-mcp-command.md`와 `build.log`가 있습니다. Codex 원문은 `.omc/artifacts/ask/` 아래 `codex-you-are-a-senior-engineer-doing-an-independent-third-pass-co-2026-09-28T08-53-43-642Z.md`(1차, 중단)와 `…-2026-09-30T01-19-11-938Z.md`(2차, 완주)입니다.
> **빌드**: `./gradlew build --rerun-tasks` 통과. 테스트는 domain 358 · infrastructure 539 · application 426, 실패·건너뜀 0, ktlint 통과로 13.5 수치와 일치합니다.
>
> **결론**
> - 13.3 Tier A/B의 핵심 정합성 수정은 대부분 실제로 해결됐습니다. 소유권 토큰, `:now` 시계, CDC PENDING-only, DLT, 필터 경로 정규화, 시크릿 fail-fast, 낙관 락이 여기에 해당합니다.
> - 남은 문제는 두 부류입니다.
>   - **브랜치가 새로 만든 운영 리스크 3건**: T6 타임아웃 재시도 중복, T7 동시 기동 금지 미강제, T8 마이그레이션 체크리스트 누락.
>   - **이전 두 회차가 보지 않은 영역의 main 기존 결함**: 스케줄러 단일 스레드, 스탠드업·AI 답변의 Block Kit 한도, 스탠드업 스케줄러 정지, CVE digest 절단 유실 등입니다. High 8건 중 6건이 여기서 나왔습니다.
> - 머지 전 필수 항목은 14.4 Tier A입니다.

### 14.1 이번에 실행으로 확인한 것 (13.5 "실행하지 못한 것" 해소분)

부트 jar(`application/build/libs/application-alpha.jar`)를 H2 인메모리로 띄우고, 도달 불가능한 Kafka 주소를 주어 직접 측정했습니다. 외부 호출은 하지 않았습니다.

| 확인 대상 | 결과 | 비고 |
|---|---|---|
| local 프로파일(cdc + kafka) 컨텍스트 부팅 | **기동**(46초, 대부분 KafkaAdmin 대기) | `Clock`·`relayTaskExecutor`·`Environment`·`MeterRegistry`·`PlatformTransactionManager` 새 생성자 배선 정상. readiness 200 |
| local(polling + application_event) | **기동**(7초) | |
| prod 프로파일 + 샘플 configmap/secret 키만 | **기동**. `/actuator/health/readiness` 200, `/actuator/env` 404, `metrics`·`info` 200 | A7 확인 |
| prod, `SLACK_SIGNING_SECRET` 미설정 / 빈 문자열 | 둘 다 **기동 실패**("unresolved placeholder" / "blank; only the 'local' profile on its own…") | A4 확인 |
| 무서명 POST 우회 입력 21종 | **전부 401/400** | `%63`·`%73` 인코딩, `..`·`./`, `;x=1` matrix, `%2F`·`%252F`, `%2e`, `//`, 대문자, 끝 슬래시 등. A6 확인 |
| 정상 서명 요청 | `/api/slack/events`와 `/api/slack/events;x=1`에서 200. 필터를 통과하고 Spring 라우팅에 맞지 않는 변형은 404 | 오탐 없음 |
| 실제 선택된 `TaskScheduler` | `DefaultTaskSchedulerConfiguration#taskSchedulerVirtualThreads`(`/api/actuator/conditions`) → **`SimpleAsyncTaskScheduler`** | **T1의 결정적 증거** |
| 서명 필터 거부 로그 | 사유 대신 `SlackRequestVerificationFilter$$Lambda/0x…@1683cb1`이 찍힘 | **T27** |
| **T28** | 비활성 루틴의 오래된 PENDING dispatch가 스탠드업 발송 큐 전체를 영구히 막음(레인⑥ U7의 일부를 Codex 2차 R3-06이 Medium으로 제기) | `JpaSessionDispatchRepository.kt:16-28`(`PENDING` + `ORDER BY dmTriggerAt` + limit, 루틴 활성 조건 없음), `StandupSchedulingService.kt:117-132`(비활성 루틴이면 상태 변경 없이 건너뜀) | 루틴을 멈출 제품 경로가 없어 DB에서 비활성화하는 것이 유일한 방법인데, 그렇게 한 루틴들에 PENDING dispatch가 batch size(기본 50)만큼 남으면 쿼리가 매번 같은 50건을 고르고 건너뜀 → 활성 루틴의 DM이 영영 선택되지 않음 | main 기존 | **소스** |
| `spring.jpa.open-in-view` | 설정 없음 → 기본 true(부팅 WARN) | T25의 전제 |

**여전히 실행하지 못한 것**: 실제 MariaDB(V18~V22 적용, REPEATABLE_READ, strict `sql_mode`, 1062 메시지), 실제 Kafka 브로커(DLT 왕복), 클러스터 배포 워크플로(RBAC 포함), actionlint·shellcheck(미설치), Slack 측 동작(타임아웃 이후 게시 완료 여부, 봇 메시지 안의 `<!channel>` 발화).

### 14.2 13.5 반영 주장 재판정

| 항목 | 판정 | 근거 | 레인 |
|---|---|---|---|
| A1 헬스체크 | **RESOLVED** (Low 단서 P3) | `deploy_action.yaml:290-341`의 서비스명·포트·ns가 매니페스트와 일치하고, alpine busybox `wget`, `jq` 사전 점검이 있음. exec 폴백이 종료 중인 이전 파드를 검사할 수 있음 | ⑤ |
| A2 시계 통일 | **RESOLVED** | outbox 네이티브 쓰기에 `CURRENT_TIMESTAMP` 0건. `@CreationTimestamp`/`@UpdateTimestamp`의 `source` 기본값 VM(hibernate-core 7.4.5 바이트코드). 같은 계열이 CVE(`JpaCveEventRepository.kt:82,118,134`)와 스탠드업(`JpaSessionDispatchRepository.kt:36,51,68,84`)에 남음 — 현재 UTC DB에서는 일관되나 DB 존을 바꾸면 어긋남 | ① ⑥ |
| A3 CDC 소유권 | **RESOLVED** | `IN_PROGRESS` 분기 제거, 결함을 고정하던 테스트 반전(`DebeziumLogTailingProcessorTest.kt:165-181`), 상태 기록 실패가 리스너 밖으로 새지 않음, 토큰 전이 전부 조건부, ABA·오버플로·NULL 행 없음 | ① |
| A3 "레코드당 최악 약 53초 < 60초" | **PARTIAL** | Slack HTTP 합산(52.64초)은 맞음. DB 대기(`completeClaim` 5회 재시도, Hikari `connection-timeout`)가 빠짐 — 풀 고갈 시 약 94초. 문서 4곳의 수치가 서로 다름(O3) | ① |
| A3 "모든 Slack 호출에 6초 타임아웃" | **PARTIAL** | chat.*·views.open·response_url은 OkHttp `callTimeout` 6초 적용(바이트코드). `users.profile.get`은 JDK 클라이언트 connect 3초 + read 10초(`RestClientRequester.kt:23-35`) | ② |
| A4 시크릿 fail-fast | **RESOLVED** (실측) | 14.1. `local,slack-live`·`spring.profiles.include`·기본 프로파일 우회도 막힘 | ④ + 메인 |
| A5 dedup 상태 머신 | **PARTIAL** | 단일 JVM에서는 `compute` 원자성으로 정확. 복제본 간 공유는 결정 #34로 미결. 재생 요청을 그대로 처리(W4) | ④ |
| A6 필터 경로 | **RESOLVED** (실측) | 14.1 | ④ + 메인 |
| A7 매니페스트 키 | **RESOLVED** (실측) | 14.1 | ⑤ + 메인 |
| S8 / S8b 낙관 락·락 패자 피드백 | **RESOLVED** | 세 쓰기가 모두 관리 엔티티 + `saveAndFlush`. inverse bag에 `@OptimisticLock(excluded=false)`. 지연 쓰기는 ThreadLocal 큐 → `commit()` 반환 뒤 `REQUIRES_NEW`라 afterCommit 함정에 해당 안 함. 충돌 1회 재시도·회신 | ③ |
| S9 DLT | **RESOLVED** (O7 Low). Codex 2차는 **REGRESSED**로 판정 — 14.5 | partition −1 → null(spring-kafka 4.1.1)이라 DLT 파티션 수 무관. `setFailIfSendResultIsError(false)`는 의도된 선택이며, 버려진 레코드의 행은 `findStalePending`이 5분 뒤 복구(14.5) | ① + 메인 |
| S10 참가자 0명 회의 | **부분 해결** | 조회 쿼리는 모두 LEFT, DISTINCT 제거. 리마인더·아젠다 수신자에서 호스트 제외는 그대로(N3 제품 결정). `findPendingBefore`는 INNER + DISTINCT(무해) | ③ |
| S11 오류 코드 분류 | **RESOLVED** (T13 잔여) | `ratelimited` → rate-limited, `fatal_error`·`request_timeout` 영구, transient는 2개로 축소, `Retry-After` > 3초면 대기 없이 위임 | ② |
| S12 `or` / `errorMark` / `copy()` | **PARTIAL** | C8 회귀와 `copy()` 리셋은 해결. **중첩 스코프(`and{}`·`ifNotNull{}`·`shouldNotBeNullAnd{}`) 안의 `or`가 같은 필드의 바깥 오류를 지움**(W3, 프로브 실측) — 사용처 0건이라 잠복. Codex 2차는 RESOLVED로 봤으나 중첩 스코프는 검사하지 않음 | ④ |
| S13 본문 상한·response_url | **RESOLVED** | `contentLengthLong` 선검사 + `readNBytes(MAX+1)`, 파싱된 `HttpUrl` 객체를 그대로 전송, 리다이렉트 이중 차단, `peekBody(4096)` | ② ④ |
| S14 actuator | **RESOLVED** (local 잔존은 의도) | dev `health,info,metrics` + `when_authorized`, `run`의 `include=*` 제거, JMX·JDWP 루프백 | ④ ⑤ |
| S15 역할 캐시·실패 처리 | **PARTIAL** | 복제본 간 회수 지연 → 자기 재부여(T10). 트랜잭션 안의 USER 강등 무효(T11, 프로브 실측). Codex 2차는 fallback 코드의 존재만 확인(트랜잭션 밖) | ④ |
| S16 ops | 대부분 **RESOLVED**. (f) **PARTIAL**, (g) 의도적 보류 | (f) 종료 예산(T12) | ⑤ |
| S17 attempt/send 예산 | **RESOLVED** | `@bot status`는 갱신 안 됨(O4) | ① |
| S18 과거 시각·end_at | **RESOLVED** | 생성 경로와 같은 존·분 경계. V21은 MariaDB 문법·멱등 | ③ |
| S19 프롬프트 컨텍스트 이름 | **RESOLVED** (잔여 Low) | 유니코드 따옴표 유사 문자 통과, `\p{Cf}`가 ZWJ를 지움 | ② |
| S21 프로필 리졸버 | **RESOLVED** | 60초 negative cache, 10% 축출, URI 템플릿 인코딩. 동시 miss 미병합(D7) | ② |
| H14 SSE 상한 | **RESOLVED** | 한 줄·한 프레임 ≤ 512K자, 누적 ≤ 256K자, 오류 본문 ≤ 8,192자(한 줄 완성 전 검사) | ② |
| N1 / N2 | **RESOLVED** | claim·조회·저장이 한 `runInTx`, H2 tx 매니저 테스트 추가 | ③ |
| "이전 릴리스와 동시에 떠 있으면 안 된다" | **NOT ENFORCED** | T7 | ① ③ ⑤ |
| 배포 전 마이그레이션 `V20 → V22 → V21` | **불완전** | T8 | ③ ⑤ |

### 14.3 신규 결함 (심각도순)

"확인" 열은 메인 세션의 재검증 수준입니다. **실측**은 실행으로 확인, **소스**는 메인이 인용 라인을 직접 열어 확인, **레인**은 레인 보고를 근거로 채택하고 메인이 재확인하지 않은 경우입니다.

#### High

| ID | 결함 | 근거 | 실패 시나리오 | 도입 | 확인 |
|---|---|---|---|---|---|
| **T1** | **모든 `@Scheduled(fixedDelay)` 작업(10개)이 스레드 하나에서 직렬 실행되고, `spring.task.scheduling.pool.size: 4`는 효과가 없다.** 브랜치의 복구 스윕이 넘친 작업을 그 스레드에서 직접 발송해 악화시킨다 | `application.yaml:10-15`, `application-prod.yaml:9-11`(세 프로파일 모두 `spring.threads.virtual.enabled: true`), spring-context 7.0.9 `SimpleAsyncTaskScheduler.scheduleWithFixedDelay` → `fixedDelayExecutor`(코어 1) + `taskOnSchedulerThread`, `AsyncConfig.kt:29-41`(큐 = batchSize, `CallerRunsPolicy`), `OutboxRecoveryScheduler.kt:39,56-64`(stuck 100 + stale 100 일괄 제출) | ① `AI_PROVIDER=sidecar`면 `CveSummaryWorker.tick`이 최악 10 × 120초 동안 스레드를 점유하고, NVD 수집은 토픽당 최대 35초를 씀. 그동안 `MeetingReminderScheduler`("10분 전" 리마인더가 회의 시작 뒤에 도착), `StandupScheduler`, `DailyAgendaScheduler`, 복구 스윕, 보존이 모두 멈춤. ② Slack 장애 뒤 stuck + stale이 104건을 넘으면, 작업자 4 + 큐 100을 뺀 나머지(최대 96건)가 **스케줄러 스레드에서 순차 발송**(건당 최대 약 53초)되어 수십 분간 모든 스케줄 작업이 정지. `application.yaml` 주석이 막으려던 바로 그 기아가 그대로 남아 있음 | 가상 스레드·pool 설정은 main 기존(`dc2b904`), 스윕 + CallerRuns는 `8504c07` | **실측**(conditions 엔드포인트) + 바이트코드 + 레인⑥ 프로브(3초 작업 동안 200ms 주기 작업이 3015ms간 0회 실행) |
| **T2** | **스탠드업 루틴 하나의 비정상 cutoff 값이 모든 루틴의 스탠드업을 영구 정지시킨다** | `ModalTemplateBuilder.kt:520-526`(자유 텍스트 입력), `ParsedSubmissions.kt:178`(`toLongOrNull`), `Routine.kt:39`(양수인지만 검사), `StandupSchedulingService.kt:57-62,83`(루틴 단위 격리 없음), `StandupScheduler.kt:15-22`(4단계가 한 `runCatching`) | 누구든 `/standup setup`의 Cutoff에 `1000000000000000`을 넣으면 루틴이 저장되고, 이후 매 틱 `cutoffAnchor.plus(cutoffOffset)`에서 `DateTimeException`이 남. `openSessionsForToday`가 던지므로 같은 틱의 `sendPendingDispatches`·넛지·마감 감지가 **모든 루틴에서** 매번 건너뜀. MariaDB에서는 10자리 값만으로 DATETIME 범위를 넘어 같은 결과. 슬래시 커맨드에는 권한 검사가 없고(H11), `StandupSchedulingServiceTest.kt:244-263`이 이 전파를 정답으로 고정 | main 기존 | **소스** + jshell 재현 |
| **T3** | **AI 답변이 3,000자를 넘으면 Slack 게시가 영구 실패**하는데, 이력에는 COMPLETED로 남는다 | `AgentConverseService.kt:188,298-308`, `ModalTemplateBuilder.kt:79-84`, `ModalBlockBuilder.kt:117-124`(section 1개), 사이드카 허용 256K자 | `finalText`가 자르지 않은 채 section 하나의 mrkdwn으로 들어감 → section 텍스트 상한 3,000자 초과 → `invalid_blocks` → 영구 실패 → outbox FAILURE. 사용자는 무응답을 받음. 코드 설명·로그 요약 요청이면 흔한 길이. 같은 코드베이스의 CVE 경로는 이 한도를 알고 2,900자로 자름(`CveNotificationDispatcher.kt:158`) | main 기존 | **소스** |
| **T4** | **스탠드업 요약 전체가 section 하나**라 보통 규모 팀에서 그날 요약이 영구 유실된다 | `ModalTemplateBuilder.kt:603-632`(`onlyTextTemplate`), 답변 입력 `:452-455`(`max_length` 없음), `Routine.kt:55-57`(질문 8개 × 200자, 멤버 30명) | 예: 6명 × 3문항 × (질문 30자 + 답변 150자)면 3,000자 초과 → `invalid_blocks` → FAILURE. 세션은 이미 SUMMARIZED라 다음 cutoff 스윕도 다시 만들지 않음 | main 기존 | **소스** + Codex 2차 템플릿 실행(5명 × 700자 → 3,620자) |
| **T5** | **`/standup setup`의 완료·실패 회신이 채널 `""`로 발송돼 항상 유실**되고, 테스트 픽스처가 이를 가린다 | `SlackInteractionRequestParser.kt:60,182-188`(채널 복구는 reschedule/add-participant만), `SlackIntentResolver.kt:209`(`responseBasicInfo = basicInfo`), `StandupRoutineSetupService.kt:48-49` | view_submission의 `basicInfo.channel`은 설계상 `""`(`ViewSubmissionChannelRoutingRegressionTest.kt:150` "basicInfo.channel stays blank")인데 셋업 서비스가 그 채널로 `chat.postEphemeral`을 보냄 → `channel_not_found` → 영구 실패. 검증 실패 안내("Couldn't create…")도 사라져 사용자는 루틴이 안 만들어진 것을 모름. `StandupRoutineSetupServiceTest.kt:89`는 픽스처(`CommandDomainInputCreator.kt:60-61`)가 채널을 채워 넣어 통과 | main 기존 | **소스** |
| **T6** | **6초 `callTimeout`이 비멱등 POST를 그대로 재시도해 중복 게시**를 만들고, 스윕 재발송으로 증폭된다 | `ApplicationMessageDispatcher.kt:53-56`(`IOException`을 transient로), `:80-86`(callTimeout 6초), `:118-131`(RetryService 3회 → `transient_exhausted`), `SlackMessageRelayServiceImpl.kt:91-93`(IN_PROGRESS로 남겨 스윕에 위임), `AppConfig.kt:106`(`maxSends = 10`), 결함 고정 테스트 `ApplicationMessageDispatcherTest.kt:309-340`(`calls shouldBe 3`) | Slack이 느려 `chat.postMessage`가 7초에 성공하는 상황: 6초에 `InterruptedIOException` → 요청은 이미 처리됨 → 같은 폼 2회 재전송 → 최대 3건. 소진되면 300초 뒤 스윕이 다시 보냄(최대 10회 발송). main은 OkHttp read timeout 10초(바이트 사이 유휴 기준)라 6~10초 응답은 성공했음. `chat.postEphemeral`, response_url, 429 인라인 대기 뒤 두 번째 실행도 같음 | `cca9984`(6초 callTimeout, 스윕 재발송). 호출 안의 IOException 재시도는 main 기존 | **소스**. Slack이 연결이 끊긴 뒤에도 게시를 완료하는지는 미실측 |
| **T7** | **"이전 릴리스와 동시 기동 금지"를 매니페스트도 워크플로도 강제하지 않는다.** 자동 롤백도 혼재를 만든다 | `deployment.yaml:5-10`(`strategy` 없음 → RollingUpdate, maxSurge 1), `k8s/README.md:115-137`("Do not change the strategy in `deployment.yaml`", 머지 전 수동 `kubectl patch`), `deploy_action.yaml:397-398`(`rollout undo`), `V20__add_outbox_attempt_count.sql:17-24` | (a) 수동 patch를 잊으면 머지 즉시 자동 배포가 구·신 파드를 startup(최대 180초) + readiness 동안 함께 띄움. 같은 컨슈머 그룹과 스윕을 공유하므로 구 파드가 신 파드의 IN_PROGRESS를 재발송하고, JPA로 상태를 attempt 조건 없이 덮어써 SUCCESS → FAILURE가 가능함. (b) 첫 배포의 헬스 게이트가 실패하면 **롤링 롤백**이 같은 혼재를 만듦. (c) README 4단계는 조건 없이 RollingUpdate로 복원하라고 해, pre-V20으로 롤백된 뒤 다음 배포에서 다시 겹침. (d) 회의 쪽: 구 바이너리의 벌크 UPDATE는 `version`을 올리지 않아 lost update(M9) | `cca9984`(1회성 수동 절차로 설계) | **소스** |
| **T15** | **CVE digest가 전체 이벤트를 전달 완료로 claim한 뒤 본문을 2,900자로 잘라, 잘린 보안 알림이 조용히 사라진다**(Codex 2차로 Medium → High) | `CveNotificationDispatcher.kt:109-114`(claim 후 enqueue), `:131-142`(`capBody`), `application-prod.yaml:165`(`digest-summary-max-length: 700`), 테스트 `CveNotificationDispatcherTest.kt:538`(절단 길이·마커만 검사) | 요약 상한이 700자라 요약 달린 이벤트 5개 정도면 절단됨. 잘린 뒤쪽 이벤트는 제목조차 전송되지 않지만 `cve_delivery` 행이 있어 이후 조회에서도 제외되고 다시 발송되지 않음 | main 기존 | **소스** + Codex 2차 실행 재현(6건 입력 → 2,913자, 마지막 이벤트 식별자 없음) |

#### Medium

| ID | 결함 | 근거 | 요지 | 도입 | 확인 |
|---|---|---|---|---|---|
| **T8** | 배포 체크리스트에 V18·V19가 없고, 번호와 적용 순서가 다르며, readiness는 스키마 누락을 못 잡는다 | `review.md` 13.5 "배포 전 사람이 해야 할 일" 2번, main의 마지막 마이그레이션 = V17, `application-prod.yaml`의 `ddl-auto: none`, `db/migration/AGENTS.md:7`("Ordered patch scripts") | V18 없이 배포하면 기동·readiness·게이트는 모두 통과하지만 모든 `meetings` SELECT가 `Unknown column version`으로 실패(회의 기능 전체 500). V20·V22가 없으면 outbox claim이 전부 실패해 발송이 멈추는데 readiness는 UP. 번호순으로 적용하면 V21이 새 바이너리보다 먼저 실행됨 | `cca9984`(문서) | **소스** |
| **T9** | 스탠드업 답변을 다시 제출하면 항상 유니크 제약 위반 | `StandupRepositoryImpl.kt:81-101`(`removeIf` + `add` + `save`), `StandupSessionSchema.kt:116-124`(IDENTITY), V4 `uk_standup_answer_session_user` | IDENTITY라 merge 캐스케이드 시점에 INSERT가 즉시 실행되고 orphan DELETE는 flush 때 실행됨 → INSERT가 먼저 → `ConstraintViolationException` → 상호작용 tx 롤백 → 사용자 오류. DM 버튼을 두 번 누르거나 "Standup submitted." 갱신 전에 재제출하면 발생. infra 계층 standup 리포지토리 테스트 0건 | main 기존 | **Codex 실측**(Hibernate 7.4.5 + H2) + 레인⑥ 실측 + 메인이 ID 전략으로 메커니즘 확인 |
| **T10** | 역할 캐시가 복제본 간 회수를 60초 늦게 반영해, 회수된 ADMIN이 **스스로 재부여**할 수 있음. 결정 #15 위반 | `CommandRoleResolver.kt:22,36-50`(JVM별 캐시, 축출은 로컬만), `RoleManagementService.kt:97-107`, `docs/wiki/decisions.md:59-60`(#15 "대화 중 revoke 즉시 반영"), `deployment.yaml:6`(`replicas: 2`) | B가 `@bot revoke @A` → 파드 1만 축출 → 파드 2에는 A=ADMIN 캐시가 남음 → A가 60초 안에 `@bot grant @A admin`을 보내면 파드 2에서 ADMINISTRATION 게이트를 통과해 **영구 재부여**. MCP 게이트도 같은 캐시를 씀 | `cca9984` | **소스** |
| **T11** | S15의 "조회 실패 시 USER 강등"이 트랜잭션 경로에서 무효 | `CommandRoleResolver.kt:42-47`, 호출부 `SlackInteractionHandlerImpl.kt:53-76`, `SlackMentionEventHandlerImpl.kt:34-68`, 목만 쓰는 테스트 `CommandRoleResolverTest.kt:128-143` | 참여 중인 tx에서 리포지토리 예외 → Spring이 rollback-only로 표시 → `resolve`는 USER를 돌려주지만 커밋에서 `UnexpectedRollbackException` → 500. 그 사이 USER로 실행된 동기 리스너가 롤백되고, "falling back to USER" WARN은 사실과 다른 로그가 됨 | `cca9984` | 레인④ 실측(H2 + `JpaTransactionManager`) + **소스** |
| **T12** | 종료 예산이 레코드 1건의 최악 처리 시간을 못 담음 | `application-prod.yaml:7`(`timeout-per-shutdown-phase: 10s`), `KafkaConsumerConfiguration.kt:127-146`(`stopImmediate`·`shutdownTimeout` 미설정), `deployment.yaml:15-16`(grace 45초) | 종료 중 느린 Slack 호출을 하던 레코드는 발송 후 DataSource가 닫혀 완료 기록에 실패 → 다른 파드의 스윕이 재발송(중복). 수정: `stopImmediate = true`, `shutdownTimeout`·phase ≥ 60초, grace ≥ 90초 | `cca9984` | 레인⑤(확신도 med) |
| **T13** | `internal_error` 재시도 — Slack 문서상 "일부 작업이 이미 성공했을 수 있음" | `ApplicationMessageDispatcher.kt:45`, 고정 테스트 `ApplicationMessageDispatcherTest.kt:456-469` | 같은 경고가 붙은 `fatal_error`는 중복 방지를 이유로 영구 처리하면서 `internal_error`는 3회 + 스윕 재시도. 비멱등 메서드에서는 `service_unavailable`만 재시도해야 함 | `8504c07`~`cca9984` | 레인②(docs.slack.dev 문구 확인) |
| **T14** | 토큰·워크스페이스 전체 오류를 행 단위 영구 실패로 처리 | `ApplicationMessageDispatcher.kt:344-349,374-377` | 봇 토큰 교체·재설치 중(`invalid_auth`, `token_revoked`, `account_inactive`, `not_authed`, `missing_scope`) 들어온 모든 outbox 행이 즉시 FAILURE. 설정을 고쳐도 복구 불가. 시스템 오류는 보류 + 헬스 DOWN이어야 함 | main 기존(브랜치가 분류표를 새로 만들면서도 구분 안 함) | 레인② |
| **T16** | digest가 사용자당 하루 1통이 아니라 여러 통으로 쪼개짐 | `JpaCveDeliveryRepository.kt:31-47`(`ORDER BY e.id, s.userId` + limit), `CveNotificationDispatcher.kt:76-97`(limit 뒤 `groupBy(userId)`) | 구독자 10명 × 이벤트 11건 = 110쌍 → batch 50씩 틱 3번 → 사용자마다 digest 3통. `notification/AGENTS.md`의 "첫 틱이 전부 비운다"와 어긋남 | main 기존 | **소스** |
| **T17** | 신규 구독자에게 과거 이벤트가 한꺼번에 쏟아지고, 새 GitHub 토픽은 과거 릴리스 10건을 새 알림으로 보냄 | `JpaCveDeliveryRepository.kt:36-45`(구독 시각 조건 없음), `GithubReleaseSourceAdapter.kt:35,90`, `CveCollector.kt:43-53` | 오늘 구독하면 최근 7일치 DONE 이벤트 전부. IMMEDIATE 토픽이면 건당 DM 1통. 의도라면 구독 확인 문구에 안내 필요 | main 기존 | **소스**(의도 여부 미확인) |
| **T18** | 스탠드업 DM 발송이 일시 오류 한 번에 FAILED(종단)로 끝나고, 넛지는 claim이 먼저 커밋됨 — 12.2 N1과 같은 패턴 | `StandupSchedulingService.kt:141,169-179,214-237`, `JpaSessionDispatchRepository.kt:20,85`, 고정 테스트 `StandupSchedulingServiceTest.kt:388-413` | outbox 저장 tx가 데드락·커넥션 타임아웃으로 실패하면 그 멤버는 그날 DM·넛지를 못 받음. 3.1 표의 "안전" 판정 예외 | main 기존 | 레인⑥ |
| **T19** | 마감 뒤 제출한 답변이 "Standup submitted."로 안내되지만 요약에는 반영 안 됨. SUMMARIZED 세션에도 초대 DM 발송 | `StandupRepositoryImpl.kt:81-101`(상태 검사 없음), `StandupSchedulingService.kt:117-132`(`sessionStatus`를 조회하고도 안 씀) | 늦은 응답자는 반영된 줄 앎. T1로 틱이 밀리면 닫힌 세션에 대한 "Fill in" DM이 도착 | main 기존 | 레인⑥⑦ 독립 일치 |
| **T20** | 에이전트 세션이 스레드 단위로 사용자 간 공유됨 | `AgentConverseService.kt:90`(`channel:threadId`), `AppMentionContextParser.kt:182`(멘션에서는 threadId가 항상 non-null이라 `?: publisherId` 분기는 도달 불가) | 관리자 A의 턴에서 모델이 호출한 `list_roles`·`get_status`·A의 `list_meetings` 원본 결과가 provider 세션에 남고, 같은 스레드의 B가 같은 세션을 재개해 호출 단위 권한 검사를 대화 기억으로 우회. 답변 자체는 이미 스레드에 공개되므로 새는 것은 답변에 안 담긴 도구 결과 | main 기존 | **소스**(사이드카의 세션 재개 동작은 미확인) |
| **T21** | Slack mrkdwn 이스케이프가 저장소 전체에 0건 | 공통 `ModalElementBuilder.kt:31-35`. 싱크: AI 답변, 스탠드업 요약(`ModalTemplateBuilder.kt:613,626`), 일정 변경 공지, notice, CVE DM(`CveNotificationDispatcher.kt:126-151`, prod 기본 Noop 요약기가 GitHub 릴리스 본문을 그대로 사용) | 스탠드업 답변에 `<!channel>`을 넣으면 요약 채널 전체에 봇 명의로 @channel. 회의 제목 `<!channel>`(20자 한도 통과)이 AI 답변을 거쳐 발화. `<https://evil\|정상링크>` 위장 링크 | main 기존 | **소스**(grep `&lt;` 0건). 봇 게시물에서 실제로 발화하는지는 미실측 |
| **T22** | 거절 사유 상세가 255자를 넘으면 DB 오류로 거절 제출 실패. BEFORE_COMMIT 리스너가 망가진 tx 안에서 3회 재시도 | `ModalTemplateBuilder.kt:340-344`(`max_length` 없음), `V8__…sql:18`(VARCHAR(255)), `MeetingServiceImpl.kt:69-96`(`retryService.execute`가 같은 tx·세션에서 재시도) | strict MariaDB·H2에서 `Data too long` → 롤백 → 500 → 모달에는 일반 오류만. 재시도는 rollback-only tx에서 무의미 | main 기존 | **소스** |
| **T23** | k8s에서 MCP 필터의 루프백 검사를 `X-Forwarded-For`로 우회 가능 | `McpTurnTokenFilter.kt:22,32-33`, `server.forward-headers-strategy` 미설정 | Boot 4.1.1은 `KUBERNETES_SERVICE_HOST`가 있으면 forward headers를 켬(`CloudPlatform.isUsingForwardHeaders()`, javap). 클러스터 안 다른 파드가 `X-Forwarded-For: 127.0.0.1`을 보내면 경계 한 겹이 무너짐. 턴 토큰은 여전히 필요 | main 기존 | 레인⑦(Jetty customizer 세부는 문서 기준) |
| **T24** | 멘션 프롬프트에서 링크·코드블록·다른 사용자 멘션이 누락 | `SlackMentionMapper.kt:20-43`, `AppMentionContextParser.kt:181` | 첫 `rich_text_section`의 `text`·`user`만 읽음 → "이 링크 요약해줘 https://…"의 링크, `rich_text_preformatted`, `@alice`가 빠진 채 모델에 전달 | main 기존 | 레인⑦ |
| **T25** | OSIV 기본 on에서 지연 쓰기의 첫 시도가 상호작용 tx의 영속성 컨텍스트를 물려받는데, 테스트·문서는 반대로 주장 | `SlackInteractionHandlerImpl.kt:55-57`, `MeetingServiceImpl.kt:346-349`, `MeetingWriteJpaTransactionTest.kt:59-62,212-223`, `service/meeting/AGENTS.md:42,46,50` | 현재는 상호작용 tx가 `MeetingSchema`를 로드하지 않아 잠복. 사전 권한 확인처럼 회의를 읽는 코드가 추가되면 첫 시도가 stale 상태로 버전 검사 없는 오답(`OVER_CAPACITY` 등)을 회신. 운영 HTTP 경로(OSIV + 실제 `JpaTransactionManager`)를 모델링한 테스트 0건. 풀 교착은 요청당 1커넥션이라 **발생하지 않으며**, `application-prod.yaml:16-17`의 "two connections" 주석이 낡음(M2) | `cca9984` | 레인③(바이트코드) + 메인 부팅 WARN |
| **T26** | CVE 요약 `markDone` 실패 시 재시도 예산 없이 15분마다 무한 재요약 | `CveSummaryWorker.kt:34-37,74-79`, `JpaCveEventRepository.kt:129-141`(`resetStuck`이 `retry_count` 유지), V14 `ai_summary TEXT` | 요약이 TEXT 65,535바이트를 넘으면(한국어 약 2.2만 자) strict 모드에서 매번 실패 → SUMMARIZING → 15분 뒤 PENDING → 사이드카 재호출 반복 | main 기존 | 레인⑥ |
| **T27** | 서명 필터의 모든 로그가 사유 대신 람다 객체로 찍힘 | `SlackRequestVerificationFilter.kt:19`(최상위 `logger`), `:51,65,87`(`logger.warn { … }`) | `OncePerRequestFilter` 상속 → 클래스 안의 `logger`가 상속된 commons-logging `Log`(`GenericFilterBean.logger`)로 해석 → `warn(Object)`에 람다가 들어가 `toString()`만 출력. 공격·시계 오차·시크릿 오설정을 로그로 구분할 수 없고, local의 "검증 비활성" 경고도 읽을 수 없음. 저장소에서 이 패턴은 여기 한 곳 | main 기존(브랜치가 헤더 검사 로그 `:65`를 추가하며 확대) | **실측** |

#### Low

| ID | 요지 | 근거 | 레인 |
|---|---|---|---|
| O2 | polling 모드에서 fixedRate 틱이 새 가상 스레드마다 겹쳐, 백로그 중 5초마다 발송 레인이 하나씩 늘어남(토큰 덕분에 중복 발송은 없음) | `PollingMessageProcessor.kt:18-31` | ① |
| O3 | "53초" 산식에 DB 대기 누락, 문서 4곳 수치 불일치(49.6 / ~50 / 52.64 / 약 53) | `SlackMessageRelayServiceImpl.kt:155-168` | ① |
| O4 | `@bot status`가 스윕 유예·retrying 카운터 없이 판정해 actuator 헬스와 불일치(`health/AGENTS.md`의 "never disagree" 위반) | `OpsStatusService.kt:72-87` vs `OutboxHealthIndicator.kt:33-39` | ① + 메인 |
| O6 | 미지원 `schemaVersion` 행을 stuck이 아니라 FAILURE로 영구 처리(문서는 "stuck") | `SlackMessageRelayServiceImpl.kt:69-79` | ① |
| O7 | DLT 토픽이 없고 생성도 막히면 dead-letter 1건마다 `max.block.ms`(60초) 동안 리스너가 블록 | `KafkaConsumerConfiguration.kt:41-59` | ① |
| O8 | 24시간이 지난 PENDING도 stale claim으로 발송(문서 "24 h bound stops everything"과 불일치) | `OutboxRecoveryScheduler.kt:57-60` | ① |
| D4 | `Retry-After` 상한 없음 → 극단값이면 `LocalDateTime.plus` 예외가 `runCatching` 밖에서 발생 | `ApplicationMessageDispatcher.kt:239`, `SlackMessageRelayServiceImpl.kt:117-118` | ② |
| D5 | response_url의 2xx 비-JSON 본문은 무엇이든 성공 처리(문서는 평문 `ok`만) | `ApplicationMessageDispatcher.kt:338-353` | ② |
| D6 | `SidecarAgentClient.converse`가 `InterruptedException`과 `Error`까지 삼킴 | `SidecarAgentClient.kt:56-64` | ② |
| D7 | 프로필 리졸버가 동시 miss를 합치지 않음, 가득 찬 캐시의 정렬 축출이 동시 실행 | `SlackUserProfileResolver.kt:33-53` | ② |
| P3 | exec 폴백이 preStop 중인 이전 파드의 readiness UP으로 통과할 수 있음 | `deploy_action.yaml:308` | ⑤ |
| P4 | `run:` 기본 셸에 `pipefail` 없음 → 롤백 판정의 jq 실패가 "변경 없음"으로 흡수 | `deploy_action.yaml:243,387` | ⑤ |
| P5 | CI에서 Test 3개 × `-Xmx4g` + 데몬 3g×2 = 최대 18g(16GB 러너) | `gradle-ci.properties:7-16` | ⑤ |
| W3 | 중첩 스코프 안의 `or`가 같은 필드의 바깥 오류 삭제(`p1 AND (p2 OR p3)`가 참이 됨). 사용처 0건 | `Validation.kt:23,49-61` | ④ 실측 |
| W4 | dedup이 재시도 헤더 없는 동일 본문(=재생)을 그대로 다시 처리. 테스트가 고정 | `SlackRetryDeduplicator.kt:92-95` | ④ |
| W5 | "값싼 선거절"은 형식만 맞추면 통과 → 무인증으로 요청당 1 MiB 버퍼링 가능, `security/AGENTS.md:53-54` 서술 틀림 | `SlackRequestVerificationFilter.kt:58-76` | ④ |
| W6 | 타임스탬프 `abs(now - ts)` 정수 오버플로(`ts = Long.MIN_VALUE + now`면 "신선"). HMAC이 여전히 필요 | `SlackSignatureVerifier.kt:58` | ④ 실측 |
| W7 | `./run app.jar`의 기본 환경이 `local` → 공백 시크릿이면 검증을 끈 채 모든 인터페이스에서 기동 | `run:94`, `application-local.yaml:49-53,94` | ④ |
| V5 | `/latest <key>`가 키를 소문자화해 대문자 섞인 토픽을 못 찾음 | `CveQuerySlashServiceImpl.kt:43-44` | ⑥ |
| V6 | 토픽 `displayName` 75자 초과 또는 100개 초과 시 구독 모달이 모든 사용자에게 안 열림 | `ModalTemplateBuilder.kt:595`, `CveTopicBootstrap.kt:26-27` | ⑥⑦ 일치 |
| V7 | NVD 응답을 첫 페이지만 읽음(2,000건 초과분 유실) | `NvdCveSourceAdapter.kt:42-47,98-99` | ⑥ |
| V8 | `GITHUB_TOKEN` 기본값 공백 → 익명 한도(시간당 60회)가 토픽 5개 정도에서 가득 참 | `application-prod.yaml:151` | ⑥ |
| V10 | 복제본 2개 동시 기동 시 토픽 부트스트랩 UNIQUE 경합으로 파드 1개 기동 실패 | `CveTopicRepositoryImpl.kt:11-24` | ⑥ |
| U6 | `detectCutoffs`에서 한 세션의 예외가 나머지 세션의 요약을 모두 막음 | `StandupSchedulingService.kt:246-258` | ⑥ |
| U7 | 루틴 비활성화·수정 경로가 없음. `/standup setup`을 재실행하면 루틴이 중복 생성돼 DM 2통(큐 막힘 부분은 T28로 승격) | `StandupRepository.deactivateRoutine`(프로덕션 호출 0건) | ⑥ |
| U8 | cutoff "abc"가 조용히 120분으로 대체, 정원 검사가 중복 제거 전에 수행 | `ParsedSubmissions.kt:177-179`, `Routine.kt:70-74` | ⑥ |
| A9 | 워크플로·타 앱이 올린 app_mention(`blocks`/`user` 없음)이 역직렬화 예외로 500 → Slack 3회 재전송 | `EventCallbackData.kt:13,25` | ⑦ |
| A11 | 모든 봇 메시지의 대체 `text`가 라우팅 토큰이라 푸시 알림 미리보기에 UUID 노출 | `SlackApiEventConstructor.kt:703,734` | ⑦ |
| M5 | 리마인더 조회가 컬렉션 fetch + Pageable이라 SQL LIMIT 없이 백로그 전체를 메모리에 적재 | `JpaMeetingReminderRepository.kt:17-32` | ③ |
| M6 | materialize와 reschedule 경합 시 옛 시각 리마인더가 발송되고 새 시각 리마인더는 영구 누락 | `MeetingReminderSchedulingService.kt:57-75` | ③ |
| M7 | 리마인더 claim 이후 취소를 재확인하지 않음 → 취소된 회의 리마인더 발송 | `MeetingReminderSchedulingService.kt:107-138` | ③ |
| M8 | 수동 tx 경계의 catch에서 rollback/commit이 다시 던지면 원래 예외가 가려짐 등 | `SlackInteractionHandlerImpl.kt:66-72` | ③ |
| R3-07 | Socket Mode가 slash·event를 처리 전에 ACK하고, interactive는 처리 예외를 `getOrNull()`로 삼킨 뒤 빈 성공 ACK를 보냄 → DB 장애로 롤백돼도 Slack은 정상 수신으로 보고 재처리 없음. **local 전용** | `SocketModeReceiver.kt:50,59,145-149` | Codex 2차 + 메인 |
| R3-S20 | dedup 맵이 캡(1만 건)에 도달하면, 지울 COMPLETED 항목이 없어도 새 요청마다 `trimCompleted`가 전체를 필터·정렬(한 번에 하나, `tryLock`). 엔트리는 서명 검증 뒤에만 생겨 무인증 유발은 불가 | `SlackRetryDeduplicator.kt:77,137-149` | Codex 2차 + 메인 |

### 14.4 다음 반영 우선순위

**Tier A — 머지·배포 전** (각각 작은 수정)

| # | 항목 | 수정 방향 |
|---|---|---|
| A1 | **T7** 동시 기동 금지 | 이 릴리스의 `deployment.yaml`에 `strategy: {type: Recreate}`를 넣는다(롤아웃·`rollout undo` 모두 Recreate로 진행되고, 후속 PR에서 필드를 지우면 three-way merge가 기본값으로 되돌림). 또는 apply 전에 live strategy·replicas를 검사하는 가드 스텝. README 4단계는 "새 릴리스가 떠 있을 때만"으로 한정 |
| A2 | **T8** 마이그레이션 체크리스트 | V18(중복 참가자 사전 점검) → V19 → V20 → V22 → 구 파드 종료 → 배포 → V21. `db/migration/AGENTS.md`에 "번호 ≠ 적용 순서" 예외 명시. 선택: 기동 시 필수 컬럼 존재 검사 |
| A3 | **T6** 타임아웃 재시도 중복 | 비멱등 메서드(postMessage·postEphemeral·response_url)는 요청 본문 전송 뒤 타임아웃이면 재시도하지 않는다(OkHttp `EventListener.requestBodyEnd`로 판정). 연결 단계 실패(`ConnectException`·`UnknownHost`·TLS)만 재시도. `ApplicationMessageDispatcherTest.kt:337` 기대값 반전. T13(`internal_error`)을 함께 처리 |
| A4 | **T1** 스케줄러 | `ThreadPoolTaskScheduler`를 `taskScheduler` 빈으로 명시(pool ≥ 4)하거나, 오래 걸리는 CVE 작업을 전용 executor로 분리. 복구 스윕은 relay 큐의 잔여 용량만큼만 claim·제출하고, 거절되면 inline 실행 대신 버린다(행은 300초 뒤 다시 reclaim). 14.1의 conditions 확인을 Spring 배선 스모크 테스트로 고정 |
| A5 | **T10 + T11** 역할 | `ADMINISTRATION`/`OPERATIONS` 명령과 MCP 호출은 캐시를 우회해 DB에서 조회(또는 USER만 캐시). 역할 조회는 상호작용·멘션 tx 시작 전이나 `REQUIRES_NEW`/`NOT_SUPPORTED` 읽기로 분리. 실제 `JpaTransactionManager` 회귀 테스트 |
| A6 | **T2** 스탠드업 정지 | cutoff 상한 검증(예: 1–1440분, 모달은 `number_input` + max), `openSessionForRoutine`과 틱의 각 단계를 루틴·세션 단위 `runCatching`으로 격리(U6 동시 해결). `StandupSchedulingServiceTest.kt:244-263` 기대값 반전 |

**Tier B — 다음 PR (사용자 가시 결함)**
- **T15**: claim 전에 절단하거나 메시지를 여러 통으로 분할해, claim한 이벤트가 모두 본문에 들어가도록 보장합니다. 테스트는 길이가 아니라 포함 여부를 단언해야 합니다. Codex 2차는 T15·T4를 머지 전 필수로 권고했습니다. 다만 브랜치가 만든 결함이 아니므로 이 문서는 Tier B 최상단에 둡니다.
- T3·T4: section을 2,900자 단위로 분할(50블록 이내)하고 스탠드업 답변 입력에 `max_length`를 둔다. 렌더된 페이로드가 Block Kit 한도(section 3,000, 블록 50, option 75, 옵션 100)를 넘지 않는지 단언하는 테스트를 추가한다.
- T5: 회신 대상을 `payload.commandChannel`로 바꾸고, 검증 실패는 `response_action: errors`로 모달 안에서 반환한다. 픽스처 채널을 `""`로 맞춘다.
- T9: 기존 답변 행을 갱신하거나 `ON DUPLICATE KEY UPDATE`를 쓴다. T19의 상태 검사도 같은 메서드에서 처리한다.
- T12: 종료 예산을 조정한다.
- T14: 시스템 오류 부류를 보류 + 헬스 DOWN으로 처리한다.
- T16·T17: digest를 사용자 단위로 페이징하고, 구독 시각 조건을 추가한다.
- T28: dispatch 조회에 활성 루틴 조건을 넣거나, 건너뛴 dispatch를 SKIPPED로 표시한다.
- T18: 실패 시 PENDING 복귀 + 횟수 제한을 두고, 넛지 claim을 저장 tx에 합류시킨다.
- T21: `&`·`<`·`>` 이스케이프를 공통 함수로 만들고, AI 출력의 `<!channel|here|everyone>`을 무력화한다.
- T22: 입력에 `max_length = 255`를 두고, BEFORE_COMMIT 리스너 안의 재시도를 제거한다.

**Tier C — 정리·하드닝**: T20(`sessionKey`에 요청자 포함), T23(`server.forward-headers-strategy: none`), T24, T25(`spring.jpa.open-in-view: false` 명시 결정과 OSIV 테스트, "two connections" 주석 정정), T26, T27(`KotlinLogging.logger {}`를 다른 이름으로 바꾸거나 `this@…` 회피), Low 전부, 14.6 문서 드리프트. **여전히 의도적 미착수**: C6, N3, `SlashCommandGate`, detekt/JaCoCo, R2→R1, 복제본 간 dedup(결정 #34).

### 14.5 교차 일치 · 조정 · 기각

**독립 수렴**(서로 다른 레인이 같은 결론에 도달 — 신뢰도 높음)
- T1: 레인①(O1, CallerRuns 관점) + 레인⑥(V1, CVE 작업 관점) + 메인 실측
- T9: Codex(실측) + 레인⑥(U2, 실측)
- T7: 레인①(O5, 자동 롤백 경로) + 레인⑤(P1, 수동 절차) + 레인③(M9, 회의 lost update)
- T8: 레인③(M3) + 레인⑤(P2)
- T19: 레인⑥(U3) + 레인⑦(A10)
- T21: 레인⑥(V9, CVE) + 레인⑦(A6, AI·스탠드업)
- V6: 레인⑥ + 레인⑦(A12)

**Codex 1차(09-28, 한도로 중단) 중간 판단 4건의 처리**

| Codex 판단 | 메인 판정 |
|---|---|
| 스탠드업 답변 수정 시 INSERT가 DELETE보다 먼저 나가 유니크 위반(실행 재현) | **채택** → T9. IDENTITY 전략이 원인임을 확인 |
| `cca9984`의 DLT 설정이 전송 실패를 로그만 남기고 정상 반환 → 원본 오프셋 진행(실행 재현) | **사실이나 Low로 하향**. `setFailIfSendResultIsError(false)`는 13.3이 제시한 선택지이고, 버려진 CDC 레코드의 outbox 행은 PENDING으로 남아 `OutboxRecoveryScheduler.findStalePending`이 300초 뒤 발송함 → 메시지 유실이 아니라 DLT 포렌식 사본 유실. O7과 함께 처리 |
| CVE digest가 여러 이벤트를 전달 완료로 기록한 뒤 본문을 자름 | **채택** → T15 |
| 비활성 스탠드업 루틴의 대기 행이 배치 앞부분을 계속 차지 | **채택** → U7, 2차 대조 후 T28(Medium)로 승격 |

**Codex 2차(09-30, 완주) 대조** — 14장·레인 산출물을 읽지 않는 조건으로 실행. 테스트 59개(`service.relay.*`, `MeetingWriteJpaTransactionTest`)를 재실행했고, 일부 항목은 함수·템플릿을 직접 실행해 확인. 권고는 **머지 보류**.

| Codex | 심각도(Codex) | 내용 | 이 장과의 관계 · 메인 판정 |
|---|---|---|---|
| R3-01 | High | CVE digest가 잘라낸 이벤트도 전달 완료로 기록(실행 재현) | **T15와 동일 → High로 상향 채택**. prod 요약 상한 700자라 흔한 경로 |
| R3-02 | High | 정상 크기의 스탠드업 응답만으로 요약 발송 실패(템플릿 실행 3,620자) | **T4와 동일, 채택**. "SUMMARIZED라 재생성 없음"을 T4에 추가 |
| R3-03 | Medium | DLT 전송 실패를 복구 성공으로 처리 → DLT replay 불가, S9 **REGRESSED** | **판정 차이, 메인은 Low 유지**(아래) |
| R3-04 | Medium | 넛지 claim과 outbox 저장의 tx 분리 | **T18과 동일, 채택**(레인⑥ + Codex 독립 일치) |
| R3-05 | Medium | 마감된 답변을 성공 접수하지만 요약에 미반영 | **T19와 동일, 채택**(레인⑥⑦ + Codex 3중 일치). 오래된 DM에서도 모달을 다시 열 수 있다는 점 추가 |
| R3-06 | Medium | 비활성 루틴의 오래된 dispatch가 전체 큐를 막음 | **채택 → T28(Medium) 신설**. 레인⑥은 U7(Low)에 묶었으나, 루틴을 멈출 방법이 DB 수정뿐이라 발생 조건이 운영에서 자연스럽게 생김 |
| R3-07 | Medium | Socket Mode가 처리 실패에도 성공 ACK | **채택하되 Low**: local 프로파일 전용 개발 경로 |
| Tier C 지적 | — | dedup 맵이 in-flight로 가득 차면 요청마다 전체 스캔 | **채택(Low)** → R3-S20 |
| Top 5 #4 | — | 2 replicas에서 공유 dedup(A5) | 기존 결정 #34(열린 결정). Codex는 머지 전 필수로 봄 |
| Top 5 #5 | — | 첫 배포 절차(V18·V20·V22 + 구 파드 종료) 강제 | **T7 + T8과 동일**(4중 일치: 레인①③⑤ + Codex) |

**R3-03(DLT) 판정 차이의 근거**: DLT로 가는 레코드는 세 부류입니다. ① 역직렬화 실패 ② `CdcRecordParseException` ③ `findById`/`claim`의 DB 예외(재시도 2회 후)입니다. 세 경우 모두 해당 outbox 행은 DB에 PENDING으로 남으므로 `findStalePending`이 300초 뒤 발송합니다. 즉 DLT 발행이 실패해도 **비즈니스 메시지는 유실되지 않고**, 잃는 것은 CDC 레코드의 포렌식 사본입니다. 반대로 `true`로 바꾸면 13.2 S9(c)처럼 DLT 장애가 파티션을 무한 재전달로 막습니다. 따라서 이 장은 설계 선택으로 보고 **Low**를 유지합니다. 권고는 `false`를 유지하되 DLT 실패 카운터(메트릭·알림)를 추가하고, DLT producer의 `max.block.ms`를 줄이는 것(O7)입니다. DLT를 감사(audit) 기록으로 쓸 계획이면 Codex 판정(Medium)이 맞습니다.

**Codex 2차가 찾지 못한 것**: T1(부팅해야 드러나는 스케줄러 선택), T2, T3, T5, T6, T7의 자동 롤백 경로, T9(1차에서는 재현했으나 2차 보고에 없음), T10, T11(트랜잭션 안 실측이 필요), T27(부팅 로그), W3(중첩 스코프). 반대로 R3-S20은 레인이 놓친 것을 Codex만 짚었습니다. 정적 리뷰 한 번으로는 이 장 High의 절반을 놓쳤다는 뜻이므로, **레인 병렬 + 부팅 실측 + 독립 Codex의 조합**을 유지할 가치가 있습니다.

**심각도 조정·기각**
- T20(레인⑦ High) → **Medium**: 답변 자체는 이미 스레드에 공개되고, 새는 것은 답변에 담기지 않은 도구 결과뿐. 사이드카의 세션 재개 동작도 미확인.
- T10(레인④ Medium~High) → **Medium**: 내부 관리자의 악의를 전제하고, 60초 창에 다른 복제본으로 라우팅돼야 함.
- 레인⑤ P1(c) "13.5의 '3×1536Mi' 근거가 거꾸로" → **기각**: 13.5 문장은 RollingUpdate를 유지할 때의 필요량 서술로도 읽힘.
- 메인이 슬래시 커맨드 실측에서 본 500 → **결함 아님**: 합성 페이로드에 Slack이 항상 보내는 `token` 필드가 빠져서 난 것.
- 레인③의 "prod 프로파일 부팅 미확인" → 메인이 14.1에서 확인함.

**결함 동작을 정답으로 고정한 테스트**(13.4 교훈이 다시 확인됨): `ApplicationMessageDispatcherTest.kt:309-340`(T6), `:456-469`(T13), `StandupSchedulingServiceTest.kt:244-263`(T2), `:388-413`(T18), `SlackRetryDeduplicatorTest.kt:91-101`(W4), `StandupRoutineSetupServiceTest.kt:89` + 픽스처(T5), `CommandRoleResolverTest.kt:128-143`(T11, 트랜잭션 없는 목), `MeetingWriteJpaTransactionTest`(T25, 운영에서 쓰지 않는 인라인 경로).

### 14.6 문서 드리프트 (브랜치가 만든 것 위주)

| 문서 | 서술 | 실제 | 조치 |
|---|---|---|---|
| `review.md` 13.5 "배포 전 사람이 해야 할 일" 2번 | `V20 → V22 → V21` | V18·V19도 이 릴리스에 필요 | T8 |
| `application-prod.yaml:16-17`, `resources/AGENTS.md:57-59` | "Meeting writes hold two connections", ×2 풀 사이징 | OSIV에서 요청당 1커넥션 | 정정(T25) |
| `V18__…sql:8-10` | `OPTIMISTIC_FORCE_INCREMENT`로 잠근다 | 관리 엔티티 + `@Version` | 정정 |
| `repository/meeting/AGENTS.md:74-75,100-102` | 모든 읽기가 LEFT JOIN FETCH, DISTINCT 없음 | `findPendingBefore`는 INNER + DISTINCT | 정정 |
| `V21__…sql:26-29` | 구 바이너리가 end_at을 재계산 | 실제 구 바이너리(main)는 `start_at`만 바꿈 → 이것이 V21 대기의 진짜 사유 | 정정 |
| `events-and-outbox.md:62-64`, relay `AGENTS.md`, `impl/command/AGENTS.md`, 13.5 | 레코드당 49.6 / ~50 / 52.64 / 약 53초 | 하나로 통일하고 DB 대기 항목 추가 | O3 |
| `application/.../configurations/AGENTS.md` | "overflow runs on those scheduler threads"(복수) | 가상 스레드 모드에서 fixed-delay 스레드는 1개 | T1 |
| `health/AGENTS.md` | "the chat reply and the health endpoint must never disagree" | 판정 로직이 다름 | O4 |
| `security/AGENTS.md:53-54` | "unsigned flood costs no buffering" | 형식만 맞추면 1 MiB 버퍼링 | W5 |
| `security/mcp/AGENTS.md` | "Behind a reverse proxy remoteAddr is the proxy" | k8s에서는 forward headers가 자동으로 켜짐 | T23 |
| `k8s/README.md:83-84`, `k8s/AGENTS.md:37` | 어떤 매니페스트도 `metadata.namespace`를 설정하지 않음 | `route/httpRoute.yaml:5` `namespace: your-namespace` | 정정 |
| `k8s/AGENTS.md:30,41,48-51` | 변수 목록 없는 envsubst / "five `SQL_*` keys" / `SLACK_CDC_TOPIC`이 없어도 기동 | `'${IMAGE_NAME}'` 제한 / 6개 / `@KafkaListener` 플레이스홀더 해석 실패로 기동 실패 | 정정 |
| `dev-environment.md:156-158` vs `:184-185` | 롤백 조건이 "새 리비전"만 | 템플릿 해시 **또는** 리비전 | 통일 |
| `gradle-config/AGENTS.md:50` | "All three presets" | 4개(ci 포함) | 정정 |
| `exception/meeting/AGENTS.md`, `standup/schema/AGENTS.md`, `command/entity/AGENTS.md` 외 3곳 | Updated 날짜·본문이 코드 변경을 반영 안 함 | — | 규칙대로 갱신 |
| `AGENTS.md:106-107`, `README.md` | Jackson BOM 3.2.0, Spring AI 2.0.0 | `build.gradle.kts` 3.2.2 / 2.0.1 | 정정(main 기존) |

### 14.7 이번 회차의 한계와 교훈

- **Codex는 2차 실행에서 완주했습니다.**
  - 1차는 AGENTS.md 전체를 출력하다 토큰 한도에 걸렸습니다.
  - 2차는 좁은 범위로 읽기와 A·B절 우선 작성을 조건으로 줘 완주했습니다.
  - 대조 결과 판정이 갈린 것은 1건(R3-03)이고, 새로 반영한 것은 T15 상향, T28 신설, Low 2건입니다(14.5). 독립성을 위해 14장을 읽지 말라고 지시했으며, 실제로 14장의 T-번호를 인용하지 않았습니다.
- **부팅 실측은 단위 테스트가 원천적으로 못 잡는 결함을 드러냈습니다.** T1(실제 스케줄러 구현 선택)과 T27(상속 필드 섀도잉)은 1,323개 테스트가 모두 녹색인 상태에서 부팅 한 번으로 드러났습니다. 12.3부터 미착수로 남은 **Spring 배선 스모크 테스트**는 T1을 고정하는 테스트로 바로 시작할 수 있습니다: `@SpringBootTest`에서 `taskScheduler` 빈 타입과 `relayTaskExecutor` 선택을 단언.
- **Block Kit 한도는 테스트 계층에 존재하지 않습니다.** T3·T4·V6이 모두 여기서 나왔습니다. 렌더러 출력에 한도 단언을 거는 테스트 한 벌로 이 부류 전체를 막을 수 있습니다.
- **"브랜치가 건드리지 않은 영역" 레인이 High의 대부분을 찾았습니다.** 이전 두 회차는 diff 중심이었습니다. 다음 회차에도 변경되지 않은 영역 레인을 최소 1개 유지할 것을 권합니다.

**재현 명령**

```bash
# 부팅 실측 (H2 인메모리, Kafka 도달 불가 주소, 외부 호출 없음)
SLACK_API_TOKEN=xoxb-dummy SLACK_SIGNING_SECRET=testsecret java -jar application/build/libs/application-alpha.jar \
  --spring.profiles.active=local --server.port=19000 \
  --spring.datasource.url='jdbc:h2:mem:cc;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1' \
  --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa --spring.datasource.password= \
  --spring.jpa.hibernate.ddl-auto=create --spring.kafka.bootstrap-servers=127.0.0.1:1 \
  --slack.app.mode.outbox-reading-strategy=polling --slack.app.mode.event-publisher=application_event

# T1: 실제 선택된 스케줄러 (기대: taskSchedulerVirtualThreads)
curl -s localhost:19000/api/actuator/conditions | grep -o 'DefaultTaskSchedulerConfiguration#[A-Za-z]*' | sort -u

# A6: 무서명 우회 입력 (기대: 전부 401/400)
for p in /api/sla%63k/events /api/x/../slack/events '/api/slack;x=1/events' /api/slack%2Fevents; do
  curl -s -o /dev/null -w "%{http_code} $p\n" --path-as-is -X POST -H 'Content-Type: application/json' \
    --data '{"type":"url_verification","challenge":"x"}' "http://localhost:19000$p"; done

# T27: 거부 로그가 람다 toString으로 찍히는지
grep 'SlackRequestVerificationFilter\$\$Lambda' <부팅 로그>

# T2: Instant 오버플로
printf 'java.time.Instant.now().plus(java.time.Duration.ofMinutes(1000000000000000L))\n/exit\n' | jshell -q
```

---

## 15. 14장 반영 — 수정 브랜치 `feature/review-round3-fixes` (2026-09-30 ~ 10-02)

> **브랜치**: `feature/review-critical-fixes`의 `b9c261a`(14장 커밋)에서 분기. 항목별 커밋 105개(`b9c261a..e0bace7`, 이 장을 남기는 문서 커밋 제외), push하지 않았습니다.
> **방법**
> 1. **1차 수정**: Opus 5.5 워커 8개가 영역별로 격리된 git worktree에서 항목별로 커밋했습니다. 메모리(16GB)를 고려해 2회차로 나눴고, 메인 세션이 cherry-pick으로 통합하며 충돌을 풀었습니다(74커밋).
> 2. **1차 검수**: Opus 리뷰어 3개(읽기 전용)와 Codex 독립 리뷰가 수정분을 재판정했습니다. 그 결과 새 결함·잔여 결함 30여 건이 나왔습니다(15.3).
> 3. **2차 수정**: Opus 워커 3개가 15.3 항목을 고쳤습니다(28커밋).
> 4. **2차 검수**: Codex가 2차 수정분을 다시 리뷰했습니다. 사용량 한도로 중간에 끊겼고, 남긴 판단 중 2건을 메인 세션이 고쳤습니다(15.6).
> 5. **메인 세션 몫**: 통합, 교차 정리 커밋 4개, Codex 2차 지적 수정 2개, 전체 빌드, 부팅·종료 실측(15.1), 이 장 작성.
>
> **결과**: `./gradlew build --rerun-tasks` 통과. 56개 작업이 모두 실제 실행됐고, 테스트는 1,323개에서 **1,653개**(domain 382 · infrastructure 684 · application 587)로 늘었으며 실패·건너뜀 0입니다.
> **산출물**: `.omc/artifacts/review-2026-09-28-r3/`의 `fixrev1-outbox-dispatch-deploy.md`, `fixrev2-security-meeting-standup.md`, `fixrev3-cve-templates.md`, `fixrev-codex.md`(1차 검수), 빌드 로그.

### 15.1 실측으로 확인한 것

통합본 부트 jar를 H2 인메모리와 도달 불가능한 Kafka 주소로 띄워 확인했습니다. 외부 호출은 하지 않았고, 복구 스윕이 Slack으로 보내기 전(300초)에 종료했습니다.

| 항목 | 14장 상태 | 수정 후 실측 |
|---|---|---|
| T1 스케줄러 | `SimpleAsyncTaskScheduler` 선택, fixed-delay 직렬 | Boot 기본 빈이 물러나고 `scheduling-1`~`4` 스레드 풀 |
| T27 거부 로그 | `…$$Lambda/0x…@1683cb1` | `Rejected Slack request headers: reason=MISSING_TIMESTAMP path=/api/slack/events` |
| T25 OSIV | 부팅 WARN `open-in-view is enabled by default` | WARN 사라짐. 서명된 `/meetup list`·`/standup list` 200, `LazyInitializationException` 0건(회의 데이터 없는 상태). 정적 전수 확인으로 트랜잭션 밖 LAZY 접근 0건(리뷰어2) |
| A6 우회 입력(회귀 확인) | 21종 차단 | 여전히 전부 401/400 |
| W7 local 바인드 | 모든 인터페이스 | 커넥터 `127.0.0.1:19000` |
| F6 access_blocked | 헬스에 신호 없음 | `/actuator/health`의 outbox에 `accessBlocked`·`lastAccessBlockedAt`·`accessBlockedWindowSeconds` |
| F1/T12 종료 경로 | 정지 중 큐 작업 신규 발송, 대기 직렬 합 > grace | SIGTERM → relay 정지("0 queued claims left IN_PROGRESS") → Kafka 컨슈머 → 웹 graceful → EntityManagerFactory → Hikari 순서. 유휴 상태 1초 |
| prod 프로파일 | — | 샘플 configmap/secret 키로 기동, readiness UP(OSIV 끔·종료 단계 60초·forward headers 끔 반영본) |
| 간헐 실패 보고된 `SlackInteractionHandlerImplTest` | — | 5회 반복 모두 통과(각 17건) |

### 15.2 14장 항목별 반영 현황

"반영" = 수정 + 회귀 테스트, "부분" = 일부만 또는 설계상 잔여, "제외" = 제품 결정·별도 PR. 커밋은 이 브랜치의 SHA이고, `+` 뒤는 2차 검수에서 보강한 커밋입니다.

| 14장 | 상태 | 커밋 | 비고 |
|---|---|---|---|
| T1 스케줄러 단일 스레드 | 반영 | `e07184f` + `d43ea56`, `ab30730` | `ThreadPoolTaskScheduler` 명시, relay AbortPolicy + 원자적 슬롯 예약(F2~F4), Spring 배선 스모크 테스트 |
| T2 cutoff 하나로 스탠드업 전체 정지 | 반영 | `a04efe8` | 1–1440분 검증, 루틴·세션·단계 단위 격리, 결함 고정 테스트 반전 |
| T3 AI 답변 3,000자 | 반영 | `830448f` + `f31eb05`, `1e16395`, `238811f` | section 분할(50블록), 스테이징 전 40,000자 상한(CDC 레코드 기준), 빈 header 결함도 함께 수정 |
| T4 스탠드업 요약 section 하나 | 반영 | `970ac12` + `1a0554c`, `f31eb05`, `ab855ca` | 멤버별 section, 답변 중복 fetch 제거(G1), payload MEDIUMTEXT(H1) |
| T5 setup 회신 채널 `""` | 반영 | `0cf0d71` | 커맨드 채널로, 픽스처를 운영과 같게. 모달 안 오류 표시는 제외(15.4) |
| T6 타임아웃 재시도 중복 | 반영 | `4bc8d3f` + `8eb3844` | `RequestSendTracker`로 전송 후 실패는 `outcome_unknown`(종단), OkHttp `retryOnConnectionFailure` 끔 |
| T7 동시 기동 금지 미강제 | 반영 | `542b31f` + `02ea62e`, `3c909da` | `strategy: Recreate`, 롤아웃 타임아웃 480초 |
| T8 마이그레이션 체크리스트 | 반영 | `c8b31c3` + `f31eb05` | V18 → V19 + V23 → V20 → V22 → 배포 → V21 |
| T9 답변 재제출 유니크 위반 | 반영 | `2fc9d9c` + `181ecc8` | 동시 최초 제출까지 네이티브 upsert |
| T10 역할 캐시 회수 지연 | 반영 | `e9b954d` | USER만 캐시 → 회수는 다음 호출에 즉시 반영 |
| T11 USER 강등이 tx 안에서 무효 | 반영 | `16fca45` | 역할 조회를 트랜잭션 시작 전으로, 실제 `JpaTransactionManager` 회귀 테스트 |
| T12 종료 예산 | 반영 | `4e18067` + `3c909da` | relay `SmartLifecycle`로 정지 시 큐 비우기, grace 150 = 직렬 합, `ShutdownBudgetTest`가 합계 검사 |
| T13 `internal_error` 재시도 | 반영 | `2382132` | 비멱등은 `outcome_unknown` |
| T14 토큰 전역 오류 대량 유실 | 반영 | `bd79872` + `5455699` | 15분 보류(24시간 상한), 헬스·`@bot status` DOWN 신호 |
| T15 digest 절단 후 전달 완료 | 반영 | `7db238c` + `dc1a010` | 여러 outbox 행으로 분할, 엔티티 경계 절단 |
| T16 digest 여러 통 | 반영 | `7db238c` + `9f7e0da` | 사용자 우선 페이징, 단일 사용자 초과분도 한 digest |
| T17 신규 구독 백필 | **제외** | — | 의도 여부가 제품 결정 |
| T18 DM FAILED 종단·넛지 선커밋 | 반영 | `865ba20` | claim을 저장 tx에 합류, cutoff까지 재시도 |
| T19 마감 뒤 답변 | 반영 | `77768ae` + `43f9492`, `b49d16e` | 세션 행 `PESSIMISTIC_WRITE`로 답변·요약 직렬화(두 tx 경합 테스트) |
| T20 스레드 세션 공유 | 반영 | `e9941e9` | 세션 키 `채널:스레드:요청자` |
| T21 mrkdwn 이스케이프 0건 | 반영 | `a3e462f`, `ee8382e`, `fa862c4`, `813c51d` + `9c02c3d`, `0befb64` | 정본 `domain/common/MarkupEscape.kt`, AI 출력은 브로드캐스트만 무력화 |
| T22 거절 사유 255자 | 반영 | `c53cb38`, `712fa27` + `9ea9712` | 입력 `max_length`, 도메인 검증(코드포인트), BEFORE_COMMIT 재시도 제거 |
| T23 MCP XFF 우회 | 반영 | `fbf79e9` + `bdeb68d` | `forward-headers-strategy: none`, 실제 Jetty 커넥터 테스트 |
| T24 멘션 rich_text 누락 | 반영 | `4f179ce` + `79df70b` | 요소 전체 복원, 굵게·인라인 코드 멘션의 역직렬화 실패(`Element.style`)도 수정 |
| T25 OSIV | 반영 | `1cec748` | `open-in-view: false` 명시, OSIV 대조 테스트 |
| T26 CVE 요약 무한 재시도 | 반영 | `0fb5210` + `58e9114` | FAILED + retry+1, 백오프 |
| T27 필터 로거 섀도잉 | 반영 | `6896b0e` | 로그 캡처 테스트 |
| T28 비활성 루틴이 큐 막음 | 반영 | `97f9daf` + `aed80bf` | 건너뜀 종결(이번 릴리스는 `FAILED` + `skipped:` 사유로 기록 — 15.5) |

**Low 항목**: O2 `b5ba092` · O3 `201354e`·`b31fab6`·`7fcb543` · O4 `3951079` · O6 `d68e9c9` · O7 `f1b336e` · O8 `40f7db9` · D4 `27ced49` · D5 `936f365` · D6 `f0c66ef`·`ef152bc` · D7 `e4ecf9a` · A3(프로필 6초) `9be51fa` · A2 계열 CVE 시계 `a44314e` · P3 `64c008a` · P4 `4be810a` · P5 `125f7ea` · W3 `a202d76` · W4 `7cc1852` · W5 `813d15e`(문서만) · W6 `1b4149b` · W7 `e73b203` · R3-S20 `da9aaca` · R3-07 `0220334`(interactive만) · V5 `d5ee5e7` · V6 `c63a5da`·`62b50cb`·`c1f45bc` · V7 `1fb4203`·`2e111ab`(5페이지 상한 경고·메트릭) · V8 `8c4d5de`(경고만) · V10 `36493cd` · U6·U8 `a04efe8`·`e522cc4` · A9 `7634167`·`acc2594` · M6 `1f012ff`·`1721fed` · M7 `16f6f2c` · M8 `16fca45`·`144de7c` · **M5는 수정 불필요로 판정** — Hibernate 7.4.5는 컬렉션 fetch + `Pageable`을 SQL 파생 테이블로 페이징함(실측), 대신 `fail_on_pagination_over_collection_fetch=true` 회귀 테스트 `1f8e3e2` · 14.6 문서 드리프트 `3a60242`·`da7d23a`·`a1a0611`·`5c2b8e0`·`058f94a`·`7c70c5d`.

### 15.3 수정 브랜치 1차 검수에서 나온 결함과 처리

Opus 리뷰어 3개(F·G·H 계열)와 Codex(R·N 계열)가 독립적으로 찾은 것입니다. 같은 결함을 여러 곳이 짚은 경우는 묶었습니다.

| 지적 | 심각도 | 내용 | 처리 |
|---|---|---|---|
| R2 / F1 | High | relay 실행기가 종료 정지 단계 내내 큐의 claim을 새로 발송하고, 대기가 직렬로 더해져 grace 90을 넘음 → SIGKILL 후 스윕 재발송(중복) | `3c909da` (15.1에서 종료 순서 실측) |
| R1 / F5 | High | OkHttp `retryOnConnectionFailure`(기본 true)가 추적기를 우회해 POST를 재전송(Codex·워커 모두 로컬 서버로 재현) | `8eb3844` |
| N1 | High | CVE 부트스트랩이 75자 초과 표시 이름으로 기동 실패(DB 128자, 템플릿은 이미 절단) | `c1f45bc` (+ 옵션 100개 절단, `activate` 상한) |
| G1 | High (main 기존) | 스탠드업 세션 조회가 Set + List(bag)를 한 쿼리로 fetch → 답변이 dispatch 수만큼 중복 → payload 비대화 | `1a0554c` (`answers`를 Set으로, dispatch 3 × 답변 2 회귀 테스트) |
| H1 | Medium | outbox `payload TEXT`(64KB)라 긴 한국어 AI 답변·꽉 찬 요약이 strict 모드에서 롤백 | `f31eb05` (V23 MEDIUMTEXT + 스테이징 상한 + payload 크기 가드), 상한은 Codex 2차 지적으로 `238811f`에서 40,000자로 |
| R5 / G5 | Medium | 답변 접수와 마감 요약이 직렬화되지 않음 | `43f9492` |
| R3 / F6 | Medium | access_blocked 보류가 헬스·`@bot status`에 안 보임 | `5455699` |
| R4 | Medium | 혼자 페이지를 채운 사용자의 digest가 여러 통 | `9f7e0da` |
| R6 / G3 / G8 / H2 | Medium | 넛지·셋업 회신의 루틴 이름, 거절 사유 상세(도메인), ops 에코 미이스케이프 | `9c02c3d`, `0befb64` |
| N2 / G10 | Medium | 리마인더 폐기·재정렬에 관찰 시각 조건이 없어 다른 복제본 결과를 덮음 | `1721fed` |
| G2 | Low~Medium | 새 enum `SKIPPED`를 이전 바이너리가 못 읽어 자동 롤백 시 스탠드업 정지 | `aed80bf` (이번 릴리스는 `FAILED` + 사유, `SKIPPED`는 읽기만) |
| F2 · F3 · F4 | Low | 슬롯 과대 계산, 폴러·스윕 동시 과다 claim, 캐스트 실패 시 fail-open | `d43ea56` (원자적 예약으로 통합) |
| G4 · G7 · G9 | Low | 동시 최초 제출, 세션 없음 공지, outbox 저장 BEFORE_COMMIT 재시도 | `181ecc8`, `b49d16e`, `c8ed6bf` |
| H4~H10 | Low | 빈 코드블록·엔티티 절단, 봇 멘션 과잉 무시, CVE 리셋 백오프, NVD 인터럽트·페이지 상한, ask 접두어, 255자 단위, `max_length` 하한 | `dc1a010`, `acc2594`, `58e9114`, `2e111ab`, `79df70b`, `9ea9712`, `ab855ca` |
| H11 · G11 · G12 | Low(테스트) | 가드 테스트 범위, 아무것도 검증하지 않던 XFF 테스트, bag 중복을 못 잡던 픽스처 | `1e16395`(가드가 빈 header 실결함 발견), `bdeb68d`, `1a0554c` |
| 문서 모순 | — | 리뷰어별 D 목록 | `7fcb543`, `84a3535`, `3acc063` 외 각 커밋 |
| (통합 중 발견) | Low(테스트) | 슬롯 스모크 테스트가 `activeCount`를 기다려 전체 빌드 부하에서 간헐 실패 | `ab30730` (디스패처 진입 수로 대기) |

**판정이 갈린 곳**
- **DLT 전송 실패**(Codex 1차 R3-03, 2차 "S9 REGRESSED"): 14.5의 근거대로 Low로 유지했습니다. outbox 행은 PENDING으로 남아 스윕이 복구합니다. 대신 `f1b336e`로 DLT 발행 실패를 메트릭·ERROR 로그로 드러냈습니다.
- **G2 처리 방식**: 리뷰어는 "문서화 또는 FAILED+사유"를 제시했습니다. Recreate의 자동 `rollout undo` 경로와 겹치므로 문서화만으로는 부족하다고 보고 FAILED+사유로 바꿨습니다.

### 15.4 의도적으로 하지 않은 것

- **제품 결정이 필요한 것**
  - T17(신규 구독 백필, GitHub 첫 수집의 과거 릴리스)
  - U7(루틴 비활성화·수정 기능)
  - N3(주최자 알림 포함)
  - 복제본 간 dedup 공유(결정 #34)
  - V8의 prod 토큰 필수화(경고만 추가)
- **별도 PR**
  - C6(툴체인·컴파일러 옵션 이동)
  - `SlashCommandGate`, detekt/JaCoCo
  - R2→R1 God-class 분해
  - S16(g) non-root(80번 포트와 함께 바꿔야 함)
- **구조상 보류**
  - A11(대체 `text`가 라우팅 토큰): 파서가 메시지 버튼의 라우팅을 `message.text`에서 읽어 하위 호환 설계가 필요합니다.
  - T5의 모달 안 오류 표시: 검증이 ack 이후 이벤트 리스너에서 돕니다.
  - R3-07의 slash/event 선 ACK: Slack 3초 규칙 때문이며 local 전용입니다.
  - W5 게이트웨이 속도 제한: 문서 권고만 했습니다.
  - Codex R5 부수 지적(DM 발송의 마감 재검사): 늦은 DM에 답해도 이제 "closed" 안내를 받습니다.

### 15.5 배포 전 체크리스트 (갱신)

1. **마이그레이션 수동 적용 순서**: V18(중복 참가자 사전 점검 쿼리 → 정리) → V19 + V23 → V20 → V22 → 배포 → 모든 파드가 새 바이너리가 된 뒤 V21.
   - 번호와 적용 순서가 다릅니다. 정본은 `db/migration/AGENTS.md`와 `k8s/README.md`의 One-time 절입니다.
   - V23(`payload` MEDIUMTEXT)은 테이블 재작성이 일어날 수 있으니 한산한 때 적용합니다.
   - readiness는 스키마 누락을 잡지 못하므로 `SHOW COLUMNS`로 미리 확인합니다.
2. **`deployment.yaml`의 `strategy: Recreate`**: 이 블록이 있는 동안 모든 배포(롤백 포함)는 중단을 동반합니다. 중단 시간은 최대 구 파드 종료 150초 + startup 180초입니다. 모든 파드가 V20 이상이 된 뒤 후속 PR에서 블록을 지웁니다.
3. **롤백 호환성**
   - 건너뛴 스탠드업 dispatch는 `FAILED` + `skipped:` 사유로 기록되므로 이전 바이너리로 롤백해도 읽힙니다.
   - `SKIPPED` 저장은 다음 릴리스에서 시작합니다.
   - V23 MEDIUMTEXT는 롤백에 영향이 없습니다.
4. **배포 수치**: `terminationGracePeriodSeconds` 150, `DEPLOYMENT_ROLLOUT_TIMEOUT` 480초, 배포 job 25분.
5. **새 설정 키**: `slack.app.outbox.health.access-blocked-window-seconds`(기본 1200). 보류가 최근 20분 안에 있으면 헬스 DOWN입니다(readiness 그룹에는 포함하지 않음).
6. **13.5에서 이어지는 확인 항목**: 배포 계정 RBAC(`services/proxy` get 또는 `pods/exec` create), configmap/secret 새 키, Kafka `<topic>-dlt` 생성 권한, 노드 메모리 여유.
7. **`main`이 `c2947fa`(#22)로 앞서 있습니다.** 이 브랜치와 `feature/review-critical-fixes`는 그 전 `main` 기준이므로 머지 전에 rebase 또는 merge로 충돌을 해소해야 합니다.

### 15.6 Codex 2차 수정분 리뷰

> Codex가 `7c70c5d..HEAD`를 리뷰하던 중 사용량 한도(18:38 이후 재시도 안내)로 **최종 표 없이 중단**됐습니다. 원문은 `.omc/artifacts/ask/codex-you-are-a-senior-engineer-reviewing-the-second-fix-round-on--2026-10-02T05-45-18-673Z.md`입니다.
> 중단 전에 application 테스트 281개와 domain·infrastructure 선택 테스트를 실행해 통과를 확인했습니다. 종료 처리는 "큐 차단·DB 파기 순서가 개선됐다"고 판정했고, 아래 5건의 판단을 남겼습니다. 한도가 풀린 뒤 전체 판정을 다시 받는 것을 권합니다.

| Codex 판단 | 메인 판정 | 처리 |
|---|---|---|
| AI 답변 상한(139,200자)을 적용해도 CDC 갱신 레코드가 1 MiB를 넘음 — 역슬래시 입력 1,114,270바이트, 제어문자 입력 1,949,392바이트(재현). 가드 테스트는 실제 이중 직렬화 대신 `payload × 2`로 계산 | **채택(Medium)**. 레코드가 `max.request.size`를 넘으면 Debezium 커넥터가 실패해 모든 발송이 멈춤 | `238811f`: 상한 40,000자(Slack 메시지 `text` 한도와 같음, 최악 입력 약 560 KB), 가드가 payload를 JSON 문자열로 다시 인코딩해 계산하고 제어문자·역슬래시 입력도 단언. 옛 상한에서 이 두 단언이 실패하는 것을 확인 |
| OkHttp가 `503 + Retry-After: 0`을 내부에서 재전송 | Codex 스스로 결함에서 제외(디스패처도 503은 Slack이 처리하지 않은 응답으로 보고 재시도) — **동의** | — |
| CVE 표시 이름 상한을 UTF-16 길이로 셈 → 이모지 76자 이름도 거부 | **채택(Low)**. utf8mb4 `VARCHAR(128)`은 문자 단위 | `e0bace7`: 코드포인트로 판정, 이모지 128자 통과·129자 실패 테스트 |
| ask 접두어 제거가 앞선 링크 라벨의 단어를 지울 수 있음 | 수용(Low). 워커가 잔여 위험으로 문서화 | — |
| `ShutdownBudgetTest`가 웹 graceful·스케줄러 정지 단계를 합산하지 않음 | 수용(Low). 그 단계들은 정지 전에 시작된 발송이 레코드 예산을 다 쓴 뒤에 오므로 보통 수 초임(워커 문서화). MCP SSE 장기 연결이 웹 단계를 길게 쥐는지는 미검증 | 15.7 |

### 15.7 남은 위험과 미검증

- **실행 환경이 없어 확인하지 못한 것**
  - 실제 MariaDB에서의 동작: V18~V23 적용, `PESSIMISTIC_WRITE`의 InnoDB 동작, `INSERT … ON DUPLICATE KEY UPDATE`, strict `sql_mode`.
  - Kafka 브로커 왕복(DLT, MEDIUMTEXT 크기 행의 CDC 레코드).
  - 클러스터 배포·롤백·RBAC.
- **Slack 측 동작**
  - 48 section × 2,900자 메시지가 `msg_blocks_too_long`에 걸리는지.
  - OkHttp 재전송을 끈 뒤 `outcome_unknown`(유실)이 실제로 얼마나 자주 생기는지.
  - 봇 게시물 안의 `<!channel>`이 실제로 알림을 울리는지.
- **설계상 잔여**
  - access_blocked 신호는 복제본별 메모리라 재시작하면 사라집니다.
  - 종료가 시작된 뒤 CDC가 claim한 레코드는 약 300초 뒤 스윕이 보냅니다.
  - Codex 2차 리뷰는 중간에 끊겼습니다. 한도가 풀린 뒤 `7c70c5d..HEAD` 전체 판정을 다시 받는 것을 권합니다.

---

## 부록: 재현용 확인 명령

```bash
# 스케줄러 claim 패턴 대조 (3.1 표)
grep -rn "@Scheduled" --include="*.kt" application
grep -rn "claim_token\|claimToken" --include="*.kt" infrastructure/src/main application/src/main

# @Modifying 의 clearAutomatically 누락
grep -rn -A1 "@Modifying" --include="*.kt" infrastructure | grep -c "clearAutomatically"   # → 0

# 낙관적 락 적용 범위
grep -rn "field:Version" --include="*.kt" infrastructure/src/main                          # → 1건

# 도메인 레이어 순수성
grep -rn "^import org.springframework\|^import jakarta\." --include="*.kt" domain/src/main  # → 0건

# Flyway 부재 확인
grep -rn "flyway" --include="*.kts" --include="*.yaml" . | grep -v build/ | grep -v docs/   # → 0건

# 프로덕션 미구현 코드
grep -rn 'TODO("' --include="*.kt" */src/main

# 비활성화된 테스트
grep -rn "@Disabled\|!given\|!when\|!should" --include="*.kt" */src/test                    # → 0건

# CI 게이트
head -8 .github/workflows/lint.yaml .github/workflows/simple_test_action.yaml
grep -n "x test\|required_contexts" .github/workflows/deploy_action.yaml

# Outbox 인덱스 마이그레이션 부재 (H4 확정 근거)
grep -rn -i "index" application/src/main/resources/db/migration/*.sql | grep -i outbox

# C6 실측: 루트 컴파일러 옵션/툴체인이 모듈에 적용되는지
cat > /tmp/probe.gradle <<'PROBE'
allprojects {
    afterEvaluate { p ->
        p.tasks.matching { it.name == 'compileKotlin' }.each { t ->
            def co = t.compilerOptions
            println "PROBE|${p.path}|freeArgs=${co.freeCompilerArgs.getOrNull()}|jvmTarget=${co.jvmTarget.getOrNull()}"
        }
        def je = p.extensions.findByName('java')
        println "TOOLCHAIN|${p.path}|lang=${je?.toolchain?.languageVersion?.getOrNull()}|vendor=${je?.toolchain?.vendor?.getOrNull()}"
    }
}
PROBE
./gradlew -I /tmp/probe.gradle help -q | grep -E "^PROBE\||^TOOLCHAIN\|"
# 기대: 모듈의 freeArgs=[] 그리고 lang=null → 루트 설정이 적용되지 않음
# 2026-09-22 재측정: 모듈 jvmTarget=JVM_21 (데몬 JDK 21 폴백). C6 본문의 JVM_25 인용은 데몬이 25였던 머신의 값 — 12.1 참조
```

---

## 부록 B: 레인별 기여 요약

| 발견 | 레인 |
|---|---|
| C1 CI 게이트 | 메인 + 빌드 + Codex (3중 독립 일치) |
| C2 `RetryService` 레이스 | 인프라 + Codex |
| C3 `KafkaErrorBroadcaster` | 메인 + 인프라 + Codex |
| C4 `"kafka:port"` | 메인 |
| C5 JOIN FETCH 절단 | 인프라 + Codex |
| C6 툴체인 미적용 | 빌드 (메인이 실측 재확인) |
| C7 k8s probe·resources 부재 | 빌드 |
| C8 `ValidationBuilder.or` | 도메인 |
| H1 Outbox claim | 메인 + Codex |
| H2 Slack 429 | 인프라 |
| H3 Kafka 오토커밋 | 메인 (Codex 반론 반영) |
| H4 Outbox 인덱스 | 메인 + 인프라 (Codex 유보 → 메인이 마이그레이션 확인해 해소) |
| H5 빈 예외 핸들러 | 메인 + Codex |
| H6 `"null"` 문자열 | 메인 |
| H7 `notBlank` 노출 | 도메인 |
| H8 마이그레이션 리스크 | 메인 + 인프라 |
| H9~H10 | 도메인 |
| H11 슬래시 권한 | 메인 |
| H12~H16 | 인프라 |
| H17 `run` 스크립트 파손 | 빌드 (재현 완료) |
| H18 `api` 누출 | 빌드 (실측) |
| H19 빈 테스트 스펙 | 빌드 |
| H20~H21 워크플로 권한·배포 검증 | 빌드 |
| M25~M26 문서 드리프트·액션 핀 | 빌드 |
| X1~X4 | Codex |
