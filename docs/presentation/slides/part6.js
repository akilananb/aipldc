/* part6.js — Courses 11–12: what a live run taught us, and the closing takeaway.
 * Two slides, part prefix p6-. No LoopCanvas mounts, no wiring. */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[6] = `
<style>
  /* Course 11 packs 8 rule-cards + intro + sub-heading; tighten this slide's spacing only
     so the punchline stays on-screen without scrolling. */
  .p6-rules h3 { margin: 10px 0 6px; }
  .p6-rules .cols-2 { gap: 12px; }
  .p6-rules .cols-4 { gap: 10px; }
  .p6-rules .rule-card { padding: 10px 14px; font-size: 14px; }
</style>
<section class="slide" data-lesson="What running it for real taught us" data-min="3" data-notes="Four real failures from one live run, all fixed, all now structural. The agent sometimes forgot the Area: marker — the label the next station needed — so we stopped relying on memory and injected it deterministically. At-least-once delivery placed the same order twice, until createItem deduped on an idempotency key. An id sequence lived only in memory and forgot itself across a Postgres restart, silently rebinding new stories to old rows. And cards sat in state new until real verifier outcomes were synced back onto the board. That gives us four house rules: pick one dish you already make every week; use deterministic checks, not opinions; give every cook a chef; and build a kitchen that never drops an order.">
  <div class="lesson-tag">Course 11</div>
  <h2>What running it for real taught us</h2>
  <div class="p6-rules">
  <p>Four things went wrong in a real run. Each one is now built out of the path, not left to luck.</p>

  <div class="cols-2">
    <div class="reveal">
      <div class="rule-card"><b>The agent forgot the Area: marker</b><br>The label the next station needed. Fixed by deterministic injection, not memory — the equipment stamps it on every time.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>The same order got placed twice</b><br>At-least-once delivery duplicated it, until <code>createItem</code> deduped on an idempotency key. Every write now refuses to repeat itself.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>The ticket numbers reset overnight</b><br>An id sequence lived only in memory and forgot itself across a Postgres restart — silently rebinding new stories to old rows.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Tickets stuck on 'new'</b><br>Cards sat in state <code>new</code> until real verifier outcomes were synced back onto the board.</div>
    </div>
  </div>

  <h3>Four house rules</h3>
  <div class="cols-4">
    <div class="reveal">
      <div class="rule-card"><b>Pick one dish you already make every week</b><br>Automate that end to end first.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Thermometers, not opinions</b><br>Deterministic checks wherever a function is enough.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>Every cook has a chef</b><br>A named person owns every AI's output.</div>
    </div>
    <div class="reveal">
      <div class="rule-card"><b>A kitchen that never drops an order</b><br>Assume repeats and restarts; build recovery in from day one.</div>
    </div>
  </div>
  </div>

  <div class="punch">Don't build the kitchen before you know which dish you are cooking.</div>
</section>

<section class="slide" data-lesson="The one question to take home" data-min="2" data-notes="We started with an engineer and an agent and grew it into a pipeline with three gates, three parallel stations, and a monitor that closes the loop. But the question was never whether the AI can cook; it can. The practical question is which dish you already make every single week, by hand, that you could turn into an engineered workflow like this, with deterministic checks for what can be measured, AI for the fuzzy middle, and a named person on every gate. Priya's button went from Monday morning to the table the same day. Start with the workflow you already do by hand. That, not a clever agent, is the thing to build.">
  <div class="lesson-tag">Course 12</div>
  <h2>The one question to take home</h2>
  <div class="two">
    <div>
      <h3>How kitchens grow</h3>
      <div class="ladder">
        <div class="reveal rung"><b>Home cooking — manual development</b> — one person doing everything by hand.</div>
        <div class="reveal rung"><b>A cook with a helper — agent-assisted development</b> — the AI assists; you still run every step.</div>
        <div class="reveal rung"><b>A designed kitchen — engineered developer workflows</b> — stations, thermometers, a pass. What this deck built.</div>
        <div class="reveal rung"><b>A kitchen that improves itself — a software factory</b> — whose monitor closes the loop.</div>
      </div>
    </div>
    <div class="panel">
      <div class="reveal callout warn">Priya's spreadsheet button: placed Monday morning, on the table the same day, and the critic is still watching it.</div>
      <div class="reveal callout">The AI did not make the cook faster. It let us run a pipeline where no order is dropped and no dish leaves without a person tasting it.</div>
    </div>
  </div>
  <div class="punch">Don't ask 'can the AI cook?' Ask 'which workflow do we already run every week that we could turn into a reliable kitchen — with a person on the pass?'</div>
</section>
`;

window.PARTS_INIT[6] = function () {};
