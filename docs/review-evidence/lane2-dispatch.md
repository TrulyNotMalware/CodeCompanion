# 3차 리뷰: Slack 발송 · 외부 HTTP 클라이언트 레인

범위 파일 테스트 59개를 `--offline`으로 좁혀서 실행했고 모두 통과했습니다(`ApplicationMessageDispatcherTest` 27, `SidecarAgentClientTest` 17, `SlackUserProfileResolverTest` 8, `RetryServiceTest` 7). SDK·Spring 동작은 캐시에 있는 jar를 `javap -c`로 열어 확인했습니다(`slack-api-client-1.51.0`, `spring-core-7.0.9`, `spring-web-7.0.9`). Slack 오류 코드 설명은 docs.slack.dev의 chat.postMessage 페이지에서 확인했습니다.

**결론:** 13.5가 반영했다고 한 S11·S13(response_url 부분)·S21·S19·H14는 해결됐습니다. A3의 "6초 타임아웃"은 `users.profile.get` 하나가 6초가 아니라서 부분 해결입니다. 새로 High 1건이 있습니다. cca9984가 넣은 6초 호출 타임아웃이 비멱등 POST를 그대로 재시도하는 구조와 만나 중복 게시가 생기고, 테스트 하나가 이 동작을 정답으로 고정하고 있습니다.

---

### 1. 13.5 판정표

| 항목 | 판정 | 근거 file:line | 설명 |
|---|---|---|---|
| **S11** 오류 코드 분류 | **RESOLVED** (D2 참고) | `ApplicationMessageDispatcher.kt:45`, `:344-349`, `:369-373`, `:157-159`, `:161-166` | `ok=false ratelimited`는 chat.*와 response_url 모두 `raiseIfRetryable`을 거쳐 `SlackRateLimitedException`이 됩니다(transient 재시도 아님). `fatal_error`·`request_timeout`은 영구 실패입니다. transient 집합은 `internal_error`·`service_unavailable`로 줄었습니다. `Retry-After`가 3초를 넘거나 없으면 대기 없이 `RateLimitedOutput`으로 넘깁니다. 대기 중 인터럽트가 오면 플래그를 복원하고 rate-limited로 반환합니다. 다만 `internal_error`에도 `fatal_error`와 같은 "일부 성공했을 수 있음" 경고가 붙어 있습니다(D2). |
| **A3(dispatch)** Retry-After 보존 보류 | **RESOLVED** | `ApplicationMessageDispatcher.kt:65-78`, `:175-181`, `:237-243`; `SlackMessageRelayServiceImpl.kt:91`, `:116-125` | 초 값과 HTTP-date를 모두 파싱합니다. 첫 호출과 인라인 대기 후 두 번째 호출 모두 해당 응답의 헤더를 씁니다. 릴레이는 `retryAfter ?: 60s` + 해시 분산으로 `deferClaim`합니다. `ok=false` 경로는 SDK가 헤더를 소문자 키로 넣어 주기 때문에(`toLowerCasedKeyMap`) `get("retry-after")`가 맞습니다. |
| **A3(dispatch)** "모든 Slack 호출에 6초 호출 타임아웃" | **PARTIAL** | 6초 적용: `ApplicationMessageDispatcher.kt:80-86`, `:88-93`, `:107-108`, `:248`. 미적용: `RestClientRequester.kt:23-24`, `:33-35`, `RestClientConfiguration.kt:12` | chat.*, views.open, response_url은 OkHttp `callTimeout` 6초가 실제로 걸립니다(javap로 확인: `Slack(SlackConfig)` → `buildSlackHttpClient` → `buildOkHttpClient`가 `callTimeout(...)` 호출). `users.profile.get`만 JDK 클라이언트 connect 3초 + read 10초입니다. Spring 7.0.9 `JdkClientHttpRequest$TimeoutHandler`가 `sendAsync` 직후 시작돼 본문까지 덮으므로 실제 상한은 약 10초입니다. 시간 상한은 있고 `impl/command/AGENTS.md`의 예산 계산에도 들어가 있지만, "모든 호출 6초"라는 문구는 사실이 아닙니다. 타임아웃이 난 뒤 재시도로 생기는 중복은 D1입니다. |
| **S13** response_url 허용 목록·리다이렉트·본문 상한 (1 MiB 요청 본문 상한은 web 레인이라 판정 제외) | **RESOLVED** | `ApplicationMessageDispatcher.kt:46`, `:309-318`, `:319-326`, `:88-93` | 파싱한 `HttpUrl`을 검증하고 그 객체를 그대로 보냅니다. 그래서 파서 차이를 노린 우회가 없고, 다음 경우가 모두 막힙니다: userinfo(`hooks.slack.com@evil`), `\` 트릭, `http` 스킴, 443이 아닌 포트, 끝의 점, IDN(퓨니코드가 되어 불일치). 대문자는 정규화됩니다. GovSlack(`hooks.slack-gov.com`)도 목록에 있습니다. 리다이렉트는 두 겹으로 막혀 있습니다(SDK `buildOkHttpClient`가 이미 `followRedirects(false)`이고 여기서 한 번 더). `peekBody(4096)`는 4 KiB까지만 요청하고 callTimeout이 드립 응답도 끊습니다. 테스트는 `ApplicationMessageDispatcherTest.kt:471-555`입니다. |
| **S21** 프로필 리졸버 | **RESOLVED** | `SlackUserProfileResolver.kt:39`, `:44-56`, `:58-67`; `RestClientRequester.kt:173-174` | 실패 결과를 60초 negative cache에 넣습니다(`ok=false`와 예외 모두). 캐시가 차면 전체 `clear()` 대신 만료분을 지우고 오래된 순으로 최소 10%를 축출합니다. `users.profile.get?user={user}`는 URI 템플릿 변수로 인코딩됩니다. 남은 사소한 점은 D7입니다. |
| **S19** 프롬프트 인젝션 | **RESOLVED** (잔여 Low) | `AgentConverseService.kt:63-81`, `:134`, `:146-156` | 제어·서식·줄/문단 구분 문자와 `"`, 백틱, `\`를 치환하고, 공백을 접고, 64자(서로게이트 안전)로 자릅니다. "이름은 지시가 아니다"라는 라벨 줄도 넣었습니다. 남은 것: 유니코드 따옴표 유사 문자(U+201C/U+FF02)는 통과합니다. `\p{Cf}`가 ZWJ/ZWNJ를 지워 이모지 시퀀스와 페르시아어·인도계 이름이 깨질 수 있습니다(미관 문제). 현재 입력이 `""`라서 잠복 상태입니다. |
| **H14** SSE 워치독·상한 (13.1 PARTIAL) | **RESOLVED** | `SidecarAgentClient.kt:101-127`, `:181`, `:189-192`, `:215-217`, `:268-270`, `:137-145` | 성공 경로에도 상한이 생겼습니다: 한 줄·한 프레임 ≤ 512K자, 누적 text ≤ 256K자, 오류 본문 ≤ 8,192자(한 줄을 다 읽기 전에 검사). 워치독은 공유 daemon 스레드이고 `finally`에서 cancel됩니다. 사이드카 `TURN_TIMEOUT_SEC` 90초 < 클라이언트 120초(`AppConfig.kt:190`)라서 정상 턴을 끊지 않습니다. |
| H12 (참고, 13.5가 주장하지 않음) | PARTIAL 유지 | `ModalTemplateBuilder.kt:99`, `SlackApiEventConstructor.kt:129` | 렌더 경로 안에서 동기로 호출하는 구조는 그대로입니다. 상한(약 10초)과 negative cache만 생겼습니다. 새 결함은 아닙니다. |

---

### 2. 신규 결함 표

| ID | 심각도 | 제목 | file:line | 구체적 실패 시나리오 | 도입 | 확신도 | 수정 방향 |
|---|---|---|---|---|---|---|---|
| **D1** | **High** | 6초 callTimeout이 비멱등 POST를 그대로 재시도해 중복 게시 | `ApplicationMessageDispatcher.kt:53-55`(`IOException`을 transient로), `:80-86`(callTimeout), `:122-124`(RetryService 3회), `:131`; `SlackMessageRelayServiceImpl.kt:92-93`; `AppConfig.kt:106`(`maxSends = 10`); 테스트 `ApplicationMessageDispatcherTest.kt:309-340`(`:35`에서 본문을 다 읽은 뒤 멈춤, `:337` `calls shouldBe 3`) | Slack이 느려서 chat.postMessage가 7초 만에 게시에 성공하는 상황입니다. 6초에 `InterruptedIOException("timeout")`이 나고, 요청은 이미 Slack에 도착해 처리됐습니다. RetryService가 같은 폼을 두 번 더 보내 최대 3건이 게시됩니다. `transient_exhausted`는 행을 IN_PROGRESS로 남기고, 300초 뒤 스윕이 다시 보냅니다(최대 10번). 이론상 한 메시지가 최대 3×10 = 30건 게시될 수 있습니다. `chat.postEphemeral`, response_url(원본 교체가 아닌 경우), 429 인라인 대기 뒤 두 번째 실행도 같습니다. main은 OkHttp 기본 read timeout 10초(바이트 사이 유휴 기준)라서 6~10초 응답은 성공했습니다. 테스트는 "서버가 완전한 POST를 3번 받음"을 정답으로 고정하고 있습니다. | cca9984(6초 callTimeout, `transient_exhausted` → 스윕 재발송). 호출 안의 IOException 재시도 자체는 main 기존 | MEDIUM(Slack이 클라이언트 연결이 끊긴 뒤에도 게시를 완료하는지는 실측하지 않음. 코드 경로는 확정) | OkHttp `EventListener`(`requestBodyEnd`)로 "전송 완료"를 기록해, 비멱등 메서드는 전송 뒤 타임아웃이면 재시도하지 않고 "결과 불명"으로 처리합니다(FAILURE, 또는 `metadata`에 idempotencyKey를 실어 `conversations.history`로 대조한 뒤 재발송). 연결 단계 실패(`ConnectException`·`UnknownHost`·TLS)만 재시도합니다. 테스트 `:337` 기대값도 뒤집어야 합니다. |
| **D2** | Medium | `internal_error`를 재시도하는데, Slack 문서상 `fatal_error`와 같은 "부분 성공" 경고가 붙어 있음 | `ApplicationMessageDispatcher.kt:45`, `:348`; 고정 테스트 `ApplicationMessageDispatcherTest.kt:456-469`(response_url `internal_error` → 재시도, `calls 2`) | docs.slack.dev chat.postMessage: `internal_error` — "…likely due to a transient issue on our end. **It's possible some aspect of the operation succeeded before the error was raised.**" 코드는 `fatal_error`를 "두 번 게시되면 안 된다"는 이유로 영구 실패로 두면서(테스트 `:233-246`), 같은 경고가 있는 `internal_error`는 3번 재시도하고 스윕으로 또 보냅니다. 기준이 서로 맞지 않고 중복 게시로 이어집니다. 13.3의 권고("transient를 internal_error/service_unavailable로 축소")를 그대로 따른 결과입니다. | 8504c07(transient 집합)에서 시작, cca9984가 유지. main은 `ok=false`를 재시도하지 않았음 | HIGH(문서 문구 확인) | 비멱등 메서드(postMessage/postEphemeral/response_url)에서는 `internal_error`를 영구 실패 또는 D1의 "결과 불명"으로 처리합니다. 재시도는 `service_unavailable`과 멱등인 `chat.update`만 허용합니다. |
| **D3** | Medium | 토큰·워크스페이스 전체에 걸리는 오류를 행 단위 영구 실패로 처리해 대량 유실 | `ApplicationMessageDispatcher.kt:344-349`(목록 밖은 전부 영구), `:374-377`; `SlackMessageRelayServiceImpl.kt:94` | 봇 토큰이 교체·폐기되거나 앱을 재설치하는 동안(`invalid_auth`, `token_revoked`, `account_inactive`, `not_authed`, `team_access_not_granted`, `missing_scope`) 들어오는 모든 outbox 행이 즉시 FAILURE가 되어 영구 유실됩니다. 흔적은 WARN 로그뿐이고, 설정을 고쳐도 되돌릴 수 없습니다. 같은 영구 분류 안에 `channel_not_found`·`is_archived` 같은 진짜 행 단위 오류가 섞여 있습니다. | main 기존(main도 `ok=false`면 실패 처리). 브랜치가 분류표를 새로 만들면서도 구분하지 않음 | MEDIUM | "시스템 오류" 부류를 따로 두고, 행은 보류(defer)하면서 헬스 DOWN/알림을 올립니다. 또는 dispatch 서킷 브레이커를 둡니다. |
| **D4** | Low | `Retry-After` 값에 상한이 없어 릴레이 `defer`에서 예외 가능 | `ApplicationMessageDispatcher.kt:239`; `SlackMessageRelayServiceImpl.kt:117-118`(`runCatching` 밖) | `Retry-After: 99999999999999999`(약 1e17초)면 `LocalDateTime.plus`가 `DateTimeException`을 던집니다. 이 예외가 `dispatchClaimed` 밖으로 나가면 CDC 경로에서는 `consume` 밖 → `DefaultErrorHandler` 재전달/DLT로 가고, 폴링 경로에서는 executor에서 잡히지 않은 예외가 됩니다. 행은 이미 renew돼 결국 스윕이 처리하므로 실제 영향은 소음 정도입니다. 발신원이 Slack/hooks 호스트로 고정돼 있어 가능성은 매우 낮습니다. | cca9984 | MEDIUM | `parseRetryAfter`에서 `giveUpAfter` 이하로 clamp하거나, `defer`의 계산 전체를 `runCatching` 안으로 옮깁니다. |
| **D5** | Low | response_url에서 2xx이고 JSON이 아닌 본문은 무엇이든 성공 처리(문서와 불일치) | `ApplicationMessageDispatcher.kt:338-339`, `:351-353`; `impl/command/AGENTS.md`("success is plain-text `ok` or JSON ok=true") | 200과 함께 `ok`가 아닌 평문(오류 문자열이나 중간 프록시 페이지)이 오면 SUCCESS로 기록됩니다. Slack이 오류를 보통 4xx로 돌려준다면 실제로는 거의 안 생깁니다. | cca9984 | LOW | 2xx 평문이면 `body.trim() == "ok"`일 때만 성공으로 보거나, 문서 문구를 코드에 맞게 고칩니다. |
| **D6** | Low | `SidecarAgentClient.converse`가 `InterruptedException`을 삼킴 | `SidecarAgentClient.kt:56-64` | 종료 중 async 스레드가 인터럽트되면 `httpClient.send`의 `InterruptedException`을 `runCatching`이 잡아 `transport_error`로 바꿉니다. 인터럽트 플래그가 사라지고, 사용자에게 FAILURE_MESSAGE outbox 행이 생깁니다. `Error`까지 잡습니다. | main 기존 | HIGH | `InterruptedException`은 플래그를 복원한 뒤 다시 던지거나 별도 결과로 돌리고, 나머지는 `Exception`만 잡습니다. |
| **D7** | Low | 프로필 리졸버가 동시 miss를 합치지 않고, 가득 찬 캐시의 정렬이 동시 실행됨 | `SlackUserProfileResolver.kt:33-41`, `:44-53`; `RestClientRequester.kt:181-183` | 같은 게시자의 승인 메시지를 여러 릴레이 워커가 동시에 렌더하면 `users.profile.get`이 N번 병렬로 나갑니다(각 최대 10초). 캐시가 상한일 때 동시 `store`마다 O(n log n) 정렬이 돕니다. 실패 한 번에 ERROR 스택트레이스와 WARN이 중복으로 찍힙니다. | cca9984(리졸버), 로그는 main 기존 | HIGH | `computeIfAbsent` 스타일로 in-flight Future를 공유합니다. 정렬 축출은 락이나 `tryLock` 하나로 묶습니다. 요청기 로그를 DEBUG로 내립니다. |

---

### 3. 확인했고 문제 없던 것

- **SDK 1.51.0 동작(javap).**
  - stats를 끄면 `postFormWithTokenAndParseResponse`가 `TeamIdCache`/`auth.test`를 건너뜁니다. 429 catch 블록의 `Long.valueOf(Retry-After)`는 `teamId != null`일 때만 실행되므로, HTTP-date 헤더가 `NumberFormatException`으로 새지 않습니다.
  - 2xx가 아닌 응답은 전부 `SlackApiException`입니다(`parseJsonResponseAndRunListeners:135-152`). 그래서 3xx·4xx는 영구, 5xx는 transient로 가는 매핑이 맞습니다(`:221-233`).
  - `Slack.getInstance(SlackConfig)`는 매번 새 인스턴스를 만들고 설정대로 HTTP 클라이언트를 구성합니다.
- **OkHttp callTimeout.** Okio 워치독이 호출을 취소하는 방식이라 스레드가 새지 않고, 본문 읽기까지 덮습니다. dispatcher의 sleeper는 인터럽트 플래그를 복원합니다(`:163-165`).
- **RetryService (Spring 7.0.9 바이트코드).**
  - 정책을 이루는 값 6개 전부 + 예외 Set을 키로 템플릿을 캐시하므로 스레드 사이 간섭이 없습니다.
  - 재시도 대상이 아닌 예외는 첫 시도 뒤 곧바로 `RetryException(cause = 그 예외)`로 끝납니다. 예외 이력은 suppressed에 쌓입니다.
  - backoff 중 인터럽트가 오면 Spring이 플래그를 복원하고, cause를 마지막 action 예외로 둡니다. dispatcher는 이를 `transient_exhausted`로 처리합니다.
  - `asRateLimited()`의 cause 체인 탐색은 "IOException → IOException → 429" 순서도 잡아냅니다.
- **실행당 시간 계산.** RetryService 1회 실행 ≤ 18.32초, `dispatch` 전체 ≤ 39.64초, 프로필 조회 실제 ≤ 10초(문서의 13초는 보수적 값).
- **Sidecar.** 오류 본문과 `done`에서 스트림을 닫아 연결이 새지 않습니다. JSON 파싱 오류는 `transport_error`, 타임아웃 뒤 예외는 `stream_timeout`으로 분류됩니다. 이 스트림 경로의 테스트는 통과합니다.
- **views.open.** `expired_trigger_id`/`trigger_expired`/`ratelimited`/예외 모두 대체 이벤트와 `failOutput`으로 끝나고 재시도하지 않습니다(trigger가 3초 뒤 만료되므로 맞는 처리).
- **GovSlack.** API 기본 URL이 `slack.com`으로 하드코딩돼 있어 GovSlack 배포는 어차피 불가능합니다. 허용 목록에 `hooks.slack-gov.com`이 있어도 해가 없습니다.

### 4. 미검증으로 남긴 것

- Slack이 callTimeout으로 클라이언트 연결이 끊긴 뒤에도 게시를 완료하는지, 실제 지연 분포(D1의 발생 빈도). 매우 그럴 것으로 보지만 실측하지 않았습니다.
- 실제 response_url의 오류 응답 형식(200+평문인지 4xx인지, D5).
- JDK `HttpResponseInputStream.close()`가 막혀 있는 reader를 IOException으로 깨우는지 EOF로 깨우는지. 테스트 `SidecarAgentClientTest.kt:369-397` 통과를 근거로 믿었고, JDK 소스는 보지 않았습니다. EOF라면 코드가 `stream_timeout` 대신 `incomplete_stream`이 됩니다.
- 레코드당 60초 예산 중 DB 쪽(claim/renew/complete 5회 재시도, Hikari 대기). outbox 레인 범위입니다.
- `RestClientRequesterTest`는 외부 네트워크가 필요해 실행하지 않았습니다.
- 범위 밖이지만 관찰한 것: `MeetingServiceImpl.kt:71`, `:87`은 BEFORE_COMMIT 리스너 안에서 모든 `Exception`을 같은 트랜잭션으로 3번 재시도합니다. Hibernate는 예외가 난 세션을 재사용하면 안 되는데 그 위에서 재시도하는 셈입니다(main 기존). meeting 레인에 넘깁니다.
