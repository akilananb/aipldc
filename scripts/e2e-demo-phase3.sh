#!/usr/bin/env bash
# scripts/e2e-demo-phase3.sh — scripted end-to-end walkthrough of build-order phase 3, against the
# `local` profile and the real target-repos/orders-service git repo.
# Demonstrates: intake -> grill -> answers -> story v1 -> gate 1 (PO + SquadLead approve) ->
# plan agent (one task per scenario) -> build loop (omp over ACP against a real git worktree,
# real npm test verifier) -> review agent -> PR opened on the shared story branch -> gate 2
# (FSDeveloper + QA approve) -> board state `approved`.
#
# Prerequisite: the build-worker host process must be running and polling task queue "build"
# (ANTHROPIC_OAUTH_TOKEN / TARGET_REPO_PATH set) - this script does not start it.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8081}"
FEATURE_BOARD_ID="${FEATURE_BOARD_ID:-4413}"
PO_USER="po@acme"
LEAD_USER="lead@acme"
FSDEV_USER="fsdev@acme"
QA_USER="qa@acme"
REPO_PATH="${TARGET_REPO_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/target-repos/orders-service}"

log() { echo "[e2e-phase3] $*" >&2; }

poll() {
  local desc=$1 max=$2; shift 2
  local waited=0
  while true; do
    if out=$("$@" 2>/dev/null) && [ -n "$out" ] && [ "$out" != "null" ]; then
      echo "$out"
      return 0
    fi
    waited=$((waited + 3))
    if [ "$waited" -ge "$max" ]; then
      log "TIMEOUT waiting for: $desc"
      return 1
    fi
    sleep 3
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
  "curl -sf '$BASE/api/items' | jq -r '.[] | select(.boardId==\"$FEATURE_BOARD_ID\") | .id'")
log "feature work_item id = $FEATURE_ID"

# 2. Answer the grill questions (fixture answers matching stub-llm's questions).
log "2/12 Poll for grill questions, then answer q1/q4"
poll "grill questions posted" 60 bash -c \
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
  "curl -sf '$BASE/api/items' | jq -r '.[] | select(.kind==\"story\") | .id' | tail -1")
poll "story awaiting-G1" 60 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.latestVersion==1)'" >/dev/null
log "story work_item id = $STORY_ID"

# 4. Gate 1: PO + Squad Lead approve v1 directly (the request-changes/blocking-comment loop is
# already covered end-to-end by scripts/e2e-demo.sh; this script's focus is phase 3).
log "4/12 Approve gate 1 as PO and Squad Lead"
api -X POST "$BASE/api/items/$STORY_ID/approve" -H "X-User: $PO_USER" -H 'X-Role: PO' \
  -H 'Content-Type: application/json' -d '{"note":"intent + criteria ok"}' >/dev/null
api -X POST "$BASE/api/items/$STORY_ID/approve" -H "X-User: $LEAD_USER" -H 'X-Role: SquadLead' \
  -H 'Content-Type: application/json' -d '{"note":"scope ok"}' >/dev/null
poll "gate 1 passed -> approved" 60 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"approved\")'" >/dev/null
log "    OK: gate 1 passed"

# 5. Plan agent runs: tasks.md written, board state -> planned.
log "5/12 Poll for planned (plan agent ran, tasks.md written)"
poll "story planned" 60 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"planned\" or .canonicalState==\"in-progress\" or .canonicalState==\"awaiting-G2\")'" >/dev/null
log "    OK: planned"

# 6. Build loop runs (real omp over ACP, real git worktrees, real npm test) - the slow step.
log "6/12 Poll for in-progress (build loop started)"
poll "story in-progress" 60 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"in-progress\" or .canonicalState==\"awaiting-G2\")'" >/dev/null
log "    OK: build loop running - this drives real omp sessions, can take several minutes"

log "7/12 Poll for awaiting-G2 (build loop + review agent finished, PR opened)"
poll "story awaiting-G2" 900 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"awaiting-G2\")'" >/dev/null
log "    OK: awaiting-G2"

# 8. Inspect the story branch and target repo state for real evidence.
BRANCH="story/$(api "$BASE/api/items/$STORY_ID" | jq -r .boardId)"
log "8/12 Inspect branch $BRANCH in $REPO_PATH"
git -C "$REPO_PATH" log --oneline "$BRANCH" | head -10
git -C "$REPO_PATH" diff main..."$BRANCH" --stat

# 9. review.md shows the plan + build + review trail.
log "9/12 GET review.md"
REVIEW_MD=$(api "$BASE/api/items/$STORY_ID/review-md")
echo "$REVIEW_MD"
if ! echo "$REVIEW_MD" | grep -q "PR opened"; then
  log "FAIL: review.md does not contain 'PR opened'"; exit 1
fi
log "    OK: review.md shows the PR-opened block"

# 10. PR record + comments (LocalGitRepoAdapter's sidecar store - no dedicated REST endpoint yet).
log "10/12 Inspect PR record"
PR_JSON=$(ls "$REPO_PATH"/.git/pdlc-prs/*.json | tail -1)
cat "$PR_JSON" | jq .

# 11. Gate 2: FS Developer + QA approve.
log "11/12 Approve gate 2 as FS Developer and QA"
api -X POST "$BASE/api/items/$STORY_ID/pr/approve" -H "X-User: $FSDEV_USER" -H 'X-Role: FSDeveloper' \
  -H 'Content-Type: application/json' -d '{"note":"code matches the traceability rows"}' >/dev/null
api -X POST "$BASE/api/items/$STORY_ID/pr/approve" -H "X-User: $QA_USER" -H 'X-Role: QA' \
  -H 'Content-Type: application/json' -d '{"note":"verifier green, scope clean"}' >/dev/null
poll "gate 2 passed -> approved" 60 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"approved\")'" >/dev/null
log "    OK: gate 2 passed"

# 12. Final review.md check + target repo tests genuinely green on the story branch.
log "12/12 Final checks"
REVIEW_MD_FINAL=$(poll "review.md shows gate 2 passed" 20 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID/review-md' | grep -q 'gate 2 passed' && curl -sf '$BASE/api/items/$STORY_ID/review-md'")
echo "$REVIEW_MD_FINAL"
TMP_CHECK=$(mktemp -d)
git -C "$REPO_PATH" worktree add "$TMP_CHECK" "$BRANCH" >/dev/null 2>&1
if ! (cd "$TMP_CHECK" && npm test 2>&1 | tail -20); then
  git -C "$REPO_PATH" worktree remove --force "$TMP_CHECK"
  log "FAIL: target repo tests are not green on $BRANCH"; exit 1
fi
git -C "$REPO_PATH" worktree remove --force "$TMP_CHECK"

log "SUCCESS: plan -> real omp build loop -> verifier -> review -> PR -> gate 2 passed on branch $BRANCH."
