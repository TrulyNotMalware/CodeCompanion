# 수정 브랜치 리뷰 — outbox·relay·스케줄러·Kafka·Slack 발송·배포 영역

**대상**: `feature/review-round3-fixes` @ `7c70c5d`, 범위 `b9c261a..HEAD` 74커밋. 파일은 메인 체크아웃(`.`) 기준으로 읽었습니다. 제 워크트리는 이 브랜치가 아니라 오래된 다른 브랜치(`c754aab`)여서 쓰지 않았습니다.

**실행으로 확인한 것**
- `:application:test --tests 'dev.notypie.application.service.relay.*'`: 7개 스펙, 66개 테스트 모두 통과.
- `SchedulingWiringSmokeTest` 3/3, `ShutdownBudgetTest` 6/6: HEAD 커밋 뒤인 09:39에 다른 실행이 남긴 결과 XML로 확인했습니다.
- 프로브 2개(`scratchpad/rev1/shutdown/ShutdownProbe*.java`, `scratchpad/rev1/slots/SlotsProbe.java`).
  - 둘 다 spring-context 7.0.9 jar로 돌렸습니다.
  - 원 코드를 띄운 것이 아니라 Spring 클래스로 같은 설정을 재현한 것입니다.

**라이브러리 의미 확인(javap)**: spring-context 7.0.9, spring-kafka 4.1.1, slack-api-client 1.51.0, okhttp 4.12.0, spring-web 7.0.9.

**판정 요약**
- 대부분 실제로 고쳐졌습니다.
- PARTIAL은 3건: T12(종료 예산 산식이 틀림), T14(헬스 신호 없음), O3(문서 한 곳에 옛 수치가 남음).
- 수정이 새로 만든 결함 중 가장 무거운 것은 F1(Medium)입니다. 종료 대기 시간이 단계별로 차례대로 더해지는데, grace 90초는 단계 하나만 계산했습니다.

---

### 1. 항목 판정표

| 항목 | 판정 | 근거 file:line | 설명 |
|---|---|---|---|
| **T1** 스케줄러 | **FIXED** (잔여 F2·F3·F4) | `SchedulingConfig.kt:13-16`, `application.yaml:25-31`, `AsyncConfig.kt:29-42`(AbortPolicy `:37`), `SlackMessageRelayServiceImpl.kt:57-78`, `OutboxRecoveryScheduler.kt:56-63,79-89`, `PollingMessageProcessor.kt:34-44` | `taskScheduler` 빈을 이름으로 등록해 Boot 기본값이 물러나고, `ThreadPoolTaskScheduler` pool 4가 적용됩니다(스모크 테스트 통과). CallerRuns를 AbortPolicy로 바꿨고, 읽는 쪽은 `freeDispatchSlots()` 만큼만 claim합니다. 거절된 claim은 발송 없이 IN_PROGRESS로 남습니다. `TaskRejectedException extends RejectedExecutionException`(javap)이라 `:69`의 catch가 잡습니다. 그 행은 300초 뒤 `findStuckInProgress`→`reclaim`으로 회수됩니다. CDC 경로는 영향이 없습니다: `DebeziumLogTailingProcessor.kt:70-71`이 리스너 스레드에서 `dispatchClaimed`를 직접 부르고 executor·슬롯을 쓰지 않습니다. relay executor를 감싸는 프록시는 현재 배선에 없습니다(저장소에 `@Aspect`·BPP·TaskDecorator 0건, Boot 메트릭 바인더는 감싸지 않음). 다만 감싸이면 `Int.MAX_VALUE`가 되는 fail-open 구조입니다(F4). |
| **T12** 종료 예산 | **PARTIAL** | `KafkaConsumerConfiguration.kt:46-48,215-218`, `application.yaml:4-9`, `AsyncConfig.kt:38-40`, `deployment.yaml:30-31` | `stopImmediate`·`shutdownTimeout` 60초·phase 60초·grace 90초는 반영됐습니다. 처리 중인 레코드의 오프셋 처리는 맞습니다: spring-kafka 4.1.1 `doInvokeWithRecords`는 `stopImmediate && !isRunning()`이면 *다음* 레코드 앞에서만 멈추고, RECORD ack가 처리한 레코드를 커밋합니다. 나머지는 커밋되지 않고 재전달되며, PENDING 재조회 덕분에 안전합니다. 그러나 대기 시간은 Kafka 단계(≤60) → 웹 graceful(≤60) → executor 단계(실행 중인 스케줄 작업 ≤60) → **relay executor 파기 대기(≤60)** 순으로 **차례대로 더해집니다**. relay executor는 종료 중에도 큐에 있던 claim을 새로 발송합니다(F1). |
| **O2** | FIXED | `PollingMessageProcessor.kt:22-30` | AtomicBoolean으로 재진입을 막습니다. `ThreadPoolTaskScheduler`에서는 fixedRate가 원래 겹치지 않으므로 이중 보호입니다. |
| **O3** | **PARTIAL** | relay `AGENTS.md:73-89`, `impl/command/AGENTS.md:104-117` vs `application/src/main/resources/AGENTS.md:55-61` | 두 AGENTS는 45.64초 + SQL 6×`connection-timeout`으로 통일됐습니다. 하지만 resources AGENTS에는 "3s connect + 10s read, about 53s, about 7s left"가 그대로 남았습니다(3장 D5). |
| **O4** | FIXED | `OutboxHealthSnapshot.kt:22-23,26-42`, `OutboxHealthIndicator.kt:19-20`, `OpsStatusService.kt:71-74` | 두 곳이 같은 스냅숏과 같은 `healthy` 판정을 씁니다. |
| **O6** | FIXED | `SlackMessageRelayServiceImpl.kt:108-116` | renew 전에 return하므로 send 예산을 쓰지 않고 FAILURE도 아닙니다. 24시간 상한에서 sweep 포기 또는 `:93-107`이 처리합니다. |
| **O7** | FIXED | `KafkaConsumerConfiguration.kt:52-53,73-82,84-121` | DLT 전용 producer에 `max.block.ms` 5초, 동기·비동기 실패를 모두 세는 카운터. `failIfSendResultIsError(false)`라 future를 기다리지 않습니다. |
| **O8** | FIXED | `OutboxRecoveryScheduler.kt:64-78`, `SlackMessageRelayServiceImpl.kt:92-107`, `MessageOutboxRepository.kt:114-131` | 기한이 지난 PENDING은 claim하지 않고 `abandonPending`으로 정리합니다. 모든 읽기 경로가 `dispatchClaimed`의 기한 검사를 거칩니다. |
| **T6** | **FIXED** (잔여 F5) | `ApplicationMessageDispatcher.kt:120-139,257-311`(probe `:270-280`, 5xx `:288-296`, IOException `:301-304`), `:400-455`(probe `:418-420`, IOE `:451-454`), `RequestSendTracker.kt:12-44`, `SlackRequestBuilderConfiguration.kt:32-44` | SDK 클라이언트와 response_url 클라이언트 둘 다 `eventListenerFactory(RequestSendTracker)`를 씁니다. 운영 빈은 생성자 기본값을 쓰므로 실제로 그 클라이언트를 씁니다. 근거(javap): `Slack(SlackConfig,SlackHttpClient)` private 생성자가 `httpClient.setConfig(config)`를 부르고, `postFormWithAuthorizationHeader`→`newCall().execute()`가 호출 스레드에서 동기로 실행됩니다. 따라서 ThreadLocal은 RealCall이 만들어지는 순간에 읽히고, `track`의 finally에서 지워지므로 새지 않습니다. 다른 스레드의 `newCall`은 `EventListener.NONE`을 받습니다. stats가 꺼져 있어 `TeamIdCache`의 `auth.test`는 건너뜁니다. 테스트도 유효합니다: 본문 전송 뒤 stall이면 calls 1, 연결 거부면 attempts 2·calls 1. |
| **T13** | FIXED | `ApplicationMessageDispatcher.kt:457-464,502,431-432` | 비멱등 호출의 `internal_error`는 outcome_unknown, `chat.update`만 transient입니다. |
| **outcome_unknown 종단성** | FIXED | `SlackMessageRelayServiceImpl.kt:140-149`, `OutboxMessageEvents.kt:22-27` | `when`의 else → `complete` → `!ok`이므로 `MessagePublishFailedEvent`, 곧 FAILURE입니다. 판정 함수들의 reason 문자열이 서로 달라 분기가 겹치거나 빠지는 곳이 없습니다(`ApplicationMessageDispatcher.kt:95-101`). |
| **T14** access_blocked | **PARTIAL** (F6) | `ApplicationMessageDispatcher.kt:52-69,503-510`, `SlackMessageRelayServiceImpl.kt:142-145,170-190`, `MessageOutboxRepository.kt:138,156` | 보류는 맞습니다: renew에서 +1, defer에서 `GREATEST(send_count-1,0)`으로 상쇄되어 예산을 쓰지 않습니다. `eligibleAt ≤ createdAt+24h`(`:172`)이고, 24시간 뒤에는 sweep 포기 또는 만료 처리됩니다. 그러나 14.4가 요구한 **"헬스 DOWN"은 없습니다**. Micrometer 카운터와 ERROR 로그뿐입니다. |
| **D4** | FIXED | `ApplicationMessageDispatcher.kt:80-82,326-335` | BigInteger로 비교해 [0, 24h]로 제한합니다. HTTP-date도 같은 범위로 제한하고 `runCatching` 안에서 처리합니다. |
| **D5** | FIXED | `ApplicationMessageDispatcher.kt:443-447,466-472` | 평문 `ok` 또는 JSON `ok=true`만 성공입니다. |
| **D6** | FIXED | `SidecarAgentClient.kt:58-71` | 인터럽트 플래그를 복원하고, `Exception`만 잡아 `Error`는 전파합니다. |
| **D7** | FIXED | `SlackUserProfileResolver.kt:36-61,74-96` | `putIfAbsent` 기반 in-flight 병합, `tryLock`으로 축출을 한 스레드만 수행합니다. |
| **A3** | FIXED | `RestClientRequester.kt:23-27,32-39` | readTimeout 6초. spring-web 7.0.9 `JdkClientHttpRequest$TimeoutHandler`가 타임아웃 시 본문 스트림을 닫습니다(javap). render당 호출은 1회입니다(`ModalTemplateBuilder.kt:121`). |
| **T7** | FIXED | `deployment.yaml:7-21` | `type: Recreate` + `rollingUpdate: null`(three-way merge에서 라이브 객체의 기본값 블록을 지움). `rollout undo`는 템플릿만 되돌리므로 Recreate가 유지됩니다. 롤아웃 타임아웃 420초(`deploy_action.yaml:45-47`) = grace 90 + startup 180 + readiness 10 + 여유 약 140초. job 20분은 420 + health 약 170 + 420 + 기타로 안에 듭니다. |
| **T8** | FIXED (문서) | `db/migration/AGENTS.md:62-79`, k8s README "One-time" | V18 → V19 → V20 → V22 → (Recreate) → V21. "번호 ≠ 적용 순서"와 readiness가 스키마 누락을 못 잡는다는 점을 명시했습니다. |
| **P3** | FIXED | `deploy_action.yaml:321-353` | 현재 리비전의 pod-template-hash로 거르고 `deletionTimestamp == null`인 파드만 봅니다. Recreate에서는 사실상 해당 없는 상황입니다. |
| **P4** | FIXED | `deploy_action.yaml:31-36` | 4장 참고: 기존 파이프에 새 실패 경로가 생기지 않았습니다. |
| **P5** | FIXED | `gradle-ci.properties:16-21` | `workers.max=2`로 힙 상한 3+3+8 = 14GB. 두 워크플로가 실제로 `apply.sh ci`를 씁니다(`simple_test_action.yaml:70`, `deploy_action.yaml:148`). |

---

### 2. 수정 브랜치가 새로 만든 결함

| ID | 심각도 | 제목 | file:line | 실패 시나리오 | 원인 커밋 | 확신도 | 수정 방향 |
|---|---|---|---|---|---|---|---|
| **F1** | **Medium** | 종료 대기 시간이 차례대로 더해져 grace 90초를 넘고, relay executor가 종료 중에도 큐의 claim을 새로 발송함 | `AsyncConfig.kt:38-40`, `deployment.yaml:30-31`, `application.yaml:4-9`, `ShutdownBudgetTest.kt:47-55,85-96` | 아래 별도 설명 | `4e18067`(relay 대기 30→60초, grace = 5+60+25 산식). `setWaitForTasksToCompleteOnShutdown(true)`는 기존 설정 | 구조: **High**(javap + 프로브). 빈도: Medium | 아래 별도 설명 |
| **F2** | Low | `freeDispatchSlots()`가 이미 만들어져 쉬고 있는 스레드를 "지금 받을 수 있는 자리"로 셈 → 스윕이 가득 채워 넣으면 거의 매번 1~4건 거절 | `SlackMessageRelayServiceImpl.kt:58-61`, `OutboxRecoveryScheduler.kt:57-63,79-85` | 정상 상태(relay 스레드 4개가 만들어져 유휴)에서 slots = 100 + 4 = 104를 보고합니다. 프로브: 104건을 연달아 제출하면 **200회 중 197회 거절 발생, 총 240건**. 원인은 core=max이고 스레드가 이미 있으면 새 작업이 큐로만 가기 때문입니다. 유휴 스레드는 큐를 비우는 속도로만 돕습니다. 장애 뒤 backlog가 104건 이상인 스윕마다 일부 claim이 300초 더 밀립니다(유실은 없음). 기존 테스트(`SlackMessageRelayServiceImplTest.kt:620-670`)는 아직 시작 안 된 스레드만 다뤄 이 경우를 못 봅니다 | `e07184f` | High(프로브) | `remainingCapacity + (maximumPoolSize - poolSize)`로 계산하거나, 거절되면 `reclaim`을 되돌리는 경로를 추가 |
| **F3** | Low | polling 모드에서 폴러와 스윕이 슬롯을 예약 없이 동시에 읽어 함께 과다 claim | `PollingMessageProcessor.kt:35-43`, `OutboxRecoveryScheduler.kt:57,79`, `application-slack-live.yaml:68`(polling) | 스케줄러가 4스레드가 되면서 5초 폴러와 60초 스윕이 병렬로 돕니다. 둘 다 104를 읽고 합쳐 최대 약 200건을 claim하면 AbortPolicy가 약 100건을 거절하고, 이미 claim된 PENDING 행이 300초 대기합니다. prod·dev·local은 CDC라 해당 없고 slack-live 프로파일에서만 생깁니다 | `e07184f` | Medium(논리상 확실, 실행 재현은 안 함) | relay 서비스에 원자적 `tryReserve(n)`(Semaphore)을 두어 claim 전에 자리를 예약 |
| **F4** | Low(잠복) | `freeDispatchSlots()`가 fail-open이고, 테스트가 이를 정답으로 고정 | `SlackMessageRelayServiceImpl.kt:58-61`(`as? ThreadPoolTaskExecutor … ?: Int.MAX_VALUE`), `SlackMessageRelayServiceImplTest.kt:674-679` | 데코레이터·프록시·타입 변경으로 캐스트가 실패하면 스윕이 stuck 100 + stale 100을 claim하고 약 96건이 거절돼 300초씩 밀립니다. 스모크 테스트(`SchedulingWiringSmokeTest.kt:57-64`)는 실제 빈으로 `freeDispatchSlots()`를 검사하지 않습니다 | `e07184f` | High(코드), 현재 발생 안 함 | 용량 인터페이스를 명시적으로 주입하고 기본값을 fail-closed(예: 0 또는 batchSize)로 |
| **F5** | Low | "최대 1회 게시" 약속과 OkHttp의 투명 재전송이 모순 | `ApplicationMessageDispatcher.kt:129,133-139`, 로그 `:321`, `impl/command/AGENTS.md` 결정표 | `buildOkHttpClient`는 `retryOnConnectionFailure`를 건드리지 않습니다(기본 true, javap). OkHttp 4.12 `RetryAndFollowUpInterceptor.recover/isRecoverable`는 `InterruptedIOException`이 아닌 IOException(reset, unexpected EOF)이면 본문 전송 뒤에도 다른 경로로 같은 Call을 다시 보냅니다. Slack이 처리를 마친 뒤 응답 전에 연결이 끊기면 중복 게시가 되고, 디스패처는 성공으로 봅니다. 동작 자체는 main 기존이지만, 브랜치가 문서와 로그에 "posted at most once"를 새로 약속했습니다 | `4bc8d3f`(문서·로그) | Medium | 잔여 위험으로 문서화하거나, 비멱등 클라이언트에 `retryOnConnectionFailure(false)` + 짧은 keep-alive. 후자는 stale 연결이 outcome_unknown(유실)으로 바뀌는 트레이드오프 |
| **F6** | Low | access_blocked 보류가 운영상 보이지 않음 | `OutboxHealthSnapshot.kt:22-23,35-38`, `MessageOutboxRepository.kt:156`, `SlackMessageRelayServiceImpl.kt:186-189` | 토큰 회수 중에는 모든 행이 15분마다 다시 시도되며 최대 24시간 보류됩니다. 하지만 defer가 `send_count`를 되돌리고 `updated_at`을 미래로 잡아 retrying·stuck 어느 쪽에도 걸리지 않습니다. 그래서 health와 `@bot status`는 계속 UP입니다. WARN 로그는 "Slack rate limit (Retry-After=900s)"로 찍힙니다. 시간이 중요한 메시지(리마인더, ephemeral)는 토큰을 고친 뒤 몇 시간 늦게 나갈 수 있습니다 | `bd79872` | High | 최근 N분 access_blocked 비율이나 마지막 발생 시각으로 health 기여자를 DOWN 처리하고, `defer`에 사유 인자를 넘겨 로그를 구분. 유효 기간이 짧은 detail type은 보류 상한을 따로 두는 것을 검토 |

**F1 실패 시나리오**
- 실제 정지 순서(phase 상수 javap): Kafka 컨테이너 `MAX-100` → 웹 graceful `MAX-1024` → executor `1073741823`. 이어서 파기 단계에서 relay `awaitTermination` 60초, `@Async` 10초.
- relay executor는 `waitForTasksToCompleteOnShutdown=true` 때문에 lateShutdown 상태가 됩니다(`ExecutorConfigurationSupport.onApplicationEvent` javap). 그래서 정지 단계 내내 큐에 있던 claim을 **새로 시작**하고, 대기는 파기 단계에서야 시작됩니다.
- 프로브 결과: Kafka 2초 + 스케줄 작업 대기 + relay 3초 → `close()` 5.21초. Kafka 단계 도중 큐의 task2가 시작됐고, 아직 실행 중인 채로 `close()`가 반환됐습니다.
- prod 계산: preStop 5 + Kafka ≤60(레코드 1건 약 46초) + relay ≤60 = **최대 125초 > grace 90** → SIGKILL. 진행 중이던 relay 발송(최대 4건)이 발송 뒤 상태를 기록하지 못하고, 새 파드의 스윕이 300초 뒤 재발송해 중복이 납니다.
- 실행 중인 `@Scheduled` 작업(CVE 등)이 있으면 executor 단계에서 최대 60초가 더해집니다.
- 브랜치 전에는 phase 10 + relay 30 + preStop 5 = 45 = grace였는데, 이번에 그 정합이 깨졌습니다.

**F1 수정 방향**
- (a) `ContextClosedEvent`에서 relay 큐를 비웁니다(`threadPoolExecutor.queue.clear()`). 큐에 있던 claim은 발송되지 않은 IN_PROGRESS로 남아 스윕이 중복 없이 회수합니다. 대기는 실행 중인 작업만 대상으로 합니다.
- (b) grace를 preStop + Kafka 단계 + relay 대기 + 여유(≥140초)로 올리거나 relay 대기를 줄이고, `ShutdownBudgetTest`가 **합계**를 검사하게 합니다.
- (c) grace를 바꾸면 롤아웃 타임아웃 420초도 다시 계산해야 합니다.

**미결 질문 (확신도 낮음, 판정에 반영하지 않음)**
- [Medium/Low 확신] relay executor 파기와 DataSource 파기 사이에 명시적 의존(`dependsOn`)이 없습니다.
  - 지금은 생성 순서상 relay가 먼저 파기될 가능성이 높습니다. DataSource가 `SlackMessageRelayServiceImpl` 첫 인자(`outboxRepository`)를 통해 먼저 만들어지기 때문입니다.
  - 그래도 `AsyncConfig.kt:39` 주석의 "before the DataSource closes"는 보장된 사실이 아닙니다.
- [미검증] MCP streamable HTTP의 긴 SSE 요청이 열려 있으면 웹 graceful 단계가 60초를 다 쓸 수 있습니다. 위 직렬 합에 더해집니다.

---

### 3. 문서 모순

| # | 문서 위치 | 서술 | 실제 |
|---|---|---|---|
| D1 | `application/src/main/resources/k8s/AGENTS.md:74-76`, `docs/wiki/dev-environment.md:206-207`, `deployment.yaml:30`, `AsyncConfig.kt:39`, `configurations/AGENTS.md` AsyncConfig 행 | grace 90 = preStop 5 + 단계 하나 60 + 여유 25. relay 대기 60초도 그 안에 든다 | 단계 대기와 relay 파기 대기는 차례대로 더해집니다(F1). 같은 위키 `events-and-outbox.md:228`은 "위에 쌓는다"고 써서 서로도 어긋납니다 |
| D2 | `.github/workflows/deploy_action.yaml:222` | "Rollout (300s) … rollback rollout (300s)" | `:47` 기준 420초 |
| D3 | `application/src/main/resources/k8s/AGENTS.md:146` | "rollout status (300s)" | 420초 |
| D4 | `application/src/main/resources/AGENTS.md:17` | prod "10s graceful shutdown" | prod의 10초 설정은 삭제됐고, `application.yaml:9`가 모든 프로파일에 60초를 줍니다 |
| D5 | `application/src/main/resources/AGENTS.md:55-61` | "profile lookup (3s connect + 10s read), about 53s … about 7s" | HTTP 45.64초(profile 6초). relay AGENTS가 "이 합계를 다른 곳에 복사하지 말라"고 한 바로 그 사본입니다(O3 잔여) |
| D6 | `ApplicationMessageDispatcher.kt:321`, `impl/command/AGENTS.md` 결정표 | "posted at most once" | OkHttp의 투명 재전송이 남아 있습니다(F5) |
| D7 | `MessageRelayService.kt:8`, relay `AGENTS.md`(`freeDispatchSlots` 서술), `events-and-outbox.md:149` | "지금 큐에 넣을 수 있는 수이므로 claim이 기다리는 일은 드물다" | 유휴 스레드를 과대 계산해 가득 채운 스윕에서 상시 거절이 납니다(F2) |
| D8 | `application/src/test/AGENTS.md:8`, `application/src/AGENTS.md:15` | "no Spring context" | 브랜치가 추가한 `SchedulingWiringSmokeTest`·`ShutdownBudgetTest`는 `ApplicationContextRunner`를 씁니다 |
| D9 | `application/.../service/AGENTS.md:29` | 폴러가 "recovers stuck IN_PROGRESS" | 회수는 `OutboxRecoveryScheduler`가 합니다. main 기존 드리프트(`90c747a`)로 브랜치가 만든 것은 아닙니다 |

---

### 4. 확인했고 문제 없던 것

- **`SlackMessageRelayServiceImpl` 병합 결과**: 세 워커의 수정이 충돌 없이 합쳐졌습니다.
  - 순서: eventId 파싱 → 24시간 만료 → schemaVersion → renew → render → dispatch → `when`(rate-limit → access_blocked → transient → else). 빠진 분기도 중복도 없습니다.
  - 거절 처리(`:69-76`)는 남은 claim을 모두 발송 없이 둡니다.
- **스윕의 `asSequence().mapNotNull{reclaim}.take(slots)`**: lazy라 slots를 넘는 reclaim UPDATE는 실행되지 않습니다. `take(0)`이면 빈 시퀀스이고, `slots - reclaimed.size`가 오버플로할 일도 없습니다.
- **`access_blocked`가 24시간을 넘지 않음**: defer 상한(`:172`), sweep의 `abandonStuck`(`updated_at < cutoff` 조건과 맞음), `dispatchClaimed` 만료 검사가 모두 맞물립니다. `oldestInFlightAgeSeconds`는 미래 `updated_at`에 대해 0으로 보정됩니다.
- **T1 smoke**: `ThreadPoolTaskSchedulerBuilder`는 가상 스레드 설정에서도 생성됩니다. 테스트에서 virtual=true로 부팅이 통과했습니다.
- **배포 pipefail**: 기존 파이프 4종에 새 실패 경로가 없습니다.
  - `:273` envsubst 실패는 이제 스텝 실패가 됩니다(의도된 변화).
  - `:285` `grep -c … || true`는 여전히 "0" 또는 개수를 남깁니다.
  - `:253`·`:427`의 `jq|sha256sum|cut`는 같은 입력을 쓰는 `:252`·`:426`의 `VAR=$(jq …)`가 errexit으로 먼저 멈추므로 실질 변화가 없습니다.
  - `:327-338`은 `if !` 안이라 pipefail 덕을 봅니다.
- **Recreate + `rollout undo`**: undo로 구 리비전 템플릿만 돌아오고 전략은 Recreate로 남아, 구·신 바이너리가 섞이지 않습니다. PDB는 Recreate 롤아웃에 적용되지 않습니다(README에도 명시).
- **O7**: DLT 실패 카운터가 동기 예외와 비동기 future 실패를 모두 셉니다. 템플릿이 없는 경우도 셉니다.
- **D4**: `defer`의 `LocalDateTime.plus`는 24시간 + 2분 안이라 오버플로가 없습니다.
- **RequestSendTracker 경계 사례**:
  - 본문을 일부만 보낸 뒤 타임아웃이면 `bodySent=false`로 재시도합니다. Slack은 잘린 본문을 처리하지 않습니다.
  - OkHttp가 같은 Call 안에서 재시도한 뒤 연결 단계에서 실패해도 첫 시도의 `bodySent=true`가 남아 outcome_unknown이 됩니다. 보수적 방향이라 맞습니다.
  - 트래커가 없는 클라이언트는 `mayHaveBeenSent=true`로 재발송하지 않는 쪽으로 실패합니다.
- **테스트 유효성**:
  - 디스패처의 타임아웃 테스트(`ApplicationMessageDispatcherTest.kt:440-492`)는 실제 루프백 서버와 실제 `slackClient`로 calls·attempts를 검증합니다.
  - relay의 outcome_unknown 테스트(`SlackMessageRelayServiceImplTest.kt:422-460`)는 `completeClaim(FAILURE)` 1회와 defer 0회를 검증합니다.
  - 결함을 정답으로 고정한 테스트는 F4(`:674-679`) 하나이고, 합계를 검사하지 않는 테스트는 `ShutdownBudgetTest`(F1)입니다.
