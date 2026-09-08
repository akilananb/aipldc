/* Part 4 — Courses 9–14 (stages 3, 4, 5, 6, 7, 8 of the shared LoopCanvas).
 * Each lesson mounts the SAME growing canvas at exactly one new stage.
 * LoopCanvas.mountFlow() renders every included node/edge directly and lets `deck.js`'s
 * auto-reveal animate them in — do NOT wrap the canvas div in `.reveal`; just place the
 * mount div and write the surrounding lesson content (wrapped in its own `.reveal` for its
 * own stagger-in). */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[4] = `
<style>.p4-dense .callout{padding:8px 15px;font-size:14.5px;line-height:1.32}.p-tags{gap:6px;margin-bottom:2px}</style>
<section class="slide" data-lesson="Stage 3 — intake turns the request into a spec" data-min="4" data-notes="Here the pipeline takes control of the request. The engineer files a card and steps back. The grill agent asks six kinds of question and has to cite why it is asking. Priya answers or parks; parked becomes out of scope in writing. Then the PO agent writes the story and the spec delta from the same acceptance criteria, and if something is still missing it asks up to three follow-ups rather than inventing it. Like a waiter reading the customizations back before the ticket prints.">
  <div class="lesson-tag">Course 9</div>
  <h2>Stage 3 — intake turns the request into a spec</h2>
  <div class="two">
    <div id="p4l11-canvas" class="loopcanvas"></div>
    <div class="stack p4-dense">
      <div class="row p-tags"><span class="pill">Context</span><span class="pill stop">Escalate</span></div>
      <div class="reveal callout">The engineer no longer prompts the agent. A card is filed on the board — the prompt edge retires.</div>
      <div class="reveal callout">The grill agent interrogates the card across six fixed categories: scope, users, acceptance, risk, dependency, NFR. Every question cites its evidence or is marked assumption-check; it never asks what the card already says.</div>
      <div class="reveal callout">Parked questions become explicit out-of-scope lines, not guesses.</div>
      <div class="reveal callout">The PO agent writes the story twice: the human story and the spec delta, same acceptance criteria word for word; if a requirement is still missing it comes back with at most 3 follow-up questions instead of guessing.</div>
      <div class="reveal callout warn">Context, not prompt: the agent downstream never sees Priya's sentence, only the scenarios.</div>
    </div>
  </div>
  <div class="punch">Nothing becomes a story until every question is answered or parked.</div>
</section>

<section class="slide" data-lesson="Stage 4 — quality check and gate G1" data-min="3" data-notes="The first gate has two locks. One is code: a quality score that must pass, with up to two automatic revisions. The other is people: two named roles approving the same version independently, with blocking comments that freeze the gate and versions that clear old signatures. In the live run the first draft scored 58 and failed; one revision later it scored 88. Like the head chef reading the ticket before it goes to the line.">
  <div class="lesson-tag">Course 10</div>
  <h2>Stage 4 — quality check and gate G1</h2>
  <div class="two">
    <div id="p4l12-canvas" class="loopcanvas"></div>
    <div class="stack p4-dense">
      <div class="row p-tags"><span class="pill pass">Verify</span><span class="pill loop">Continue</span><span class="pill stop">Escalate</span></div>
      <div class="reveal callout">Verify: the quality agent scores the story 0–100 for INVEST, clarity, testability, completeness. Continue: FAIL triggers an auto-revise, at most twice — a runtime cap, not a prompt.</div>
      <div class="reveal callout">Escalate: the agent stops and sets awaiting-G1. No agent crosses a gate.</div>
      <div class="reveal callout">Two named roles approve independently: PO (intent, acceptance) and Squad Lead (scope, feasibility, risk).</div>
      <div class="reveal callout">A blocking comment freezes the gate for everyone; a new version clears every earlier signature.</div>
      <div class="reveal callout ok">Live run — story 9004: v1 scored 58 and failed, auto-revised, v2 scored 88 and passed.</div>
    </div>
  </div>
  <div class="punch">Maker-checker: the agent makes; named humans check — per version, with blocking comments.</div>
</section>

<section class="slide" data-lesson="Stage 5 — plan, build lanes, verify, guard" data-min="5" data-notes="Now the spec drives the build. One task per scenario, waves with no file conflicts, each task carrying its scenario, its touch list and its test from config.yaml. Workers claim tasks and build in isolated worktrees. The guard is code: edit outside your touch list and it is reverted and escalated. The verifier is code: the repo's own tests. In the live run all eight tasks went green. Like three stations each with their own thermometer, and nobody reaching into another station's pan.">
  <div class="lesson-tag">Course 11</div>
  <h2>Stage 5 — plan, build lanes, verify, guard</h2>
  <div class="two">
    <div>
      <div id="p4l13-canvas" class="loopcanvas"></div>
      <div class="row p-tags"><span class="pill loop">Continue</span><span class="pill pass">Verify</span><span class="pill">Retry</span><span class="pill stop">Escalate</span></div>
    </div>
    <div class="stack p4-dense">
      <div class="reveal callout">The plan agent emits one task per scenario, in waves where no two tasks in a wave touch the same file — proves / touches / test on every task.</div>
      <div class="reveal callout">Build workers claim tasks over REST, each in its own git worktree — three lanes at once, never sharing a working copy.</div>
      <div class="reveal callout">Escalate: the prompt says you may ONLY edit these files, and the scope guard reverts anything outside touches and raises 'forbidden action'. Retry: FAIL re-enters the same session.</div>
      <div class="reveal callout">The verifier is the repo's own npm test — a task is green only when its scenario's test passes and scope is clean.</div>
      <div class="reveal callout ok">Continue: a 10-minute wall clock per task, enforced by the worker — not an iteration count. Live run: 8 / 8 tasks green, 24–36 tool calls each.</div>
    </div>
  </div>
  <div class="punch">An inner loop never touches board state. It returns a typed result and the outer loop decides.</div>
</section>

<section class="slide" data-lesson="Stage 6 — review agent and gate G2" data-min="3" data-notes="Review is spec-controlled too. The review agent maps every task back to its scenario and its test, so the human gate reads traceability rows instead of raw diffs. Scope and verifier failures are blockers by construction. Then two named roles decide, and any of them can call a specialist agent into the thread, whose answer stays a draft until a person approves it. Like the expeditor checking the plate against the ticket before it leaves the pass.">
  <div class="lesson-tag">Course 12</div>
  <h2>Stage 6 — review agent and gate G2</h2>
  <div class="two">
    <div id="p4l14-canvas" class="loopcanvas"></div>
    <div class="stack p4-dense">
      <div class="row p-tags"><span class="pill pass">Verify</span><span class="pill stop">Escalate</span></div>
      <div class="reveal callout">The review agent is first reviewer on every PR. It builds a traceability row per task: scenario → test → files.</div>
      <div class="reveal callout">Verify: findings are tagged blocker / should / nit; scope violations and non-green verifiers are automatic blockers. Escalate: only a blocker sends the task back, and every @mention draft waits for a human.</div>
      <div class="reveal callout">Gate G2 — FSDev + QA — judges the PR against the traceability rows.</div>
      <div class="reveal callout">Any reviewer can summon a specialist in a comment: @analyst, @architect, @qa, @dev. The draft is pending until a human approves it.</div>
    </div>
  </div>
  <div class="punch">Every agent has a human who owns its output — and any reviewer can summon an agent with an @.</div>
</section>

<section class="slide" data-lesson="Stage 7 — release pack, gate G3, deploy" data-min="3" data-notes="Release is the third gate and the first time the spec reaches operations. Four documents, four named checkers, one version number that resets every signature when anything changes. The monitor rule for this order is written here, before deploy, as a threshold code can evaluate. Like packing the delivery bag: receipt, allergen card, delivery slip, QC stamp, each signed by a different person.">
  <div class="lesson-tag">Course 13</div>
  <h2>Stage 7 — release pack, gate G3, deploy</h2>
  <div class="two">
    <div id="p4l15-canvas" class="loopcanvas"></div>
    <div class="stack p4-dense">
      <div class="row p-tags"><span class="pill pass">Verify</span><span class="pill stop">Escalate</span></div>
      <div class="reveal callout">The release agent drafts four documents from sources of truth: change-notes (PO), rollout-plan (Squad Lead), monitor-rules (QA), test-evidence (QA).</div>
      <div class="reveal callout">Gate G3 opens only when all four are signed by their named checker. A changes-requested bumps the pack version and clears every signature.</div>
      <div class="reveal callout">The monitor rules are written down before deploy: export-error-rate — http_5xx_rate &gt; 2% over 15m → file-card.</div>
      <div class="reveal callout warn">Verify: four documents, four named signatures. Escalate: G3 opens only when all four are signed; the agent drafts, it never signs.</div>
    </div>
  </div>
  <div class="punch">Agents draft every document. Named humans sign each one.</div>
</section>

<section class="slide" data-lesson="Stage 8 — monitor closes the wheel" data-min="2" data-notes="The last stage is deterministic. The monitor reads the rule the release pack wrote, evaluates it on its window, and on a trip files a card with the numbers attached. That card is a new order, and the grill agent picks it up. In the live run the error rate averaged three point one percent against a two percent threshold and a card was filed. Like the customer's feedback going straight back onto the order board.">
  <div class="lesson-tag">Course 14</div>
  <h2>Stage 8 — monitor closes the wheel</h2>
  <div class="two">
    <div id="p4l16-canvas" class="loopcanvas"></div>
    <div class="stack p4-dense">
      <div class="row p-tags"><span class="pill pass">Verify</span><span class="pill loop">Continue</span></div>
      <div class="reveal callout">Deploy is not done. The monitor agent is pure code — no model — evaluating each rule on its window against a baseline.</div>
      <div class="reveal callout">On a trip it gathers evidence: signal, window, samples, observed value, threshold.</div>
      <div class="reveal callout">Continue at lifecycle scale: the trip files a bug card in state new, the grill agent picks it up, and the outer loop takes another pass — with an idempotency key on the card, so a repeated trip never files twice.</div>
      <div class="reveal callout warn">Live trip: http_5xx_rate averaged 3.1% over 15m against a 2% threshold. Filed: 'Monitor trip: export-error-rate'.</div>
    </div>
  </div>
  <div class="punch">Deploy is not done. The monitor files evidence back to the board, and the wheel closes.</div>
</section>
`;

window.PARTS_INIT[4] = function () {
  var l11 = document.getElementById('p4l11-canvas');
  if (l11) LoopCanvas.mountFlow(l11, { stage: 3, ns: 'p4l11' });
  var l12 = document.getElementById('p4l12-canvas');
  if (l12) LoopCanvas.mountFlow(l12, { stage: 4, ns: 'p4l12' });
  var l13 = document.getElementById('p4l13-canvas');
  if (l13) LoopCanvas.mountFlow(l13, { stage: 5, ns: 'p4l13' });
  var l14 = document.getElementById('p4l14-canvas');
  if (l14) LoopCanvas.mountFlow(l14, { stage: 6, ns: 'p4l14' });
  var l15 = document.getElementById('p4l15-canvas');
  if (l15) LoopCanvas.mountFlow(l15, { stage: 7, ns: 'p4l15' });
  var l16 = document.getElementById('p4l16-canvas');
  if (l16) LoopCanvas.mountFlow(l16, { stage: 8, ns: 'p4l16' });
};
