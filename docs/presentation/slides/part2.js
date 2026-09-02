window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[2] = `
<section class="slide" data-lesson="The three actors" data-min="5" data-notes="Three kinds of actors build everything in this deck: code, engineers, and agents. Code is deterministic — run the tests, parse the results, check the exit code, create the worktree. Engineers carry judgment — what to build, what matters, whether it is correct, what tradeoffs to accept. Agents are flexible reasoners — interpretation, exploration, implementation, debugging, planning, adapting. The whole machine is just these three actors arranged into loops. The art is putting each one where it is strongest.">
  <span class="lesson-tag">Lesson 4</span>
  <h2>The three actors</h2>
  <p>Everything in this deck is built from three kinds of actors. Each one is best at exactly one thing.</p>

  <div class="three">
    <div class="panel reveal">
      <h3>Code</h3>
      <ul>
        <li>Run the tests</li>
        <li>Parse the results</li>
        <li>Check exit codes</li>
        <li>Create worktrees</li>
      </ul>
      <p class="small">Deterministic. Same input, same output, every time.</p>
    </div>
    <div class="panel reveal">
      <h3>Engineers</h3>
      <ul>
        <li>What should be built</li>
        <li>What matters</li>
        <li>Is it correct</li>
        <li>What tradeoffs</li>
      </ul>
      <p class="small">Judgment. The decisions that need a human name on them.</p>
    </div>
    <div class="panel reveal">
      <h3>Agents</h3>
      <ul>
        <li>Interpretation</li>
        <li>Exploration</li>
        <li>Implementation</li>
        <li>Debugging</li>
        <li>Planning</li>
        <li>Adapting</li>
      </ul>
      <p class="small">Flexible reasoning. Strong in the fuzzy middle.</p>
    </div>
  </div>

  <div class="punch">Use code where determinism is enough. Use agents where reasoning is required. Keep engineers where judgment matters.</div>
</section>

<section class="slide" data-lesson="SDD — the spec is the prompt" data-min="4" data-notes="SDD means the spec is the prompt. A story card is a sentence; a spec delta is a directory of decisions. proposal.md says what changes and why; specs/area/spec.md says exactly what the new behavior is; tasks.md breaks it into verifiable steps. Gherkin Scenario names become the verifier's test names — one real scenario was export-button-available-with-active-filters. The agent builds to the contract, not to the opinion.">
  <span class="lesson-tag">Lesson 5</span>
  <h2>SDD — the spec is the prompt</h2>
  <p>A story card is a sentence. A spec delta is a directory of decisions.</p>

  <div class="panel" style="max-width:56ch">
    <p class="small" style="margin:0 0 6px">STORY CARD</p>
    <p class="mono" style="margin:0">"As a user, I want to export my results."</p>
    <p class="small" style="margin:8px 0 0">An opinion — nothing a machine can verify.</p>
  </div>

  <p class="small" style="margin:16px 0 8px">The same intent, exploded into a spec delta — three files:</p>

  <div class="row">
    <span class="card-chip reveal">openspec/changes/&lt;slug&gt;/proposal.md</span>
    <span class="card-chip reveal">specs/&lt;area&gt;/spec.md</span>
    <span class="card-chip reveal">tasks.md</span>
  </div>

  <div class="reveal">
    <div class="panel" style="margin-top:18px;border-left:4px solid var(--pass)">
      <p class="small" style="margin:0 0 6px">REAL RUN — the spec becomes the test</p>
      <p style="margin:0 0 6px">Gherkin <code>Scenario:</code> names become the verifier's test names.</p>
      <p class="mono" style="margin:0">scenario: export-button-available-with-active-filters</p>
    </div>
  </div>

  <div class="punch">A story card is an opinion. A spec delta is a contract.</div>
</section>

<section class="slide" data-lesson="The simplest loop — stages 1 &amp; 2" data-min="5" data-notes="This is where the loop appears for the first time. Stage one is the smallest useful workflow: an engineer drives an agent, and the agent's work goes to review. Stage two adds one deterministic box — verify, running the repo's own npm test. If verify passes, the diff goes to review. If it fails, the failure loops back into the same agent session as new context. Agents plus code beat agents alone, because code gives the agent a ground truth to retry against.">
  <span class="lesson-tag">Lesson 6</span>
  <h2>The simplest loop — stages 1 &amp; 2</h2>
  <p>This is the smallest useful workflow. An engineer drives an agent. The agent's work goes to review.</p>

  <div id="p2l6-canvas" class="loopcanvas"></div>

  <p class="small" style="margin-top:14px">The agent writes code. Deterministic verify runs the repo's own tests. PASS → review. FAIL → back into the same agent session, the failure becomes the next prompt.</p>

  <div class="punch">Agents plus code beat agents alone.</div>
</section>
`;

window.PARTS_INIT[2] = function () {
  LoopCanvas.mount(document.getElementById('p2l6-canvas'), { stage: 2, ns: 'p2l6', revealFrom: 1 });
};
