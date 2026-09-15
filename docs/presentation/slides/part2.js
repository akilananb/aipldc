window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[2] = `
<section class="slide" data-lesson="Spec-driven development: intent you can check" data-min="3" data-notes="Bridge from Course 5: the simplest loop proved verify and retry once something checkable exists — but nothing in Priya's sentence is checkable yet. Spec-driven development rewrites the request until it is, and that rewritten form is what enters the harness window. Two halves. Intent: the proposal says why and what; the delta says exactly what is added, modified or removed against the current spec, and the spec, not the card, is the source of truth. Validation: code checks the spec before a human reads it, because INVEST and Definition-of-Ready are deterministic validators; a person signs it once at G1; and the repo checks it forever, because every scenario line becomes a named test. Kitchen: the printed slip, one line per customization, and the kitchen can tick each one.">
  <div class="lesson-tag">Course 6</div>
  <h2>Spec-driven development: intent you can check</h2>
  <p>Course 5's simplest loop proved verify and retry — but only once something checkable exists. Nothing in 'let me export my filtered orders' is checkable yet. Spec-driven development rewrites the request until it is — and that is what enters the harness window.</p>
  <div class="two">
    <div class="panel">
      <h3>Intent — written so it can be read back</h3>
      <div class="stack">
        <div class="reveal callout warn">'Let me export my filtered orders.' Nothing here can be checked.</div>
        <div class="reveal callout"><b>proposal.md</b> — why and what: the intent, the scope, and every acceptance scenario as GIVEN / WHEN / THEN.</div>
        <div class="reveal callout"><b>specs/&lt;area&gt;/spec.md</b> — the delta against the current spec: ADDED / MODIFIED / REMOVED requirements. The spec, not the story card, is the source of truth.</div>
      </div>
    </div>
    <div class="panel">
      <h3>Validation — three checks, three checkers</h3>
      <div class="stack">
        <div class="reveal callout"><b>By code, first</b> — INVEST and Definition-of-Ready are deterministic validators: every scenario has a delta section, every answered question is referenced, every parked one is an out-of-scope line.</div>
        <div class="reveal callout"><b>By a person, once</b> — G1 signs the spec, not the code. Change a word and the signature resets.</div>
        <div class="reveal callout"><b>By the repo, forever</b> — every scenario line becomes a named test. A task is not done until that test is green.</div>
        <div class="reveal callout warn">The printed order slip: one line per customization, and the kitchen can tick each one.</div>
      </div>
    </div>
  </div>
  <div class="punch">A story card is an opinion. A spec delta is a contract.</div>
</section>

<section class="slide" data-lesson="The change folder is the workflow" data-min="4" data-notes="The OpenSpec change folder is not a document set, it is the workflow: each artifact is produced by one stage and is the next stage's required input, and a stage cannot start until the artifact it requires exists. Proposal from intake, delta from the PO agent and validated before G1, tasks from the plan agent, apply by the build worker in its own worktree, and when the change lands the delta merges into the main spec so the spec stays the source of truth. Below are the real files from the real run, unedited. Every task carries three facts: which scenario it proves, which files it may touch, which test proves it. Those three facts are the contract every later stage enforces.">
  <style>.p2-files pre{font-family:var(--mono);font-size:12.5px;line-height:1.45;margin:0;white-space:pre-wrap;color:var(--ink)}.p2-files .panel{padding:12px 16px}.p2-flow{margin-bottom:14px;flex-wrap:nowrap}.p2-flow .era{min-width:0;flex:1;padding:10px 12px}.p2-flow .era b{font-size:14px;font-family:var(--mono)}.p2-flow .era p.small{font-size:12.5px;margin:0;line-height:1.3}</style>
  <div class="p2-files">
  <div class="lesson-tag">Course 7</div>
  <h2>The change folder is the workflow</h2>
  <div class="timeline p2-flow">
    <div class="reveal era"><b>proposal.md</b><p class="small">Intent. Intake: grill, then PO (Course 9).</p></div>
    <div class="reveal era"><b>specs/…/spec.md</b><p class="small">The delta. Validated by code, signed at G1 (Course 10).</p></div>
    <div class="reveal era"><b>tasks.md</b><p class="small">One task per scenario. Plan agent (Course 11).</p></div>
    <div class="reveal era"><b>apply</b><p class="small">Build worker, own worktree, the repo's npm test (Course 11).</p></div>
    <div class="reveal era"><b>archive</b><p class="small">Delta merges into specs/. The spec stays the source of truth.</p></div>
  </div>
  <div class="two">
    <div class="panel">
      <h3>openspec/changes/export-filtered-orders-view-to-csv/proposal.md</h3>
      <pre>## Acceptance criteria
Area: orders
Scenario: export-filtered-orders-with-correct-columns
  GIVEN a sales ops user has applied filters to the orders view
  WHEN the user triggers the CSV export
  THEN a CSV file is generated containing only the filtered
       orders with columns in this exact order: Order ID,
       date, customer name, status, total</pre>
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
  <div class="punch">Four scenarios in, four tasks out — and each file is the next stage's only input.</div>
  </div>
</section>

<section class="slide" data-lesson="Define the workflow once, share it" data-min="3" data-notes="Two files define the workflow, and both live in the repo. schema.yaml is OpenSpec's workflow definition: which artifacts exist, what each one requires, what apply tracks; fork the default and edit it, and every agent follows the new order. config.yaml is the team's customization on top: the default schema, project context and per-artifact rules injected into every agent prompt, and in this pipeline the areas map, which is how the area on a story becomes the files a task may touch and the test that proves it. The PO agent stamps the area deterministically; the plan agent resolves it through the map, and if the file or the area is missing it warns and falls back to a naming convention instead of stopping. Because both are files in git, sharing the workflow is git clone; a schema can also live at user level for every project on a machine, or be published as a community schema other teams copy in. Kitchen: the house recipe binder, written once, copied to every new kitchen the group opens. Bridge: the scenarios now exist as text, and Course 5's simplest loop already proved the smallest version of this on one of them — next, Course 9 runs the same shape at SDLC scale, starting with intake.">
  <div class="p2-files">
  <div class="lesson-tag">Course 8</div>
  <h2>Define the workflow once, share it</h2>
  <div class="two">
    <div class="stack">
      <div class="panel">
        <h3>openspec/schemas/team/schema.yaml — the workflow</h3>
        <pre>artifacts:
  - id: proposal
    generates: proposal.md
    requires: []
  - id: specs
    generates: specs/**/*.md
    requires: [proposal]
  - id: tasks
    generates: tasks.md
    requires: [specs]
apply:
  requires: [tasks]
  tracks: tasks.md</pre>
      </div>
      <div class="panel">
        <h3>openspec/config.yaml — the team's map</h3>
        <pre>areas:
  orders:
    touches:
      - src/export.js
    test: test/export.test.js</pre>
      </div>
    </div>
    <div class="stack">
      <div class="reveal callout"><b>Define</b> — schema.yaml says which artifacts exist, what each requires and what apply tracks. openspec schema fork spec-driven team copies the default; edit it and every agent follows the new order.</div>
      <div class="reveal callout"><b>Customize</b> — config.yaml adds project context and per-artifact rules injected into every prompt, and in this pipeline the areas map: the PO agent stamps 'Area: orders' by code, the plan agent turns it into touches: src/export.js and test: test/export.test.js on every task.</div>
      <div class="reveal callout"><b>Share</b> — both are files in the repo, so the workflow travels with git clone. A schema can also sit at user level for every project on a machine, or be published as a community schema other teams copy in.</div>
      <div class="reveal callout warn">Missing file or unknown area: the plan agent warns and falls back to src/&lt;area&gt;.js and test/&lt;area&gt;.test.js — it never stops the pipeline.</div>
      <div class="reveal callout ok">Next: the workflow is defined, and Course 5's simplest loop already proved verify and retry on one scenario. Course 9 runs the same shape at SDLC scale, starting with intake.</div>
    </div>
  </div>
  <div class="punch">The workflow is a file. Every agent, and every team that clones the repo, runs the same one.</div>
  </div>
</section>
`;
