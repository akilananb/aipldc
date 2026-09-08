/* part6.js — Courses 17–18: what a live run taught us, and the closing takeaway.
 * Two slides, part prefix p6-. No LoopCanvas mounts, no wiring. */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[6] = `
<style>
  /* Course 17 packs 8 rule-cards + intro + sub-heading; tighten this slide's spacing only
     so the punchline stays on-screen without scrolling. */
  .p6-lessons h3 { margin: 10px 0 6px; }
  .p6-lessons .cols-2 { gap: 12px; }
  .p6-lessons .cols-4 { gap: 10px; }
  .p6-lessons .rule-card { padding: 10px 14px; font-size: 14px; }
</style>
<section class="slide" data-lesson="What a live run taught us" data-min="2" data-notes="Four failures from one live run, one per layer or decision. The area marker was context the model had to remember, so it is now injected. The duplicate card was a retry without an idempotency key, so every create carries one. The reset ids were state that did not survive a restart, so it lives in Postgres. The stuck cards were a board that trusted the agent, so verifier outcomes are synced onto it. Then the contract answered for this pipeline: what stops it, what proves a step, what is safe to retry, what forces a human. None of those answers is a prompt.">
  <div class="lesson-tag">Course 17</div>
  <h2>What a live run taught us</h2>
  <div class="p6-lessons">
  <p>Four failures from one live run, one per layer or decision. Each is now enforced by the runtime.</p>

  <div class="cols-2">
    <div class="reveal">
      <div class="rule-card"><b><span class="pill">Context</span> &nbsp;The model forgot the Area: marker</b><br>The plan agent could not resolve the area. Fixed by injecting it deterministically from the board, never from memory.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b><span class="pill">Retry</span> &nbsp;The same card was created twice</b><br>At-least-once delivery. Every create now carries an idempotency key and refuses to repeat.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b><span class="pill loop">Continue</span> &nbsp;Ids reset after a restart</b><br>A sequence lived in memory and rebound new stories to old rows. Anything that must survive the night lives in Postgres.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b><span class="pill pass">Verify</span> &nbsp;Cards stuck on new</b><br>The board showed hope, not fact, until verifier outcomes were synced back onto it.</div>
    </div>
  </div>

  <h3>The loop contract, answered for this pipeline</h3>
  <div class="cols-4">
    <div class="reveal">
      <div class="rule-card"><b>Continue</b><br>A 10-minute wall clock per build task, enforced by the worker; auto-revise at most twice; activity retries capped at 2.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Verify</b><br>npm test, the scope guard, the quality score, the monitor threshold.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Retry</b><br>FAIL re-enters the same session; every card create carries an idempotency key.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Escalate</b><br>forbidden action, exhausted budget, G1, G2, G3, @mention approval.</div>
    </div>
  </div>
  </div>

  <div class="punch">If any answer is 'the prompt tells the model to behave', it is not engineered yet.</div>
</section>

<section class="slide" data-lesson="The evolution, and the question to take home" data-min="2" data-notes="We started with a prompt, made it context, put a harness around one run, then a loop with a contract around the harness, then a durable outer loop around the whole lifecycle. The model never got smarter. The loop got a contract. So the question to take home is not how to build an agent loop; it is which repeatable workflow you already run that you could give these four answers.">
  <style>.p6-close .ladder{gap:5px}.p6-close .ladder .rung{padding:8px 12px;font-size:13px}.p6-close .panel{padding:12px 16px}</style>
  <div class="lesson-tag">Course 18</div>
  <h2>The evolution, and the question to take home</h2>
  <div class="two p6-close">
    <div>
      <h3>How the order evolved</h3>
      <div class="ladder">
        <div class="reveal rung"><b>A prompt</b> — 'export my filtered orders'. Nothing checkable.</div>
        <div class="reveal rung"><b>Context</b> — proposal, spec delta, tasks. Every line is a test.</div>
        <div class="reveal rung"><b>A harness</b> — omp, its worktree, the repo's own tests.</div>
        <div class="reveal rung"><b>A loop</b> — intake, quality, G1, plan, lanes, guard, review, G2: Continue, Verify, Retry, Escalate at every stage.</div>
        <div class="reveal rung"><b>Loops inside a durable loop</b> — release, G3, deploy, monitor, back to the board.</div>
      </div>
    </div>
    <div class="panel">
      <div class="reveal callout warn">Priya's export: card filed in the morning, on the table the same day, and the monitor is still watching it.</div>
      <div class="reveal callout">The model did not get smarter. The loop around it got a contract.</div>
    </div>
  </div>
  <div class="punch">Don't ask 'how do I build an agent loop?' Ask 'which repeatable workflow can I give a loop contract: what stops it, what proves it, what retries, who signs?'</div>
</section>
`;

window.PARTS_INIT[6] = function () {};
