# 개발 환경과 배포 파이프라인

_type: guide · updated: 2026-09-28_

> JDK 25 · Gradle 9.7.1 툴체인, 프로파일 배선, 로컬 실행 레시피, 수동 마이그레이션·시크릿 관례, `main` 머지 → OKE 배포 경로.

## 툴체인과 Gradle 프리셋

- **JDK 25 (Adoptium)는 필수다.** 루트 `build.gradle.kts`가 `toolchain { languageVersion = 25, vendor = ADOPTIUM }`과
  `sourceCompatibility`/`targetCompatibility = 25`를 고정하고, `settings.gradle.kts`의 foojay resolver가 없는 JDK를 받아
  온다. Kotlin 2.4.10 · Spring Boot 4.1.1 · ktlint 14.2.0의 원본은 루트 빌드의 `plugins` 블록이다.
- Gradle은 `gradle/wrapper/gradle-wrapper.properties`가 9.7.1로 고정한다. 항상 `./gradlew`를 쓴다.
- `gradle.properties`는 **생성물이며 git-ignored**다. `./gradle-config/apply.sh`가 `uname -s`로 OS를 판별해
  `gradle-config/gradle-{macos,linux}.properties` 중 하나를 루트로 복사하고, 프리셋이 없는 OS(Windows 등)와
  `apply.sh common`은 `gradle-common.properties`로 폴백한다. 공유 빌드 설정을 바꿀 때는 루트 파일이 아니라 프리셋을 고치고
  다시 적용한다. 재실행 시 남는 `gradle.properties.backup.*`도 git-ignored다. CI 테스트·배포 빌드는 같은 스크립트를
  `apply.sh ci`로 돌려 16GB 러너용 `gradle-ci.properties`(Gradle 3g · Kotlin 3g 데몬, `workers.max=4`, `testMaxHeap=2g`)를
  쓴다 — Linux 프리셋의 8g + 6g 데몬에 포크 테스트 JVM까지 얹으면 러너 메모리를 넘는다. 모듈 세 개의 테스트 JVM이 병렬로
  뜨므로 기본 `-Xmx4g`면 3g + 3g + 3 x 4g = 18GB라, CI는 `testMaxHeap=2g`로 12GB 이내에 둔다(2026-10-01, 로컬에서 2g로
  전체 스위트 통과 확인). `ci`는 OS 판별로는 절대 선택되지 않는다.
- 프리셋 안의 `kotlin.version` 키는 어떤 빌드 스크립트도 읽지 않는 메타데이터다. 플러그인 버전과 맞춰 두되 원본으로 믿지 않는다.
- ktlint 훅은 `./gradlew addKtlintCheckGitPreCommitHook`이 `.git/hooks/pre-commit`으로 설치한다. 스테이지된 `.kt`/`.kts`만
  검사하며, 검사 동안 **unstaged diff를 잠시 되돌렸다가 복원**한다(`git apply -R`). 포맷 규칙 원본은 `.editorconfig`(120 컬럼,
  wildcard import 허용)이고 자동 수정은 `./gradlew ktlintFormat`이다.

## 프로파일 매트릭스

`application.yaml`은 안전 기본값만 갖는다 — `spring.ai.mcp.server.enabled`와 `slack.app.mcp.enabled`를 끄고
`spring.task.scheduling.pool.size`를 4로 둔다. 환경별 배선은 각 `application-<profile>.yaml`이 소유한다.

| 프로파일 | DB / `spring.jpa.hibernate.ddl-auto` | `outbox-reading-strategy` / `event-publisher` | 인바운드 |
|---|---|---|---|
| `local` | MariaDB(orbstack) / `update` | `cdc` / `kafka` | Socket Mode |
| `slack-live` | MariaDB(orbstack) / `update` | `polling` / `application_event` | HTTP + 터널 |
| `dev` | MariaDB(`DATABASE_URL` 계열 env) / `update` | `cdc` / `kafka` | HTTP |
| `prod` | MariaDB(`SQL_DATABASE_URL` 계열 env) / **`none`** | `cdc` / `kafka` | HTTP, `server.port` 80 |

- 두 모드 키의 전체 이름은 `slack.app.mode.outbox-reading-strategy`와 `slack.app.mode.event-publisher`, CDC 토픽은
  `slack.app.mode.cdc.topic`이다. 코드 기본값(`AppConfig.Mode`)은 `POLLING` + `APPLICATION_EVENT`라서 키를 생략한 프로파일은
  CDC 토픽을 읽지 않고 아웃박스를 폴링한다. 모드별 빈 선택은 `configurations/conditions/Conditions.kt`의 Condition 네 개가
  맡는다 — 상세는 [events-and-outbox.md](events-and-outbox.md).
- `local`만 `slack.app.api.app-token`(Socket Mode app-level token)과 `slack.app.socket.meeting-command` /
  `standup-command`를 가진다. 수신기 `SocketModeReceiver`는 `@Profile("local")`이라 다른 프로파일에서는 빈 자체가 없다.
  루트 `AGENTS.md`와 `application/build.gradle.kts` 주석이 말하는 `socket` 프로파일은 존재하지 않는다 — 코드가 원본이다.
- AI 사이드카 설정 키(`slack.app.agent.sidecar.base-url` / `bearer-secret` / `request-timeout-seconds`)는 `local`과
  `prod` YAML에만 있다. 빈 자체(`AgentGateway`, `AgentConverseService`)는 `AgentConfiguration`이 프로파일과 무관하게
  만들고 `AppConfig`의 루프백 기본 URL을 쓰므로, 키가 없는 프로파일은 "미연결"이 아니라 "기본값으로 연결 시도"다.
  `prod`는 bearer만 `SIDECAR_BEARER_SECRET`으로 주입한다.
- `prod`는 전부 env 주입이다. `MCP_ENABLED` 하나가 `spring.ai.mcp.server.enabled`와 `slack.app.mcp.enabled`를 함께 켠다.
  CVE 수집은 `slack.app.cve.*`(`GITHUB_TOKEN`, `NVD_API_KEY`, `collector.*`, `notification.*`)와 `slack.app.ai.provider`
  (`AI_PROVIDER`, 기본 `noop`)로 조정하지만, **`slack.app.cve.enabled`는 어떤 프로파일도 켜지 않는다**(코드 기본 false) —
  켜려면 env 또는 `--slack.app.cve.enabled=true` 인자가 필요하다. `spring.lifecycle.timeout-per-shutdown-phase` 10s,
  `spring.kafka.consumer.isolation-level: read_committed`도 `prod` 전용이다.
- `spring.threads.virtual.enabled`는 네 프로파일 모두 on. `slack.app.api.signing-secret`은 `dev`·`prod`·`slack-live`에서
  `${SLACK_SIGNING_SECRET}`(기본값 없음)이다. 2026-09-28에 `slack-live`의 빈 기본값을 제거했다 — 그 프로파일은 터널로 실제
  Slack 앱에 연결되는데 빈 시크릿이면 필터가 검증을 끈다. 기본값이 없다는 것만으로는 fail-fast가 아니다(Boot 바인더는 미해결
  플레이스홀더를 리터럴로 보존) — 기동 거부는 `local` 외 프로파일에서 빈 값·미해결 값을 거절하는 애플리케이션 검증이 맡는다.
  `local`만 빈 기본값을 유지한다(Socket Mode라 HTTP 수신이 없다).
- actuator: `prod`와 `dev`는 `health,info,metrics,prometheus` + `show-details: when_authorized`(Spring Security가 없어 상세는 항상
  가려지므로 outbox 상태는 `/actuator/prometheus`의 게이지로 본다).
  `local`은 개발자 편의를 위해 `loggers`·`threaddump`·`mappings`·`conditions`까지 열고 `show-details: always`다 — 인증 없이 모든
  인터페이스에 바인드되므로 9000 포트를 네트워크에 노출하지 않는다. `heapdump`는 어느 프로파일에도 없다(덤프에 토큰이 실림).
  `run` 스크립트는 더 이상 `-Dmanagement.endpoints.web.exposure.include`로 YAML을 덮지 않는다.
- Kafka 컨슈머(`local`·`dev`·`prod`): `max-poll-records: 5`, `max.poll.interval.ms: 300000`. 한 번에 받은 5건을 300초 안에
  끝내야 하므로 레코드당 평균 예산은 60초다. PENDING 행 레코드 1건은 Slack 디스패치 1회이고(나머지 CDC 이벤트는 즉시 반환),
  디스패치 1회는 `RetryService` 기본 3회 시도에, 429의 `Retry-After`가 3초 이하면 그만큼 기다린 뒤 한 번 더(다시 최대 3회) 돈다.
  모든 Slack 호출(SDK와 `response_url`)은 SDK 클라이언트의 호출 전체 타임아웃 `SLACK_CALL_TIMEOUT`(6초,
  `ApplicationMessageDispatcher`)을 공유하므로 디스패치 1회는 2 × (3 × 6초 + 백오프) + 3초 ≈ 40초로 묶이고,
  렌더링의 프로필 조회(연결 3초 + 읽기 10초)를 더하면 레코드당 약 53초다. 60초 예산에서 남는 약 7초가 claim·갱신·완료 SQL 몫이다. 그 타임아웃·재시도 정책·위 두 값을 바꾸거나 리스너 안에 대기를 넣을 때는 이 계산부터 다시 한다. 배치가 초과되면 컨슈머가
  그룹에서 빠졌다가 재전달받는데, 이미 claim된 행은 PENDING이 아니므로 CDC 프로세서가 건너뛴다.
- Hikari 풀: 모든 프로파일 `maximum-pool-size: 20`. 회의 cancel/reschedule/참가자 추가는 바깥 interaction 트랜잭션과
  `isolatedWriteTemplate`의 `REQUIRES_NEW` 쓰기로 커넥션 두 개를 동시에 잡고, 릴레이 executor·스케줄러·CDC 리스너가 같은 풀을 쓴다.
  산정식: 동시 회의 interaction 수 × 2 + 릴레이 워커(`relayTaskExecutor` 4, `CallerRunsPolicy`면 제출 스레드 1 추가) + 스케줄러
  스레드(4) + CDC 리스너(1) + DB를 쓰는 AI 턴(`agentTurnExecutor`, `slack.app.agent.turns.max-concurrent` 4). 요청 스레드는 가상 스레드라 동시 interaction을
  막는 것은 스레드 수가 아니라 풀이며, 커넥션을 못 얻은 요청은 `connection-timeout` 뒤 실패한다. MariaDB `max_connections`는
  풀 × 파드 수를 담아야 한다: 레플리카 2 × 20 = 40, 롤링 업데이트 surge 중 60, 여기에 Debezium과 운영자 세션을 더한다.

## 로컬 실행 레시피

### A. Socket Mode (`local`) — 터널 없음

1. orbstack MariaDB(`code_companion`)와 3-broker Kafka + Debezium이 떠 있어야 한다. `local`은 CDC 모드라 Kafka 없이는 뜨지 않는다.
2. **별도의 개발용 Slack 앱**에서 Socket Mode를 켜고 `connections:write` 스코프의 app-level token을 발급한다. Socket Mode는
   앱 전체 설정이라 운영 앱에 켜면 Request URL이 비활성화된다.
3. `SLACK_API_TOKEN`, `SLACK_APP_TOKEN`을 env로 주고 `./gradlew :application:bootRun --args='--spring.profiles.active=local'`
   을 실행한다. 명령 이름이 `/meetup`·`/standup`이 아니면 `SLACK_MEETING_COMMAND` / `SLACK_STANDUP_COMMAND`로 매핑한다.
4. 로그의 `Slack Socket Mode receiver connected`가 성공 신호다.

### B. HTTP + 터널 (`slack-live`) — 실제 워크스페이스 e2e (구 `real`, 2026-09-21 개명)

- Kafka·Debezium 없이 orbstack MariaDB만 있으면 된다. `ngrok`/`cloudflared`로 9000 포트를 노출하고 Slack 앱의 Request URL
  세 개 — slash(`/api/slash/meet`, `/api/slash/standup`), Interactivity(`/api/slack/interaction`), Events(`/api/slack/events`)
  — 를 터널 주소로 잡는다. Events URL 검증(`url_verification`)은 앱이 먼저 떠 있어야 통과한다.
- `SLACK_API_TOKEN`, `SLACK_SIGNING_SECRET`, `DATABASE_USER_PWD`(모두 필수, 기본값 없음)로
  `bootRun --args='--spring.profiles.active=slack-live'`. 터널로 공개되는 포트라 actuator는 `/api/actuator/health`만 연다
  (2026-10-01: DB 비밀번호 기본값 제거, `metrics`·`info` 노출 제거). devtools의 restart classloader가 기동을 깨면 `--spring.devtools.restart.enabled=false`를 붙인다.
- 기능별 시나리오, 시간 단축용 `--slack.app.meeting.reminder.offsets-minutes` 류 오버라이드, DB 조회 스니펫은 git-ignored
  `RealTestSetup.md`(로컬 전용)에 있다.

### C. CDC 스택 — 언제 필요한가

- `outbox-reading-strategy: cdc`인 프로파일(`local`, `dev`, `prod`)을 실제로 돌릴 때만 필요하다. 아웃박스 → Debezium → Kafka →
  consumer 경로 자체를 검증하는 게 아니면 `slack-live`로 충분하다.
- `application/src/main/resources/cdc/docker-compose/`: MariaDB(binlog ROW/FULL, `mariadb/my.cnf`) + KRaft 3-broker Kafka
  + Debezium Connect + Kafka UI/Debezium UI. `docker-compose.yml` · `debezium/connect_mariadb.sh` · `my.cnf`의 `EXTERNAL_IP`,
  `ROOT_PASSWORD`, `SERVER_ID` 같은 SCREAMING_SNAKE 플레이스홀더를 채워야 뜬다. 앱은 호스트에 노출된 `EXTERNAL` 리스너 포트로
  붙는다(컨테이너 간 `PLAINTEXT` 포트·KRaft 컨트롤러 포트가 아님). 커넥터는 `table.include.list`로 `outbox_message`만 캡처하며
  `topic.prefix`가 붙은 결과 토픽 이름은 `slack.app.mode.cdc.topic`과 일치해야 한다.
- `application-local.yaml`의 `spring.kafka.bootstrap-servers`는 이 compose가 아니라 별도 `~/infra` Kafka를 가리킨다. compose를
  쓰려면 포트를 바꾼다(cdc README "Connecting the Application").
- `cdc/k8s/yamls/mariadb/`는 클러스터용 MariaDB master/slave StatefulSet(1+2, `database` 네임스페이스, Pod ordinal 기반
  server-id, 복제 설정 Job)이다. 스토리지 클래스와 비밀번호가 플레이스홀더이고 `mariadb-headless` 이름이 세 파일에 걸쳐 맞물린다.
- **시간대:** `cdc/`의 MariaDB(compose·k8s 모두)는 `TZ`·`default_time_zone`을 지정하지 않아 UTC로 돈다. 앱 파드는
  `-Duser.timezone=Asia/Seoul` + `/etc/localtime` 마운트로 서울 시간이다. 아웃박스 시각(`updated_at` 등)은 이제 애플리케이션
  시계(`Clock`)로 기록되므로 DB 세션 시간대(`NOW()`, `CURRENT_TIMESTAMP`)에 기대는 쿼리·운영 비교를 하지 않는다. 운영 DB의
  실제 값은 `SELECT @@session.time_zone, NOW()`로 확인한다(미확인).

## 데이터베이스와 마이그레이션

- 런타임 DB는 MariaDB(`org.mariadb.jdbc.Driver`, 네 프로파일 모두). H2는 `infrastructure`에 `runtimeOnly`로만 있고 실제로는
  **테스트에서만** 쓰인다 — `infrastructure/src/test/resources/application.yaml`에 datasource가 없어 Boot가 임베디드 H2를 자동
  구성한다. README의 "H2 (local & tests)"는 `local` 프로파일에는 맞지 않는다.
- **Flyway는 설치되어 있지 않다.** 어떤 빌드 스크립트에도 flyway 의존성이 없고, 프로파일 YAML에도 `spring.flyway.*` 키가 없다
  (무효 키였던 `enabled: false`는 2026-09-21에 제거). `db/migration/V*.sql`은 **사람이 수동으로 적용**한다.
- 스키마 기동 방식: `local`/`slack-live`/`dev`는 `ddl-auto: update`로 Hibernate가 베이스 테이블을 만들고 `V*` 스크립트는 그 위에 얹는
  증분 패치다. `prod`는 `ddl-auto: none` + `spring.jpa.generate-ddl: false` — 배포 전에 새 마이그레이션을 운영 DB에 직접 적용한다.
- 번호 규칙 `V<n>__<snake_case>.sql`. 현재 최고 번호는 **V22**(2026-09-28 작업 트리; 마지막으로 fetch한 `origin/main`은 V17), 다음은 **V23**. 번호를 정하기
  전에 `git ls-tree -r --name-only origin/main | grep db/migration`으로 origin 선점을 확인한다. 적용된 스크립트는 수정·재번호 금지.
- 새 엔티티는 JPA 스키마 클래스(`infrastructure/repository/*/schema/`)와 마이그레이션을 **둘 다** 추가한다. H2/`ddl-auto` 테스트는
  MariaDB 전용 문법 오류를 잡지 못하므로 `slack-live` DB에 한 번 적용해 본다.

## 시크릿 취급

- 커밋된 프로파일 YAML은 전부 `${ENV_VAR}` 플레이스홀더다. `application-local.yaml`은 **git-tracked**이므로 로컬 테스트용 실제
  토큰을 채워 두었다면 그 hunk를 절대 스테이징하지 않는다(`git add -p`). 명시적 요청이 없으면 작업 트리 값을 되돌리지도 않는다.
- git-ignored 로컬 파일: `gradle.properties`(+`.backup.*`), `logs/`, `*.hprof`, `.omc/`, `.claude/`, 그리고 로컬 작업 문서
  `RealTestSetup.md` · `Handoff.md` · `Refactor.md` · `STYLE_GUIDE.local.md` · `CveBotPlan.md`.
- k8s: `k8s/secret.yaml`(`stringData:` — DB URL/계정, `SLACK_API_TOKEN`, `SLACK_SIGNING_SECRET`)과 `k8s/configmap.yaml`(격리 수준,
  타임아웃, 배치 크기, `KAFKA_BOOTSTRAP_SERVERS`, `SLACK_CDC_TOPIC`)은 플레이스홀더 템플릿이다. 실제 값은 클러스터에만 있고, 새 env는 `application-prod.yaml`의 `${VAR}`와 매니페스트
  키 등록이 한 쌍이다.
- gitleaks: `.gitleaks.toml`은 기본 룰을 확장하고 `cdc/k8s/yamls/mariadb/mariadb-config.yaml`만 경로 allowlist한다(샘플 Secret의
  플레이스홀더가 kubernetes-secret 룰에 걸리기 때문). 실제 유출은 allowlist가 아니라 회전 + 히스토리 재작성으로 처리한다. 테스트
  픽스처 토큰은 `xoxb-test…`처럼 룰에 안 걸리는 가짜를 유지한다.
- `scripts/mcp-smoke.sh`는 `MCP_SIGNING_SECRET` env를 요구하며 `slack.app.mcp.signing-secret`과 같아야 한다. 토큰 포맷
  (`ScopedTurnTokenCodec`)이 바뀌면 스크립트도 같은 커밋에서 바꾼다.

## CI/CD 요약

| 워크플로 | 트리거 | 하는 일 |
|---|---|---|
| `lint.yaml` | `feature/*` · `feat/*` · `features/*` · `dependabot/**` push, `main` 대상 모든 PR | `./gradlew ktlintCheck` |
| `simple_test_action.yaml` | 같은 트리거 | `apply.sh ci` 후 **변경 모듈과 그 의존 모듈** 테스트, gradle·`gradle-config`·`domain` 변경 시 전체 `test`; 실패 시 `build-reports.zip` |
| `security_check.yaml` | `main` push/PR, 매주 월 09:00 KST, 수동 | CodeQL(`java-kotlin`, 실제 컴파일), dependency-submission + PR review(`high` 이상 실패), gitleaks |
| `deploy_action.yaml` | `main`으로 **머지된** PR | 전체 `build` → 멀티 아키 이미지 → Harbor push → `envsubst '${IMAGE_NAME}'`로 `k8s/deployment.yaml` 적용(OKE) → rollout + 클러스터 내부 readiness 확인(service proxy, 실패 시 `kubectl exec … wget`) → 배포 단계 실패 시 `rollout undo` |

- 배포 빌드는 테스트·`ktlintCheck`를 포함한 전체 `./gradlew build -PjarName=…`(잡 타임아웃 40분), 이미지는 QEMU/Buildx로
  `linux/amd64,linux/arm64`. 워크플로는 `deploy-production` concurrency 그룹으로 직렬화된다(취소 없음, 대기 중인 실행은 더 새 머지로 대체될 수 있음).
  머지되지 않고 닫힌 PR은 실행마다 별도 그룹(`deploy-skip-<run_id>`)을 받아 대기 중인 배포를 밀어내지 않는다. 배포 빌드는
  `apply.sh ci`가 검증용 `./gradlew help`로 띄운 데몬을 그대로 재사용한다.
- 롤백: 배포 전에 Deployment의 `deployment.kubernetes.io/revision`을 기록하고, 실패 시 **이번 실행이 새 리비전을 만들었을 때만**
  `kubectl rollout undo --to-revision=<기록값>`으로 파드 템플릿 전체(이미지·프로브·리소스)를 되돌린다. 같은 SHA 재배포처럼 리비전이
  안 바뀌었으면 되돌리지 않고 그렇게 로그를 남긴다(예전 `kubectl set image`는 매니페스트 변경을 못 되돌리고 거짓 성공 로그를 냈다).
- Dependabot: gradle(`/`) · github-actions(`/`) · docker(`/application`) · docker-compose(CDC compose 디렉터리) 네 생태계, 매주 월 09:00 KST, 커밋 프리픽스 `chore :`.
  Kotlin 플러그인 3종 · Spring · 테스트 라이브러리는 그룹으로 묶여 한 PR로 온다. 루트 빌드의 버전은 `extra["x"] = "…"` 형태여야
  Dependabot이 읽는다. `dependabot/**` 브랜치 패턴이 lint·test 워크플로에 있어야 그 PR이 검증된다.
- 워크플로별 편집 체크리스트(actionlint, gitleaks 로컬 재현, `envsubst` 렌더 확인)는
  [`.github/AGENTS.md`](../../.github/AGENTS.md)가 상세하다.

## 운영 토폴로지와 `run` 스크립트

- 이미지: `application/Dockerfile` — `eclipse-temurin` 25 JRE alpine, 빌드 인자 `PROFILES` 기본 `prod`, `-Duser.timezone=Asia/Seoul`.
  `./build/libs/$JAR_FILE_NAME.jar`를 복사하므로 CI의 `-PjarName`과 `JAR_FILE_NAME`이 일치해야 한다(`application/build.gradle.kts`가
  `jarName` 프로퍼티로 bootJar 파일명을 바꾼다).
- k8s(`application/src/main/resources/k8s/`): Deployment 2 replicas, 컨테이너 포트 80, `envFrom`으로 ConfigMap + Secret 주입,
  `imagePullSecrets: dockercred`, `/etc/localtime`을 `hostPath`로 마운트(노드에 zoneinfo가 없으면 기동 실패), PDB `minAvailable: 1`,
  ClusterIP Service, 라우팅은 HTTPRoute 또는 NGINX Ingress 중 택일. 클러스터가 ARM 인스턴스라 멀티 아키 이미지가 필수다.
- AI 사이드카는 매니페스트에 없다. 켜려면 `restartPolicy: Always`인 native sidecar(`initContainers`)로 추가하고 `BEARER_SECRET`을
  `slack.app.agent.sidecar.bearer-secret`과 맞춘다(`k8s/README.md`).
- 헬스: 배포 워크플로는 공개 URL을 쓰지 않는다. 2026-09-28 `https://api.notypie.dev` 실측에서 존재하지 않는 경로까지 포함해
  조사한 모든 경로가 GET/POST 모두 `401`(`WWW-Authenticate: Bearer`, `server: istio-envoy`)을 돌려줬고, `GET /actuator/health`만
  이 앱의 형식이 아닌 JSON `404`를 돌려줬다. 즉 공개 호스트 앞에 이 저장소 밖의 bearer 인증 계층이 있고, 그 계층이 어떤 경로를
  앱으로 넘기는지는 밖에서 알 수 없다(게이트웨이 운영자가 확인할 일). 401은 이 앱의 Slack 서명 필터가 응답했다는 증거가 아니다.
  그래서 배포 잡은 클러스터 안에서 `/actuator/health/readiness`를 약 2분간 폴링해 `jq -e '.status == "UP"'`을 요구한다. 매 시도마다
  API 서버 service proxy(`kubectl get --raw /api/v1/namespaces/api-service/services/code-companion-svc:80/proxy/...`, `services/proxy`
  `get` 권한, `resourceNames`를 쓰면 이름은 `code-companion-svc:80`)를 먼저, 실패하면 `kubectl exec deploy/code-companion-deploy
  -c code-companion-deploy -- wget -qO- http://localhost:80/...`(`pods/exec` `create` 권한)를 시도하고 어느 쪽이 응답했는지 로그에
  남긴다. 집계 `/actuator/health`는 로그용으로 한 번만 읽는다 — 아웃박스 헬스 인디케이터가 릴리스와 무관하게 DOWN일 수 있어서다.
  롤백은 apply·rollout·verify·health 단계가 실패했을 때만 돌고, 배포 전 백업과 비교해 파드 템플릿 해시나 리비전이 달라졌으면
  `rollout undo`한다(리비전 주석은 컨트롤러가 나중에 쓰므로 템플릿을 비교한다. 조회가 3번 실패하면 비교 없이 undo). 샘플 라우트(`k8s/route/`)는 `/api/slack`·`/api/slash` 접두만 넘긴다 —
  `/actuator`·`/api/actuator`(dev·local·slack-live)·`/mcp`는 무인증이라 외부로 라우팅하면 안 된다. prod의 actuator base path는 `application-prod.yaml`에 `/actuator`로 고정이다.
- 파드 종료 예산: `preStop` 5초 sleep → Spring graceful shutdown(단계당 10초) ⊂ `terminationGracePeriodSeconds` 45초. 메모리는
  힙 1Gi(limit 2Gi의 50%) + 비힙을 덮도록 request 1536Mi. 기본 롤링 업데이트(surge 1)라 롤아웃 중에는 요청 기준 3 × 1536Mi = 4.5Gi가
  동시에 스케줄돼야 한다(`kubectl describe nodes`의 Allocated resources로 확인; 부족하면 surge 파드가 Pending → 타임아웃 → 롤백). 컨테이너는 80 포트 때문에 아직 root로 돈다(`allowPrivilegeEscalation: false`만 적용).
- `run`은 **빌드된 jar를 손으로 띄우는** 스크립트다(`./run [-e local|dev|prod] <jar>`). 환경별 힙·GC(local/dev G1, prod ZGC) ·
  JDWP 디버그 포트(기본 5005, 인증 없음, `127.0.0.1`에만 바인드) · devtools · prod 확인 프롬프트 · JMX(기본 9010, 인증 없음,
  `127.0.0.1`에만 바인드, RMI 포트를 레지스트리 포트와 같게 고정해 포트 하나짜리 SSH 터널로 접근)를 붙이고 `-Dspring.profiles.active`를
  세팅한다. `slack-live`는 받지 않으므로 `bootRun --args`로 띄운다. 컨테이너 배포는 이 스크립트를 쓰지 않는다.

## 함정

- **docs-only 머지는 배포되지 않는다.** deploy 트리거와 lint/test의 **push** 트리거는 `paths`에 `!**/*.md`가 있다(`pull_request`
  트리거는 아래 이유로 필터가 없다). 반대로 새 최상위 소스 디렉터리는 모든 필터(+ `security_check.yaml`의 `source`)에 추가하기 전까지
  CI가 조용히 건너뛴다.
- lint·test는 `feature/*` 계열 push **와 `main`으로 가는 모든 PR**에서 돈다(2026-09-22부터). 배포 빌드도 `-x test` 없이
  전체 `build`를 돌린다. 단 GitHub 브랜치 보호의 required check 등록은 레포 밖 설정이라, 체크가 pending인 채로 머지되면
  배포 빌드가 마지막 게이트가 된다. `pull_request` 트리거에는 일부러 `paths` 필터가 없다 — required check가 트리거조차
  안 되면(docs-only PR) 영원히 pending이라 머지가 막히기 때문. docs-only PR은 test 잡이 모듈 0개로 수 초 만에 끝나지만 lint 잡은
  전체 `ktlintCheck`를 돈다. lint·test는 같은 ref의 이전 실행을 취소한다(`concurrency`).
- test 워크플로는 변경 모듈에 **의존하는** 모듈까지 돌린다: `domain`·gradle·`gradle-config` 변경 → 전체, `infrastructure` → infrastructure + application,
  `application` → application만.
- `src/main/resources` 아래는 **전부 jar에 들어간다.** `processResources`에 exclude가 없어 `AGENTS.md`, `k8s/`·`cdc/` README와
  매니페스트, `application-local.yaml`까지 `BOOT-INF/classes/`에 포함된다(기존 빌드 산출물로 확인). 거기에 실제 값을 두지 않는 이유다.
- `deploy_action.yaml`의 `dorny/paths-filter@v4` 블록에는 `!` 패턴을 넣지 않는다(무효). 마크다운 제외는 워크플로 레벨 `paths`에만.

## 근거

- `build.gradle.kts`, `settings.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `.editorconfig`, `.gitignore`, `run`
- `gradle-config/apply.sh`, `gradle-config/gradle-{macos,linux,common,ci}.properties`, `gradle-config/README.md`
- `application/build.gradle.kts`, `infrastructure/build.gradle.kts`, `application/Dockerfile`
- `application/src/main/resources/application.yaml`, `application-{local,slack-live,dev,prod}.yaml`, `db/migration/V1__…`~`V22__…`,
  `k8s/**`, `cdc/**`; `infrastructure/src/test/resources/application.yaml`
- `application/src/main/kotlin/dev/notypie/application/configurations/AppConfig.kt`, `CveConfiguration.kt`,
  `conditions/Conditions.kt`, `socket/SocketModeReceiver.kt`, `security/SlackRequestVerificationFilter.kt`
- `.github/workflows/{lint,simple_test_action,security_check,deploy_action}.yaml`, `.github/dependabot.yml`, `.gitleaks.toml`
- `scripts/mcp-smoke.sh`, `README.md`, git-ignored `RealTestSetup.md`

## 관련 페이지

- [architecture-overview.md](architecture-overview.md) — 모듈 책임과 요청 흐름
- [events-and-outbox.md](events-and-outbox.md) — POLLING/CDC 릴레이와 퍼블리셔 모드의 동작
- [testing-guide.md](testing-guide.md) — EmbeddedKafka + H2 통합 테스트 실행
- [decisions.md](decisions.md), [history.md](history.md)
- [`.github/AGENTS.md`](../../.github/AGENTS.md), [`gradle-config/AGENTS.md`](../../gradle-config/AGENTS.md),
  [`scripts/AGENTS.md`](../../scripts/AGENTS.md),
  [`application/src/main/resources/AGENTS.md`](../../application/src/main/resources/AGENTS.md)
