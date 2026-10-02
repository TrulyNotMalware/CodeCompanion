> 이 파일은 `omc ask codex` 산출물을 저장소에 옮기면서 줄인 사본입니다. 중복된 `Final prompt` 절, 반복된
> user 프롬프트, MCP 전송 오류 줄을 뺐고, 각 `exec` 블록은 명령·종료 상태와 출력 앞부분
> (12줄)만 남겼습니다. 리뷰어의 서술(`codex` 블록)과 최종 보고서는 원문 그대로입니다.
> 절대 경로는 저장소 기준 상대 경로로 바꿨습니다.

# codex advisor artifact

- Provider: codex
- Exit code: 0
- Created at: 2026-09-22T01:17:46.886Z

## Original task

Final independent senior-engineer code review of this repository (CodeCompanion), a Kotlin 2.4.10 / Spring Boot 4.1.1 / Java 25 Gradle multi-module Slack bot on branch main @ 90c747a. Modules: domain (framework-free core) <- infrastructure (Slack SDK, JPA, Kafka, AI sidecar adapters) <- application (Spring bootstrap, controllers, use-case services). Transactional outbox + Debezium CDC + Kafka relays messages to Slack; scheduled jobs drive meeting reminders / standup / CVE feeds; an MCP server is exposed on /mcp.

IMPORTANT - budget your effort. Do NOT read the whole repo. Read at most ~25 files, chosen from the list below, and produce the report. Prioritise producing a finished report over exhaustive reading.

Review these specific files and report only defects you can anchor to a line you actually read:
1. application/src/main/kotlin/dev/notypie/application/service/relay/PollingMessageProcessor.kt
2. application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt
3. application/src/main/kotlin/dev/notypie/application/service/relay/SlackMessageRelayServiceImpl.kt
4. infrastructure/src/main/kotlin/dev/notypie/repository/outbox/MessageOutboxRepository.kt
5. infrastructure/src/main/kotlin/dev/notypie/repository/outbox/schema/OutboxMessage.kt
6. infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt
7. infrastructure/src/main/kotlin/dev/notypie/impl/command/ApplicationMessageDispatcher.kt
8. infrastructure/src/main/kotlin/dev/notypie/impl/command/RestClientRequester.kt
9. infrastructure/src/main/kotlin/dev/notypie/impl/agent/SidecarAgentClient.kt
10. infrastructure/src/main/kotlin/dev/notypie/repository/meeting/JpaMeetingRepository.kt
11. infrastructure/src/main/kotlin/dev/notypie/repository/meeting/MeetingRepositoryImpl.kt
12. infrastructure/src/main/kotlin/dev/notypie/exception/KafkaErrorBroadcaster.kt
13. application/src/main/kotlin/dev/notypie/application/configurations/ConsumerConfig.kt
14. application/src/main/kotlin/dev/notypie/application/security/SlackSignatureVerifier.kt
15. application/src/main/kotlin/dev/notypie/application/security/SlackRequestVerificationFilter.kt
16. application/src/main/kotlin/dev/notypie/application/security/SlackRetryDeduplicator.kt
17. application/src/main/kotlin/dev/notypie/application/controllers/SlashCommandController.kt
18. application/src/main/kotlin/dev/notypie/application/controllers/SlackEventController.kt
19. application/src/main/kotlin/dev/notypie/application/exception/ControllerAdvice.kt
20. application/src/main/kotlin/dev/notypie/application/service/mention/SlackMentionEventHandlerImpl.kt
21. domain/src/main/kotlin/dev/notypie/domain/meet/entity/Meeting.kt
22. domain/src/main/kotlin/dev/notypie/domain/common/Utils.kt
23. application/src/main/resources/application-prod.yaml
24. application/src/main/resources/application-dev.yaml
25. .github/workflows/simple_test_action.yaml and .github/workflows/lint.yaml and .github/workflows/deploy_action.yaml

Look specifically for: outbox claim/dispatch races and duplicate or lost delivery; Kafka consumer offset semantics vs the yaml; shared mutable state across threads; transaction boundaries and external I/O inside transactions; missing DB indexes on hot query paths; JOIN FETCH that truncates collections; Slack signature verification and replay handling; the Slack 3-second ACK contract; TODO()/NotImplementedError or empty catch blocks or empty exception handlers in production paths; and whether tests and lint actually gate merges into main.

Output: a ranked list of findings. For EACH: severity (Critical/High/Medium/Low), file:line, what is wrong, the concrete failure scenario, and a specific fix. Then a short section listing what you checked and found CORRECT. If something looks like a defect but is actually fine, say so and explain why. Write the report in Korean; English technical terms are fine.


```text
검토 기준은 **main @ `90c747a`**입니다. 지정 목록에서 **25개 파일**을 읽었습니다. `Meeting.kt`, `Utils.kt`는 제외했으며, 코드 수정·빌드·테스트 실행은 하지 않았습니다. 기존 미추적 파일 `review.md`도 열거나 변경하지 않았습니다.

확인한 범위에서 Critical은 없으며, 아래 High 항목은 배포 전에 수정하는 편이 좋겠습니다.

1. **High — claim 결과의 개수만으로 처리 대상을 선택하여 중복 dispatch와 누락 발생**

   **위치:** [PollingMessageProcessor.kt:38](application/src/main/kotlin/dev/notypie/application/service/relay/PollingMessageProcessor.kt:38), [MessageOutboxRepository.kt:33](infrastructure/src/main/kotlin/dev/notypie/repository/outbox/MessageOutboxRepository.kt:33)

   `claimPending()`은 변경된 행 **개수**만 반환하는데, 호출자는 `candidates.take(claimedCount)`를 자신이 claim한 행으로 간주합니다. 후보가 `[A, B]`이고 다른 인스턴스가 A를 먼저 claim하면, 현재 인스턴스는 B를 claim하고도 A를 dispatch합니다. A는 중복 처리되고 B는 복구 시점까지 방치됩니다.

   **수정:** 짧은 트랜잭션에서 `SELECT … FOR UPDATE SKIP LOCKED`로 선택·갱신하고 정확한 행 목록을 반환하거나, 고유 claim token을 저장한 뒤 해당 token의 행만 dispatch하십시오.

2. **High — stuck 메시지 복구에 소유권 획득과 lease 갱신이 없음**

   **위치:** [PollingMessageProcessor.kt:25](application/src/main/kotlin/dev/notypie/application/service/relay/PollingMessageProcessor.kt:25), [SlackMessageRelayServiceImpl.kt:35](application/src/main/kotlin/dev/notypie/application/service/relay/SlackMessageRelayServiceImpl.kt:35)

   오래된 `IN_PROGRESS` 행을 조회한 뒤 곧바로 비동기 dispatch합니다. 복구 작업이 5초 이상 걸리면 다음 tick이 같은 행을 다시 제출하며, 여러 인스턴스도 동시에 같은 행을 복구할 수 있습니다. 정상 전송이 느린 경우에도 원래 작업과 복구 작업이 겹칩니다.

   **수정:** 만료된 lease를 조건부 UPDATE로 획득하고 owner/token과 만료 시각을 갱신하십시오. 완료 처리도 해당 token 소유자만 수행하게 하고, 장시간 작업에는 lease 연장을 적용하십시오.

3. **High — CDC 재전달 시 현재 DB 상태를 확인하지 않고 다시 전송**

   **위치:** [DebeziumLogTailingProcessor.kt:41](application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt:41), [DebeziumLogTailingProcessor.kt:55](application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt:55)

   검사하는 상태는 CDC 레코드에 담긴 과거의 `PENDING`입니다. Slack 전송과 DB 상태 갱신까지 성공한 뒤 offset commit 전에 프로세스가 종료되면, 재전달된 원본 레코드로 다시 dispatch합니다. 이미 DB에 성공이 기록된 경우조차 건너뛰지 못합니다.

   **수정:** `eventId`로 현재 상태를 확인하고 원자적으로 claim한 경우에만 전송하십시오. Slack 전송 성공과 DB 기록 사이의 crash window는 별도로 남으므로, 지원되는 전송에는 안정적인 중복 방지 키도 적용해야 합니다.

4. **High — 실패한 Slack 요청의 정상 재시도를 성공 ACK로 폐기**

   **위치:** [SlackRetryDeduplicator.kt:28](application/src/main/kotlin/dev/notypie/application/security/SlackRetryDeduplicator.kt:28), [SlackRequestVerificationFilter.kt:66](application/src/main/kotlin/dev/notypie/application/security/SlackRequestVerificationFilter.kt:66)

   fingerprint를 실제 처리 **전에** 저장하고, 처리 실패 시 제거하지 않습니다. 최초 요청이 DB 오류로 실패한 뒤 동일 fingerprint의 Slack retry가 도착하면, controller를 호출하지 않고 HTTP 200을 반환합니다. 요청은 처리되지 않았는데 Slack에는 성공으로 보입니다.

   **수정:** durable inbox에 요청을 저장한 뒤 ACK하고 처리 상태를 관리하십시오. 최소한 임시 claim과 완료 상태를 구분하고, 실패한 claim은 재처리할 수 있어야 합니다. 다중 인스턴스에서는 공유 저장소가 필요합니다.

5. **High — app mention ACK가 명령 실행과 트랜잭션 완료를 기다림**

   **위치:** [SlackEventController.kt:43](application/src/main/kotlin/dev/notypie/application/controllers/SlackEventController.kt:43), [SlackMentionEventHandlerImpl.kt:33](application/src/main/kotlin/dev/notypie/application/service/mention/SlackMentionEventHandlerImpl.kt:33), [SlackMentionEventHandlerImpl.kt:65](application/src/main/kotlin/dev/notypie/application/service/mention/SlackMentionEventHandlerImpl.kt:65)

   controller가 트랜잭션을 포함한 `handleEvent()` 반환 후 ACK합니다. DB lock이나 commit 지연으로 3초를 넘으면 실제 처리가 진행 중이어도 Slack은 실패로 판단하고 재시도합니다. 이는 [Slack의 Events API ACK 계약](https://docs.slack.dev/apis/events-api/)과 충돌합니다.

   **수정:** 검증된 이벤트를 짧은 inbox 트랜잭션으로 수락한 뒤 즉시 ACK하고, 명령 실행은 worker로 이동하십시오. 읽지 않은 하위 구현을 근거로 AI 호출까지 동기라고 단정하지는 않습니다.

6. **High — CDC 파싱 실패를 정상 반환하여 복구 가능한 레코드까지 소비 처리**

   **위치:** [DebeziumLogTailingProcessor.kt:37](application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt:37), [DebeziumLogTailingProcessor.kt:44](application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt:44)

   변환 실패와 잘못된 UUID를 로그만 남기고 `return`합니다. 예를 들어 배포 버전과 CDC 스키마가 일시적으로 맞지 않으면 해당 레코드는 listener 관점에서 성공 처리됩니다. 이후 offset이 전진하면 코드를 수정해도 자동 재처리되지 않습니다.

   **수정:** 재시도 가능한 오류는 전파하고, 영구적인 poison record는 원본·오류를 durable DLT에 저장한 뒤 소비를 완료하십시오. 단순 로그를 유일한 복구 수단으로 두지 않아야 합니다.

7. **High — 지정 CI가 main 병합 결과에 대한 테스트·lint gate를 제공하지 않음**

   **위치:** [simple_test_action.yaml:2](.github/workflows/simple_test_action.yaml:2), [lint.yaml:2](.github/workflows/lint.yaml:2), [deploy_action.yaml:98](.github/workflows/deploy_action.yaml:98), [deploy_action.yaml:129](.github/workflows/deploy_action.yaml:129)

   테스트와 lint는 특정 이름의 branch `push`에서만 실행됩니다. `fix/*` PR이나 main과의 병합 결과는 이 두 workflow의 검사 대상이 아닙니다. 배포는 merge 후 실행되며 테스트를 제외하고, deployment 생성 시 required contexts도 비워 둡니다.

   **수정:** main 대상 `pull_request`에서 검사하고, merge queue 사용 시 `merge_group`도 추가하십시오. 해당 결과를 required check로 설정하고, 배포할 SHA가 검증된 SHA인지 보장해야 합니다. **GitHub branch protection 설정 자체는 확인하지 않았으므로 실제 merge 허용 여부까지 단정하지 않습니다.**

8. **Medium — 공유 RetryTemplate의 정책을 요청마다 변경하는 경쟁 조건**

   **위치:** [RetryService.kt:33](infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt:33)

   `retryPolicy` 설정과 `execute()` 호출이 하나의 원자적 연산이 아닙니다. 스레드 A가 자신의 정책을 설정한 직후 B가 덮어쓰면 A가 B의 재시도 횟수·예외 분류·backoff로 실행될 수 있습니다.

   **수정:** 호출별 로컬 `RetryTemplate`을 만들거나 정책별로 변경하지 않는 인스턴스를 사용하십시오. 또한 `maxAttempts`를 그대로 `maxRetries()`에 전달하여 최초 실행까지 합치면 이름이 의미하는 횟수보다 한 번 더 실행됩니다. 총 시도 횟수라면 `maxAttempts - 1`로 변환해야 합니다. [Spring retry 횟수 정의](https://docs.spring.io/spring-framework/reference/core/resilience.html)

9. **Medium — JOIN FETCH 대상에 사용자 조건을 걸어 참석자 컬렉션을 절단**

   **위치:** [JpaMeetingRepository.kt:31](infrastructure/src/main/kotlin/dev/notypie/repository/meeting/JpaMeetingRepository.kt:31), [JpaMeetingRepository.kt:42](infrastructure/src/main/kotlin/dev/notypie/repository/meeting/JpaMeetingRepository.kt:42)

   조회자가 주최자가 아닌 참석자라면 `p.userId = :userId`를 만족하는 participant 행만 fetch됩니다. 참석자 세 명인 회의를 조회해도 컬렉션에는 본인만 들어가며, 그 상태로 DTO가 생성됩니다. `DISTINCT`는 누락된 참석자를 복원하지 않습니다.

   **수정:** 사용자 소속 여부는 별도 `EXISTS` 서브쿼리로 검사하고, 참석자 fetch join에는 사용자 조건을 적용하지 마십시오.

10. **Medium — sidecar timeout이 SSE 전체 소비 시간을 제한하지 않음**

    **위치:** [SidecarAgentClient.kt:53](infrastructure/src/main/kotlin/dev/notypie/impl/agent/SidecarAgentClient.kt:53), [SidecarAgentClient.kt:63](infrastructure/src/main/kotlin/dev/notypie/impl/agent/SidecarAgentClient.kt:63), [SidecarAgentClient.kt:114](infrastructure/src/main/kotlin/dev/notypie/impl/agent/SidecarAgentClient.kt:114)

    `ofLines()`는 body 수신 완료 전에 반환하며, 이후 iterator 소비에는 별도 deadline이 없습니다. sidecar가 헤더를 보낸 뒤 연결을 유지하면서 `done/error`를 보내지 않으면, 설정된 120초를 넘겨 작업과 연결이 계속 점유될 수 있습니다. [JDK 25 streaming body 설명](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html#ofLines())

    **수정:** 스트림 소비 전체에 deadline과 idle timeout을 적용하고, 만료 시 HTTP subscription 또는 스트림을 실제로 취소·종료하십시오.

11. **Medium — DatabaseException을 빈 handler가 성공 응답으로 숨김**

    **위치:** [ControllerAdvice.kt:14](application/src/main/kotlin/dev/notypie/application/exception/ControllerAdvice.kt:14)

    handler가 상태 코드 설정, 응답, 로깅 없이 종료됩니다. 일반적인 미커밋 응답에서는 DB 예외가 처리된 것으로 간주되어 빈 HTTP 200이 나갑니다. 작업 실패가 사용자와 Slack 모두에게 드러나지 않습니다.

    **수정:** 예외 원인을 로그에 남기고, 실패 성격에 맞는 상태와 응답을 반환하십시오. 이미 ACK한 비동기 작업이라면 별도 오류 알림과 재처리 상태를 기록해야 합니다.

12. **Medium — Kafka 모드의 오류 broadcaster가 호출 즉시 NotImplementedError 발생**

    **위치:** [KafkaErrorBroadcaster.kt:8](infrastructure/src/main/kotlin/dev/notypie/exception/KafkaErrorBroadcaster.kt:8), [ConsumerConfig.kt:72](application/src/main/kotlin/dev/notypie/application/configurations/ConsumerConfig.kt:72)

    Kafka 모드용 Bean으로 등록되는 구현에 `TODO()`가 남아 있습니다. `broadcastError()`가 호출되면 오류 통지 대신 `NotImplementedError`가 발생하며, `catch (Exception)`으로도 잡히지 않습니다.

    **수정:** 오류 topic 전송과 전송 실패 처리를 구현하거나, 구현 전까지 동작하는 broadcaster를 등록하십시오.

13. **Medium — 하위 모듈 변경 시 의존하는 상위 모듈 테스트를 생략**

    **위치:** [simple_test_action.yaml:61](.github/workflows/simple_test_action.yaml:61)

    domain만 변경하면 `:domain:test`만 실행합니다. domain의 계약 변경이 application을 깨뜨려도 application 테스트는 실행되지 않습니다. infrastructure 변경에도 같은 문제가 있습니다.

    **수정:** domain 변경은 세 모듈 전체, infrastructure 변경은 infrastructure와 application을 검사하도록 의존 방향을 반영하십시오. 규모상 비용이 크지 않다면 PR마다 전체 `build`가 더 단순합니다.

14. **Medium — polling Bean이 바인딩된 AppConfig 대신 기본값을 사용**

    **위치:** [ConsumerConfig.kt:34](application/src/main/kotlin/dev/notypie/application/configurations/ConsumerConfig.kt:34), [PollingMessageProcessor.kt:13](application/src/main/kotlin/dev/notypie/application/service/relay/PollingMessageProcessor.kt:13)

    Bean 생성 시 `AppConfig`를 전달하지 않아 생성자 기본값인 `AppConfig()`가 사용됩니다. 운영자가 batch size나 stuck threshold를 조정해도 polling에는 반영되지 않습니다. 느린 전송에 맞춰 threshold를 늘렸다고 생각했는데 기존 값으로 복구가 시작될 수 있습니다.

    **수정:** Bean factory에 바인딩된 `AppConfig`를 주입하고 생성자로 전달하십시오.

15. **Low — `/task` endpoint가 아무 작업 없이 성공 종료**

    **위치:** [SlashCommandController.kt:46](application/src/main/kotlin/dev/notypie/application/controllers/SlashCommandController.kt:46)

    요청을 파싱한 후 사용하지 않습니다. 이 endpoint로 명령이 들어오면 작업 생성이나 안내 없이 성공 응답만 반환됩니다.

    **수정:** 실제 handler를 연결하거나, 미지원 명령임을 명시적으로 응답하고 등록을 제거하십시오.

**확인 결과 올바른 부분과 오탐으로 제외한 항목**

- **서명 계산:** [SlackSignatureVerifier.kt:30](application/src/main/kotlin/dev/notypie/application/security/SlackSignatureVerifier.kt:30)의 raw body 기반 HMAC-SHA256, timestamp 검사, `MessageDigest.isEqual()` 사용은 올바릅니다. 다만 secret이 비면 필터가 검증을 생략하며, retry header 없는 재전송은 deduplicator가 차단하지 않습니다. 따라서 완전한 replay 방지나 운영 환경의 fail-closed를 확인했다고 볼 수는 없습니다.
- **Kafka offset:** prod/dev YAML은 모두 `enable-auto-commit: true`입니다. 이를 수동 ACK 또는 Spring `AckMode` 기반 commit으로 설명하면 틀립니다. 그러나 동기 listener이므로 이 설정 하나만으로 “처리 중 백그라운드 commit으로 유실된다”고 판정하지 않았습니다. 확정된 문제는 위의 정상 반환 처리와 재전달 시 상태 미확인입니다. [Spring Kafka commit 규칙](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html)
- **outbox 저장 시점:** [SlackMessageRelayServiceImpl.kt:89](application/src/main/kotlin/dev/notypie/application/service/relay/SlackMessageRelayServiceImpl.kt:89)의 `BEFORE_COMMIT`은 발행 트랜잭션과 outbox 저장을 함께 commit하려는 올바른 선택입니다. 해당 메서드 안에는 Slack I/O가 없습니다. 다만 모든 발행 경로에 트랜잭션이 존재하는지는 이번 범위에서 확인하지 않았습니다.
- **비동기 실행:** 같은 Bean 내부 호출에 `@Async`를 기대하지 않고 executor에 직접 제출하는 방식은 맞습니다.
- **HTTP 공유 상태·자원:** `RestClientRequester`는 호출마다 request spec을 새로 만듭니다. `ApplicationMessageDispatcher`가 response를 닫은 후 상태 코드·메시지만 읽는 부분도 closed body 접근 오류가 아닙니다. sidecar 역시 스트림을 `use`로 닫고, terminal event 없는 EOF를 실패로 처리합니다.
- **modal·회의 변경:** modal open을 지연되는 outbox 경로에서 금지하고 즉시 전송 경로로 분리한 점은 적절합니다. 회의 취소·일정 변경 SQL에 requester와 취소 상태 조건을 함께 넣은 것도 올바릅니다.
- **DB 인덱스:** [OutboxMessage.kt:19](infrastructure/src/main/kotlin/dev/notypie/repository/outbox/schema/OutboxMessage.kt:19)의 매핑에는 `idempotency_key` 인덱스만 있습니다. hot query에는 `(status, created_at)`, `(status, updated_at)`이 필요하지만, prod는 `ddl-auto: none`이고 migration을 읽지 않았으므로 **운영 DB에 해당 인덱스가 없다는 결함으로 확정하지 않았습니다.**


OpenAI Codex v0.155.1
--------
workdir: .
model: gpt-6-astra
provider: openai
approval: never
sandbox: danger-full-access
reasoning effort: high
reasoning summaries: none
session id: 01a0c6ac-d720-7952-9156-619633d5f52e
--------
user
(프롬프트 생략 — 위 `Original task`와 같음)
codex
지정 파일 중심으로 약 25개 파일만 확인하고, 실제로 읽은 코드 줄에 근거한 결함을 우선순위별로 정리하겠습니다. 현재 커밋과 작업 트리 상태도 확인하겠습니다.

exec
/bin/zsh -lc 'pwd && git status --short && git rev-parse --short HEAD && git branch --show-current && rg --files -g AGENTS.md application infrastructure domain .github | sort' in .
 succeeded in 0ms:
.
?? review.md
90c747a
main
.github/AGENTS.md
.github/workflows/AGENTS.md
application/AGENTS.md
application/src/AGENTS.md
application/src/main/AGENTS.md
application/src/main/kotlin/AGENTS.md
application/src/main/kotlin/dev/AGENTS.md
application/src/main/kotlin/dev/notypie/AGENTS.md
… (출력 207줄 생략)

exec
/bin/zsh -lc "python3 - <<'PY'
from pathlib import Path
paths = [\"application/src/main/kotlin/dev/notypie/application/service/relay/PollingMessageProcessor.kt\",\"application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt\",\"application/src/main/kotlin/dev/notypie/application/service/relay/SlackMessageRelayServiceImpl.kt\",\"infrastructure/src/main/kotlin/dev/notypie/repository/outbox/MessageOutboxRepository.kt\",\"infrastructure/src/main/kotlin/dev/notypie/repository/outbox/schema/OutboxMessage.kt\",\"infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt\",\"infrastructure/src/main/kotlin/dev/notypie/impl/command/ApplicationMessageDispatcher.kt\",\"infrastructure/src/main/kotlin/dev/notypie/impl/command/RestClientRequester.kt\"]
for p in paths:
 print('\\nFILE '+p)
 for i,l in enumerate(Path(p).read_text().splitlines(),1): print(f'{i:4} {l}')
… (명령 1줄 생략)
 succeeded in 63ms:

FILE application/src/main/kotlin/dev/notypie/application/service/relay/PollingMessageProcessor.kt
   1 package dev.notypie.application.service.relay
   2 
   3 import dev.notypie.application.configurations.AppConfig
   4 import dev.notypie.repository.outbox.MessageOutboxRepository
   5 import org.springframework.scheduling.annotation.Scheduled
   6 import java.time.Clock
   7 import java.time.Duration
   8 
   9 class PollingMessageProcessor(
  10     private val outboxRepository: MessageOutboxRepository,
… (출력 886줄 생략)

exec
/bin/zsh -lc "python3 - <<'PY'
from pathlib import Path
paths = [\"infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt\",\"infrastructure/src/main/kotlin/dev/notypie/impl/agent/SidecarAgentClient.kt\",\"infrastructure/src/main/kotlin/dev/notypie/repository/meeting/JpaMeetingRepository.kt\",\"infrastructure/src/main/kotlin/dev/notypie/repository/meeting/MeetingRepositoryImpl.kt\"]
for p in paths:
 print('\\nFILE '+p)
 for i,l in enumerate(Path(p).read_text().splitlines(),1): print(f'{i:4} {l}')
… (명령 1줄 생략)
 succeeded in 29ms:

FILE infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt
   1 package dev.notypie.impl.retry
   2 
   3 import dev.notypie.configurations.RetryOptions
   4 import org.springframework.core.retry.RetryException
   5 import org.springframework.core.retry.RetryPolicy
   6 import org.springframework.core.retry.RetryTemplate
   7 import org.springframework.util.backoff.FixedBackOff
   8 import java.time.Duration
   9 
  10 class RetryService(
… (출력 523줄 생략)

exec
/bin/zsh -lc "python3 - <<'PY'
from pathlib import Path
paths = [\"infrastructure/src/main/kotlin/dev/notypie/exception/KafkaErrorBroadcaster.kt\",\"application/src/main/kotlin/dev/notypie/application/configurations/ConsumerConfig.kt\",\"application/src/main/resources/application-prod.yaml\",\"application/src/main/resources/application-dev.yaml\"]
for p in paths:
 print('\\nFILE '+p)
 for i,l in enumerate(Path(p).read_text().splitlines(),1): print(f'{i:4} {l}')
… (명령 1줄 생략)
 succeeded in 17ms:

FILE infrastructure/src/main/kotlin/dev/notypie/exception/KafkaErrorBroadcaster.kt
   1 package dev.notypie.exception
   2 
   3 import org.springframework.kafka.core.KafkaTemplate
   4 
   5 class KafkaErrorBroadcaster(
   6     val kafkaTemplate: KafkaTemplate<String, Any>,
   7 ) : ErrorBroadcaster {
   8     override fun broadcastError(message: String): Unit = TODO("Not yet implemented")
   9 }

… (출력 343줄 생략)

codex
outbox에서 claim한 행 수를 후보 목록의 앞부분과 대응시키는 경쟁 조건과, 회의 조회에서 참석자 컬렉션이 일부만 로드되는 문제를 확인했습니다. 이어서 Slack 요청 검증·ACK 처리와 CI 설정을 확인하고, Kafka 재전달의 정상 특성과 실제 결함을 구분하겠습니다.

exec
/bin/zsh -lc "python3 - <<'PY'
from pathlib import Path
paths = [\"application/src/main/kotlin/dev/notypie/application/security/SlackSignatureVerifier.kt\",\"application/src/main/kotlin/dev/notypie/application/security/SlackRequestVerificationFilter.kt\",\"application/src/main/kotlin/dev/notypie/application/security/SlackRetryDeduplicator.kt\",\"application/src/main/kotlin/dev/notypie/application/controllers/SlashCommandController.kt\"]
for p in paths:
 print('\\nFILE '+p)
 for i,l in enumerate(Path(p).read_text().splitlines(),1): print(f'{i:4} {l}')
… (명령 1줄 생략)
 succeeded in 11ms:

FILE application/src/main/kotlin/dev/notypie/application/security/SlackSignatureVerifier.kt
   1 package dev.notypie.application.security
   2 
   3 import java.security.MessageDigest
   4 import java.time.Clock
   5 import javax.crypto.Mac
   6 import javax.crypto.spec.SecretKeySpec
   7 import kotlin.math.abs
   8 
   9 class SlackSignatureVerifier(
  10     private val clock: Clock,
… (출력 336줄 생략)

exec
/bin/zsh -lc "python3 - <<'PY'
from pathlib import Path
paths = [\"application/src/main/kotlin/dev/notypie/application/controllers/SlackEventController.kt\",\"application/src/main/kotlin/dev/notypie/application/exception/ControllerAdvice.kt\",\"application/src/main/kotlin/dev/notypie/application/service/mention/SlackMentionEventHandlerImpl.kt\",\".github/workflows/simple_test_action.yaml\",\".github/workflows/lint.yaml\",\".github/workflows/deploy_action.yaml\"]
for p in paths:
 print('\\nFILE '+p)
 for i,l in enumerate(Path(p).read_text().splitlines(),1): print(f'{i:4} {l}')
… (명령 1줄 생략)
 succeeded in 38ms:

FILE application/src/main/kotlin/dev/notypie/application/controllers/SlackEventController.kt
   1 package dev.notypie.application.controllers
   2 
   3 import dev.notypie.application.service.interaction.InteractionHandler
   4 import dev.notypie.application.service.mention.AppMentionEventHandler
   5 import dev.notypie.impl.command.slack.SlackEventType
   6 import io.github.oshai.kotlinlogging.KotlinLogging
   7 import org.springframework.http.MediaType
   8 import org.springframework.http.ResponseEntity
   9 import org.springframework.util.MultiValueMap
  10 import org.springframework.web.bind.annotation.PostMapping
… (출력 604줄 생략)

codex
총 25개 파일을 확인했습니다. 실패한 요청도 재시도로 판단해 ACK하는 중복 방지 로직과, main PR의 병합 결과를 검사하지 않는 CI 구성을 확인했습니다. Kafka 자동 커밋만으로 유실을 단정하거나, 읽지 않은 migration을 근거로 운영 DB 인덱스가 없다고 판단하지는 않겠습니다.

web search: 
web search: https://bugs.openjdk.org/browse/JDK-8258397
codex
검토 기준은 **main @ `90c747a`**입니다. 지정 목록에서 **25개 파일**을 읽었습니다. `Meeting.kt`, `Utils.kt`는 제외했으며, 코드 수정·빌드·테스트 실행은 하지 않았습니다. 기존 미추적 파일 `review.md`도 열거나 변경하지 않았습니다.

확인한 범위에서 Critical은 없으며, 아래 High 항목은 배포 전에 수정하는 편이 좋겠습니다.

1. **High — claim 결과의 개수만으로 처리 대상을 선택하여 중복 dispatch와 누락 발생**

   **위치:** [PollingMessageProcessor.kt:38](application/src/main/kotlin/dev/notypie/application/service/relay/PollingMessageProcessor.kt:38), [MessageOutboxRepository.kt:33](infrastructure/src/main/kotlin/dev/notypie/repository/outbox/MessageOutboxRepository.kt:33)

   `claimPending()`은 변경된 행 **개수**만 반환하는데, 호출자는 `candidates.take(claimedCount)`를 자신이 claim한 행으로 간주합니다. 후보가 `[A, B]`이고 다른 인스턴스가 A를 먼저 claim하면, 현재 인스턴스는 B를 claim하고도 A를 dispatch합니다. A는 중복 처리되고 B는 복구 시점까지 방치됩니다.

   **수정:** 짧은 트랜잭션에서 `SELECT … FOR UPDATE SKIP LOCKED`로 선택·갱신하고 정확한 행 목록을 반환하거나, 고유 claim token을 저장한 뒤 해당 token의 행만 dispatch하십시오.

2. **High — stuck 메시지 복구에 소유권 획득과 lease 갱신이 없음**

   **위치:** [PollingMessageProcessor.kt:25](application/src/main/kotlin/dev/notypie/application/service/relay/PollingMessageProcessor.kt:25), [SlackMessageRelayServiceImpl.kt:35](application/src/main/kotlin/dev/notypie/application/service/relay/SlackMessageRelayServiceImpl.kt:35)

   오래된 `IN_PROGRESS` 행을 조회한 뒤 곧바로 비동기 dispatch합니다. 복구 작업이 5초 이상 걸리면 다음 tick이 같은 행을 다시 제출하며, 여러 인스턴스도 동시에 같은 행을 복구할 수 있습니다. 정상 전송이 느린 경우에도 원래 작업과 복구 작업이 겹칩니다.

   **수정:** 만료된 lease를 조건부 UPDATE로 획득하고 owner/token과 만료 시각을 갱신하십시오. 완료 처리도 해당 token 소유자만 수행하게 하고, 장시간 작업에는 lease 연장을 적용하십시오.

3. **High — CDC 재전달 시 현재 DB 상태를 확인하지 않고 다시 전송**

   **위치:** [DebeziumLogTailingProcessor.kt:41](application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt:41), [DebeziumLogTailingProcessor.kt:55](application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt:55)

   검사하는 상태는 CDC 레코드에 담긴 과거의 `PENDING`입니다. Slack 전송과 DB 상태 갱신까지 성공한 뒤 offset commit 전에 프로세스가 종료되면, 재전달된 원본 레코드로 다시 dispatch합니다. 이미 DB에 성공이 기록된 경우조차 건너뛰지 못합니다.

   **수정:** `eventId`로 현재 상태를 확인하고 원자적으로 claim한 경우에만 전송하십시오. Slack 전송 성공과 DB 기록 사이의 crash window는 별도로 남으므로, 지원되는 전송에는 안정적인 중복 방지 키도 적용해야 합니다.

4. **High — 실패한 Slack 요청의 정상 재시도를 성공 ACK로 폐기**

   **위치:** [SlackRetryDeduplicator.kt:28](application/src/main/kotlin/dev/notypie/application/security/SlackRetryDeduplicator.kt:28), [SlackRequestVerificationFilter.kt:66](application/src/main/kotlin/dev/notypie/application/security/SlackRequestVerificationFilter.kt:66)

   fingerprint를 실제 처리 **전에** 저장하고, 처리 실패 시 제거하지 않습니다. 최초 요청이 DB 오류로 실패한 뒤 동일 fingerprint의 Slack retry가 도착하면, controller를 호출하지 않고 HTTP 200을 반환합니다. 요청은 처리되지 않았는데 Slack에는 성공으로 보입니다.

   **수정:** durable inbox에 요청을 저장한 뒤 ACK하고 처리 상태를 관리하십시오. 최소한 임시 claim과 완료 상태를 구분하고, 실패한 claim은 재처리할 수 있어야 합니다. 다중 인스턴스에서는 공유 저장소가 필요합니다.

5. **High — app mention ACK가 명령 실행과 트랜잭션 완료를 기다림**

   **위치:** [SlackEventController.kt:43](application/src/main/kotlin/dev/notypie/application/controllers/SlackEventController.kt:43), [SlackMentionEventHandlerImpl.kt:33](application/src/main/kotlin/dev/notypie/application/service/mention/SlackMentionEventHandlerImpl.kt:33), [SlackMentionEventHandlerImpl.kt:65](application/src/main/kotlin/dev/notypie/application/service/mention/SlackMentionEventHandlerImpl.kt:65)

   controller가 트랜잭션을 포함한 `handleEvent()` 반환 후 ACK합니다. DB lock이나 commit 지연으로 3초를 넘으면 실제 처리가 진행 중이어도 Slack은 실패로 판단하고 재시도합니다. 이는 [Slack의 Events API ACK 계약](https://docs.slack.dev/apis/events-api/)과 충돌합니다.

   **수정:** 검증된 이벤트를 짧은 inbox 트랜잭션으로 수락한 뒤 즉시 ACK하고, 명령 실행은 worker로 이동하십시오. 읽지 않은 하위 구현을 근거로 AI 호출까지 동기라고 단정하지는 않습니다.

6. **High — CDC 파싱 실패를 정상 반환하여 복구 가능한 레코드까지 소비 처리**

   **위치:** [DebeziumLogTailingProcessor.kt:37](application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt:37), [DebeziumLogTailingProcessor.kt:44](application/src/main/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessor.kt:44)

   변환 실패와 잘못된 UUID를 로그만 남기고 `return`합니다. 예를 들어 배포 버전과 CDC 스키마가 일시적으로 맞지 않으면 해당 레코드는 listener 관점에서 성공 처리됩니다. 이후 offset이 전진하면 코드를 수정해도 자동 재처리되지 않습니다.

   **수정:** 재시도 가능한 오류는 전파하고, 영구적인 poison record는 원본·오류를 durable DLT에 저장한 뒤 소비를 완료하십시오. 단순 로그를 유일한 복구 수단으로 두지 않아야 합니다.

7. **High — 지정 CI가 main 병합 결과에 대한 테스트·lint gate를 제공하지 않음**

   **위치:** [simple_test_action.yaml:2](.github/workflows/simple_test_action.yaml:2), [lint.yaml:2](.github/workflows/lint.yaml:2), [deploy_action.yaml:98](.github/workflows/deploy_action.yaml:98), [deploy_action.yaml:129](.github/workflows/deploy_action.yaml:129)

   테스트와 lint는 특정 이름의 branch `push`에서만 실행됩니다. `fix/*` PR이나 main과의 병합 결과는 이 두 workflow의 검사 대상이 아닙니다. 배포는 merge 후 실행되며 테스트를 제외하고, deployment 생성 시 required contexts도 비워 둡니다.

   **수정:** main 대상 `pull_request`에서 검사하고, merge queue 사용 시 `merge_group`도 추가하십시오. 해당 결과를 required check로 설정하고, 배포할 SHA가 검증된 SHA인지 보장해야 합니다. **GitHub branch protection 설정 자체는 확인하지 않았으므로 실제 merge 허용 여부까지 단정하지 않습니다.**

8. **Medium — 공유 RetryTemplate의 정책을 요청마다 변경하는 경쟁 조건**

   **위치:** [RetryService.kt:33](infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt:33)

   `retryPolicy` 설정과 `execute()` 호출이 하나의 원자적 연산이 아닙니다. 스레드 A가 자신의 정책을 설정한 직후 B가 덮어쓰면 A가 B의 재시도 횟수·예외 분류·backoff로 실행될 수 있습니다.

   **수정:** 호출별 로컬 `RetryTemplate`을 만들거나 정책별로 변경하지 않는 인스턴스를 사용하십시오. 또한 `maxAttempts`를 그대로 `maxRetries()`에 전달하여 최초 실행까지 합치면 이름이 의미하는 횟수보다 한 번 더 실행됩니다. 총 시도 횟수라면 `maxAttempts - 1`로 변환해야 합니다. [Spring retry 횟수 정의](https://docs.spring.io/spring-framework/reference/core/resilience.html)

9. **Medium — JOIN FETCH 대상에 사용자 조건을 걸어 참석자 컬렉션을 절단**

   **위치:** [JpaMeetingRepository.kt:31](infrastructure/src/main/kotlin/dev/notypie/repository/meeting/JpaMeetingRepository.kt:31), [JpaMeetingRepository.kt:42](infrastructure/src/main/kotlin/dev/notypie/repository/meeting/JpaMeetingRepository.kt:42)

   조회자가 주최자가 아닌 참석자라면 `p.userId = :userId`를 만족하는 participant 행만 fetch됩니다. 참석자 세 명인 회의를 조회해도 컬렉션에는 본인만 들어가며, 그 상태로 DTO가 생성됩니다. `DISTINCT`는 누락된 참석자를 복원하지 않습니다.

   **수정:** 사용자 소속 여부는 별도 `EXISTS` 서브쿼리로 검사하고, 참석자 fetch join에는 사용자 조건을 적용하지 마십시오.

10. **Medium — sidecar timeout이 SSE 전체 소비 시간을 제한하지 않음**

    **위치:** [SidecarAgentClient.kt:53](infrastructure/src/main/kotlin/dev/notypie/impl/agent/SidecarAgentClient.kt:53), [SidecarAgentClient.kt:63](infrastructure/src/main/kotlin/dev/notypie/impl/agent/SidecarAgentClient.kt:63), [SidecarAgentClient.kt:114](infrastructure/src/main/kotlin/dev/notypie/impl/agent/SidecarAgentClient.kt:114)

    `ofLines()`는 body 수신 완료 전에 반환하며, 이후 iterator 소비에는 별도 deadline이 없습니다. sidecar가 헤더를 보낸 뒤 연결을 유지하면서 `done/error`를 보내지 않으면, 설정된 120초를 넘겨 작업과 연결이 계속 점유될 수 있습니다. [JDK 25 streaming body 설명](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html#ofLines())

    **수정:** 스트림 소비 전체에 deadline과 idle timeout을 적용하고, 만료 시 HTTP subscription 또는 스트림을 실제로 취소·종료하십시오.

11. **Medium — DatabaseException을 빈 handler가 성공 응답으로 숨김**

    **위치:** [ControllerAdvice.kt:14](application/src/main/kotlin/dev/notypie/application/exception/ControllerAdvice.kt:14)

    handler가 상태 코드 설정, 응답, 로깅 없이 종료됩니다. 일반적인 미커밋 응답에서는 DB 예외가 처리된 것으로 간주되어 빈 HTTP 200이 나갑니다. 작업 실패가 사용자와 Slack 모두에게 드러나지 않습니다.

    **수정:** 예외 원인을 로그에 남기고, 실패 성격에 맞는 상태와 응답을 반환하십시오. 이미 ACK한 비동기 작업이라면 별도 오류 알림과 재처리 상태를 기록해야 합니다.

12. **Medium — Kafka 모드의 오류 broadcaster가 호출 즉시 NotImplementedError 발생**

    **위치:** [KafkaErrorBroadcaster.kt:8](infrastructure/src/main/kotlin/dev/notypie/exception/KafkaErrorBroadcaster.kt:8), [ConsumerConfig.kt:72](application/src/main/kotlin/dev/notypie/application/configurations/ConsumerConfig.kt:72)

    Kafka 모드용 Bean으로 등록되는 구현에 `TODO()`가 남아 있습니다. `broadcastError()`가 호출되면 오류 통지 대신 `NotImplementedError`가 발생하며, `catch (Exception)`으로도 잡히지 않습니다.

    **수정:** 오류 topic 전송과 전송 실패 처리를 구현하거나, 구현 전까지 동작하는 broadcaster를 등록하십시오.

13. **Medium — 하위 모듈 변경 시 의존하는 상위 모듈 테스트를 생략**

    **위치:** [simple_test_action.yaml:61](.github/workflows/simple_test_action.yaml:61)

    domain만 변경하면 `:domain:test`만 실행합니다. domain의 계약 변경이 application을 깨뜨려도 application 테스트는 실행되지 않습니다. infrastructure 변경에도 같은 문제가 있습니다.

    **수정:** domain 변경은 세 모듈 전체, infrastructure 변경은 infrastructure와 application을 검사하도록 의존 방향을 반영하십시오. 규모상 비용이 크지 않다면 PR마다 전체 `build`가 더 단순합니다.

14. **Medium — polling Bean이 바인딩된 AppConfig 대신 기본값을 사용**

    **위치:** [ConsumerConfig.kt:34](application/src/main/kotlin/dev/notypie/application/configurations/ConsumerConfig.kt:34), [PollingMessageProcessor.kt:13](application/src/main/kotlin/dev/notypie/application/service/relay/PollingMessageProcessor.kt:13)

    Bean 생성 시 `AppConfig`를 전달하지 않아 생성자 기본값인 `AppConfig()`가 사용됩니다. 운영자가 batch size나 stuck threshold를 조정해도 polling에는 반영되지 않습니다. 느린 전송에 맞춰 threshold를 늘렸다고 생각했는데 기존 값으로 복구가 시작될 수 있습니다.

    **수정:** Bean factory에 바인딩된 `AppConfig`를 주입하고 생성자로 전달하십시오.

15. **Low — `/task` endpoint가 아무 작업 없이 성공 종료**

    **위치:** [SlashCommandController.kt:46](application/src/main/kotlin/dev/notypie/application/controllers/SlashCommandController.kt:46)

    요청을 파싱한 후 사용하지 않습니다. 이 endpoint로 명령이 들어오면 작업 생성이나 안내 없이 성공 응답만 반환됩니다.

    **수정:** 실제 handler를 연결하거나, 미지원 명령임을 명시적으로 응답하고 등록을 제거하십시오.

**확인 결과 올바른 부분과 오탐으로 제외한 항목**

- **서명 계산:** [SlackSignatureVerifier.kt:30](application/src/main/kotlin/dev/notypie/application/security/SlackSignatureVerifier.kt:30)의 raw body 기반 HMAC-SHA256, timestamp 검사, `MessageDigest.isEqual()` 사용은 올바릅니다. 다만 secret이 비면 필터가 검증을 생략하며, retry header 없는 재전송은 deduplicator가 차단하지 않습니다. 따라서 완전한 replay 방지나 운영 환경의 fail-closed를 확인했다고 볼 수는 없습니다.
- **Kafka offset:** prod/dev YAML은 모두 `enable-auto-commit: true`입니다. 이를 수동 ACK 또는 Spring `AckMode` 기반 commit으로 설명하면 틀립니다. 그러나 동기 listener이므로 이 설정 하나만으로 “처리 중 백그라운드 commit으로 유실된다”고 판정하지 않았습니다. 확정된 문제는 위의 정상 반환 처리와 재전달 시 상태 미확인입니다. [Spring Kafka commit 규칙](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html)
- **outbox 저장 시점:** [SlackMessageRelayServiceImpl.kt:89](application/src/main/kotlin/dev/notypie/application/service/relay/SlackMessageRelayServiceImpl.kt:89)의 `BEFORE_COMMIT`은 발행 트랜잭션과 outbox 저장을 함께 commit하려는 올바른 선택입니다. 해당 메서드 안에는 Slack I/O가 없습니다. 다만 모든 발행 경로에 트랜잭션이 존재하는지는 이번 범위에서 확인하지 않았습니다.
- **비동기 실행:** 같은 Bean 내부 호출에 `@Async`를 기대하지 않고 executor에 직접 제출하는 방식은 맞습니다.
- **HTTP 공유 상태·자원:** `RestClientRequester`는 호출마다 request spec을 새로 만듭니다. `ApplicationMessageDispatcher`가 response를 닫은 후 상태 코드·메시지만 읽는 부분도 closed body 접근 오류가 아닙니다. sidecar 역시 스트림을 `use`로 닫고, terminal event 없는 EOF를 실패로 처리합니다.
- **modal·회의 변경:** modal open을 지연되는 outbox 경로에서 금지하고 즉시 전송 경로로 분리한 점은 적절합니다. 회의 취소·일정 변경 SQL에 requester와 취소 상태 조건을 함께 넣은 것도 올바릅니다.
- **DB 인덱스:** [OutboxMessage.kt:19](infrastructure/src/main/kotlin/dev/notypie/repository/outbox/schema/OutboxMessage.kt:19)의 매핑에는 `idempotency_key` 인덱스만 있습니다. hot query에는 `(status, created_at)`, `(status, updated_at)`이 필요하지만, prod는 `ddl-auto: none`이고 migration을 읽지 않았으므로 **운영 DB에 해당 인덱스가 없다는 결함으로 확정하지 않았습니다.**
tokens used
85,394

```

## Concise summary

Provider completed successfully. Review the raw output for details.

## Action items

- Review the response and extract decisions you want to apply.
- Capture follow-up implementation tasks if needed.
