# MariaDB Master-Slave Cluster for CDC

A production-ready MariaDB Master-Slave cluster optimized for Change Data Capture (CDC) with Debezium compatibility.

## Features

- **Master-Slave Replication**: 1 Master + 2 Slaves configuration
- **CDC Ready**: Binary logging with ROW format and FULL row image
- **High Availability**: Pod anti-affinity and PodDisruptionBudget
- **Persistent Storage**: Separate PVCs for data and logs
- **Auto Configuration**: Dynamic server-id assignment based on pod ordinal

## Prerequisites

- Kubernetes cluster
- Storage class configured
- Namespace: `database`

## Configuration Before Deployment

### 1. Update Storage Class
Edit `mariadb-sts.yaml` and replace `your-storage-class-name` with your actual storage class:
```yaml
storageClassName: "your-actual-storage-class"  # e.g., "gp2", "standard", "fast-ssd"
```

### 2. Update Passwords
Edit `mariadb-config.yaml` and change the base64 encoded passwords:
```bash
# Generate new base64 passwords
echo -n "your-root-password" | base64
echo -n "your-replication-password" | base64
```

### 3. Adjust Resources (Optional)
Modify CPU, memory, and replica count in `mariadb-sts.yaml` based on your requirements:
```yaml
replicas: 3                    # Adjust based on your HA needs
resources:
  requests:
    memory: "8Gi"              # Adjust based on your workload
    cpu: "500m"
  limits:
    memory: "16Gi"             # Adjust based on your cluster capacity
    cpu: "1000m"
```

### 4. Service Type Configuration (Optional)
If you need external access, modify `mariadb-svc.yaml` to use NodePort or LoadBalancer:
```yaml
spec:
  type: NodePort        # or LoadBalancer for cloud providers
  ports:
  - name: mysql
    port: 3306
    targetPort: 3306
    nodePort: 30006     # Only for NodePort type
```

## Quick Setup

### 1. Apply ConfigMap
```bash
kubectl apply -f mariadb-config.yaml
```

**Verify:**
```bash
kubectl get configmap mariadb-config -n database
```

### 2. Deploy StatefulSet
```bash
kubectl apply -f mariadb-sts.yaml
```

**Verify:**
```bash
kubectl get pods -n database -w
# Wait until all pods are Running (1/1)
kubectl get sts mariadb -n database
```

### 3. Create Services
```bash
kubectl apply -f mariadb-svc.yaml
```

**Verify:**
```bash
kubectl get svc -n database
```

### 4. Setup Replication
```bash
kubectl apply -f mariadb-job.yaml
```

**Verify:**
```bash
kubectl logs -f job/setup-mariadb-replication -n database
# Check replication status
kubectl exec -it mariadb-1 -n database -- mariadb -u root -p -e "SHOW SLAVE STATUS\G" | grep -E "(Slave_IO_Running|Slave_SQL_Running)"
```

## Connection Information

- **Master (Read/Write)**: `mariadb-master.database.svc.cluster.local:3306`
- **Slaves (Read Only)**: `mariadb-slave.database.svc.cluster.local:3306`
- **Headless Service**: `mariadb-headless.database.svc.cluster.local:3306`

### Default Credentials
- **Replication User**: `replicator` / `replicator`

## Verification Commands

```bash
# Check cluster status
kubectl get pods,pvc,svc -n database

# Verify server IDs
for i in 0 1 2; do
  kubectl exec -it mariadb-$i -n database -- mariadb -u root -p -e "SELECT @@server_id, @@read_only;"
done

# Test replication
kubectl exec -it mariadb-0 -n database -- mariadb -u root -p -e "CREATE DATABASE test; USE test; CREATE TABLE t1 (id INT); INSERT INTO t1 VALUES (1);"
kubectl exec -it mariadb-1 -n database -- mariadb -u root -p -e "USE test; SELECT * FROM t1;"
```

---

# MariaDB Master-Slave 클러스터 (CDC용)

CDC(Change Data Capture)에 최적화된 프로덕션 환경용 MariaDB Master-Slave 클러스터입니다.

## 주요 특징

- **Master-Slave 복제**: 1 Master + 2 Slave 구성
- **CDC 지원**: ROW 포맷 바이너리 로깅 및 FULL 행 이미지
- **고가용성**: Pod 분산 배치 및 PodDisruptionBudget
- **영구 저장소**: 데이터 및 로그용 별도 PVC
- **자동 설정**: Pod 순번 기반 동적 server-id 할당

## 사전 요구사항

- Kubernetes 클러스터
- 스토리지 클래스 설정
- 네임스페이스: `database`

## 배포 전 설정

### 1. 스토리지 클래스 수정
`mariadb-sts.yaml`에서 `your-storage-class-name`을 실제 스토리지 클래스로 변경:
```yaml
storageClassName: "실제-스토리지-클래스명"  # 예: "gp2", "standard", "fast-ssd"
```

### 2. 패스워드 변경
`mariadb-config.yaml`에서 base64 인코딩된 패스워드 변경:
```bash
# 새로운 base64 패스워드 생성
echo -n "새로운-루트-패스워드" | base64
echo -n "새로운-복제-패스워드" | base64
```

### 3. 리소스 조정 (선택사항)
`mariadb-sts.yaml`에서 요구사항에 맞게 CPU, 메모리, 복제본 수 조정:
```yaml
replicas: 3                    # 고가용성 요구사항에 따라 조정
resources:
  requests:
    memory: "8Gi"              # 워크로드에 따라 조정
    cpu: "500m"
  limits:
    memory: "16Gi"             # 클러스터 용량에 따라 조정
    cpu: "1000m"
```

### 4. 서비스 타입 설정 (선택사항)
외부 접근이 필요한 경우 `mariadb-svc.yaml`에서 NodePort 또는 LoadBalancer 사용:
```yaml
spec:
  type: NodePort        # 또는 클라우드 제공업체의 경우 LoadBalancer
  ports:
  - name: mysql
    port: 3306
    targetPort: 3306
    nodePort: 30006     # NodePort 타입의 경우에만
```

## 설치 순서

### 1. ConfigMap 적용
```bash
kubectl apply -f mariadb-config.yaml
```

**확인:**
```bash
kubectl get configmap mariadb-config -n database
```

### 2. StatefulSet 배포
```bash
kubectl apply -f mariadb-sts.yaml
```

**확인:**
```bash
kubectl get pods -n database -w
# 모든 Pod가 Running (1/1) 상태가 될 때까지 대기
kubectl get sts mariadb -n database
```

### 3. 서비스 생성
```bash
kubectl apply -f mariadb-svc.yaml
```

**확인:**
```bash
kubectl get svc -n database
```

### 4. 복제 설정
```bash
kubectl apply -f mariadb-job.yaml
```

**확인:**
```bash
kubectl logs -f job/setup-mariadb-replication -n database
# 복제 상태 확인
kubectl exec -it mariadb-1 -n database -- mariadb -u root -p -e "SHOW SLAVE STATUS\G" | grep -E "(Slave_IO_Running|Slave_SQL_Running)"
```

## 연결 정보

- **Master (읽기/쓰기)**: `mariadb-master.database.svc.cluster.local:3306`
- **Slave (읽기 전용)**: `mariadb-slave.database.svc.cluster.local:3306`
- **헤드리스 서비스**: `mariadb-headless.database.svc.cluster.local:3306`

### 기본 계정
- **복제 사용자**: `replicator` / `replicator`


## 검증 명령어

```bash
# 클러스터 상태 확인
kubectl get pods,pvc,svc -n database

# 서버 ID 확인
for i in 0 1 2; do
  kubectl exec -it mariadb-$i -n database -- mariadb -u root -p -e "SELECT @@server_id, @@read_only;"
done

# 복제 테스트
kubectl exec -it mariadb-0 -n database -- mariadb -u root -p -e "CREATE DATABASE test; USE test; CREATE TABLE t1 (id INT); INSERT INTO t1 VALUES (1);"
kubectl exec -it mariadb-1 -n database -- mariadb -u root -p -e "USE test; SELECT * FROM t1;"
```
