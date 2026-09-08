window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[1] = `
<style>
  /* Part 1 bespoke one-offs — every selector scoped under the p1- prefix. */
  #p1-spin-group {
    transform-origin: 100px 100px;
    animation: p1-spin 8s linear infinite;
  }
  @keyframes p1-spin {
    to { transform: rotate(360deg); }
  }
  .p1-legend { display:flex; flex-wrap:wrap; gap:8px; margin-top:20px; }
  .p1-legend .card-chip { min-width:0; }
  .p1-legend .card-chip b { color:var(--ink); }
</style>

<section class="slide" data-lesson="Title" data-min="0" data-notes="Sixty minutes and one lens. Working with agents has moved outward four times: from the words you type, to the context the model sees, to the harness one run lives in, to the loop that repeats, checks, retries and stops that run without you on every turn. We start with one customer placing one customized order and never leave it. First the order becomes context: a spec. Then the smallest loop builds from it. Then every stage of a real delivery lifecycle is added, and at each one we name which of the four loop decisions it enforces and what the human signs. By the end the whole SDLC is one engineered loop on screen.">
  <div class="title-grid">
    <div>
      <h1>AI-Native Engineering</h1>
      <p class="small">One customized order — from prompt, to context, to harness, to loop — through a whole delivery lifecycle run by agents and checked by people.</p>
    </div>
    <svg class="loop-hero" viewBox="0 0 200 200" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="A loop drawing itself on load">
      <defs>
        <linearGradient id="sc-loop-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="var(--loop)"/>
          <stop offset="60%" stop-color="#00A3E0"/>
          <stop offset="100%" stop-color="var(--pass)"/>
        </linearGradient>
        <filter id="sc-hero-glow" x="-20%" y="-20%" width="140%" height="140%">
          <feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(4, 115, 234, 0.4)"/>
        </filter>
      </defs>
      <circle cx="100" cy="100" r="90" fill="none" stroke="rgba(4, 115, 234, 0.1)" stroke-width="10"/>
      <g id="p1-spin-group">
        <circle id="p1-title-arc" cx="100" cy="100" r="90" fill="none" stroke="url(#sc-loop-grad)" stroke-width="10" stroke-linecap="round" filter="url(#sc-hero-glow)"/>
        <polygon points="114,10 96,1 96,19" fill="var(--pass)"/>
      </g>
    </svg>
  </div>
</section>

<section class="slide" data-lesson="A customized order" data-min="3" data-notes="A customized order is a perfect small example of a feature request: five constraints, all reasonable, none written down in a form anyone can check. Our real request for the next hour is Priya's: export the filtered orders view to CSV. It comes from a live run of this pipeline against a real repository. Everyone in this room has an AI that writes code. The order is still the bottleneck, and that is what we fix first.">
  <style>.p1-hook .panel{padding:12px 16px}.p1-hook h3{margin:8px 0 6px}.p1-hook .stack{gap:8px}</style>
  <div class="p1-hook">
  <div class="lesson-tag">Hook</div>
  <h2>A customized order</h2>
  <div class="two">
    <div class="panel">
      <h3>What the customer says</h3>
      <div class="reveal callout warn">'Chicken burger, no onions, extra spicy, gluten-free bun, and it has to be at my door by seven.'</div>
    </div>
    <div class="panel">
      <h3>What actually reaches the kitchen</h3>
      <div class="stack">
        <div class="reveal callout">A shouted summary. Two of the five customizations survive.</div>
        <div class="reveal callout">The cook improvises the rest.</div>
        <div class="reveal callout">It arrives at 7:40 with onions.</div>
      </div>
    </div>
  </div>
  <div class="reveal callout">Priya, in sales operations, asks for a button that downloads exactly the orders she has filtered as a spreadsheet. Same problem: a wish with five customizations in it, and only the loud ones survive the hand-off.</div>
  <div class="punch">Everyone can cook now. The hard part is getting the order right.</div>
  </div>
</section>

<section class="slide" data-lesson="Menu" data-min="2" data-notes="Four acts. First the lens: the four-layer map from prompt to loop. Second context: spec-driven development, the real files, and the one place a team customizes the schema. Third harness: the smallest loop that works, the equipment under it, and the loop contract that names what every later stage enforces. Fourth the loop at lifecycle scale: every stage tagged with the decisions it enforces, one order all the way round, and the question to take home. Course 16 and Course 17 are the ones we drop if we are short.">
  <style>#p1-agenda{grid-template-columns:repeat(5,1fr);gap:5px}#p1-agenda .card{padding:5px 7px}#p1-agenda .card b{font-size:12px;line-height:1.15}#p1-agenda .card .t{font-size:10px;line-height:1.15}</style>
  <div class="lesson-tag">Menu</div>
  <h2>Eighteen courses, one order, one growing picture</h2>
  <div id="p1-agenda" class="agenda"></div>
  <p style="margin-top:10px;">Total <span id="p1-agenda-total"></span>. Four acts: the lens (Course 1), context — the order as a spec (2–5), harness and the loop contract (6–8), the loop at SDLC scale and the close (9–18). If we run short we skip Course 16 and Course 17 — never Course 11 or Course 15.</p>
</section>

<section class="slide" data-lesson="From prompt to loop" data-min="3" data-notes="Four layers, each wrapping the one before. Prompt engineering is the words you send; its ceiling is that a perfect sentence cannot supply facts the model never saw. Context engineering is everything the model sees at inference: history, documents, tool output, state. That is where our spec will live. Harness engineering is the environment one run lives in: tools, sandbox, constraints, a protocol. It makes one run reliable. Loop engineering is the cycle that repeats, checks, retries and stops that run without a human on every turn. By hand the human is the loop: prompt, inspect, correct, prompt again. Engineered, a system runs trigger, act, check, retry, continue or stop, and the human designs the loop and signs at the gates. That is the whole talk in one slide.">
  <div class="lesson-tag">Course 1</div>
  <h2>From prompt to loop</h2>
  <div class="two">
    <div>
      <h3>Four layers, each wrapping the last</h3>
      <div class="ladder">
        <div class="reveal rung"><b>Prompt engineering</b> — the words you send. A perfect sentence cannot supply facts the model never saw.</div>
        <div class="reveal rung"><b>Context engineering</b> — everything the model sees: history, documents, tool output, state. The spec lives here.</div>
        <div class="reveal rung"><b>Harness engineering</b> — the environment one run lives in: tools, sandbox, constraints, a protocol. Makes one run reliable.</div>
        <div class="reveal rung"><b>Loop engineering</b> — the cycle that repeats, checks, retries and stops that run without a human on every turn.</div>
      </div>
    </div>
    <div class="panel">
      <h3>Who is the loop?</h3>
      <div class="reveal callout warn">By hand: prompt → inspect → correct → prompt again. The human is the loop.</div>
      <div class="reveal callout">Engineered: trigger → act → check → retry → continue or stop. A system runs the loop; the human designs it and signs at the gates.</div>
      <p class="small">After Addy Osmani, 'Loop Engineering' (2026); tosea.ai; The AI Runtime.</p>
    </div>
  </div>
  <div class="punch">Stop writing the perfect prompt. Design the loop that writes it.</div>
</section>

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
      <p class="small">the finished pipeline — built one stage at a time from Course 6</p>
    </div>
  </div>
  <div class="punch">Use code where determinism is enough. Use agents where reasoning is required. Keep people where judgment matters.</div>
</section>
`;

window.PARTS_INIT[1] = function () {
  var el = document.getElementById('p1l1-canvas');
  if (el) { LoopCanvas.mount(el, { stage: 8, ns: 'p1l1', silhouette: true }); }
};
