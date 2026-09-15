#!/usr/bin/env bash
# scripts/demo.sh — scoped startup/reset orchestration for the clean restaurant demo.
#
# Subcommands:
#   up            Preflight git/docker + the seven pinned checkpoint SHAs, ensure the disposable
#                 /Users/work/Documents/restaurant-runtime clone exists (restaurant-base @ the
#                 pinned 05-verify-retry-loop SHA), start the primary pdlc-pilot stack, and wait
#                 for actual demo seeding readiness (GET /api/demo -> enabled:true + liveItemId).
#   reset --yes   Destroy ONLY the pdlc-pilot (and any old pdlc-restaurant) compose projects'
#                 volumes, stop demo-targeting build-workers, recreate the runtime clone, then
#                 run `up`. Requires the literal --yes; anything else is a dry-run.
#   start-live    POST /api/demo/live/start (idempotent) and print the live feature's UI URL.
#   verify        Read-only readiness + catalog-count checks. No mutations, no LLM triggers.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CHECKPOINT_REPO="/Users/work/Documents/restaurant-service"
RUNTIME_DIR="/Users/work/Documents/restaurant-runtime"
COMPOSE_FILE="$REPO_ROOT/infra/docker-compose.yml"
OLD_COMPOSE_FILE="$REPO_ROOT/infra/restaurant/docker-compose.yml"
BASE="${BASE_URL:-http://localhost:8081}"
UI_BASE="${UI_BASE_URL:-http://localhost:5173}"

# The seven pinned presentation-source checkpoints (read-only git history in the checkpoint
# repo). The runtime is branched from the 05-verify-retry-loop baseline.
BASELINE_SHA="e20025a41f3aa69b22a7904e1d56c6328dccccc2"
CHECKPOINTS="
05-verify-retry-loop e20025a41f3aa69b22a7904e1d56c6328dccccc2
06-agent-intake c5729c674c5bc9ecc378df1dfe4798a33ca168ef
07-spec-gate-g1 7e7f75745f0e28d210cdd02ee7269d5d4d5d9733
08-plan-build-lanes 556b2b42f262dfb5017f7fee417ef76ab0caddc2
09-review-gate-g2 10e97cc6f9397221c5937348ea36c220174ba505
10-release-gate-g3 3bfd1f2e603aadac76adba3a7e5f1fe58368eb9e
11-monitor-and-archive 210a4aaabd73670617ac2c1be0632e80a1eef3ed
"

log() { echo "[demo] $*" >&2; }

usage() {
  cat >&2 <<'EOF'
Usage: scripts/demo.sh [up|reset --yes|start-live|verify]

  up            Preflight (git, docker compose, the seven pinned checkpoint SHAs),
                ensure /Users/work/Documents/restaurant-runtime exists (restaurant-base
                @ 05-verify-retry-loop), start the primary pdlc-pilot stack, and wait
                for GET /api/demo to report enabled:true with a non-null liveItemId.
  reset --yes   Destructive: stop demo build-workers, down --volumes the pdlc-pilot
                and old pdlc-restaurant compose projects, recreate the runtime clone,
                then run `up`. Requires the literal --yes; otherwise dry-run.
  start-live    POST /api/demo/live/start (idempotent) and print the live item's UI URL.
  verify        Read-only readiness + catalog-count checks against the running stack.
EOF
}

preflight_tools() {
  command -v git >/dev/null 2>&1 || { echo "error: git not found on PATH" >&2; exit 1; }
  command -v docker >/dev/null 2>&1 || { echo "error: docker not found on PATH" >&2; exit 1; }
  if ! docker compose version >/dev/null 2>&1; then
    echo "error: 'docker compose' (v2 plugin) not available" >&2; exit 1
  fi
}

preflight_checkpoints() {
  [ -d "$CHECKPOINT_REPO/.git" ] || {
    echo "error: $CHECKPOINT_REPO is missing or not a git repo" >&2; exit 1; }
  while read -r name sha; do
    [ -n "$name" ] || continue
    if ! git -C "$CHECKPOINT_REPO" cat-file -e "$sha" 2>/dev/null; then
      echo "error: pinned checkpoint $name ($sha) does not resolve in $CHECKPOINT_REPO" >&2
      exit 1
    fi
  done <<< "$CHECKPOINTS"
}

clone_runtime() {
  log "cloning $CHECKPOINT_REPO -> $RUNTIME_DIR"
  git clone -q "$CHECKPOINT_REPO" "$RUNTIME_DIR"
  git -C "$RUNTIME_DIR" switch -q -c restaurant-base "$BASELINE_SHA"
  log "runtime ready at $RUNTIME_DIR on restaurant-base"
}

# `up` path: create the runtime clone only if missing; never re-clone a valid existing one.
ensure_runtime() {
  if [ -d "$RUNTIME_DIR" ]; then
    [ ! -L "$RUNTIME_DIR" ] || { echo "error: $RUNTIME_DIR is a symlink; refusing to use it" >&2; exit 1; }
    [ -d "$RUNTIME_DIR/.git" ] || { echo "error: $RUNTIME_DIR exists but is not a git repo; refusing" >&2; exit 1; }
    origin="$(git -C "$RUNTIME_DIR" remote get-url origin 2>/dev/null || true)"
    if [ "$origin" != "$CHECKPOINT_REPO" ]; then
      echo "error: $RUNTIME_DIR origin ('$origin') is not '$CHECKPOINT_REPO'; refusing" >&2; exit 1
    fi
    log "runtime already exists and is valid; preserving it at $RUNTIME_DIR"
    return 0
  fi
  clone_runtime
}

wait_ready() {
  log "waiting for demo seeding readiness (GET $BASE/api/demo)"
  local deadline=$((SECONDS + 180))
  local last=""
  while true; do
    last="$(curl -s "$BASE/api/demo" 2>/dev/null || true)"
    if printf '%s' "$last" | jq -e '(.enabled == true) and (.liveItemId != null)' >/dev/null 2>&1; then
      log "ready: $last"
      return 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "error: control-plane did not become seeded within 180s; last response: ${last:-<no response>}" >&2
      return 1
    fi
    sleep 2
  done
}

stop_build_workers() {
  # Best-effort: only kill build-worker processes we can positively tie to THIS demo. Never
  # pkill node/omp unscoped; if a matching process's ownership is ambiguous, fail instead.
  local pids pid cmd cwd owned killed=0
  pids="$(pgrep -f 'build-worker' 2>/dev/null || true)"
  [ -n "$pids" ] || return 0
  for pid in $pids; do
    cmd="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' || true)"
    owned=0
    printf '%s' "$cmd" | grep -qF "$REPO_ROOT/build-worker" && owned=1
    [ "$cwd" = "$REPO_ROOT/build-worker" ] && owned=1
    printf '%s' "$cmd" | grep -qF "TARGET_REPO_PATH=$RUNTIME_DIR" && owned=1
    if [ "$owned" = "1" ]; then
      log "stopping demo-targeting build-worker pid $pid"
      kill "$pid" 2>/dev/null || true
      killed=1
    else
      echo "error: cannot verify ownership of build-worker pid $pid (cmd='$cmd', cwd='${cwd:-<unknown>}'); refusing to kill it" >&2
      exit 1
    fi
  done
  [ "$killed" = "0" ] || sleep 2
}

reset_scope() {
  echo "Reset scope (nothing is deleted until you pass --yes):"
  echo "  - docker compose project 'pdlc-pilot' ($COMPOSE_FILE): down --volumes --remove-orphans"
  echo "    (includes the langfuse-* services when that profile is enabled: langfuse-postgres,"
  echo "     langfuse-clickhouse, langfuse-minio, langfuse-redis - ALL accumulated LLM traces,"
  echo "     the Langfuse org/project/account and API keys are destroyed and re-initialized fresh)"
  echo "  - old 'pdlc-restaurant' project ($OLD_COMPOSE_FILE): down --volumes --remove-orphans"
  echo "  - stop build-worker host processes verified to target this demo (never unscoped pkill)"
  echo "  - delete + recreate $RUNTIME_DIR (restaurant-base @ $BASELINE_SHA)"
  echo "Preserved: $CHECKPOINT_REPO, $REPO_ROOT, infra/.env"
}

# `reset` path: preflight the runtime, then delete + recreate it. Also tolerates a missing
# runtime (fresh environment -> just clone).
recreate_runtime() {
  if [ ! -d "$RUNTIME_DIR" ] && [ ! -L "$RUNTIME_DIR" ]; then
    log "$RUNTIME_DIR is absent; creating a fresh clone"
    clone_runtime
    return 0
  fi
  [ ! -L "$RUNTIME_DIR" ] || { echo "error: $RUNTIME_DIR is a symlink; refusing to delete it" >&2; exit 1; }
  case "$RUNTIME_DIR" in
    "$CHECKPOINT_REPO"|"$CHECKPOINT_REPO"/*)
      echo "error: $RUNTIME_DIR is inside the checkpoint repo; refusing to delete" >&2; exit 1 ;;
    "$REPO_ROOT"|"$REPO_ROOT"/*)
      echo "error: $RUNTIME_DIR is inside this repo; refusing to delete" >&2; exit 1 ;;
  esac
  case "$CHECKPOINT_REPO" in
    "$RUNTIME_DIR"|"$RUNTIME_DIR"/*)
      echo "error: the checkpoint repo is inside $RUNTIME_DIR; refusing to delete" >&2; exit 1 ;;
  esac
  case "$REPO_ROOT" in
    "$RUNTIME_DIR"|"$RUNTIME_DIR"/*)
      echo "error: this repo is inside $RUNTIME_DIR; refusing to delete" >&2; exit 1 ;;
  esac
  [ -d "$RUNTIME_DIR/.git" ] || { echo "error: $RUNTIME_DIR has no .git directory; refusing to delete" >&2; exit 1; }
  origin="$(git -C "$RUNTIME_DIR" remote get-url origin 2>/dev/null || true)"
  [ "$origin" = "$CHECKPOINT_REPO" ] || {
    echo "error: $RUNTIME_DIR origin ('$origin') is not '$CHECKPOINT_REPO'; refusing to delete" >&2; exit 1; }

  log "recreating $RUNTIME_DIR"
  rm -rf "$RUNTIME_DIR"
  clone_runtime
}

do_up() {
  preflight_tools
  preflight_checkpoints
  ensure_runtime
  log "starting primary stack: docker compose -f infra/docker-compose.yml up -d --build"
  ( cd "$REPO_ROOT" && docker compose -f "$COMPOSE_FILE" up -d --build )
  wait_ready
  log "UP COMPLETE: demo enabled and live feature seeded (see GET $BASE/api/demo)"
}

do_reset() {
  if [ "${2:-}" != "--yes" ]; then
    reset_scope
    echo "error: reset requires the literal second argument '--yes' (dry-run: nothing was changed)" >&2
    exit 1
  fi
  preflight_tools
  preflight_checkpoints
  reset_scope
  stop_build_workers
  log "tearing down pdlc-pilot project"
  ( cd "$REPO_ROOT" && docker compose -f "$COMPOSE_FILE" --profile '*' down --volumes --remove-orphans )
  log "tearing down any old pdlc-restaurant project"
  ( cd "$REPO_ROOT" && docker compose -f "$OLD_COMPOSE_FILE" --profile '*' down --volumes --remove-orphans ) || true
  recreate_runtime
  do_up
}

do_start_live() {
  local resp item_id
  resp="$(curl -sf -X POST "$BASE/api/demo/live/start")"
  item_id="$(printf '%s' "$resp" | jq -r '.itemId')"
  echo "$resp" | jq .
  echo "live feature item: $item_id"
  echo "UI: $UI_BASE/items/$item_id"
}

do_verify() {
  local demo items total features stories tasks releases bugs snapshot_items live_features failed=0
  demo="$(curl -sf "$BASE/api/demo")"
  if ! printf '%s' "$demo" | jq -e '.enabled == true' >/dev/null 2>&1; then
    echo "error: demo not enabled: $demo" >&2; exit 1
  fi
  echo "demo: $demo"

  items="$(curl -sf "$BASE/api/items")"
  total="$(printf '%s' "$items" | jq 'length')"
  features="$(printf '%s' "$items" | jq '[.[] | select(.kind=="feature")] | length')"
  stories="$(printf '%s' "$items" | jq '[.[] | select(.kind=="story")] | length')"
  tasks="$(printf '%s' "$items" | jq '[.[] | select(.kind=="task")] | length')"
  releases="$(printf '%s' "$items" | jq '[.[] | select(.kind=="release")] | length')"
  bugs="$(printf '%s' "$items" | jq '[.[] | select(.kind=="bug")] | length')"
  echo "catalog counts: feature=$features story=$stories task=$tasks release=$releases bug=$bugs total=$total"

  [ "$total" = "44" ]   || { echo "error: expected 44 work items, got $total" >&2; failed=1; }
  [ "$features" = "9" ] || { echo "error: expected 9 features, got $features" >&2; failed=1; }
  [ "$stories" = "7" ]  || { echo "error: expected 7 stories, got $stories" >&2; failed=1; }
  [ "$tasks" = "25" ]   || { echo "error: expected 25 tasks, got $tasks" >&2; failed=1; }
  [ "$releases" = "2" ] || { echo "error: expected 2 releases, got $releases" >&2; failed=1; }
  [ "$bugs" = "1" ]     || { echo "error: expected 1 bug, got $bugs" >&2; failed=1; }
  [ "$failed" = "0" ]   || exit 1

  # Snapshot marker: exactly one feature (demo-live) has no snapshot; the other 43 items are
  # curated snapshots. NOTE: the `.snapshot` field on ItemSummaryDto is added by the parallel
  # demo-snapshot DTO work (DemoSnapshotDto). Until that lands, `.snapshot` is null/absent for
  # every item and this check fails loudly — the intended behavior for a half-merged contract.
  live_features="$(printf '%s' "$items" | jq '[.[] | select(.kind=="feature" and .snapshot == null)] | length')"
  snapshot_items="$(printf '%s' "$items" | jq '[.[] | select(.snapshot != null)] | length')"
  echo "live (non-snapshot) features: $live_features; snapshot-marked items: $snapshot_items"
  [ "$live_features" = "1" ]   || { echo "error: expected exactly 1 non-snapshot feature, got $live_features" >&2; failed=1; }
  [ "$snapshot_items" = "43" ] || { echo "error: expected 43 snapshot-marked items, got $snapshot_items" >&2; failed=1; }
  [ "$failed" = "0" ] || exit 1

  echo "verify OK"
}

ACTION="${1:-up}"
case "$ACTION" in
  up)         do_up ;;
  reset)      do_reset "$@" ;;
  start-live) do_start_live ;;
  verify)     do_verify ;;
  -h|--help|help) usage ;;
  *)          usage; exit 1 ;;
esac
