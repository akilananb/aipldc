window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[2] = `
<section class="slide" data-lesson="A recipe, not a wish" data-min="3" data-notes="A story card is a sentence, and a sentence is an opinion; nothing in it can be checked. So the first thing this kitchen does is turn the wish into a recipe — a spec delta: what changes and why (the proposal), exactly what the finished dish must do written as Gherkin given-when-then scenarios (the spec), and the steps broken small enough to check (the task list). That is not paperwork. Each scenario line literally becomes the taste test the kitchen runs later. The agent builds to the contract, not to the opinion.">
  <div class="lesson-tag">Course 2</div>
  <h2>A recipe, not a wish</h2>
  <div class="two">
    <div class="panel">
      <h3>The wish</h3>
      <div class="reveal callout warn">'Let me export my filtered orders.'</div>
      <p class="small">A wish. Nothing in it can be checked.</p>
    </div>
    <div class="panel">
      <h3>The recipe — a spec delta</h3>
      <div class="stack">
        <div class="reveal callout">What changes, and why — the proposal.</div>
        <div class="reveal callout">Exactly what the finished dish must do, written as Gherkin scenarios: given this, when that, then this happens.</div>
        <div class="reveal callout">The steps, broken small enough to check — the task list.</div>
      </div>
    </div>
  </div>
  <div class="reveal callout">In the real run, one Gherkin scenario — 'export button is available when filters are active' — became the kitchen's exact taste test.</div>
  <div class="punch">A wish is an opinion. A recipe is a contract.</div>
</section>

<section class="slide" data-lesson="Cook, taste, serve" data-min="4" data-notes="This is the smallest kitchen that actually works, and it is the loop everyone talks about. An engineer tells an AI coding agent what to build. Then one piece of equipment: the codebase's own automated tests, which is the thermometer. If the reading is good the change goes forward to PR review. If it is not, it loops straight back into the same agent session with the failure as the next prompt. Agents plus automated tests beat agents alone, because the tests give the agent something true to retry against.">
  <div class="lesson-tag">Course 3</div>
  <h2>Cook, taste, serve</h2>
  <div class="two">
    <div id="p2l6-canvas" class="loopcanvas"></div>
    <div class="stack">
      <div class="reveal callout">The engineer tells the AI coding agent — the cook — what to build.</div>
      <div class="reveal callout">Before the change reaches review, the codebase's own automated tests — the thermometer — check it. Same result every time. No opinions.</div>
      <div class="reveal callout">Tests fail? It loops straight back into the same agent session — the same cook — with the failure as the next prompt.</div>
      <div class="reveal callout warn">Only a change that passes the tests — the taste test — reaches the PR review — the plating check.</div>
    </div>
  </div>
  <div class="punch">Agents plus automated tests beat agents alone.</div>
</section>
`;

window.PARTS_INIT[2] = function () {
  LoopCanvas.mountFlow(document.getElementById('p2l6-canvas'), { stage: 2, ns: 'p2l6', revealFrom: 1 });
};
