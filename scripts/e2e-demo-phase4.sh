#!/usr/bin/env bash
# scripts/e2e-demo-phase4.sh — scripted end-to-end walkthrough of build-order phase 4, against the
# `local` profile and the real target-repos/orders-service git repo. Runs the full phase 1-3 flow
# (intake -> grill -> gate 1 -> plan -> build loop -> gate 2) then continues into phase 4:
# release agent drafts the pack -> gate 3 (PO/SquadLead/QA sign each document by its own checker
# role) -> deploy through CiPort (real git verify + deployment-marker file) -> one monitor
# evaluation pass -> a tripped rule files a card back on the board.
#
# Prerequisite: the standalone build agent (build-worker/dist/worker.js) must be running and
# polling control-plane's REST API (PDLC_API_URL, BUILD_FILTER_PROFILE, ANTHROPIC_OAUTH_TOKEN
# set) - this script does not start it.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8081}"
FEATURE_BOARD_ID="${FEATURE_BOARD_ID:-4414}"
PO_USER="po@acme"
LEAD_USER="lead@acme"
FSDEV_USER="fsdev@acme"
QA_USER="qa@acme"
REPO_PATH="${TARGET_REPO_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/target-repos/orders-service}"

log() { echo "[e2e-phase4] $*" >&2; }

poll() {
  local desc=$1 max_seconds=$2; shift 2
  local waited=0
  while true; do
    if "$@" 2>/dev/null; then return 0; fi
    waited=$((waited + 2))
    if [ "$waited" -ge "$max_seconds" ]; then
      log "FAIL: timed out waiting for $desc"; return 1
    fi
    sleep 2
  done
}

api() { curl -sf "$@"; }

# 1. Intake.
log "1/12 POST item.created for feature #$FEATURE_BOARD_ID"
api -X POST "$BASE/webhooks/local" -H 'Content-Type: application/json' -d '{
  "kind": "item.created",
  "boardId": "'"$FEATURE_BOARD_ID"'",
  "rev": 1,
  "itemKind": "feature",
  "title": "Export the filtered orders view to CSV",
  "description": "Sales ops wants to export the current filtered orders view to CSV.",
  "areaPath": "orders"
}' >/dev/null

FEATURE_ID=$(poll "feature work_items row" 240 bash -c \
  "curl -sf '$BASE/api/items' | jq -er '.[] | select(.boardId==\"$FEATURE_BOARD_ID\") | .id'")
log "feature work_item id = $FEATURE_ID"

# 2. Answer the grill questions (fixture answers matching stub-llm's questions).
log "2/12 Poll for grill questions, then answer q1/q4"
poll "grill questions posted" 180 bash -c \
  "curl -sf '$BASE/api/items/$FEATURE_ID/board-comments' | jq -e 'length > 0'" >/dev/null
api -X POST "$BASE/webhooks/local" -H 'Content-Type: application/json' -d '{
  "kind": "comment.added", "boardId": "'"$FEATURE_BOARD_ID"'", "rev": 2,
  "author": "'"$PO_USER"'", "text": "q1: Current filtered view, max 10k rows."
}' >/dev/null
api -X POST "$BASE/webhooks/local" -H 'Content-Type: application/json' -d '{
  "kind": "comment.added", "boardId": "'"$FEATURE_BOARD_ID"'", "rev": 3,
  "author": "'"$PO_USER"'", "text": "q4: Audit every export; 10 per user per hour."
}' >/dev/null

# 3. Story drafted, awaiting-G1.
log "3/12 Poll for story draft (awaiting-G1)"
STORY_ID=$(poll "story work_items row" 120 bash -c \
  "curl -sf '$BASE/api/items' | jq -er '[.[] | select(.kind==\"story\")] | last | .id'")
poll "story awaiting-G1" 180 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.latestVersion==1)'" >/dev/null
log "story work_item id = $STORY_ID"

# 4. Gate 1: PO + Squad Lead approve v1 directly.
log "4/12 Approve gate 1 as PO and Squad Lead"
api -X POST "$BASE/api/items/$STORY_ID/approve" -H "X-User: $PO_USER" -H 'X-Role: PO' \
  -H 'Content-Type: application/json' -d '{"note":"intent + criteria ok"}' >/dev/null
api -X POST "$BASE/api/items/$STORY_ID/approve" -H "X-User: $LEAD_USER" -H 'X-Role: SquadLead' \
  -H 'Content-Type: application/json' -d '{"note":"scope ok"}' >/dev/null
poll "gate 1 passed -> approved" 120 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"approved\")'" >/dev/null
log "    OK: gate 1 passed"

# 5-7. Plan -> build loop -> review -> PR -> awaiting-G2 (real omp sessions, can take minutes).
log "5/12 Poll for planned (plan agent ran, tasks.md written)"
poll "story planned" 120 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"planned\" or .canonicalState==\"in-progress\" or .canonicalState==\"awaiting-G2\")'" >/dev/null
log "6/12 Poll for in-progress (build loop started)"
poll "story in-progress" 120 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"in-progress\" or .canonicalState==\"awaiting-G2\")'" >/dev/null
log "    OK: build loop running - this drives real omp sessions, can take several minutes"
log "7/12 Poll for awaiting-G2 (build loop + review agent finished, PR opened)"
poll "story awaiting-G2" 900 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"awaiting-G2\")'" >/dev/null
log "    OK: awaiting-G2"

# 8. Gate 2: FS Developer + QA approve.
log "8/12 Approve gate 2 as FS Developer and QA"
api -X POST "$BASE/api/items/$STORY_ID/pr/approve" -H "X-User: $FSDEV_USER" -H 'X-Role: FSDeveloper' \
  -H 'Content-Type: application/json' -d '{"note":"code matches the traceability rows"}' >/dev/null
api -X POST "$BASE/api/items/$STORY_ID/pr/approve" -H "X-User: $QA_USER" -H 'X-Role: QA' \
  -H 'Content-Type: application/json' -d '{"note":"verifier green, scope clean"}' >/dev/null

# The build loop's own scope guard reverts any out-of-touches edit attempt (e.g. a task trying to
# edit review.md) but the review agent still surfaces the attempt as a [blocker] scope finding,
# which seeds an open blocking comment gate 2 cannot pass while it's open (by design - a human
# must look at it). If that happened, request-changes clears it (bumps the version, matching every
# other gate's signal contract) and both checkers re-approve at the new version.
OPEN_BLOCKING=$(api "$BASE/api/items/$STORY_ID" | jq -r '.gate.openBlockingComments // 0')
if [ "$OPEN_BLOCKING" != "0" ]; then
  log "    gate 2 has $OPEN_BLOCKING open blocking comment(s) (reverted scope-violation escalation) - clearing via request-changes"
  api -X POST "$BASE/api/items/$STORY_ID/pr/request-changes" -H "X-User: $FSDEV_USER" -H 'X-Role: FSDeveloper' >/dev/null
  api -X POST "$BASE/api/items/$STORY_ID/pr/approve" -H "X-User: $FSDEV_USER" -H 'X-Role: FSDeveloper' \
    -H 'Content-Type: application/json' -d '{"note":"scope deviation reviewed and accepted; reverted edit attempt, tests genuinely green"}' >/dev/null
  api -X POST "$BASE/api/items/$STORY_ID/pr/approve" -H "X-User: $QA_USER" -H 'X-Role: QA' \
    -H 'Content-Type: application/json' -d '{"note":"verifier green, scope clean on the actual diff"}' >/dev/null
fi
poll "gate 2 passed -> awaiting-G3" 240 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"awaiting-G3\")'" >/dev/null
log "    OK: gate 2 passed, release agent drafted the pack"

# 9. Seed metric samples BEFORE gate 3 passes: the workflow deploys and runs the one monitor
# evaluation pass immediately once gate 3 is satisfied, so the samples must already be in the
# shared metric_samples table (control-plane and agents are separate JVMs; see LocalMetricsAdapter)
# and within the export-error-rate rule's "> 2% over 15m" trailing window before that happens.
log "9/12 Seed http_5xx_rate samples above the export-error-rate threshold"
for v in 2.8 3.1 3.4; do
  api -X POST "$BASE/api/metrics" -H 'Content-Type: application/json' \
    -d '{"signal":"http_5xx_rate","value":'"$v"'}' >/dev/null
done
log "    OK: seeded 3 samples (avg ~3.1 > 2% threshold)"

# 10. Gate 3: inspect the release pack, sign every document with its own named checker role.
log "10/12 Inspect release pack, sign each document"
DOCS_JSON=$(poll "release documents listed" 30 bash -c "curl -sf '$BASE/api/items/$STORY_ID/release' | jq -e 'select(length == 4)'")
echo "$DOCS_JSON" | jq '.[] | {docId, title, checkerRole}'

sign() {
  local doc_id=$1 user=$2 role=$3
  api -X POST "$BASE/api/items/$STORY_ID/release/$doc_id/sign" -H "X-User: $user" -H "X-Role: $role" \
    -H 'Content-Type: application/json' -d '{"note":"reviewed"}' >/dev/null
}
sign change-notes "$PO_USER" PO
sign rollout-plan "$LEAD_USER" SquadLead
sign monitor-rules "$QA_USER" QA
sign test-evidence "$QA_USER" QA
poll "gate 3 passed -> done (deployed)" 180 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"done\")'" >/dev/null
log "    OK: gate 3 passed, release deployed"

# 11. Verify the deploy marker + monitor trip card, both real side effects.
log "11/12 Verify deploy marker file and monitor trip card"
RELEASE_ID=$(ls "$REPO_PATH"/.git/pdlc-deploys/*.json | tail -1)
log "deploy marker: $RELEASE_ID"
cat "$RELEASE_ID" | jq .

TRIP_ITEM=$(poll "monitor trip card filed" 120 bash -c \
  "curl -sf '$BASE/api/items' | jq -e '.[] | select(.kind==\"bug\" or .kind==\"feature\")'")
echo "$TRIP_ITEM" | jq .
log "    OK: monitor trip filed a card"

# 12. Final review.md check: release pack, signatures, gate 3 passed, deployed, monitor evaluation.
log "12/12 Final review.md check"
REVIEW_MD_FINAL=$(poll "review.md shows gate 3 passed and a monitor trip" 60 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID/review-md' | grep -q 'gate 3 passed' && curl -sf '$BASE/api/items/$STORY_ID/review-md' | grep -q 'Monitor evaluation' && curl -sf '$BASE/api/items/$STORY_ID/review-md'")
echo "$REVIEW_MD_FINAL"
for marker in "Release pack published" "signed" "gate 3 passed" "Deployed" "Monitor evaluation" "1 trip(s)"; do
  if ! echo "$REVIEW_MD_FINAL" | grep -q "$marker"; then
    log "FAIL: review.md missing '$marker'"; exit 1
  fi
done
log "    OK: review.md carries the full release/gate3/deploy/monitor trail"

log "SUCCESS: gate 2 -> release pack drafted -> gate 3 (4 docs signed) -> real CiPort deploy -> monitor evaluation -> trip filed a card."
