<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-09-30 -->

# gradle-config

## Purpose
OS-tuned Gradle daemon settings and the script that installs them. The repository's `gradle.properties`
is **git-ignored and generated** — it is produced by copying one of the presets here, so build-performance
settings stay shared without committing a machine-specific file.

## Key Files
| File | Description |
|------|-------------|
| `apply.sh` | Detects the OS (`Darwin` → macos, `Linux` → linux, `MINGW`/`CYGWIN`/`MSYS` → windows), prints system info, and writes `gradle.properties` at the repo root. Accepts `force`, `common`, `ci` or `--help`/`-h`; any other or extra argument prints usage and exits 2. Verification runs `./gradlew help` and prints its output when it fails |
| `gradle-macos.properties` | 6 GB heap, ZGC, no Linux-only flags, `apple.awt.UIElement=true`; 4 GB Kotlin daemon. For 16 GB+ machines |
| `gradle-linux.properties` | 8 GB heap, ZGC + large pages + transparent huge pages, string dedup, `workers.max=16`; 6 GB Kotlin daemon. For 16 GB+ servers |
| `gradle-ci.properties` | CI runner preset, installed only by an explicit `./apply.sh ci` (never by OS detection): 3 GB heap, JVM default GC, `workers.max=2`, 3 GB Kotlin daemon, Kotlin daemon fallback enabled. Sized for 4 vCPU / 16 GB GitHub runners, where forked test JVMs (`-Xmx4g` each, root `build.gradle.kts`) run beside both daemons: `workers.max` caps them at two at once, 3 + 3 + 2 × 4 = 14 GB of heap ceilings instead of 18 GB with all three Test tasks forked |
| `gradle-common.properties` | Portable fallback for `./apply.sh common` and unknown hosts: 4 GB heap, no GC selection, no experimental VM options, default worker count, Kotlin daemon fallback enabled |
| `README.md` | Human-facing guide, including the documented `gradle-common.properties` cross-platform preset |

All presets share: parallel + caching + configuration cache (`problems=warn`), incremental Kotlin,
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
- **Preset selection degrades, it does not fail.** `detect_os` recognises `macos`, `linux`, `windows`,
  and `unknown`, but only two presets exist. `apply_os_config` warns and delegates to
  `apply_common_config` when `gradle-<os>.properties` is absent, so Windows and any future platform
  land on the portable preset instead of exiting 1. Adding `gradle-windows.properties` overrides it
  with no code change — that is the intended way to tune a new platform.
- **Unknown options fail, OS detection does not.** A typo such as `apply.sh cii` must not fall through to OS
  detection and install the 8g + 6g Linux preset on a CI runner, so the option is validated before anything is
  written. Keep new options in both the `case` whitelist in `main` and `show_usage`.
- A change here is exercised by `simple_test_action.yaml` (its push paths and `gradle` filter include
  `gradle-config/**`, which selects the full `test` task) before it reaches `main`. `lint.yaml` does not run
  `apply.sh`, so it does not watch this directory.
- `force` is threaded through both paths: `apply_os_config "$os" "$force"` passes it to
  `apply_common_config`, which skips `backup_existing_config` when set. Keep them consistent, or
  `--force` starts meaning different things depending on which preset was chosen.
- All three presets now declare `kotlin.version=2.4.10`, matching the Kotlin plugin in the root
  `build.gradle.kts`. The property is inert — no build script reads it — but keep the three files
  agreeing with the plugin so it does not drift back into a misleading second source of truth.
  `README.md` states the real toolchain (Gradle 9.7.1 / Java 25 / Kotlin 2.4.10 / Boot 4.1.1).
- CI runs `./gradle-config/apply.sh ci` in the test workflow and in the deploy build, so a change to `apply.sh`
  or `gradle-ci.properties` changes CI build behaviour; a syntax error in `apply.sh` breaks every test run and
  every deploy. Keep the CI preset's daemon heaps small: the Linux preset (8 GB + 6 GB daemons) plus forked
  4 GB test JVMs overcommits a 16 GB runner. Keep `workers.max` at 2 as well: it is what bounds the number of
  Test tasks forking a 4 GB JVM at the same time under `org.gradle.parallel`, and raising it (or a Test heap, or
  a daemon heap) needs the arithmetic in the preset's comment redone. `ci` backs up an existing
  `gradle.properties` like the default path.

### Testing Requirements
There is no automated test. Verify manually:
```bash
./gradle-config/apply.sh          # regenerates gradle.properties for this OS
./gradle-config/apply.sh common   # exercises the portable preset
./gradle-config/apply.sh ci       # exercises the CI preset
./gradlew --stop && ./gradlew build
```
To exercise a platform you are not on, shim `uname` onto `PATH` (it must answer both `-s` and `-m`)
and run `apply.sh` against it. Caveat: `gradlew` itself branches on `uname` (line ~113) and calls
`cygpath` for MSYS/MinGW, so a Windows shim makes the script's own `verify_config` fail on a Unix
host — that is the shim, not the preset. Verify the preset separately without the shim.
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
- `.github/workflows/simple_test_action.yaml` and `deploy_action.yaml` — run `apply.sh ci` before Gradle

### External
Bash, Gradle 9.7.1, a JDK 25 toolchain.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
