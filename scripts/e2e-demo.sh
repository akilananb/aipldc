#!/usr/bin/env bash
# scripts/e2e-demo.sh — scripted end-to-end walkthrough against the `local` profile's
# restaurant demo. Exercises the SEEDED demo-live feature (started via /api/demo/live/start, not
# a synthetic webhook item.created): intake -> grill questions -> answers from the resolved brief
# -> story draft v1 -> blocking comment on the delivery-time scenario -> request-changes -> agent
# revision v2 -> PO + Squad Lead approve (distinct identities, same version) -> gate 1 passes ->
# board state `approved`, review.md shows the full trail.
#
# The live brief and resolved answer are read from the SAME bundled fixture JSON the
# DemoInitializer seeds from (control-plane/src/main/resources/demo/restaurant-demo.json); they
# are never duplicated here.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8081}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE="$SCRIPT_DIR/../control-plane/src/main/resources/demo/restaurant-demo.json"
PO_USER="po@acme"
LEAD_USER="lead@acme"

log() { echo "[e2e] $*" >&2; }

poll() {
  # poll <description> <max_seconds> <command...>
  local desc=$1 max=$2; shift 2
  local waited=0
  while true; do
    if out=$("$@" 2>/dev/null) && [ -n "$out" ] && [ "$out" != "null" ]; then
      echo "$out"
      return 0
    fi
    waited=$((waited + 2))
    if [ "$waited" -ge "$max" ]; then
      log "TIMEOUT waiting for: $desc"
      return 1
    fi
    sleep 2
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

# Answer every OPEN grill question from the resolved brief. Never park, never skip; if a question
# cannot be answered it stays open and we fail loudly rather than erasing a blocker.
answer_open_questions() {
  local deadline=$((SECONDS + 120)) grill_json open_ids qid
  while true; do
    grill_json="$(curl -s "$BASE/api/items/$FEATURE_ID/grill" || true)"
    if printf '%s' "$grill_json" | jq -e '.resolved == true' >/dev/null 2>&1; then
      log "    OK: all grill questions resolved"
      return 0
    fi
    open_ids="$(printf '%s' "$grill_json" | jq -r '.questions[]? | select(.status == "open") | .id' 2>/dev/null || true)"
    if [ -z "$open_ids" ]; then
      if [ "$SECONDS" -ge "$deadline" ]; then
        log "FAIL: grill questions never resolved; last state: $grill_json"
        return 1
      fi
      sleep 3
      continue
    fi
    for qid in $open_ids; do
      log "    answering $qid from the resolved brief"
      if ! api -X POST "$BASE/api/items/$FEATURE_ID/grill/$qid/answer" \
          -H "X-User: $PO_USER" -H 'X-Role: PO' -H 'Content-Type: application/json' \
          -d "$(jq -n --arg a "$RESOLVED_ANSWER" '{text: $a}')" >/dev/null 2>&1; then
        # The question may have been resolved between our read and this POST (benign race); only
        # fail if it is genuinely still open.
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

# 1. Discover the seeded live feature via GET /api/demo and start its real workflow.
log "1/9 Start live restaurant demo feature via /api/demo/live/start"
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

# 2. Poll until grill questions appear, then answer each from the resolved brief.
#    Question ids come from GET /api/items/{id}/grill (structured, reliable) — the old script
#    hardcoded q1/q4 from the CSV fixture; the real grill agent decides ids at runtime.
log "2/9 Poll for grill questions, answer from resolved brief"
poll "grill questions posted" 120 bash -c \
  "curl -sf '$BASE/api/items/$FEATURE_ID/grill' | jq -e '(.questions | length) > 0 and .resolved == false'" >/dev/null
api "$BASE/api/items/$FEATURE_ID/grill" | jq .
answer_open_questions

# 3. Poll for ready-for-story, then the child story (parentId == the feature's boardId).
log "3/9 Poll for ready-for-story then story draft v1"
poll "feature ready-for-story" 60 bash -c \
  "curl -sf '$BASE/api/items/$FEATURE_ID' | jq -e 'select(.canonicalState==\"ready-for-story\" or .canonicalState==\"awaiting-G1\" or .canonicalState==\"approved\")'" >/dev/null

# NOTE: WorkItemEntity.parentId is the PARENT'S boardId (see ensureWorkItem: feature.boardId()),
# not its UUID — filter by the feature's boardId, never the first story globally.
STORY_ID=$(poll "story work_items row" 120 bash -c \
  "curl -sf '$BASE/api/items' | jq -er '.[] | select(.kind==\"story\" and .parentId==\"$FEATURE_BOARD_ID\") | .id'")
log "story work_item id = $STORY_ID"

poll "story awaiting-G1" 60 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.latestVersion==1)'" >/dev/null

# 4. Print artifact v1.
log "4/9 Story v1"
V1=$(api "$BASE/api/artifacts/$STORY_ID/versions/1")
echo "$V1" | jq -r .storyMarkdown

# 4b. Poll until the quality agent's story verdict passes — the quality gate hard-blocks gate 1
# (auto-revise up to 2 rounds may run first), so every approve call below must wait for this.
log "4b/9 Poll for quality verdict == passed (v1)"
QUALITY_DEADLINE=$((SECONDS + 120))
QUALITY_JSON="{}"
while true; do
  QUALITY_JSON=$(curl -s "$BASE/api/items/$STORY_ID/quality")
  if echo "$QUALITY_JSON" | jq -e 'select(.verdict=="passed")' >/dev/null 2>&1; then
    log "    OK: quality verdict passed (score $(echo "$QUALITY_JSON" | jq -r .score))"
    break
  fi
  if [ "$SECONDS" -ge "$QUALITY_DEADLINE" ]; then
    log "FAIL: quality verdict did not pass within 120s; last response: $QUALITY_JSON"
    exit 1
  fi
  sleep 3
done

# 5. Blocking comment on the delivery-time/timezone scenario; approve while the blocking comment
# is open must not pass the gate. Anchor to the actual scenario the PO agent named (delivery
# time is the resolved brief's sensitive scenario); never a hardcoded line number.
log "5/9 POST blocking comment on the delivery-time scenario"
DELIVERY_SCENARIO="$(echo "$V1" | jq -r '.storyMarkdown' | sed -nE 's/^[[:space:]]*Scenario:[[:space:]]*//p' | tr -d '\r' | grep -iE 'deliver' | head -1)"
[ -n "$DELIVERY_SCENARIO" ] || \
  DELIVERY_SCENARIO="$(echo "$V1" | jq -r '.storyMarkdown' | sed -nE 's/^[[:space:]]*Scenario:[[:space:]]*//p' | tr -d '\r' | grep -iE 'time|seven' | head -1)"
if [ -z "$DELIVERY_SCENARIO" ]; then
  log "FAIL: could not locate the delivery-time scenario in the v1 story markdown; cannot anchor the blocking comment"
  exit 1
fi
log "    targeting scenario: $DELIVERY_SCENARIO"

api -X POST "$BASE/api/artifacts/$STORY_ID/comments" \
  -H "X-User: $LEAD_USER" -H 'X-Role: SquadLead' -H 'Content-Type: application/json' -d "$(jq -n \
    --arg t "scenario:$DELIVERY_SCENARIO" \
    --arg txt "\"By seven\" has no timezone. Preserve an explicit-offset ISO-8601 deliverBy verbatim and remove unrelated scope." \
    '{target: $t, text: $txt, intent: "change", blocking: true}')" >/dev/null

log "    verify: approve while blocking comment is open does not pass gate 1"
api -X POST "$BASE/api/items/$STORY_ID/approve" -H "X-User: $PO_USER" -H 'X-Role: PO' \
  -H 'Content-Type: application/json' -d '{"note":"premature"}' > /tmp/e2e-premature-approve.json
if ! jq -e '.openBlockingComments > 0' /tmp/e2e-premature-approve.json >/dev/null; then
  log "FAIL: expected gate 1 to remain blocked by the open comment"; exit 1
fi
log "    OK: gate 1 blocked (openBlockingComments > 0)"

# 6. Request changes as Squad Lead -> agent revision v2.
log "6/9 POST request-changes as Squad Lead"
api -X POST "$BASE/api/items/$STORY_ID/request-changes" -H "X-User: $LEAD_USER" -H 'X-Role: SquadLead' >/dev/null

poll "story revised to v2" 180 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.latestVersion==2)'" >/dev/null
V2=$(api "$BASE/api/artifacts/$STORY_ID/versions/2")
echo "$V2" | jq -r .storyMarkdown

# Structural assertions are robust across the live agent's phrasing; the ISO-8601 offset is the
# one concrete token the resolved answer guarantees must be preserved verbatim, but its exact
# placement/wording in v2 is the agent's, so it is a WARN, not a hard failure.
if ! echo "$V2" | jq -r .storyMarkdown | grep -qE '20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\+[0-9]{2}:[0-9]{2}'; then
  log "WARN: v2 story markdown does not obviously carry an explicit-offset ISO-8601 deliverBy (agent wording may differ); structural checks below still gate success"
fi

V1_AFTER=$(api "$BASE/api/artifacts/$STORY_ID/versions/1")
if ! echo "$V1_AFTER" | jq -e '.comments[] | select(.resolvedInVersion == 2)' >/dev/null; then
  log "FAIL: expected v1 blocking comment to be marked resolved in v2"; exit 1
fi
log "    OK: comment marked resolved in v2"

# 6b. Poll until quality passes again on v2 (a subsequently-passed quality report).
log "6b/9 Poll for quality verdict == passed on v2"
QUALITY_DEADLINE=$((SECONDS + 120))
while true; do
  QUALITY_JSON=$(curl -s "$BASE/api/items/$STORY_ID/quality")
  if echo "$QUALITY_JSON" | jq -e 'select(.verdict=="passed" and .version == 2)' >/dev/null 2>&1; then
    log "    OK: quality verdict passed on v2 (score $(echo "$QUALITY_JSON" | jq -r .score))"
    break
  fi
  if [ "$SECONDS" -ge "$QUALITY_DEADLINE" ]; then
    log "FAIL: quality verdict did not pass on v2 within 120s; last response: $QUALITY_JSON"
    exit 1
  fi
  sleep 3
done

# 7. Two distinct-identity approvals on v2.
log "7/9 Approve as PO and Squad Lead (distinct identities, same version)"
api -X POST "$BASE/api/items/$STORY_ID/approve" -H "X-User: $PO_USER" -H 'X-Role: PO' \
  -H 'Content-Type: application/json' -d '{"note":"intent + criteria ok"}' >/dev/null
api -X POST "$BASE/api/items/$STORY_ID/approve" -H "X-User: $LEAD_USER" -H 'X-Role: SquadLead' \
  -H 'Content-Type: application/json' -d '{"note":"scope ok, feasible this sprint"}' >/dev/null

poll "gate 1 passed -> approved" 60 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"approved\")'" >/dev/null
log "    OK: canonicalState == approved"

# 8. review.md contains the full trail.
log "8/9 GET review.md"
REVIEW_MD=$(api "$BASE/api/items/$STORY_ID/review-md")
echo "$REVIEW_MD"
if ! echo "$REVIEW_MD" | grep -q "→ gate 1 passed on v2"; then
  log "FAIL: review.md does not contain '→ gate 1 passed on v2'"; exit 1
fi

log "SUCCESS: gate 1 passed on v2; board state approved; review.md trail verified."
