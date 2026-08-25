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
| `gradle-common.properties` | Portable fallback for `./apply.sh common` and unknown hosts: 4 GB heap, no GC selection, no experimental VM options, default worker count, Kotlin daemon fallback enabled |
| `README.md` | Human-facing guide, including the documented `gradle-common.properties` cross-platform preset |

Both presets share: parallel + caching + configuration cache (`problems=warn`), incremental Kotlin,
`kotlin.code.style=official`, VFS watching, verbose console, `warning.mode=all`.

## For AI Agents

### Working In This Directory
- **`gradle.properties` at the repo root is generated output.** It is listed in `.gitignore`. Never edit
  it as the fix for a shared build setting — edit the preset here and re-run `./gradle-config/apply.sh`.
  Re-running also drops a timestamped `gradle.properties.backup.*` beside it; those are git-ignored.
- Keep the presets **in sync where the setting is not OS-specific** (configuration cache, caching,
  incremental compilation, code style). Only memory, GC flags, and worker counts should diverge.
- **`gradle-common.properties` is the portability escape hatch.** It backs `./apply.sh common` and the
  `unknown`-OS fallback, so it must stay valid on platforms nobody here tests. That is why it selects
  no GC, sets no experimental VM options, leaves `workers.max` unset, and enables
  `kotlin.daemon.useFallbackStrategy` — the opposite of the macOS/Linux presets. Do not "optimize" it.
- **Windows never reaches the common preset automatically.** `detect_os` maps MSYS/Cygwin/MinGW to
  `windows`, which is not `unknown`, so `apply_os_config` looks for a non-existent
  `gradle-windows.properties` and exits 1. Either add that file or route `windows` to
  `apply_common_config`; until then Windows users must run `./apply.sh common` explicitly.
- All three presets now declare `kotlin.version=2.4.0`, matching the Kotlin plugin in the root
  `build.gradle.kts`. The property is inert — no build script reads it — but keep the three files
  agreeing with the plugin so it does not drift back into a misleading second source of truth.
  `README.md` states the real toolchain (Gradle 9.5.1 / Java 25 / Kotlin 2.4.0 / Boot 4.1.0).
- CI runs `./gradle-config/apply.sh` in the test workflow, so a change here affects CI build behaviour.
  A syntax error in `apply.sh` breaks every test run.

### Testing Requirements
There is no automated test. Verify manually:
```bash
./gradle-config/apply.sh          # regenerates gradle.properties for this OS
./gradle-config/apply.sh common   # exercises the portable preset
./gradlew --stop && ./gradlew build
```
`apply.sh` ends by running `./gradlew help` itself, so a malformed preset fails there. Also compile at
least one module (`./gradlew :domain:compileKotlin`) — `help` does not start the Kotlin daemon, which
is where the `kotlin.daemon.*` keys actually take effect. Confirm the configuration cache reports no
new problems.

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
