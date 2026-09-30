#!/usr/bin/env bash
# Smoke-probe the MCP domain-tools endpoint.
#
#   MCP_SIGNING_SECRET=... ./scripts/mcp-smoke.sh [base-url] [probe-user-id]
#
# 1) unauthenticated initialize        → expect 401 from the turn-token filter
# 2) initialize with a minted token    → expect a server info response
# 3) tools/list on the same session    → expect get_status / list_meetings / list_roles
#
# The token is minted exactly like ScopedTurnTokenCodec does (v1.<b64url(payload)>.<b64url(hmac)>),
# so this doubles as a wire-compat check of the token format. Pass the probing user's Slack id
# as the second argument to exercise role-gated tools/call by hand afterwards.
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:9000}"
PROBE_USER_ID="${2:-U_SMOKE}"
ENDPOINT="$BASE_URL/mcp"
: "${MCP_SIGNING_SECRET:?export MCP_SIGNING_SECRET (slack.app.mcp.signing-secret of the app)}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
payload=$(printf '{"sub":"%s","sk":"%s","tid":"%s","iat":%d,"exp":%d}' \
  "$PROBE_USER_ID" "smoke:probe" "$(uuidgen | tr '[:upper:]' '[:lower:]')" "$now" $((now + 300)))
encoded=$(printf '%s' "$payload" | b64url)
signature=$(printf '%s' "v1.$encoded" | openssl dgst -sha256 -hmac "$MCP_SIGNING_SECRET" -binary | b64url)
token="v1.$encoded.$signature"

initialize_body='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcp-smoke","version":"0"}}}'
accept_headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')

echo "1) unauthenticated initialize (expect 401)"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$ENDPOINT" "${accept_headers[@]}" -d "$initialize_body")
echo "   HTTP $code"
[ "$code" = "401" ] || { echo "   FAIL: expected 401"; exit 1; }

echo "2) initialize with minted token (user=$PROBE_USER_ID)"
headers_file=$(mktemp)
body_file=$(mktemp)
trap 'rm -f "$headers_file" "$body_file"' EXIT
code=$(curl -s -D "$headers_file" -o "$body_file" -w '%{http_code}' -X POST "$ENDPOINT" \
  -H "Authorization: Bearer $token" "${accept_headers[@]}" -d "$initialize_body")
echo "   HTTP $code"
# Truncated copy for the log, read from the file so a long body cannot SIGPIPE curl under pipefail.
head -c 600 "$body_file"
echo
[ "$code" = "200" ] || { echo "   FAIL: expected 200"; exit 1; }
grep -q '"serverInfo"' "$body_file" || { echo "   FAIL: no serverInfo in the initialize result"; exit 1; }
session_id=$(awk 'tolower($1) ~ /^mcp-session-id:/ {gsub("\r",""); print $2}' "$headers_file")
session_header=()
[ -n "$session_id" ] && session_header=(-H "Mcp-Session-Id: $session_id")

# ${arr[@]+"${arr[@]}"}: a bare empty "${arr[@]}" is an unbound-variable error under `set -u` in bash 3.2 (macOS).
curl -s -o /dev/null -X POST "$ENDPOINT" -H "Authorization: Bearer $token" \
  ${session_header[@]+"${session_header[@]}"} \
  "${accept_headers[@]}" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

echo "3) tools/list (expect get_status / list_meetings / list_roles)"
tools=$(curl -s -X POST "$ENDPOINT" -H "Authorization: Bearer $token" \
  ${session_header[@]+"${session_header[@]}"} \
  "${accept_headers[@]}" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
printf '%s\n' "$tools"
for tool in get_status list_meetings list_roles; do
  grep -Eq "\"name\"[[:space:]]*:[[:space:]]*\"$tool\"" <<< "$tools" \
    || { echo "   FAIL: tools/list does not expose $tool"; exit 1; }
done
echo "OK: all three steps passed"
