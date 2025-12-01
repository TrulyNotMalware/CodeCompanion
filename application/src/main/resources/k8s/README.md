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
- Actuator endpoint configuration
- Hibernate batch size settings

### Secret (secret.yaml)
Stores sensitive information that needs to be configured:
- Database connection URL, username, and password
- Slack API token

**Important:** Replace placeholder values with actual credentials before deployment.

### Deployment (deployment.yaml)
Defines the application deployment with:
- 2 replicas for high availability
- Container port 80
- References to ConfigMap and Secret for environment variables
- Timezone configuration (Asia/Seoul)
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

## Deployment Steps

1. **Configure Secret**
   ```bash
   # Edit secret.yaml with your actual credentials
   # Encode values in base64 if needed
   ```

2. **Apply ConfigMap and Secret**
   ```bash
   kubectl apply -f configmap.yaml
   kubectl apply -f secret.yaml
   ```

3. **Deploy Application**
   ```bash
   kubectl apply -f deployment.yaml
   kubectl apply -f service.yaml
   ```

4. **Set up Routing** (Choose one)

   For Gateway API:
   ```bash
   kubectl apply -f route/httpRoute.yaml
   ```

   For NGINX Ingress:
   ```bash
   kubectl apply -f route/ingress.yaml
   ```

## Prerequisites

- Kubernetes cluster (v1.31+)
- kubectl configured
- For Gateway API: Gateway API CRDs installed
- For Ingress: NGINX Ingress Controller and cert-manager installed

## Environment Variables

The application receives environment variables from:
- **ConfigMap**: Non-sensitive configuration
- **Secret**: Sensitive credentials

All environment variables are injected into the container via `envFrom`.

## Notes

- The deployment uses `$IMAGE_NAME` variable which should be replaced during CI/CD
- Timezone is set to Asia/Seoul via volume mount
- PodDisruptionBudget ensures service availability during updates
- Image pull policy is set to `IfNotPresent`

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
- Actuator 엔드포인트 설정
- Hibernate 배치 크기 설정

### Secret (secret.yaml)
설정이 필요한 민감한 정보를 저장합니다:
- 데이터베이스 연결 URL, 사용자명, 비밀번호
- Slack API 토큰

**중요:** 배포 전에 플레이스홀더 값을 실제 인증 정보로 교체해야 합니다.

### Deployment (deployment.yaml)
다음을 포함하는 애플리케이션 배포를 정의합니다:
- 고가용성을 위한 2개의 레플리카
- 컨테이너 포트 80
- 환경 변수를 위한 ConfigMap 및 Secret 참조
- 타임존 설정 (Asia/Seoul)
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

## 배포 단계

1. **Secret 설정**
   ```bash
   # 실제 인증 정보로 secret.yaml 편집
   # 필요한 경우 값을 base64로 인코딩
   ```

2. **ConfigMap 및 Secret 적용**
   ```bash
   kubectl apply -f configmap.yaml
   kubectl apply -f secret.yaml
   ```

3. **애플리케이션 배포**
   ```bash
   kubectl apply -f deployment.yaml
   kubectl apply -f service.yaml
   ```

4. **라우팅 설정** (하나를 선택)

   Gateway API의 경우:
   ```bash
   kubectl apply -f route/httpRoute.yaml
   ```

   NGINX Ingress의 경우:
   ```bash
   kubectl apply -f route/ingress.yaml
   ```

## 사전 요구사항

- Kubernetes 클러스터 (v1.31+)
- kubectl 설정 완료
- Gateway API의 경우: Gateway API CRD 설치 필요
- Ingress의 경우: NGINX Ingress Controller 및 cert-manager 설치 필요

## 환경 변수

애플리케이션은 다음으로부터 환경 변수를 받습니다:
- **ConfigMap**: 민감하지 않은 설정
- **Secret**: 민감한 인증 정보

모든 환경 변수는 `envFrom`을 통해 컨테이너에 주입됩니다.

## 참고 사항

- 배포는 CI/CD 중에 교체되어야 하는 `$IMAGE_NAME` 변수를 사용합니다
- 볼륨 마운트를 통해 타임존이 Asia/Seoul로 설정됩니다
- PodDisruptionBudget은 업데이트 중 서비스 가용성을 보장합니다
- 이미지 풀 정책은 `IfNotPresent`로 설정되어 있습니다
