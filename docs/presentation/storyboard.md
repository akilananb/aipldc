# AI PDLC — Loops, Workflows, and the Software Factory — storyboard

90-minute interactive, animated, self-guided deck. 23 slides, one shared, progressively-growing
loop diagram (`LoopCanvas`) as the visual spine. Multi-file, `file://`-compatible, zero build step.

## Team roster

| Role | Owns |
|---|---|
| **Director** (main agent) | storyboard, engine (`index.html`, `deck.css`, `loop.js`, `deck.js`), integration, smoke test |
| **Writer-Evolution** | `slides/part1.js` — Title, Map, L1–L3 |
| **Writer-Foundations** | `slides/part2.js` — L4–L6 |
| **Writer-Tools** | `slides/part3.js` — L7–L10 |
| **Writer-Pipeline** | `slides/part4.js` — L11–L16 |
| **Writer-Flow** | `slides/part5.js` — L17–L18 |
| **Writer-Close** | `slides/part6.js` — L19–L21 |

## Engine contract (binding for every part file)

```js
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};
window.PARTS[N] = `<section class="slide" data-lesson="…" data-min="…" data-notes="…">…</section>…`;
window.PARTS_INIT[N] = function () { /* wire this part's buttons/anims by id */ };
```

- Every `id` inside part `N` MUST be prefixed `pN-` (parts are concatenated via `innerHTML`, which
  never executes injected `<script>` tags — hence `PARTS_INIT` for wiring).
- `deck.js` concatenates `PARTS[1..6]` in numeric order into `#slides`, then calls each
  `PARTS_INIT[1..6]` in order, then boots navigation/rail/agenda/hash-routing.
- Navigation: `→`/`Space` advances exactly one slide; `←` goes back one slide; `Home`/`End` jump.
  `#N` deep-links slide `N` (0-based). Every slide's `.reveal` elements animate in automatically
  (staggered fade/rise-in, 150ms/element, capped at 1400ms total, via `deck.js`'s `playReveal`)
  the moment the slide becomes active — no per-item interaction. `.reveal{opacity:0}` →
  `.reveal.in{opacity:1}` (CSS transition). `deck.js` dispatches a `deck:activate` custom event
  (`{detail:{index, slide}}`) on every `activate(i)` call so part files can react to their own
  slide becoming active (used by L17's autoplay).
- Rail: one segment per slide, width ∝ `data-min` (min 1); `done`/`now` classes; mono clock vs
  `data-min` budget, turns red past budget. `T` resets the lesson timer. `N` toggles `#notes`
  (renders the active slide's `data-notes`). `F` toggles fullscreen.
- Agenda: `#p1-agenda` (Map slide) is auto-built by `deck.js` from every slide's
  `data-lesson`/`data-min`; `#p1-agenda-total` renders the summed minutes ("90 min").
- Visual language: palette/type stack copied verbatim from `docs/ai-pdlc-workshop.html` (`--ink`,
  `--fog`, `--loop`, `--pass`, `--stop`, IBM Plex Sans/Mono); reused layout classes `.panel`,
  `.callout`, `.lesson-tag`, `.btn`, `.two`, `.three`, `.pill`; new shared toolkit classes
  `.stack`, `.row`, `.cols-2/3/4`, `.ladder`, `.timeline`, `.card-chip`, `.kanban-track`/`.kanban-chip`,
  `.war-card`, `.rule-card`, `table.tbl`; `.reveal`; `.punch` (the per-lesson bolded takeaway).
  Writers MAY add a scoped `<style>` block inside their template literal for bespoke one-offs
  (`<style>` tags DO apply via `innerHTML`, unlike `<script>`) — scope every selector under the
  slide's own `pN-` id prefix to avoid cross-part collisions.

## `LoopCanvas` (in `loop.js`, Director-owned — writers only call `.mount`)

```js
LoopCanvas.mount(containerEl, { stage, ns, silhouette = false, token = false, drawIn = false, revealFrom = stage });
LoopCanvas.mountFlow(containerEl, { stage, ns, revealFrom = stage });
```

- Renders every node/edge with `stageIn <= stage` and (`stageOut` absent or `> stage`). Fixed
  positions — the same master geometry (below) is reused by every mount; nothing is ever
  re-laid-out, only extended.
- Elements whose `stageIn` falls in `[revealFrom, stage]` get class `new` (amber glow pulse).
  `revealFrom` defaults to `stage`; **L6 passes `revealFrom: 1` at `stage: 2`** so the very first
  canvas mount marks both stage 1's and stage 2's pieces `new` at once (the loop "appears" from
  nothing). Every included node/edge renders directly (no step-wrapping) — `deck.js`'s
  `playReveal` animates the containing slide's `.reveal` elements in on activation.
- `mountFlow` is the wrapper every per-lesson canvas actually calls: `mount(..., {token:true})`
  (edges fully visible immediately) plus a `deck:activate` listener that fires
  `DeckFlow.reset(ns)` + `DeckFlow.play({ns, seq: LoopCanvas.seqForStage(stage), speed:1})` every
  time that slide becomes active — one narrative dot retracing that stage's path, replayed on
  every visit, never one ambient dot per edge. `L17` (`p5l17`) is the one exception: it calls
  `mount` directly with `token:true, drawIn:true` so its own Play/Pause/Reset/speed controls (not
  a `deck:activate` autoplay of `mountFlow`'s making — `part5.js` wires its own listener) drive
  the shared `DeckFlow` instance with the reveal-from-nothing effect below.
- `silhouette: true` renders everything at stage 8 with class `.sil` (gray fill, labels hidden)
  — the L1 teaser; never combined with `token`.
- `drawIn: true` (only ever paired with `token: true`) additionally hides every edge behind a
  stroke-dash "draw" hack until `DeckFlow` lights it — L17's reveal-from-nothing effect. Leave it
  off (the `mountFlow` default) anywhere edges must render fully visible immediately: a
  per-lesson `seqForStage(stage)` never lights every edge in its diagram (e.g. the `review-engineer`
  "read the diff" annotation edge is never part of any sequence), so `drawIn` there would leave
  those edges permanently invisible.
- All ids are namespaced `${ns}-n-<name>` / `${ns}-e-<a>-<b>` (the canvas mounts on ~11 slides;
  namespacing avoids duplicate-id collisions and lets every mount run its own independent
  `DeckFlow` run keyed by `ns`). `token: true` adds `#${ns}-token`.
- `LoopCanvas.seqForStage(stage)` returns that stage's autoplay path: the correct "how does the
  token reach the agent" entry prefix for the stage — it changes as the entry edge retires
  (`engineer-agent` at s2, `story-agent` at s3, `g1-agent` at s4, `queue-agent` at s5+) — followed
  by the shared verify-loop-back tail, extended with `review-g2` at s6+, then release/`g3`/deploy
  at s7+, then monitor/card/back-to-board at s8. `LoopCanvas.SEQ` is `seqForStage(8)` — the
  canonical stage-8 sequence L17 replays.
- `LoopCanvas.pos('engineer')` returns `{x,y}` — used to re-home the token on reset.
- Callout convenience: add class `hl` to a mounted node/edge `<g>` (e.g.
  `document.getElementById(ns+'-n-agent').classList.add('hl')`) for "this box"/"this wire"
  thumbnail highlights (L7/L8) — coexists fine with `mountFlow`'s autoplay `.lit`/`.active`.

`DeckFlow` (in `deck.js`): `DeckFlow.play({ns, seq, speed})` lights each edge (`.lit`), animates
`#${ns}-token` along it via `requestAnimationFrame` + `path.getPointAtLength`, then marks the
target node `.active`. `DeckFlow.pause(ns)` cancels the run. `DeckFlow.reset(ns)` strips
`lit`/`active`/`done` and re-homes the token at `engineer`. `mountFlow` drives all three
automatically per-`ns`; L17's own Play/Pause/Reset buttons call them directly.

## Narration style guide (binding for all writers)

- Numbered atomic lessons ("Lesson 7 — …"); one idea per lesson.
- Short declarative sentences, often one per line. No corporate filler.
- Each lesson has exactly one reframing **punchline** rendered as `.punch` (verbatim strings —
  see the lesson table; do not paraphrase).
- Diagrams build progressively via `LoopCanvas` stage growth; each newly-added stage animates in
  automatically via `.reveal` (a pulse glow on `new` pieces) and a single autoplay token retraces
  that stage's path via `mountFlow`; never show the finished diagram before its stage.
- Real numbers from this repo's live runs are cited as evidence, not decoration (see fact sheet).
- `data-notes` = 3–6 sentence speaker script per slide expanding the beats in this same voice.

## Lesson table — single source of truth for `data-lesson`/`data-min`/punchlines

The deck's spine: **L6 starts `LoopCanvas` at stage 1→2; every Part-4 lesson (L11–L16) adds
exactly one stage to the SAME canvas; L17 runs the finished stage-8 machine end-to-end.**
`Canvas s<k>` = `LoopCanvas.mountFlow(el, {stage:k, ns:'p<part>l<lesson>'})`.

| Slide idx | # | Part | `data-lesson` | `data-min` | Punchline (`.punch`, verbatim) | Canvas ns |
|---|---|---|---|---|---|---|
| 0 | Title | 1 | Title | 0 | — | `p1l1` (silhouette, right panel) |
| 1 | Map | 1 | Map | 3 | — | — |
| 2 | L1 | 1 | The opening provocation | 4 | "The loop is not the product. The product is the workflow." | `p1l1` |
| 3 | L2 | 1 | Where we started | 4 | "Every era shortened the distance between intent and running code. None removed the human decision." | — |
| 4 | L3 | 1 | Who writes the prompt | 4 | "What changed is who writes the prompt." | — |
| 5 | L4 | 2 | The three actors | 5 | "Use code where determinism is enough. Use agents where reasoning is required. Keep engineers where judgment matters." | — |
| 6 | L5 | 2 | SDD — the spec is the prompt | 4 | "A story card is an opinion. A spec delta is a contract." | — |
| 7 | L6 | 2 | The simplest loop — stages 1 & 2 | 5 | "Agents plus code beat agents alone." | `p2l6` (stage 1→2, `revealFrom:1`, autoplay) |
| 8 | L7 | 3 | omp + Orca — the coding agent | 4 | "The coding agent is a session you can drive — by hand from a terminal, or by protocol from a machine." | `p3l7` (thumbnail, stage 2, agent `hl`, autoplay) |
| 9 | L8 | 3 | ACP — the wire between them | 4 | "ACP is for driving a session-based agent. If there's no session, it's the wrong wire." | `p3l8` (thumbnail, stage 2, engineer-agent edge `hl`, autoplay) |
| 10 | L9 | 3 | Embabel — typed reasoning | 4 | "Free-text output is a prototype. A typed contract is a system." | — |
| 11 | L10 | 3 | Temporal — the durable outer loop | 3 | "Waiting for humans across days, exactly-once progression, auditability — the engine's native features, not code you maintain." | — |
| 12 | L11 | 4 | Stage 3 — put a spec in front | 5 | "Nothing becomes a story until every question is answered or parked." | `p4l11` (stage 3, autoplay) |
| 13 | L12 | 4 | Stage 4 — add the human gate | 5 | "Maker-checker: the agent makes; named humans check — per version, with blocking comments." | `p4l12` (stage 4, autoplay) |
| 14 | L13 | 4 | Stage 5 — split, isolate, parallelize | 5 | "An inner loop never touches board state. It returns a typed result and the outer loop decides." | `p4l13` (stage 5, autoplay) |
| 15 | L14 | 4 | Stage 6 — review, and the second gate | 4 | "Every agent has a human who owns its output — and any reviewer can summon an agent with an @." | `p4l14` (stage 6, autoplay) |
| 16 | L15 | 4 | Stage 7 — release, the third gate, deploy | 4 | "Agents draft every document. Named humans sign each one." | `p4l15` (stage 7, autoplay) |
| 17 | L16 | 4 | Stage 8 — monitor closes the wheel | 4 | "Deploy is not done. The monitor files evidence back to the board, and the wheel closes." | `p4l16` (stage 8, autoplay) |
| 18 | L17 | 5 | THE FLOW — run the machine | 8 | "The loop is only one mechanism inside the machine." | `p5l17` (stage 8, `token:true, drawIn:true`, reveal-from-nothing, autoplays on activate) |
| 19 | L18 | 5 | The kanban view — work as state | 3 | "Stop asking what your agent is doing. Ask what state each piece of work is in." | — |
| 20 | L19 | 6 | What a live run taught us | 3 | "Agents plus code beat agents alone — and code plus retries demands idempotency." | — |
| 21 | L20 | 6 | Eight design rules | 3 | "Don't build the factory before you know which workflow you're automating." | — |
| 22 | L21 | 6 | Final takeaway | 2 | "Don't ask 'how do I build an agent loop?' Ask 'what repeatable developer workflow can I turn into a reliable system of code, agents, and human judgment?'" | — |

Minutes: 0+3 +4+4+4 +5+4+5 +4+4+4+3 +5+5+5+4+4+4 +8+3 +3+3+2 = **90**.

Part → slide-count contract: part1 = 5 (Title, Map, L1–L3) · part2 = 3 (L4–L6) · part3 = 4
(L7–L10) · part4 = 6 (L11–L16) · part5 = 2 (L17–L18) · part6 = 3 (L19–L21) = **23 total**.

## LoopCanvas master geometry & stage map (implementer makes zero layout decisions)

ViewBox `0 0 1280 680`. Rects 150×56, diamonds for gates, agent boxes amber.

**Nodes** `name (cx,cy) shape stageIn[→stageOut]`: `engineer` (340,300) circle s1 · `agent`
(560,300) rect s1 · `review` (1000,300) rect "review · PR" s1 · `verify` (800,300) rect
"verify · npm test" s2 · `board` (140,80) rect "board · new card" s3 · `grill` (330,80) rect s3 ·
`story` (520,80) rect "story + spec" s3 · `g1` (700,80) diamond "G1 · PO + Squad Lead" s4 ·
`quality` (520,150) chip s4 · `plan` (860,80) rect s5 · `queue` (560,215) strip
"task queue · claimed over REST" s5 · `agent2`/`verify2` (560/800,390) lane 2 s5 ·
`agent3`/`verify3` (560/800,460) lane 3 s5 · `shield2`/`shield3` (495,390)/(495,460) s5 · `g2`
(1140,300) diamond "G2 · FSDev + QA" s6 · `mention` (1000,210) chip s6 · `release` (1140,440) rect
"release pack · 4 docs" s7 · `g3` (1000,530) diamond "G3 · signatures" s7 · `deploy` (800,530) rect
s7 · `monitor` (560,530) rect s8 · `card` (330,530) rect "trip card" s8.

**Edges** `e-<a>-<b> [stageIn→stageOut]`: `engineer-agent` ("prompt") s1→out3 ·
`agent-review` s1→out2 · `review-engineer` ("read the diff", curved via `[860,210]` to clear
`agent`/`verify`) s1→out6 · `agent-verify` s2 · `verify-review` (PASS) s2 · `verify-agent` (FAIL,
dashed) s2 · `engineer-board` ("files a card") s3 · `board-grill` s3 · `grill-story` s3 ·
`story-agent` (bends down) s3→out4 · `story-g1` s4 · `g1-agent` (bends down) s4→out5 ·
`g1-plan` s5 · `plan-queue` s5 · `queue-agent` s5 · `queue-agent2` (curved via `[750,280]` to
clear `agent`) s5 · `queue-agent3` (curved via `[50,280]` to clear `agent`/`agent2`) s5 ·
`agent2-verify2`/`agent3-verify3` s5 · `verify2-review` (PASS) s5 · `verify3-review` (PASS,
curved via `[890,460]` to clear `verify2`) s5 · `verify2-agent2`/`verify3-agent3` (FAIL,
dashed) s5 · `review-g2` s6 · `g2-release` s7 · `release-g3` s7 · `g3-deploy` s7 ·
`deploy-monitor` s8 · `monitor-card` s8 · `card-board` (curved via `[60,300]`, closes the
wheel) s8.

**One token, not one dot per edge**: edges render as static paths with no per-edge motion. Every
`mountFlow` canvas — every lesson slide with a diagram — adds exactly one `#${ns}-token` circle
and replays `LoopCanvas.seqForStage(stage)` via `DeckFlow.play` on every `deck:activate` for that
slide; only the edges on that stage's path get `.lit`, everything else (e.g. `review-engineer`,
the lane 2/3 branches before their own stage's sequence reaches them) stays in its normal static
style. L17 additionally sets `drawIn:true` so its edges start hidden and draw in as its token
(which walks the full `seqForStage(8)`) reaches them.

**`LoopCanvas.SEQ`** = `seqForStage(8)` (stage-8 autoplay, dwell 700ms/node, 350ms at 2×):
engineer→board→grill→story→g1→plan→queue→agent→verify → **FAIL loop-back** (verify-agent, agent,
agent-verify, verify) → PASS→review→g2→release→g3→deploy→monitor→card→board. On completion
`board` gets `.done` (the wheel turned once). Earlier stages get their own shorter sequence from
`seqForStage(stage)` — e.g. `seqForStage(2)` (L6–L8) is just engineer→agent→verify→FAIL
loop-back→PASS→review.

## Grounded fact sheet (writers may not invent beyond it)

- Pipeline, agents, gates, queue split, dual audit trail: `AGENTS.md` (repo root) +
  `docs/agent-playbook.md`.
- Three-loop table, ACP client/agent pairs, Embabel-vs-goose rationale, scale notes:
  `docs/orchestration-decision.md` §2, §3, §5 — Embabel: typed `Story`/handoff objects, INVEST
  checks as `@Condition`s, GOAP replans on new comments (no new prompt engineering); ACP pairs:
  build-worker (client) → `omp --mode acp` (agent) over stdio; editor (Zed/JetBrains) → omp/goose
  over stdio; Embabel reasoning agents are reached over MCP, never ACP, because ACP is for driving
  a *session-based* agent and they are not one.
- Canonical states (`core/.../CanonicalState.java`, all observed live on `/api/items`): `new`,
  `needs-clarification`, `ready-for-story`, `awaiting-G1`, `queued`, `approved`, `planned`,
  `in-progress`, `awaiting-G2`, `awaiting-G3`, `done`, `stale`.
- Verifier mechanics (`build-worker/src/verifier.ts`): spawns the target repo's own `npm test`
  (`node --test --test-reporter=tap`), parses TAP top-level `ok`/`not ok` lines into a
  name→passed map; green only if exit 0 and every named test passed.
- Scope guard (`build-worker/src/buildTask.ts`): any changed file outside the task's effective
  `touches` (plus its one owned `testPath`, unless another task's scenario block is already
  there) is reverted via `revertPaths` and escalated as a forbidden action; a shared test file's
  prior content must survive byte-for-byte (`wasTestContentPreserved`, substring containment) —
  an agent can never weaken another task's test to make its own pass.
- Real run evidence: story board 9004 — quality v1 58 failed → v2 88 passed (INVEST/DoR
  auto-revise then re-check); 8 tasks all verifier-green, 24–36 iterations, `PT10M` wall-clock
  budget/task (`docs/agent-playbook.md` §3–4 budget/DAG rules); release `R-2026-09-02-4423`;
  monitor rule `export-error-rate` (`http_5xx_rate > 2% over PT15M`, observed `3.1%`) filed a bug
  card titled "Monitor trip: export-error-rate" (`docs/agent-playbook.md` §7–8 release/monitor
  agents, monitor rule shape).
- Mention agents (`control-plane/.../review/AgentMentions.java`,
  `agents/.../mention/MentionAgent.java`): fixed pilot set `analyst`, `architect`, `qa`, `dev`;
  first `@agent` mention in a comment is parsed case-insensitively; one real LLM call renders the
  markdown draft directly (no JSON parsing) via a per-agent Mustache prompt.
- Maker-checker mechanics (`docs/agent-playbook.md` "Maker-checker" section): every stage has a
  preview surface with line-level, `blocking` comments; signatures are per version — a new
  version clears all earlier approvals; `review.md` is the append-only audit trail.
- Orca: describe only as "the terminal the engineer drives omp from" — repo docs don't cover it
  further; do not elaborate.

## Smoke test — actual results (run 2026-09-03, port 8090, headless browser tool)

1. **Static** — `node --check` passes on `deck.js`, `loop.js`, and all 6 `slides/partN.js`.
   `grep -c '<section class="slide"'` totals 23 (5+3+4+6+2+3, matching the part contract).
2. **Serve** — `python3 -m http.server 8090` from `docs/presentation` via `hub start`; port
   readiness confirmed.
3. **Browser smoke** — zero console/page errors; `document.querySelectorAll('.slide').length`
   is 23; `#p1-agenda-total` renders "90 min" (23 agenda cards); rail segment count is 23;
   stepped `Space` through every step + slide from `Home` to the end (400 presses, no errors),
   landed on slide index 22; `End` from any point lands on the last slide with `.active`; `N`
   opens `#notes` with non-empty `data-notes` text (454 chars on the title slide).
4. **Progressive-build check** — PASS: L6 (`#p2l6-n-verify` exists, `#p2l6-n-plan` absent); L13
   (`#p4l13-n-agent3` exists, `#p4l13-n-monitor` absent); L16 (`#p4l16-e-card-board` exists).
   Confirms the same canvas instance grows stage-by-stage rather than being re-drawn per lesson.
5. **Flow-run check** — `#18` → `#p5-play` at 1× completes the full circuit (incl. the scripted
   FAIL loop-back) in ~13.5s, well inside the 25s budget (~7.3s at 2×); `#p5l17-n-monitor.active`
   and `#p5l17-n-board.done` both true on completion; `#p5-reset` clears both and re-homes the
   token to `engineer`'s position. Screenshots captured: Title (arc-draw complete), L13
   mid-build, L17 completed circuit.
6. **Offline check** — `file:///Users/work/Documents/ai-pldc/docs/presentation/index.html` loads
   with zero console/page errors; 23 slides render; `End` navigation works (no fetch/CORS
   dependence — IBM Plex falls back to system fonts offline as designed).
7. `hub stop` issued against the `deck-server` process after verification completed.

### Bugs found during smoke and fixed in place

- **Edge geometry methods on a `<g>` wrapper.** `loop.js` originally put each edge's id on the
  wrapping `<g class="fedge-g">`, not the `<path class="fedge">`. `DeckFlow.play` calls
  `getTotalLength()`/`getPointAtLength()` on that element — methods only `<path>` (an
  `SVGGeometryElement`) exposes — so the L17 token silently never moved past the first edge.
  Fixed by moving the id + `new`/`sil`/`hl` classes directly onto the `<path>`; updated the
  corresponding `deck.css` selectors (`.fedge.hl`, `.fedge.sil`, `.fedge.new`) to match.
- **Node shapes invisible (solid black, no readable labels).** `deck.css`'s LoopCanvas rules used
  descendant combinators (`.fnode rect{...}`) assuming `.fnode` was a wrapper around the
  rect/polygon/circle, but `loop.js` puts the `fnode` class directly on the shape element itself.
  The selectors never matched, so every node fell back to the SVG default black fill with
  dark-navy text on top of it — unreadable. Fixed by changing every such rule to target `.fnode`
  directly (and `.fnode-g.<state> .fnode` for the wrapper-driven state classes `active`/`done`/
  `hl`/`sil`/`new`, which is a true descendant relationship).
- **Edges terminated at node centers.** With no clipping, straight/curved edges ran through node
  labels and arrowheads landed mid-text (e.g. "new card" rendered as "ew card"). Added
  `boundaryPoint()` to `loop.js` so every edge starts/ends at its node's boundary (circle radius,
  rect half-extents, or diamond edge) instead of its center.
- **L17 off-by-one lesson numbers.** `part5.js` displayed "Lesson 18"/"Lesson 19" for L17/L18.
  Fixed to "Lesson 17"/"Lesson 18".
- **L17 slide had no fitting container.** Its scoped `<style>` targeted `#p5l17`, but the
  `<section>` never carried that id, so the flex/sizing rules never applied and the stage-8
  diagram overflowed the viewport (G3 and the bottom row were scrolled out of view). Fixed by
  adding `id="p5l17"` to the section (scoped to `#p5l17.active` so it doesn't fight
  `.slide{display:none}` when inactive) and capping the canvas at `56vh` so the full circuit is
  visible without scrolling.

All five fixes were verified by re-running the affected checks above after each change; the deck
is in a fully passing state as of this run.

## Smoke test — auto-reveal / flow-dot redesign pass (run 2026-09-04, port 8090, headless browser tool)

What changed: removed every click-to-reveal interaction (`.step`/`.step.hid` engine, `stepNew`,
`REVEAL_GROUPS`) and replaced it with auto-reveal (`.reveal`/`.reveal.in`, `deck.js`'s
`playReveal`, staggered 150ms/element capped at 1400ms, fired from a new `deck:activate` custom
event on every `activate(i)`); added continuously-looping flow dots (`<circle class="flowdot">` +
`<animateMotion>`/`<mpath>`) to every rendered edge except `token`/`silhouette` mounts; fixed 4
`loop.js` `EDGES` entries with previously-missing `curve` control points (`review-engineer`
`[810,210]`, `queue-agent2` `[750,280]`, `queue-agent3` `[50,280]`, `verify3-review` `[860,460]`)
that were clipping through node boxes; `→`/`Space`/`←` are now pure one-slide-per-press
navigation; L17 now autoplays on first (and every) activation via a `deck:activate` listener in
`part5.js`, with Play/Pause/Reset/speed kept as manual replay controls.

1. **Static** — `node --check` passes on `deck.js`, `loop.js`, and all 6 `slides/partN.js`.
2. **Overlap scan** — a Python re-implementation of `boundaryPoint()`/`edgeD()` sampling each
   edge at 60 points + its label midpoint against every other coexisting node's bounding shape
   (≥6px stroke pad, ≥12px label pad) reproduced exactly the 4 known offenders
   (`queue-agent2`/`queue-agent3`/`review-engineer`/`verify3-review`) against the *original*
   (unfixed) curve values, confirming the scan is sound; against the *final* `EDGES` table the
   same scan reports zero offenders.
3. **Class-token cleanup** — `grep -rn '\bstep\b\|\bhid\b' docs/presentation/slides/
   docs/presentation/deck.css docs/presentation/deck.js` returns zero matches (the DeckFlow
   internal stepping helper was renamed `advance` to avoid the collision).
4. **Serve** — `python3 -m http.server 8090` from `docs/presentation` via `hub start`; port
   readiness confirmed.
5. **Browser smoke** — zero console/page errors; for every slide index 0..22, navigating via hash
   and waiting 2.5s, `document.querySelectorAll('.slide.active .reveal:not(.in)').length === 0`
   on all 23 (every reveal element reached `.in` with no keypress); 22 real `ArrowRight`
   keypresses from slide 0 incremented the active index by exactly 1 each time, landing on index
   22 (one press now always equals one slide — the old dual-mode step/slide advance is gone);
   `End` from slide 5 lands on index 22 `.active`; `N` opens `#notes` with non-empty text; rail
   segment count is 23.
6. **One token per canvas, not one dot per edge** — every `mountFlow` canvas (`#7`..`#17`) renders
   exactly one `.token` circle and zero `.flowdot` elements; `document.querySelectorAll('.flowdot').length === 0`
   deck-wide (the old per-edge `animateMotion` flow dots stay removed as visually distracting).
   `#18` (L17, `token:true, drawIn:true`) also renders exactly one `#p5l17-token` circle.
7. **L17 autoplay** — a fresh load at hash `#18` reached `#p5l17-n-monitor.active` and
   `#p5l17-n-board.done` within 14s with no click; `#p5-reset` cleared both classes; `#p5-play`
   replayed the full circuit to completion again (manual replay still works after autoplay).
8. **Offline check** — `file:///Users/work/Documents/ai-pldc/docs/presentation/index.html` loads
   with zero console/page errors, 23 slides, and `.reveal` elements still animate in
   automatically on hash navigation.
9. `hub stop` issued against the `deck-server` process after verification completed.

All checks passed on the first full run; no additional bugs found during this pass.
