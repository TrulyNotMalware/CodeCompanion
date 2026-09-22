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
