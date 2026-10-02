# 3차 리뷰 — 보안 · 웹 계층 · 도메인 검증 DSL 레인 보고

**범위**: `application/.../security/**`, `CommandRoleResolver`, `RoleManagementService`, `SlackInteractionHandlerImpl`, `SlackMentionEventHandlerImpl`, `McpToolGate`, `ControllerAdvice`, `SocketModeReceiver`, `application-*.yaml`, `run`, `domain/.../common/Validation.kt` 및 관련 테스트. 기준 커밋은 HEAD `cca9984`.

**방법**: 17:37에 빌드된 부트 jar(`application-alpha.jar`, HEAD와 같은 트리)를 스크래치에 풀었습니다. 그 클래스로 Java 프로브 두 개를 돌렸습니다.
- `scratchpad/lane4/probeA/ProbeA.java`: Validation `or` 동작과 타임스탬프 신선도 검사
- `scratchpad/lane4/probeC/ProbeC.java`: H2 + Hibernate 7.4 + `JpaTransactionManager`에 실제 `JpaUserCommandRoleRepository` → `UserCommandRoleRepositoryImpl` → `CommandRoleResolver`를 연결

경로 우회 21종, prod 기동 실패(fail-fast), 로거 결함은 메인 세션이 이미 확인했으므로 다시 보지 않았습니다. 서버 프로세스는 띄우지 않았고, 저장소 트리에는 파일을 만들지 않았습니다.

**종합 위험도: MEDIUM.** 역할 캐시 때문에 복제본 사이에서 권한 회수가 늦게 반영되고, 이 틈에 스스로 권한을 다시 부여할 수 있습니다(W1). S15의 "실패 시 USER 강등"은 트랜잭션 경로에서 실제로 동작하지 않습니다(W2, 실측).

---

### 1. 13.5 판정표

| 항목 | 판정 | 근거 file:line | 설명 |
|---|---|---|---|
| **A4** 시크릿 fail-fast | **RESOLVED** | `SlackRequestVerificationFilter.kt:33-38,155,159-167`, `application-slack-live.yaml:65`(기본값 제거), `application-dev.yaml:100`, `application-prod.yaml:124` | 미해결 플레이스홀더는 정규식으로 모든 프로파일에서 거부합니다. 공백 시크릿은 `activeProfiles == setOf("local")`일 때만 허용합니다. 우회 경로를 따져 봤습니다. `local,slack-live`는 slack-live의 `${SLACK_SIGNING_SECRET}`가 덮어써 플레이스홀더 거부로 실패합니다. `spring.profiles.include`는 activeProfiles에 합쳐지므로 실패합니다. 기본 프로파일(빈 집합)도 실패합니다. 필터는 조건 없는 `@Component`라 검증이 꺼지는 구성은 없습니다. prod는 메인 세션이 실측했습니다. 남은 문제는 W7(Low)과 메인 세션이 찾은 로거 결함입니다 |
| **A5** dedup 상태 머신 | **PARTIAL** | `SlackRetryDeduplicator.kt:74-125`, `SlackRequestVerificationFilter.kt:97-148` | JVM 하나 안에서는 맞게 동작합니다. 진행 중 재시도는 503, 완료 후 재시도는 200, Throwable/5xx는 기록을 지웁니다. 세대 토큰이 늦게 도착한 mark를 막고, 용량이 차면 `Untracked`로 처리합니다. 판정 근거는 `compute`의 원자성과 테스트 `SlackRetryDeduplicatorTest.kt:114-143`의 경합 케이스입니다. 복제본 간 공유(#34)는 미결로 남아 있습니다. 새로 찾은 것은 재생(replay)을 그대로 처리하는 문제(W4)입니다 |
| **A6** 필터 경로 | **RESOLVED** | `SlackRequestVerificationFilter.kt:40-41,172-200` | 메인 세션 실측과 일치합니다. 코드로도 확인했습니다. Spring MVC는 `RequestPath.parse(requestURI, contextPath)`의 `valueToMatch`(디코딩, matrix 제거) 경로로 라우팅합니다. 필터의 세 번째 뷰가 바로 이 경로에 정규화만 더한 것이라, Slack 컨트롤러로 가는 요청은 반드시 `/api/slack|/api/slash` 뷰를 갖게 됩니다 |
| **S12** `or`·errorMark·copy | **PARTIAL** | `Validation.kt:14-18,23,49-61` | `errorMark`가 사라졌고 `Field`는 더 이상 data class가 아닙니다. 따라서 `copy()` 리셋과 다른 필드 오류를 지우는 C8 회귀는 해결됐습니다(테스트 `ValidationBuilderTest.kt:62-151`). 그러나 중첩 스코프(`and{}`·`ifNotNull{}`·`shouldNotBeNullAnd{}`) 안의 `or`가 **같은 필드의 바깥쪽 오류까지 지웁니다**(W3, 실측). "자기 피연산자 오류만 제거"라는 주장은 틀렸습니다 |
| **S13** 본문 상한 | **RESOLVED** | `CachedBodyHttpServletRequest.kt:80-85`, `SlackRequestVerificationFilter.kt:70-76` | `contentLengthLong` 사전 검사, chunked 요청의 `readNBytes(MAX+1)`, 서명 검증 전 413 모두 들어가 있습니다. 한계로, 헤더 사전 검사는 누구나 통과할 수 있어 인증 없이도 1 MiB 버퍼링을 일으킬 수 있습니다. `security/AGENTS.md:53-54`의 서술도 틀렸습니다(W5, Low) |
| **S14** actuator | **RESOLVED**(local 잔존은 의도) | `application-dev.yaml:70,75`, `application-prod.yaml:92,100,102`, `run:366,381,403-410`, `route/httpRoute.yaml:14-21`, `route/ingress.yaml:19-33` | dev는 `health,info,metrics` + `when_authorized`로 바뀌었습니다. `run`의 `include=*`는 사라졌고 JMX·JDWP는 127.0.0.1에 바인드합니다. 샘플 라우트는 `/api/slack`·`/api/slash`로 좁혔습니다. local은 여전히 `loggers,threaddump,mappings,conditions` + `show-details: always`로 모든 인터페이스에 열려 있습니다(`application-local.yaml:49-53,65,71`). 주석으로 문서화된 의도입니다 |
| **S15** 역할 캐시·실패 처리 | **PARTIAL** | `CommandRoleResolver.kt:36-74`, `RoleManagementService.kt:97-107` | 60초 캐시, 커밋 후 축출, 세대 가드는 구현됐습니다. 그러나 (a) 트랜잭션 안에서 조회가 실패하면 USER 강등이 효과가 없습니다. 트랜잭션이 rollback-only로 표시돼 커밋 때 `UnexpectedRollbackException`이 나고 500이 됩니다(W2, 실측). (b) 다른 복제본에서는 강등 뒤 최대 60초 동안 ADMIN이 유지되고, 그 사이 스스로 권한을 다시 부여할 수 있습니다(W1) |

---

### 2. 신규 결함 표

| ID | 심각도 | 제목 | file:line | 공격/실패 시나리오 | 도입 | 확신도 | 수정 방향 |
|---|---|---|---|---|---|---|---|
| **W1** | **Medium~High**(인가 약화, 내부 관리자가 전제) | 역할 캐시가 복제본 사이에서 회수를 늦게 반영해, 회수된 ADMIN이 **스스로 재부여**할 수 있음 | `CommandRoleResolver.kt:22,36-50`, `RoleManagementService.kt:67-79,97-107`, `SlackMentionEventHandlerImpl.kt:55-61`, `CommandSet.kt:14-16`, `AppMentionContextParser.kt:73`, `McpToolGate.kt:53-54`; 결정 위반 `docs/wiki/decisions.md:59-60`(#15 "대화 중 revoke 즉시 반영") | 1) 관리자 B가 `@bot revoke @A`를 보내고 파드 1이 처리합니다. 파드 1은 커밋 후 축출합니다. 파드 2의 캐시에는 A=ADMIN이 남아 있습니다. A는 60초마다 아무 멘션이나 보내 캐시를 데워 둘 수 있습니다. 2) 봇이 채널에 "Revoked…"를 게시하므로 A는 회수 사실을 바로 압니다. 3) A가 60초 안에 `@bot grant @A admin`을 몇 번 보내면, 파드 2에 도달한 요청은 캐시된 ADMIN으로 `ADMINISTRATION` 게이트를 통과합니다. `saveRole`로 **영구 재부여**됩니다. 캐시가 없던 이전에는 매번 DB를 조회해 회수가 즉시 반영됐습니다. MCP 게이트도 같은 캐시를 씁니다. `service/command/AGENTS.md:34-37`은 60초 지연만 적었고, 이 재부여 연쇄는 분석하지 않았습니다 | cca9984 | 높음(코드 추적; 복제본 2개는 `deployment.yaml` 기준) | `ADMINISTRATION`/`OPERATIONS` 권한이 필요한 명령과 MCP 호출은 캐시를 우회해 DB에서 조회. 또는 USER(행 없음)만 캐시. 또는 공유 무효화(`updated_at` 버전 비교). 결정 #15와 AGENTS 서술을 맞출 것 |
| **W2** | **Medium** | S15의 "조회 실패 시 USER 강등"이 트랜잭션 경로에서 무효: rollback-only 오염 뒤 `UnexpectedRollbackException`으로 500 | `CommandRoleResolver.kt:42-47`; 호출 위치 `SlackInteractionHandlerImpl.kt:53-56,61-76,141`, `SlackMentionEventHandlerImpl.kt:34-38,60,63-68` | **실측(ProbeC)**: 테이블 이름을 바꿔 조회 실패를 일으키면 트랜잭션 안에서 `resolve`는 `USER`를 돌려주지만 `rollbackOnly=true`이고, 커밋에서 `UnexpectedRollbackException: Transaction silently rolled back…`이 납니다. Hibernate가 JPA 스펙에 따라 PersistenceException이 나면 트랜잭션을 롤백 전용으로 표시하기 때문입니다. 트랜잭션 밖(MCP)에서만 USER가 정상 반환됩니다. DB가 완전히 죽으면 트랜잭션 시작 시점에 이미 실패하므로, 이 fallback이 인터랙션/멘션 경로를 살리는 경우는 사실상 없습니다. 그 사이 명령은 USER로 실행되고, 트랜잭션 안의 동기 리스너(`SlackInteractionHandlerImpl.kt:100`)가 실행된 뒤 롤백되며, "falling back to USER" WARN은 사실과 다른 로그가 됩니다. `CommandRoleResolverTest.kt:128-143`은 트랜잭션 없는 mockk로 이 문제를 가립니다 | cca9984(주장한 수정이 효과 없음) | 높음(실측) | 역할 조회를 인터랙션/멘션 트랜잭션 밖(시작 전)이나 `PROPAGATION_NOT_SUPPORTED`/`REQUIRES_NEW` 읽기로 분리. H2 + `JpaTransactionManager` 실트랜잭션 회귀 테스트 추가 |
| **W3** | **Low**(잠복: main 코드에서 `or`·`and{}`·`ifNotNull`·`shouldNotBeNullAnd` 사용 0건) | 중첩 스코프 안의 `or`가 같은 필드의 **바깥 오류를 삭제**해 `p1 AND (p2 OR p3)`가 참이 됨 | `Validation.kt:23`(`nested`가 부모 `raisedErrors` 공유), `:49-52`(`and`가 `this`를 넘김), `:54-61`(`leftErrors = raisedErrors.toList()`) | **실측(ProbeA)**, x=3: `shouldSatisfy{짝수} and { it shouldBeGreaterThan 100 or { it shouldBeGreaterThan 0 } }` → 기대 `[x:짝수 위반]`, 실제 `[]`. `ifNotNull { >0 or <100 }`(양쪽 통과)과 `shouldNotBeNullAnd { >100 or >0 }`도 모두 `[]`. 오른쪽이 통과하면 스코프 진입 전 같은 필드의 오류까지 지워집니다. 대조군(최상위 `(짝수) or >0` → `[]`)은 문서화된 의미대로입니다. 테스트 `ValidationBuilderTest.kt:62-88`은 **다른 필드**(`earlier`)만 확인해 이 경우를 놓칩니다 | cca9984 | 높음(실측) | 중첩 블록과 `and`에 스코프별 자식 `raisedErrors`를 주고, 블록이 끝나면 부모에 병합. `or`의 왼쪽은 "현재 스코프에서 쌓인 오류"로 한정. 위 3개 케이스를 회귀 테스트로 추가 |
| **W4** | Low | dedup이 **재생(replay)을 그대로 처리**: 같은 본문에 `X-Slack-Retry-Num`이 없으면 `Untracked`로 다시 처리 | `SlackRetryDeduplicator.kt:92-95`; 결함 동작을 고정한 테스트 `SlackRetryDeduplicatorTest.kt:91-101` | Slack은 같은 본문을 재시도 헤더 없이 다시 보내지 않습니다(event_id가 고유). 따라서 TTL 안에 그런 요청이 오면 재생뿐입니다. 서명 요청을 캡처한 공격자(로그 유출이나 TLS 종단 이후 구간)는 타임스탬프 허용 창 300초 안에 헤더를 빼고 다시 보내 AI 턴이나 명령을 중복 실행할 수 있습니다. 서명이 없는 재시도 헤더를 붙이면 오히려 dedup에 걸리는 역설도 있습니다 | cca9984(설계) | 중간 | `retryNum == null && existing(만료 전)`이면 200 no-op(또는 409)으로 처리하고 해당 테스트 기대값을 반전 |
| **W5** | Low | "값싼 선거절(Cheap rejection first)"은 위조할 수 있음: 인증 없이 요청당 1 MiB 버퍼링을 강제 가능, 문서 서술도 거짓 | `SlackRequestVerificationFilter.kt:58-76`, `security/AGENTS.md:53-54` | `checkHeaders`는 현재 시각과 형식만 맞는 `v0=`+hex 64자면 통과합니다(공개된 형식). 공격자는 매 요청으로 1 MiB를 읽게 할 수 있습니다(`readNBytes` 특성상 순간 최대 약 2배). prod는 가상 스레드이고 Jetty `VirtualThreadPool()` 기본 maxTasks는 200(바이트코드 확인)입니다. 동시 200개면 순간 수백 MiB 규모로, 힙 1 GiB 대비 GC 압박을 줍니다. AGENTS의 "unsigned flood costs no buffering"은 스캐너에만 맞는 말입니다 | cca9984(문서) / 구조 기존 | 중간(수치 추정) | 문서 정정. 게이트웨이 속도 제한이나 동시성 상한 명시. 필요하면 상한을 Slack 문서 근거로 재산정 |
| **W6** | Low(심층 방어) | 타임스탬프 신선도 검사의 정수 오버플로 | `SlackSignatureVerifier.kt:54-60`(`:58` `abs(now - ts)`) | **실측**: `ts = Long.MIN_VALUE + now`면 `now - ts`가 `Long.MIN_VALUE`로 넘치고 `abs`도 음수라 `> 300`이 거짓이 됩니다. 결과는 `valid=true`(약 2,920억 년 전 시각이 "신선"). HMAC이 여전히 필요해 인증 우회는 아니지만, 신선도 검사와 선거절이 무력화됩니다 | main 기존(cca9984가 옮김) | 높음(실측) | `timestamp !in (now - tol)..(now + tol)` 범위 비교나 `Math.subtractExact`로 교체. 테스트 추가 |
| **W7** | Low | `local` 단독이면 공백 시크릿으로 검증을 끈 채 모든 인터페이스에서 기동, `run` 기본값도 `local` | `SlackRequestVerificationFilter.kt:163`, `application-local.yaml:49-53`(`server.address` 없음), `:94`(`${SLACK_SIGNING_SECRET:}`), `run:94`(`ENVIRONMENT="local"`) | 운영자가 서버에서 `./run app.jar`를 `-e` 없이 실행하면 local 프로파일로 뜨고 서명 검증이 꺼집니다(WARN 1회). 그 상태로 `:9000`의 `/api/slack/*`가 네트워크에 열리고, 위조 페이로드로 부트스트랩 관리자 ID를 사칭할 수 있습니다. 같은 포트에 쓰기 가능한 `loggers` actuator도 열립니다 | main 기존(A4 설계가 유지) | 중간 | local에 `server.address: 127.0.0.1`. 또는 명시적 옵트인(`slack.app.api.verification-disabled=true`)을 요구. `run` 기본 환경을 필수 인자로 |

---

### 3. 확인했고 문제 없던 것

- **쿼리 파라미터 오염**: 래퍼가 `getParameter`/`Values`/`Map`/`Names`를 재정의하고 `getQueryString()`은 null을 돌려줍니다. 컨트롤러는 모두 `@RequestParam`으로 이 래퍼만 읽습니다. `RequestContextHolder`나 원본 `getParameter`를 쓰는 곳은 없습니다(grep 0건). 모든 핸들러가 `@RequestHeader headers`를 무시합니다(`SlackRequestParser.kt:11-17`, 멘션·인터랙션 핸들러). 재생 요청의 Content-Type을 multipart로 바꿔도 본문이 이미 소비돼 `getParts`가 빈 결과라 악용할 수 없습니다.
- **폼 파싱과 서명 본문 일치**: 같은 바이트를 씁니다. `URLDecoder`는 `+`를 공백으로, `%XX`는 서블릿과 같게 해석합니다. 문자셋은 Boot `CharacterEncodingFilter`(HIGHEST_PRECEDENCE, 이 필터보다 먼저 실행)가 UTF-8로 고정합니다. 파라미터 맵은 lazy라 서명 검증 전에는 파싱되지 않습니다. 잘못된 `%` 인코딩은 서명된 요청에서만 500이 됩니다.
- **본문 재호출**: `getInputStream`은 매번 새 스트림, `getReader`도 가능, `getContentLength`는 실제 크기를 돌려줍니다.
- **서명**: `MessageDigest.isEqual`은 고정 길이(정규식이 67자를 강제)에서 상수 시간입니다. `v0=`과 소문자 hex를 강제합니다. 복수 헤더면 첫 값만 씁니다(Slack은 하나만 보냄). 실측으로 `" ts"`·`0x10`은 INVALID, `now-301`·`Long.MIN_VALUE`는 EXPIRED였습니다. `+ts`는 파싱되지만 HMAC이 원문 문자열을 덮으므로 무해합니다.
- **dedup 동시성**: 판정은 `compute` 안에서 원자적입니다. 두 재시도가 경합하면 하나만 FirstAttempt, 나머지는 503입니다. 원본이 실패하면 기록이 지워지고 다음 재시도가 FirstAttempt로 처리됩니다. 만료된 세대의 mark는 무시됩니다. trim은 COMPLETED만 지웁니다. 엔트리는 서명 검증 뒤에만 생기므로 인증 없이 용량을 고갈시킬 수 없습니다.
- **역할 캐시(같은 복제본 안)**: 세대 가드가 "옛 값을 읽은 조회의 재캐시"를 막습니다. REPEATABLE_READ에서도 인터랙션·멘션 트랜잭션의 첫 DB 읽기가 `findRole`이라 스냅샷이 최신입니다. 실패는 캐시하지 않고, 부트스트랩 관리자는 캐시를 우회합니다.
- **Validation**: 최상위 진리표 4가지는 정확합니다(ProbeA 대조군). 다른 필드 오류는 보존되고, identity 기반 삭제라 값이 같은 오류끼리 섞이지 않습니다.
- **Socket Mode**: `@Profile("local")` 전용이고 app-level 토큰으로 WSS에 연결합니다. 인바운드 HTTP가 없어 필터 우회 문제가 아닙니다. **MCP**: 컨테이너가 매칭하는 `FilterRegistrationBean` + HMAC 턴 토큰이고, `/mcp`는 샘플 라우트에 없습니다.
- **오류 디스패치/비동기**: 컨트롤러는 동기이고 `forward:`·`RequestDispatcher`가 없어 OncePerRequestFilter의 ERROR/ASYNC 건너뛰기가 안전합니다.

### 4. 미검증으로 남긴 것

- **커밋 뒤 실패로 인한 중복 처리**: events 경로에서 핸들러 트랜잭션이 커밋된 뒤 응답 쓰기가 실패하면(Slack의 3초 타임아웃 뒤 연결 종료 → flush 시 `EofException`, 또는 `CommandOutput` 직렬화 실패) `ControllerAdvice.kt:38-44`가 500을 돌려주고, 이어서 `markFailed`가 호출돼 기록이 지워집니다. 그러면 Slack 재시도가 이미 커밋된 이벤트를 다시 처리합니다. Jetty와 Envoy가 반쯤 닫힌 연결에서 첫 쓰기를 실패시키는지는 측정하지 못했습니다.
- Jetty `VirtualThreadPool(200)`의 maxTasks가 실제로 동시성을 제한하는지(W5의 메모리 추정 근거).
- W2는 H2로 실측했습니다. Hibernate의 rollback-only 표시는 DB와 무관한 JPA 스펙 동작이지만, MariaDB 실환경에서는 재현하지 않았습니다.
- `McpTurnTokenFilter`의 루프백 판정이 Istio 사이드카 주입 환경에서 약해지는지(인바운드 출발지가 127.0.0.6이라 루프백으로 보임). 토큰 검증이 따로 있어 영향은 제한적입니다.
- k8s Secret 값 끝에 개행이 붙은 경우: `isBlank`는 통과하므로 기동은 되지만 모든 서명이 401이 되는 운영 장애.
- Kafka 모드에서 `RoleManageRequestEvent`를 어느 복제본이 소비·축출하는지(W1의 창이 어느 파드에 생기는지).
