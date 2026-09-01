# AI PDLC — agent playbook

One feature, eight agents, three human gates. Every agent below is written the same way so the team can audit it: what triggers it, what it reads (context gathering, in order), what it does, what it validates, what it hands off (and in what format), who owns its output, and when it must stop.

Conventions used throughout:

- **Board** = Azure DevOps. Work items are the source of truth for state; the repo is the source of truth for the spec text.
- **Spec delta** = the OpenSpec change folder (`openspec/changes/<slug>/`) with `proposal.md`, `tasks.md`, `specs/<area>/spec.md` using `ADDED / MODIFIED / REMOVED` sections. Scenarios use GIVEN / WHEN / THEN.
- **Handoff envelope** = every agent output starts with a small YAML block that the next agent parses before reading prose. Same shape everywhere:

```yaml
handoff:
  from: grill-agent            # who produced this
  to: po-agent                 # who consumes it
  work_item: 4412              # ADO id
  state: ready-for-story       # the ADO state this transitions to
  inputs_read: [ado:4412, repo:orders-service@a1b2c3, incidents:INC-2210]
  confidence: 0.8              # agent's own estimate; humans read it, agents don't act on it
  open_questions: []           # must be empty to advance past a gate
  blocked_by: []               # ids of items that must close first
```

Human gates: **G1** story approval (PO + Squad Lead) · **G2** PR review (FS Developer + QA) · **G3** release approval (Squad Lead). No agent transitions a work item across a gate; it sets the state to `awaiting-<gate>` and stops.

---

## 1. Grill agent (intake review)

**Purpose.** Turn a one-line feature card into a fully-clarified request before anyone writes a story. It is the junior analyst who is never embarrassed to ask the obvious question.

**Trigger.** Work item of type Feature enters state `New` on the board (webhook / MCP poll).

**Reads — in this order, stop when the token budget (default 60k) is hit:**
1. The work item: title, description, tags, area path, parent epic, attachments, all comments.
2. Linked items: related features, duplicates, prior stories in the same area path (last 12 months).
3. Repo context for the area path: `openspec/specs/**` for the touched domain, `AGENTS.md`, top-level README, the module the title names (by keyword and by path mapping in `openspec/config.yaml`).
4. Operational context: incidents and monitor cards tagged with the same area in the last 90 days.
5. Org constraints file: `docs/constraints.md` (security, data classification, compliance, browser/device support, performance baselines).

**Does.**
1. Writes a two-sentence restatement of the request in its own words (this catches misreads early).
2. Generates questions in six fixed categories. Each question must cite the evidence that prompted it (a file, a spec line, an incident) or be marked `assumption-check`.
   - **scope** — which data, which screens, which states; explicit non-goals
   - **users / roles** — who sees it, who is excluded, tenancy
   - **acceptance** — observable outcomes, exact fields, ordering, formats, error behaviour
   - **risk** — PII / data classification, auth, rate limits, abuse, legal
   - **dependency** — other teams, APIs, migrations, feature flags, third-party services
   - **NFR** — volume, latency, availability, cost ceiling, accessibility, localisation
3. Posts the questions as a single comment on the work item, one numbered question per line, category prefixed.
4. Re-runs when a new comment arrives from PO / Squad Lead / FS Dev. Marks each question `answered`, `parked` (PO explicitly deferred it — becomes an out-of-scope line in the story) or `open`.
5. Proposes a **type decision**: is this one story, an epic to split, a bug, or a duplicate of #N. Squad Lead confirms with a comment.

**Validates.**
- Every category has at least one question or an explicit "nothing to ask — evidence: …".
- No question can be answered from the material it already read (it must not ask what the description says).
- Duplicates: title similarity > 0.8 against open items in the same area → asks "is this #N?" before anything else.
- Constraint hits: any PII / auth / payment keyword triggers a mandatory risk question.

**Produces → PO agent.** Comment on the work item plus a `grill.md` attachment:

```yaml
handoff:
  from: grill-agent
  to: po-agent
  work_item: 4412
  state: ready-for-story
  type_decision: story          # story | epic | bug | duplicate:#id
  questions:
    - id: q1
      category: scope
      question: Which orders — everything the user can see, or the current filtered view?
      evidence: orders-grid uses server-side paging (src/orders/grid.tsx:41)
      status: answered
      answer: Current filtered view, max 10k rows.
      answered_by: PO
    - id: q4
      category: risk
      question: Orders contain customer PII. Log and rate-limit exports?
      evidence: docs/constraints.md#data-classification; INC-2210 (bulk download abuse)
      status: answered
      answer: Audit every export; 10 per user per hour.
  parked: [q7]                  # become "Out of scope" lines in the story
  constraints_hit: [pii, rate-limit]
```

**Human owner.** PO answers; Squad Lead parks/escalates; FS Dev and QA may add questions.

**Stops / escalates when.**
- All questions `answered` or `parked` → sets `ready-for-story`, hands off.
- 5 working days with open questions → tags Squad Lead, sets `stale`.
- Type decision = epic → stops; Squad Lead splits; grill runs again per child.
- Must **never** answer its own questions, invent requirements, or set the item to `Approved`.

---

## 2. PO agent (story writer)

**Purpose.** Turn the clarified request into one story that is written twice from one source: an ADO user story humans approve, and a spec delta agents build and verify against. Same acceptance criteria, word for word.

**Trigger.** Work item state `ready-for-story` with a `grill.md` attachment whose `open_questions` is empty.

**Reads.**
1. `grill.md` (answers are the requirements; parked questions are the out-of-scope list).
2. The original work item and comments (tone, priority, requester).
3. Current spec for the domain: `openspec/specs/<area>/spec.md` — to write a *delta*, not a fresh spec.
4. Existing scenarios in that spec — to reuse vocabulary (same nouns for the same things).
5. Story template: `docs/templates/story.md`; DoR checklist: `docs/definition-of-ready.md`.
6. Two most recently archived changes in the same area — style calibration only.

**Does.**
1. Drafts the story in the fixed format (below).
2. Converts every answered acceptance question into at least one scenario; every risk answer into a scenario or NFR; every parked question into an out-of-scope line.
3. Writes the spec delta: only `ADDED` / `MODIFIED` / `REMOVED` requirements that differ from the current spec. Each requirement uses MUST / SHALL / SHOULD / MAY.
4. Runs the INVEST and DoR validations (below), fixes what it can, lists what it can't.
5. Creates the User Story work item as a child of the feature, links the change folder, sets state `awaiting-G1`.
6. Posts a short approval summary comment: what the PO is approving, what the Squad Lead is approving, and any DoR items it could not satisfy.

**Story format (ADO description; identical scenarios go in the spec delta):**

```markdown
# <verb phrase title>                     e.g. Export the filtered orders view to CSV
Feature: #4412 · Change: openspec/changes/export-orders-csv · Area: orders

As a <role>
I want <capability>
So that <outcome the requester actually said>

## Acceptance criteria
Scenario: <name>
  GIVEN <precondition>
  WHEN  <action>
  THEN  <observable result>            (one THEN per scenario; add AND lines, not second THENs)

## NFR
- performance: 10k rows < 5 s (p95); progress indicator after 2 s
- security: sales, admin roles only; every export audited (user, filter, row count)
- limits: 10 exports / user / hour → 429 with Retry-After

## Out of scope
- scheduled exports (parked q7)
- XLSX format

## Dependencies
- streaming endpoint in orders-service (same story, task T1)
- none external

## Open decisions for approvers
- none                                    (if this list is non-empty, G1 should not pass)
```

**Validates — INVEST, with the concrete check for each letter:**

| Letter | Check the agent runs | If it fails |
|---|---|---|
| **I**ndependent | `Dependencies` lists no other *open* story; any listed dependency is a task inside this story or an already-merged change | Splits, or marks `blocked_by` and tells Squad Lead |
| **N**egotiable | Story describes outcome and constraints, not implementation (no class names, no library choices, no UI pixel specs) unless the constraints file mandates them | Rewrites implementation lines as NFRs or drops them |
| **V**aluable | "So that" line quotes or paraphrases the requester's stated outcome; at least one scenario is visible to that role | Sends back to grill with a `value` question |
| **E**stimable | Every scenario names concrete data (fields, roles, numbers), and the plan agent's dry-run can produce ≥ 1 task per scenario | Adds an `open decision` for approvers |
| **S**mall | ≤ 6 scenarios and ≤ 2 code areas touched (from `openspec/config.yaml` path map); dry-run plan ≤ 8 tasks | Proposes a split into two stories with the dependency stated |
| **T**estable | Every THEN is observable by a test: an HTTP status, a DOM element, a log row, a DB row, a metric. No "works well", "is fast", "user-friendly" | Rewrites with a number or an observable, or asks the PO |

**Validates — Definition of Ready:**
- Every grill question is answered or parked; the story references each answer.
- Spec delta parses (`openspec validate`) and contains no requirement already present unchanged in `specs/`.
- Vocabulary check: nouns in the scenarios exist in the current spec glossary or are defined in the delta.
- Constraints file: every hit from the grill (`pii`, `rate-limit` …) has a matching NFR or scenario.
- `Open decisions for approvers` is empty, or the summary comment explains why not.

**Produces → G1 (humans) then plan agent.**

```yaml
handoff:
  from: po-agent
  to: plan-agent               # after G1
  work_item: 4413              # the story
  parent: 4412
  state: awaiting-G1
  change: openspec/changes/export-orders-csv
  scenarios: [export-current-view, role-restriction, rate-limit, audit]
  nfr: {p95_10k_rows_s: 5, progress_after_s: 2, exports_per_user_hour: 10}
  areas: [orders-service/export, web/orders-grid]
  invest: {I: pass, N: pass, V: pass, E: pass, S: pass, T: pass}
  dor_unmet: []
  approvals: {po: null, squad_lead: null}
```

**Human owner.** PO approves intent + acceptance criteria. Squad Lead approves scope, feasibility, risk. Both required; either can send back with a comment, which re-runs the PO agent with that comment as a new input.

**Stops / escalates when.** Any INVEST fail it cannot fix → still creates the story but with `dor_unmet` non-empty and a comment; G1 approvers decide. It must **never** approve, never change the answer to a grill question, never add scope that wasn't asked for.

---

## 3. Plan agent

**Purpose.** Break an approved story into tasks the build agent can finish in one loop each, in dependency order, each with the test that proves it.

**Trigger.** Story state `Approved` (both G1 approvals present).

**Reads.**
1. Story + spec delta (the *only* requirements source).
2. Codebase map: `openspec/config.yaml` path map, `CODEOWNERS`, module READMEs for the listed areas.
3. Existing tests in those areas (to extend rather than duplicate).
4. CI config (what runs where; which jobs are slow).
5. Team capacity note on the sprint (from board iteration) and the token/iteration budget policy (`docs/agent-budgets.md`).
6. Last 5 archived changes in the same areas: how tasks were split, where budgets blew.

**Does.**
1. Produces a task list where every task is: one code area, one scenario or NFR (or a slice of one), one proof.
2. Builds the dependency DAG; groups tasks into waves that can run in parallel.
3. Assigns each task a budget: iterations, tokens, wall-clock — from the policy and from history in similar areas.
4. Writes tasks to the board as child Task work items of the story (title, description, proof, budget, wave, `blocked_by`).
5. Writes `tasks.md` in the change folder (the build agent reads this, not the board).
6. Flags tasks that need a human before starting (data migrations, auth changes, anything in `docs/constraints.md#human-first`).

**Task format (`tasks.md` and ADO Task description):**

```markdown
- [ ] T2 · role check + rate limit on /export                 wave 1 · budget 6 iters / 120k tok / 30 min
      area: orders-service/export
      proves: Scenario "role restriction", Scenario "rate limit"
      test: tests/export/test_limits.py::test_11th_export_429  (new)
            tests/export/test_limits.py::test_role_denied      (new)
      touches: src/export/limits.py, src/export/routes.py       (build agent may not edit files outside this list without escalating)
      may_edit_tests: true   (only the tests named above)
      blocked_by: []
      human_first: false
```

**Validates.**
- **Coverage:** every scenario and every NFR in the story maps to ≥ 1 task; every task maps to ≥ 1 scenario/NFR. Orphans on either side fail the plan.
- **Dependency:** DAG has no cycles; no task depends on a task in a later wave; external dependencies (other repos, other teams) are listed on the story, not hidden in a task.
- **Size:** no task touches > 2 areas or > ~8 files; if it does, split.
- **Proof:** every task names a test path. "Manual check" is allowed only for tasks flagged `human_first`.
- **Budget sanity:** sum of task budgets ≤ story budget; any single task > 40% of the story budget is flagged to Squad Lead.
- **Conflict:** two tasks in the same wave must not list the same file in `touches`.

**Produces → build agent (per task) and Squad Lead (whole plan).**

```yaml
handoff:
  from: plan-agent
  to: build-agent
  work_item: 4413
  state: planned
  tasks: [T1, T2, T3, T4, T5]
  waves: [[T1, T2, T3], [T4], [T5]]
  coverage: {scenarios_uncovered: [], tasks_orphaned: []}
  budgets: {story: {iters: 30, tokens: 600k}, tasks: {T1: {...}, ...}}
  human_first: []
```

**Human owner.** Squad Lead reorders, re-estimates, merges or rejects tasks by editing the board; that re-runs the plan agent's validations, not its planning. Not a gate.

**Stops / escalates when.** Coverage or dependency validation fails → posts the plan with the failure and waits. It must **never** change scenarios, and never mark a task done.

---

## 4. Build agent (the loop)

**Purpose.** Finish one task: make the named test pass without breaking anything else, inside a budget, and open a PR with a trace.

**Trigger.** Task in state `Ready` whose `blocked_by` are all `Done`, inside a wave the orchestrator has released.

**Context gathering — assembled once at start, pruned every iteration:**
1. The task block from `tasks.md` (pinned, never pruned).
2. The scenarios it proves, verbatim from the spec delta (pinned).
3. The files in `touches`, current contents.
4. The named tests (existing ones verbatim; new ones as the stub the plan agent wrote).
5. Nearest module README / `AGENTS.md` conventions.
6. Repo-wide: lint config, type config, how to run the test suite (from `AGENTS.md`).
7. Tool outputs from previous iterations — **summarised** to the last failure and the diff so far; full logs stay in the trace, not in context.

**The loop.** Each iteration = reason → act → observe → verify → stop?.

| Step | What happens | Validation at that step |
|---|---|---|
| reason | Pick the smallest next change that moves the named test toward green; write a one-line intent | Intent must reference the scenario or test it serves; if it can't, that's a scope drift signal |
| act | Edit files, run commands in the sandbox | Pre-commit checks: only files in `touches` (or a justified escalation); no edits to tests unless `may_edit_tests` names them; no secrets in diff; lint + type check pass on touched files; no new dependencies without a `deps` line in the task |
| observe | Run the named tests, then the module's test suite | Read the *first* failure, not all of them; diff summary ≤ 40 lines in context |
| verify | Hand the branch to the **verifier** (separate process, read-only, agent cannot call it with arguments) | Verifier returns `green / red / partial` with the failing scenario names |
| stop? | Decide | Stop on: green · budget exhausted · stuck (no change in failing-test set for 2 iterations) · forbidden action attempted · verifier unreachable |

**Stop conditions and what each one produces:**

- **green** → commits with message `T2: <intent> (proves: rate-limit, role-restriction)`, opens PR against the story branch, attaches the trace link, sets task `Awaiting-review`.
- **budget** → commits WIP on a `wip/T2` branch, writes an escalation note: what passes, what fails, last three intents, what it would try next. Sets task `Needs-human`, assigns FS Developer. This is a **correct outcome**, not a failure.
- **stuck** → same as budget but with the note "no progress for 2 iterations; likely wrong approach or missing context: …".
- **forbidden** → reverts the attempted change, stops, escalates with the exact action it tried (e.g. "wanted to edit tests/export/conftest.py").

**Must never:** edit the verifier or CI config; edit tests outside `may_edit_tests`; touch `openspec/specs/`; merge; push to main; disable a failing test; catch-and-swallow exceptions to make tests pass; call external services outside the sandbox allow-list.

**Produces → review agent, then G2.**

```yaml
handoff:
  from: build-agent
  to: review-agent
  task: T2
  work_item: 4413
  state: awaiting-review
  branch: story/4413-export-csv
  pr: 918
  proves: [rate-limit, role-restriction]
  verifier: green
  iterations: 4
  tokens: 71k
  touched: [src/export/limits.py, src/export/routes.py, tests/export/test_limits.py]
  trace: https://trace/…/T2
  escalation: null
```

---

## 5. Verifier (read-only gate the agents share)

**Purpose.** The single definition of "done". Deterministic first, rubric second. Owned by QA; no agent has write access to it.

**Trigger.** Called by the build loop, by CI on every PR push, and by the release agent before G3.

**Reads.** The branch, the story's spec delta, `tests/`, the NFR block.

**Does — in this order, fail fast:**
1. **Static:** lint, type check, secret scan, dependency-licence scan (advisory unless policy says block).
2. **Unit + integration:** the module suite.
3. **Scenario check:** for every scenario in the story, find a test tagged with its name (`@scenario("rate-limit")` or the test path from `tasks.md`). Missing tag = `red` for that scenario, even if all tests pass.
4. **NFR probes:** the measurable NFRs run as tests (10k-row export timed on the fixture; 11th call returns 429).
5. **Diff scope:** files changed ⊆ union of `touches` for tasks in this PR, plus the named tests. Anything else = `red: scope`.
6. **Rubric (LLM, advisory):** does each THEN in the scenario have an assertion that would fail if the behaviour regressed? Flags weak assertions (`assert response is not None`).

**Output.**

```yaml
verifier:
  result: partial
  scenarios: {export-current-view: green, role-restriction: green, rate-limit: red, audit: not-run}
  failing: [tests/export/test_limits.py::test_11th_export_429]
  scope: ok
  nfr: {p95_10k_rows_s: 3.9}
  weak_assertions: []
```

Human owner: QA sets what "green" requires (scenario tagging rule, NFR probes, strictness of the rubric). QA can only make it stricter without a story; loosening it is a story.

---

## 6. Review agent (PR)

**Purpose.** First reviewer on every PR so the human reviewers spend their time on judgement, not on checklists.

**Trigger.** PR opened or updated with `verifier: green`.

**Reads.** The diff, the task blocks it claims to prove, the scenarios, module conventions, the build trace summary (intents per iteration), `CODEOWNERS`.

**Does.**
1. Traceability: for each task, quote the scenario line and point at the code line and test line that satisfy it.
2. Review by category, each finding with severity `blocker / should / nit` and a suggested patch where possible: correctness vs scenarios · error handling and edge cases the scenarios imply · security (auth, input validation, PII in logs) · performance against NFRs · maintainability (naming matches spec vocabulary, no dead code) · tests (assertion strength, flakiness signals).
3. Reads the trace: flags any iteration where the intent drifted from the task, and any place the agent "made the test pass" rather than "made the behaviour right".
4. Posts one summary comment plus inline comments; requests changes only for `blocker`.
5. Suggests reviewers from `CODEOWNERS` and assigns FS Developer + QA.

**Validates.** Every scenario claimed by the PR has a traceability row; every `blocker` has a concrete reproduction or reference; no comment restates what the verifier already reported.

**Produces → G2 (FS Developer + QA).**

```yaml
handoff:
  from: review-agent
  to: human-review
  pr: 918
  state: awaiting-G2
  traceability: [{scenario: rate-limit, code: src/export/limits.py:31, test: test_limits.py:44}]
  findings: {blocker: 0, should: 2, nit: 3}
  drift_flags: []
  reviewers: [fs-dev, qa]
```

**Must never** approve or merge. A `blocker` from the review agent sends the task back to the build loop with the finding as the new first line of context.

---

## 7. Release agent

**Purpose.** Turn a merged story into a release the Squad Lead can approve in one screen: what changes, for whom, how it rolls out, how it rolls back, what will be watched.

**Trigger.** Story branch merged to main; all tasks `Done`; verifier green on main.

**Reads.** The story + spec delta, merged PRs and their review summaries, CI results, `docs/release-policy.md` (canary %, soak time, rollback SLO), current on-call, the monitor rule template.

**Does.**
1. Writes change notes from the story (user-facing, from the "As a / So that" and scenarios — not from commit messages).
2. Writes the rollout plan: environment order, canary percentage, soak duration, feature flag name, rollback trigger and command.
3. Derives **monitor rules** from NFRs and risk scenarios (see next agent) and stores them with the release.
4. Runs pre-release checks: migrations reversible, flag defaults off, config diffs listed, dependency changes listed, spec delta ready to archive.
5. Creates the release work item, links everything, sets `awaiting-G3`, assigns Squad Lead.
6. After G3: triggers the pipeline deploy stage, watches the canary through the soak window, then either promotes (per policy) or halts and pages.
7. On promote: runs `openspec archive <change>` — the delta becomes the spec; opens a PR for that if archive requires review.

**Release pack — the document set the release agent owns.** The agent maintains `release/<release-id>/manifest.yaml`; every document has a template, a source of truth it is generated from, a named human checker, and a status. Gate 3 cannot open while any document is `missing`, `draft` or `awaiting-checker`.

| Document | Generated from | Checker | Notes |
|---|---|---|---|
| Change notes | story "As a / So that" + scenarios | PO | user-facing; never from commit messages |
| Rollout & rollback plan | release policy + flags + migrations | Squad Lead | rollback command dry-run in staging, evidence linked |
| Monitor rules | NFRs + risk scenarios | QA | thresholds, windows, owners, actions |
| Test evidence | verifier output, e2e runs, NFR probes | QA | links, not copies |
| ASMR | org template `docs/release/ASMR.md` | Squad Lead | expansion and fields per your release process — fill the template map |
| AIG | org template `docs/release/AIG.md` | Squad Lead | same; the agent fills what it can from story/PRs and marks the rest `needs-human` |
| Risk & compliance sign-off | constraints hits (PII, auth, rate limit, audit) | PO | each hit maps to a scenario or NFR and its test |
| Stakeholder comms | change notes + audience list | PO | who is told what, when |

```yaml
release: R-2026-08-31-04
story: 4413
pack_version: 2
documents:
  - id: change-notes
    path: release/R-2026-08-31-04/change-notes.md
    template: docs/release/change-notes.md
    sources: [ado:4413, openspec/changes/export-orders-csv]
    checker: PO
    status: signed            # missing | draft | awaiting-checker | changes-requested | signed
    signed_by: {who: po, at: 2026-08-31T15:02Z, version: 2}
  - id: asmr
    path: release/R-2026-08-31-04/ASMR.md
    template: docs/release/ASMR.md
    checker: squad-lead
    status: awaiting-checker
    needs_human: [section 4.2 "business continuity impact"]
gate3: blocked              # opens only when every status == signed
```

Rules: the agent drafts and re-drafts; it never sets `signed`. A `changes-requested` on any document re-runs the agent for that document only, bumps `pack_version`, and clears every signature older than the change (signatures are per version). The manifest is frozen with the release item and kept for audit.

**Validates.** Every scenario appears in change notes or is explicitly internal; rollback plan has a tested command (dry-run in staging); every monitor rule has a threshold, a window and an owner; no `human_first` task shipped without its sign-off comment.

**Produces → G3, then monitor agent.**

```yaml
handoff:
  from: release-agent
  to: monitor-agent
  release: R-2026-08-31-04
  work_item: 4413
  state: awaiting-G3
  rollout: {canary_pct: 10, soak_min: 60, flag: orders.export_csv, rollback: "flag off + deploy prev"}
  monitor_rules:
    - id: export-error-rate
      signal: http_5xx_rate{route="/export"}
      threshold: "> 2% over 15m"
      action: file-card
      owner: PO
    - id: export-p95
      signal: latency_p95{route="/export", rows>=10k}
      threshold: "> 5s over 15m"
      action: file-card
    - id: export-abuse
      signal: exports_per_user_hour
      threshold: ">= 10 for > 3 users in 1h"
      action: file-card + notify-squad-lead
  archive_after_promote: openspec/changes/export-orders-csv
```

**Must never** deploy before G3, skip the soak, or promote past a tripped rule.

---

## 8. Monitor agent

**Purpose.** Keep watching after deploy, and turn what it sees into evidence on the board — not into fixes.

**Trigger.** Release promoted; runs continuously for the watch window in the release policy (default 14 days), then hands rules to the standing alerting.

**Reads.** The monitor rules from the release, metrics/logs/traces for those signals, the story (to explain findings in the story's vocabulary), the board (to avoid duplicates).

**Does.**
1. Evaluates each rule on its window; keeps a rolling baseline from the 7 days before release.
2. On a trip: gathers evidence — the numbers, the window, sample requests or traces, the affected roles/filters, correlation with deploy time and flag state.
3. Files a work item on the board: type Bug (rule tripped) or Feature (usage pattern suggests a follow-up), linked to the story and the release, with the evidence attached and the tripped rule id in the title. State `New` — which means the **grill agent picks it up**, and the wheel turns.
4. Weekly, writes a one-page adoption note to the story: usage of the new capability, who uses it, cost per use, any trend approaching a threshold. This is the "visibility" layer — it is evidence for the PO, not a separate analysis track.
5. Escalates directly (page) only on rules whose action says so; otherwise it files and waits.

**Validates.** Rule trips confirmed against baseline (not a pre-existing condition); no duplicate card for the same rule within its cool-down; evidence attached before filing; card written in story vocabulary, not metric names alone.

**Produces → board (grill agent) and PO.**

```yaml
handoff:
  from: monitor-agent
  to: grill-agent
  work_item: 4420                     # new card
  linked: [4413, R-2026-08-31-04]
  rule: export-error-rate
  evidence: {window: "17:00–19:00", rate: "3.4%", count_5xx: 61, pattern: "filters with > 10k rows", traces: [...]}
  proposed_type: bug
  state: new
```

**Must never** change config, flip flags, roll back, or close its own cards. Rollback is a Squad Lead decision on the release item.

---

## Maker-checker: how humans sit in the loop

Every agent is a **maker**; every gate has named **checkers**. The pattern is identical at each gate so people learn it once.

**Surfaces.** The checker never reads raw agent output. Each stage has a preview surface with line-level comments:

| Stage | Maker | Preview surface | Checkers |
|---|---|---|---|
| Grill | grill agent | ADO comment thread on the feature | PO answers, Squad Lead parks |
| Story | PO agent | story preview (ADO story + rendered spec delta, line-numbered) | PO, Squad Lead — gate 1 |
| Plan | plan agent | task list on the board + `tasks.md` | Squad Lead (adjusts, not a gate) |
| Build | build agent | PR diff + trace summary | FS Developer, QA — gate 2 |
| Review | review agent | PR comments with traceability rows | FS Developer, QA — gate 2 |
| Release | release agent | release pack (each document rendered) | named checker per document; Squad Lead — gate 3 |
| Monitor | monitor agent | filed card with evidence | PO triages |

**`review.md` — one per change folder, append-only, the audit trail.** Every comment, every agent revision, every approval lands here, so a release can be replayed later: who asked for what, what the agent changed, who signed which version.

```markdown
# review.md — openspec/changes/export-orders-csv

## v1 · PO agent · 2026-08-31 10:12
story drafted · INVEST pass · DoR unmet: none

### comment · Squad Lead · line 13 (Scenario: rate limit) · 10:40
make it 20/hour for admin — sales ops asked for it in the grill (q4 follow-up)

### comment · QA · line 15 (Scenario: audit) · 10:44
"row count" — also record filter hash so we can reproduce an export

→ request changes · Squad Lead · 10:45

## v2 · PO agent · 10:46
- line 13: "GIVEN 10 exports (20 for admin) in the last hour THEN the next returns 429"
- line 15: "… user, filter, filter hash, row count …"
- spec delta updated (ADDED rate-limit requirement now has two thresholds)
- INVEST re-run: pass · DoR: pass
- replies: comment@13 resolved · comment@15 resolved

### approve · PO · v2 · 11:02 · "intent + criteria ok"
### approve · Squad Lead · v2 · 11:05 · "scope ok, feasible this sprint"
→ gate 1 passed on v2 · state: Approved
```

**Comment contract (what the agent receives back).**

```yaml
comment:
  id: c7
  by: squad-lead
  stage: story            # grill | story | plan | build | review | release-pack:<doc-id>
  target: line:13         # line:N | scenario:<name> | task:T2 | file:path:line | doc:<id>#section
  text: make it 20/hour for admin
  intent: change          # change | question | note
  blocking: true          # blocking comments must be resolved before any approval
```

**Resolution loop.**
1. Checker adds comments on the preview; any `blocking` comment disables the approve buttons for everyone.
2. Checker presses **Request changes**. The maker agent re-runs with the comments as its first context lines, revises only what the comments touch, re-runs its own validations (INVEST/DoR for the PO agent, coverage/DAG for the plan agent, verifier for the build agent), bumps the version, and replies to every comment with what changed — or `wont-change: <reason>` if it believes the comment conflicts with an approved answer (then the checker decides).
3. Signatures are per version: a new version clears all earlier approvals. Two checkers approve independently; neither can approve on behalf of the other.
4. Approval writes to `review.md` and moves the work item state. The agent moves nothing across a gate.
5. `intent: question` comments do not block; the agent answers them in `review.md` and on the preview.

**Separation of duties.** The account that runs a maker agent cannot be a checker on the same stage. Checkers are people with board roles; the review agent's comments are advisory and never count as an approval. Where a document needs two signatures (e.g. risk sign-off), both are named in the manifest.

## Cross-cutting rules every agent follows

1. **One source of requirements.** Scenarios live in the spec delta; the ADO story mirrors them. If they differ, the spec delta wins and the PO agent is re-run to fix the mirror.
2. **Envelope first.** Every output starts with the `handoff` YAML; the next agent refuses inputs whose `open_questions` or `blocked_by` are non-empty when crossing a gate.
3. **Context budget per agent** (defaults; Squad Lead can change per area): grill 60k · PO 40k · plan 80k · build 120k per task, pruned per iteration · review 60k · release 40k · monitor 30k per evaluation. Pinned items are never pruned; tool output is summarised after it is used.
4. **Read before write.** An agent re-reads the board item immediately before writing to it; if the state changed under it, it stops and re-plans.
5. **Traces are mandatory.** Every model call and tool call is traced; the trace link goes on the work item. Escalations quote the trace, not memory.
6. **Humans own outputs, not agents.** Grill → PO · Story → PO + Squad Lead · Plan → Squad Lead · Build → FS Developer · Verifier → QA · Review → FS Developer + QA · Release → Squad Lead · Monitor → PO.
7. **Agents never cross a gate.** They set `awaiting-G<n>` and stop.
8. **Maker-checker everywhere.** Every agent output has a preview surface, line-level comments, versioned approvals, and an entry in `review.md`. Signatures are per version.

## Loop validation checklist (print this for L7)

Before the loop starts
- [ ] Task has a named test and a `touches` list
- [ ] Scenarios pinned verbatim in context
- [ ] Budget set (iterations, tokens, wall-clock)
- [ ] Sandbox has no secrets and an egress allow-list
- [ ] Verifier reachable and read-only

Every iteration
- [ ] Intent line references a scenario or test
- [ ] Diff ⊆ `touches` (+ named tests)
- [ ] No test disabled, skipped, or weakened
- [ ] Lint + type check on touched files
- [ ] First failure read, not all of them; context pruned

Stop conditions
- [ ] Green from the verifier (not from the agent's own run)
- [ ] Budget exhausted → WIP branch + escalation note (correct outcome)
- [ ] Stuck: same failing set for 2 iterations → escalate
- [ ] Forbidden action attempted → revert, stop, escalate

After the loop
- [ ] PR opened with trace link and `proves:` list
- [ ] Review agent traceability rows present for every claimed scenario
- [ ] G2 reviewers assigned from `CODEOWNERS`
