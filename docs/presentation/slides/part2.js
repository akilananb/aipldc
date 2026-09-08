window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[2] = `
<section class="slide" data-lesson="SDD: the order slip is the spec" data-min="3" data-notes="Spec-driven development means the request is rewritten into a form that can be checked before anyone builds. Three files. The proposal says what changes and why and lists the scenarios. The spec delta says what is added, modified or removed, each as a given-when-then scenario. The task list breaks it into steps that each prove one scenario. Like a printed order slip with one line per customization: no onions is a line, and the kitchen can tick it. Every scenario line literally becomes a test later.">
  <div class="lesson-tag">Course 3</div>
  <h2>SDD: the order slip is the spec</h2>
  <div class="two">
    <div class="panel">
      <h3>The wish</h3>
      <div class="reveal callout warn">'Let me export my filtered orders.'</div>
      <p class="small">Nothing here can be checked.</p>
    </div>
    <div class="panel">
      <h3>The spec — three files</h3>
      <div class="stack">
        <div class="reveal callout"><b>proposal.md</b> — what changes and why, plus the acceptance scenarios.</div>
        <div class="reveal callout"><b>specs/&lt;area&gt;/spec.md</b> — the spec delta: ADDED / MODIFIED / REMOVED requirements, each with a Gherkin scenario.</div>
        <div class="reveal callout"><b>tasks.md</b> — the steps, each proving exactly one scenario.</div>
      </div>
    </div>
  </div>
  <div class="reveal callout">One scenario from the real run: 'export-filtered-orders-with-correct-columns' — GIVEN filters are applied, WHEN the user exports, THEN the CSV has exactly: Order ID, date, customer name, status, total. That line becomes the test.</div>
  <div class="punch">A story card is an opinion. A spec delta is a contract.</div>
</section>

<section class="slide" data-lesson="The OpenSpec change folder" data-min="4" data-notes="These are the real files from the real run, unedited. The proposal carries the area marker and the scenarios in plain Gherkin. The spec delta restates each scenario as an added requirement. The task list has one task per scenario, in waves, and every task carries three facts: which scenario it proves, which files it may touch, which test proves it. Those three facts are the contract every later stage enforces.">
  <style>.p2-files pre{font-family:var(--mono);font-size:12.5px;line-height:1.45;margin:0;white-space:pre-wrap;color:var(--ink)}.p2-files .panel{padding:12px 16px}</style>
  <div class="p2-files">
  <div class="lesson-tag">Course 4</div>
  <h2>The OpenSpec change folder</h2>
  <div class="two">
    <div class="stack">
      <div class="panel">
        <h3>openspec/changes/export-filtered-orders-view-to-csv/</h3>
        <pre>proposal.md
specs/orders/spec.md
tasks.md</pre>
      </div>
      <div class="panel">
        <h3>proposal.md</h3>
        <pre>## Acceptance criteria
Area: orders
Scenario: export-filtered-orders-with-correct-columns
  GIVEN a sales ops user has applied filters to the orders view
  WHEN the user triggers the CSV export
  THEN a CSV file is generated containing only the filtered
       orders with columns in this exact order: Order ID,
       date, customer name, status, total</pre>
      </div>
    </div>
    <div class="stack">
      <div class="panel">
        <h3>specs/orders/spec.md</h3>
        <pre># orders

## ADDED Requirements

### Requirement: export-filtered-orders-with-correct-columns
The system SHALL support the
"export-filtered-orders-with-correct-columns" scenario.</pre>
      </div>
      <div class="panel">
        <h3>tasks.md</h3>
        <pre>## Wave 1
- T1 Implement "export-filtered-orders-with-correct-columns"
  (proves: export-filtered-orders-with-correct-columns;
   touches: src/export.js; test: test/export.test.js)</pre>
      </div>
    </div>
  </div>
  <div class="punch">Four scenarios in, four tasks out — each one names the file it may touch and the test that proves it.</div>
  </div>
</section>

<section class="slide" data-lesson="Customizing the schema: config.yaml" data-min="3" data-notes="The schema is customized in one file. Areas map to the paths a task may touch and the test that proves it. The PO agent injects the area marker into the story so the plan agent does not depend on the model remembering it. The plan agent resolves the area through this map and writes touches and test onto every task; if the file is missing or the area unknown it warns and falls back to a naming convention. Like a kitchen ticket that also says which station cooks it.">
  <div class="p2-files">
  <div class="lesson-tag">Course 5</div>
  <h2>Customizing the schema: config.yaml</h2>
  <div class="two">
    <div class="panel">
      <h3>openspec/config.yaml</h3>
      <pre>areas:
  orders:
    touches:
      - src/export.js
    test: test/export.test.js</pre>
    </div>
    <div class="stack">
      <div class="reveal callout">This is the one thing a team customizes: the map from a spec area to the code it may touch and the test that proves it.</div>
      <div class="reveal callout">The PO agent stamps 'Area: orders' onto the story deterministically, so the plan agent never depends on the model remembering it.</div>
      <div class="reveal callout">The plan agent reads this map and turns 'Area: orders' into touches: src/export.js and test: test/export.test.js on every task.</div>
      <div class="reveal callout warn">Missing file or unknown area: the plan agent logs a warning and falls back to the naming convention src/&lt;area&gt;.js and test/&lt;area&gt;.test.js — it never stops the pipeline.</div>
    </div>
  </div>
  <div class="punch">Customize the map, not the agents. The schema decides where an agent is allowed to cook.</div>
  </div>
</section>

<section class="slide" data-lesson="The simplest loop" data-min="4" data-notes="This is the loop everyone talks about, and it works because of the one deterministic piece: the tests. The engineer prompts the agent. The agent edits. The tests run. Green goes to review; red goes back into the same session with the failure attached, and the agent retries against something true. Like a thermometer in the kitchen: no opinion, same reading every time. This is a first sighting of two loop-contract decisions — verify by code, retry into the same session — before we name all four formally, two courses from now.">
  <div class="lesson-tag">Course 6</div>
  <h2>The simplest loop</h2>
  <div class="two">
    <div id="p2l6-canvas" class="loopcanvas"></div>
    <div class="stack">
      <div class="row p-tags"><span class="pill pass">Verify</span><span class="pill">Retry</span></div>
      <div class="reveal callout">An engineer prompts a coding agent — stage 1.</div>
      <div class="reveal callout">Stage 2 adds one piece of code: the repo's own npm test runs before anything reaches review.</div>
      <div class="reveal callout">PASS goes forward to a PR. FAIL loops straight back into the same agent session with the failing test as the next prompt.</div>
      <div class="reveal callout warn">Verify is the repo's own test, not the agent's opinion. Retry is the same session re-entered with the failure attached — the first two of a four-part contract, named two courses on.</div>
    </div>
  </div>
  <div class="punch">Agents plus code beat agents alone.</div>
</section>
`;

window.PARTS_INIT[2] = function () {
  LoopCanvas.mountFlow(document.getElementById('p2l6-canvas'), { stage: 2, ns: 'p2l6', revealFrom: 1 });
};
