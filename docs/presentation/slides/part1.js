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
