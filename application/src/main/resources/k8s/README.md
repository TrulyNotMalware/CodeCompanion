# Kubernetes Deployment Manifests

This directory contains Kubernetes manifests for deploying the Code Companion application.

## Directory Structure

```
k8s/
├── configmap.yaml        # Application configuration
├── deployment.yaml       # Deployment and PodDisruptionBudget
├── secret.yaml          # Sensitive data (credentials)
├── service.yaml         # ClusterIP Service
└── route/
    ├── httpRoute.yaml   # Gateway API HTTPRoute
    └── ingress.yaml     # NGINX Ingress
```

## Components

### ConfigMap (configmap.yaml)
Contains application configuration including:
- Database connection settings (isolation level, timeouts)
- Hibernate batch size settings
- Kafka bootstrap servers (placeholder) and the CDC topic (`SLACK_CDC_TOPIC`, `cdc.code_companion.outbox_message`)

### Secret (secret.yaml)
Stores sensitive information that needs to be configured:
- Database connection URL, username, and password
- Slack API token and Slack signing secret (`SLACK_SIGNING_SECRET`; the app refuses to start without it)

Values sit under `stringData:`, so write them as plain text — the API server base64-encodes them.

**Important:** Replace placeholder values with actual credentials before deployment.

### Deployment (deployment.yaml)
Defines the application deployment with:
- 2 replicas for high availability
- Container port 80
- References to ConfigMap and Secret for environment variables
- `imagePullSecrets: dockercred` for the private registry
- Timezone configuration (Asia/Seoul) via a `hostPath` mount of `/etc/localtime`
- Startup / readiness / liveness probes on `/actuator/health/liveness` and `/actuator/health/readiness`
  (startup allows 3 minutes). A Pod that never turns Ready usually failed property binding or cannot reach
  the DB/Kafka: check `kubectl logs` before touching the probes
- Resources: 250m CPU / 1536Mi memory requested, 2Gi memory limit (the JVM heap is 50% of the limit; the
  request covers heap plus non-heap memory)
- Strategy: `Recreate` for this release only; a follow-up PR deletes the block once every Pod runs V20+ (see
  "One-time" below). A rollout stops both Pods before starting new ones, so every deploy, a rollback included, is an outage: the old Pods' shutdown (normally seconds, at most
  the 180s grace) plus the new Pod's startup (at most the startup probe's 180s) and first readiness check (10s),
  about 6 minutes in the worst case plus scheduling and image pull. It needs only 2 × 1536Mi of requests. Once
  that block is removed, the default rolling update briefly runs 3 Pods (2 replicas + 1 surge) and needs
  3 × 1536Mi = 4.5Gi of requestable memory at once; see Prerequisites
- Shutdown: a 5s `preStop` sleep, then Spring's graceful shutdown (scheduler and web server phases 10s each, the
  Kafka listener phase up to one dispatch, 46s, so a record in hand finishes), three Kafka producer closes (5s each)
  and the executor waits (relay up to one dispatch, 46s; AI turns 20s; default 10s): 162s within a 180s
  `terminationGracePeriodSeconds`
- Container `securityContext` with `allowPrivilegeEscalation: false` (the container still runs as root to bind port 80)
- PodDisruptionBudget ensuring at least 1 pod remains available during disruptions

### Service (service.yaml)
Creates a ClusterIP service exposing the application on port 80 internally.

### Routing Options

The application supports two routing mechanisms:

#### 1. HTTPRoute (route/httpRoute.yaml)
Uses Kubernetes Gateway API for advanced routing capabilities. Configure:
- `namespace`: Your target namespace
- `parentRefs`: Your gateway name and namespace
- `hostnames`: Your domain

#### 2. Ingress (route/ingress.yaml)
Uses NGINX Ingress Controller with:
- Automatic TLS certificate management via cert-manager
- TLS termination
- Host-based routing

Configure:
- `host`: Your domain
- `cert-manager.io/cluster-issuer`: Your cluster issuer name
- `secretName`: TLS secret name

Both samples forward only the `/api/slack` and `/api/slash` prefixes (the Slack endpoints). Never route
`/actuator`, `/api/actuator` (the base path of the dev, local and slack-live profiles) or `/mcp` publicly: they
are served on the application port without authentication.

## Deployment Steps

Every command below targets the `api-service` namespace explicitly — the deploy workflow does the same. Only
`route/httpRoute.yaml` sets `metadata.namespace` (placeholder `your-namespace`): set it to `api-service` before
step 4, or `kubectl apply -n api-service` rejects the mismatch.

1. **Configure Secret**
   ```bash
   # Edit secret.yaml with your actual credentials (plain text under stringData)
   ```

2. **Apply ConfigMap and Secret**
   ```bash
   kubectl apply -n api-service -f configmap.yaml
   kubectl apply -n api-service -f secret.yaml
   ```

3. **Deploy Application**
   ```bash
   kubectl apply -n api-service -f deployment.yaml
   kubectl apply -n api-service -f service.yaml
   ```

4. **Set up Routing** (Choose one)

   For Gateway API:
   ```bash
   kubectl apply -n api-service -f route/httpRoute.yaml
   ```

   For NGINX Ingress:
   ```bash
   kubectl apply -n api-service -f route/ingress.yaml
   ```

## One-time: a release that must not overlap the previous one

The release that introduced outbox claim tokens (`attempt_count`, migration V20) cannot overlap its predecessor:
the old Pods write outbox timestamps with the database clock and re-dispatch `IN_PROGRESS` rows they do not own.
That release therefore ships `deployment.yaml` with `strategy.type: Recreate`, and no manual `kubectl patch` is
needed: the workflow's `kubectl apply` sets the strategy, and its rollout stops every old Pod before the first new
one starts. A `rollout undo` does the same, because it restores only the pod template and the strategy is not part
of it. `Recreate` deletes the old Pods through the ReplicaSet, not the eviction API, so the PodDisruptionBudget does
not block it. While the block is in the manifest, every deploy is an outage (see Deployment above); the workflow's
450s rollout timeout covers the worst case.

That release ships V18 through V23 at once (`main` was at V17), and the script numbers are not the apply order:
V21 goes last. Production runs `ddl-auto: none` and nothing applies the scripts automatically, so work through
this list by hand (`../db/migration/AGENTS.md` keeps the same order):

1. **V18** — first run the duplicate check in its header and delete the extra `meeting_participants` rows (keep
   the lowest `id`); the new unique key fails otherwise:
   ```sql
   SELECT meeting_id, user_id, COUNT(*) FROM meeting_participants
   GROUP BY meeting_id, user_id HAVING COUNT(*) > 1;
   ```
   Then apply V18.
2. **V19** (outbox status indexes) and **V23** (outbox payload `MEDIUMTEXT`). For V23, measure the table and try
   the online form first, as its header says; if the server rejects it and copying the table would block outbox
   writes too long, apply V23 after step 5 instead, once the release's retention purge has shrunk the table.
3. **V20**, then **V22** (the claim token and the send budget).

   Steps 1–3 only add columns with defaults, indexes and a wider type, which the running pre-V20 Pods never depend
   on, so apply them while the previous release still serves. The new release needs V18, V20 and V22, and
   readiness does not check the schema: without V18 every `meetings` query fails on the unknown `version` column,
   and without V20/V22 every outbox claim fails and no Slack message goes out, while the Pods stay Ready and the
   workflow reports success. Confirm before merging:
   ```sql
   SHOW COLUMNS FROM meetings LIKE 'version';
   SHOW COLUMNS FROM outbox_message WHERE Field IN ('attempt_count', 'send_count');
   ```
4. **Stop the old Pods, then deploy** — merge and watch the workflow. Its `Recreate` rollout does both, in that
   order, and a rollback it performs stops the new Pods first the same way.
5. **V21** — only once every Pod runs the new release: the workflow finished and `kubectl get pods -n api-service
   -l app=code-companion-deploy` lists only Pods of the new ReplicaSet. An older binary's reschedule moves only
   `start_at` without checking `version`, so it can leave new inverted rows behind the script. If the workflow
   rolled back, V21 waits for the next successful deploy; the previous release runs on the V18–V23 schema
   unchanged.

**Afterwards:** once every Pod runs a V20+ binary and a rollback to a pre-V20 revision is no longer wanted, delete
the `strategy` block from `deployment.yaml` in a follow-up PR. `kubectl apply` removes the field, because it is in
the last-applied configuration, and the API server defaults it back to `RollingUpdate` (25% / 25%). Do not restore
the rolling update while a pre-V20 revision is still serving (for example after the workflow rolled back).

If a migration must run while no Pod is up, use `kubectl scale deployment code-companion-deploy -n api-service
--replicas=0` before merging, wait for the Pods to disappear (`kubectl get pods -n api-service -l
app=code-companion-deploy`), run the script, then merge; V21 still waits for step 5. The workflow's apply sets `replicas: 2` again. The
outage then lasts until the build finishes and a new Pod is Ready.

## Prerequisites

- Kubernetes cluster (v1.31+)
- kubectl configured
- An image pull secret named `dockercred` in the target namespace — `deployment.yaml` references it via
  `imagePullSecrets`, so the pod cannot pull from the private registry without it:
  ```bash
  kubectl create secret docker-registry dockercred \
    --docker-server=harbor.registry.notypie.dev \
    --docker-username=<user> --docker-password=<password> -n <namespace>
  ```
- Nodes must have `/usr/share/zoneinfo/Asia/Seoul` present — the timezone is mounted with a `hostPath`,
  not a ConfigMap, so a node without that file will fail to start the pod
- For Gateway API: Gateway API CRDs installed
- For Ingress: NGINX Ingress Controller and cert-manager installed
- The identity the deploy workflow uses needs, in `api-service`, at least one of the two permissions its
  post-deploy readiness check tries in order (grant both to keep the fallback):
  - `get` on `services/proxy`, for
    `kubectl get --raw /api/v1/namespaces/api-service/services/code-companion-svc:80/proxy/actuator/health/readiness`.
    If the Role lists `resourceNames`, the name checked is `code-companion-svc:80`, not `code-companion-svc`.
    This path also needs the API server to reach pod IPs, which some cluster networks do not allow.
  - `create` on `pods/exec` (plus `list` on `replicasets` and `pods`, which the rollback and the Ready-pod count
    already use), for `kubectl exec <pod> -c code-companion-deploy -- wget -qO- http://localhost:80/actuator/health/readiness`
    on every Pod of the new ReplicaSet that is not terminating.
  Run the first command once from the deploy identity before relying on it; the workflow log says which method answered.
- Rollout capacity: under `Recreate`, 2 × 1536Mi of memory requests must fit; under the rolling update (once the
  `Recreate` block is removed, see Deployment), 3 × 1536Mi during a rollout. Check with
  `kubectl describe nodes | grep -A8 'Allocated resources'`, comparing requested memory with each node's
  allocatable. If a new or surge Pod stays `Pending`, the rollout times out and the workflow rolls back
- The MariaDB manifests under `cdc/` set no time zone (UTC) while this Pod runs Asia/Seoul. The application
  writes outbox timestamps from its own clock, so do not rely on the DB session time zone (`NOW()`,
  `CURRENT_TIMESTAMP`) when comparing against them

## Environment Variables

The application receives environment variables from:
- **ConfigMap**: Non-sensitive configuration
- **Secret**: Sensitive credentials

All environment variables are injected into the container via `envFrom`.

## AI Agent Sidecar (Optional)

The AI assistant lane (`@bot ask`) requires [agent-sidecar](https://github.com/TrulyNotMalware/agent-sidecar) running inside the same Pod. The manifests in this directory do not include it by default; to enable it:

1. **Add the sidecar as a native sidecar container** — an `initContainers` entry with `restartPolicy: Always` (Kubernetes v1.29+), so it starts before and outlives the app container.
2. **Sidecar environment**:
   - `PROVIDER`: `claude` or `codex`
   - `BEARER_SECRET`: shared secret, must match the app's `slack.app.agent.sidecar.bearer-secret`
   - `WORKSPACE_ROOT`: a writable path (mount an `emptyDir` when running as non-root)
3. **Provider auth** — store exactly one of these in a Secret and inject it into the sidecar container:
   - `CLAUDE_CODE_OAUTH_TOKEN` (Claude subscription, from `claude setup-token`)
   - `ANTHROPIC_API_KEY` (Claude API)
   - `OPENAI_API_KEY` (Codex — the sidecar materializes `~/.codex/auth.json` at startup)
4. **App configuration**: `slack.app.agent.sidecar.base-url` stays at the Pod-loopback default `http://127.0.0.1:7300`; only the bearer secret needs to be injected (e.g. as an environment variable from the same Secret).

Because both containers share the Pod network namespace, no Service or NetworkPolicy changes are needed — the sidecar should bind to `127.0.0.1` only.

## Notes

- The deployment uses `$IMAGE_NAME` variable which should be replaced during CI/CD (`envsubst '${IMAGE_NAME}'`)
- Timezone is set to Asia/Seoul via volume mount
- PodDisruptionBudget keeps one Pod through voluntary disruptions such as node drains; it does not apply to a
  `Recreate` rollout
- Image pull policy is set to `IfNotPresent`
- Probes, resources and the shutdown budget are described under Deployment above; on a failed deploy the
  workflow runs `kubectl rollout undo` to the previous revision

---

# Kubernetes 배포 매니페스트

이 디렉토리는 Code Companion 애플리케이션을 Kubernetes에 배포하기 위한 매니페스트 파일들을 포함하고 있습니다.

## 디렉토리 구조

```
k8s/
├── configmap.yaml        # 애플리케이션 설정
├── deployment.yaml       # 배포 및 PodDisruptionBudget
├── secret.yaml          # 민감한 데이터 (인증 정보)
├── service.yaml         # ClusterIP 서비스
└── route/
    ├── httpRoute.yaml   # Gateway API HTTPRoute
    └── ingress.yaml     # NGINX Ingress
```

## 구성 요소

### ConfigMap (configmap.yaml)
다음과 같은 애플리케이션 설정을 포함합니다:
- 데이터베이스 연결 설정 (격리 수준, 타임아웃)
- Hibernate 배치 크기 설정
- Kafka 부트스트랩 서버(플레이스홀더)와 CDC 토픽(`SLACK_CDC_TOPIC`, `cdc.code_companion.outbox_message`)

### Secret (secret.yaml)
설정이 필요한 민감한 정보를 저장합니다:
- 데이터베이스 연결 URL, 사용자명, 비밀번호
- Slack API 토큰과 Slack 서명 시크릿(`SLACK_SIGNING_SECRET`, 없으면 앱이 기동을 거부)

값은 `stringData:` 아래에 평문으로 적습니다 — base64 인코딩은 API 서버가 합니다.

**중요:** 배포 전에 플레이스홀더 값을 실제 인증 정보로 교체해야 합니다.

### Deployment (deployment.yaml)
다음을 포함하는 애플리케이션 배포를 정의합니다:
- 고가용성을 위한 2개의 레플리카
- 컨테이너 포트 80
- 환경 변수를 위한 ConfigMap 및 Secret 참조
- 프라이빗 레지스트리용 `imagePullSecrets: dockercred`
- `/etc/localtime`의 `hostPath` 마운트를 통한 타임존 설정 (Asia/Seoul)
- `/actuator/health/liveness`·`/actuator/health/readiness` 기반 startup/readiness/liveness 프로브(startup은 3분 허용).
  파드가 Ready가 되지 않으면 대개 프로퍼티 바인딩 실패나 DB/Kafka 연결 실패이므로 프로브를 고치기 전에 `kubectl logs`부터 확인
- 리소스: CPU 250m / 메모리 1536Mi 요청, 메모리 limit 2Gi (JVM 힙은 limit의 50%, 요청값은 힙 + 비힙 메모리를 포함)
- 전략: 이번 릴리스에만 `Recreate`입니다. 모든 파드가 V20 이상이 되면 후속 PR에서 블록을 지웁니다(아래 "1회성" 참고).
  롤아웃이 파드 두 개를 모두 멈춘 뒤 새 파드를 띄우므로 롤백을 포함한 모든 배포가 중단을 냅니다: 이전 파드 종료(보통 몇 초, 최대 유예 180초) + 새 파드 기동(최대 startup 프로브 180초) + 첫
  readiness 확인(10초), 최악 약 6분에 스케줄링·이미지 풀 시간이 더해집니다. 요청은 2 × 1536Mi만 있으면 됩니다. 그 블록을 지운
  뒤의 기본 롤링 업데이트는 롤아웃 중 파드 3개(레플리카 2 + surge 1)를 띄우므로 요청 기준 3 × 1536Mi = 4.5Gi가 동시에 들어갈
  자리가 있어야 합니다(사전 요구사항 참고)
- 종료: 5초 `preStop` sleep 후 Spring graceful shutdown(스케줄러·웹 서버 단계 각 10초, 처리 중인 레코드를 끝내도록 Kafka 리스너 단계는 디스패치 하나 46초), Kafka producer 종료 세 번(각 5초), executor 대기(릴레이 디스패치 하나 46초, AI 턴 20초, 기본 10초), 합계 162초 ⊂ `terminationGracePeriodSeconds` 180초
- 컨테이너 `securityContext` `allowPrivilegeEscalation: false` (80 포트 바인딩 때문에 여전히 root로 실행)
- 중단 시 최소 1개의 파드를 유지하는 PodDisruptionBudget

### Service (service.yaml)
애플리케이션을 내부적으로 포트 80에 노출하는 ClusterIP 서비스를 생성합니다.

### 라우팅 옵션

애플리케이션은 두 가지 라우팅 메커니즘을 지원합니다:

#### 1. HTTPRoute (route/httpRoute.yaml)
고급 라우팅 기능을 위해 Kubernetes Gateway API를 사용합니다. 다음을 설정하세요:
- `namespace`: 대상 네임스페이스
- `parentRefs`: 게이트웨이 이름 및 네임스페이스
- `hostnames`: 도메인

#### 2. Ingress (route/ingress.yaml)
다음을 포함하는 NGINX Ingress Controller를 사용합니다:
- cert-manager를 통한 자동 TLS 인증서 관리
- TLS 종료
- 호스트 기반 라우팅

다음을 설정하세요:
- `host`: 도메인
- `cert-manager.io/cluster-issuer`: 클러스터 issuer 이름
- `secretName`: TLS secret 이름

두 샘플 모두 `/api/slack`과 `/api/slash` 접두(Slack 엔드포인트)만 전달합니다. `/actuator`, `/api/actuator`(dev·local·
slack-live 프로파일의 base path), `/mcp`는 애플리케이션 포트에서 인증 없이 제공되므로 절대 외부로 라우팅하지 마세요.

## 배포 단계

아래 명령은 모두 `api-service` 네임스페이스를 명시합니다 — 배포 워크플로도 같습니다. `metadata.namespace`를 설정하는
매니페스트는 `route/httpRoute.yaml`(플레이스홀더 `your-namespace`)뿐이므로, 4단계 전에 `api-service`로 바꾸세요. 그대로 두면
`kubectl apply -n api-service`가 네임스페이스 불일치로 거부합니다.

1. **Secret 설정**
   ```bash
   # 실제 인증 정보로 secret.yaml 편집 (stringData 아래 평문)
   ```

2. **ConfigMap 및 Secret 적용**
   ```bash
   kubectl apply -n api-service -f configmap.yaml
   kubectl apply -n api-service -f secret.yaml
   ```

3. **애플리케이션 배포**
   ```bash
   kubectl apply -n api-service -f deployment.yaml
   kubectl apply -n api-service -f service.yaml
   ```

4. **라우팅 설정** (하나를 선택)

   Gateway API의 경우:
   ```bash
   kubectl apply -n api-service -f route/httpRoute.yaml
   ```

   NGINX Ingress의 경우:
   ```bash
   kubectl apply -n api-service -f route/ingress.yaml
   ```

## 1회성: 이전 릴리스와 겹치면 안 되는 릴리스

아웃박스 claim 토큰(`attempt_count`, 마이그레이션 V20)을 도입한 릴리스는 이전 릴리스와 겹치면 안 됩니다. 이전 파드는 아웃박스
시각을 DB 시계로 쓰고, 자기 것이 아닌 `IN_PROGRESS` 행을 다시 발송합니다. 그래서 그 릴리스의 `deployment.yaml`은
`strategy.type: Recreate`를 담고, 손으로 `kubectl patch`할 필요가 없습니다. 워크플로의 `kubectl apply`가 전략을 설정하고, 롤아웃이
이전 파드를 모두 멈춘 뒤 첫 새 파드를 띄웁니다. `rollout undo`도 같습니다(파드 템플릿만 되돌리고 전략은 템플릿 밖에 있음).
`Recreate`는 eviction API가 아니라 ReplicaSet으로 파드를 지우므로 PodDisruptionBudget에 막히지 않습니다. 블록이 매니페스트에 있는
동안은 모든 배포가 중단을 냅니다(위 배포 절 참고). 워크플로의 롤아웃 타임아웃 450초가 최악의 경우를 덮습니다.

그 릴리스는 V18부터 V23까지를 한꺼번에 싣고(`main`은 V17까지), 스크립트 번호는 적용 순서가 아닙니다. V21이 마지막입니다.
운영은 `ddl-auto: none`이고 스크립트를 자동으로 적용하는 도구가 없으므로 아래 순서대로 직접 진행합니다(`../db/migration/AGENTS.md`와 같은 순서):

1. **V18** — 먼저 헤더의 중복 점검 쿼리를 실행하고, 중복된 `meeting_participants` 행을 지웁니다(가장 작은 `id`만 남김).
   그러지 않으면 새 유니크 키 생성이 실패합니다:
   ```sql
   SELECT meeting_id, user_id, COUNT(*) FROM meeting_participants
   GROUP BY meeting_id, user_id HAVING COUNT(*) > 1;
   ```
   그다음 V18을 적용합니다.
2. **V19**(아웃박스 status 인덱스)와 **V23**(아웃박스 payload `MEDIUMTEXT`). V23은 헤더대로 테이블 크기를 재고 온라인 형식을
   먼저 시도합니다. 서버가 거부하고 테이블 복사가 아웃박스 쓰기를 너무 오래 막을 크기라면 V23은 5단계 뒤, 새 릴리스의 보존
   정리가 테이블을 줄인 다음에 적용합니다.
3. **V20**, 이어서 **V22** (claim 토큰과 발송 예산).

   1~3단계는 기본값 있는 컬럼, 인덱스, 더 넓은 타입만 바꾸고 실행 중인 pre-V20 파드는 그것에 의존하지 않으므로, 이전 릴리스가
   서비스하는 동안 적용합니다. 새 릴리스에는 V18·V20·V22가 필요한데 readiness는 스키마를 검사하지 않습니다. V18이 없으면 모든
   `meetings` 조회가 알 수 없는 `version` 컬럼으로 실패하고, V20·V22가 없으면 아웃박스 claim이 전부 실패해 Slack 메시지가 하나도
   나가지 않는데, 파드는 Ready이고 워크플로는 성공으로 끝납니다. 머지 전에 확인하세요:
   ```sql
   SHOW COLUMNS FROM meetings LIKE 'version';
   SHOW COLUMNS FROM outbox_message WHERE Field IN ('attempt_count', 'send_count');
   ```
4. **이전 파드 종료 후 배포** — 머지하고 워크플로를 지켜봅니다. `Recreate` 롤아웃이 이 순서로 둘 다 수행하고, 워크플로가
   롤백하면 같은 방식으로 새 파드를 먼저 멈춥니다.
5. **V21** — 모든 파드가 새 릴리스로 돈 뒤에만 적용합니다. 워크플로가 끝났고 `kubectl get pods -n api-service -l
   app=code-companion-deploy`에 새 ReplicaSet의 파드만 보여야 합니다. 이전 바이너리의 일정 변경은 `version` 검사 없이
   `start_at`만 옮기므로 스크립트 뒤에 뒤집힌 행을 새로 남길 수 있습니다. 워크플로가 롤백했다면 V21은 다음 배포가 성공할 때까지
   기다립니다. 이전 릴리스는 V18~V23 스키마에서 그대로 돕니다.

**그 다음:** 모든 파드가 V20 이상 바이너리로 돌고 pre-V20 리비전으로의 롤백이 더는 필요 없으면, 후속 PR에서 `deployment.yaml`의
`strategy` 블록을 지웁니다. 필드가 last-applied 설정에 있으므로 `kubectl apply`가 지우고, API 서버가 `RollingUpdate`(25% / 25%)로
되돌립니다. pre-V20 리비전이 아직 서비스 중이면(예: 워크플로가 롤백한 뒤) 롤링 업데이트로 되돌리지 마세요.

파드가 하나도 없을 때 실행해야 하는 마이그레이션이 있으면 머지 전에 `kubectl scale deployment code-companion-deploy -n
api-service --replicas=0`으로 내리고 파드가 사라진 것을 확인한 뒤(`kubectl get pods -n api-service -l app=code-companion-deploy`)
스크립트를 실행하고 머지합니다. V21은 여전히 5단계를 기다립니다. 워크플로의 apply가 `replicas: 2`로 되돌립니다. 이 경우 중단은 빌드가 끝나고 새 파드가
Ready가 될 때까지 이어집니다.

## 사전 요구사항

- Kubernetes 클러스터 (v1.31+)
- kubectl 설정 완료
- 대상 네임스페이스에 `dockercred` 이미지 풀 시크릿 — `deployment.yaml`이 `imagePullSecrets`로
  참조하므로, 없으면 프라이빗 레지스트리에서 이미지를 받을 수 없습니다:
  ```bash
  kubectl create secret docker-registry dockercred \
    --docker-server=harbor.registry.notypie.dev \
    --docker-username=<user> --docker-password=<password> -n <namespace>
  ```
- 노드에 `/usr/share/zoneinfo/Asia/Seoul` 파일이 존재해야 합니다 — 타임존은 ConfigMap이 아니라
  `hostPath`로 마운트되므로, 해당 파일이 없는 노드에서는 파드가 기동되지 않습니다
- Gateway API의 경우: Gateway API CRD 설치 필요
- Ingress의 경우: NGINX Ingress Controller 및 cert-manager 설치 필요
- 배포 워크플로가 쓰는 계정에 `api-service`에서 다음 두 권한 중 하나 이상이 필요합니다. 배포 후 readiness 확인이
  이 순서로 시도하므로, 폴백을 유지하려면 둘 다 부여하세요:
  - `services/proxy` `get`:
    `kubectl get --raw /api/v1/namespaces/api-service/services/code-companion-svc:80/proxy/actuator/health/readiness`.
    Role이 `resourceNames`를 쓰면 검사되는 이름은 `code-companion-svc`가 아니라 `code-companion-svc:80`입니다.
    이 경로는 API 서버가 파드 IP에 도달할 수 있어야 하며, 클러스터 네트워크에 따라 불가능할 수 있습니다.
  - `pods/exec` `create`(롤백과 Ready 파드 수 확인이 이미 쓰는 `replicasets`·`pods` `list` 포함): 종료 중이 아닌 새 ReplicaSet의
    모든 파드에 대해 `kubectl exec <pod> -c code-companion-deploy -- wget -qO- http://localhost:80/actuator/health/readiness`.
  첫 번째 명령을 배포 계정으로 한 번 실행해 확인하세요. 워크플로 로그에 어느 방식이 응답했는지 남습니다.
- 롤아웃 용량: `Recreate`에서는 메모리 요청 2 × 1536Mi, 롤링 업데이트(`Recreate` 블록을 지운 뒤, Deployment 참고)에서는
  롤아웃 중 3 × 1536Mi가 동시에 들어가야 합니다.
  `kubectl describe nodes | grep -A8 'Allocated resources'`로 노드별 요청량과 allocatable을 비교하세요.
  새 파드나 surge 파드가 `Pending`에 머물면 롤아웃이 타임아웃되고 워크플로가 롤백합니다
- `cdc/`의 MariaDB 매니페스트는 타임존을 지정하지 않아(UTC) Asia/Seoul인 이 파드와 다릅니다. 아웃박스 시각은
  애플리케이션 시계로 기록되므로 DB 세션 타임존(`NOW()`, `CURRENT_TIMESTAMP`)을 비교 기준으로 쓰지 마세요

## 환경 변수

애플리케이션은 다음으로부터 환경 변수를 받습니다:
- **ConfigMap**: 민감하지 않은 설정
- **Secret**: 민감한 인증 정보

모든 환경 변수는 `envFrom`을 통해 컨테이너에 주입됩니다.

## AI 에이전트 사이드카 (선택)

AI 어시스턴트 기능(`@bot ask`)을 사용하려면 [agent-sidecar](https://github.com/TrulyNotMalware/agent-sidecar)가 같은 Pod 안에서 실행되어야 합니다. 이 디렉토리의 매니페스트에는 기본 포함되어 있지 않으며, 활성화하려면:

1. **네이티브 사이드카 컨테이너로 추가** — `restartPolicy: Always`를 가진 `initContainers` 항목 (Kubernetes v1.29+). 앱 컨테이너보다 먼저 시작되고 앱보다 오래 유지됩니다.
2. **사이드카 환경 변수**:
   - `PROVIDER`: `claude` 또는 `codex`
   - `BEARER_SECRET`: 공유 시크릿, 앱의 `slack.app.agent.sidecar.bearer-secret`과 일치해야 함
   - `WORKSPACE_ROOT`: 쓰기 가능한 경로 (non-root 실행 시 `emptyDir` 마운트 권장)
3. **프로바이더 인증** — 아래 중 하나만 Secret에 저장하고 사이드카 컨테이너에 주입:
   - `CLAUDE_CODE_OAUTH_TOKEN` (Claude 구독, `claude setup-token`으로 발급)
   - `ANTHROPIC_API_KEY` (Claude API)
   - `OPENAI_API_KEY` (Codex — 사이드카가 시작 시 `~/.codex/auth.json`을 생성)
4. **앱 설정**: `slack.app.agent.sidecar.base-url`은 Pod 루프백 기본값 `http://127.0.0.1:7300`을 그대로 사용하고, bearer 시크릿만 주입하면 됩니다 (예: 같은 Secret의 환경 변수로).

두 컨테이너가 Pod 네트워크 네임스페이스를 공유하므로 Service나 NetworkPolicy 변경은 필요 없습니다 — 사이드카는 `127.0.0.1`에만 바인드하는 것이 안전합니다.

## 참고 사항

- 배포는 CI/CD 중에 교체되어야 하는 `$IMAGE_NAME` 변수를 사용합니다 (`envsubst '${IMAGE_NAME}'`)
- 볼륨 마운트를 통해 타임존이 Asia/Seoul로 설정됩니다
- PodDisruptionBudget은 노드 drain 같은 자발적 중단에서 파드 하나를 유지합니다. `Recreate` 롤아웃에는 적용되지 않습니다
- 이미지 풀 정책은 `IfNotPresent`로 설정되어 있습니다
- 프로브·리소스·종료 예산은 위 Deployment 절 참고. 배포가 실패하면 워크플로가 `kubectl rollout undo`로 이전 리비전을 복원합니다
