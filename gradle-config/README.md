# OS-Specific Gradle Configuration

This directory contains OS-optimized Gradle configuration files and automated setup scripts. Tuned for this
repository's toolchain: Java 25 (Adoptium) + Kotlin 2.4.0 + Spring Boot 4.1.0 on Gradle 9.5.1.

The generated `gradle.properties` at the repository root is **git-ignored** — always edit the preset here
and re-run `./apply.sh` rather than editing the generated file.

## Quick Start

### Automatic Setup
```bash
./apply.sh

# For cross-platform compatibility (requires gradle-common.properties — see note below)
./apply.sh common
```

### Manual Setup
Copy the appropriate configuration file to your project root as `gradle.properties`:
- `gradle-macos.properties` → macOS (Apple Silicon & Intel)
- `gradle-linux.properties` → Linux distributions
- `gradle-common.properties` → Universal compatibility — **not currently included in this directory**

> **Note:** `apply.sh` still references `gradle-common.properties` for both `./apply.sh common` and its
> unsupported-OS fallback, but the file is not checked in. Both paths fail until it is added. On macOS
> and Linux the plain `./apply.sh` works normally.

## Configuration Files

### gradle-macos.properties
**Key Features:**
- ZGC without Linux-specific options
- Apple Silicon optimizations
- File system watching enabled
- 6GB memory allocation

**Recommended System:** 16GB+ RAM

### gradle-linux.properties
**Key Features:**
- Maximum performance settings
- Transparent Huge Pages support
- Server-grade memory allocation (8GB)
- Enhanced parallel processing

**Recommended System:** 16GB+ RAM, server environment

### gradle-common.properties *(not currently included)*
**Optimized for:** Cross-platform compatibility
**Intended Features:**
- Safe defaults for all operating systems
- 4GB memory allocation
- Basic optimizations only

**Recommended System:** 8GB+ RAM

Add this file if you need `./apply.sh common` or support for an OS other than macOS/Linux.

## Advanced Usage

### Custom Memory Settings
Adjust memory allocation based on your system:

```properties
# For 8GB systems
org.gradle.jvmargs=-Xmx2g ...

# For 32GB+ systems
org.gradle.jvmargs=-Xmx8g ...
```

### Kotlin Daemon Optimization
Fine-tune Kotlin compilation performance:

```properties
-Dkotlin.daemon.jvm.options="-Xmx4g,-XX:+UseZGC,-Dkotlin.daemon.verbose=true"
```

## Troubleshooting

### Common Issues

**"UseTransparentHugePages" error on macOS:**
- Use `gradle-macos.properties`
- This option is Linux-only

**OutOfMemoryError:**
- Reduce `-Xmx` value in `org.gradle.jvmargs`
- Check available system memory

**Slow builds despite configuration:**
- Verify Configuration Cache is enabled
- Check if incremental compilation is working
- Run `./gradlew --stop` to restart daemon

### Verification Commands
```bash
# Check current configuration
./gradlew properties | grep org.gradle

# Verify Java toolchain
./gradlew javaToolchains

# Performance analysis
./gradlew build --profile
```

## Compatibility

### Supported Versions
- **Gradle:** 9.5.1 (pinned by `gradle/wrapper/gradle-wrapper.properties`)
- **Java:** 25 — required, not just recommended. The root build pins an Adoptium toolchain and sets
  `sourceCompatibility`/`targetCompatibility` to 25, so an older JDK will not build this project.
- **Kotlin:** 2.4.0 (declared by the Kotlin plugin in the root `build.gradle.kts`)
- **Spring Boot:** 4.1.0

> The `kotlin.version` key inside the preset files is stale metadata; the Kotlin plugin version in the
> root `build.gradle.kts` is what actually governs compilation.

### OS Support Matrix
| OS                        | File | Status | Notes |
|---------------------------|---|---|---|
| macOS | gradle-macos.properties | Full Support | Optimized for M1/M2/M3 |
| Linux                     | gradle-linux.properties | Full Support | Maximum performance |
| Other OS                  | gradle-common.properties | Not available | File not checked in |

---

# OS별 Gradle 설정

OS 최적화 Gradle 설정 파일과 자동 설정 스크립트가 포함된 디렉토리입니다. 이 저장소의 툴체인 기준으로
튜닝되어 있습니다: Java 25 (Adoptium) + Kotlin 2.4.0 + Spring Boot 4.1.0, Gradle 9.5.1.

저장소 루트에 생성되는 `gradle.properties`는 **git-ignore 대상**입니다. 공유 설정을 바꿀 때는 생성된
파일이 아니라 이 디렉토리의 프리셋을 수정하고 `./apply.sh`를 다시 실행하세요.

## 빠른 시작

### 자동 설정
```bash
./apply.sh

# 크로스 플랫폼 호환성을 위한 공통 설정 (gradle-common.properties 필요 — 아래 참고)
./apply.sh common
```

### 수동 설정
적절한 설정 파일을 프로젝트 루트에 `gradle.properties`로 복사:
- `gradle-macos.properties` → macOS (Apple Silicon 및 Intel)
- `gradle-linux.properties` → Linux 배포판
- `gradle-common.properties` → 범용 호환성 — **현재 이 디렉토리에 포함되어 있지 않음**

> **참고:** `apply.sh`는 `./apply.sh common`과 미지원 OS 폴백에서 여전히 `gradle-common.properties`를
> 참조하지만 해당 파일은 커밋되어 있지 않습니다. 파일을 추가하기 전까지 두 경로 모두 실패합니다.
> macOS와 Linux에서는 `./apply.sh`가 정상 동작합니다.

## 설정 파일 설명

### gradle-macos.properties
**주요 기능:**
- Linux 전용 옵션을 제외한 ZGC 설정
- Apple Silicon 최적화
- 파일 시스템 감시 활성화
- 6GB 메모리 할당

**권장 시스템:** 16GB+ RAM

### gradle-linux.properties
**주요 기능:**
- 최대 성능 설정
- Transparent Huge Pages 지원
- 서버급 메모리 할당 (8GB)
- 향상된 병렬 처리

**권장 시스템:** 16GB+ RAM, 서버 환경

### gradle-common.properties *(현재 미포함)*
**최적화 대상:** 크로스 플랫폼 호환성
**의도된 기능:**
- 모든 운영체제에서 안전한 기본 설정
- 4GB 메모리 할당
- 기본 최적화만 적용

**권장 시스템:** 8GB+ RAM

`./apply.sh common`이나 macOS/Linux 외 OS 지원이 필요하면 이 파일을 추가하세요.

## 고급 사용법

### 사용자 정의 메모리 설정
시스템에 맞는 메모리 할당 조정:

```properties
# 8GB 시스템용
org.gradle.jvmargs=-Xmx2g ...

# 32GB+ 시스템용
org.gradle.jvmargs=-Xmx8g ...
```

### Kotlin 데몬 최적화
Kotlin 컴파일 성능 미세 조정:

```properties
-Dkotlin.daemon.jvm.options="-Xmx4g,-XX:+UseZGC,-Dkotlin.daemon.verbose=true"
```

## 문제 해결

### 일반적인 문제

**macOS에서 "UseTransparentHugePages" 오류:**
- `gradle-macos.properties` 사용
- 이 옵션은 Linux 전용입니다

**OutOfMemoryError:**
- `org.gradle.jvmargs`의 `-Xmx` 값 감소
- 사용 가능한 시스템 메모리 확인

**설정했는데도 빌드가 느림:**
- Configuration Cache 활성화 확인
- 증분 컴파일 작동 여부 확인
- `./gradlew --stop`으로 데몬 재시작

### 검증 명령어
```bash
# 현재 설정 확인
./gradlew properties | grep org.gradle

# Java 툴체인 확인
./gradlew javaToolchains

# 성능 분석
./gradlew build --profile
```

## 호환성

### 지원 버전
- **Gradle:** 9.5.1 (`gradle/wrapper/gradle-wrapper.properties`에 고정)
- **Java:** 25 — 권장이 아니라 필수입니다. 루트 빌드가 Adoptium 툴체인을 고정하고
  `sourceCompatibility`/`targetCompatibility`를 25로 설정하므로 하위 JDK로는 빌드되지 않습니다.
- **Kotlin:** 2.4.0 (루트 `build.gradle.kts`의 Kotlin 플러그인이 선언)
- **Spring Boot:** 4.1.0

> 프리셋 파일 안의 `kotlin.version` 값은 갱신되지 않은 메타데이터입니다. 실제 컴파일 버전은 루트
> `build.gradle.kts`의 Kotlin 플러그인 버전이 결정합니다.

### OS 지원 매트릭스
| OS | 파일 | 상태 | 비고 |
|---|---|---|---|
| macOS | gradle-macos.properties | 완전 지원 | M1/M2/M3 최적화 |
| Linux | gradle-linux.properties | 완전 지원 | 최대 성능 |
| 기타 OS | gradle-common.properties | 사용 불가 | 파일 미포함 |
