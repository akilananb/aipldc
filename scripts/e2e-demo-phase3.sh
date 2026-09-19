#!/usr/bin/env bash
# scripts/e2e-demo-phase3.sh — scripted end-to-end walkthrough of build-order phase 3, against the
# `local` profile's restaurant demo and the real /Users/work/Documents/restaurant-runtime git repo.
# Exercises the SEEDED demo-live feature (started via /api/demo/live/start, not a synthetic
# webhook item.created): intake -> grill -> answers from the resolved brief -> story v1 -> gate 1
# (PO + SquadLead approve) -> plan agent (one task per scenario) -> build loop (omp over ACP
# against a real git worktree, real npm test verifier) -> review agent -> PR opened on the shared
# story branch -> gate 2 (FSDeveloper + QA approve) -> board state `approved`.
#
# Prerequisite: the build-worker host process must be running and polling task queue "build"
# (ANTHROPIC_OAUTH_TOKEN / TARGET_REPO_PATH set) BEFORE gate 1 is approved - the plan step now
# runs as a claimable build-worker task too (an ACP coding-agent session analyzing the repo), not
# a deterministic Java planner; this script does not start the worker.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8081}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE="$SCRIPT_DIR/../control-plane/src/main/resources/demo/restaurant-demo.json"
PO_USER="po@acme"
LEAD_USER="lead@acme"
FSDEV_USER="fsdev@acme"
QA_USER="qa@acme"
REPO_PATH="${TARGET_REPO_PATH:-/Users/work/Documents/restaurant-runtime}"

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

# --- fixture bundle (authored by the parallel fixture task) -------------------------------
[ -f "$FIXTURE" ] || {
  echo "error: fixture bundle missing at $FIXTURE (the DemoInitializer's restaurant-demo.json has not landed yet; run again after the fixture task completes)" >&2
  exit 1
}
LIVE_TITLE="$(jq -r '.live.title' "$FIXTURE")"
LIVE_DESCRIPTION="$(jq -r '.live.description' "$FIXTURE")"
RESOLVED_ANSWER="$(jq -r '.live.resolvedAnswer' "$FIXTURE")"
[ -n "$RESOLVED_ANSWER" ] && [ "$RESOLVED_ANSWER" != "null" ] || {
  echo "error: $FIXTURE has no .live.resolvedAnswer" >&2; exit 1
}

# Answer every OPEN grill question from the resolved brief; the reserved intake-confirmation
# question (evidence "grill:confirmation") gets a literal "confirm" instead, never the brief's
# answer. Adaptive intake may post several rounds (a dependent follow-up, then the confirmation) -
# this loop re-fetches and re-answers each new round until /grill reports resolved. Never
# park/skip; if a question cannot be answered it stays open and we fail loudly rather than erasing
# a blocker. The deadline is checked on every iteration, not only while no question is open.
answer_open_questions() {
  local deadline=$((SECONDS + 120)) grill_json open_ids qid evidence answer
  while true; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      log "FAIL: grill questions never resolved; last state: $(curl -s "$BASE/api/items/$FEATURE_ID/grill" || true)"
      return 1
    fi
    grill_json="$(curl -s "$BASE/api/items/$FEATURE_ID/grill" || true)"
    if printf '%s' "$grill_json" | jq -e '.resolved == true' >/dev/null 2>&1; then
      log "    OK: all grill questions resolved"
      return 0
    fi
    open_ids="$(printf '%s' "$grill_json" | jq -r '.questions[]? | select(.status == "open") | .id' 2>/dev/null || true)"
    if [ -z "$open_ids" ]; then
      sleep 3
      continue
    fi
    for qid in $open_ids; do
      evidence="$(printf '%s' "$grill_json" | jq -r --arg q "$qid" '.questions[] | select(.id == $q) | .evidence')"
      if [ "$evidence" = "grill:confirmation" ]; then
        log "    confirming shared understanding ($qid)"
        answer="confirm"
      else
        log "    answering $qid from the resolved brief"
        answer="$RESOLVED_ANSWER"
      fi
      if ! api -X POST "$BASE/api/items/$FEATURE_ID/grill/$qid/answer" \
          -H "X-User: $PO_USER" -H 'X-Role: PO' -H 'Content-Type: application/json' \
          -d "$(jq -n --arg a "$answer" '{text: $a}')" >/dev/null 2>&1; then
        if curl -s "$BASE/api/items/$FEATURE_ID/grill" | jq -e --arg q "$qid" \
            '[.questions[] | select(.id == $q and .status == "open")] | length == 0' >/dev/null 2>&1; then
          log "    $qid already resolved (benign race); continuing"
        else
          log "FAIL: could not answer grill question $qid and it is still open"
          return 1
        fi
      fi
    done
  done
}

# 1. Discover the seeded live feature and start its real workflow.
log "1/12 Start live restaurant demo feature via /api/demo/live/start"
DEMO_JSON=$(api "$BASE/api/demo")
if ! echo "$DEMO_JSON" | jq -e '.enabled == true' >/dev/null; then
  log "FAIL: /api/demo reports demo disabled: $DEMO_JSON"; exit 1
fi
FEATURE_ID=$(echo "$DEMO_JSON" | jq -er '.liveItemId')
FEATURE_BOARD_ID=$(api "$BASE/api/items/$FEATURE_ID" | jq -er '.boardId')
log "feature work_item id = $FEATURE_ID (boardId $FEATURE_BOARD_ID)"
log "live feature: $LIVE_TITLE"

START_JSON=$(api -X POST "$BASE/api/demo/live/start")
STARTED_ID=$(echo "$START_JSON" | jq -er '.itemId')
if [ "$STARTED_ID" != "$FEATURE_ID" ]; then
  log "FAIL: /api/demo/live/start returned itemId $STARTED_ID != liveItemId $FEATURE_ID"; exit 1
fi

# 2. Poll for grill questions, then answer each from the resolved brief.
log "2/12 Poll for grill questions, answer from resolved brief"
poll "grill questions posted" 120 bash -c \
  "curl -sf '$BASE/api/items/$FEATURE_ID/grill' | jq -e '(.questions | length) > 0 and .resolved == false'" >/dev/null
answer_open_questions

# 3. Story drafted, awaiting-G1 (parentId == the feature's boardId, never first/last story globally).
log "3/12 Poll for story draft (awaiting-G1)"
STORY_ID=$(poll "story work_items row" 120 bash -c \
  "curl -sf '$BASE/api/items' | jq -er '.[] | select(.kind==\"story\" and .parentId==\"$FEATURE_BOARD_ID\") | .id'")
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

# 5. Plan step runs on the build-worker (ACP coding-agent session analyzes the repo and writes
# .pdlc/plan.json); control-plane validates it and publishes tasks.md; board state -> planned.
log "5/12 Poll for planned (plan step ran on the build-worker, tasks.md written)"
poll "story planned" 900 bash -c \
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
git -C "$REPO_PATH" diff restaurant-base..."$BRANCH" --stat

# 9. review.md shows the plan + build + review trail.
log "9/12 GET review.md"
REVIEW_MD=$(api "$BASE/api/items/$STORY_ID/review-md")
echo "$REVIEW_MD"
if ! echo "$REVIEW_MD" | grep -q "PR opened"; then
  log "FAIL: review.md does not contain 'PR opened'"; exit 1
fi
log "    OK: review.md shows the PR-opened block"

# 10. PR record for THIS story's branch (LocalGitRepoAdapter sidecar store, keyed by branch).
log "10/12 Inspect PR record for branch $BRANCH"
PR_JSON=""
for f in "$REPO_PATH"/.git/pdlc-prs/*.json; do
  [ -f "$f" ] || continue
  if jq -e --arg b "$BRANCH" 'select(.branch == $b)' "$f" >/dev/null 2>&1; then
    PR_JSON="$f"
    break
  fi
done
if [ -z "$PR_JSON" ]; then
  log "FAIL: no PR record found for branch $BRANCH in $REPO_PATH/.git/pdlc-prs/"; exit 1
fi
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
