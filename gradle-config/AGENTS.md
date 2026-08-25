<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# gradle-config

## Purpose
OS-tuned Gradle daemon settings and the script that installs them. The repository's `gradle.properties`
is **git-ignored and generated** — it is produced by copying one of the presets here, so build-performance
settings stay shared without committing a machine-specific file.

## Key Files
| File | Description |
|------|-------------|
| `apply.sh` | Detects the OS (`Darwin` → macos, `Linux` → linux, `MINGW`/`CYGWIN`/`MSYS` → windows), prints system info, and writes `gradle.properties` at the repo root. Accepts `force` or `common` |
| `gradle-macos.properties` | 6 GB heap, ZGC, no Linux-only flags, `apple.awt.UIElement=true`; 4 GB Kotlin daemon. For 16 GB+ machines |
| `gradle-linux.properties` | 8 GB heap, ZGC + large pages + transparent huge pages, string dedup, `workers.max=16`; 6 GB Kotlin daemon. For 16 GB+ servers |
| `README.md` | Human-facing guide, including the documented `gradle-common.properties` cross-platform preset |

Both presets share: parallel + caching + configuration cache (`problems=warn`), incremental Kotlin,
`kotlin.code.style=official`, VFS watching, verbose console, `warning.mode=all`.

## For AI Agents

### Working In This Directory
- **`gradle.properties` at the repo root is generated output.** It is listed in `.gitignore`. Never edit
  it as the fix for a shared build setting — edit the preset here and re-run `./gradle-config/apply.sh`.
- Keep the presets **in sync where the setting is not OS-specific** (configuration cache, caching,
  incremental compilation, code style). Only memory, GC flags, and worker counts should diverge.
- **`gradle-common.properties` is referenced but missing.** `apply.sh` uses it in two places —
  `apply_common_config()` for `./apply.sh common`, and the unsupported-OS fallback — but the file is
  not checked in, so both paths fail. macOS and Linux are unaffected. Add the file rather than
  removing the code paths, since the fallback is the only thing covering a non-macOS/Linux machine.
- The presets pin `kotlin.version=2.3.21` while the root build applies the Kotlin plugin at `2.4.0`.
  The plugin version in `build.gradle.kts` is what governs compilation; treat the property as stale
  metadata rather than a second source of truth, and update it when you touch these files.
  `README.md` now states the real toolchain (Gradle 9.5.1 / Java 25 / Kotlin 2.4.0 / Boot 4.1.0).
- CI runs `./gradle-config/apply.sh` in the test workflow, so a change here affects CI build behaviour.
  A syntax error in `apply.sh` breaks every test run.

### Testing Requirements
There is no automated test. Verify manually:
```bash
./gradle-config/apply.sh          # regenerates gradle.properties for this OS
./gradlew --stop && ./gradlew build
```
Confirm the daemon starts and the configuration cache does not report new problems.

### Common Patterns
- Bash with `set -e`, colored `log_info` / `log_success` / `log_warning` / `log_error` helpers.
- `PROJECT_ROOT` derived from `${BASH_SOURCE[0]}` so the script works from any working directory.

## Dependencies

### Internal
- Root `build.gradle.kts` and `gradlew` — the consumers of the generated `gradle.properties`
- `.github/workflows/simple_test_action.yaml` — runs `apply.sh` before tests

### External
Bash, Gradle 9.5.1, a JDK 25 toolchain.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
