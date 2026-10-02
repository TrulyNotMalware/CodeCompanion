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
  request covers heap plus non-heap memory). With the default rolling update a rollout briefly runs 3 Pods
  (2 replicas + 1 surge), so the nodes need 3 × 1536Mi = 4.5Gi of requestable memory at once; see Prerequisites
- Shutdown: a 5s `preStop` sleep, then Spring's graceful shutdown (10s per phase, two phases) and the executor
  waits (20s + 20s + 10s), within an 80s `terminationGracePeriodSeconds`
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

Every command below targets the `api-service` namespace explicitly — the deploy workflow does the same, and
none of the manifests set `metadata.namespace`.

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

The strategy in `deployment.yaml` is the default rolling update, so old and new Pods briefly run side by side. The
release that introduced outbox claim tokens (`attempt_count`, migration V20) cannot overlap its predecessor: the
old Pods write outbox timestamps with the database clock and re-dispatch `IN_PROGRESS` rows they do not own. For
that one rollout, stop the old Pods first. Do not change the strategy in `deployment.yaml`.

1. Apply the migrations that release needs, as their headers say (V20 must be in place before the new code runs).
2. Before merging, switch the live Deployment to `Recreate`. `kubectl apply` leaves `spec.strategy` alone because
   the manifest does not set it, so the workflow's rollout will use it:
   ```bash
   kubectl patch deployment code-companion-deploy -n api-service \
     -p '{"spec":{"strategy":{"type":"Recreate","rollingUpdate":null}}}'
   ```
   The patch changes no Pod template, so it starts no rollout. `Recreate` deletes the old Pods through the
   ReplicaSet, not the eviction API, so the PodDisruptionBudget does not block it. The service is down from the
   moment the old Pods stop until a new Pod is Ready (the startup probe allows up to 3 minutes).
3. Merge and watch the workflow. A rollback it performs also uses `Recreate`, which is what you want then.
4. Afterwards, restore the rolling update:
   ```bash
   kubectl patch deployment code-companion-deploy -n api-service \
     -p '{"spec":{"strategy":{"type":"RollingUpdate","rollingUpdate":{"maxSurge":"25%","maxUnavailable":"25%"}}}}'
   ```

If a migration must run while no Pod is up, use `kubectl scale deployment code-companion-deploy -n api-service
--replicas=0` instead of step 2, wait for the Pods to disappear (`kubectl get pods -n api-service -l
app=code-companion-deploy`), run the script, then merge. The workflow's apply sets `replicas: 2` again. The
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
  - `create` on `pods/exec`, for
    `kubectl exec deploy/code-companion-deploy -c code-companion-deploy -- wget -qO- http://localhost:80/actuator/health/readiness`.
  Run the first command once from the deploy identity before relying on it; the workflow log says which method answered.
- Rollout capacity: 3 × 1536Mi of memory requests must fit during a rollout (see Deployment). Check with
  `kubectl describe nodes | grep -A8 'Allocated resources'`, comparing requested memory with each node's
  allocatable. If the surge Pod stays `Pending`, the rollout times out and the workflow rolls back
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
- PodDisruptionBudget ensures service availability during updates
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
- 리소스: CPU 250m / 메모리 1536Mi 요청, 메모리 limit 2Gi (JVM 힙은 limit의 50%, 요청값은 힙 + 비힙 메모리를 포함).
  기본 롤링 업데이트는 롤아웃 중 파드 3개(레플리카 2 + surge 1)를 띄우므로 노드에 요청 기준 3 × 1536Mi = 4.5Gi가
  동시에 들어갈 자리가 있어야 합니다(사전 요구사항 참고)
- 종료: 5초 `preStop` sleep 후 Spring graceful shutdown(단계당 10초, 두 단계)과 executor 대기(20초 + 20초 + 10초), 전체 `terminationGracePeriodSeconds` 80초
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

`deployment.yaml`은 기본 롤링 업데이트라 잠시 이전 파드와 새 파드가 함께 돕니다. 아웃박스 claim 토큰(`attempt_count`,
마이그레이션 V20)을 도입한 릴리스는 이전 릴리스와 겹치면 안 됩니다. 이전 파드는 아웃박스 시각을 DB 시계로 쓰고, 자기 것이
아닌 `IN_PROGRESS` 행을 다시 발송합니다. 그 한 번의 롤아웃에서는 이전 파드를 먼저 멈추세요. `deployment.yaml`의 전략은 바꾸지 않습니다.

1. 그 릴리스에 필요한 마이그레이션을 각 헤더의 안내대로 적용합니다(V20은 새 코드가 뜨기 전에 있어야 함).
2. 머지 전에 라이브 Deployment를 `Recreate`로 바꿉니다. 매니페스트에 `spec.strategy`가 없으므로 `kubectl apply`는 이 값을
   건드리지 않고, 워크플로의 롤아웃이 그대로 사용합니다:
   ```bash
   kubectl patch deployment code-companion-deploy -n api-service \
     -p '{"spec":{"strategy":{"type":"Recreate","rollingUpdate":null}}}'
   ```
   파드 템플릿은 바뀌지 않으므로 롤아웃이 시작되지 않습니다. `Recreate`는 eviction API가 아니라 ReplicaSet으로 파드를 지우므로
   PodDisruptionBudget에 막히지 않습니다. 이전 파드가 멈춘 뒤 새 파드가 Ready가 될 때까지(startup 프로브 최대 3분) 서비스가 중단됩니다.
3. 머지하고 워크플로를 지켜봅니다. 워크플로가 롤백해도 `Recreate`로 진행되며, 그때도 그것이 맞습니다.
4. 끝나면 롤링 업데이트로 되돌립니다:
   ```bash
   kubectl patch deployment code-companion-deploy -n api-service \
     -p '{"spec":{"strategy":{"type":"RollingUpdate","rollingUpdate":{"maxSurge":"25%","maxUnavailable":"25%"}}}}'
   ```

파드가 하나도 없을 때 실행해야 하는 마이그레이션이 있으면 2단계 대신 `kubectl scale deployment code-companion-deploy -n
api-service --replicas=0`으로 내리고 파드가 사라진 것을 확인한 뒤(`kubectl get pods -n api-service -l app=code-companion-deploy`)
스크립트를 실행하고 머지합니다. 워크플로의 apply가 `replicas: 2`로 되돌립니다. 이 경우 중단은 빌드가 끝나고 새 파드가
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
  - `pods/exec` `create`:
    `kubectl exec deploy/code-companion-deploy -c code-companion-deploy -- wget -qO- http://localhost:80/actuator/health/readiness`.
  첫 번째 명령을 배포 계정으로 한 번 실행해 확인하세요. 워크플로 로그에 어느 방식이 응답했는지 남습니다.
- 롤아웃 용량: 롤아웃 중 메모리 요청 3 × 1536Mi가 동시에 들어가야 합니다(Deployment 참고).
  `kubectl describe nodes | grep -A8 'Allocated resources'`로 노드별 요청량과 allocatable을 비교하세요.
  surge 파드가 `Pending`에 머물면 롤아웃이 타임아웃되고 워크플로가 롤백합니다
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
- PodDisruptionBudget은 업데이트 중 서비스 가용성을 보장합니다
- 이미지 풀 정책은 `IfNotPresent`로 설정되어 있습니다
- 프로브·리소스·종료 예산은 위 Deployment 절 참고. 배포가 실패하면 워크플로가 `kubectl rollout undo`로 이전 리비전을 복원합니다
