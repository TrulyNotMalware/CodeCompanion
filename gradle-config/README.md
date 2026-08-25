# OS-Specific Gradle Configuration

This directory contains OS-optimized Gradle configuration files and automated setup scripts. Tuned for this
repository's toolchain: Java 25 (Adoptium) + Kotlin 2.4.0 + Spring Boot 4.1.0 on Gradle 9.5.1.

The generated `gradle.properties` at the repository root is **git-ignored** — always edit the preset here
and re-run `./apply.sh` rather than editing the generated file.

## Quick Start

### Automatic Setup
```bash
./apply.sh

# For cross-platform compatibility
./apply.sh common
```

### Manual Setup
Copy the appropriate configuration file to your project root as `gradle.properties`:
- `gradle-macos.properties` → macOS (Apple Silicon & Intel)
- `gradle-linux.properties` → Linux distributions
- `gradle-common.properties` → Universal compatibility

> **Note:** you rarely need to pick manually. `apply.sh` maps `uname -s` to `macos`, `linux`,
> `windows`, or `unknown`, and any platform without a matching `gradle-<os>.properties` — Windows via
> Git Bash / MSYS / Cygwin included — automatically falls back to the common preset with a warning.
> Adding a `gradle-windows.properties` here is all it takes to override that.

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

### gradle-common.properties
**Optimized for:** Cross-platform compatibility
**Key Features:**
- No GC selection and no experimental VM options — the JVM default collector, valid everywhere
- 4GB heap (2GB Kotlin daemon)
- Worker count left to Gradle's processor-count default
- Kotlin daemon fallback **enabled**, so an untuned platform degrades to in-process compilation
  instead of failing the build

**Recommended System:** 8GB+ RAM

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
| Windows / Other OS        | gradle-common.properties | Basic Support | Automatic fallback — no preset tuned for these |

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

# 크로스 플랫폼 호환성을 위한 공통 설정
./apply.sh common
```

### 수동 설정
적절한 설정 파일을 프로젝트 루트에 `gradle.properties`로 복사:
- `gradle-macos.properties` → macOS (Apple Silicon 및 Intel)
- `gradle-linux.properties` → Linux 배포판
- `gradle-common.properties` → 범용 호환성

> **참고:** 대개 직접 고를 필요가 없습니다. `apply.sh`는 `uname -s`를 `macos`/`linux`/`windows`/
> `unknown`으로 매핑하고, 대응하는 `gradle-<os>.properties`가 없는 플랫폼(Git Bash·MSYS·Cygwin의
> Windows 포함)은 경고와 함께 공통 프리셋으로 자동 폴백합니다. `gradle-windows.properties`를 이
> 디렉토리에 추가하면 그 파일이 우선 적용됩니다.

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

### gradle-common.properties
**최적화 대상:** 크로스 플랫폼 호환성
**주요 기능:**
- GC 지정과 실험적 VM 옵션 없음 — 모든 플랫폼에서 유효한 JVM 기본 컬렉터 사용
- 4GB 힙 (Kotlin 데몬 2GB)
- 워커 수는 Gradle의 프로세서 개수 기본값에 위임
- Kotlin 데몬 폴백 **활성화** — 튜닝되지 않은 플랫폼에서 빌드 실패 대신 인프로세스 컴파일로 degrade

**권장 시스템:** 8GB+ RAM

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
| Windows / 기타 OS | gradle-common.properties | 기본 지원 | 자동 폴백 — 전용 프리셋 없음 |
