# MariaDB + Kafka + Debezium CDC Stack

A simple Docker Compose setup for MariaDB, Kafka, and Debezium CDC (Change Data Capture) for local development.

## Important Notice

**This setup is designed for DEVELOPMENT and TESTING purposes only.**

For production environments, please consider:
- Using managed Kafka services (AWS MSK, Confluent Cloud, etc.)
- Implementing proper security configurations (SSL/TLS, authentication)
- Setting up monitoring and alerting
- Using persistent volumes with proper backup strategies
- Configuring resource limits and high availability

## Directory Structure

```
docker-compose/
├── debezium/
│   └── connect_mariadb.sh
├── mariadb/
│   └── my.cnf
├── docker-compose.yml
└── README.md
```

## Architecture

This stack includes:

- **MariaDB**: Source database with binlog enabled
- **Kafka Cluster**: 3-node KRaft mode cluster (no Zookeeper required)
- **Debezium Connect**: CDC connector for MariaDB
- **Kafka UI**: Web interface for Kafka management
- **Debezium UI**: Web interface for Debezium connector management

## Prerequisites

- Docker Engine 20.10+
- Docker Compose 2.0+
- At least 4GB of available RAM
- Available ports: 3306, 8083, 9090, 9091, 9092, 9093, 9094

## Configuration

### 1. Update docker-compose.yml

Replace the following placeholders in `docker-compose.yml`:

- `EXTERNAL_IP`: Your host machine's IP address (use `localhost` for local-only access)
- `ROOT_PASSWORD`: MariaDB root password
- `KAFKA_UI_USER_NAME`: Kafka UI username
- `KAFKA_UI_USER_PASSWD`: Kafka UI password
- `/your/cnf/location/my.cnf`: your mariadb/my.cnf path
- `/your/kafka/config`: your kafka configuration path


### 2. Configure MariaDB

Edit `mariadb/my.cnf` and replace:

- `SERVER_ID`: Unique server ID (e.g., 1)

### 3. Configure Debezium Connector

Edit `debezium/connect_mariadb.sh` and replace:

- `debezium_hostname:port`: Debezium Connect host and port (e.g., `localhost:8083`)
- `DATABASE_USER_ID`: MariaDB user with replication privileges
- `DATABASE_PASSWD`: MariaDB user password
- `DATABASE_SERVER_ID`: Same as MariaDB server-id
- `kafka_host:port`: Kafka bootstrap server (e.g., `kafka-00:19092`)

## Usage

### 1. Start the Stack

```bash
docker-compose up -d
```

### 2. Verify Services

Check if all services are running:

```bash
docker-compose ps
```

Wait a few minutes for all services to be fully ready.

### 3. Register Debezium Connector

Make the script executable and run it:

```bash
chmod +x debezium/connect_mariadb.sh
./debezium/connect_mariadb.sh
```

Verify the connector status:

```bash
curl http://localhost:8083/connectors/mariadb-event-connector/status
```

## Access Information

| Service | URL | Credentials |
|---------|-----|-------------|
| MariaDB | `localhost:3306` | root / ROOT_PASSWORD |
| Kafka Broker 1 | `localhost:9092` | - |
| Kafka Broker 2 | `localhost:9093` | - |
| Kafka Broker 3 | `localhost:9094` | - |
| Kafka UI | http://localhost:9090 | KAFKA_UI_USER_NAME / KAFKA_UI_USER_PASSWD |
| Debezium Connect | http://localhost:8083 | - |
| Debezium UI | http://localhost:9091 | - |

## Monitoring CDC Events

### Using Kafka UI

1. Access Kafka UI at http://localhost:9090
2. Navigate to Topics
3. Look for topics with prefix `cdc.`

### Using CLI

```bash
# List topics
docker exec -it <kafka-container-id> /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:19092 --list

# Consume messages
docker exec -it <kafka-container-id> /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 \
  --topic cdc.code_companion.outbox_message \
  --from-beginning
```
## Troubleshooting

### Connector fails to start

- Check MariaDB binlog is enabled: `SHOW VARIABLES LIKE 'log_bin';`
- Verify database user has correct privileges
- Check Debezium logs: `docker-compose logs debezium`

### Kafka connection issues

- Ensure all Kafka brokers are healthy: `docker-compose ps`
- Verify network connectivity between services
- Check `EXTERNAL_IP` is correctly set

### No CDC events appearing

- Verify connector is running: `curl http://localhost:8083/connectors/mariadb-event-connector/status`
- Check table is included in `table.include.list`
- Ensure operations are performed on monitored tables

## Cleanup

Stop and remove all containers:

```bash
docker-compose down
```

Remove volumes (WARNING: This will delete all data):

```bash
docker-compose down -v
```

## References

- [Debezium MariaDB Connector Documentation](https://debezium.io/documentation/reference/stable/connectors/mariadb.html)
- [Apache Kafka KRaft Mode](https://kafka.apache.org/documentation/#kraft)
- [Kafka UI GitHub](https://github.com/provectus/kafka-ui)
- [Compose file reference](https://velog.io/@jthugg/make-local-kafka-cluster-kraft-mode-feat-debezium-cdc)

---

# MariaDB + Kafka + Debezium CDC 스택

로컬 개발을 위한 간단한 MariaDB, Kafka, Debezium CDC(Change Data Capture) Docker Compose 설정입니다.

## 중요 공지

**이 설정은 개발 및 테스트 목적으로만 설계되었습니다.**

프로덕션 환경에서는 다음을 고려하세요:
- 관리형 Kafka 서비스 사용 (AWS MSK, Confluent Cloud 등)
- 적절한 보안 설정 구현 (SSL/TLS, 인증)
- 모니터링 및 알림 설정
- 적절한 백업 전략과 함께 영구 볼륨 사용
- 리소스 제한 및 고가용성 구성

## 디렉터리 구조

```
docker-compose/
├── debezium/
│   └── connect_mariadb.sh
├── mariadb/
│   └── my.cnf
├── docker-compose.yml
└── README.md
```

## 아키텍처

이 스택은 다음을 포함합니다:

- **MariaDB**: binlog가 활성화된 소스 데이터베이스
- **Kafka 클러스터**: 3노드 KRaft 모드 클러스터 (Zookeeper 불필요)
- **Debezium Connect**: MariaDB용 CDC 커넥터
- **Kafka UI**: Kafka 관리용 웹 인터페이스
- **Debezium UI**: Debezium 커넥터 관리용 웹 인터페이스

## 사전 요구사항

- Docker Engine 20.10+
- Docker Compose 2.0+
- 최소 4GB의 사용 가능한 RAM
- 사용 가능한 포트: 3306, 8083, 9090, 9091, 9092, 9093, 9094

## 설정

### 1. docker-compose.yml 업데이트

`docker-compose.yml`에서 다음 플레이스홀더를 교체합니다:

- `EXTERNAL_IP`: 호스트 머신의 IP 주소 (로컬 전용의 경우 `localhost` 사용)
- `ROOT_PASSWORD`: MariaDB root 비밀번호
- `KAFKA_UI_USER_NAME`: Kafka UI 사용자명
- `KAFKA_UI_USER_PASSWD`: Kafka UI 비밀번호
- `/your/cnf/location/my.cnf`: mariadb/my.cnf 경로
- `/your/kafka/config`: kafka 설정이 저장될 경로

### 2. MariaDB 설정

`mariadb/my.cnf`를 수정하고 교체합니다:

- `SERVER_ID`: 고유한 서버 ID (예: 1)

### 3. Debezium 커넥터 설정

`debezium/connect_mariadb.sh`를 수정하고 교체합니다:

- `debezium_hostname:port`: Debezium Connect 호스트 및 포트 (예: `localhost:8083`)
- `DATABASE_USER_ID`: 복제 권한이 있는 MariaDB 사용자
- `DATABASE_PASSWD`: MariaDB 사용자 비밀번호
- `DATABASE_SERVER_ID`: MariaDB server-id와 동일
- `kafka_host:port`: Kafka 부트스트랩 서버 (예: `kafka-00:19092`)

## 사용법

### 1. 스택 시작

```bash
docker-compose up -d
```

### 2. 서비스 확인

모든 서비스가 실행 중인지 확인합니다:

```bash
docker-compose ps
```

모든 서비스가 완전히 준비될 때까지 몇 분 기다립니다.

### 3. Debezium 커넥터 등록

스크립트를 실행 가능하게 만들고 실행합니다:

```bash
chmod +x debezium/connect_mariadb.sh
./debezium/connect_mariadb.sh
```

커넥터 상태를 확인합니다:

```bash
curl http://localhost:8083/connectors/mariadb-event-connector/status
```

## 접속 정보

| 서비스 | URL | 인증 정보 |
|---------|-----|-------------|
| MariaDB | `localhost:3306` | root / ROOT_PASSWORD |
| Kafka 브로커 1 | `localhost:9092` | - |
| Kafka 브로커 2 | `localhost:9093` | - |
| Kafka 브로커 3 | `localhost:9094` | - |
| Kafka UI | http://localhost:9090 | KAFKA_UI_USER_NAME / KAFKA_UI_USER_PASSWD |
| Debezium Connect | http://localhost:8083 | - |
| Debezium UI | http://localhost:9091 | - |

## CDC 이벤트 모니터링

### Kafka UI 사용

1. http://localhost:9090에서 Kafka UI 접속
2. Topics로 이동
3. `cdc.` 접두사가 있는 토픽 찾기

### CLI 사용

```bash
# 토픽 목록 보기
docker exec -it <kafka-container-id> /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:19092 --list

# 메시지 소비
docker exec -it <kafka-container-id> /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 \
  --topic cdc.code_companion.outbox_message \
  --from-beginning
```

Kafka UI에서 CDC 이벤트를 확인합니다.

## 문제 해결

### 커넥터 시작 실패

- MariaDB binlog가 활성화되었는지 확인: `SHOW VARIABLES LIKE 'log_bin';`
- 데이터베이스 사용자가 올바른 권한을 가지고 있는지 확인
- Debezium 로그 확인: `docker-compose logs debezium`

### Kafka 연결 문제

- 모든 Kafka 브로커가 정상인지 확인: `docker-compose ps`
- 서비스 간 네트워크 연결 확인
- `EXTERNAL_IP`가 올바르게 설정되었는지 확인

### CDC 이벤트가 나타나지 않음

- 커넥터가 실행 중인지 확인: `curl http://localhost:8083/connectors/mariadb-event-connector/status`
- 테이블이 `table.include.list`에 포함되어 있는지 확인
- 모니터링되는 테이블에서 작업이 수행되는지 확인

## 정리

모든 컨테이너 중지 및 제거:

```bash
docker-compose down
```

볼륨 제거 (경고: 모든 데이터가 삭제됩니다):

```bash
docker-compose down -v
```

## 참고 자료

- [Debezium MariaDB 커넥터 문서](https://debezium.io/documentation/reference/stable/connectors/mariadb.html)
- [Apache Kafka KRaft 모드](https://kafka.apache.org/documentation/#kraft)
- [Kafka UI GitHub](https://github.com/provectus/kafka-ui)
- [Compose file reference](https://velog.io/@jthugg/make-local-kafka-cluster-kraft-mode-feat-debezium-cdc)
