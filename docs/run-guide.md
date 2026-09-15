# Step-by-step: running the restaurant-service demo

> Mirrored from the presenter's Obsidian vault (`Tech Talk/Run Guide.md`, companion to that
> vault's `Tech Talk/README.md`) so it's versioned alongside the code it describes. Edit either
> copy, keep both in sync.

Stages 0–5 (Part 1) are scripted on the presenter machine, no Docker (`omp` output varies
slightly run to run). From stage 6 on, the demo lives entirely in the **eLoop.AI UI at
`http://localhost:5173/`** — the same primary `pdlc-pilot` stack used for every other ai-pldc
demo, not a separate isolated stack.

**Two ways to show stages 6–11, pick per audience:**

- **Guided walkthrough (default, always available, zero risk).** The UI ships with a
  permanent, read-only catalog of 8 curated stage snapshots, taken from real runs against
  `restaurant-service`'s checkpoints and condensed for presentation. No LLM calls, no
  build-worker, nothing can go wrong or drift between rehearsals. Use this for most audiences
  and any time you don't have working model credentials handy.
- **Live demo (optional, real).** One separate, ordinary, mutable feature (`demo-live`) that
  you start on demand. It runs the actual pipeline — real grill questions, a real PO draft,
  a real build-worker loop, real gate approvals — against a fresh clone of the runtime. Use
  this when you specifically want to show the agents thinking live, and only when you have
  working model credentials and are prepared to run the real build loop.

The two never collide: the 8 snapshots are immutable and read-only (every gate/approve/sign
control is disabled on them), and starting the live demo never touches them.

## Prerequisites

- Node ≥22.14, `openspec` CLI, `omp` with a working model credential, Docker (Colima or Docker
  Desktop), `jq`, `curl`, a browser. Only needed for the live demo and Part 1's live `omp`
  moments — the guided walkthrough needs none of this.
- `infra/.env` in `ai-pldc` exists (even if empty — the primary stack's Compose file reads it
  automatically) and points the **agents** service (grill/PO/plan/review/release/monitor, the
  Embabel-based LLM calls) at an OpenAI-compatible endpoint: `PDLC_LLM_API_KEY` for the gateway
  in `infra/pdlc.yaml` (OpenRouter by default), or `PDLC_LLM_BASE_URL` plus a blank key for an
  unauthenticated one. Models are `agents.roles.*.model` in that `pdlc.yaml` and must exist on
  the chosen endpoint.
- The **build-worker** host process is a separate credential surface: it spawns `omp acp`
  directly (via `ACP_AGENT_CMD`, default `omp acp`), which needs its own working `omp` auth in
  that terminal's environment — `ANTHROPIC_OAUTH_TOKEN`, per `scripts/e2e-demo-phase3.sh`'s
  documented prerequisite. This is independent of `PDLC_LLM_API_KEY` above; both are required
  for the live demo's build stage, neither is needed for the guided walkthrough. Never
  print/copy either.
- Docker daemon running: `colima start` if using Colima.
- Ahead of time: `cd /Users/work/Documents/ai-pldc && scripts/demo.sh up` and leave it running.
  First-time image builds can take several minutes — don't discover that live. `scripts/demo.sh
  up` preflights git/Docker/the pinned checkpoint SHAs, creates `restaurant-runtime` if it's
  missing (leaves an existing valid one untouched), starts the stack, and waits for the catalog
  to actually be seeded — not just for containers to exist.

---

## Part 1 — Stages 0–5 (presenter machine, no Docker)

Unchanged from before — this part never touches the Docker stack.

```bash
cd /Users/work/Documents/restaurant-service
```

Walk each stage — checks out the branch, prints its `DEMO.md` section, runs `git log`,
`openspec list`, and `npm test`:

```bash
scripts/walk.sh 0    # baseline: placeOrder/cancelOrder, 2 tests
scripts/walk.sh 1    # openspec init + canonical spec
scripts/walk.sh 2    # /opsx-propose kitchen-ticket: proposal + delta + tasks, no code
scripts/walk.sh 3    # team schema fork + area map (raw CLI, no opsx skill for this)
scripts/walk.sh 4    # /opsx-apply: implements from tasks.md (item-only ticket)
scripts/walk.sh 5    # red test -> same session -> /opsx-archive
```

**Live moments to actually run in front of the room** (already baked into branches 2–5, but
worth re-running interactively so the room sees the agent think — `omp` output is
non-deterministic, a fresh run will not say exactly this, that's the point):

```bash
# Stage 2 — inside an omp session, not raw openspec CLI. /opsx-propose creates the change
# and every required artifact (proposal, spec delta, tasks.md) in one turn.
omp -p '/opsx-propose kitchen-ticket As a customer I want my order sent to the kitchen as a
ticket so the kitchen knows what to cook. The ticket should show the item that was ordered.
Sending an order that does not exist should fail with a 404. Keep this scoped to exactly
that — nothing about the bun, spice level, omitted ingredients, or delivery time yet.'

# Stage 3 — no opsx skill wraps schema management; this part is genuinely raw CLI.
openspec schema fork spec-driven team
# edit openspec/schemas/team/schema.yaml (drop design), openspec/config.yaml
# (context/rules/areas), and the existing change's .openspec.yaml (schema: team)

# Stage 4 — /opsx-apply loops through every pending task until done or blocked.
omp -p '/opsx-apply kitchen-ticket'
# Expect it to PAUSE if docs/constraints.md's idempotency/deadline rules conflict with the
# narrow proposal — that's a real guardrail, not a bug. Answer with explicit scope decisions
# (e.g. "defer both, record as Deferred: notes"), continuing the same session:
omp -c -p 'For #1: Option A - defer, record it. For #2: Option A - defer, record it. Update
the artifacts, then implement.'

# Stage 5 — extend the SAME session to add the next scenario to the SAME change, but tell it
# to stop before touching code (you write the test first):
omp -c -p 'Extend this change: the kitchen ticket must also carry the bun exactly as ordered.
Add a scenario + task for it. Do NOT implement yet, do NOT touch src/orders.ts or
test/orders.test.ts — I will write the test myself first.'

# write the red test by hand, confirm it fails:
npm test 2>&1 | grep -A 20 "not ok"

# paste the TAP failure back into the SAME session as the next prompt:
omp -c -p "<paste the not-ok TAP block>

Implement the pending task to make npm test green. Touch only src/orders.ts."
# Expect a possible SECOND pause here too, if the new field breaks an existing test's strict
# deepEqual — a real, not scripted, conflict between 'touch only src/orders.ts' and 'never
# edit another scenario's test block'. Resolve by authorizing the one-line fix to the other
# test's stale expected-value literal (not its tested behavior).

# archive, agent-driven — asks whether to sync the delta into main specs first:
omp -c -p '/opsx-archive kitchen-ticket'
```

Sanity check before moving on:

```bash
git switch --detach 05-verify-retry-loop~2 && npm test   # exactly 1 not ok, exit 1
git switch main                                          # leave it clean
```

---

## Stages 6–11 at a glance

The 8 guided-walkthrough rows don't map 1:1 to the 6 checkpoint branches — 06 (intake) is
split into two rows (before/after the first quality pass) and 08 (plan/build) into two
(planned/built), condensing 7 historical story revisions down to a curated before/after pair
per row:

|Row (UI order)|Checkpoint|Story state shown|What it demonstrates|
|---|---|---|---|
|1 · Intake and clarification|`06-agent-intake`|`needs-clarification`, no story yet|2 open grill questions|
|2 · First draft / quality feedback|`06-agent-intake`|`awaiting-G1`, v1|Quality failed 52, 1 blocking comment, no approvals|
|3 · Revised spec ready for G1|`07-spec-gate-g1`|`awaiting-G1`, v2|Quality passed 78, comment resolved, only PO approved|
|4 · Planned task waves|`08-plan-build-lanes`|`planned`|G1 fully passed, 5 tasks `new`|
|5 · Build complete, before review|`08-plan-build-lanes`|`in-progress`|5 tasks `done`, frozen build evidence|
|6 · PR ready for G2|`09-review-gate-g2`|`awaiting-G2`|Traceability, `@qa` mention approved, only FSDeveloper approved|
|7 · Release pack ready for G3|`10-release-gate-g3`|`awaiting-G3`|4 release docs, 2 of 4 signed|
|8 · Released, monitor trip and archive|`11-monitor-and-archive`|`done`, **Replay evidence**|All 4 signed, archived spec, monitor bug filed|

Row 8 is explicitly labeled **Replay evidence** in the UI — the deploy/monitor content there is
recorded provenance from the original checkpoint run, not something the click-through
regenerates. Never claim it as a live result.

---

## Part 2 — The guided walkthrough (default, no setup beyond `scripts/demo.sh up`)

- **Open:** `http://localhost:5173/`.
- **Do:** the item list opens on **"Guided walkthrough — 8 frozen stages (read-only)"** —
  exactly one clickable row per stage, in order, each carrying a `Step N of 8` header and a
  `Demo snapshot · <label>` / `Read-only` badge. Click a row's chevron to reveal its
  sibling feature/release/bug cards (e.g. open the release pack from stage 7, or the monitor
  bug from stage 8) without leaving the list.
- **Act as:** identity doesn't matter here — every control (`Approve`, `Request changes`,
  `Sign`, the comment composer, grill answer inputs) is disabled on a snapshot with a visible
  "Read-only demo snapshot" explanation. That's the point: walk the room through real frozen
  evidence — `Preview`/`Diff`/`Quality`/`review.md`/`Release` tabs all work normally for
  reading — without any risk of accidentally mutating it.
- **Show, per row:** same content as the old live walkthrough (grill questions, spec diff,
  quality score, blocking comment → resolution, task waves, PR traceability + `@qa` mention,
  release signatures, monitor trip + archived spec) — it's the same story end to end, just
  fixed rather than regenerated per rehearsal.
- Nothing here needs the build-worker, a model credential, or a reset between talks.

---

## Part 3 — The live demo (optional, needs real model credentials)

Only do this when you specifically want the room to see the agents produce something live.

### Setup

1. **Start the build-worker in its own visible terminal, before starting the live demo** — it
   must already be polling once the story reaches planning. Needs `ANTHROPIC_OAUTH_TOKEN` set
   in this terminal's environment first (the credential `omp acp` itself authenticates with —
   separate from `PDLC_LLM_API_KEY` above; never paste the actual value into this guide):
   ```bash
   export ANTHROPIC_OAUTH_TOKEN=...

   cd /Users/work/Documents/ai-pldc/build-worker
   npm ci && npm run build
   PDLC_API_URL=http://localhost:8081 \
   BUILD_FILTER_PROFILE=local \
   TARGET_REPO_PATH=/Users/work/Documents/restaurant-runtime \
   ACP_AGENT_CMD='omp acp' \
   npm start
   ```
   If `control-plane`'s `BUILD_AGENT_TOKEN` is nonempty, set the same value as an env var in
   this terminal first — never paste secrets into this guide. Leave the worker running through
   gate 2; quiet polling is normal, not failure.
2. **Start the feature**, either from the UI (`Live demo — run this one yourself` section, the
   `Start live restaurant demo` button) or from a terminal:
   ```bash
   cd /Users/work/Documents/ai-pldc && scripts/demo.sh start-live
   ```
   Both are idempotent — calling either twice never starts a second workflow, it just opens the
   same feature again. This replaces the old raw `curl .../webhooks/local` intake call: the
   feature and its brief ("Preserve the customer's customizations on the kitchen ticket... by
   seven in the evening") are already seeded, this just starts its real `FeatureWorkflow`.

**Tabs, by purpose:** `http://localhost:5173/` is the presenter UI (main tab).
`http://localhost:8080` is the Temporal UI — operator-only, optional, for workflow history if
asked. `http://localhost:8081` is the API — used only for the optional terminal exceptions
below, never presented as the demo screen.

### Stage 6 — intake

- **Act as:** any identity to view the item; switch to **PO · PO** before answering questions.
- **Do:** open the `demo-live` feature (the button above navigates there automatically). Under
  `Clarification questions`, read each actual question the grill agent asked, type a real
  answer in `Type your answer...`, click `Answer`. Repeat for every open question — there's no
  fixed set, and a fresh run may ask different or additional questions.
- **Show:** two example answers if the actual questions ask about scope and timing (adapt to
  what's actually asked, don't force these exact words):
  - Scope: only the kitchen ticket contents — the omitted ingredients, spice level, and
    requested delivery time. No cooking, courier, or printer integration.
  - Timing: preserve the requested delivery timestamp verbatim, including its offset — for
    example `2026-09-14T19:00:00+07:00`. Don't leave it ambiguous on purpose to manufacture a
    later gate failure; answer for real. Bun handling stays as already implemented — don't
    reopen it.
- **Continue when:** the PO agent drafts a story automatically once clarification is complete
  — a link appears under `Stories` on the feature page. If more than one story is listed, open
  the one actively running, not one still `queued`.

### Stage 7 — gate 1 (spec review)

- **Open:** the story page (from `Stories` on the feature page), its latest version.
- **Act as:** **Squad Lead · SquadLead** to review.
- **Do:** open `Preview` and `Quality`; read the actual acceptance scenarios and the actual
  quality findings — a fresh run's scenarios, score, and pass/fail won't match the guided
  walkthrough's frozen numbers.
- **If changes are needed:** click the relevant block in `Preview` to populate
  `Anchored to`, write specific feedback, set intent `change`, tick `Blocking`, click
  `Add comment`; then click `Request changes` in the gate panel. Only use this ready-made
  example if the requested delivery timestamp is genuinely missing or vague in what the agent
  actually wrote:
  > Carry the requested delivery timestamp verbatim, including its offset; for example
  > `2026-09-14T19:00:00+07:00`. This is a value printed on the kitchen ticket, not a
  > guarantee of delivery.
  Don't paste this for an unrelated finding, and don't manufacture feedback if the spec is
  already solid.
- **Show:** after `Request changes`, wait for the next version, select it, check `Diff`
  (compares story markdown only — never implementation code) and `Quality` again, and confirm
  the actual blocking comment's concern is now addressed. Repeat only while a genuine issue
  remains; there's no fixed number of rounds or target score.
- **Continue when:** `Quality` shows a passed verdict and no blocking comment is open. Switch
  to **PO · PO** → click `Approve`. Switch to **Squad Lead · SquadLead** → click `Approve`.
  The gate panel shows both approvals; the workflow proceeds to planning/build automatically.

### Stage 8 — watch planning and coding

- **Open:** the same story, `Tasks (N)` tab.
- **Act as:** any allowed reviewer identity only if a decision question appears; otherwise
  this stage is hands-off.
- **Do:** watch task titles/state appear as the planner creates them; click a task for its
  detail/plan. This is driven by the planning agent and the host `build-worker` (already
  running from Setup, driving `omp acp` against the real `restaurant-runtime` worktree, then
  verifying and reporting results) — don't invoke `/opsx-apply` or any opsx skill manually
  here, the application runs it.
- **If `Build needs a decision` appears:** read the actual question, answer it in the UI as an
  allowed reviewer, and wait for the workflow to resume.
- **Show:** don't promise a fixed task count, parallel lanes, or a specific completion time —
  report whatever the run actually does.
- **Continue when:** the story reaches `awaiting-G2`. Leave the build-worker terminal running
  through gate 2 — a `Request changes` round may need it again.

### Stage 9 — review implementation and approve gate 2

- **Open:** the story's `review.md` tab for PR/traceability evidence. The local-git PR link
  (`local://prs/...`) is not a browsable hosted PR page — there's no GitHub/Azure DevOps PR UI
  in this demo.
- **For actual code review** — the one terminal-only exception here, since `Source`/`Diff` in
  the UI only ever show story *markdown*, never implementation code:
  ```bash
  git -C /Users/work/Documents/restaurant-runtime diff restaurant-base...story/"$STORY_BOARD_ID" -- src test
  ```
  (`STORY_BOARD_ID` is the `board …` value shown in the story header, not its UUID URL.)
- **Optional ad-hoc analysis, in the UI:** act as **QA · QA**, anchor a real scenario in
  `Preview`, intent `question`, leave `Blocking` unchecked, e.g.: "@qa Does the delivery-time
  scenario verify that the kitchen ticket preserves the requested timestamp including its
  timezone offset? Cite the relevant scenario and test, or identify the gap." Click
  `Add comment`; show it progress analyzing → pending approval. Read the actual result
  critically; if acceptable, switch to **PO · PO** and click `Approve result` — this approves
  the analysis for the audit trail, it does not approve the PR.
- **For real blocking findings:** anchor specific `change` feedback and click
  `Request changes` as **FS Developer · FSDeveloper** or **QA · QA**; re-review the updated
  build afterward. An approval click never overrides an open blocker.
- **Continue when:** review is satisfactory. **FS Developer · FSDeveloper** → `Approve PR`,
  then **QA · QA** → `Approve PR`. The story moves to `awaiting-G3` automatically.

### Stage 10 — sign the release documents

- **Open:** `Release` tab on the story.
- **Do:** read each generated document, then switch identity and click its `Sign`:
  change-notes → **PO · PO**; rollout-plan → **Squad Lead · SquadLead**; monitor-rules and
  test-evidence → **QA · QA**. The checker role shown on each card is authoritative — if a
  generated pack shows a different checker than expected, sign as whichever role it actually
  displays.
- **Show:** signed status accumulating per card; there is no single "Approve G3" button.
- **If a revision is needed:** add concrete feedback, then `Request changes`; the regenerated
  pack requires every signature again.
- **Optional terminal fixture, immediately before the last signature:** there's no
  metrics-input UI, so injected telemetry simulates the ticket-printer's error rate. First read
  the actual `monitor-rules` document. Only if it defines the expected `http_5xx_rate > 2` rule
  over a recent window (the rule id is `http-error-rate`, renamed from an earlier
  `export-error-rate` placeholder), inject three samples above threshold:
  ```bash
  for v in 2.8 3.1 3.4; do
    curl --fail-with-body -sS -X POST http://localhost:8081/api/metrics \
      -H "Content-Type: application/json" -d "{\"signal\":\"http_5xx_rate\",\"value\":$v}"
  done
  ```
  Label these explicitly as injected demo samples, not observed restaurant failures. If the
  live rule is actually different, skip this and report whatever the monitor evaluation
  actually finds — don't claim this rule is universal.
- **Continue when:** the last signature lands. Deploy and one monitor evaluation pass run
  automatically — there's no `Deploy` or `Run monitor` button.

### Stage 11 — evidence, then archive (separately)

- **Open:** `review.md` on the story. Wait for **both** a `Deployed` block and a
  `Monitor evaluation` block for *this* run — the story's status can already show `done`
  before the monitor evaluation block is appended, so `done` alone isn't proof.
- **Show:** the actual trip count from `Monitor evaluation` — zero trips is a valid, honest
  result; don't expect the guided walkthrough's frozen numbers (2.8/3.1/3.4, mean 3.1%) unless
  you injected exactly that fixture above.
- **Optional, if demonstrating a filed bug:**
  ```bash
  curl --fail-with-body -sS http://localhost:8081/api/items | \
    jq --arg parent "$STORY_BOARD_ID" '.[] | select(.kind == "bug" and .parentId == $parent)'
  ```
  An empty result is not evidence of a card — only report a bug if this actually returns one.
  A filed bug does not automatically start a new feature workflow. (The guided walkthrough's
  stage 8 row already shows a monitor bug card if you'd rather not wait on a live trip.)

**Archive — a separate, isolated demonstration, not a live application action.** Do this only
once every story in the feature is done and monitor evidence is present, and only after
stopping the build-worker (`Ctrl-C`). It works in a disposable clone, never the shared live
runtime or the checkpoint repo:

```bash
printf 'Story board ID: '; read -r STORY_BOARD_ID
ARCHIVE_DIR=$(mktemp -d /tmp/restaurant-archive.XXXXXX) && \
git clone /Users/work/Documents/restaurant-runtime "$ARCHIVE_DIR" && \
git -C "$ARCHIVE_DIR" switch -c presentation-archive origin/restaurant-base && \
git -C "$ARCHIVE_DIR" merge --no-edit origin/story/"$STORY_BOARD_ID"
```

This combines the default branch's review/spec artifacts with the built story's code, without
writing back to the live runtime. If the merge conflicts, stop and keep the workspace as-is —
don't invent a resolution or archive incomplete evidence.

```bash
cd "$ARCHIVE_DIR" && npm test && openspec list
omp
```

Inside `omp`, type `/opsx-archive` and select the change whose title/slug matches the reviewed
story (no slug is hardcoded — pick whichever one matches). Choose `Sync now` when prompted. The
agent validates and synchronizes the delta into the canonical spec before moving the change; it
should stop on incomplete tasks, missing/empty scenario content, invalid specs, or a sync
failure — if it does, report the actual blocker, don't invent requirements or claim success.

Verify:

```bash
openspec validate --all --no-interactive
openspec validate --archived --no-interactive
openspec list
```

The selected change should now be archived with its approved delta present in the
corresponding canonical capability spec; unrelated active changes may still be listed. Label
this whole step an isolated archive demonstration — it never touched the live runtime the demo
actually used. (The guided walkthrough's stage 8 row already shows what a completed archive
looks like, if you'd rather not run this live.)

---

## Teardown / reset

**Between talks, leaving the machine ready for the next one (default):** do nothing — the
catalog is durable (Postgres, not an in-memory map) and survives a `control-plane` restart or
the machine sleeping. If you ran the live demo, its progress also survives; only a full reset
clears it.

**Full clean reset** (wipes the live demo's progress, all Langfuse trace history/accounts, and
recreates `restaurant-runtime` from the pinned baseline — prints the exact scope and requires
the literal `--yes`, safe to re-run):

```bash
cd /Users/work/Documents/ai-pldc
scripts/demo.sh reset --yes
```

This replaces the old manual "stop worker → `docker compose down` → back up/`live-reset.sh` the
runtime clone → `docker compose up -d --build` → poll `/api/items`" dance with one command; it
preflights ownership of `restaurant-runtime` and any running build-worker before touching
anything, and never touches `restaurant-service` (the checkpoint repo) or unrelated Docker
projects.

**If you only want to stop the containers without wiping data:**

```bash
cd /Users/work/Documents/ai-pldc
docker compose -f infra/docker-compose.yml down   # no --volumes: data survives
```

Either way, remember to `Ctrl-C` the build-worker terminal first if it's running.

## Quick health checks if something looks wrong

```bash
# Read-only: confirms enabled + the full 44-item catalog (9 features, 7 stories, 25 tasks,
# 2 releases, 1 bug) without mutating anything.
cd /Users/work/Documents/ai-pldc && scripts/demo.sh verify

# Container status:
docker compose -f infra/docker-compose.yml ps

# Logs if control-plane didn't seed:
docker compose -f infra/docker-compose.yml logs --tail=80 control-plane agents

# restaurant-runtime should be clean, on restaurant-base, at the pinned baseline commit
# (e20025a, "05-verify-retry-loop") unless a live demo has progressed it:
git -C /Users/work/Documents/restaurant-runtime status --short
git -C /Users/work/Documents/restaurant-runtime branch --show-current

# ai-pldc's own git tree should show no uncommitted changes from running the demo:
cd /Users/work/Documents/ai-pldc && git status --short

# library-service completely untouched:
git -C /Users/work/Documents/library-service status --short
```

There is no longer a separate isolated `pdlc-restaurant` stack in the normal flow — the
primary `pdlc-pilot` stack (ports 5173/8081/8080/5432/7233) *is* the restaurant demo. The
`infra/restaurant/` Compose file still exists for manual/legacy use on its own ports
(15173/18081/18080) but `scripts/demo.sh` never starts it, and a full reset explicitly tears
it down too if it happens to be running.
