<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-30 -->

# .github/workflows

## Purpose
The four GitHub Actions workflows. Triggers, path filters, permissions, and the constraints behind each step are
documented in `../AGENTS.md`; this file is the per-file index.

## Key Files
| File | Description |
|------|-------------|
| `lint.yaml` | `ktlintCheck` on pushes to `feature/*`, `feat/*`, `features/*`, `dependabot/**` (source-path filtered incl. `gradle/**`, `!**/*.md`) and on every PR into `main` (no path filter); 15-minute timeout, cancels superseded runs |
| `simple_test_action.yaml` | Same triggers, plus `gradle-config/**` in the push paths; 30-minute timeout, cancels superseded runs; runs `gradle-config/apply.sh ci`, then the changed modules' tests **and their dependants'** via `dorny/paths-filter@v4` (full `test` when Gradle, `gradle-config` or `domain` files change); uploads `build-reports.zip` on failure |
| `security_check.yaml` | Push/PR to `main`, weekly, manual: `changes` gate (`dorny/paths-filter@v4`, `some-with-excludes`), CodeQL `java-kotlin` with a manual `./gradlew classes --no-daemon --no-build-cache` compile, Gradle dependency-graph submission + dependency review on PRs, gitleaks secret scan |
| `deploy_action.yaml` | Merged PR to `main` only, serialised by the `deploy-production` concurrency group (an unmerged close gets a throwaway group); every `run:` step under `defaults.run.shell: bash` (`-eo pipefail`): full `build` (tests included, `apply.sh ci`, 40-minute timeout) → multi-arch image → Harbor → `envsubst '${IMAGE_NAME}'` apply to OKE (`-n api-service`) → rollout + Ready-pod count + in-cluster readiness check (service proxy, falling back to `kubectl exec … wget` on every non-terminating Pod of the current revision's ReplicaSet; parsed with `jq`) → `rollout undo` to the recorded revision when one of those steps failed |

## For AI Agents

### Working In This Directory
- Read `../AGENTS.md` first — it holds the non-obvious rules (why `!` patterns are useless inside the deploy workflow's
  paths-filter block, why CodeQL cannot use `build-mode: none`, why the `changes` job needs `pull-requests: read`, why fork PRs skip
  dependency submission, why `dependabot/**` must stay in the branch lists).
- Keep permissions least-privilege and declared per job; the workflow-level default is `contents: read`.
- Pin action majors (`@v6`, `@v4`, …); Dependabot's `github-actions` ecosystem bumps them in one grouped PR.

### Testing Requirements
- `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest -color` before pushing; the only accepted
  pre-existing findings are the `SC2086` shellcheck notes on `$GITHUB_OUTPUT` lines.
- Exercise `security_check.yaml` with `workflow_dispatch` or a PR to `main`; lint/test with a `feature/*` push.

### Common Patterns
- `dorny/paths-filter` outputs gate jobs; `if:` expressions compare to the string `'true'`.
- `gradle.properties` is git-ignored and absent on a fresh runner: the test and deploy jobs install the CI
  preset with `./gradle-config/apply.sh ci`; the CodeQL compile passes explicit heaps on the command line instead.

## Dependencies

### Internal
- `gradle-config/apply.sh`, `gradle/wrapper/`, `application/Dockerfile`, `application/src/main/resources/k8s/deployment.yaml`
- `../dependabot.yml`, `../../.gitleaks.toml`

### External
GitHub Actions, CodeQL, gitleaks, Harbor, Oracle OKE / OCI CLI, Docker Buildx + QEMU.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
