# CodeCompanion 리뷰: `kotlin-spring-boot` 스킬 기준

> **작성일**: 2026-10-01
> **대상**: `feature/review-critical-fixes` @ `cca9984` (클린 트리) · Kotlin 2.4.10 · Spring Boot 4.1.1 · Java 25
> **기준**: `~/.claude/skills/kotlin-spring-boot/`(`SKILL.md` + reference 13개). 스킬이 리뷰 도중 갱신돼 `scheduling-executors.md`가 추가됐다. 인용은 줄 번호가 아니라 **파일과 절 이름**으로 한다.
> **범위**: 스킬의 규칙과 Gotcha를 이 프로젝트 코드에 대 보고 어긋난 곳을 찾았다. `review.md`(1~13장)에서 이미 고쳐진 항목은 다시 적지 않았다.

---

## 0. 방법과 근거 표기

- 영역별로 리뷰 레인 5개를 병렬로 돌렸다(빌드·DI / JPA·트랜잭션 / 메시징·설정·관측 / 웹·보안·HTTP 클라이언트 / 테스트·동시성). 각 레인은 해당 reference를 체크리스트로 삼아 현재 HEAD 코드를 읽었다. 코드 수정과 프로젝트 Gradle 실행은 하지 않았다.
- 메인 세션은 상위 지적을 소스, 바이트코드, scratch 실행으로 다시 확인했다(부록 A). 레인끼리 결론이 엇갈린 시계 문제(L-1)는 Hibernate 소스로 판정했다.

| 표기 | 의미 |
|---|---|
| **[실행]** | scratch 앱으로 같은 설정을 재현해 확인 |
| **[바이트코드]** | 프로젝트 빌드 산출물을 `javap`로 확인 |
| **[소스]** | Boot·Spring·Hibernate jar 소스로 확인 |
| **[읽기]** | 프로젝트 코드를 읽어 확인. 런타임 영향은 추론 |
| **[실행 필요]** | 결론이 운영 환경 값(DB 존, 실제 페이로드 등)에 달려 있음 |

`review.md` 상태: **신규**는 review.md에 없던 것, **잔존(ID)**은 review.md에 있었고 지금도 남아 있는 것이다.

---

## 요약

| 심각도 | 건수 | 대표 항목 |
|---|---:|---|
| 높음 | 6 | Hikari 설정 전체 미적용, fixedDelay 잡 직렬화, CVE 외부 호출 본문 무제한 대기, standup 답변 중복, Slack POST 중복 발송, `:application` 컨텍스트 테스트 부재 |
| 중간 | 13 | 보안 필터 로그가 람다 객체로 찍힘, 시크릿 누락 상태로 Ready, BEFORE_COMMIT 안의 재시도, open-in-view, 트랜잭션 안의 `views.open`, AI 턴이 커밋 전에 시작, MCP 루프백 판정 우회 등 |
| 낮음 | 30여 | 영역별 표(3장) |

**가장 먼저 볼 것**: H-1(Hikari)과 H-2(스케줄러)다. 둘 다 "YAML에 적어 둔 설정이 실제로는 적용되지 않는" 유형이다. 프로젝트 문서의 용량 계산(`resources/AGENTS.md`의 Hikari 공식, CDC 레코드당 60초 예산)이 이 설정을 전제로 하고 있다. 스킬이 가장 강조하는 "Spring을 거쳐 검증하라"(SKILL.md Workflow 3, `testing.md` § Wiring smoke test)를 지키지 않은 결과가 H-6이고, H-6이 H-1과 H-2를 지금까지 가려 왔다.

---

## 1. 높음

### H-1. `spring.datasource.hikari.*` 설정이 하나도 적용되지 않는다 — 풀 10개, 대기 30초로 동작 [실행]

- **스킬**: `kotlin-spring-basics.md` § Configuration properties("Check the effective value … before editing"). § Explicit and conditional registration(직접 정의한 빈은 Boot 자동 구성을 물러나게 하고, 그 빈에 걸려 있던 설정 속성도 함께 사라진다). SKILL.md Workflow 3.
- **위치**: `infrastructure/src/main/kotlin/dev/notypie/configurations/JpaConfiguration.kt:45-50`
  ```kotlin
  fun hikariDataSource(dataSourceProperties: DataSourceProperties): HikariDataSource =
      dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()
  ```
  `@ConfigurationProperties("spring.datasource.hikari")`가 없다. `initializeDataSourceBuilder()`는 driver, url, user, password만 복사하고, `spring.datasource.hikari` 바인딩은 Boot 자체 빈에만 걸려 있다. 그런데 Boot 빈은 프로젝트가 `DataSource`를 정의했기 때문에 만들어지지 않는다 [소스].
- **재현** [실행]: 같은 방식으로 빈을 만들고 `maximum-pool-size=20`, `connection-timeout=5000`, `pool-name=hikari_pool`, `transaction-isolation=…`을 준 결과는 `maxPool=10 connTimeout=30000 poolName=null isolation=null`이었다. Boot 자동 구성이었다면 네 값이 모두 적용된다.
- **영향**:
  - `application-*.yaml`의 hikari 블록 전체(4개 프로파일)가 효과가 없다.
  - `resources/AGENTS.md`의 풀 크기 공식은 연결 20개를 전제로 한다. 회의 쓰기 2개 + relay 5 + 스케줄러 + CDC 1 + async 10을 합치면 10을 넘는다. 경합이 생기면 회의 상호작용 두 건이 서로 두 번째 연결을 기다리다 30초 뒤 함께 실패한다.
  - 연결 대기가 5초가 아니라 30초라서 Slack의 3초 ack를 놓쳐 Events API 재시도를 부른다. CDC 레코드 하나가 재시도를 포함해 수십 초를 써서 `max.poll.interval.ms` 예산이 깨진다.
  - prod의 `transaction-isolation: ${SQL_PROD_ISOLATION_LEVEL}`도 적용되지 않는다. 그 값이 MariaDB 기본값(REPEATABLE_READ)과 같다면 실질 영향은 없다.
  - 대시보드나 로그에서 풀 이름은 `hikari_pool`이 아니라 `HikariPool-1`이다.
- **수정**: 빈 메서드에 `@ConfigurationProperties("spring.datasource.hikari")`를 붙인다. H-6의 컨텍스트 테스트에서 `maximumPoolSize == 20`을 단언한다.
- **review.md**: 신규. `review.md:1036`은 YAML 값을 적용된 값처럼 인용했다.

### H-2. fixedDelay `@Scheduled` 잡 9개가 스레드 하나에서 직렬로 돈다 [실행]

- **스킬**: `scheduling-executors.md` § Which scheduler runs `@Scheduled`, `coroutines.md`(가상 스레드 행: "`spring.task.scheduling.pool.size` stops applying and all `fixedDelay` jobs share one thread").
- **위치**: 모든 프로파일이 `spring.threads.virtual.enabled: true`다(`application-prod.yaml:9-11` 등). 그래서 Boot가 `SimpleAsyncTaskScheduler`를 만든다. `application.yaml:10-15`의 `pool.size: 4`와 그 주석("one slow job … would starve all the others")은 효과가 없다. `@Scheduled` 10개 중 9개가 fixedDelay이고 `PollingMessageProcessor`만 fixedRate다.
- **재현** [실행]: 3초짜리 fixedDelay 작업 옆에서 500ms fixedDelay 작업이 6.5초 동안 몇 번 실행되는지 셌다.

  | 설정 | 실행 횟수 |
  |---|---:|
  | 이 프로젝트 설정(가상 스레드 + `pool.size=4`) | **2** |
  | 플랫폼 스레드 + 풀 4 | 13 |
  | 가상 스레드 + 직접 정의한 `ThreadPoolTaskScheduler` | 13 |
  | fixedRate 작업(비교용) | 14 |

- **영향**: `CveCollector`(NVD/GitHub HTTP)와 `CveSummaryWorker`(사이드카 호출, 최대 120초)가 느려지면 standup 발송·리마인더·daily agenda·outbox 복구 스윕·보존 정리가 모두 그만큼 밀린다. H-3과 겹치면 영구히 멈춘다. `resources/AGENTS.md` Hikari 공식의 "scheduler threads (pool.size, 4)" 항목도 사실과 다르다.
- **수정**: `ThreadPoolTaskScheduler` 빈을 직접 정의한다(풀 크기 명시, `setVirtualThreads(true)` 가능). fixedRate로 바꿀 수도 있지만, 그러면 같은 잡이 겹쳐 실행될 수 있으니 잡마다 판단해야 한다.
- **review.md**: 신규.

### H-3. CVE 소스 어댑터가 응답 본문을 시간·크기 제한 없이 읽는다 — H-2와 겹치면 모든 fixedDelay 잡이 멈춘다 [읽기]

- **스킬**: `http-clients.md` § Consuming streams(헤더 타임아웃과 본문 데드라인은 별개다), § Always start from the Boot-configured builder("a stalled server blocks the calling thread forever").
- **위치**: `NvdCveSourceAdapter.kt:52,59`, `GithubReleaseSourceAdapter.kt:36,43`가 `HttpRequest.timeout()` + `BodyHandlers.ofString()`을 쓴다. 프로젝트도 `SidecarAgentClient.kt:82-83`에서 "`timeout()`은 응답 헤더까지만 덮는다"고 적어 두었다. 사이드카에만 watchdog을 달았다.
- **실패 시나리오**: NVD가 헤더를 보낸 뒤 본문 전송 중에 멈추면 `CveCollector.tick()`이 반환하지 않는다. H-2 때문에 같은 스케줄러 스레드를 쓰는 fixedDelay 잡 전체(리마인더, standup, outbox 복구 포함)가 재시작 전까지 멈추고, 아무 로그도 남지 않는다.
- **수정**: 사이드카처럼 `ofInputStream` + 전체 데드라인 watchdog + 바이트 상한을 둔다. H-2의 스케줄러 분리도 함께 한다.
- **review.md**: 신규(H14는 SSE만 다뤘다).

### H-4. standup 세션 조회가 `answers`를 dispatch 수만큼 복제하고, 그 목록이 요약 outbox 페이로드에 들어간다 [실행·레인]

- **스킬**: `jpa-entities.md` § N+1 queries("Fetching two collections at once produces a cartesian product … Fetch one collection per query").
- **위치**: `StandupSessionSchema.kt:57,64`는 `dispatches: MutableSet`, `answers: MutableList`(bag)다. `JpaStandupSessionRepository.kt:44-46`은 `SELECT DISTINCT s … LEFT JOIN FETCH s.dispatches LEFT JOIN FETCH s.answers`를 실행한다. `StandupSummaryService.kt:54`의 `answers = session.answers`가 그 목록을 페이로드에 넣는다.
- **재현** (레인, Hibernate 7.4.5 + H2 단독 프로그램): dispatch 3개 × answer 2개로 로드한 `answers`는 6개였다. bag은 결과 행마다 원소를 하나씩 추가하고 중복을 제거하지 않는다.
- **영향**: 지금은 템플릿이 `associateBy`로 중복을 감춘다. 하지만 페이로드 크기는 멤버 수의 제곱으로 커진다. 10명 × 답변 1KB면 약 100KB로, MariaDB `TEXT`(65,535바이트, `V11:28`)를 넘는다. 이 경우 INSERT가 실패해 세션이 `COLLECTING`에 머물고, 매분 재시도하지만 요약은 끝내 게시되지 않는다 [실행 필요: MariaDB에서 확인].
- **수정**: `answers`를 `Set`으로 바꾸거나 두 번째 쿼리로 따로 로드한다. 페이로드 크기 가드를 추가한다.
- **review.md**: 잔존(M13). M13은 N×M 행만 지적했고 중복과 페이로드 초과는 다루지 않았다.

### H-5. 타임아웃이 난 Slack POST를 다시 보내 같은 메시지가 여러 번 게시될 수 있다 [읽기]

- **스킬**: `http-clients.md` § Retries, rate limits, and non-idempotent calls("A timed-out POST may have succeeded; retrying it blindly duplicates the effect", "Know who else retries").
- **위치**: `ApplicationMessageDispatcher.kt:53-54`의 `TRANSIENT_EXCEPTIONS`에 `IOException`이 들어 있다. `:121-124`가 `chat.postMessage`/`postEphemeral`/`response_url` POST를 `retryService.execute`로 감싼다(최대 3회). 호출 타임아웃은 6초(`:55`)다. 재시도를 소진하면 outbox 복구 스윕이 다시 보낸다(`maxSends = 10`, `AppConfig.kt:106`).
- **실패 시나리오**: Slack이 느리지만 게시는 받아들이고 6초 넘게 걸려 응답하면, OkHttp `InterruptedIOException` → 인라인 재전송 2회 → 스윕 재발송으로 이어진다. 같은 채널 메시지나 DM이 최대 수십 번 게시될 수 있고, Slack 쪽에는 이를 막을 멱등 키가 없다.
- **수정**: 인라인 재시도는 요청이 Slack에 닿지 않은 실패(`ConnectException`, `UnknownHostException`)만 대상으로 한다. 전송 후 타임아웃은 "결과 불명"으로 기록하고, 맹목적으로 재발송하지 않는다.
- **review.md**: 신규. review.md의 H2 수정안(`:519`)은 오히려 `IOException` 재시도를 권했다.

### H-6. `:application`의 Spring 배선을 검증하는 테스트가 없다 [읽기]

- **스킬**: SKILL.md Gotchas("Agent writes unit tests only for a wiring change"), `testing.md` § Wiring smoke test, § Choose the smallest test that would have failed.
- **위치**: `:application`에는 `@SpringBootTest`도 `src/test/resources`도 없다. 단위 테스트 위에서 한 번도 검증되지 않는 것들은 다음과 같다.
  - H-1(풀 설정)과 H-2(스케줄러 종류)
  - `@Qualifier("relayTaskExecutor")` 주입(`SlackMessageRelayServiceImpl.kt:41`)
  - `Clock` 빈 선택
  - outbox 쓰기 `@TransactionalEventListener(BEFORE_COMMIT)`(`SlackMessageRelayServiceImpl.kt:187`). 스펙 7개가 `CommitRecordingEventPublisher` 가짜로 대체한다.
  - `AgentConverseService`의 클래스 수준 `@Async` 프록시
  - `@KafkaListener` + 에러 핸들러 + DLT 배선(recoverer는 목 템플릿으로만 테스트)
- **실패 시나리오**: 새 코드 경로가 트랜잭션 없이 `OutboundMessageEnqueued`를 발행하면, `fallbackExecution = false`라서 리스너가 건너뛰어지고 메시지가 outbox에 기록되지 않는다. 그래도 모든 스펙은 녹색이다.
- **수정**: `@SpringBootTest(webEnvironment = NONE)` + H2 + `@EmbeddedKafka`(리스너 자동 시작 끔) 스모크 테스트 하나를 둔다. 단언할 것은 풀 크기, 스케줄러 타입, executor 식별, `Clock` 빈, AOP 프록시 여부, 실제 BEFORE_COMMIT 왕복으로 outbox 행이 생기는지다.
- **review.md**: 잔존(12.3·13.3 Tier C·13.4·13.5 미착수 항목).

---

## 2. 중간

| # | 항목 | 스킬 근거 | 위치 | 실패 시나리오 → 수정 | 근거 · review.md |
|---|---|---|---|---|---|
| M-1 | **Slack 보안 필터의 로그 6줄이 람다 객체로 찍힌다** | `kotlin-spring-basics.md` § Logging(상속된 `protected val logger`가 파일 수준 `logger`를 가린다) | `SlackRequestVerificationFilter.kt:19`(`private val logger`) + `:29`(`OncePerRequestFilter` 상속). 호출 `:51,:65,:73,:87,:106,:111` | 바이트코드상 6개 모두 `GenericFilterBean.logger`(commons-logging)의 `Log.warn(Object)`/`info(Object)`에 `Function0`을 넘긴다. 서명 거부, 본문 초과, 검증 비활성, 재시도 보류 로그가 `…$$Lambda@hash`로 남아 유일한 인바운드 보안 감사 로그를 읽을 수 없다. → 이름을 `log`로 바꾼다. 원인인 `docs/wiki/coding-style.md:34`("`private val logger`")도 고친다(코드 40개 파일 중 30개는 이미 `log`). 이 패턴에 해당하는 파일은 이것 하나다. | [바이트코드] · 신규 |
| M-2 | **시크릿이 없어도 앱이 Ready가 된다** | § Configuration properties(미해결 플레이스홀더는 글자 그대로 바인딩된다, blank is not missing) | `application-prod.yaml:123`(`token: ${SLACK_API_TOKEN}`), dev `:97`, slack-live `:64`. `AppConfig.kt:40` 기본값 `""`. prod `:141`(`${SIDECAR_BEARER_SECRET:}`) | k8s는 `envFrom: secretRef`로 주입하므로 키가 빠져도 파드 오류가 나지 않는다. 토큰은 문자열 `${SLACK_API_TOKEN}` 그대로 바인딩되고, readiness를 통과한 뒤 모든 Slack 호출이 `invalid_auth`로 실패한다. 사이드카는 빈 `Bearer `를 보낸다. → 서명 시크릿에만 있는 검사(`Filter:159-166`)를 `AppConfig.Api`/`Sidecar`의 `init` 바인딩 검사로 옮겨 일반화한다. | [읽기] · 신규(토큰), 잔존(사이드카, `review.md:831`) |
| M-3 | **Kafka producer 설정이 lite 모드에서 직접 호출되고 속성 3개만 쓴다** | § Dependency injection(lite / `proxyBeanMethods=false`에서 `@Bean` 메서드를 직접 부르면 새 인스턴스가 생긴다), `config-observability.md` § Configuration sources | `KafkaConsumerConfiguration.kt:148-169`: `@Configuration` 없이 `@Import`된 클래스가 `KafkaTemplate(producerFactory())`를 부르고, 팩토리는 bootstrap과 serializer 2개로만 만든다 | Spring이 관리하지 않는 두 번째 팩토리가 생겨 종료 시 닫히지 않는다. `spring.kafka.producer.*`·`properties.*`(SASL/TLS, acks)가 producer에 닿지 않는다. 인증이 추가되면 컨슈머는 붙고 DLT 발송만 실패하는데, `setFailIfSendResultIsError(false)` 때문에 poison 레코드가 로그 한 줄만 남기고 사라진다. → `kafkaTemplate(producerFactory: ProducerFactory<…>)` 파라미터 주입 + `kafkaProperties.buildProducerProperties()` | [읽기] · 잔존(S23, 컨슈머 쪽만 수정됨) |
| M-4 | **BEFORE_COMMIT 리스너 안에서 호출자 트랜잭션을 공유한 채 재시도한다** | `transactions.md` § Optimistic locking and retry(재시도 메서드의 호출자가 트랜잭션을 들고 있으면 안 된다), § Boundaries(오류는 flush/commit에서 드러난다) | `MeetingServiceImpl.kt:71,87`(`retryService.execute { createNewMeeting / updateParticipantAttendance }`), `SlackMessageRelayServiceImpl.kt:189-196`(`retryService.execute { outboxRepository.save(row) }`). `RetryService`는 모든 `Exception`을 재시도한다 | 첫 실패가 공유 트랜잭션을 rollback-only로 만들고, 2회차의 "성공"은 커밋에서 `UnexpectedRollbackException`이 된다. outbox INSERT는 커밋 flush 때에야 실행되므로 `save()` 재시도는 INSERT 실패를 볼 수조차 없다. 연결을 쥔 채 백오프만 한다. → 리스너 안의 재시도를 제거하고, 재시도는 트랜잭션 밖에서 명령 전체 단위로 한다(이미 있는 `executeRetryingOnConflict`처럼) | [읽기] · 신규 |
| M-5 | **open-in-view가 켜져 있고, 읽기 경로가 트랜잭션 밖에서 매핑한다** | `jpa-entities.md` § Relationships(`spring.jpa.open-in-view=false`), § Separate domain and persistence models(매핑은 트랜잭션 안에서) | 5개 프로파일 어디에도 `spring.jpa.open-in-view`가 없어 기본값 `true`다(Boot 4.1.1 메타데이터 [소스]). `*RepositoryImpl`의 읽기 메서드에는 `@Transactional`이 없다 | HTTP 요청에서는 fetch join이 빠져도 lazy load가 조용히 성공하지만, 같은 코드가 스케줄러에서는 `LazyInitializationException`을 던진다. MVC 요청은 첫 연결을 응답이 끝날 때까지 쥐고 있다(H-1의 10개 풀과 겹친다). → `application.yaml`에 `open-in-view: false`, 읽기는 `@Transactional(readOnly = true)` 안에서 매핑 | [읽기] · 신규(OSIV), 잔존(M10) |
| M-6 | **Slack `views.open`이 DB 트랜잭션 안에서 실행된다** | `transactions.md` § Boundaries("Do not hold a transaction open across slow remote calls") | `ApplicationMessageDispatcher.kt:245-249`. 호출 경로는 `StandupSlashServiceImpl.kt:16`, `CveSubscriptionSlashServiceImpl.kt:38,68,98`(`@Transactional`), `SlackInteractionHandlerImpl.kt:61-76` | 모달 하나가 최대 6초 동안 연결을 쥔다. Slack이 느리면 동시 모달 몇 개로 풀(실제 10개, H-1)이 바닥난다. → 커밋 뒤 요청 스레드에서 연다(`MeetingWriteDeferral`과 같은 방식). M-5를 먼저 고쳐야 효과가 있다 | [읽기] · 잔존(M11) |
| M-7 | **리마인더 조회가 페이징하면서 컬렉션을 fetch하고, INNER JOIN이라 참가자 없는 회의를 놓친다** | `jpa-entities.md` § N+1 queries(페이징과 컬렉션 fetch를 함께 쓰지 않는다, INNER JOIN FETCH는 자식 없는 부모를 뺀다) | `JpaMeetingReminderRepository.kt:20-22,29-31`: `JOIN FETCH r.meeting m JOIN FETCH m.participants` + `pageable` | Hibernate가 페이징을 메모리에서 처리해서(HHH90003004) 장애 뒤 한 틱이 밀린 리마인더 전체를 로드한다. 참가자 0명 회의의 리마인더는 선택되지 않아 영원히 PENDING이다. → id를 먼저 페이징하고 `LEFT JOIN FETCH`로 다시 조회 | [읽기] · 신규(S10 수정이 이 쿼리에는 적용되지 않음) |
| M-8 | **AI 턴이 멘션 트랜잭션 커밋 전에 비동기로 시작된다** | `transactions.md` § After-commit side effects | `AgentConverseService.kt:38`(클래스 수준 `@Async`) + `:86-87`(일반 `@EventListener`). 이벤트는 `@Transactional` `SlackMentionEventHandlerImpl.handleEvent`(`:33,:63`) 안에서 동기로 발행된다 | 멘션 트랜잭션이 발행 뒤 실패해도 턴은 이미 큐에 들어가 응답한다. 500을 받은 Slack이 재시도하면 두 번째 턴이 생겨 AI 응답이 중복되고 사이드카 비용도 두 배가 된다. → `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` | [읽기] · 신규 |
| M-9 | **AI 턴 executor가 큐 10,000·종료 대기 10초로, 이미 200을 준 턴을 배포 때 버린다** | `scheduling-executors.md` § `@Async` and executor beans(제한된 큐, 종료 시 유실) | `AsyncConfig.kt:17-27`(`queueCapacity = 10000`, `setAwaitTerminationSeconds(10)`). `@Async`가 가상 스레드가 아닌 이 플랫폼 풀에서 돈다(Boot executor가 물러남 [소스]) | 턴이 최대 120초라서 11번째 멘션부터는 아무 피드백 없이 몇 분씩 기다린다. 롤링 배포마다 큐에 남은 턴(Slack에는 이미 200을 줘서 재시도도 없다)이 조용히 사라진다. → 제한된 큐 + 거부 시 "busy" 회신, 또는 턴 행을 영속화 | [읽기] · 잔존(M21 후반부) |
| M-10 | **MCP "루프백 전용" 판정이 `remoteAddr` 하나에 기대는데, Kubernetes에서는 클라이언트가 그 값을 바꿀 수 있다** | 스킬에 직접 대응하는 규칙은 없다. 프로젝트 decision #14(루프백 바인드)를 기준으로 판정 | `McpTurnTokenFilter.kt:22,32-33`. `server.forward-headers-strategy` 설정 없음(grep 0건) | Boot 4.1.1은 Kubernetes를 감지하면 forwarded 헤더를 신뢰한다(`CloudPlatform.isUsingForwardHeaders()` 기본 `true` [소스]). 그러면 Jetty `ForwardedRequestCustomizer`가 `X-Forwarded-For`의 맨 왼쪽 값을 `remoteAddr`로 쓴다. 클러스터 안의 다른 파드가 `X-Forwarded-For: 127.0.0.1`을 붙이면 루프백 판정을 통과하고 HMAC 토큰만 남는다. → `server.forward-headers-strategy: none` 명시, 또는 MCP 전용 127.0.0.1 커넥터 + `localPort` 확인 | [소스] 연결 고리 확인, 실제 우회는 [실행 필요] · 신규 |
| M-11 | **outbox 적체, DLT, Debezium 커넥터 상태를 알려 줄 신호가 없다** | `messaging-outbox.md` § CDC with Debezium("Monitor connector state and lag, alert on outbox age") | 신호는 `OutboxHealthIndicator`뿐이다. 이마저 `show-details: when_authorized`인데 Spring Security가 없어 세부가 아무에게도 보이지 않는다. outbox·DLT 미터 0개, 메트릭 exporter 의존성 0개, 커넥터 `heartbeat.interval.ms` 없음, binlog 보존 7일(`mariadb-config.yaml:19`) | 커넥터가 멈추면 전달은 5분 주기의 복구 스윕으로 조용히 넘어가고 아무도 모른다. 7일이 지나면 binlog 위치가 사라져 재스냅샷이 필요하다. → 최고령 PENDING age·상태별 count 게이지, DLT 발행 카운터, exporter와 알림, 커넥터 상태 점검, 재스냅샷 런북 | [읽기] · 신규 |
| M-12 | **NVD 호출에 페이싱이 없고 403/429를 빈 결과로 삼킨다** | `http-clients.md` § Retries, rate limits(`Retry-After`, 부하 상한) | `CveCollector.kt:26-29`(토픽 연속 호출), `:41`(fetch 전에 윈도를 claim), `NvdCveSourceAdapter.kt:64-66`(non-2xx → `emptyList()`) | API 키가 없으면 30초에 5건만 허용된다. NVD 토픽이 6개 이상이면 6번째부터 매 틱 거부되고, 윈도는 이미 소진된 상태라 영구 미수집이 된다. 남는 건 WARN 한 줄뿐이다. → 어댑터 안에서 페이싱하고, 403/429/503이면 ledger claim을 되돌린다 | [읽기] · 잔존(M23) |
| M-13 | **"롤백된다"는 이름의 스펙 3개가 목 트랜잭션 매니저로 호출 순서만 본다** | `testing.md` § Transactions, time, and concurrency in tests("A mocked `PlatformTransactionManager` proves nothing about atomicity") | `MeetingReminderSchedulingServiceTest.kt:255`, `StandupSummaryServiceTest.kt:204`, `CveNotificationDispatcherTest.kt:190`. PTM을 목으로 쓰는 스펙 파일 6개 | `markReminderSent`나 CVE `claim`이 `REQUIRES_NEW`로 바뀌거나 outbox 저장이 `runInTx` 밖으로 나가도 세 스펙은 녹색이다. → 이미 있는 실 트랜잭션 패턴(`DailyAgendaSchedulingServiceTest.kt:268-299`: H2 트랜잭션 매니저 + 행 수 단언)으로 바꾼다 | [읽기] · 신규(S22와 같은 부류) |

---

## 3. 낮음

### 3.1 빌드·설정·DI

| 항목 | 위치 | 요지 | review.md |
|---|---|---|---|
| `AppConfig`의 data class `toString()`이 시크릿 7개를 출력 | `AppConfig.kt:16, 40-42, 128, 153, 158, 189` | 지금 로그로 찍는 곳은 없다. 첫 `log.info { "$appConfig" }`나 MockK 불일치 메시지에서 토큰과 서명 시크릿이 노출된다. `Api`·`Mcp`·`Github`·`Nvd`·`Sidecar`에 마스킹 `toString` | 신규 |
| 빈 생성자 기본값과, `clock`을 빼먹은 `@Bean` 팩토리 | `clock: Clock = Clock.system*()` 13곳, `appConfig: AppConfig = AppConfig()` 5곳(`OutboxHealthIndicator.kt:17` 등). `clock`을 생략한 팩토리 5곳(`SlackRequestVerificationConfiguration.kt:24`, `McpServerConfiguration.kt:33,57`, `AgentConfiguration.kt:45`, `CveConfiguration.kt:99`) | 지금은 존이 같아 무해하다. 하지만 컨텍스트 테스트에서 `Clock` 빈을 고정해도 팩토리로 만든 빈에는 닿지 않는다. `AppConfig` 등록이 빠지면 5개 빈은 기본 설정으로 조용히 뜬다. 기본값을 지우고 팩토리에서 넘긴다 | 신규 |
| `CdcDeadLetterRecovery`의 nullable 템플릿 | `KafkaConsumerConfiguration.kt:62,67-72,126` | 템플릿이 없으면 poison 레코드를 로그만 남기고 버린다. CDC 모드에서는 템플릿을 필수로(fail-fast) | 신규 |
| Jackson 버전이 Boot BOM을 덮어씀 | `build.gradle.kts:27`(3.2.2) + 모듈의 `api(platform(jackson-bom))`, Boot 4.1.1 BOM은 3.1.5 | Spring이 시험한 조합에서 벗어난다. Boot가 Jackson 버전을 갖게 한다(decision #20은 선언 위치만 다룬다) | 신규 |
| `api(platform(kotest-bom))`이 모든 모듈의 공개 제약으로 노출 | `build.gradle.kts:99` | 운영 runtimeClasspath에 테스트 BOM 제약이 실린다(jar는 없다). `testImplementation`·`testFixturesImplementation`으로 | 신규 |
| deprecated `spring-boot-starter-web` | `application/build.gradle.kts:26-28` | `spring-boot-starter-webmvc`로 바꾼다. 이 스타터도 Tomcat을 끌어오므로 exclude는 유지한다 | 신규 |
| CI 메모리 상한 합이 러너 용량을 넘을 수 있음 | `gradle-ci.properties:7-11`(데몬 3g+3g, parallel), 테스트 JVM `-Xmx4g` | 모듈 3개가 병렬로 돌면 상한 합이 약 18GB로 16GB 러너를 넘는다. 테스트 `-Xmx`를 낮추거나 `maxParallelForks`/`workers.max`를 제한 | 잔존(S16(j)) |
| `findById(...).orElse(null)` | `DebeziumLogTailingProcessor.kt:61`, `CveTopicRepositoryImpl.kt:44` | `findByIdOrNull` | 신규 |
| MCP 전송이 Boot `JsonMapper` 대신 자체 매퍼 사용 | `McpServerConfiguration.kt:87` | Kotlin 모듈과 `spring.jackson.*`이 빠진다. 지금은 `CallToolResult`만 다뤄 무해 | 신규 |
| eager 태스크 설정 | `build.gradle.kts:67,75` `tasks.withType<T> { }` | `withType<T>().configureEach { }` | 신규 |
| `slack-live`의 DB 비밀번호 기본값 커밋, 터널 포트에 metrics 노출 | `application-slack-live.yaml:26`(값은 적지 않음), `:56-59` | ngrok으로 외부에 열린 포트에서 `/api/actuator/metrics`가 무인증으로 보인다. 기본값을 지우고 `health`만 노출한다 | 신규 |

### 3.2 JPA·트랜잭션

| 항목 | 위치 | 요지 | review.md |
|---|---|---|---|
| **L-1. 리마인더·standup stuck 스윕이 DB 시계 `updated_at`과 앱 `Instant`를 비교** | `JpaSessionDispatchRepository.kt:36,84-85`, `JpaMeetingReminderRepository.kt:40,88-89` | 레인 판정이 엇갈려 소스로 확인했다 [소스]. Hibernate 7.4.5 MySQL/MariaDB 방언은 `Instant`를 UTC Calendar로 바인딩한다(`Dialect.java:1960-1966` → `TimestampUtcAsJdbcTimestampJdbcType`). 그래서 **DB 세션 존이 UTC인 동안에는 맞다.** 존이 바뀌거나 JDBC URL에 존 옵션이 붙으면 9시간 어긋나 stuck 행 복구가 늦어지거나 claim이 계속 리셋된다. outbox(A2)처럼 `:now`를 `Clock`으로 바인딩해 DB 설정에서 떼어 낸다 [실행 필요: prod `@@session.time_zone`] | 신규(S2와 같은 부류, A2는 outbox만 수정) |
| outbox `save()`가 매번 merge + SELECT | `OutboxMessage.kt:29`(앱이 정한 id), `:75`(`var version: Long = 0L`, primitive) | Spring Data는 primitive 버전을 "버전 없음"으로 보고 non-null id로 판단해 `merge`한다. 가장 바쁜 쓰기 경로에서 메시지마다 SELECT가 하나 더 나간다. `Long? = null` 또는 `Persistable` | 신규 |
| 트랜잭션 안에서 예외를 잡고 성공처럼 응답 | `StandupRoutineSetupService.kt:33`, `MeetingServiceImpl.kt:310`, `CommandRoleResolver.kt:44` | 안쪽 `@Transactional` 실패로 이미 rollback-only가 된 상태다. 친절한 회신을 stage해도 커밋에서 `UnexpectedRollbackException`이 나고 회신 outbox 행도 함께 사라진다 | 신규 |
| `runInTx`가 커밋 시점 실패를 `Result.failure`로 주지 않음 | `TransactionTemplateExt.kt:7-8` | `runCatching`이 람다만 감싼다. flush나 BEFORE_COMMIT 실패는 `execute`에서 던져져 `.onFailure`가 건너뛰어지고, 그 틱의 나머지(예: `detectCutoffs`)가 중단된다 | 신규 |
| `cve_topic.active`가 덮어써질 수 있음 | `CveTopicRepositoryImpl.kt:26-32`(관리 엔티티 전체 UPDATE), `JpaCveTopicRepository.kt:23`(벌크 UPDATE) | 롤링 배포 중 부트스트랩 upsert가 낡은 `active` 값으로 관리자 토글을 되돌린다. `@Version` 또는 `@DynamicUpdate` | 신규 |
| 엔티티 public setter와 외부에서 바꾸는 컬렉션 | 스키마 `var` 20개 중 14개가 public setter(CveTopic 6, CveEvent 5 등). `MeetingRepositoryImpl.kt:139`, `StandupRepositoryImpl.kt:90-91` | `protected set` + 행위 메서드 | 신규 |
| 네이티브 SQL의 상태 문자열 리터럴 | `Jpa*Repository` 6개 파일, 53곳(예: `'SENDING'`) | enum 이름을 바꿔도 컴파일은 통과하고 SQL만 조용히 0행이 된다. 바인딩 파라미터로 | 신규 |
| non-null 타입을 nullable 컬럼에 매핑 | `MeetingSchema.kt:168-169`(`absent_reason`), `OutboxMessage.kt:78-79` | 레거시 NULL 행이 매핑 시점에 NPE를 낸다 [실행 필요: NULL 행 count] | 신규 |
| checked 예외 커밋 경로(현재 휴면) + 트랜잭션 안의 Kafka 전송 | `KafkaEventPublisher.kt:34-58`: `@Transactional` 안에서 `send().get(5s)`, checked `TimeoutException` 재던짐 | 지금은 모든 이벤트가 `isInternal = true`라 도달하지 않는다. 외부 이벤트를 켜는 순간 롤백된 명령의 이벤트가 나가거나, 타임아웃에도 커밋된다. 외부 이벤트는 outbox로 보낸다 | 신규 |
| 요청 경로의 `findAll()`, 벽시계 직접 읽기 | `UserCommandRoleRepositoryImpl.kt:13`. `MeetingRepositoryImpl.kt:114`, `CveSummaryWorker.kt:25,27,45,74,83,94`, `CveCollector.kt:24` | 시간 의존 로직을 테스트로 고정할 수 없다(`CveSummaryWorker` stuck 판정 회귀가 녹색으로 통과). `Clock` 주입 | 신규 |

### 3.3 메시징·관측

| 항목 | 위치 | 요지 | review.md |
|---|---|---|---|
| CDC `Envelope`가 쓰지도 않는 Debezium 메타 필드 약 20개를 non-null로 요구 | `DebeziumOutboxMessage.kt:6,15,52-56,73-77` | 컨버터 설정이나 Debezium 버전이 바뀌면 모든 레코드가 역직렬화에 실패해 DLT로 가고, M-11 때문에 아무도 모른다. `op`·`after`만 모델링 | 신규 |
| 일시적 DB 오류가 약 2초 만에 DLT로 | `KafkaConsumerConfiguration.kt:139` `FixedBackOff(1s, 2)` | 정상 레코드와 진짜 poison이 DLT에 섞인다. 행이 진실의 원천이니 파싱 오류만 DLT로 보내고 나머지는 로그 후 스윕에 맡긴다 | 신규 |
| 종료 phase 10초 < CDC 레코드 1건 최대 약 60초 | `application-prod.yaml:7`, 컨테이너 `stopImmediate` 미설정 | 롤아웃 중 처리 중인 레코드가 완료 기록 전에 끊겨 300초 뒤 중복 게시되고, 같은 poll의 나머지는 닫힌 풀에서 실패해 DLT로 간다 [실행 필요]. `config-observability.md` § Graceful shutdown에 맞춰 예산을 다시 잡는다 | 신규 |
| outbox 헬스 체크가 호출마다 쿼리 7개, 재시도 행 하나에도 DOWN | `OutboxHealthIndicator.kt:23-40` | 캐시·타임아웃이 없다. 세부가 가려져 있어 "재시도 1건"과 "적체"를 구분할 수 없다 | 신규 |
| CDC 스냅숏 시각을 JVM 존으로 변환, 단위 가정 | `OutboxMessage.kt:103`(`ZoneId.systemDefault()`, 항상 1e6으로 나눔), `:96`(`e.message`만 로깅) | 지금은 행을 다시 읽어서 쓰지 않는다. 다음에 이 값을 읽는 코드는 9시간 틀린 시각을 받는다 | 신규 |

### 3.4 웹·HTTP 클라이언트

| 항목 | 위치 | 요지 | review.md |
|---|---|---|---|
| 처리할 수 없는 이벤트에 500 → Slack이 재시도 | `SlackMentionEventHandlerImpl.kt:70-75,91-92` → `ControllerAdvice.kt:38-44` | 바인딩 실패나 `AppIdNotFoundException`이 500이 되어 같은 페이로드가 4번 처리되고 ERROR가 4번 찍힌다. `security.md` § Responding to webhook deliveries대로 400 + `X-Slack-No-Retry: 1` [실행 필요: 실제로 해당 필드가 빠진 페이로드가 오는지] | 신규 |
| 컨트롤러 응답 계약이 느슨함 | `SlackEventController.kt:33,44,51`(`ResponseEntity<*>`, ack 본문에 `exception.toString()` 포함), `SlashCommandController` 7곳 `produces = form-urlencoded` | 내부 예외 문구가 200 본문으로 나간다. `consumes`의 오기다 | 잔존(R7) |
| `RestClientRequester`가 정적 `RestClient.builder()` 사용 | `RestClientRequester.kt:29-36`, `RestClientConfiguration.kt:12` | `http.client.requests` 메트릭과 `traceparent`가 없고 `spring.http.clients.*`로 조정할 수 없다. 주입받은 `RestClient.Builder` 사용 | 잔존(H13, 다른 수단으로 해결 처리됨) |

### 3.5 테스트·동시성

| 항목 | 위치 | 요지 | review.md |
|---|---|---|---|
| 블로킹 HTTP 주변의 `runCatching`이 인터럽트를 삼킴 | `SidecarAgentClient.kt:56-57`, `NvdCveSourceAdapter.kt:59`, `GithubReleaseSourceAdapter.kt:43`, `CveSummaryWorker.kt:69`, `Command.kt:33-41`(`Throwable`) | 종료 중 인터럽트가 "Sorry…" 회신이나 재시도 소모로 바뀌고 인터럽트 플래그는 지워진다. 플래그를 복원하고 다시 던진다 | 잔존(R7, `Command.kt`) · 나머지 신규 |
| 공유 H2에서의 전역 단언 | `MessageOutboxRepositoryTest.kt:67-70,343`, `MeetingRepositoryWriteTest.kt:435-449,510-539` | 스펙 순서에 따라 결과가 바뀐다. 블록 자신의 id로 걸러서 단언 | 신규 |
| 스펙 수준 strict mock을 초기화하지 않음 | `SlackOutboundRendererTest.kt:34`, `SlackOutboundStagerTest.kt:30-31`, `SlackApiEventConstructorTest.kt:23`, `KafkaEventPublisherTest.kt:59` | 케이스마다 인자가 달라서 우연히 통과하고 있다. 케이스별 생성 또는 `clearMocks` | 신규 |
| Kafka 테스트가 페이로드·헤더를 단언하지 않음 | `KafkaEventPublisherTest.kt:97-102` | 토픽과 키만 본다. `record.value()`와 타입 헤더를 단언 | 신규 |
| 타임아웃 없는 래치 대기 | `SlackRetryDeduplicatorTest.kt:126,131,133` | 교착이 생기면 CI가 30분 동안 멈춘다. `check(await(5, SECONDS))` | 신규 |
| 문서 불일치 | `application/src/test/AGENTS.md:8`("no H2") | 실제로는 스펙 6개가 H2 + 실 트랜잭션 매니저를 쓴다 | 신규 |

---

## 4. 이미 알려진 항목 (참고)

- **루트 `kotlin {}`·`java { toolchain }`가 모듈에 적용되지 않음**: 스킬 SKILL.md Gotcha와 정확히 일치한다. decisions #25가 단독 PR로 미뤄 두었고, `-Xjsr305=strict`를 켜면 컴파일 오류가 쏟아질 것으로 예상된다.
- **레플리카 간 Slack 재시도 dedup**: decisions #34, 미결.
- **H18 `api` 노출**: 스킬 기준으로도 정당하다(공개 인터페이스가 `JpaRepository`를 상속).

---

## 5. 스킬 기준으로 잘 지켜진 것

- **프록시**: `open class *RepositoryImpl`의 `@Transactional` 33개는 모두 `override`(open)이고, application의 12개는 `@Service` 클래스에 있다. advice가 조용히 빠지는 메서드는 없다. `suspend`/`@Async` 조합도 없다.
- **엔티티**: 15개 모두 일반 `class`이고, `@Enumerated(STRING)`만 쓰며 equals/hashCode/toString을 재정의하지 않는다. 회의 쓰기는 관리 엔티티를 수정하고 `@Version`을 쓰며, `clearAutomatically`나 `LockModeType`은 쓰지 않는다.
- **`JpaMeetingRepository`**: fetch join alias로 필터링하지 않고(EXISTS), `LEFT JOIN FETCH`를 쓴다.
- **outbox**:
  - CAS 소유권 토큰(`attempt_count`)을 쓰고, 실제 발송 수(`send_count`)를 따로 센다.
  - 네이티브 쓰기는 전부 `:now`를 `Clock`으로 바인딩한다.
  - CDC 릴레이는 툼스톤, delete, non-PENDING after-image를 건너뛰고, 행을 다시 읽은 뒤 claim한다.
  - DLT는 `byte[]` 템플릿으로 분기하고 `-dlt` `NewTopic`을 선언한다.
  - `ErrorHandlingDeserializer`, `AckMode.RECORD`, auto-commit 끔, 파싱 오류 비재시도를 모두 갖췄다.
- **Slack 웹훅 필터**:
  - raw 본문 HMAC과 상수 시간 비교를 하고, 헤더를 먼저 검사한 뒤 1 MiB 상한을 둔다.
  - 쿼리 문자열 파라미터는 무시한다.
  - 정규화한 경로 3개 기준의 default-deny다.
  - in-flight 재시도에는 503을 준다.
  - 필터는 한 번만 등록된다.
- **설정·로깅**:
  - 서명 시크릿은 미해결 플레이스홀더와 blank에서 기동이 실패한다.
  - `@Value`의 `\${}` 이스케이프가 정확하다.
  - Jackson 3 import(databind는 `tools.jackson`, annotation은 `com.fasterxml`)가 맞다.
  - `ControllerAdvice`는 REEH를 상속하고 `log`를 쓴다.
  - error/warn 로깅 55건이 throwable을 넘기고, `.message`만 찍는 곳은 2건이다.
- **HTTP 클라이언트**: 모든 외부 호출에 타임아웃이 있다(본문 데드라인은 H-3 예외). `Retry-After`는 초와 HTTP 날짜를 모두 파싱한다. `response_url`은 호스트 allowlist를 쓰고 리다이렉트를 따르지 않으며 본문 4KiB만 읽는다.
- **테스트**:
  - Spring 스펙 8개 모두 `@ApplyExtension` + `@Autowired constructor`, Boot 4 슬라이스 import를 쓰고 `@MockBean`은 없다.
  - `Thread.sleep`, `runBlocking`, 비활성 스펙, `unmockkAll`이 모두 0건이다.
  - 경합 테스트는 스레드별 트랜잭션, 시간 제한 있는 래치, 지는 쪽 경로 단언을 갖췄다(`MeetingRepositoryWriteTest:76-107`).
  - 버그를 고정하던 S3 테스트는 기대값이 뒤집혀 있다.
- **빌드**: 패키징된 jar에 Tomcat core가 없다. Boot BOM이 testFixtures에도 적용돼 있다. `extra["x"] as String` 형식을 쓰고 `by extra`/`kotlinOptions`는 없다.

---

## 6. 권장 순서

| 순서 | 항목 | 이유 |
|---|---|---|
| 1 | H-1 Hikari, H-2 스케줄러, H-3 CVE 본문 데드라인 | 한 줄~몇 줄로 고칠 수 있고, 운영 용량 계산의 전제를 되살린다 |
| 2 | H-6 컨텍스트 스모크 테스트 | 1번을 테스트로 고정하고, 앞으로 같은 부류(설정이 적용되지 않음, 배선 오류)를 막는다 |
| 3 | H-5 Slack POST 재시도 범위, M-4 리스너 안 재시도, M-8 AI 턴 커밋 후 시작 | 사용자에게 보이는 중복과 유실 |
| 4 | H-4 standup 답변 중복 | 팀 규모가 커지면 요약이 영구 실패한다 |
| 5 | M-1 보안 필터 로그, M-2 시크릿 fail-fast, M-10 forwarded 헤더 | 보안 감사 로그와 배포 안전성 |
| 6 | M-5 OSIV → M-6 `views.open`, M-7 리마인더 쿼리 | M-5가 M-6의 선행 조건 |
| 7 | M-3, M-9, M-11, M-12, M-13 | 운영 가시성과 테스트 신뢰도 |
| 8 | 3장 낮음 | 영역별로 묶어 정리 PR |

---

## 7. 반영 현황 (2026-10-02)

같은 브랜치에 지적 하나당 커밋 하나로 반영했다(`27899d2..HEAD`). 각 수정은 새 테스트를 넣고 수정을 되돌리거나 변형했을 때 그 테스트가 실패하는 것을 확인했다. 예외는 "검증" 열에 적었다. 마지막 전체 `./gradlew build`(HEAD `2e76910e`) 결과는 domain 363, infrastructure 620, application 506건이고 실패는 0이다. 이 범위의 `7f2c45b0`(마크다운 이스케이프·Block Kit 한도 공통 함수)은 같은 체크아웃에서 돌던 다른 세션의 커밋이며 이 반영 작업에 속하지 않는다. 그 커밋의 테스트도 위 수에 들어 있다.

**상태**: 적용 / 일부 적용(남은 부분 명시) / 결정으로 보류 / 해당 없음(지적이 틀림)

### 높음·중간

| ID | 상태 | 커밋 | 검증 · 남은 것 |
|---|---|---|---|
| H-1 | 적용 | `16892cb1` | 스모크 테스트가 풀 20을 단언 |
| H-2 | 적용 | `47dd4131`, `8170b811` | `ThreadPoolTaskScheduler` 4스레드. **남음**: 느린 잡 4개가 동시에 걸리면 풀이 다시 찬다(리뷰 레인 지적) |
| H-3 | 적용 | `f0181d06` | 본문 데드라인과 크기 상한 |
| H-4 | 적용 | `ab65462a`, `30ff436b` | 답변 중복 제거, 요약을 멤버별 섹션(각 3000자 이하, 48명 + "…and N more")과 페이로드 상한으로 나눔 |
| H-5 | 적용 | `92fac16a`, `55ca5466`, `6c376484`, `4e130f7c` | 보내지 않은 요청만 인라인 재시도. 보냈을 수 있는 실패는 outcome_unknown으로 처리하고, OkHttp 자체 재전송은 끔. "보냄"의 기준은 헤더 쓰기가 끝난 시점(`requestHeadersEnd`)이다(2차 리뷰 레인 지적으로 `requestHeadersStart`에서 옮김). Slack이 2xx로 답했는데 본문을 읽지 못한 경우(Gson 예외, 빈 본문 NPE)도 결과 불명으로 분류한다(Codex 지적). **판단**: 503을 "처리되지 않음"으로 본 것은 판단이며 실제 Slack 응답으로 검증하지 않았다. **남음**: 아래 2차 리뷰 레인 표 |
| H-6 | 적용 | `61ba69d1`, `86d7ef16` | `@SpringBootTest` 스모크 |
| M-1 | 적용 | `87e61e5d` | |
| M-2 | 적용 | `045e9b49` | |
| M-3 | 적용 | `cdb269fa` | |
| M-4 | 적용 | `7eea7013` | |
| M-5 | 적용 | `ada58ec7` | |
| M-6 | 적용 | `9c2ba01e` | |
| M-7 | 적용 | `a4b4edab`, `22d8b1da` | |
| M-8 | 적용 | `53e35cc3` | |
| M-9 | 적용(사용자 결정: 제한된 큐 + busy 회신) | `d1547f2c`, `a239ca8e`, `99dc0a32`, `cac82007` | 포화 안내는 AFTER_COMMIT 리스너 안에서 실행되므로 REQUIRES_NEW로 쓴다. 실 JpaTransactionManager로 확인했다. 그동안 커밋된 트랜잭션의 커넥션이 잡혀 있어 거절된 멘션 하나가 커넥션 두 개를 쓴다(H2 실측: 세션 2, 일반 트랜잭션 1). 풀 산정식에 넣었다 |
| M-10 | 적용(사용자 결정: forwarded 헤더 차단) | `5ed9ddf2` | **실행 필요**: 클러스터 안 우회 시도 |
| M-11 | 적용(사용자 결정: Prometheus까지) | `12995d31`, `a60b87ee` | 게이지와 DLT 핸드오프 카운터를 추가하고 Debezium heartbeat와 런북을 넣음. **실행 필요**: 실제 스크레이프와 알림 규칙, 커넥터 heartbeat |
| M-12 | 일부 적용 | `04756fe1`, `f6014aa5` | 6초 페이싱과 403/429/503 거부 로그는 적용했다. ledger claim 반환은 **적용하지 않았다**. lookback(2 × window 이상)이 일회성 누락을 메우고, 반복 누락의 원인은 고정 순서와 무간격 호출이었기 때문이다. **남음**: 페이싱 동안 스케줄러 스레드 하나를 붙잡는다. 같은 egress IP를 쓰는 레플리카는 할당량을 나눠 쓴다 |
| M-13 | 적용 | `be724ba5` | |

### 낮음

| 항목 | 상태 | 커밋 | 비고 |
|---|---|---|---|
| `AppConfig` toString 시크릿 | 적용 | `51eac7e0` | |
| 빈 생성자 기본값, 팩토리의 `clock` 누락 | 적용 | `50f96841`, `13bdde51` | |
| `CdcDeadLetterRecovery` nullable 템플릿 | 적용 | `645301d7` | |
| Jackson이 Boot BOM을 덮어씀 | 결정으로 보류 | `d76a3348` | decision #35 |
| kotest BOM `api` 노출 | 적용 | `282c0f64` | |
| `spring-boot-starter-web` | 적용 | `faadc968` | |
| CI 메모리 상한 | 적용 | `16d723bf` | 테스트 힙 2g |
| `findById().orElse(null)` | 적용 | `e80b01f7` | |
| MCP 자체 매퍼 | 적용 | `83fb79fd` | **실행 필요**: MCP 라이브 경로 |
| eager 태스크 설정 | 적용 | `0cf93fde` | |
| `slack-live` 비밀번호 기본값, metrics 노출 | 적용 | `4ee842fe` | **남음**: 지운 기본값은 git 이력에 남아 있다. 실제 비밀번호였다면 교체해야 한다 |
| L-1 stuck 스윕 시계 | 적용 | `0dfd5a5d` | 쓰기와 비교가 모두 `Clock`으로 바인딩한 `:now`를 쓴다. 그래서 DB 세션 존과 무관하다 [읽기. MariaDB에서는 실행하지 않음] |
| outbox merge + SELECT | 적용 | `e5defc16` | `Persistable` |
| 트랜잭션 안에서 예외를 잡고 성공 응답 | 적용 | `b265a272` | |
| `runInTx` 커밋 시점 실패 | 적용 | `5caf0545` | |
| `cve_topic.active` 덮어쓰기 | 적용 | `3c46beb0` | `@DynamicUpdate` |
| 엔티티 public setter | 적용 | `8b380a79` | |
| 네이티브 SQL 상태 리터럴 | 다른 수단으로 적용 | `074cf0dc` | 바인딩 파라미터 대신 가드 테스트를 넣었다(아래 설명) |
| non-null 타입 ↔ nullable 컬럼 | 일부 적용 | `701463d3` | 아래 설명 참고 |
| checked 예외 커밋 경로 + 트랜잭션 안 Kafka 전송 | 일부 적용 | `5dd6edcf` | `KafkaPublishException`(unchecked)으로 롤백을 보장했다. 외부 이벤트를 outbox로 옮기는 일은 휴면 경로라서 문서에만 기록했다 |
| 요청 경로 `findAll()`, 벽시계 | `Clock`은 적용, `findAll`은 해당 없음 | `13bdde51` | 역할 목록 명령은 모든 부여를 보여 줘야 한다 |
| CDC `Envelope` 메타 필드 | 적용 | `be581fb7`, `bf6719cb` | |
| 일시 오류가 DLT로 | 적용 | `b51580ff` | 파싱·역직렬화 실패만 DLT로 보낸다. 나머지는 로그를 남기고 ack하며 복구 스윕에 맡긴다 |
| 종료 예산 | 적용(사용자 결정: 진행 중 디스패치를 끝까지 기다림) | `c2d1770f`, `7e054dd8`, `00a9abe5`, `dc28030a`, `0e2700a9` | `ShutdownBudgetTest`: 5 + 3×10 + 2×5 + 20 + 20 + 10 = 95초 ≤ 유예 100초. 처음에는 단계를 둘로 세어 75 ≤ 80으로 봤다. Kafka producer 종료(기본 30초, 단계 타임아웃 밖에서 동기로 닫힘)는 Codex가 찾았고 5초로 제한해 두 번을 넣었다. 실행 중인 잡이 있으면 스케줄러도 자기 단계에서 기다린다(프로브: 단계당 1초에서 close 1초→2초). 같은 poll의 나머지가 닫힌 풀에서 DLT로 가는 부분은 `stopImmediate`로 막았다. **해소(2026-10-02, review.md 15장)**: 리스너 단계와 릴레이 executor가 디스패치 1건 예산(`RECORD_SHUTDOWN_WAIT`, 코드 상수에서 계산해 약 46초)만큼 기다리고, 정지 시 큐에 남은 claim은 새로 보내지 않는다(스윕이 회수). 이 예산은 상태 기록의 SQL 시간을 0으로 세므로 풀과 DB가 정상일 때만 진행 중 디스패치가 끝까지 간다(review.md 15.4). 유예 180초, 계산된 합계 162초(`dc28030a`), 큐에 남은 AI 턴의 안내 예산을 넣어 170초(review.md 15.9). **남음**: 커넥션 풀 고갈 시 문장마다 붙는 `connection-timeout`과 상태 UPDATE의 잠금 대기는 예산 밖이고, 크래시·SIGKILL은 여전히 중복 게시가 될 수 있다 |
| outbox 헬스 쿼리 | 적용 | `5796442c` | 쿼리 타임아웃 힌트와 prod 캐시 10초. 재시도 행 하나에 DOWN이 되는 동작은 **설계대로 유지**한다(게이트에 쓰이지 않고, 알림은 `outbox_retrying_messages`로 보냄). **실행 필요**: 타임아웃 동작(H2로 재현 불가) |
| CDC 스냅숏 시각 존·단위 | 적용 | `c766fd67` | UTC 기준이며 밀리·마이크로 단위를 구분한다. **실행 필요**: 실제 Debezium 레코드 |
| 처리할 수 없는 이벤트에 500 | 적용 | `f49e4ed0`, `f026e232` | 400 + `X-Slack-No-Retry` |
| 컨트롤러 응답 계약 | 적용 | `0248598b` | |
| `RestClientRequester` 정적 빌더 | 적용 | `140d4c4d`, `13224091` | 이제 `http.client.requests`가 기록된다. `traceparent`는 tracing 브리지가 없어 원래 해당이 없다. 명시적 팩토리가 3s/10s 타임아웃을 고정하므로 `spring.http.clients.*`는 의도적으로 적용되지 않는다. 응답 변환이 Boot JSON 매퍼로 바뀌었으므로, DTO에 없는 키가 들어 있는 `users.profile.get` 본문이 `SlackUserProfileDto`로 읽히는지 스모크에서 확인한다(`fail-on-unknown-properties=true`이면 실패) |
| 인터럽트를 삼키는 `runCatching` | 적용 | `f99098ff`, `76c1988e` | 처음에는 헤더 대기 중 인터럽트만 보존됐다. SSE 본문을 읽는 중의 인터럽트는 JDK 클라이언트가 `IOException`(플래그 유지)으로 알려 CVE 재시도를 썼다. 2차 리뷰 레인 지적으로 고침 |
| 공유 H2 전역 단언 | outbox는 적용, `MeetingRepositoryWriteTest`는 해당 없음 | `060b2372` | 지적한 구간은 `meetingUid`로 다시 조회하는 단언이다 |
| 스펙 수준 strict mock | 적용 | `68f8b5a4` | |
| Kafka 테스트 단언 | 적용 | `9e32baf1` | |
| 타임아웃 없는 래치 | 적용 | `02291b7e` | |
| 문서 불일치(`no H2`) | 적용 | `690f741e` | |

### 2차 리뷰 레인 (반영 커밋 `55ca5466^..`, code-reviewer)

반영 커밋 26개를 따로 리뷰했다. 지적마다 소스를 다시 읽거나 실행해 판정했다.

| 지적 | 판정 | 조치 |
|---|---|---|
| [중간] 과부하 안내가 커넥션을 하나 더 잡는다 | 확인(H2 실측) | `cac82007`. 산정식에 추가. 코드는 회의 쓰기와 같은 패턴이라 그대로 둠 |
| [중간] 종료 예산이 처리 중인 CDC 레코드를 덮지 못한다 | 확인(spring-kafka 4.1.1 소스: 단계 대기 뒤 `stop()`은 `isRunning()`으로 막혀 아무것도 안 함) | `7e054dd8`. 단계 수 정정, 잘못된 주석 정정, 잔존 위험 명시. 위 표의 "종료 예산" |
| [낮음] SSE 본문 중 인터럽트가 CVE 재시도를 쓴다 | 확인(되돌리면 새 케이스 실패) | `76c1988e` |
| [낮음] HTTP/2에서 보내지 않은 요청이 "보냄"으로 집계된다 | 확인(OkHttp 4.12 소스: `requestHeadersStart` 뒤에 `newStream`) | `6c376484`. HTTP/2 서버로 실행하지는 않았다. 리스너 계약만 테스트로 고정 |
| [낮음] 기록된 뒤의 비 I/O 실패(2xx 비 JSON 본문의 Gson 예외, 빈 본문 NPE)가 재전송으로 이어진다 | 확인(slack-api-client 1.51.0 소스, 새 케이스가 수정 전 실패). 반영 전부터 있던 동작 | 처음에는 조치하지 않았다가 Codex도 같은 지적(중간)을 해 `4e130f7c`로 고침 |
| [질문] `restRequester`가 Boot 매퍼로 프로필 DTO를 읽는가 | 실행으로 확인: 읽는다 | `13224091`. 스모크에 고정 |

### Codex 교차 리뷰 (`HEAD=7e054dd8` 기준, 읽기 전용)

| 지적 | 판정 | 조치 |
|---|---|---|
| [중간] Slack 2xx 본문 파싱 실패가 중복 게시로 이어진다(H-5 불완전) | 확인. 위 2차 레인의 낮음 지적과 같은 경로 | `4e130f7c` |
| [중간] 종료 예산에 Kafka producer close 대기(각 30초)가 빠졌다 | 확인(spring-kafka 4.1.1: `DefaultKafkaProducerFactory`는 `SmartLifecycle` 단계 `Integer.MIN_VALUE`에서 `stop()`이 `destroy()`를 동기로 부른다). 유예 초과는 브로커 장애 중 미전송 레코드가 있을 때의 추론 | `00a9abe5`. 5초로 제한하고 예산에 두 번 넣음 |
| [낮음] 모달 지연 테스트가 호출부의 래핑 제거를 잡지 못한다 | 확인(래핑을 빼도 기존 테스트 통과) | `d6bd162f`. 슬래시 엔드포인트 6개와 interaction 핸들러에 호출부 테스트(래핑 제거 시 실패) |

Codex는 트랜잭션 전파, Spring 빈 연결, 회의·스탠드업 저장, CDC 파싱·복구 분기, HTTP 본문 데드라인 산술에서는 결함을 찾지 못했다고 보고했다. `SocketModeReceiver`(local 전용)의 지연 래핑은 여전히 테스트가 없다.

### 최종 리뷰 레인 (`7e054dd8..00a9abe5`, code-reviewer, 읽기 전용)

| 지적 | 판정 | 조치 |
|---|---|---|
| [낮음] `4e130f7c`의 catch가 `dispatchOnce` 전체를 감싸 우리 코드의 NPE도 "본문을 읽지 못함"으로 분류한다. `{"ok":false}`(error 없음)이면 `failOutput(reason = result.error)`에서 NPE | 확인(새 케이스가 `reason`을 되돌리면 실패) | `bf66c722`. SDK 호출만 감싸고 `requestHeadersEnd` 이후로 한정, error 없는 거절은 `UNSPECIFIED_ERROR_REASON` |
| [낮음] 지연 테스트가 리스너를 직접 불러 Spring 이벤트 전달 경로를 보지 않는다 | 확인 | `2e76910e`. 스모크에서 `ApplicationEventPublisher`로 발행(리스너가 바로 열면 실패) |
| [낮음] DLT bytes factory 타임아웃 테스트가 사본을 본다 | 확인 | `2e76910e`. 빈이 쥔 팩토리를 단언 |
| [낮음] controllers AGENTS의 "컨트롤러 스펙 없음" | 확인 | `2e76910e` |
| [낮음] 종료 중 poison 레코드의 DLT 전송이 새 producer를 만들어 close가 한 번 더 생길 수 있다 | 추론(소스) | 문서에만 기록. 예산 여유 5초 안 |
| [질문] HikariCP 종료 대기가 예산에 없다 | 추론(대기 지점은 소스로 확인, 시간은 추론) | 문서에만 기록. DB에 닿지 않을 때만 길어진다 |

**같은 레인이 남긴 것**: `REFUSED_STREAM`은 헤더를 보낸 뒤라 여전히 "보냄"(outcome unknown)으로 분류된다. HTTP/2는 처리하지 않았음을 보장하므로 보수적인 쪽의 오차다. `retryOnConnectionFailure(false)`는 다른 경로(IP)로의 연결 재시도도 끈다. 자체 재시도가 없는 `views.open`은 연결 실패 시 바로 대체 안내로 간다.

리뷰에 없던 결함 하나를 반영 중에 찾아 함께 고쳤다. 스탠드업 멤버가 두 번째 답변을 내면 unique key 위반이 났다(`cef26a78`, HEAD에서 재현).

**네이티브 SQL 리터럴을 바인딩 대신 가드로 막은 이유.** enum 상수 이름을 바꾸면 저장된 행에는 옛 이름이 그대로 남는다. 그래서 바인딩 파라미터로 바꿔도 데이터를 마이그레이션하지 않으면 똑같이 0행이 된다. 리터럴에만 있는 위험은 SQL이 더 이상 없는 상수를 가리키는 경우다. `NativeQueryStatusLiteralTest`는 여섯 저장소의 네이티브 `@Query`에 들어 있는 따옴표 상수가 해당 컬럼 enum의 상수인지 검사한다. 클래스패스를 스캔해 매핑 없이 리터럴을 쓰는 새 저장소도 잡는다.

**nullable 컬럼 지적이 일부 적용인 이유.** 두 프로퍼티는 테이블이 생길 때부터 non-null 기본값을 가졌다(`e33aba5e`, `42be2edb`). 앱의 쓰기 경로는 셋이고 어느 것도 NULL을 쓰지 않는다: 엔티티 insert, 파라미터 UPDATE, 리터럴 UPDATE. 그래서 NULL 행은 수동 SQL로만 생길 수 있다. 매핑에 `nullable = false`를 두어 Hibernate가 만드는 스키마(H2, 새 DB)가 이 계약을 강제한다. 기존 MariaDB 컬럼은 바뀌지 않는다. **실행 필요**: prod에서 아래 두 쿼리를 확인한 뒤 V24 패치를 결정한다(V23은 outbox payload MEDIUMTEXT가 차지했다, review.md 15장).

- `SELECT COUNT(*) FROM outbox_message WHERE status IS NULL`
- `SELECT COUNT(*) FROM meeting_participants WHERE absent_reason IS NULL`

`absent_reason`은 Hibernate가 만든 `enum(...)` 타입일 수 있으므로 `SHOW CREATE TABLE`을 먼저 본다. 그 전에는 컬럼 타입을 다시 쓰는 `MODIFY`를 하지 않는다.

---

## 부록 A. 메인 세션 검증 기록

scratch 경로는 `/private/tmp/claude-501/-Users-junho-workspace-CodeCompanion/339fa428-d11b-46d9-a0d8-071e21f0681d/scratchpad/`이고, 재부팅하면 사라진다.

- **A-1 Hikari 프로브** (`hikariprobe/`, Boot 4.1.1, H2, Temurin 21). 같은 `spring.datasource.hikari.*`를 준 결과:

  | 빈 생성 방식 | 결과 |
  |---|---|
  | 프로젝트 방식(`initializeDataSourceBuilder().type(HikariDataSource)`) | `maxPool=10 connTimeout=30000 poolName=null isolation=null` |
  | Boot 자동 구성 | `maxPool=20 connTimeout=5000 poolName=hikari_pool isolation=TRANSACTION_READ_COMMITTED` |

- **A-2 스케줄러 프로브** (`schedprobe/`): H-2의 표. 생성된 스케줄러 타입은 가상 스레드에서 `SimpleAsyncTaskScheduler`, 그 외에는 `ThreadPoolTaskScheduler`였다. 근거 소스는 `DefaultTaskSchedulerConfiguration:53-64`, `TaskSchedulingConfigurations:75`, 그리고 `SimpleAsyncTaskScheduler` Javadoc(spring-context 7.0.9: "Scheduling with a fixed delay enforces execution on a single scheduler thread")이다.
- **A-3 로거 바이트코드**: `application/build/classes/.../SlackRequestVerificationFilter.class`(2026-09-28 빌드, 소스와 동일)에서 `getfield logger:Lorg/apache/commons/logging/Log;` → `Log.warn(Object)`/`Log.info(Object)` 6곳을 확인했다. 파일 수준 `logger`를 쓰면서 Spring 기반 클래스를 상속하는 파일은 이것 하나다.
- **A-4 Instant 바인딩**: hibernate-core 7.4.5 `Dialect.java:1960-1966`(`TimeZoneSupport`가 NATIVE가 아니면 `TimestampUtcAsJdbcTimestampJdbcType`), 해당 바인더 `:78-92`(`setTimestamp(…, UTC Calendar)`). MySQL/MariaDB 방언은 `getTimeZoneSupport`를 재정의하지 않는다.
- **A-5 Boot 기본값** (4.1.1 메타데이터): `spring.jpa.open-in-view=true`, `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`. Executor 백오프는 `TaskExecutorConfigurations.OnExecutorCondition`(`@ConditionalOnMissingBean(Executor)` 또는 `mode=force`). forwarded 헤더는 `CloudPlatform.isUsingForwardHeaders()`의 기본 `true`(NONE만 `false`).
- 레인이 직접 재현한 것은 H-4(Hibernate 7.4.5 + H2 단독 프로그램, `scratchpad/bagtest/src/bt/Main.java`)뿐이다. 나머지 레인 지적은 [읽기]이며, H-1·H-2·H-5·M-1·M-8과 L-1의 연결 고리는 메인 세션이 코드를 다시 열어 확인했다.

## 부록 B. 이 문서의 이전 판

처음 작성한 판은 요청을 반대로 이해한 "스킬의 빈 곳 리뷰"였다. `.omc/artifacts/skill-gap-review-2026-10-01.md`로 옮겨 두었다. 그 사이 스킬이 갱신된 것(`scheduling-executors.md` 신설 등)은 그 문서가 반영된 결과로 보인다.
