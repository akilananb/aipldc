/* Part 4 — Writer-Pipeline — L11–L16 (stages 3–8 of the shared LoopCanvas).
 * Six lessons, each mounting the SAME growing canvas at exactly one new stage.
 * LoopCanvas.mount() renders every included node/edge directly and lets `deck.js`'s
 * auto-reveal animate them in — do NOT wrap the canvas div in `.reveal`; just place the
 * mount div and write the surrounding lesson content (wrapped in its own `.reveal` for its
 * own stagger-in). */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[4] = `
<section class="slide" data-lesson="Stage 3 — put a spec in front" data-min="5" data-notes="The engineer used to type a prompt straight into the agent — that raw edge retires here. Instead, a card is filed on the board, and the grill agent interrogates it across six fixed categories: scope, users and roles, acceptance, risk, dependency, and NFR. Every question must cite the evidence that prompted it — a file, a spec line, an incident — or be marked assumption-check, and it must never ask what the description already says. When every question is answered or parked, the PO agent writes the story twice: a Gherkin spec delta with ADDED, MODIFIED, and REMOVED sections, and a human-readable story that mirrors it word for word. That delta — not a prompt — is what feeds the agent.">
  <span class="lesson-tag">Lesson 11 · Part 4</span>
  <h2>Stage 3 — put a spec in front</h2>

  <div class="reveal">
    <p>The engineer used to type a prompt straight into the agent. <b>That edge retires here.</b></p>
  </div>

  <div class="reveal">
    <div class="callout">
      <b>A card, not a prompt.</b> The engineer files a card on the board. The loop now eats a spec delta — <b>the spec is the prompt</b>.
    </div>
  </div>

  <div class="reveal">
    <h3>Grill agent — six categories, every question cited</h3>
    <div class="row">
      <span class="pill">scope</span>
      <span class="pill">users / roles</span>
      <span class="pill">acceptance</span>
      <span class="pill">risk</span>
      <span class="pill">dependency</span>
      <span class="pill">NFR</span>
    </div>
    <p class="small">Each question cites the evidence that prompted it — a file, a spec line, an incident — or is marked <code>assumption-check</code>. It must never ask what the description already says.</p>
  </div>

  <div class="reveal">
    <h3>PO agent — one story, written twice</h3>
    <div class="cols-2">
      <div class="panel">
        <b>Spec delta</b> <span class="small">(what agents build from)</span>
        <ul>
          <li><code>ADDED</code> / <code>MODIFIED</code> / <code>REMOVED</code> sections</li>
          <li>Gherkin <code>Scenario:</code> — GIVEN / WHEN / THEN</li>
          <li>MUST / SHALL / SHOULD / MAY requirements</li>
        </ul>
      </div>
      <div class="panel">
        <b>Story</b> <span class="small">(what humans approve)</span>
        <ul>
          <li>Same acceptance criteria, word for word</li>
          <li>Parked questions become out-of-scope lines</li>
          <li>Every risk answer becomes a scenario or NFR</li>
        </ul>
      </div>
    </div>
  </div>

  <div id="p4l11-canvas" class="loopcanvas"></div>

  <div class="punch">Nothing becomes a story until every question is answered or parked.</div>
</section>

<section class="slide" data-lesson="Stage 4 — add the human gate" data-min="5" data-notes="Stage 4 adds the first human gate. The PO agent has written the story, but no agent crosses a gate — it sets the state to awaiting-G1 and stops. G1 is two named humans, the PO and the Squad Lead, who approve independently: the PO owns intent and acceptance criteria, the Squad Lead owns scope, feasibility, and risk. The maker-checker pattern is identical at every gate — a preview surface with line-level comments, where a blocking comment disables the approve buttons for everyone. Approvals are per version, so a new version clears every earlier signature. In the live run, story 9004's quality check failed at 58, auto-revised, and passed at 88.">
  <span class="lesson-tag">Lesson 12 · Part 4</span>
  <h2>Stage 4 — add the human gate</h2>

  <div class="reveal">
    <p>The story is written — but <b>no agent crosses a gate</b>. It sets the state to <code>awaiting-G1</code> and stops.</p>
  </div>

  <div class="reveal">
    <h3>G1 — two named humans, approving independently</h3>
    <div class="two">
      <div class="panel">
        <b>PO</b>
        <p class="small">approves intent + acceptance criteria</p>
      </div>
      <div class="panel">
        <b>Squad Lead</b>
        <p class="small">approves scope, feasibility, risk</p>
      </div>
    </div>
    <p class="small">Either can send it back with a comment — the PO agent re-runs with that comment as new input.</p>
  </div>

  <div class="reveal">
    <h3>Maker-checker — the pattern is identical at every gate</h3>
    <ul>
      <li>A preview surface with line-level comments</li>
      <li>A <code>blocking</code> comment freezes the gate for everyone</li>
      <li>Signatures are <b>per version</b> — a new version clears all earlier approvals</li>
    </ul>
  </div>

  <div class="reveal">
    <div class="callout ok">
      <b>Live run — story 9004.</b> Quality v1 scored <b>58</b> → failed. Auto-revise → v2 scored <b>88</b> → passed.
    </div>
  </div>

  <div id="p4l12-canvas" class="loopcanvas"></div>

  <div class="punch">Maker-checker: the agent makes; named humans check — per version, with blocking comments.</div>
</section>

<section class="slide" data-lesson="Stage 5 — split, isolate, parallelize" data-min="5" data-notes="Stage 5 splits one loop into three isolated lanes. The plan agent breaks the approved story into tasks in dependency order — one code area, one scenario, one proof per task — and writes them to a queue. Build workers claim tasks over REST at /api/build-tasks/claim, each in its own git worktree, so lanes never touch each other. The verifier is not an LLM — it runs the target repo's own npm test and parses the TAP output, green only if every named top-level test passes. A scope guard reverts any file changed outside the task's touches list, and a shared test file's prior content must survive byte-for-byte. In the live run, all 8 tasks went verifier-green across 24 to 36 iterations, each inside a PT10M wall-clock budget.">
  <span class="lesson-tag">Lesson 13 · Part 4</span>
  <h2>Stage 5 — split, isolate, parallelize</h2>

  <div class="reveal">
    <p>One lane became three. The plan agent splits the approved story into tasks — <b>one code area, one scenario, one proof per task</b>.</p>
  </div>

  <div class="reveal">
    <h3>Isolation — a lane never touches board state</h3>
    <ul>
      <li>Build workers <b>claim</b> tasks over REST — <code>POST /api/build-tasks/claim</code></li>
      <li><b>One git worktree per task</b> — no shared working copy</li>
      <li>The verifier is the repo's own <code>npm test</code>, TAP-parsed</li>
    </ul>
  </div>

  <div class="reveal">
    <h3>The scope guard</h3>
    <div class="panel">
      <ul>
        <li>Any file changed outside the task's <code>touches</code> list is reverted — <code>revertPaths</code></li>
        <li>A shared test file's prior content must survive <b>byte-for-byte</b> — <code>wasTestContentPreserved</code></li>
        <li>An agent can never weaken another task's test to make its own pass</li>
      </ul>
    </div>
  </div>

  <div class="reveal">
    <div class="callout ok">
      <b>Live run.</b> 8 / 8 tasks verifier-green · 24–36 iterations · <code>PT10M</code> wall-clock budget per task.
    </div>
  </div>

  <div id="p4l13-canvas" class="loopcanvas"></div>

  <div class="punch">An inner loop never touches board state. It returns a typed result and the outer loop decides.</div>
</section>

<section class="slide" data-lesson="Stage 6 — review, and the second gate" data-min="4" data-notes="After the lanes finish, the review agent is the first reviewer on every PR. Its findings are tagged blocker, should, or nit — and only a blocker requests changes. A blocker from the review agent sends the task back to the build loop with that finding as its new first line of context. Then the second human gate opens: G2, the FS Developer and QA, who own the review output. Any reviewer can also summon an agent in a comment by mentioning it. The fixed pilot set is four agents — analyst, architect, qa, and dev — and the first mention in a comment wins.">
  <span class="lesson-tag">Lesson 14 · Part 4</span>
  <h2>Stage 6 — review, and the second gate</h2>

  <div class="reveal">
    <p>The review agent is the first reviewer on every PR — findings tagged <code>blocker</code>, <code>should</code>, or <code>nit</code>. Only a <code>blocker</code> requests changes.</p>
  </div>

  <div class="reveal">
    <div class="callout">
      <b>A blocker loops back.</b> It returns to the build loop with the finding as the new first line of context — never an approval.
    </div>
  </div>

  <div class="reveal">
    <h3>G2 — the second human gate</h3>
    <p><b>FS Developer + QA</b> own the review output. They read the review agent's traceability rows, then judge.</p>
  </div>

  <div class="reveal">
    <h3>Any reviewer can summon an agent with an @</h3>
    <div class="panel">
      <div class="row">
        <code>@d</code> <span>→</span> <code>@dev — implementation &amp; code questions, reads the repo</code>
      </div>
      <div class="row">
        <span class="pill">draft: pending</span> <span>→</span> <span class="pill">human approves</span> <span>→</span> <code>appended to review.md</code>
      </div>
    </div>
    <p class="small">Fixed pilot set: <code>@analyst</code>, <code>@architect</code>, <code>@qa</code>, <code>@dev</code>. First mention wins.</p>
  </div>

  <div id="p4l14-canvas" class="loopcanvas"></div>

  <div class="punch">Every agent has a human who owns its output — and any reviewer can summon an agent with an @.</div>
</section>

<section class="slide" data-lesson="Stage 7 — release, the third gate, deploy" data-min="4" data-notes="Stage 7 turns a merged story into a release the Squad Lead can approve on one screen. The release agent drafts every document in the pack — change notes, rollout plan, monitor rules, and test evidence — each generated from a source of truth and assigned a named human checker. The third gate, G3, opens only when all four documents are signed. The agent drafts and re-drafts, but it never signs; a changes-requested on any document bumps the pack version and clears every older signature. The live deploy marker is R-2026-09-02-4423.">
  <span class="lesson-tag">Lesson 15 · Part 4</span>
  <h2>Stage 7 — release, the third gate, deploy</h2>

  <div class="reveal">
    <p>A merged story becomes a release. The release agent drafts the whole pack — <b>it drafts, it never signs</b>.</p>
  </div>

  <div class="reveal">
    <h3>Release pack — four documents, four checkers</h3>
  </div>
  <div class="row">
    <span class="reveal"><span class="card-chip">change-notes · PO</span></span>
    <span class="reveal"><span class="card-chip">rollout-plan · Squad Lead</span></span>
    <span class="reveal"><span class="card-chip">monitor-rules · QA</span></span>
    <span class="reveal"><span class="card-chip">test-evidence · QA</span></span>
  </div>
  <div class="reveal">
    <p class="small">Each is generated from a source of truth and assigned a named human checker.</p>
  </div>

  <div class="reveal">
    <h3>G3 — the third gate</h3>
    <p>Opens only when <b>all four documents are signed</b>. A <code>changes-requested</code> bumps the pack version and clears every older signature.</p>
  </div>

  <div class="reveal">
    <div class="callout ok">
      <b>Live deploy marker.</b> <code>R-2026-09-02-4423</code> — released, signed, shipped.
    </div>
  </div>

  <div id="p4l15-canvas" class="loopcanvas"></div>

  <div class="punch">Agents draft every document. Named humans sign each one.</div>
</section>

<section class="slide" data-lesson="Stage 8 — monitor closes the wheel" data-min="4" data-notes="Stage 8 closes the wheel. Deploy is not the end — the monitor agent keeps watching for the release's watch window, evaluating each monitor rule on its window against a seven-day baseline. When a rule trips, it gathers evidence and files a bug card on the board with the tripped rule id in the title, in state new. That state new card is picked up by the grill agent, and the wheel turns again. The live run tripped export-error-rate — HTTP 5xx rate over 2 percent for 15 minutes, observed at 3.1 percent — and filed a card titled Monitor trip: export-error-rate. That is the whole machine: deploy feeds monitor, monitor feeds the board, the board feeds the grill.">
  <span class="lesson-tag">Lesson 16 · Part 4</span>
  <h2>Stage 8 — monitor closes the wheel</h2>

  <div class="reveal">
    <p>Deploy is not done. The monitor agent keeps watching for the release's whole watch window.</p>
  </div>

  <div class="reveal">
    <h3>A tripped rule becomes evidence on the board</h3>
    <ul>
      <li>Evaluates each rule on its window against a 7-day baseline</li>
      <li>On a trip: gathers the numbers, the window, sample traces</li>
      <li>Files a <b>bug card in state <code>new</code></b> — which the grill agent picks up</li>
    </ul>
  </div>

  <div class="reveal">
    <div class="callout warn">
      <b>Live trip.</b> Rule <code>export-error-rate</code> — <code>http_5xx_rate &gt; 2% over PT15M</code> — observed <b>3.1%</b>. Filed card: <b>Monitor trip: export-error-rate</b>.
    </div>
  </div>

  <div class="reveal">
    <p><b>The wheel closes.</b> deploy → monitor → board → grill → … a filed card sends the loop back through the top.</p>
  </div>

  <div id="p4l16-canvas" class="loopcanvas"></div>

  <div class="punch">Deploy is not done. The monitor files evidence back to the board, and the wheel closes.</div>
</section>
`;

window.PARTS_INIT[4] = function () {
  var l11 = document.getElementById('p4l11-canvas');
  if (l11) LoopCanvas.mount(l11, { stage: 3, ns: 'p4l11' });
  var l12 = document.getElementById('p4l12-canvas');
  if (l12) LoopCanvas.mount(l12, { stage: 4, ns: 'p4l12' });
  var l13 = document.getElementById('p4l13-canvas');
  if (l13) LoopCanvas.mount(l13, { stage: 5, ns: 'p4l13' });
  var l14 = document.getElementById('p4l14-canvas');
  if (l14) LoopCanvas.mount(l14, { stage: 6, ns: 'p4l14' });
  var l15 = document.getElementById('p4l15-canvas');
  if (l15) LoopCanvas.mount(l15, { stage: 7, ns: 'p4l15' });
  var l16 = document.getElementById('p4l16-canvas');
  if (l16) LoopCanvas.mount(l16, { stage: 8, ns: 'p4l16' });
};
