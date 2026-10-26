# Prompt: 60-second product demo video

Copy the prompt below into Claude Code, run from the repo root, with the eLoop.ai stack running (UI on `:5173`, API on `:8081`).

---

## Product context

**What it is.** AI-Powered Software Delivery (the app is called **eLoop.ai**; internally "AI PDLC") is a governed, agent-driven product development lifecycle for a bank. Every feature runs through one fixed pipeline:
- **AI agents** do the repeatable analysis, writing and coding.
- **Named humans** make exactly three decisions: approve the story, approve the pull request, and approve the release.
- **Everything in between is recorded.**

The design rule is *agents propose, humans dispose*. It follows the bank's existing **maker-checker** pattern: an agent makes, a named human checks, every time.

**The problem it solves.** These are problems with how software gets *delivered*, not with any business process:
- **Requirements surface late.** A one-line card reaches a developer sprints later. The questions that should have come first, about which users, which data and the error path, get asked in PR review or in production.
- **Governance depends on the team.** "Who approved this, and on which version of the spec?" means digging through chat history instead of running a query.
- **Review is manual and repetitive.** Senior reviewers spend time restating what the tests already prove.
- **Production issues return slowly.** An incident becomes a postmortem, and only weeks later, if at all, a backlog card.
- **AI coding assistants alone don't fix this.** A faster typist for unclear requirements just ships the wrong thing faster. *The bottleneck was never typing speed.*

**The flow (one feature, end to end).**
```
New card on the board
  → Grill agent asks clarifying questions (scope, users, acceptance, risk, dependency, NFR)
  → PO answers / parks them
  → PO agent drafts the story + spec delta (Given/When/Then), quality agent scores it
  → GATE 1: PO + Squad Lead approve the story (signatures tied to a content hash)
  → Plan agent splits it into dependency-ordered tasks, each naming the test that proves it
  → Build agent works each task in a budgeted loop (iterations / tokens / time) until the verifier is green
  → Review agent posts a first-pass PR review (blocker / should / nit, traced to the spec)
  → GATE 2: FS Developer + QA approve the PR
  → Release agent drafts the release pack: change notes, rollout plan, monitor rules, test evidence
  → GATE 3: a named checker signs each document; Squad Lead approves the release
  → Deploy
  → Monitor agent watches production; when a rule trips it files an evidence-backed card back to the board
  → back to intake
```
- **Agents.** There are seven: grill, PO, plan, build, review, release and monitor. An on-demand "mention" agent (`@analyst`, `@architect`, `@qa`) can be called up in comments. Each agent has one human owner and a hard scope. For example, the build agent can never edit the verifier, and no agent can approve or deploy.
- **Roles.**
  - **PO:** intent; answers the grill; Gate 1.
  - **Squad Lead:** scope; Gates 1 and 3.
  - **FS Developer:** build loop and PR; Gate 2.
  - **QA:** owns the verifier and the monitor thresholds; Gate 2.
- **Maker-checker at every gate.**
  - The agent drafts, a human comments, and the agent revises only what was commented on.
  - Two named people sign the exact version they read.
  - A new version clears earlier signatures.
- **Audit trail.** Every comment, revision and signature goes to `review.md`, committed in git, and to an append-only Postgres table. That makes a feature fully replayable later.
- **Tiles worth highlighting.** The UI shows a feature delivery map, gate panels, a quality score, the task execution graph, a release pack with per-document signatures, and an Activity / review.md audit view.

**Under the hood.**
- **Control-plane:** Spring Boot.
- **Reasoning agents:** Embabel.
- **Workflow engine:** Temporal, one workflow per feature, so a gate can wait for days safely.
- **Build worker:** Node, running omp over ACP in a sandboxed git worktree per task.
- **Storage:** Postgres.
- **UI:** React, Radix UI.

The board, repo and CI sit behind ports, so a toolchain is a config profile in `pdlc.yaml`. Today that's Azure DevOps, GitHub or local; Jira and GitLab follow the same pattern. Each agent role has its own token budget.

**Demo content used in the video.** The read-only "Corporate Lending" guided walkthrough seeded in the app (`control-plane/src/main/resources/demo/loan-demo.json`): 8 frozen stages of one feature, *"Automate corporate loan application validation"*.
- **Grill:** 5 questions on check order, DSCR ≥ 1.25× and leverage ≤ 3.5× thresholds, never auto-reject, four-eyes above USD 5M, and 7-year audit.
- **Story:** v1 fails quality at 52; v2 passes at 81 and is signed.
- **Tasks:** 5, from document completeness to the audit trail.
- **Review:** the review agent flags the hard-coded leverage threshold.
- **Release:** pilot on one corporate banking desk, with 200 sample applications as test evidence.
- **Monitor:** trips at an 18% SME exception rate against a 10% threshold, and files "Review DSCR rule for SME segment".

**Audience and message.** Bank technology and business leaders at Enterprise AI Day. Take-away: *clear requirements before a line of code, the bank's maker-checker process on every feature, and a product delivered complete: built, tested, released and monitored.*

---

## The prompt

Build a **60-second silent product demo video** (1920×1080, 30 fps, MP4 with no audio track) for **AI-Powered Software Delivery** (our app, eLoop.ai), using **Remotion** in a `demo-video/` folder.

**Style:** clean corporate banking. Prosper Blue `#0072AA`, green `#A4D0A0`, navy `#003366`, white and `#F4F4F4` backgrounds, Inter font, 8px corners, soft shadows `0 4px 6px rgba(0,0,0,0.1)`, thin 1.5px line icons.

**Story (problem → solution → proof):**
1. **0–4s, title slide:** dark navy with a teal accent bar. Text: "AI-POWERED SOFTWARE DELIVERY"; "From legacy complexity to intelligent, agentic engineering"; pill "CAI × SPM | Loop Engineering in action"; "Journey • Case study • Outcomes • Way forward"; footer "ENTERPRISE AI DAY | 28 OCTOBER 2026". On the right, a modular-digital-services panel and a business-outcomes panel.
2. **Real board:** the one-line feature card "Automate corporate loan application validation". Caption: "Every feature starts as one line."
3. **The problem is how bank software gets delivered, not the loan process.** Show four pain points: requirements surface late, governance depends on the team, review is manual and repetitive, and production issues return slowly. Then the hook: "The bottleneck was never typing speed."
4. **The answer:** AI-Powered Software Delivery. Agents do the work; named humans make the decisions. Show the pipeline drawing itself: 7 agents and 3 human gates (maker-checker).
5. **Walkthrough on real eLoop.ai screens** of the Corporate Lending demo walkthrough (board ids `loan-06a…loan-11`):
   - grill agent questions
   - quality agent fails v1 at 52, then v2 is signed at Gate 1
   - tasks go from 0/5 to 5/5 verified
   - review agent finds a hard-coded leverage threshold, then Gate 2
   - release pack signatures, then Gate 3
   - monitor trips at 18% and files a card back to the board
6. **Outcomes, then end card:** "From idea to production, governed."

**Technique:**
- Capture real screens with a puppeteer-core script using the installed Chrome. Use light mode, a 1600×1000 viewport at 2× scale, and open tabs via `?tab=`.
- Record element positions from the DOM, so zooms and highlights land exactly on the right elements.
- In Remotion, show each screenshot in a browser frame with smooth zoom and pan, one shared spotlight and green highlight rings.
- Add a navy lower-third subtitle bar: step icon, a pipeline progress-dots row, words revealed one by one, and key phrases in green.
- Remotion's downloaded headless browser crashes on this Mac, so render with the installed Chrome.

**Check:** render stills of every scene and look at them, fix any layout problems, then render the MP4. Confirm with ffprobe that it is exactly 60s with no audio stream. Keep previous renders as backups.
