/* Part 4 — Courses 5–9 (stages 3, 4, 5, 7, 8 of the shared LoopCanvas).
 * Each lesson mounts the SAME growing canvas at exactly one new stage.
 * LoopCanvas.mountFlow() renders every included node/edge directly and lets `deck.js`'s
 * auto-reveal animate them in — do NOT wrap the canvas div in `.reveal`; just place the
 * mount div and write the surrounding lesson content (wrapped in its own `.reveal` for its
 * own stagger-in). */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[4] = `
<section class="slide" data-lesson="Take the order properly" data-min="4" data-notes="Here the engineer stops talking to an agent directly. Priya places a card on the board and the grill agent — an AI waiter — interrogates it across six real categories: scope, users and roles, acceptance, risk, dependency, NFR. Every question has to cite the evidence that prompted it, and it may never ask something the description already answers. Priya answers or parks each one, and parked questions become explicit not-this-time notes. Only then does the PO agent write the ticket twice: a spec delta for the kitchen and a plain-language story for Priya, word for word the same. Nothing hits the stove until every question is answered or parked.">
  <div class="lesson-tag">Course 5</div>
  <h2>Take the order properly</h2>
  <div class="two">
    <div id="p4l11-canvas" class="loopcanvas"></div>
    <div class="stack">
      <div class="reveal callout">Priya no longer talks to a cook. She places a card on the board.</div>
      <div class="reveal callout">The grill agent — an AI waiter — interrogates it across six real categories: scope, users &amp; roles, acceptance, risk, dependency, NFR.</div>
      <div class="reveal callout">Every question cites the evidence that prompted it. It never asks what the description already says.</div>
      <div class="reveal callout">Unanswered? Priya parks it — an explicit not-this-time note, not a guess.</div>
      <div class="reveal callout warn">The PO agent then writes the ticket twice: a spec delta for the kitchen, a plain-language story for Priya — word for word the same.</div>
    </div>
  </div>
  <div class="punch">Nothing hits the stove until every question is answered or parked.</div>
</section>

<section class="slide" data-lesson="The pass: a human tastes first" data-min="4" data-notes="This is the first gate, and it is the most important idea in the deck. The PO agent writes the story and then it stops; no agent crosses gate G1 on its own. Two named roles taste it independently: the PO for intent and acceptance, the Squad Lead for feasibility and risk. Either can send it back with a comment and the PO agent rewrites with that comment as its new first line. A version bump clears every earlier signature, because you sign what is in front of you, not the idea. In the real run story 9004's quality check scored 58 and failed, was rewritten once, and passed at 88. The AI makes; named people check.">
  <div class="lesson-tag">Course 6</div>
  <h2>The pass: a human tastes first</h2>
  <div class="two">
    <div id="p4l12-canvas" class="loopcanvas"></div>
    <div class="stack">
      <div class="reveal callout">The story is written — and the AI stops. No agent crosses gate G1 on its own.</div>
      <div class="reveal callout">Two named roles taste it independently: the PO (intent, acceptance) and the Squad Lead (feasibility, risk).</div>
      <div class="reveal callout">Either can send it back with a comment. The PO agent rewrites with that comment as its new first line.</div>
      <div class="reveal callout">A version bump clears every earlier signature — you sign what's in front of you, not the idea.</div>
      <div class="reveal callout warn">Real run: story 9004's quality check scored 58 and failed. One auto-revise later, version two scored 88 and passed.</div>
    </div>
  </div>
  <div class="punch">The AI makes. Named people check — every version, every time.</div>
</section>

<section class="slide" data-lesson="Many stations at once" data-min="4" data-notes="Now one lane becomes three. The plan agent — a prep cook — splits the approved ticket into tasks: one code area, one scenario, one proof per task, in dependency order. Build workers claim tasks over REST, each in its own git worktree, so three stations work at once without tripping over each other. A scope guard reverts anything a task changes outside its own file list, and no task can weaken another task's test to make its own pass. The verifier is the repo's own npm test, TAP-parsed — not an opinion. In the real run all eight tasks went verifier-green, each after twenty-four to thirty-six attempts inside a ten-minute budget. A station never touches the board; it hands back a typed result and the outer loop decides.">
  <div class="lesson-tag">Course 7</div>
  <h2>Many stations at once</h2>
  <div class="two">
    <div id="p4l13-canvas" class="loopcanvas"></div>
    <div class="stack">
      <div class="reveal callout">The plan agent splits the approved ticket into tasks — one code area, one scenario, one proof per task.</div>
      <div class="reveal callout">Build workers claim tasks over REST, each in its own git worktree — three stations working at once, never touching each other.</div>
      <div class="reveal callout">A scope guard reverts anything a task changes outside its own file list — and no task can weaken another task's test to pass its own.</div>
      <div class="reveal callout">The verifier is the repo's own npm test, TAP-parsed — not an opinion.</div>
      <div class="reveal callout warn">Real run: 8 / 8 tasks verifier-green, 24–36 attempts each, a 10-minute budget per task.</div>
    </div>
  </div>
  <div class="punch">A station never touches the order board. It hands back a finished dish and the kitchen decides what happens next.</div>
</section>

<section class="slide" data-lesson="Two more passes before it leaves" data-min="4" data-notes="Two more gates, quickly. First the review agent — an AI sous chef — is first reviewer on every PR, tagging findings blocker, should, or nit; only a blocker sends the task back to the build loop. Then gate G2, FSDev plus QA, judges what's left. Any reviewer can summon a specialist with an at-mention in a comment — the fixed pilot set is analyst, architect, qa, dev. And before the change ships there is the release pack: four documents, four named checkers — change-notes owned by the PO, rollout-plan by the Squad Lead, monitor-rules and test-evidence by QA. Gate G3 opens only when all four are signed. The AI drafts every document. It never signs one.">
  <div class="lesson-tag">Course 8</div>
  <h2>Two more passes before it leaves</h2>
  <div class="two">
    <div id="p4l15-canvas" class="loopcanvas"></div>
    <div class="stack">
      <div class="reveal callout">The review agent — an AI sous chef — is first reviewer on every PR, tagging findings blocker / should / nit. Only a blocker sends it back.</div>
      <div class="reveal callout">Gate G2 — FSDev + QA — judges what's left.</div>
      <div class="reveal callout">Any reviewer can summon a specialist with an @mention — the fixed set is analyst, architect, qa, dev.</div>
      <div class="reveal callout">The release pack is four documents, four named checkers: change-notes (PO), rollout-plan (Squad Lead), monitor-rules (QA), test-evidence (QA).</div>
      <div class="reveal callout warn">Gate G3 opens only when all four are signed. The AI drafts every one. It never signs.</div>
    </div>
  </div>
  <div class="punch">Every AI in this kitchen has a person whose name is on its work.</div>
</section>

<section class="slide" data-lesson="The critic keeps eating" data-min="3" data-notes="Deploy is not done. The monitor agent — the critic — keeps watching for the release's whole window, comparing the last window against a seven-day baseline. When a rule trips it gathers the numbers and the evidence and files a bug card in state new — a complaint on the board. That complaint is a new order; the grill agent, the waiter, picks it up. In the real run the rule was http_5xx_rate greater than 2% over 15 minutes; it observed 3.1% and filed 'Monitor trip: export-error-rate'. That closes the wheel: table feeds critic, critic feeds board, board feeds waiter.">
  <div class="lesson-tag">Course 9</div>
  <h2>The critic keeps eating</h2>
  <div class="two">
    <div id="p4l16-canvas" class="loopcanvas"></div>
    <div class="stack">
      <div class="reveal callout">Deploy is not done. The monitor agent — the critic — keeps watching for the release's whole window.</div>
      <div class="reveal callout">It compares the last window against a seven-day baseline.</div>
      <div class="reveal callout">A tripped rule gathers the evidence and files a bug card in state new — a complaint on the board.</div>
      <div class="reveal callout">That complaint is a new order. The grill agent — the waiter — picks it up. The wheel turns again.</div>
      <div class="reveal callout warn">Real trip: rule http_5xx_rate &gt; 2% over 15 minutes. Observed 3.1%. Filed: 'Monitor trip: export-error-rate'.</div>
    </div>
  </div>
  <div class="punch">Serving is not finishing. The critic files the complaint, and the kitchen starts again.</div>
</section>
`;

window.PARTS_INIT[4] = function () {
  var l11 = document.getElementById('p4l11-canvas');
  if (l11) LoopCanvas.mountFlow(l11, { stage: 3, ns: 'p4l11' });
  var l12 = document.getElementById('p4l12-canvas');
  if (l12) LoopCanvas.mountFlow(l12, { stage: 4, ns: 'p4l12' });
  var l13 = document.getElementById('p4l13-canvas');
  if (l13) LoopCanvas.mountFlow(l13, { stage: 5, ns: 'p4l13' });
  var l15 = document.getElementById('p4l15-canvas');
  if (l15) LoopCanvas.mountFlow(l15, { stage: 7, ns: 'p4l15', revealFrom: 6 });
  var l16 = document.getElementById('p4l16-canvas');
  if (l16) LoopCanvas.mountFlow(l16, { stage: 8, ns: 'p4l16' });
};
