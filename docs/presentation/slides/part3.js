/* Part 3 — Under the hood: the real tools behind the kitchen analogy, named explicitly
 * (equal weight — not small print). Registers window.PARTS[3] (template-literal HTML) and
 * window.PARTS_INIT[3] (canvas wiring). Every id is prefixed p3-. LoopCanvas is Director-owned —
 * mount only, never edit loop.js. */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[3] = `
<style>
  /* Course 4 packs four cards + intro + punch; tighten this slide's stack/card padding only. */
  .p3-tools.stack { gap: 6px; }
  .p3-tools .rule-card { padding: 8px 12px; }
</style>
<section class="slide" data-lesson="Under the hood" data-min="3" data-notes="One course naming the real software behind every kitchen term you have seen. omp is the coding agent: precise edits, a session you can keep driving, its own clean copy of the code — the cook's knife roll. ACP is the protocol that lets either a person or the machine drive that same agent session — the kitchen intercom. Embabel is the hand-off format: every step between AI helpers is a filled-in form, not a paragraph — the ticket format. And Temporal is the durable workflow engine that holds every order from placed to served, even across a days-long wait for a signature, and survives a restart — the order rail that never forgets.">
  <div class="lesson-tag">Course 4</div>
  <h2>Under the hood</h2>
  <p>The real software behind every kitchen term you have seen so far.</p>

  <div class="two">
    <div id="p3l7-canvas" class="loopcanvas small"></div>
    <div class="stack p3-tools">
      <div class="reveal"><div class="rule-card"><b>omp — the coding agent</b><br>Precise edits, a session you can keep driving, its own clean copy of the code. Think of it as the cook's knife roll.</div></div>
      <div class="reveal"><div class="rule-card"><b>ACP — the protocol</b><br>Lets a person or the machine drive that same agent session, by terminal or by code. The kitchen intercom.</div></div>
      <div class="reveal"><div class="rule-card"><b>Embabel — the hand-off format</b><br>Every step between AI helpers is a filled-in form, not a paragraph. The ticket format.</div></div>
      <div class="reveal"><div class="rule-card"><b>Temporal — the durable workflow engine</b><br>Holds every order from placed to served, even across a days-long wait for a signature, and survives a restart. The order rail that never forgets.</div></div>
    </div>
  </div>

  <div class="punch">Good kitchens run on boring, reliable equipment. The AI is the cook, not the kitchen.</div>
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
