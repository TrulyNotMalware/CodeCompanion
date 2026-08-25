<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# scripts

## Purpose
Operational shell probes run by hand against a live application — not part of the Gradle build or CI.

## Key Files
| File | Description |
|------|-------------|
| `mcp-smoke.sh` | Smoke-probes the MCP domain-tools endpoint: (1) unauthenticated `initialize` must return 401, (2) `initialize` with a minted token must return server info, (3) `tools/list` on that session must expose `get_status`, `list_meetings`, `list_roles` |

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
- The script asserts and exits non-zero on the first mismatch (`set -euo pipefail`). Keep that shape so
  it is usable as a deploy gate.

### Testing Requirements
Requires a running app with MCP enabled. There is no unit test; the equivalent logic is covered by
`ScopedTurnTokenCodecTest` and `McpToolGateTest` in `:application`. Treat those specs as the contract
and this script as the end-to-end confirmation.

### Common Patterns
- `set -euo pipefail`, defaults via `${1:-...}`, required env asserted with `${VAR:?message}`.
- `openssl` for base64url and HMAC; `curl` with an explicit expected HTTP code check per step.
- A comment header stating the exact invocation and what each numbered step expects.

## Dependencies

### Internal
- `application/src/main/kotlin/dev/notypie/application/security/mcp/` — the token format being reproduced
- `application/src/main/kotlin/dev/notypie/application/mcp/` — the tools being listed

### External
`bash`, `curl`, `openssl`, `uuidgen`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
