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

<section class="slide" data-lesson="Title" data-min="0" data-notes="We have about forty-five minutes. We are going to build one restaurant kitchen on screen, station by station, and by the end you will see a real request go all the way from a guest's order to a plate on the table, with the AI doing the cooking and people doing the tasting. Every kitchen term maps to a real engineering step, and we will name both as we go. You do not need to know how software is written. You need to know how a good kitchen — and a good delivery pipeline — is run.">
  <div class="title-grid">
    <div>
      <h1>The AI Kitchen</h1>
      <p class="small">How AI-native engineering builds and ships software — told through one working kitchen.</p>
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

<section class="slide" data-lesson="Monday, 9:04 a.m." data-min="3" data-notes="Start with Priya, not with technology. Her request is tiny and completely reasonable, and in most organisations it still takes six weeks, most of which is waiting and misunderstanding, not building. Everyone here has already tried an AI that writes code, and it did not make that six weeks disappear, because the cook was never the bottleneck. The kitchen was. This deck is about the kitchen. We will follow Priya's spreadsheet button the whole way through, and it is a real request from a real run of this system.">
  <style>.p1-hook .panel{padding:12px 16px}.p1-hook h3{margin:8px 0 6px}.p1-hook .stack{gap:8px}</style>
  <div class="p1-hook">
  <div class="lesson-tag">Hook</div>
  <h2>Monday, 9:04 a.m.</h2>
  <div class="two">
    <div class="panel">
      <h3>The request</h3>
      <div class="reveal callout">Priya runs sales operations. She filters the orders screen every morning and needs one small thing: a button that downloads exactly what she is looking at as a spreadsheet.</div>
    </div>
    <div class="panel">
      <h3>Where it usually goes</h3>
      <div class="stack">
        <div class="reveal callout">Week 1 — it is logged and prioritised.</div>
        <div class="reveal callout">Week 3 — a developer picks it up and comes back with six questions.</div>
        <div class="reveal callout">Week 5 — it is built. It exports the wrong columns.</div>
        <div class="reveal callout">Week 6 — it ships. Priya has been using a workaround for a month and a half.</div>
      </div>
    </div>
  </div>
  <div class="reveal callout warn">Everyone in this room has an AI that can write code now. So why is the spreadsheet button still six weeks away?</div>
  <div class="punch">Everyone has an AI that can cook. Almost nobody has a kitchen.</div>
  </div>
</section>

<section class="slide" data-lesson="Menu" data-min="2" data-notes="This is the shape of the next forty-five minutes. Twelve short courses, one picture that grows. Each course is one idea and one line worth remembering. If we run short we drop Course 4, the under-the-hood one, never Course 7 or Course 10, the parallel stations and the full run.">
  <div class="lesson-tag">Menu</div>
  <h2>Twelve courses through an AI-native delivery pipeline.</h2>
  <div id="p1-agenda" class="agenda"></div>
  <p class="small" style="margin-top:10px;">Total: <span id="p1-agenda-total"></span></p>
  <p style="margin-top:18px;">One diagram is built on screen a station at a time. If we run short we skip Course 4 — never Course 7 or Course 10.</p>
</section>

<section class="slide" data-lesson="Who works in the kitchen" data-min="3" data-notes="Three kinds of workers build everything you are about to see. Equipment is deterministic: a thermometer gives the same reading every time, and in software that is the automated tests. Line cooks are the AI agents: fast, skilled, good in the fuzzy middle where a ticket needs interpreting. And the chef at the pass carries judgment, the decisions a person has to put their name to. The entire machine is just these three arranged well. The gray silhouette on the right is the finished kitchen. Do not try to read it yet.">
  <div class="lesson-tag">Course 1</div>
  <h2>Who works in the kitchen</h2>
  <div class="two">
    <table class="tbl">
      <tr><th>Who</th><th>What they are good at</th><th>In this kitchen</th></tr>
      <tr><td>Equipment</td><td>The same result every single time — timers, thermometers, scales</td><td>Runs the tests, checks the numbers, sets up a clean workspace</td></tr>
      <tr><td>Line cooks</td><td>Skill and speed in the fuzzy middle — reading a ticket, improvising, fixing a dish</td><td>The AI agents: interpreting, building, debugging, planning</td></tr>
      <tr><td>The chef and the pass</td><td>Judgment. The calls that need a person's name on them</td><td>What we should make, what matters, is it right, what we trade off</td></tr>
    </table>
    <div>
      <div id="p1l1-canvas" class="loopcanvas"></div>
      <p class="small">the whole kitchen — we build it one station at a time</p>
    </div>
  </div>
  <div class="punch">Let equipment do what equipment does. Let cooks cook. Keep a chef on the pass.</div>
</section>
`;

window.PARTS_INIT[1] = function () {
  var el = document.getElementById('p1l1-canvas');
  if (el) { LoopCanvas.mount(el, { stage: 8, ns: 'p1l1', silhouette: true }); }
};
