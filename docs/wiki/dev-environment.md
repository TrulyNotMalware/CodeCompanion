# 개발 환경과 배포 파이프라인

_type: guide · updated: 2026-09-21_

> JDK 25 · Gradle 9.5.1 툴체인, 프로파일 배선, 로컬 실행 레시피, 수동 마이그레이션·시크릿 관례, `main` 머지 → OKE 배포 경로.

## 툴체인과 Gradle 프리셋

- **JDK 25 (Adoptium)는 필수다.** 루트 `build.gradle.kts`가 `toolchain { languageVersion = 25, vendor = ADOPTIUM }`과
  `sourceCompatibility`/`targetCompatibility = 25`를 고정하고, `settings.gradle.kts`의 foojay resolver가 없는 JDK를 받아
  온다. Kotlin 2.4.0 · Spring Boot 4.1.0 · ktlint 14.2.0의 원본은 루트 빌드의 `plugins` 블록이다.
- Gradle은 `gradle/wrapper/gradle-wrapper.properties`가 9.5.1로 고정한다. 항상 `./gradlew`를 쓴다.
- `gradle.properties`는 **생성물이며 git-ignored**다. `./gradle-config/apply.sh`가 `uname -s`로 OS를 판별해
  `gradle-config/gradle-{macos,linux}.properties` 중 하나를 루트로 복사하고, 프리셋이 없는 OS(Windows 등)와
  `apply.sh common`은 `gradle-common.properties`로 폴백한다. 공유 빌드 설정을 바꿀 때는 루트 파일이 아니라 프리셋을 고치고
  다시 적용한다. 재실행 시 남는 `gradle.properties.backup.*`도 git-ignored다. CI 테스트 워크플로도 같은 스크립트를 먼저 돌린다.
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
- `spring.threads.virtual.enabled`는 네 프로파일 모두 on. `slack.app.api.signing-secret`이 비면
  `SlackRequestVerificationFilter`가 경고만 남기고 서명 검증을 끈다 — `slack-live`는 의도적으로 선택, `dev`는 키가 아예 없다.

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
- `SLACK_API_TOKEN`(선택 `SLACK_SIGNING_SECRET`)으로 `bootRun --args='--spring.profiles.active=slack-live'`. 헬스는
  `/api/actuator/health`. devtools의 restart classloader가 기동을 깨면 `--spring.devtools.restart.enabled=false`를 붙인다.
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

## 데이터베이스와 마이그레이션

- 런타임 DB는 MariaDB(`org.mariadb.jdbc.Driver`, 네 프로파일 모두). H2는 `infrastructure`에 `runtimeOnly`로만 있고 실제로는
  **테스트에서만** 쓰인다 — `infrastructure/src/test/resources/application.yaml`에 datasource가 없어 Boot가 임베디드 H2를 자동
  구성한다. README의 "H2 (local & tests)"는 `local` 프로파일에는 맞지 않는다.
- **Flyway는 설치되어 있지 않다.** 어떤 빌드 스크립트에도 flyway 의존성이 없고, 프로파일 YAML에도 `spring.flyway.*` 키가 없다
  (무효 키였던 `enabled: false`는 2026-09-21에 제거). `db/migration/V*.sql`은 **사람이 수동으로 적용**한다.
- 스키마 기동 방식: `local`/`slack-live`/`dev`는 `ddl-auto: update`로 Hibernate가 베이스 테이블을 만들고 `V*` 스크립트는 그 위에 얹는
  증분 패치다. `prod`는 `ddl-auto: none` + `spring.jpa.generate-ddl: false` — 배포 전에 새 마이그레이션을 운영 DB에 직접 적용한다.
- 번호 규칙 `V<n>__<snake_case>.sql`. 현재 최고 번호는 **V17**(로컬 트리와 `origin/main` 모두), 다음은 **V18**. 번호를 정하기
  전에 `git ls-tree -r --name-only origin/main | grep db/migration`으로 origin 선점을 확인한다. 적용된 스크립트는 수정·재번호 금지.
- 새 엔티티는 JPA 스키마 클래스(`infrastructure/repository/*/schema/`)와 마이그레이션을 **둘 다** 추가한다. H2/`ddl-auto` 테스트는
  MariaDB 전용 문법 오류를 잡지 못하므로 `slack-live` DB에 한 번 적용해 본다.

## 시크릿 취급

- 커밋된 프로파일 YAML은 전부 `${ENV_VAR}` 플레이스홀더다. `application-local.yaml`은 **git-tracked**이므로 로컬 테스트용 실제
  토큰을 채워 두었다면 그 hunk를 절대 스테이징하지 않는다(`git add -p`). 명시적 요청이 없으면 작업 트리 값을 되돌리지도 않는다.
- git-ignored 로컬 파일: `gradle.properties`(+`.backup.*`), `logs/`, `*.hprof`, `.omc/`, `.claude/`, 그리고 로컬 작업 문서
  `RealTestSetup.md` · `Handoff.md` · `Refactor.md` · `STYLE_GUIDE.local.md` · `CveBotPlan.md`.
- k8s: `k8s/secret.yaml`(DB URL/계정, `SLACK_API_TOKEN`)과 `k8s/configmap.yaml`(격리 수준, 타임아웃, `ACTUATOR_BASE_PATH`,
  배치 크기)은 플레이스홀더 템플릿이다. 실제 값은 클러스터에만 있고, 새 env는 `application-prod.yaml`의 `${VAR}`와 매니페스트
  키 등록이 한 쌍이다.
- gitleaks: `.gitleaks.toml`은 기본 룰을 확장하고 `cdc/k8s/yamls/mariadb/mariadb-config.yaml`만 경로 allowlist한다(샘플 Secret의
  플레이스홀더가 kubernetes-secret 룰에 걸리기 때문). 실제 유출은 allowlist가 아니라 회전 + 히스토리 재작성으로 처리한다. 테스트
  픽스처 토큰은 `xoxb-test…`처럼 룰에 안 걸리는 가짜를 유지한다.
- `scripts/mcp-smoke.sh`는 `MCP_SIGNING_SECRET` env를 요구하며 `slack.app.mcp.signing-secret`과 같아야 한다. 토큰 포맷
  (`ScopedTurnTokenCodec`)이 바뀌면 스크립트도 같은 커밋에서 바꾼다.

## CI/CD 요약

| 워크플로 | 트리거 | 하는 일 |
|---|---|---|
| `lint.yaml` | `feature/*` · `feat/*` · `features/*` · `dependabot/**` push | `./gradlew ktlintCheck` |
| `simple_test_action.yaml` | 같은 브랜치 push | `apply.sh` 후 **변경된 모듈만** 테스트, gradle 파일 변경 시 전체 `test`; 실패 시 `build-reports.zip` |
| `security_check.yaml` | `main` push/PR, 매주 월 09:00 KST, 수동 | CodeQL(`java-kotlin`, 실제 컴파일), dependency-submission + PR review(`high` 이상 실패), gitleaks |
| `deploy_action.yaml` | `main`으로 **머지된** PR | jar 빌드 → 멀티 아키 이미지 → Harbor push → `envsubst`로 `k8s/deployment.yaml` 적용(OKE) → rollout + 헬스 체크 → 실패 시 롤백 |

- 배포 빌드는 `:application:build -x test -PjarName=…`, 이미지는 QEMU/Buildx로 `linux/amd64,linux/arm64`. 배포 전에 현재 이미지를
  기록해 두고 실패하면 `kubectl set image`로 되돌린다 — `Backup current deployment` 단계를 지우면 롤백이 조용히 no-op이 된다.
- Dependabot: gradle(`/`) · github-actions(`/`) · docker(`/application`) 세 생태계, 매주 월 09:00 KST, 커밋 프리픽스 `chore :`.
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
- 헬스: 배포 워크플로는 `K8S_APP_INGRESS_HOST` + `HEALTH_CHECK_ENDPOINT`(`/api/slack/actuator/health`)를 10회 재시도한다. 저장소의
  `configmap.yaml`은 `ACTUATOR_BASE_PATH`를 `/actuator`로 두므로 `/api/slack` 접두는 저장소 밖 게이트웨이 설정에 달려 있다(미확인).
- `run`은 **빌드된 jar를 손으로 띄우는** 스크립트다(`./run [-e local|dev|prod] <jar>`). 환경별 힙·GC(local/dev G1, prod ZGC) ·
  JDWP 디버그 포트(기본 5005) · devtools · prod 확인 프롬프트 · JMX(기본 9010, 인증 없음)를 붙이고 `-Dspring.profiles.active`를
  세팅한다. `slack-live`는 받지 않으므로 `bootRun --args`로 띄운다. 컨테이너 배포는 이 스크립트를 쓰지 않는다.

## 함정

- **docs-only 머지는 배포되지 않는다.** lint/test/deploy 모두 `paths`에 `!**/*.md`가 있다. 반대로 새 최상위 소스 디렉터리는 모든
  필터(+ `security_check.yaml`의 `source`)에 추가하기 전까지 CI가 조용히 건너뛴다.
- lint·test는 `feature/*` 계열 push에서만 돈다. `main`으로 가는 PR 자체는 `security_check`만 트리거하고 배포 빌드는 `-x test`다.
  즉 `hotfix/*` 같은 이름의 브랜치는 테스트 없이 머지·배포될 수 있다.
- `src/main/resources` 아래는 **전부 jar에 들어간다.** `processResources`에 exclude가 없어 `AGENTS.md`, `k8s/`·`cdc/` README와
  매니페스트, `application-local.yaml`까지 `BOOT-INF/classes/`에 포함된다(기존 빌드 산출물로 확인). 거기에 실제 값을 두지 않는 이유다.
- `deploy_action.yaml`의 `dorny/paths-filter@v3` 블록에는 `!` 패턴을 넣지 않는다(무효). 마크다운 제외는 워크플로 레벨 `paths`에만.

## 근거

- `build.gradle.kts`, `settings.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `.editorconfig`, `.gitignore`, `run`
- `gradle-config/apply.sh`, `gradle-config/gradle-{macos,linux,common}.properties`, `gradle-config/README.md`
- `application/build.gradle.kts`, `infrastructure/build.gradle.kts`, `application/Dockerfile`
- `application/src/main/resources/application.yaml`, `application-{local,real,dev,prod}.yaml`, `db/migration/V1__…`~`V17__…`,
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
