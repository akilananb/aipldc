window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[1] = `
<style>
  /* Part 1 bespoke one-offs — every selector scoped under the p1- prefix. */
  #p1-title-arc {
    stroke-dasharray: 565.5;
    stroke-dashoffset: 565.5;
    animation: p1-draw-arc 1.8s cubic-bezier(.45,0,.2,1) .2s forwards;
  }
  @keyframes p1-draw-arc {
    to { stroke-dashoffset: 0; }
  }
  #p1-spin-group {
    transform-origin: 100px 100px;
    animation: p1-spin 8s linear 2s infinite;
  }
  @keyframes p1-spin {
    to { transform: rotate(360deg); }
  }
  .p1-legend { display:flex; flex-wrap:wrap; gap:8px; margin-top:20px; }
  .p1-legend .card-chip { min-width:0; }
  .p1-legend .card-chip b { color:var(--ink); }
</style>

<section class="slide" data-lesson="Title" data-min="0" data-notes="We have ninety minutes. One diagram grows from two boxes into a full factory. Do not try to hold the whole machine in your head now. We build it piece by piece, and every piece answers the same question: where does the human decision sit?">
  <div class="title-grid">
    <div>
      <h1>AI PDLC — Loops, Workflows, and the Software Factory</h1>
      <p class="small" style="font-size:20px;color:var(--ink-2);margin-top:6px;">90 minutes · we build one loop, piece by piece, until it is a factory</p>
      <div class="callout" style="margin-top:18px;">One diagram. It starts as two boxes and grows a stage at a time. By the end it runs end to end.</div>
      <div class="p1-legend">
        <span class="card-chip"><b>→</b> / space: next slide</span>
        <span class="card-chip"><b>←</b>: back</span>
        <span class="card-chip"><b>Home</b> / <b>End</b>: jump</span>
        <span class="card-chip"><b>N</b>: notes</span>
        <span class="card-chip"><b>T</b>: reset timer</span>
        <span class="card-chip"><b>F</b>: fullscreen</span>
      </div>
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
        <polygon points="180,56 198,72 172,80" fill="var(--pass)"/>
      </g>
    </svg>
  </div>
</section>

<section class="slide" data-lesson="Map" data-min="3" data-notes="This is the shape of the next ninety minutes. Twenty-one atomic lessons, one diagram that grows. Each lesson is one idea and one punchline. If we run long we cut L3 or L10, never L13, L17, or L19. Those are the load-bearing lessons: parallelization, the full flow, and what a live run taught us.">
  <div class="lesson-tag">Map</div>
  <h2>Twenty-one lessons. One growing diagram.</h2>
  <div id="p1-agenda" class="agenda"></div>
  <p class="small" style="margin-top:10px;">Total: <span id="p1-agenda-total"></span></p>
  <p style="margin-top:18px;">21 atomic lessons. One diagram that grows. If we run long we cut L3 or L10 — never L13, L17, or L19.</p>
</section>

<section class="slide" data-lesson="The opening provocation" data-min="4" data-notes="Start with the provocation. The loop is not the product. The old mental model was a chain: prompt, then agent, then loop. But the loop was never the deliverable. The workflow around it was. In ninety minutes we build a machine two boxes at a time, and the finished thing is already here as a silhouette.">
  <div class="lesson-tag">Lesson 1</div>
  <h2>The opening provocation</h2>
  <div class="two">
    <div class="panel">
      <h3>The old model</h3>
      <div class="stack">
        <div class="reveal callout">1 · Prompt — you type a request.</div>
        <div class="reveal callout">2 · Agent — the model does the work.</div>
        <div class="reveal callout">3 · Loop — it iterates until something passes.</div>
      </div>
      <div class="callout warn">The loop runs. But the loop is not the product.</div>
    </div>
    <div>
      <div id="p1l1-canvas" class="loopcanvas"></div>
      <p class="small" style="margin-top:8px;">in 90 minutes, this machine exists — we build it two boxes at a time</p>
    </div>
  </div>
  <div class="punch">The loop is not the product. The product is the workflow.</div>
</section>

<section class="slide" data-lesson="Where we started" data-min="4" data-notes="Before the loop, walk the eras. Each one shortened the distance between intent and running code. Waterfall closed intent to a plan. Agile closed plan to a shippable slice. CI/CD closed change to production. Cloud closed hardware to configuration. AI-assisted closes words to a first draft. Every era removed friction, and none of them removed the human decision.">
  <div class="lesson-tag">Lesson 2</div>
  <h2>Where we started</h2>
  <div class="timeline">
    <div class="era reveal"><b>Waterfall</b><span class="small">intent → a signed plan</span></div>
    <div class="era reveal"><b>Agile</b><span class="small">plan → a shippable slice</span></div>
    <div class="era reveal"><b>CI/CD</b><span class="small">change → production</span></div>
    <div class="era reveal"><b>Cloud/DevOps</b><span class="small">hardware → configuration</span></div>
    <div class="era reveal"><b>AI-assisted</b><span class="small">words → a first draft</span></div>
  </div>
  <div class="punch">Every era shortened the distance between intent and running code. None removed the human decision.</div>
</section>

<section class="slide" data-lesson="Who writes the prompt" data-min="4" data-notes="The question used to be: can it code. The real question is: who writes the prompt. Walk the ladder. First the human wrote freely. Then the human wrote structured prompts. Then the human handed over context. Then the spec wrote the prompt, then the harness, then the loop. At the top of the ladder the whole machine writes the prompt, and the human writes the workflow.">
  <div class="lesson-tag">Lesson 3</div>
  <h2>Who writes the prompt</h2>
  <div class="ladder">
    <div class="rung reveal"><b>vibe coding</b> — the human types whatever comes to mind.</div>
    <div class="rung reveal"><b>prompt engineering</b> — the human writes a structured prompt with examples.</div>
    <div class="rung reveal"><b>context engineering</b> — the human hands the model the files it needs.</div>
    <div class="rung reveal"><b>spec-driven development</b> — the spec is the prompt.</div>
    <div class="rung reveal"><b>harness-driven</b> — the harness drives the agent and verifies the result.</div>
    <div class="rung reveal"><b>loop-driven</b> — the loop feeds results back in and decides.</div>
    <div class="rung reveal"><b>AI PDLC</b> — the whole machine writes the prompt; the human writes the workflow.</div>
  </div>
  <div class="punch">What changed is who writes the prompt.</div>
</section>
`;

window.PARTS_INIT[1] = function () {
  var el = document.getElementById('p1l1-canvas');
  if (el) { LoopCanvas.mount(el, { stage: 8, ns: 'p1l1', silhouette: true }); }
};
