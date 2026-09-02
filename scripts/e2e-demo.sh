#!/usr/bin/env bash
# scripts/e2e-demo.sh — scripted end-to-end walkthrough against the `local` profile.
# Demonstrates: intake -> grill questions -> answers -> story draft v1 -> blocking comment ->
# request-changes -> agent revision v2 -> PO + Squad Lead approve (distinct identities, same
# version) -> gate 1 passes -> board state `approved`, review.md shows the full trail.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8081}"
FEATURE_BOARD_ID="4412"
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

# 1. Intake: POST item.created for feature #4412.
log "1/9 POST item.created for feature #$FEATURE_BOARD_ID"
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

# 2. Poll until grill questions appear.
log "2/9 Poll for grill questions"
poll "grill questions posted" 60 bash -c \
  "curl -sf '$BASE/api/items/$FEATURE_ID/board-comments' | jq -e 'length > 0'" >/dev/null
api "$BASE/api/items/$FEATURE_ID/board-comments" | jq .

# 3. PO answers in comments (fixture answers from the playbook example q1/q4).
log "3/9 POST answer comments"
api -X POST "$BASE/webhooks/local" -H 'Content-Type: application/json' -d '{
  "kind": "comment.added", "boardId": "'"$FEATURE_BOARD_ID"'", "rev": 2,
  "author": "'"$PO_USER"'", "text": "q1: Current filtered view, max 10k rows."
}' >/dev/null
api -X POST "$BASE/webhooks/local" -H 'Content-Type: application/json' -d '{
  "kind": "comment.added", "boardId": "'"$FEATURE_BOARD_ID"'", "rev": 3,
  "author": "'"$PO_USER"'", "text": "q4: Audit every export; 10 per user per hour."
}' >/dev/null

# 4. Poll for `ready-for-story`, then the child story in `awaiting-G1`.
log "4/9 Poll for ready-for-story then story draft v1"
poll "feature ready-for-story" 60 bash -c \
  "curl -sf '$BASE/api/items/$FEATURE_ID' | jq -e 'select(.canonicalState==\"ready-for-story\" or .canonicalState==\"awaiting-G1\" or .canonicalState==\"approved\")'" >/dev/null

STORY_ID=$(poll "story work_items row" 120 bash -c \
  "curl -sf '$BASE/api/items' | jq -r '.[] | select(.kind==\"story\") | .id' | head -1")
log "story work_item id = $STORY_ID"

poll "story awaiting-G1" 60 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.latestVersion==1)'" >/dev/null

# 5. Print artifact v1.
log "5/9 Story v1"
api "$BASE/api/artifacts/$STORY_ID/versions/1" | jq -r .storyMarkdown

# 5b. Poll until the quality agent's story verdict passes — the quality gate hard-blocks gate 1
# (auto-revise up to 2 rounds may run first), so every approve call below must wait for this.
log "5b/9 Poll for quality verdict == passed"
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

# 6. Blocking comment on line 13; approve while blocking comment is open must not pass the gate.
log "6/9 POST blocking comment (line:13)"
api -X POST "$BASE/api/artifacts/$STORY_ID/comments" \
  -H "X-User: $LEAD_USER" -H 'X-Role: SquadLead' -H 'Content-Type: application/json' -d '{
  "target": "line:13", "text": "make it 20/hour for admin — sales ops asked for it in the grill (q4 follow-up)",
  "intent": "change", "blocking": true
}' >/dev/null

log "    verify: approve while blocking comment is open does not pass gate 1"
api -X POST "$BASE/api/items/$STORY_ID/approve" -H "X-User: $PO_USER" -H 'X-Role: PO' \
  -H 'Content-Type: application/json' -d '{"note":"premature"}' > /tmp/e2e-premature-approve.json
if ! jq -e '.openBlockingComments > 0' /tmp/e2e-premature-approve.json >/dev/null; then
  log "FAIL: expected gate 1 to remain blocked by the open comment"; exit 1
fi
log "    OK: gate 1 blocked (openBlockingComments > 0)"

# 7. Request changes as Squad Lead -> agent revision v2.
log "7/9 POST request-changes as Squad Lead"
api -X POST "$BASE/api/items/$STORY_ID/request-changes" -H "X-User: $LEAD_USER" -H 'X-Role: SquadLead' >/dev/null

poll "story revised to v2" 180 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.latestVersion==2)'" >/dev/null
V2=$(api "$BASE/api/artifacts/$STORY_ID/versions/2")
echo "$V2" | jq -r .storyMarkdown
if ! echo "$V2" | jq -r .storyMarkdown | grep -q "20 for admin"; then
  log "FAIL: v2 story text does not contain '20 for admin'"; exit 1
fi
log "    OK: v2 line-13 text contains '20 for admin'"

V1_AFTER=$(api "$BASE/api/artifacts/$STORY_ID/versions/1")
if ! echo "$V1_AFTER" | jq -e '.comments[] | select(.resolvedInVersion == 2)' >/dev/null; then
  log "FAIL: expected v1 blocking comment to be marked resolved in v2"; exit 1
fi
log "    OK: comment marked resolved in v2"

# 8. Two distinct-identity approvals on v2.
log "8/9 Approve as PO and Squad Lead (distinct identities, same version)"
api -X POST "$BASE/api/items/$STORY_ID/approve" -H "X-User: $PO_USER" -H 'X-Role: PO' \
  -H 'Content-Type: application/json' -d '{"note":"intent + criteria ok"}' >/dev/null
api -X POST "$BASE/api/items/$STORY_ID/approve" -H "X-User: $LEAD_USER" -H 'X-Role: SquadLead' \
  -H 'Content-Type: application/json' -d '{"note":"scope ok, feasible this sprint"}' >/dev/null

poll "gate 1 passed -> approved" 60 bash -c \
  "curl -sf '$BASE/api/items/$STORY_ID' | jq -e 'select(.canonicalState==\"approved\")'" >/dev/null
log "    OK: canonicalState == approved"

# 9. review.md contains the full trail.
log "9/9 GET review.md"
REVIEW_MD=$(api "$BASE/api/items/$STORY_ID/review-md")
echo "$REVIEW_MD"
if ! echo "$REVIEW_MD" | grep -q "→ gate 1 passed on v2"; then
  log "FAIL: review.md does not contain '→ gate 1 passed on v2'"; exit 1
fi

log "SUCCESS: gate 1 passed on v2; board state approved; review.md trail verified."
