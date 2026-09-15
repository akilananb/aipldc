window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[1] = `
<section class="slide" data-lesson="Three kinds of workers" data-min="2" data-notes="Now the three kinds of workers behind everything you'll see today. Code is deterministic: the tests, the scope guard, the monitor threshold, the parser that reads the spec. Agents take the fuzzy middle: reading a request, drafting a story, planning tasks, building, reviewing. People own judgment: three gates, each with named roles. The gray picture is the finished pipeline. Do not read it yet.">
  <div class="lesson-tag">Course 2</div>
  <h2>Three kinds of workers</h2>
  <div class="two">
    <table class="tbl">
      <tr><th>Who</th><th>Good at</th><th>In this pipeline</th></tr>
      <tr><td>Code</td><td>The same result every time</td><td>Tests, the scope guard, the threshold monitor, the schema parser</td></tr>
      <tr><td>Agents</td><td>The fuzzy middle: reading, drafting, planning, fixing</td><td>grill, PO, plan, build, review, release — every output verified by code</td></tr>
      <tr><td>People</td><td>Judgment with a name on it</td><td>The three gates: G1, G2, G3, each with named roles</td></tr>
    </table>
    <div>
      <div id="p1l1-canvas" class="loopcanvas"></div>
      <p class="small">the finished pipeline — built one stage at a time from Course 5</p>
    </div>
  </div>
  <div class="punch">Use code where determinism is enough. Use agents where reasoning is required. Keep people where judgment matters.</div>
</section>

<style>
  /* Course 3 packs five cards + intro + punch; tighten this slide's stack/card padding only. */
  .p3-tools.stack { gap: 6px; }
  .p3-tools .rule-card { padding: 8px 12px; }
</style>
<section class="slide" data-lesson="Under the hood" data-min="2" data-notes="Two words, precisely, before the names. An agent reasons over context to do a step: one-shot, a single typed call, like grill or PO or plan or review; or session-based, a multi-turn reason-act-observe loop, like a build task. A harness is the product that runs a session-based agent's loop and speaks a protocol to it. omp is our coding harness. ACP is the protocol that lets a human or the workflow engine drive that same session. Embabel is not a harness; it makes every one-shot agent hand-off a typed record. Temporal is the durable outer loop that holds the whole order while it waits for people. None of this is the clever part.">
  <div class="lesson-tag">Course 3</div>
  <h2>Under the hood</h2>

  <div class="two">
    <div id="p3l7-canvas" class="loopcanvas small"></div>
    <div class="stack p3-tools">
      <div class="reveal"><div class="rule-card"><b>Agent vs harness</b><br>An agent reasons over context to do a step — one-shot (grill, PO, plan, review) or session-based (a loop). A harness runs a session-based agent's loop and speaks a protocol to it.</div></div>
      <div class="reveal"><div class="rule-card"><b>omp — the coding harness</b><br>Precise edits, a session you keep driving, its own git worktree.</div></div>
      <div class="reveal"><div class="rule-card"><b>ACP — the protocol</b><br>Lets a person at a terminal or the workflow engine drive the same agent session.</div></div>
      <div class="reveal"><div class="rule-card"><b>Embabel — typed hand-offs</b><br>Every agent output is a typed record (a filled-in form), never free text.</div></div>
      <div class="reveal"><div class="rule-card"><b>Temporal — the durable outer loop</b><br>Holds every order from card to done, waits days for a human signature, survives a restart.</div></div>
    </div>
  </div>

  <div class="punch">Boring, reliable equipment. The agent is the cook, not the kitchen.</div>
</section>

<section class="slide" data-lesson="The loop contract" data-min="3" data-notes="Orchestration is the visible half of a loop: automations, worktrees, sub-agents, connectors, memory. It says how work moves. The decisive half is the control structure: four decisions the loop makes on every pass. Continue: what stops it. A step cap, a time cap, a token ceiling and no-progress detection, enforced by the runtime, because a prompt is advice and a runtime limit is control. Verify: what proves the last step worked. Something outside the model: tests, types, build, a diff that stays in scope. A model grading its own work is not a verifier. Retry: what is safe to try again. Re-enter with the failure attached, and never repeat a call with side effects without an idempotency key. Escalate: what forces a human. Repeated failure, a destructive or out-of-scope action, an output nobody can check. A stop button in the prompt is not a stop button. If any answer is the prompt tells the model to behave, that part is not engineered yet. From Course 9 on, every stage carries the tags for the decisions it enforces.">
  <style>.p3-contract .cols-2{gap:12px}.p3-contract .rule-card{padding:10px 14px;font-size:14px}.p3-contract .callout{margin:12px 0 0}</style>
  <div class="p3-contract">
  <div class="lesson-tag">Course 4</div>
  <h2>The loop contract</h2>
  <p>Four decisions on every pass — enforced by the runtime, never requested in the prompt.</p>
  <div class="cols-2">
    <div class="reveal"><div class="rule-card"><b><span class="pill loop">Continue</span> &nbsp;What stops it?</b><br>A step cap, a time cap, a token ceiling, no-progress detection. A prompt is advice; a runtime limit is control.</div></div>
    <div class="reveal"><div class="rule-card"><b><span class="pill pass">Verify</span> &nbsp;What proves the step worked?</b><br>Something outside the model: tests, types, build, a diff that stays in scope. A model grading itself is not a verifier.</div></div>
    <div class="reveal"><div class="rule-card"><b><span class="pill">Retry</span> &nbsp;What is safe to try again?</b><br>Re-enter with the failure attached. Never repeat a call with side effects without an idempotency key.</div></div>
    <div class="reveal"><div class="rule-card"><b><span class="pill stop">Escalate</span> &nbsp;What forces a human?</b><br>Repeated failure, a destructive or out-of-scope action, an output nobody can check. A stop in the prompt is not a stop.</div></div>
  </div>
  <div class="reveal callout warn">If any answer is 'the prompt tells the model to behave', that part is not engineered yet. From Course 9 on, every stage is tagged with the decisions it enforces.</div>
  <div class="punch">A capable model in a weak loop is more dangerous than a limited model in a strong loop.</div>
  </div>
</section>

<section class="slide" data-lesson="The simplest loop" data-min="4" data-notes="This is the loop everyone talks about, and it works because of the one deterministic piece: the tests. The engineer prompts the agent. The agent edits. The tests run. Green goes to review; red goes back into the same session with the failure attached, and the agent retries against something true. Like a thermometer in the kitchen: no opinion, same reading every time. This makes concrete two of the four loop-contract decisions Course 4 just named formally: verify by code, retry into the same session.">
  <div class="lesson-tag">Course 5</div>
  <h2>The simplest loop</h2>
  <div class="two">
    <div id="p2l6-canvas" class="loopcanvas"></div>
    <div class="stack">
      <div class="row p-tags"><span class="pill pass">Verify</span><span class="pill">Retry</span></div>
      <div class="reveal callout">An engineer prompts a coding agent — stage 1.</div>
      <div class="reveal callout">Stage 2 adds one piece of code: the repo's own npm test runs before anything reaches review.</div>
      <div class="reveal callout">PASS goes forward to a PR. FAIL loops straight back into the same agent session with the failing test as the next prompt.</div>
      <div class="reveal callout warn">Verify is the repo's own test, not the agent's opinion. Retry is the same session re-entered with the failure attached — two of the four decisions Course 4's loop contract just named, made concrete here.</div>
    </div>
  </div>
  <div class="punch">Agents plus code beat agents alone.</div>
</section>
`;

window.PARTS_INIT[1] = function () {
  var el = document.getElementById('p1l1-canvas');
  if (el) { LoopCanvas.mount(el, { stage: 8, ns: 'p1l1', silhouette: true }); }

  var l7 = document.getElementById('p3l7-canvas');
  if (l7) {
    LoopCanvas.mountFlow(l7, { stage: 2, ns: 'p3l7' });
    var agent = document.getElementById('p3l7-n-agent');
    if (agent) agent.classList.add('hl');
  }

  var l6 = document.getElementById('p2l6-canvas');
  if (l6) { LoopCanvas.mountFlow(l6, { stage: 2, ns: 'p2l6', revealFrom: 1 }); }
};
