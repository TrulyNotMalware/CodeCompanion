<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# gradle/wrapper

## Purpose
The Gradle wrapper that every build, CI job, and Dependabot-independent tool invokes through `./gradlew`. It pins
the Gradle distribution so local machines and runners build with the same version.

## Key Files
| File | Description |
|------|-------------|
| `gradle-wrapper.properties` | `distributionUrl` pinning Gradle 9.7.1 (`-bin.zip`), plus network timeout and validation settings |
| `gradle-wrapper.jar` | Bootstrap jar downloaded by `gradlew`; validated in CI by `gradle/actions/wrapper-validation` |

## For AI Agents

### Working In This Directory
- Do not hand-edit these files. Upgrade with `./gradlew wrapper --gradle-version <x.y.z>` from the repository root,
  which rewrites both files consistently, then commit them together.
- A wrapper bump is a build-wide change: rerun `./gradlew build` locally and expect the `*.gradle.kts` path filter to
  trigger the full test workflow (`gradle/**` is in every CI filter).
- The deploy workflow validates the jar's checksum; a tampered or hand-copied jar fails the build before compiling.

### Testing Requirements
- `./gradlew --version` prints the pinned version; `./gradlew build` proves the toolchain (JDK 25 via foojay) still
  resolves under the new Gradle.

### Common Patterns
- `gradle-common.properties` / OS presets in `gradle-config/` document the Gradle version they were tuned for; update
  that note when bumping the wrapper.

## Dependencies

### Internal
- `gradlew`, `gradlew.bat` at the repository root
- `gradle-config/` presets (memory / GC flags tuned per Gradle version)

### External
Gradle distribution from `services.gradle.org`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
