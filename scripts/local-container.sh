#!/usr/bin/env bash
# Run the local profile in containers against the shared ~/infra compose stack.
#
#   ./scripts/local-container.sh up [--no-build] [--no-sidecar]
#   ./scripts/local-container.sh down | status | logs [app|sidecar]
#
# up: bootJar → image codecompanion-app:local (PROFILES=local) → network codecompanion-local →
#     sidecar (codecompanion-sidecar) → app (codecompanion-app, 127.0.0.1:9000) → waits for health.
# MariaDB (:3306) and Kafka (:19092,:29092,:39092) must already be up on the Tailscale IP
# (BIND_IP from INFRA_MARIADB_ENV), and Debezium Connect (DEBEZIUM_CONNECT_URL, default
# http://127.0.0.1:8083) must have the outbox connector registered, because the local profile
# relays the outbox through CDC. This script never starts or stops ~/infra services.
#
# Inputs, none of them in this repository:
#   ~/.config/codecompanion/local.env          SLACK_API_TOKEN, SLACK_APP_TOKEN; optional
#                                              GOOGLE_OAUTH_CLIENT_ID / GOOGLE_OAUTH_CLIENT_SECRET
#                                              (both set → calendar integration enabled),
#                                              SLACK_MEETING_COMMAND / SLACK_STANDUP_COMMAND /
#                                              SLACK_CALENDAR_COMMAND,
#                                              CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY
#                                              (SIDECAR_PROVIDER=claude)
#   ~/.config/codecompanion/local-secrets.env  generated (0600), missing keys appended: sidecar
#                                              bearer, MCP signing secret, Google token key
#   INFRA_MARIADB_ENV                          default ~/infra/mariadb/.env: BIND_IP,
#                                              MARIADB_ROOT_PASSWORD
# Overridable: SIDECAR_IMAGE (an agent-sidecar build), SIDECAR_PROVIDER (codex),
# INFRA_MARIADB_ENV, DEBEZIUM_CONNECT_URL.
# Secrets reach the containers as `docker run -e NAME` (value taken from this process's
# environment), so they never appear on a command line; `docker inspect` still shows them
# under .Config.Env. They are sourced only after the Gradle build, so the daemon never sees them.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG_DIR="$HOME/.config/codecompanion"
LOCAL_ENV="$CONFIG_DIR/local.env"
SECRETS_ENV="$CONFIG_DIR/local-secrets.env"
INFRA_MARIADB_ENV="${INFRA_MARIADB_ENV:-$HOME/infra/mariadb/.env}"
DEBEZIUM_CONNECT_URL="${DEBEZIUM_CONNECT_URL:-http://127.0.0.1:8083}"

NETWORK="codecompanion-local"
APP_CONTAINER="codecompanion-app"
APP_IMAGE="codecompanion-app:local"
SIDECAR_CONTAINER="codecompanion-sidecar"
SIDECAR_IMAGE="${SIDECAR_IMAGE:-claude-sidecar:main-dca99f3}"
SIDECAR_VOLUME="codecompanion-sidecar-state"
SIDECAR_PROVIDER="${SIDECAR_PROVIDER:-codex}"
APP_PORT=9000
JAR_NAME="application-alpha"

die() {
  echo "FAIL: $*" >&2
  exit 1
}

require_inputs() {
  [ -f "$LOCAL_ENV" ] || die "$LOCAL_ENV not found (SLACK_API_TOKEN, SLACK_APP_TOKEN)"
  [ -f "$INFRA_MARIADB_ENV" ] || die "$INFRA_MARIADB_ENV not found (set INFRA_MARIADB_ENV)"
  BIND_IP="$(. "$INFRA_MARIADB_ENV" && echo "${BIND_IP:-}")"
  [ -n "$BIND_IP" ] || die "BIND_IP missing in $INFRA_MARIADB_ENV"
}

ensure_secrets() {
  local key value
  (
    umask 077
    for key in SIDECAR_BEARER_SECRET MCP_SIGNING_SECRET GOOGLE_TOKEN_ENCRYPTION_KEY; do
      if grep -q "^$key=" "$SECRETS_ENV" 2>/dev/null; then continue; fi
      case "$key" in
        GOOGLE_TOKEN_ENCRYPTION_KEY) value="$(openssl rand -base64 32)" ;;
        *) value="$(openssl rand -hex 32)" ;;
      esac
      echo "$key=$value" >>"$SECRETS_ENV"
      echo "added $key to $SECRETS_ENV"
    done
  )
}

load_env() {
  ensure_secrets
  set -a
  # shellcheck disable=SC1090
  . "$LOCAL_ENV"
  # shellcheck disable=SC1090
  . "$SECRETS_ENV"
  # shellcheck disable=SC1090
  . "$INFRA_MARIADB_ENV"
  set +a
  : "${SLACK_API_TOKEN:?missing in $LOCAL_ENV}"
  : "${SLACK_APP_TOKEN:?missing in $LOCAL_ENV}"
  : "${MARIADB_ROOT_PASSWORD:?missing in $INFRA_MARIADB_ENV}"
}

require_infra() {
  local port connectors
  for port in 3306 19092 29092 39092; do
    nc -z -G 3 "$BIND_IP" "$port" 2>/dev/null || die "$BIND_IP:$port is closed — start ~/infra mariadb/kafka (and Tailscale) first"
  done
  connectors="$(curl -fsS --max-time 5 "$DEBEZIUM_CONNECT_URL/connectors" 2>/dev/null)" ||
    die "Debezium Connect does not answer at $DEBEZIUM_CONNECT_URL — the local profile relays the outbox through CDC; start ~/infra debezium or set DEBEZIUM_CONNECT_URL"
  case "$connectors" in
    *outbox*) ;;
    *) die "Debezium Connect at $DEBEZIUM_CONNECT_URL has no outbox connector ($connectors) — register it first, or no Slack message is delivered" ;;
  esac
}

build_image() {
  (cd "$REPO_ROOT" && ./gradlew :application:bootJar -PjarName="$JAR_NAME" --console=plain -q)
  docker build -q -t "$APP_IMAGE" \
    --build-arg JAR_FILE_NAME="$JAR_NAME" \
    --build-arg PROFILES=local \
    --build-arg SERVER_PORT="$APP_PORT" \
    --build-arg GIT_REF="$(git -C "$REPO_ROOT" rev-parse --short HEAD)" \
    "$REPO_ROOT/application" >/dev/null
}

remove_container() {
  docker rm -f "$1" >/dev/null 2>&1 || true
}

start_sidecar() {
  docker image inspect "$SIDECAR_IMAGE" >/dev/null 2>&1 ||
    die "image $SIDECAR_IMAGE not found (build it from agent-sidecar, https://github.com/TrulyNotMalware/agent-sidecar, or set SIDECAR_IMAGE)"
  remove_container "$SIDECAR_CONTAINER"
  export BEARER_SECRET="$SIDECAR_BEARER_SECRET"
  local credentials=()
  if [ "$SIDECAR_PROVIDER" = claude ]; then
    credentials=(-e CLAUDE_CODE_OAUTH_TOKEN -e ANTHROPIC_API_KEY)
  fi
  docker run -d --name "$SIDECAR_CONTAINER" --network "$NETWORK" \
    -v "$SIDECAR_VOLUME:/var/lib/claude-sidecar" \
    -e BEARER_SECRET \
    -e PROVIDER="$SIDECAR_PROVIDER" \
    ${credentials[@]+"${credentials[@]}"} \
    -e MCP_SERVER_URL="http://$APP_CONTAINER:$APP_PORT/mcp" \
    -e MCP_SERVER_NAME=domain-tools \
    -e LOG_LEVEL=INFO \
    "$SIDECAR_IMAGE" >/dev/null
}

start_app() {
  local calendar_enabled=false
  if [ -n "${GOOGLE_OAUTH_CLIENT_ID:-}" ] && [ -n "${GOOGLE_OAUTH_CLIENT_SECRET:-}" ]; then
    calendar_enabled=true
  fi
  export DATABASE_USER_PWD="$MARIADB_ROOT_PASSWORD"
  export SLACK_APP_MCP_SIGNINGSECRET="$MCP_SIGNING_SECRET"
  remove_container "$APP_CONTAINER"
  # A published port cannot reach the local profile's 127.0.0.1 bind, so the app listens on every
  # interface inside the container; host-side exposure stays on loopback through -p (the actuator
  # is unauthenticated). The sidecar calls /mcp from another container, so the MCP filter's
  # loopback rule is lifted; its turn token is still required.
  docker run -d --name "$APP_CONTAINER" --network "$NETWORK" \
    -p "127.0.0.1:$APP_PORT:$APP_PORT" \
    -e SERVER_ADDRESS=0.0.0.0 \
    -e DATABASE_URL="jdbc:mariadb://$BIND_IP:3306/code_companion" \
    -e DATABASE_USER_NAME=root \
    -e DATABASE_USER_PWD \
    -e SPRING_KAFKA_BOOTSTRAPSERVERS="$BIND_IP:19092,$BIND_IP:29092,$BIND_IP:39092" \
    -e SLACK_API_TOKEN \
    -e SLACK_APP_TOKEN \
    -e SLACK_MEETING_COMMAND \
    -e SLACK_STANDUP_COMMAND \
    -e SLACK_CALENDAR_COMMAND \
    -e SIDECAR_URL="http://$SIDECAR_CONTAINER:7300" \
    -e SIDECAR_BEARER_SECRET \
    -e SLACK_APP_MCP_ENABLED=true \
    -e SLACK_APP_MCP_SIGNINGSECRET \
    -e SLACK_APP_MCP_ALLOWREMOTE=true \
    -e SPRING_AI_MCP_SERVER_ENABLED=true \
    -e SPRING_AI_MCP_SERVER_PROTOCOL=STREAMABLE \
    -e GOOGLE_CALENDAR_ENABLED="$calendar_enabled" \
    -e GOOGLE_OAUTH_CLIENT_ID \
    -e GOOGLE_OAUTH_CLIENT_SECRET \
    -e GOOGLE_OAUTH_REDIRECT_URI="http://localhost:$APP_PORT/oauth/google/callback" \
    -e GOOGLE_TOKEN_ENCRYPTION_KEY \
    "$APP_IMAGE" >/dev/null
  echo "calendar integration: $calendar_enabled"
}

wait_for_health() {
  local i health_url="http://127.0.0.1:$APP_PORT/api/actuator/health"
  for i in $(seq 1 90); do
    if curl -fsS "$health_url" >/dev/null 2>&1; then
      echo "OK: $APP_CONTAINER healthy on http://127.0.0.1:$APP_PORT"
      return 0
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$APP_CONTAINER" 2>/dev/null)" != "true" ]; then
      docker logs --tail 40 "$APP_CONTAINER" >&2 || true
      die "$APP_CONTAINER exited"
    fi
    sleep 2
  done
  echo "last health response: $(curl -sS --max-time 5 "$health_url" 2>&1 || true)" >&2
  die "$APP_CONTAINER not healthy after 180s (docker logs $APP_CONTAINER)"
}

cmd_up() {
  local build=true sidecar=true arg
  for arg in "$@"; do
    case "$arg" in
      --no-build) build=false ;;
      --no-sidecar) sidecar=false ;;
      *) die "unknown option $arg" ;;
    esac
  done
  require_inputs
  require_infra
  if [ "$build" = true ]; then build_image; fi
  load_env
  docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null
  if [ "$sidecar" = true ]; then start_sidecar; else remove_container "$SIDECAR_CONTAINER"; fi
  start_app
  wait_for_health
}

cmd_down() {
  remove_container "$APP_CONTAINER"
  remove_container "$SIDECAR_CONTAINER"
  echo "stopped $APP_CONTAINER and $SIDECAR_CONTAINER (volume $SIDECAR_VOLUME kept)"
}

cmd_status() {
  docker ps -a --filter "name=^($APP_CONTAINER|$SIDECAR_CONTAINER)$" --format '{{.Names}}\t{{.Status}}\t{{.Image}}'
}

cmd_logs() {
  case "${1:-app}" in
    app) docker logs -f "$APP_CONTAINER" ;;
    sidecar) docker logs -f "$SIDECAR_CONTAINER" ;;
    *) die "logs takes app or sidecar" ;;
  esac
}

case "${1:-}" in
  up) shift; cmd_up "$@" ;;
  down) cmd_down ;;
  status) cmd_status ;;
  logs) shift; cmd_logs "$@" ;;
  *) die "usage: $0 up [--no-build] [--no-sidecar] | down | status | logs [app|sidecar]" ;;
esac
