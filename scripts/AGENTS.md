<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-07 -->

# scripts

## Purpose
Operational shell probes run by hand against a live application — not part of the Gradle build or CI.

## Key Files
| File | Description |
|------|-------------|
| `mcp-smoke.sh` | Smoke-probes the MCP domain-tools endpoint: (1) unauthenticated `initialize` must return 401, (2) `initialize` with a minted token must return HTTP 200 with a `serverInfo` result, (3) `tools/list` on that session must expose `get_status`, `list_meetings`, `list_roles`, `list_standups`, `list_cve_subscriptions`, `cve_latest`, `get_ai_usage` (each matched as a `"name"` value). Each step exits 1 with a `FAIL:` line on a mismatch; the last line is `OK: all three steps passed` |

## For AI Agents

### Working In This Directory
- Usage:
  ```bash
  MCP_SIGNING_SECRET=... ./scripts/mcp-smoke.sh [base-url] [probe-user-id]
  # defaults: http://127.0.0.1:9000, U_SMOKE
  ```
  `MCP_SIGNING_SECRET` must equal the app's `slack.app.mcp.signing-secret`.
- **This script is a wire-compat check, not just a health probe.** It mints the token exactly the way
  `ScopedTurnTokenCodec` does — `v1.<b64url(payload)>.<b64url(hmac-sha256)>` over `v1.<encoded>`. If you
  change the token format in `application/security/mcp/`, change it here in the same commit or the
  script starts reporting false failures.
- Passing a real Slack user id as the second argument lets you exercise role-gated `tools/call` by hand
  afterwards — useful for verifying that `McpToolGate` denies below-threshold roles.
- The script asserts every step and exits non-zero on the first mismatch (`set -euo pipefail`). Keep that
  shape so it is usable as a deploy gate. Until 2026-10-02 steps 2 and 3 only printed the responses.
- **It must run on macOS's bash 3.2.** There an empty `"${arr[@]}"` under `set -u` is an unbound-variable
  error, which is why the optional session header expands as `${session_header[@]+"${session_header[@]}"}`.
  Keep responses in a file or a variable before trimming or grepping them: `curl … | head -c N` under
  `pipefail` fails the script when the reader exits before the writer finishes.
- `set -euo pipefail`, defaults via `${1:-...}`, required env asserted with `${VAR:?message}`.
- `openssl` for base64url and HMAC; `curl` with an explicit expected HTTP code or content check per step
  (`grep -q` / `grep -Eq` on the saved body).
- A comment header stating the exact invocation and what each numbered step expects.

## Dependencies

### Internal
- `application/src/main/kotlin/dev/notypie/application/security/mcp/` — the token format being reproduced
- `application/src/main/kotlin/dev/notypie/application/mcp/` — the tools being listed

### External
`bash`, `curl`, `openssl`, `uuidgen`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
