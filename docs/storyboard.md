# AI PDLC workshop v2 — storyboard, team, smoke test

Deck: `ai-pdlc-workshop.html` (single file, no build, works offline; fonts fall back to system).
Keys: `→ ←` slides · `N` speaker notes · `T` reset lesson timer · `F` fullscreen. Rail clock turns red on overrun.

## The feature journey (spine of the deck)

ADO board intake → review agent grills → PO agent drafts story + spec delta → **gate 1: PO + Squad Lead approve** → plan agent creates child tasks → build agent works each task in a budgeted loop (verifier owned by QA) → review agent comments → **gate 2: FS Dev + QA review PR** → CI: build, unit, e2e scenarios → release agent prepares notes / rollout / monitor rules → **gate 3: Squad Lead approves release** → deploy → monitor agent watches → files a new card on the board with evidence → back to intake.

## Lessons (90 min)

| # | Lesson | Min | Interactive | Takeaway |
|---|---|---|---|---|
| 0 | Title | 0 | loop arc draws on load | agents carry the feature; humans own the gates |
| 1 | Map | 3 | clickable agenda | one feature, one interactive per lesson |
| 2 | L1 Why | 5 | — | unasked questions, not slow code, are the bottleneck |
| 3 | L2 Evolution | 9 | 7 eras: who writes the prompt | vibe → prompt → context → SDD → harness → loop → AI PDLC |
| 4 | L3 The journey | 8 | advance feature #4412 through 8 stages | three human gates, everything else agents |
| 5 | L4 Intake & grill | 7 | grill questions, answer one by one | no story until every question is answered or parked |
| 6 | L5 Story & gate 1 | 8 | story preview: add line comments → request changes → agent revises → both approve | maker-checker; signatures per version; review.md audit trail |
| 7 | L6 Plan agent | 6 | plan tree grows | each task names the test that proves it |
| 8 | L7 Work the tasks | 10 | loop simulator (difficulty / verifier / budget / context) | verifier is the bottleneck; stop-on-budget is healthy |
| 9 | L8 CI & release pack | 7 | pipeline + release pack: prepare → checker signs each doc → gate 3 enables | agent drafts every release document (change notes, rollout, monitor rules, evidence, ASMR, AIG, risk, comms); named humans sign |
| 10 | L9 Monitor | 8 | simulate 24h → threshold → ADO card filed | deploy is not done; monitor files back with evidence |
| 11 | L10 Roles & gates | 6 | role matrix highlight | every agent has a human owner |
| 12 | L11 Storyboard & demo | 8 | demo beat table | four presenters, one feature, five minutes |
| 13 | L12 Close | 5 | checklist by role | what to set up first |

If late: compress L2 or L6. Never L5, L7, L9.

## Loop simulator — three scripted runs (L7)
1. Defaults → "done: verified" in 3–5 iterations.
2. Verifier strictness 2 → "false green". Say: this is why QA owns the verifier and the build agent can't edit it.
3. Difficulty 9, budget 3 → "stopped: budget". Say: correct — FS Dev gets it back with a trace.

## Team and gates

| Role | Owns | Gate | Demo beat |
|---|---|---|---|
| PO | intent, acceptance criteria, answers the grill, triages monitor cards | gate 1 (approve story) | creates #4412, answers grill, approves story, reads monitor card |
| Squad Lead | scope, feasibility, plan adjustments, release | gate 1 (approve scope), gate 3 (approve release) | approves scope, reviews plan tasks, presses release |
| FS Developer | the build loop, PR review, takes back budget-stops | gate 2 (PR) | runs build agent on task 1, reviews PR |
| QA | the verifier: scenarios, e2e agent, strictness, monitor thresholds | gate 2 (PR) | runs pipeline incl. deliberate failure |

Agents: review/grill · PO agent · plan agent · build agent + verifier · review agent · release agent · monitor agent — all read the same story text.

## Demo script (5 min, L11)
```
0:00  PO creates feature #4412 on ADO board → review agent posts grill questions
0:50  PO answers in comments → PO agent drafts story #4413 + openspec change
1:40  Gate 1: PO approves intent, Squad Lead approves scope
2:10  Plan agent creates child tasks T1–T5 on the board
2:40  Build agent works T2; verifier runs; PR opens with review-agent comments
3:40  QA runs pipeline: e2e scenarios; then one deliberate failure (rate limit)
4:20  Release agent: notes + rollout plan; gate 3: Squad Lead approves → canary
4:45  Monitor agent trips error threshold → files ADO #4420 linked to #4412
```
Fallback: muted recording, narrated live. Rehearse with network off.

## Smoke-test checklist (T‑30)
- [ ] Open deck; `→` through 14 slides; rail advances; clock resets.
- [ ] L2: 7 era buttons redraw. L3: Advance feature × 8, Reset.
- [ ] L4: Grill it → Answer next × 6 → status "ready for story".
- [ ] L5: + on line 13 → comment → approve buttons disabled → Request changes → line turns green, comment resolved → both approve → gate passed; Reset.
- [ ] L6: Plan grows 6 nodes. L7: Run loop completes; three scripted runs behave.
- [ ] L8: Run pipeline reaches deploy; failing run stops at E2E. Prepare release pack → 8 docs to 'awaiting checker' → Checker signs next × 8 → Gate 3 enabled → press.
- [ ] L9: Simulate 24h draws the line and shows the ADO card at 19h.
- [ ] L10: 4 role tabs highlight columns. `N` notes, `F` fullscreen on projector.
- [ ] Demo environment: ADO board reachable, agents configured, or fallback video queued.

## Agent detail
See `agent-playbook.md` — per-agent reads / does / validates / handoff format / stop rules, INVEST + DoR checks, task format, loop validation checklist. Appendix slide in the deck shows the same as cards.

## Stack & architecture
See `tech-stack-architecture.md` — libraries per layer, ports/adapters (Jira / ADO / GitHub boards; GitHub Actions / Azure Pipelines / GitLab CI), Temporal maker-checker workflow, comment anchoring, `pdlc.yaml` profiles, data model, build order.

## Orchestration decision
See `orchestration-decision.md` — omp (ACP, sandboxed) for coding; Embabel for typed reasoning agents; Temporal as the orchestration server; goose scoped to ACP executor + desktop; per-agent polling rejected.

## Automated smoke test
See `smoke-test.txt`: HTML parses, `node --check` passes, all 54 referenced element IDs exist, 14 slides, budget = 90, notes on every slide. Click-through interactions were not executed here (no browser in sandbox) — that is the manual list above.

Tooling in examples (footnote only): Azure DevOps board via MCP, OpenSpec for the story/spec delta, a coding harness (omp / OpenCode), an orchestrator (goose / LangGraph), your CI. Check licences on deploy day.
