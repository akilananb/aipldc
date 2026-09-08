/* Part 3 — Course 7: Under the hood (the real tools behind the pipeline, named explicitly,
 * equal weight, not small print). Course 8: The loop contract (relocated here from Act 1 so
 * it sits directly before Stage 3). Registers window.PARTS[3] (template-literal HTML) and
 * window.PARTS_INIT[3] (canvas wiring, Course 7 only). Every id is prefixed p3-. LoopCanvas is
 * Director-owned — mount only, never edit loop.js. */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[3] = `
<style>
  /* Course 7 packs five cards + intro + punch; tighten this slide's stack/card padding only. */
  .p3-tools.stack { gap: 6px; }
  .p3-tools .rule-card { padding: 8px 12px; }
</style>
<section class="slide" data-lesson="Under the hood" data-min="2" data-notes="Two words, precisely, before the names. An agent reasons over context to do a step: one-shot, a single typed call, like grill or PO or plan or review; or session-based, a multi-turn reason-act-observe loop, like a build task. A harness is the product that runs a session-based agent's loop and speaks a protocol to it. omp is our coding harness. ACP is the protocol that lets a human or the workflow engine drive that same session. Embabel is not a harness; it makes every one-shot agent hand-off a typed record. Temporal is the durable outer loop that holds the whole order while it waits for people. None of this is the clever part.">
  <div class="lesson-tag">Course 7</div>
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
  <div class="lesson-tag">Course 8</div>
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

`;

window.PARTS_INIT[3] = function () {
  var l7 = document.getElementById('p3l7-canvas');
  if (l7) {
    LoopCanvas.mountFlow(l7, { stage: 2, ns: 'p3l7' });
    var agent = document.getElementById('p3l7-n-agent');
    if (agent) agent.classList.add('hl');
  }
};
