/* part6.js — Writer-Close: L19 (What a live run taught us), L20 (Eight design rules),
 * L21 (Final takeaway). Three slides, part prefix p6-. No LoopCanvas mounts, no wiring. */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[6] = `
<section class="slide" data-lesson="What a live run taught us" data-min="3" data-notes="Four real failures from one live run — all fixed in the repo, all now invisible because the factory absorbs them. First: a model sometimes forgot the Area: marker, and once sent a build to the wrong file; we stopped asking the model to remember and injected it deterministically. Second: at-least-once delivery duplicated board cards until createItem deduped on an idempotency key. Third: an id sequence that was not persisted across Postgres restarts silently rebound new stories to old rows. Fourth: task cards stayed stuck in new until verifier outcomes were synced back to the board. The through-line is one word — idempotency; retries will happen, so every write must be safe to repeat.">
  <span class="lesson-tag">Lesson 19</span>
  <h2>What a live run taught us</h2>
  <p>The failures are not interesting because they happened. They are interesting because each one is now <em>structural</em> — engineered out of the path, not left to luck.</p>

  <div class="cols-2">
    <div class="reveal">
      <div class="war-card">
        <b>LLM output varies</b>
        <p>A missing <code>Area:</code> marker sent a build to the wrong file. Fixed by deterministic injection — never bet the build on the model remembering to emit it.</p>
      </div>
    </div>
    <div class="reveal">
      <div class="war-card">
        <b>At-least-once delivery</b>
        <p>Delivery duplicated every board card until <code>createItem</code> deduped on an idempotency key. The write became safe to repeat.</p>
      </div>
    </div>
    <div class="reveal">
      <div class="war-card">
        <b>An id sequence that forgot to persist</b>
        <p>Across Postgres restarts, new stories silently rebound to old row ids. State that outlives a process belongs in the database.</p>
      </div>
    </div>
    <div class="reveal">
      <div class="war-card">
        <b>Cards stuck in "new"</b>
        <p>Task cards sat in <code>new</code> forever until real verifier outcomes were synced back onto the board. The board reflects work, not wishes.</p>
      </div>
    </div>
  </div>

  <div class="punch">Agents plus code beat agents alone — and code plus retries demands idempotency.</div>
</section>

<section class="slide" data-lesson="Eight design rules" data-min="3" data-notes="Eight rules, distilled from building this machine. Start with one workflow, not a platform. Push deterministic work into code; save agents for the reasoning that genuinely needs them. Make context portable, isolate parallel work so no inner loop touches board state, and make state visible as a single canonical field. Build recovery in from day one — idempotent writes, durable state. And measure the factory, not the agent: throughput and quality, not one model's output.">
  <span class="lesson-tag">Lesson 20</span>
  <h2>Eight design rules</h2>
  <p>Read them as a checklist, not a manifesto. Each one removes a whole class of the failures from the last lesson.</p>

  <div class="cols-4">
    <div class="reveal">
      <div class="rule-card"><b>One workflow first</b><br>Pick one repeatable flow; automate it end to end before scaling out.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Deterministic work in code</b><br>Tests, guards, parsing — no model where a function is enough.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Agents for reasoning</b><br>The ambiguous parts, reached over the right wire.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Context portable</b><br>Specs, state, and evidence travel with the work.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Isolate parallel work</b><br>An inner loop never touches board state.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Make state visible</b><br>Every piece of work has one canonical state.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Build recovery in</b><br>Retries, idempotency, and durable state from day one.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Measure the factory, not the agent</b><br>Track the workflow's throughput, not one model's output.</div>
    </div>
  </div>

  <div class="punch">Don't build the factory before you know which workflow you're automating.</div>
</section>

<section class="slide" data-lesson="Final takeaway" data-min="2" data-notes="We started with two boxes — an engineer and an agent — and grew it into a machine with three gates, three parallel lanes, and a monitor that closes the wheel. But the question was never how do I build an agent loop. The practical question is: what repeatable developer workflow can I turn into a reliable system of code, agents, and human judgment? Start with the workflow you already do by hand, every week. That — not a loop — is the thing to automate.">
  <span class="lesson-tag">Lesson 21</span>
  <h2>Final takeaway</h2>
  <p>Two ways to read the same climb.</p>

  <div class="two">
    <div>
      <h3>The maturity curve</h3>
      <div class="ladder">
        <div class="reveal"><div class="rung">manual development</div></div>
        <div class="reveal"><div class="rung">agent-assisted development</div></div>
        <div class="reveal"><div class="rung">engineered developer workflows</div></div>
        <div class="reveal"><div class="rung">software factory</div></div>
      </div>
    </div>
    <div>
      <h3>The work, re-framed</h3>
      <div class="ladder">
        <div class="reveal"><div class="rung">write code</div></div>
        <div class="reveal"><div class="rung">direct agents</div></div>
        <div class="reveal"><div class="rung">design workflows</div></div>
        <div class="reveal"><div class="rung">design the factory</div></div>
        <div class="reveal"><div class="rung">improve the factory</div></div>
      </div>
    </div>
  </div>

  <div class="punch">Don't ask 'how do I build an agent loop?' Ask 'what repeatable developer workflow can I turn into a reliable system of code, agents, and human judgment?'</div>
</section>
`;

window.PARTS_INIT[6] = function () {
  /* No canvas mounts or buttons in part 6 — nothing to wire. */
};
