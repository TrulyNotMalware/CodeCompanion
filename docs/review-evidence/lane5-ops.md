## 3차 리뷰 — CI/CD · k8s · 운영 스크립트 · 빌드 · 문서 레인 결과

**결론:** A1·A7과 S16 대부분은 실제로 해결됐습니다. prod 기동을 막거나 시크릿이 새는 문제는 찾지 못했습니다. 가장 중요한 것은 P1입니다. "이 릴리스는 이전 릴리스와 동시에 떠 있으면 안 된다"는 요구를 매니페스트도 워크플로도 강제하지 않고, 머지 전에 사람이 `kubectl patch`를 해야만 지켜집니다. 그다음은 두 가지입니다. 13.5의 마이그레이션 목록에 V18·V19가 빠졌고, 스키마가 없어도 배포 게이트가 통과합니다(P2). 종료 예산은 레코드 1건의 최악 처리 시간(약 53초)을 담지 못합니다(S16(f) PARTIAL).

**검증 범위:** actionlint·shellcheck는 로컬에 없어 수동으로 검토했습니다. Gradle·docker·클러스터 접속은 하지 않았습니다. 메인 세션 실측(샘플 키만으로 prod 기동 성공, readiness `{"status":"UP"}`, `/actuator/env` 404, 브로커가 없으면 KafkaAdmin 대기로 기동 약 46초)은 전제로 받아들이고 다시 재지 않았습니다.

---

### 1. 13.5 판정표

| 항목 | 판정 | 근거 file:line | 설명 |
|---|---|---|---|
| **A1** 헬스체크 | **RESOLVED** (Low 단서 P3) | `deploy_action.yaml:290-341`, `:297`, `:302-314`, `:293-296`; `application-prod.yaml:95-96,102`; `service.yaml:4-11`; `deployment.yaml:4,9,20,23`; `Dockerfile:1` | 공개 URL은 더 이상 쓰지 않습니다.<br>• 서비스 이름 `code-companion-svc`, 포트 80, 네임스페이스 `api-service`, 컨테이너 이름이 매니페스트와 일치합니다.<br>• alpine의 busybox에 `wget`이 있습니다(`-T` 지원). `jq`는 사전 점검하고 ubuntu 러너에 기본 설치돼 있습니다.<br>• probes는 명시적으로 켜져 있고 메인 세션에서 readiness UP을 확인했습니다.<br>• 게이트웨이 가설 문장은 `k8s/AGENTS.md:102-111`, `dev-environment.md:175-178`, `.github/AGENTS.md:147-151`에서 삭제·교체됐습니다.<br>• 단서: 이 게이트는 readinessProbe(같은 경로)와 `rollout status`가 이미 확인한 것을 거의 반복합니다. exec 폴백은 이전 파드를 검사할 수 있습니다(P3). RBAC는 확인하지 못했습니다. |
| **A7** 매니페스트 키 | **RESOLVED** | `configmap.yaml:10-11`, `secret.yaml:6,11` | 기본값 없는 prod 플레이스홀더(`application-prod.yaml:15,19,20,27-29,37,63,123,124,130`)가 샘플에 전부 있습니다. 메인 세션 실측으로 기동도 확인됐습니다. |
| **S14** actuator 노출 | **PARTIAL** (의도된 잔여) | `application-dev.yaml:70,75`; `run:366,381,403-410`; `route/httpRoute.yaml:15-21`; `route/ingress.yaml:20-33`; `application-local.yaml:65,71` | dev는 `health,info,metrics` + `when_authorized`로 줄었습니다. `run`의 `include=*` 제거, JDWP·JMX 루프백 바인딩, 라우트 축소도 확인했습니다. 남은 것은 local의 `loggers`(POST 쓰기)·`threaddump`·`mappings`·`conditions` + `show-details: always`로, 문서화된 의도입니다. prod의 `metrics`/`info`는 무인증이며 "라우팅하지 않는다"는 것만이 방어입니다. |
| S16(a) 메모리 | RESOLVED | `deployment.yaml:52-57`, `Dockerfile:19-20` | 힙 1Gi, request 1536Mi, limit 2Gi |
| S16(b) 롤백 | RESOLVED | `deploy_action.yaml:237-256,356-400` | `rollout undo --to-revision`을 씁니다. 템플릿 해시 **또는** 리비전이 바뀌었을 때만 되돌리고, 같은 SHA 재배포와 첫 배포는 건너뜁니다. |
| S16(c) 워크플로 concurrency | RESOLVED (security만 없음) | `deploy_action.yaml:27-29`, `lint.yaml:18-20`, `simple_test_action.yaml:32-34`; `security_check.yaml:1-14` | 배포는 직렬화되고, 머지 없이 닫힌 PR은 별도 그룹을 받습니다. `security_check.yaml`에는 여전히 concurrency가 없습니다(영향 미미). |
| S16(d) | RESOLVED | A7과 같음 | |
| S16(e) preStop | RESOLVED | `deployment.yaml:28-31` | `sh`는 alpine 이미지에 있습니다. |
| **S16(f)** 종료 예산 대 처리 시간 | **PARTIAL** | `application-prod.yaml:7` (`timeout-per-shutdown-phase: 10s`); `KafkaConsumerConfiguration.kt:127-146` (`stopImmediate`·`shutdownTimeout` 미설정 → Spring Kafka 기본값 false/10s); `dev-environment.md:61-66` (레코드당 약 53초); `deployment.yaml:15-16` | 리스너 안의 429 대기는 사라졌지만 종료 예산은 여전히 짧습니다.<br>• 종료 시 컨테이너는 현재 poll(최대 5건)을 끝까지 처리하려 하고, lifecycle은 10초만 기다린 뒤 DataSource를 닫습니다.<br>• 결과: 느린 Slack 호출 중이던 레코드는 발송 후 완료 기록에 실패하고, 다른 파드의 복구 스윕이 재발송합니다(중복).<br>• grace 45초 주석은 phase 1개분만 계산합니다.<br>• 수정: `containerProperties.stopImmediate = true`, `shutdownTimeout` ≥ 약 60초, `timeout-per-shutdown-phase` ≥ 60초, grace ≥ 5+60+여유(예: 90). 확신도 MEDIUM. |
| S16(g) root 실행 | PARTIAL (의도적 보류) | `deployment.yaml:25-26`, `k8s/AGENTS.md:90-92` | `allowPrivilegeEscalation: false`만 적용됐습니다. |
| S16(h) stringData | RESOLVED | `secret.yaml:6` | |
| S16(i) envsubst 제한 | RESOLVED | `deploy_action.yaml:261-263` | `deployment.yaml`의 `$`는 `:21`의 `$IMAGE_NAME` 하나뿐입니다. |
| S16(j) CI 프리셋 | RESOLVED (잔여 Low P5) | `gradle-ci.properties:7-16`, `apply.sh:145-158,211-242`, `deploy_action.yaml:136-139`, `simple_test_action.yaml:67-70` | 테스트·배포 워크플로 모두 `apply.sh ci`를 실제로 호출합니다. 잘못된 옵션은 exit 2이고, `verify_config` 실패는 `set -e`로 스텝 실패가 됩니다. |
| S16(k) environment_url | RESOLVED | `deploy_action.yaml:32` | |
| S16(l) `-D`/`-jar` 순서 | RESOLVED | `Dockerfile:20` | `exec`로 java가 PID 1이 되어 SIGTERM을 받습니다. |
| "이전 릴리스와 동시 기동 금지" 강제 | **NOT ENFORCED** | `deployment.yaml:5-10` (strategy 없음), `k8s/README.md:115-137` | 신규 결함 P1 |

### 2. 신규 결함 표

| ID | 심각도 | 제목 | file:line | 구체적 실패 시나리오 | 도입 | 확신도 | 수정 방향 |
|---|---|---|---|---|---|---|---|
| **P1** | **Medium~High** | 동시 기동 금지 요구를 수동 절차에만 의존 | `deployment.yaml:5-10`; `deploy_action.yaml:2-7`; `k8s/README.md:115-137` (한국어 `:319-344`); `review.md:1748,1753`; `V20__…sql:17-24`; `application-prod.yaml:68` | (a) strategy가 기본 RollingUpdate라 replicas 2에서 maxSurge 1, maxUnavailable 0입니다. 머지 전 `kubectl patch`를 잊으면 이 브랜치를 머지하는 순간 자동 배포가 이전 파드 2개와 새 파드를 startup(최대 180초)+readiness 동안 함께 띄웁니다. 같은 컨슈머 그룹 `codeCompanion`과 복구 스윕을 공유하므로, 이전 파드가 새 파드의 IN_PROGRESS를 재발송하고 9시간 시계 차이로 중복 발송이 생깁니다. `SUCCESS→FAILURE` 덮어쓰기도 가능합니다(V20 헤더).<br>(b) README 4단계(`:133-137`)는 조건 없이 "끝나면 RollingUpdate로 복원"하라고 합니다. 워크플로가 pre-V20 바이너리로 롤백한 뒤 복원하면, 다음 수정 PR 배포에서 다시 겹칩니다.<br>(c) 13.5 `:1748`이 전략을 안 바꾼 이유로 든 "3×1536Mi 필요"는 거꾸로입니다. Recreate는 surge 파드를 없앱니다. | cca9984 (README 1회성 절차, 13.5) | HIGH (미강제 자체) | 이 릴리스의 `deployment.yaml`에 `strategy: {type: Recreate}`를 넣습니다. apply가 적용하고, 롤아웃과 `rollout undo` 모두 Recreate로 진행됩니다. 후속 PR에서 삭제하면 three-way merge가 필드를 지워 서버 기본값(RollingUpdate)으로 돌아갑니다. 또는 apply 전에 live strategy가 Recreate이거나 replicas 0인지 검사하는 가드 스텝을 둡니다. README 4단계는 "새 릴리스가 떠 있을 때만"으로 한정합니다. |
| **P2** | **Medium** | 마이그레이션 목록에서 V18·V19 누락, 배포 게이트는 스키마 누락을 못 잡음 | `review.md:1754`; `k8s/README.md:122`; `application-prod.yaml:33` (`ddl-auto: none`); readiness = ReadinessState만 | main은 V17까지이고 이 브랜치가 V18~V22를 한 릴리스로 싣습니다. 13.5는 "V20 → V22 → V21"만 적었습니다(12.3 `:1586`에만 V18·V19가 있음).<br>• V18 없이 배포하면 기동과 readiness UP은 정상이고 게이트도 통과합니다. 그러나 `@Version` 때문에 모든 회의 조회가 unknown column `version`으로 실패하고, V21도 실패합니다.<br>• V20·V22가 없으면 outbox claim이 전부 실패해 나가는 Slack 메시지가 멈춥니다. readiness는 여전히 UP이고, 집계 헬스는 로그로만 남아 롤백되지 않습니다. | cca9984 (13.5 문구) | HIGH (누락) / MEDIUM (영향) | 13.5와 README에 전체 순서를 적습니다: V18(중복 참가자 점검) → V19 → V20 → V22 → 배포 → V21. 선택적으로 기동 시 필수 컬럼 존재를 확인하는 스키마 검사나 배포 전 체크 스텝을 둡니다. |
| **P3** | Low | exec 폴백이 이전(종료 중) 파드의 readiness를 검사할 수 있음 | `deploy_action.yaml:308`; `.github/AGENTS.md:141` | `kubectl exec deploy/<name>`은 `sort.Reverse(ActivePods)`로 Ready가 가장 오래되고 가장 오래된 파드를 고르며, 종료 중인 파드를 제외하지 않습니다. `rollout status` 직후 이전 파드는 preStop 5초 동안 readiness UP을 응답하므로, 게이트가 이전 바이너리 응답으로 통과합니다. 문서는 "DOWN을 보고한다"고만 적었습니다. 실효는 작습니다(readinessProbe가 같은 경로라 새 파드는 이미 Ready). service proxy 경로는 해당 없습니다. | 1b6c6be~cca9984 | MEDIUM | 현재 리비전 ReplicaSet의 `pod-template-hash`로 파드를 고르고 `deletionTimestamp`가 있는 파드는 제외한 뒤 새 파드 전부를 검사합니다. |
| **P4** | Low | `run:` 기본 셸에 pipefail이 없음 | `deploy_action.yaml:243,387` (그리고 `:263`); 파일 전체에 `shell:`/`defaults` 없음 | shell을 명시하지 않은 run의 기본값은 `bash -e {0}`입니다. `jq -cS … \| sha256sum \| cut`에서 jq가 실패하면 양쪽 모두 빈 입력의 해시가 되어 "변경 없음"으로 판정되고 롤백을 건너뜁니다. `envsubst \| kubectl`은 kubectl이 빈 입력에서 실패하므로 안전합니다. | 1b6c6be~cca9984 | HIGH (셸 의미) / LOW (실발생) | `defaults: run: shell: bash` 추가(`-eo pipefail`이 됨) |
| **P5** | Low | CI 러너 메모리 최악치 초과 가능 | `gradle-ci.properties:7-16`; `build.gradle.kts:75-85` | `org.gradle.parallel=true`에서 Test 태스크 3개가 각각 `-Xmx4g`로 동시에 돌면, 데몬 3g+3g를 더해 최대 힙이 18g입니다(16GB 러너). 비공개 저장소라면 ubuntu-latest는 2 vCPU/7GB입니다(저장소 공개 여부는 미확인). | cca9984 | LOW | CI에서 테스트 힙을 프로퍼티로 낮추거나 `workers.max=2`로 둡니다. |

### 3. 문서 드리프트 표

| 문서 file:line | 서술 | 실제 코드 | 조치 |
|---|---|---|---|
| `k8s/README.md:83-84`, `k8s/AGENTS.md:37` | "어떤 매니페스트도 `metadata.namespace`를 설정하지 않는다" | `route/httpRoute.yaml:5` `namespace: your-namespace`. `route/AGENTS.md:20`은 반대로 서술. README `:107`의 `kubectl apply -n api-service -f route/httpRoute.yaml`은 편집하지 않으면 네임스페이스 불일치로 실패 | "route/httpRoute.yaml 제외"를 명시하거나 샘플 값을 `api-service`로 |
| `k8s/AGENTS.md:30` | `envsubst < deployment.yaml \| kubectl apply …`(변수 목록 없음) | `deploy_action.yaml:263` `envsubst '${IMAGE_NAME}'`. 같은 파일 `:34`와도 모순 | `'${IMAGE_NAME}'` 추가 |
| `k8s/AGENTS.md:41` | "the five `SQL_*` keys" | 6개 (configmap 3 + secret 3) | six |
| `k8s/AGENTS.md:48-51` | `SLACK_CDC_TOPIC`이 없어도 리터럴로 컨텍스트가 뜨고 나중에 실패 | `DebeziumLogTailingProcessor.kt:28` `@KafkaListener(topics=["\${…}"])`는 엄격한 embedded resolver라 기동 시 `Could not resolve placeholder` (13.1 C4와 같은 결론; 부재 상태는 실측하지 않음) | SLACK_CDC_TOPIC은 기동 실패라고 정정 |
| `.github/AGENTS.md:147-149` vs `k8s/AGENTS.md:110`, `route/AGENTS.md:36` | 한쪽은 "`GET /actuator/health`만 404", 다른 쪽은 "모든 경로 401" | 실측(13.1)은 404/401이 섞여 있음 | 서술 통일 |
| `.github/AGENTS.md:141` | exec가 고른 이전 파드는 "DOWN을 보고한다" | preStop 5초 동안은 UP (P3) | 정정 |
| `dev-environment.md:156-158` | "이번 실행이 새 **리비전**을 만들었을 때만 undo" | `deploy_action.yaml:389` 템플릿 해시 **또는** 리비전 비교. 같은 문서 `:184-185`와도 불일치 | `:184`에 맞춤 |
| `dev-environment.md:42` | 루트 `AGENTS.md`와 `application/build.gradle.kts` 주석이 `socket` 프로파일을 언급 | 둘 다 언급 없음 (`AGENTS.md:112`는 `local`) | 문장 삭제 (main 기존) |
| `gradle-config/AGENTS.md:50` | "All three presets" | 4개 (ci 포함) | four |
| `infrastructure/…/exception/meeting/AGENTS.md:14,35` (Updated 2026-08-28) | `TABLE_NOT_FOUND(404, …)`; `DatabaseExceptionTest`는 빈 placeholder | `DatabaseException.kt` statusCode 제거; 테스트는 브랜치에서 +60행 | 갱신 + Updated 올림 |
| `infrastructure/…/repository/standup/schema/AGENTS.md:13` (Updated 2026-08-30) | RoutineSchema 인덱스 서술 없음 | `RoutineSchema.kt:20-24` `idx_standup_routine_active_channel` 추가 | 행 갱신 + Updated 올림 |
| `domain/…/command/entity/AGENTS.md:2` | Updated 2026-09-21 | 8504c07에서 본문 수정 | Updated 올림 |
| `docs/wiki/AGENTS.md`, `application/src/test/…/service/mention/AGENTS.md`, `domain/src/test/…/command/parsers/AGENTS.md` | Updated 그대로 | 같은 디렉터리 파일이 브랜치에서 변경됨 | 규칙상 Updated 올림 |
| `AGENTS.md:106-107`, `README.md:26-27,174-175` | Jackson BOM 3.2.0, Spring AI 2.0.0, `spring-ai-starter-mcp-server-webmvc` | `build.gradle.kts:27,29` 3.2.2/2.0.1; `application/build.gradle.kts:47-51`은 starter가 아니라 모듈 4개 | 정정 (main 기존; 루트 AGENTS는 브랜치가 Updated만 올림) |
| `scripts/AGENTS.md:12,29` | 각 단계를 단언하고 첫 불일치에서 비정상 종료, 3단계는 도구 3개를 노출해야 함 | `mcp-smoke.sh:37-53` 2·3단계는 단언이 없음. 빈 배열 + `set -u`는 macOS bash 3.2에서 오류 | 스크립트 보강 또는 서술 정정 (main 기존) |
| `review.md:1754` | 배포 전 수동 적용 `V20 → V22 → V21` | V18·V19도 이 릴리스에 필요 | P2 |
| `k8s/AGENTS.md:45` | BUILD_DATE는 Dockerfile이 주입 | `deploy_action.yaml:185` `github.event.head_commit.timestamp`는 pull_request 이벤트에 없어 항상 빈 값 | `github.event.pull_request.merged_at` 사용 (main 기존) |

### 4. 확인했고 문제 없던 것

- **워크플로와 매니페스트 이름 일치:** 네임스페이스, Deployment·컨테이너·Service 이름, 포트, 라벨이 서로 맞습니다. envsubst로 치환되는 `$`는 `$IMAGE_NAME` 하나뿐입니다.
- **배포 경로별 동작:**
  - 첫 배포: NotFound면 `none`을 기록하고 롤백을 건너뜁니다(`:245-248,366-370`).
  - 같은 SHA 재배포: 템플릿과 리비전이 모두 같으면 건너뜁니다(`:389-393`).
  - 조회 실패: 3회 재시도 후 비교 없이 undo합니다.
  - 롤백 실패: 배포 상태에 `rollback failed`가 기록됩니다(`:396-398,412`).
  - 롤백 범위: apply·rollout·verify·health 단계 실패에만 한정됩니다(`:361`).
  - 잡 타임아웃: 20분 안에 최악 경로(약 13분)가 들어갑니다.
- **concurrency:** 머지된 PR은 `deploy-production` 그룹, 머지 없이 닫힌 PR은 run별 그룹입니다. `cancel-in-progress: false`이고 대기 중 실행이 더 새 실행으로 대체되는 점은 문서화돼 있습니다.
- **시크릿:** `set -x`나 시크릿 echo가 없습니다. OCI 변수가 잡 전체 env인 이유는 문서화돼 있습니다.
- **README 1회성 절차의 기술적 정확성:**
  - client-side `kubectl apply`는 live strategy를 건드리지 않습니다.
  - scale 0 이후 apply는 replicas를 2로 되돌립니다.
  - `"rollingUpdate": null` patch는 유효합니다.
  - Recreate는 PDB에 막히지 않습니다.
- **`apply.sh ci`:** 테스트·배포 두 워크플로에서 호출되고, 옵션 화이트리스트가 있으며, `./gradlew help` 실패가 스텝 실패로 전파됩니다.
- **Dockerfile:** 셸 형식이지만 `exec`로 java가 PID 1이고, JVM 옵션이 `-jar` 앞에 있습니다.
- **`run`:** 함수 밖 `local` 버그가 고쳐졌습니다(`:267-268`). JDWP·JMX가 127.0.0.1에 바인딩되고 RMI 포트를 고정했습니다. actuator 오버라이드는 제거됐습니다.
- **시크릿 fail-fast와 프로파일 정합:**
  - dev·prod·slack-live의 `signing-secret`에 기본값이 없고, 필터가 미해결 플레이스홀더와 공백 값을 막습니다(`SlackRequestVerificationFilter.kt:159-167`).
  - actuator 경로는 필터 대상이 아닙니다(`:40-41,199-200`).
  - Kafka 컨슈머 설정(`max-poll-records: 5`, `max.poll.interval.ms: 300000`)이 세 프로파일에서 같습니다.
- **Updated 날짜:** 브랜치가 수정한 AGENTS.md 72개 중 70개가 올라가 있습니다(예외는 3절).
- **AGENTS.md 표본 대조 18개:** root, `.github`, `.github/workflows`, `k8s`, `k8s/route`, `gradle-config`, `db/migration`, `resources`, `application`(Dockerfile 행), `security`(필터 행 `:17`, 코드와 일치), `exception/meeting`, `standup/schema`, `command/entity`, `scripts`, `docs/wiki`, 테스트 `mention`·`parsers`, `repository/outbox`(`:96`의 V19 인덱스 일치). 불일치는 3절에 모두 적었습니다.

### 5. 미검증으로 남긴 것

- 클러스터 RBAC(`services/proxy` get, `pods/exec` create)와 API 서버에서 파드 IP로의 도달 가능성, 실제 롤아웃과 롤백 동작.
- actionlint와 shellcheck 실행(설치돼 있지 않음), `.github/workflows/AGENTS.md:29`의 "SC2086만 허용" 주장.
- `eclipse-temurin:25.0.4_7-jre-alpine` 이미지 안의 `wget`·`sh` 존재 — docker를 금지해 실행하지 못했습니다. alpine busybox 기반이라는 근거로 HIGH로 판단했습니다.
- `kubectl exec deploy/` 파드 선택 순서(P3) — kubectl 소스 지식에 근거합니다.
- gitleaks의 `kubernetes-secret-yaml` 규칙이 새 `stringData` + `YOUR_*` 값을 오탐하는지. 정규식을 읽어 보면 밑줄 때문에 매칭되지 않을 것으로 보이지만 실행하지 않았습니다.
- `dorny/paths-filter`의 `some-with-excludes`가 v4 기능이라는 문서 서술.
- 저장소 공개 여부와 러너 사양(P5).
- 컨텍스트 종료 시 Spring Kafka 컨테이너가 실제로 어떻게 멈추는지(S16(f)) — 문서화된 기본값에 근거합니다.
- ARM 노드에서 CPU request 250m로 브로커와 함께 기동했을 때 걸리는 시간 대 startupProbe 180초.
- `SLACK_CDC_TOPIC`이 없을 때 기동 실패인지 리터럴 바인딩인지(`k8s/AGENTS.md:48-51` 드리프트 판정의 전제).