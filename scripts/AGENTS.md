<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-08 -->

# scripts

## Purpose
Shell scripts run by hand, not part of the Gradle build or CI: a smoke probe against a live application and
the launcher that runs the `local` profile in containers for live testing.

## Key Files
| File | Description |
|------|-------------|
| `mcp-smoke.sh` | Smoke-probes the MCP domain-tools endpoint: (1) unauthenticated `initialize` must return 401, (2) `initialize` with a minted token must return HTTP 200 with a `serverInfo` result, (3) `tools/list` on that session must expose `get_status`, `list_meetings`, `list_roles`, `list_standups`, `list_cve_subscriptions`, `cve_latest`, `get_ai_usage` (each matched as a `"name"` value). Each step exits 1 with a `FAIL:` line on a mismatch; the last line is `OK: all three steps passed` |
| `local-container.sh` | Runs the `local` profile as two containers against the shared `~/infra` stack: `up [--no-build] [--no-sidecar]` builds the boot jar and image `codecompanion-app:local` (`PROFILES=local`, port 9000), creates network `codecompanion-local`, starts the agent sidecar `codecompanion-sidecar` and the app `codecompanion-app` (published on `127.0.0.1:9000` only) and waits up to 180 s for `/api/actuator/health`, printing the last health response when it gives up; `down` removes both containers and keeps the sidecar volume `codecompanion-sidecar-state`; `status`; `logs [app\|sidecar]`. When and why to use it: `docs/wiki/dev-environment.md` (E) |

## For AI Agents

### Working In This Directory

#### `mcp-smoke.sh`
- Usage:
  ```bash
  MCP_SIGNING_SECRET=... ./scripts/mcp-smoke.sh [base-url] [probe-user-id]
  # defaults: http://127.0.0.1:9000, U_SMOKE
  ```
  `MCP_SIGNING_SECRET` must equal the app's `slack.app.mcp.signing-secret`.
- **`mcp-smoke.sh` is a wire-compat check, not just a health probe.** It mints the token exactly the way
  `ScopedTurnTokenCodec` does — `v1.<b64url(payload)>.<b64url(hmac-sha256)>` over `v1.<encoded>`. If you
  change the token format in `application/security/mcp/`, change `mcp-smoke.sh` in the same commit or it starts
  reporting false failures.
- Passing a real Slack user id as `mcp-smoke.sh`'s second argument lets you exercise role-gated `tools/call` by
  hand afterwards — useful for verifying that `McpToolGate` denies below-threshold roles.
- `mcp-smoke.sh` asserts every step and exits non-zero on the first mismatch (`set -euo pipefail`). Keep that
  shape so it is usable as a deploy gate. Until 2026-10-02 steps 2 and 3 only printed the responses.
- `mcp-smoke.sh` keeps its optional session header as `${session_header[@]+"${session_header[@]}"}` (see the bash
  3.2 rule below) and keeps responses in a file or a variable before trimming or grepping them: `curl … | head -c N`
  under `pipefail` fails the script when the reader exits before the writer finishes.
- `mcp-smoke.sh` uses `openssl` for base64url and HMAC, and `curl` with an explicit expected HTTP code or content
  check per step (`grep -q` / `grep -Eq` on the saved body); its comment header states the exact invocation and
  what each numbered step expects.

#### `local-container.sh`
- **It never starts or stops `~/infra`.** Before building it checks that MariaDB (3306) and Kafka (19092, 29092,
  39092) answer on `BIND_IP` (`nc -z -G 3`, the macOS netcat form) and that Debezium Connect's `/connectors`
  (`DEBEZIUM_CONNECT_URL`, default `http://127.0.0.1:8083`) lists an `outbox` connector: the `local` profile relays
  the outbox through CDC, so without it the app starts and no Slack message goes out. Each check fails with a
  `FAIL:` line.
- Inputs, none of them in the repository:

  | Input | Holds |
  |-------|-------|
  | `~/.config/codecompanion/local.env` | `SLACK_API_TOKEN`, `SLACK_APP_TOKEN` (required); optional `GOOGLE_OAUTH_CLIENT_ID` + `GOOGLE_OAUTH_CLIENT_SECRET`, `SLACK_MEETING_COMMAND` / `SLACK_STANDUP_COMMAND` / `SLACK_CALENDAR_COMMAND`, and `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY` for `SIDECAR_PROVIDER=claude` |
  | `~/.config/codecompanion/local-secrets.env` | `SIDECAR_BEARER_SECRET`, `MCP_SIGNING_SECRET` (hex, 32 bytes), `GOOGLE_TOKEN_ENCRYPTION_KEY` (base64, 32 bytes); created 0600 inside a `umask 077` subshell, and a key missing from an existing file is appended alone |
  | `INFRA_MARIADB_ENV` (default `~/infra/mariadb/.env`) | `BIND_IP`, `MARIADB_ROOT_PASSWORD` (the app connects as `root` to `code_companion`) |

  Overridable from the calling shell: `SIDECAR_IMAGE` (default a local agent-sidecar build tag; build one from
  https://github.com/TrulyNotMalware/agent-sidecar), `SIDECAR_PROVIDER` (`codex`), `INFRA_MARIADB_ENV`,
  `DEBEZIUM_CONNECT_URL`. Do not add a secret value, a token or a machine-specific path to the script; a new input
  goes into one of these files or variables.
- **Order inside `up`:** input files checked → only `BIND_IP` read (not exported) for the infra checks → Gradle
  build → secrets sourced with `set -a`. Keep the build before the sourcing so no token enters the Gradle daemon's
  environment.
- Secrets reach the containers as `docker run -e NAME`, the value taken from the script's environment, so they
  never appear on a command line (`docker inspect` still shows the container's env); keep that form instead of
  `-e NAME="$VALUE"`. A `-e NAME` whose variable is unset is not set in the container, which is how the optional
  `SLACK_*_COMMAND` (meeting, standup, calendar) and `GOOGLE_OAUTH_*` variables stay optional.
- The sidecar gets `-e CLAUDE_CODE_OAUTH_TOKEN -e ANTHROPIC_API_KEY` only when `SIDECAR_PROVIDER=claude`; with
  `codex` (the default) neither reaches its container, even when `local.env` sets them. The flags live in a
  `credentials` array expanded as `${credentials[@]+"${credentials[@]}"}`, which bash 3.2 accepts when the array is
  empty (see below).
- The app container sets `SERVER_ADDRESS=0.0.0.0` because a published port cannot reach the profile's `127.0.0.1`
  bind; host-side exposure stays on loopback through `-p 127.0.0.1:9000:9000`, which must stay (the `local`
  actuator is unauthenticated). It also sets `SLACK_APP_MCP_ALLOWREMOTE=true`, because the sidecar calls `/mcp`
  from another container on `codecompanion-local`; the per-turn token is still checked. Neither override belongs
  in a profile YAML.
- `GOOGLE_CALENDAR_ENABLED` is `true` only when both `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET` are
  set, else `false`. The redirect URI it passes, `http://localhost:9000/oauth/google/callback`, must be registered
  on the Google OAuth client.
- `--no-sidecar` also removes an existing sidecar container, so a stale one never answers the new app.
- The env names it passes follow `application-local.yaml`'s placeholders and Spring's relaxed binding
  (`SLACK_APP_MCP_*`, `SPRING_AI_MCP_SERVER_*`, `SPRING_KAFKA_BOOTSTRAPSERVERS`); renaming a key there needs this
  script changed in the same commit, or the container starts with the default silently.

#### Both scripts
- **They must run on macOS's bash 3.2** (`/bin/bash -n` checks the syntax). There an empty `"${arr[@]}"` under
  `set -u` is an unbound-variable error.
- `set -euo pipefail`, defaults via `${1:-...}` / `${VAR:-...}`, required env asserted with `${VAR:?message}`, and
  every failure the script detects itself is one `FAIL:` line followed by a non-zero exit.

## Dependencies

### Internal
- `application/src/main/kotlin/dev/notypie/application/security/mcp/` — the token format `mcp-smoke.sh` reproduces
- `application/src/main/kotlin/dev/notypie/application/mcp/` — the tools `mcp-smoke.sh` lists
- `application/Dockerfile`, `application/src/main/resources/application-local.yaml` — the image and the profile
  `local-container.sh` runs

### External
`bash`, `curl`, `openssl`, `uuidgen`; `local-container.sh` also needs `docker`, `nc` and `git`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
