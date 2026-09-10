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
  .p1-primer .rule-card{padding:10px 12px;font-size:13.5px}
  .p1-primer .cols-3{gap:10px}
  .p1-harness .callout{padding:8px 14px;font-size:14px}
  .p1-harness .stack{gap:8px}
  .p1-harness h3{margin:6px 0 6px}
  .p1-nest{border:2px solid var(--loop);border-radius:12px;padding:14px 16px;background:#F0F7FF;font-family:var(--mono);font-size:13px;color:var(--ink)}
  .p1-nest-h{font-weight:700;letter-spacing:.08em;font-size:12px;margin-bottom:8px;color:var(--sc-navy)}
  .p1-nest-flow{display:flex;justify-content:center;gap:8px;margin:4px 0;font-weight:600}
  .p1-nest-state{text-align:center;color:var(--ink-2);font-size:11px;margin-bottom:12px;white-space:pre}
  .p1-nest-in{border:2px solid var(--pass);border-radius:10px;padding:12px 14px;background:#F2FBF6}
  .p1-nest-model{border:2px solid var(--sc-navy);border-radius:8px;padding:10px;text-align:center;font-weight:700;background:#fff;margin-top:10px;letter-spacing:.08em}
  .p1-hbuild{border:2px solid var(--pass);border-radius:10px;padding:14px 16px;background:#F2FBF6}
  .p1-hbuild-cap{font-family:var(--mono);font-size:11px;font-weight:700;letter-spacing:.08em;color:var(--sc-navy);margin-bottom:8px;text-align:center}
  .p1-hbuild-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-bottom:10px}
  .p1-hbuild-slot{border-radius:8px;padding:10px 4px;text-align:center;font-family:var(--mono);font-size:12px;font-weight:600;border:2px dashed var(--line);color:var(--ink-2);background:#fff;transition:all .3s ease}
  .p1-hbuild-slot.done{border:2px solid var(--pass);background:#E3F7EC;color:var(--ink)}
  .p1-hbuild-slot.active{border:2px solid var(--loop);background:#E3F1FF;color:var(--sc-navy);box-shadow:0 0 0 3px rgba(4,115,234,.15)}
  .p1-hbuild-model{border:2px solid var(--sc-navy);border-radius:8px;padding:8px;text-align:center;font-weight:700;background:#fff;letter-spacing:.06em;font-size:12px;font-family:var(--mono)}
</style>

<section class="slide" data-lesson="Title" data-min="0" data-notes="Seventy-six minutes and one lens. Working with agents has moved outward four times: from the words you type, to the context the model sees, to the harness one run lives in, to the loop that repeats, checks, retries and stops that run without you on every turn. We start with one customer placing one customized order and never leave it. First the order becomes context: a spec. Then the smallest loop builds from it. Then every stage of a real delivery lifecycle is added, and at each one we name which of the four loop decisions it enforces and what the human signs. By the end the whole SDLC is one engineered loop on screen.">
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

<section class="slide" data-lesson="Why · the clock" data-min="2" data-notes="Honest answer first: we do not have this number, and almost nobody does. DORA measures commit to production and puts elite teams under a day, low performers at one to six months; but that clock starts at commit, after the requirement has already been written, argued, re-written and scheduled. The flow-efficiency research says active work is fifteen to forty percent of elapsed time; the rest is waiting between hand-offs and approvals. Ask the room for their own number. Kitchen: twelve minutes of cooking, forty minutes to the door.">
  <div class="lesson-tag">Why 1/3</div>
  <h2>Do we have data on how long it takes from requirement to release?</h2>
  <div class="two">
    <div class="panel">
      <h3>What the industry measures</h3>
      <table class="tbl">
        <tr><th>Performer</th><th>Lead time for changes (commit → production)</th></tr>
        <tr><td>Elite</td><td>under a day</td></tr>
        <tr><td>High</td><td>a day to a week</td></tr>
        <tr><td>Medium</td><td>a week to a month</td></tr>
        <tr><td>Low</td><td>a month to six months</td></tr>
      </table>
      <p class="small">DORA, State of DevOps 2024. The clock starts at commit. Requirement → commit is not in it.</p>
    </div>
    <div class="panel">
      <h3>What almost nobody measures</h3>
      <div class="stack">
        <div class="reveal callout"><b>Flow efficiency</b> — active work is typically 15–40% of the elapsed time from idea to production. The other 60–85% is waiting: queues, hand-offs, approvals.</div>
        <div class="reveal callout warn">Ask the room: for the last feature you shipped, how many days from the request being written to it being live? How many of those days was anyone actually working on it?</div>
        <div class="reveal callout">Order placed at seven. Twelve minutes of cooking. Delivered at 7:40. The other twenty-eight minutes the ticket sat on the rail.</div>
      </div>
    </div>
  </div>
  <p class="small">Sources: dora.dev (State of DevOps 2024); Flow Framework flow-efficiency benchmarks (Kersten, Project to Product).</p>
  <div class="punch">We measure the cooking. Nobody measures the wait.</div>
</section>

<section class="slide" data-lesson="Why · 10× with the same control" data-min="2" data-notes="The claim is not fewer checks. It is the same three gates, the same named roles, the same audit trail, with the waiting between them removed because agents do the hand-offs and code does the checking. In the live run Priya's export went from card to table in a day. Governance: three gates. Control: touch lists and tests per task, enforced by code. Security: isolated worktrees, hard caps, human sign-off before anything moves. Kitchen: the same head chef still reads every ticket; the ticket just stops sitting on the rail.">
  <div class="lesson-tag">Why 2/3</div>
  <h2>What if it were 10× faster — with the same governance, control and security?</h2>
  <div class="two">
    <div class="panel">
      <h3>10× on the clock</h3>
      <div class="ladder">
        <div class="reveal rung"><b>Today</b> — the wait between hand-offs is the lead time. Weeks for a feature that took days of work.</div>
        <div class="reveal rung"><b>Priya's export, live run</b> — card filed in the morning, on the table the same day, monitor still watching it.</div>
        <div class="reveal rung"><b>Where the 10× comes from</b> — removing the wait, not removing the checks. Agents do the hand-offs; people still sign.</div>
      </div>
    </div>
    <div class="panel">
      <h3>Not less control — the same control, enforced</h3>
      <div class="stack">
        <div class="reveal callout"><b>Governance</b> — three human gates: G1 the spec, G2 the pull request, G3 the release. Named roles, independent signatures, every signature reset when the version changes.</div>
        <div class="reveal callout"><b>Control</b> — every task carries the files it may touch and the test that proves it. An edit outside that list is reverted and escalated by code, not by policy.</div>
        <div class="reveal callout"><b>Security</b> — each run is isolated in its own worktree, capped on steps, time and tokens; nothing merges or deploys without a person's name on it.</div>
        <div class="reveal callout warn">The speed comes from removing the wait, not from removing the checks.</div>
      </div>
    </div>
  </div>
  <div class="punch">10× the throughput, the same number of signatures — every one of them still human.</div>
</section>

<section class="slide" data-lesson="Why · change the process" data-min="2" data-notes="Four reasons the process itself has to change, not just the tooling. The bottleneck moved from typing to understanding. The wait, not the work, is the lead time. Dropping AI into the old process makes the queue longer, and DORA measured that. And production never talks back to the backlog on its own. Every stage from Course 9 onward answers one of these four.">
  <div class="lesson-tag">Why 3/3</div>
  <h2>Why should the current process change?</h2>
  <div class="cols-2">
    <div class="reveal"><div class="rule-card"><b>The bottleneck moved</b><br>Everyone has an AI that writes code. Code is now cheap; an unclear requirement is expensive. A faster typist for an unclear request ships the wrong thing faster.</div></div>
    <div class="reveal"><div class="rule-card"><b>The wait is the process</b><br>60–85% of elapsed time is hand-offs and queues. The steps are fine; the gaps between them are the cost, and adding a faster step does not shrink a gap.</div></div>
    <div class="reveal"><div class="rule-card"><b>AI without a loop makes it worse</b><br>DORA 2024: AI adoption correlated with 1.5% lower throughput and 7.2% lower delivery stability. More code, the same review capacity, the same queue.</div></div>
    <div class="reveal"><div class="rule-card"><b>Production feedback never comes back</b><br>An incident becomes a postmortem becomes, weeks later, maybe a card. The team that shipped it has moved on.</div></div>
  </div>
  <div class="reveal callout warn">The cooks got faster. The order rail, the expeditor and the delivery bag did not. The ticket still arrives at 7:40 with onions.</div>
  <div class="punch">Don't add an AI to the process. Rebuild the process as a loop with the AI inside it.</div>
</section>

<section class="slide" data-lesson="Menu" data-min="2" data-notes="An opener, then four acts. First the lens: the four-layer map from prompt to loop. Second context: spec-driven development — intent you can check, the change folder as the workflow, and the workflow defined once in files and shared. Third harness: the smallest loop that works, the equipment under it, and the loop contract that names what every later stage enforces. Fourth the loop at lifecycle scale: every stage tagged with the decisions it enforces, one order all the way round, and the question to take home. Course 16 and Course 17 are the ones we drop if we are short.">
  <style>#p1-agenda{grid-template-columns:repeat(5,1fr);gap:5px}#p1-agenda .card{padding:5px 7px}#p1-agenda .card b{font-size:12px;line-height:1.15}#p1-agenda .card .t{font-size:10px;line-height:1.15}</style>
  <div class="lesson-tag">Menu</div>
  <h2>Eighteen courses, one order, one growing picture</h2>
  <div id="p1-agenda" class="agenda"></div>
  <p style="margin-top:10px;">Total <span id="p1-agenda-total"></span>. Before the courses: three reasons to change the process, a one-slide model primer, and the eight parts of a harness. Then four acts: the lens (Course 1), context — the order as a spec (2–5), harness and the loop contract (6–8), the loop at SDLC scale and the close (9–18). If we run short we skip Course 16 and Course 17 — never Course 11 or Course 15.</p>
</section>

<section class="slide" data-lesson="The model, in one slide" data-min="2" data-notes="Six words, then we never define them again. A model predicts the next token. Tokens are what you pay for and wait for. The context window is everything it can see in one call; nothing outside it exists. Calls are stateless; memory is text the harness puts back. A tool call is a structured request the harness executes. And when context is missing the model fills the gap plausibly, which is why the answer is never a better prompt alone: it is putting the facts in the window and checking the output outside the model.">
  <div class="p1-primer">
  <div class="lesson-tag">Primer</div>
  <h2>The model, in one slide</h2>
  <div class="cols-3">
    <div class="reveal"><div class="rule-card"><b>Language model</b><br>Predicts the next token given everything before it. Nothing more, nothing less.</div></div>
    <div class="reveal"><div class="rule-card"><b>Token</b><br>The unit it reads and writes, about three-quarters of a word. Cost and latency scale with tokens in and tokens out.</div></div>
    <div class="reveal"><div class="rule-card"><b>Context window</b><br>The maximum tokens one call can see: instructions, history, documents, tool output and the answer itself. Outside the window, a fact does not exist.</div></div>
    <div class="reveal"><div class="rule-card"><b>Stateless</b><br>Every call starts blank. Anything that looks like memory is the harness putting text back into the window.</div></div>
    <div class="reveal"><div class="rule-card"><b>Tool call</b><br>The model emits a structured request — read this file, run these tests. The harness executes it and puts the result in the window. The model never touches the disk.</div></div>
    <div class="reveal"><div class="rule-card"><b>Gap-filling</b><br>Missing context gets filled in plausibly. The fix is not a stricter prompt: put the facts in the window, and check the output outside the model.</div></div>
  </div>
  <div class="reveal callout">A brilliant cook who remembers nothing between orders and knows only what is on the ticket in front of them. The ticket is the context window.</div>
  <div class="punch">Everything today is about what goes into the window, and what checks what comes out.</div>
  </div>
</section>

<section class="slide" data-lesson="Harness · prompt" data-min="1" data-notes="Layer one. A prompt is the instruction for one call. Its ceiling is hard: words cannot supply facts the model never saw. Without one, the model has to guess the task, the constraints and the shape of a good answer — and guesses are inconsistent.">
  <div class="p1-harness">
  <div class="lesson-tag">Harness 1/8</div>
  <h2>Prompt — the words you send</h2>
  <div class="two">
    <div class="panel">
      <h3>What it is</h3>
      <div class="stack">
        <div class="reveal callout">The instruction for one call: role, task, constraints, and the shape of the answer you want back.</div>
        <div class="reveal callout">The ceiling: a perfect sentence cannot supply a fact the model never saw.</div>
        <div class="reveal callout ok"><b>Why you need it</b> — without one, the model has to guess the task, the constraints and the shape of a good answer, and guesses are inconsistent.</div>
        <div class="reveal callout warn">The ticket wording. 'No onions' written on the slip, not shouted across the pass.</div>
      </div>
    </div>
    <div>
      <div class="p1-hbuild">
        <div class="p1-hbuild-cap">Building the harness — part 1 of 8</div>
        <div class="p1-hbuild-grid">
          <div class="p1-hbuild-slot active reveal">Prompt</div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
        </div>
        <div class="p1-hbuild-model">MODEL</div>
      </div>
    </div>
  </div>
  <div class="punch">Prompt engineering is table stakes — where the ladder starts, not where it ends.</div>
  </div>
</section>

<section class="slide" data-lesson="Harness · context engineering" data-min="1" data-notes="Layer two. Context is everything in the window, and engineering it means choosing what goes in and in what form. A model with the right instructions but the wrong facts still gives a wrong answer; context is what makes an answer specific to your situation, not a generic one.">
  <div class="p1-harness">
  <div class="lesson-tag">Harness 2/8</div>
  <h2>Context engineering — everything the model sees</h2>
  <div class="two">
    <div class="panel">
      <h3>What it is</h3>
      <div class="stack">
        <div class="reveal callout">The whole window: instructions, history, documents, tool output, state. Choosing what goes in, in what form, and what stays out.</div>
        <div class="reveal callout">More is not better. Irrelevant context dilutes the relevant; the craft is retrieval and summarisation, not stuffing.</div>
        <div class="reveal callout ok"><b>Why you need it</b> — a model with the right instructions but the wrong facts still gives a wrong answer. Context is what makes it specific to your situation.</div>
        <div class="reveal callout warn">The printed order slip with the allergen card clipped to it. The cook reads the slip, not the customer's mind.</div>
      </div>
    </div>
    <div>
      <div class="p1-hbuild">
        <div class="p1-hbuild-cap">Building the harness — part 2 of 8</div>
        <div class="p1-hbuild-grid">
          <div class="p1-hbuild-slot done">Prompt</div>
          <div class="p1-hbuild-slot active reveal">Context</div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
        </div>
        <div class="p1-hbuild-model">MODEL</div>
      </div>
    </div>
  </div>
  <div class="punch">The model can only be as right as its window.</div>
  </div>
</section>

<section class="slide" data-lesson="Harness · skills" data-min="1" data-notes="Skills are packaged procedure: how this team does one kind of job, versioned and reviewed like code, loaded only when a task matches. Repeating the same specialist know-how in every prompt bloats it and drifts as the procedure changes; a skill keeps one copy, loaded on demand.">
  <div class="p1-harness">
  <div class="lesson-tag">Harness 3/8</div>
  <h2>Skills — packaged know-how, loaded on demand</h2>
  <div class="two">
    <div class="panel">
      <h3>What it is</h3>
      <div class="stack">
        <div class="reveal callout">A reusable instruction file: a procedure, examples, checks — loaded into the window only when the task needs it.</div>
        <div class="reveal callout">Versioned in the repo and reviewed like code. The base prompt stays short; the same model becomes a specialist per job.</div>
        <div class="reveal callout ok"><b>Why you need it</b> — repeating the same specialist know-how in every prompt bloats it and drifts as the procedure changes. A skill keeps one copy, loaded on demand.</div>
        <div class="reveal callout warn">The recipe card for one dish, pulled from the binder when that dish is ordered. Not memorised, not on every ticket.</div>
      </div>
    </div>
    <div>
      <div class="p1-hbuild">
        <div class="p1-hbuild-cap">Building the harness — part 3 of 8</div>
        <div class="p1-hbuild-grid">
          <div class="p1-hbuild-slot done">Prompt</div>
          <div class="p1-hbuild-slot done">Context</div>
          <div class="p1-hbuild-slot active reveal">Skills</div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
        </div>
        <div class="p1-hbuild-model">MODEL</div>
      </div>
    </div>
  </div>
  <div class="punch">Skills are how one model becomes eight specialists.</div>
  </div>
</section>

<section class="slide" data-lesson="Harness · tools" data-min="1" data-notes="Tools are how the model acts, and therefore where control lives. A model that can only talk can't read a file, run a test or touch a real system. The tool list is also the permission list — you restrict a model with the tools you hand it, not with words.">
  <div class="p1-harness">
  <div class="lesson-tag">Harness 4/8</div>
  <h2>Tools — how the model touches the world</h2>
  <div class="two">
    <div class="panel">
      <h3>What it is</h3>
      <div class="stack">
        <div class="reveal callout">The model emits a structured call; the harness runs it and returns the result. Read, edit, run, search, query — each a typed contract with its own permission.</div>
        <div class="reveal callout">Typed outputs, not free text: a tool that returns a record can be checked; a paragraph cannot.</div>
        <div class="reveal callout ok"><b>Why you need it</b> — a model that can only talk can't read a file, run a test or touch a real system. Tools are how it acts, and the only way to restrict what it may do.</div>
        <div class="reveal callout warn">The knives, the oven, the thermometer. The cook uses them; the kitchen owns them and decides who gets which.</div>
      </div>
    </div>
    <div>
      <div class="p1-hbuild">
        <div class="p1-hbuild-cap">Building the harness — part 4 of 8</div>
        <div class="p1-hbuild-grid">
          <div class="p1-hbuild-slot done">Prompt</div>
          <div class="p1-hbuild-slot done">Context</div>
          <div class="p1-hbuild-slot done">Skills</div>
          <div class="p1-hbuild-slot active reveal">Tools</div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
        </div>
        <div class="p1-hbuild-model">MODEL</div>
      </div>
    </div>
  </div>
  <div class="punch">You don't restrict a model with words. You restrict it with the tools you hand it.</div>
  </div>
</section>

<section class="slide" data-lesson="Harness · memory" data-min="1" data-notes="Memory is not a model feature; it is a harness feature. A stateless model repeats the same mistake forever unless something outside it writes the lesson down and hands it back next time. Short-term is the transcript; long-term is files, the board, the database.">
  <div class="p1-harness">
  <div class="lesson-tag">Harness 5/8</div>
  <h2>Memory — what survives between calls</h2>
  <div class="two">
    <div class="panel">
      <h3>What it is</h3>
      <div class="stack">
        <div class="reveal callout">The model forgets everything after each call. Memory is what the harness writes down and re-injects: conventions, decisions, past failures.</div>
        <div class="reveal callout">Short-term: the session transcript. Long-term: files in the repo, the board, the database.</div>
        <div class="reveal callout ok"><b>Why you need it</b> — a stateless model repeats the same mistake forever unless something outside it writes the lesson down and hands it back next time.</div>
        <div class="reveal callout warn">The house book: the regulars' allergies, what went wrong last Friday. Written down, not in the cook's head.</div>
      </div>
    </div>
    <div>
      <div class="p1-hbuild">
        <div class="p1-hbuild-cap">Building the harness — part 5 of 8</div>
        <div class="p1-hbuild-grid">
          <div class="p1-hbuild-slot done">Prompt</div>
          <div class="p1-hbuild-slot done">Context</div>
          <div class="p1-hbuild-slot done">Skills</div>
          <div class="p1-hbuild-slot done">Tools</div>
          <div class="p1-hbuild-slot active reveal">Memory</div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
        </div>
        <div class="p1-hbuild-model">MODEL</div>
      </div>
    </div>
  </div>
  <div class="punch">If it isn't written down, the next run doesn't know it happened.</div>
  </div>
</section>

<section class="slide" data-lesson="Harness · validation" data-min="1" data-notes="Validation is the deterministic piece that makes every loop work. Nothing stops a model from confidently describing broken work as done; something outside it — tests, types, a diff that stays in scope — has to say yes before anyone trusts the output. Every check doubles as the retry signal.">
  <div class="p1-harness">
  <div class="lesson-tag">Harness 6/8</div>
  <h2>Validation — something outside the model says yes</h2>
  <div class="two">
    <div class="panel">
      <h3>What it is</h3>
      <div class="stack">
        <div class="reveal callout">A model grading its own work is not a verifier. Validation is a deterministic check it cannot talk its way past: tests, types, build, a diff that stays in scope.</div>
        <div class="reveal callout">Every check is also a retry signal. Red goes back into the window with the failure attached; green moves forward.</div>
        <div class="reveal callout ok"><b>Why you need it</b> — nothing stops a model from confidently describing broken work as done. Something outside it has to say yes before anyone trusts the output.</div>
        <div class="reveal callout warn">The thermometer. No opinion, same reading every time.</div>
      </div>
    </div>
    <div>
      <div class="p1-hbuild">
        <div class="p1-hbuild-cap">Building the harness — part 6 of 8</div>
        <div class="p1-hbuild-grid">
          <div class="p1-hbuild-slot done">Prompt</div>
          <div class="p1-hbuild-slot done">Context</div>
          <div class="p1-hbuild-slot done">Skills</div>
          <div class="p1-hbuild-slot done">Tools</div>
          <div class="p1-hbuild-slot done">Memory</div>
          <div class="p1-hbuild-slot active reveal">Validation</div>
          <div class="p1-hbuild-slot"></div>
          <div class="p1-hbuild-slot"></div>
        </div>
        <div class="p1-hbuild-model">MODEL</div>
      </div>
    </div>
  </div>
  <div class="punch">Trust the check, not the cook.</div>
  </div>
</section>

<section class="slide" data-lesson="Harness · worktree" data-min="1" data-notes="A worktree is the sandbox. An agent working directly on shared state can corrupt another run or the main branch; isolation is what makes letting it run unattended safe. Cheap to discard, invisible until pushed.">
  <div class="p1-harness">
  <div class="lesson-tag">Harness 7/8</div>
  <h2>Worktree — an isolated place to make a mess</h2>
  <div class="two">
    <div class="panel">
      <h3>What it is</h3>
      <div class="stack">
        <div class="reveal callout">Each run gets its own git worktree: same repository, its own branch, its own working directory.</div>
        <div class="reveal callout">Failure is cheap: throw the worktree away. Parallel runs cannot trample each other.</div>
        <div class="reveal callout ok"><b>Why you need it</b> — an agent working directly on shared state can corrupt another run or the main branch. Isolation is what makes letting it run unattended safe.</div>
        <div class="reveal callout warn">One station per cook: your board, your pans. Nobody plates on someone else's counter.</div>
      </div>
    </div>
    <div>
      <div class="p1-hbuild">
        <div class="p1-hbuild-cap">Building the harness — part 7 of 8</div>
        <div class="p1-hbuild-grid">
          <div class="p1-hbuild-slot done">Prompt</div>
          <div class="p1-hbuild-slot done">Context</div>
          <div class="p1-hbuild-slot done">Skills</div>
          <div class="p1-hbuild-slot done">Tools</div>
          <div class="p1-hbuild-slot done">Memory</div>
          <div class="p1-hbuild-slot done">Validation</div>
          <div class="p1-hbuild-slot active reveal">Worktree</div>
          <div class="p1-hbuild-slot"></div>
        </div>
        <div class="p1-hbuild-model">MODEL</div>
      </div>
    </div>
  </div>
  <div class="punch">Isolation is what makes autonomy safe enough to allow.</div>
  </div>
</section>

<section class="slide" data-lesson="Harness · orchestration" data-min="1" data-notes="Orchestration is who calls whom and what waits: sequencing, fan-out, hand-offs, durable waiting on humans. Without something to sequence steps, retry failures and wait on a human signature, every run needs a person babysitting it end to end. It is the visible half of a loop; the decisive half is the contract in Course 8.">
  <div class="p1-harness">
  <div class="lesson-tag">Harness 8/8</div>
  <h2>Orchestration — who calls whom, and what waits</h2>
  <div class="two">
    <div class="panel">
      <h3>What it is</h3>
      <div class="stack">
        <div class="reveal callout">Sequencing calls, fanning out to sub-agents, handing off between specialists, waiting on people and on external systems.</div>
        <div class="reveal callout">Durable: survives a crash, waits days for a signature, resumes exactly where it stopped.</div>
        <div class="reveal callout ok"><b>Why you need it</b> — without something to sequence steps, retry failures and wait on a human signature, every run needs a person babysitting it end to end.</div>
        <div class="reveal callout warn">The expeditor: calls the courses in order, holds the plate until the table is ready, never cooks.</div>
      </div>
    </div>
    <div>
      <div class="p1-hbuild">
        <div class="p1-hbuild-cap">Building the harness — part 8 of 8</div>
        <div class="p1-hbuild-grid">
          <div class="p1-hbuild-slot done">Prompt</div>
          <div class="p1-hbuild-slot done">Context</div>
          <div class="p1-hbuild-slot done">Skills</div>
          <div class="p1-hbuild-slot done">Tools</div>
          <div class="p1-hbuild-slot done">Memory</div>
          <div class="p1-hbuild-slot done">Validation</div>
          <div class="p1-hbuild-slot done">Worktree</div>
          <div class="p1-hbuild-slot active reveal">Orchestration</div>
        </div>
        <div class="p1-hbuild-model">MODEL</div>
      </div>
    </div>
  </div>
  <div class="punch">Orchestration moves the work. The loop contract decides whether it may move.</div>
  </div>
</section>

<section class="slide" data-lesson="From harness to loop" data-min="3" data-notes="The picture the rest of the hour builds on. Innermost, the model: it predicts tokens and nothing else. Around it the harness: the eight parts we just walked through, which make one run reliable. Around the harness the loop: trigger, select, run, verify, decide, with state carried between passes, which makes runs repeat, check, retry and stop without a human on every turn. By hand, the human is the loop. Engineered, a system runs the loop and the human designs it and signs at the gates. Course 6 shows the smallest one; Course 8 names its contract; from Course 9 every SDLC stage is one of these.">
  <div class="lesson-tag">Course 1</div>
  <h2>From harness to loop</h2>
  <div class="two">
    <div>
      <div class="p1-nest">
        <div class="p1-nest-h">LOOP ENGINEERING</div>
        <div class="p1-nest-flow"><span>Trigger</span><span>→</span><span>Select</span><span>→</span><span>Run</span><span>→</span><span>Verify</span><span>→</span><span>Decide</span></div>
        <div class="p1-nest-state">↑ ──────── State ──────── ┘</div>
        <div class="p1-nest-in">
          <div class="p1-nest-h">HARNESS ENGINEERING</div>
          <div class="p1-hbuild-grid">
            <div class="p1-hbuild-slot done">Prompt</div>
            <div class="p1-hbuild-slot done">Context</div>
            <div class="p1-hbuild-slot done">Skills</div>
            <div class="p1-hbuild-slot done">Tools</div>
            <div class="p1-hbuild-slot done">Memory</div>
            <div class="p1-hbuild-slot done">Validation</div>
            <div class="p1-hbuild-slot done">Worktree</div>
            <div class="p1-hbuild-slot done">Orchestration</div>
          </div>
          <div class="p1-nest-model">MODEL</div>
        </div>
      </div>
    </div>
    <div class="stack">
      <div class="reveal callout"><b>The eight parts you just saw are the harness box.</b> Prompt, context, skills, memory: what the model sees. Tools, worktree: what it may touch. Validation: what checks it. Orchestration: who calls it.</div>
      <div class="reveal callout"><b>Around the harness sits the loop.</b> Trigger → Select → Run → Verify → Decide, with State carried into the next pass. A harness makes one run reliable; a loop makes the run repeat, check, retry and stop without you on every turn.</div>
      <div class="reveal callout warn">By hand: prompt → inspect → correct → prompt again. The human is the loop.</div>
      <div class="reveal callout">Engineered: a system runs the loop; the human designs it and signs at the gates.</div>
      <p class="small">After Addy Osmani, 'Loop Engineering' (2026); tosea.ai; The AI Runtime.</p>
    </div>
  </div>
  <div class="punch">Stop writing the perfect prompt. Design the loop that writes it.</div>
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
