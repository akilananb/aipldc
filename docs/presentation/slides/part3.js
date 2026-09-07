/* Part 3 — Writer-Tools: L7 (omp + Orca), L8 (ACP), L9 (Embabel), L10 (Temporal).
 * Registers window.PARTS[3] (template-literal HTML) and window.PARTS_INIT[3] (canvas wiring).
 * Every id is prefixed p3-. LoopCanvas is Director-owned — mount only, never edit loop.js. */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[3] = `
<style>
  .p3-handshake{display:flex;align-items:stretch;gap:14px;flex-wrap:wrap;max-width:76ch;margin:14px 0}
  .p3-box{flex:1 1 200px;border:1px solid var(--line);border-radius:8px;background:var(--paper);padding:12px 14px}
  .p3-box .who{font-family:var(--mono);font-size:12px;color:var(--ink-2);text-transform:uppercase;letter-spacing:.04em;margin-bottom:8px}
  .p3-box b{display:block;font-size:17px;margin-bottom:4px}
  .p3-box .sub{font-size:13.5px;color:var(--ink-2)}
  .p3-wire{flex:0 0 auto;display:flex;flex-direction:column;justify-content:center;gap:4px;font-family:var(--mono);font-size:11.5px;color:var(--ink-2);text-align:center;padding:0 4px}
  .p3-wire .bar{width:34px;height:2px;background:var(--loop);margin:0 auto;position:relative}
  .p3-wire .bar::after{content:"";position:absolute;right:-1px;top:-4px;border:5px solid transparent;border-left:8px solid var(--loop)}
  .p3-band{display:flex;align-items:center;gap:0;flex-wrap:wrap;margin:14px 0}
  .p3-band .seg{border:1px solid var(--line);background:var(--paper);border-radius:8px;padding:10px 14px;font-size:14px;white-space:nowrap}
  .p3-band .gate{margin:0 6px;font-family:var(--mono);font-size:11px;color:var(--stop);border:1px dashed var(--stop);border-radius:999px;padding:2px 8px;white-space:nowrap}
  .p3-band .arrow{font-family:var(--mono);color:var(--ink-2);padding:0 5px}
  .p3-q{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}
  .p3-q .q{font-family:var(--mono);font-size:13px;border:1px solid var(--line);border-radius:6px;background:var(--paper);padding:6px 12px}
  .p3-q .q b{color:var(--loop)}
  .p3-cast{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0 2px}
  .p3-caption{font-size:12.5px;color:var(--ink-2);font-family:var(--mono);margin-top:6px}
  .p3-thumb{margin-top:16px}
</style>

<section class="slide" data-lesson="omp + Orca — the coding agent" data-min="4" data-notes="Two tools sit in the coding loop: omp is the agent, Orca is where you stand. omp does the deterministic work — LSP and DAP context, hashline-precise edits, a session you can keep open, subagents spun up in their own worktrees. It also exposes an ACP mode, so a machine can drive that same session over a protocol instead of a keyboard. Orca is simply the terminal the engineer drives omp from. Same agent, same session — driven by hand or by protocol.">
  <span class="lesson-tag">Lesson 7</span>
  <h2>omp + Orca — the coding agent</h2>

  <div class="two">
    <div class="panel">
      <h3>omp — the agent</h3>
      <ul>
        <li><b>LSP / DAP</b> — real code + debugger context, not grep</li>
        <li><b>hashline edits</b> — deterministic, reviewable file changes</li>
        <li><b>sessions</b> — a long-lived loop you can keep driving</li>
        <li><b>subagents</b> — parallel work, isolated in their own worktrees</li>
        <li><code class="mono">omp --mode acp</code> — the same session, driven by protocol</li>
      </ul>
      <p class="small">Decision record: omp chosen for LSP/DAP/hashline, ACP + RPC + SDK surfaces, subagents in worktrees.</p>
    </div>
    <div class="panel">
      <h3>Orca — the terminal</h3>
      <p>The terminal the engineer drives omp from.</p>
    </div>
  </div>

  <div class="p3-thumb">
    <div id="p3l7-canvas" class="loopcanvas small"></div>
    <div class="p3-caption">this box — the coding agent, mid-loop</div>
  </div>

  <div class="punch">The coding agent is a session you can drive — by hand from a terminal, or by protocol from a machine.</div>
</section>

<section class="slide" data-lesson="ACP — the wire between them" data-min="4" data-notes="ACP is the wire that connects a client to a session-based agent. The client opens a session, sends prompts, and receives tool calls and results back over one of two transports — stdio inside a sandbox, or HTTP/WS to a long-running service. The build worker talks to omp this way. So does the FS Developer's desktop, and so does an editor. But Embabel's reasoning agents are not sessions, so they are reached over MCP instead — ACP is the wrong wire for them.">
  <span class="lesson-tag">Lesson 8</span>
  <h2>ACP — the wire between them</h2>

  <div class="p3-handshake">
    <div class="p3-box reveal">
      <div class="who">client</div>
      <b>build worker</b>
      <div class="sub">opens the session, sends the prompt, enforces scope + budget</div>
    </div>
    <div class="p3-wire reveal">
      <span>prompt · tool calls</span>
      <div class="bar"></div>
      <span>results</span>
    </div>
    <div class="p3-box reveal">
      <div class="who">agent</div>
      <b><code class="mono">omp --mode acp</code></b>
      <div class="sub">the session being driven — over stdio or HTTP/WS</div>
    </div>
  </div>

  <table class="tbl">
    <tr><th>Client</th><th>Agent</th><th>Transport</th></tr>
    <tr><td>build worker</td><td class="mono">omp --mode acp</td><td>stdio (container exec)</td></tr>
    <tr><td>goose desktop</td><td class="mono">omp</td><td>stdio</td></tr>
    <tr><td>editor (Zed / JetBrains)</td><td class="mono">omp / goose</td><td>stdio</td></tr>
    <tr><td>Embabel / workflow</td><td class="mono">goose serve</td><td>ACP over HTTP/WS</td></tr>
    <tr><td colspan="3" class="small">Embabel reasoning agents are reached over MCP, never ACP — ACP drives a <i>session-based</i> agent, and they are not one.</td></tr>
  </table>

  <div class="p3-thumb">
    <div id="p3l8-canvas" class="loopcanvas small"></div>
    <div class="p3-caption">this wire — engineer → agent, the ACP link</div>
  </div>

  <div class="punch">ACP is for driving a session-based agent. If there's no session, it's the wrong wire.</div>
</section>

<section class="slide" data-lesson="Embabel — typed reasoning" data-min="4" data-notes="Every reasoning agent in the top row — grill, PO, plan, review, release, monitor — runs on Embabel. It gives each agent an injectable Ai bean and routes it to a per-role model straight out of the config. Handoffs between agents are typed records, not free text. The INVEST and definition-of-ready checks are conditions the planner must satisfy before it reaches its goal. When a checker adds a comment, that is a new world state and the planner replans — no new prompt engineering.">
  <span class="lesson-tag">Lesson 9</span>
  <h2>Embabel — typed reasoning</h2>

  <div class="panel">
    <div class="reveal">Inject <code class="mono">Ai</code> bean — one interface, no per-agent plumbing.</div>
    <div class="reveal">Per-role model routing — <code class="mono">agents.roles.&lt;role&gt;.model</code> in <code class="mono">pdlc.yaml</code>.</div>
    <div class="reveal">Typed handoffs — <code class="mono">PoHandoff</code> / <code class="mono">PlanHandoff</code>-style envelopes, not prose.</div>
    <div class="reveal">INVEST checks as <code class="mono">@Condition</code>s — the planner can't publish until they hold.</div>
    <div class="reveal">GOAP replanning — new checker comments are new world state; the plan replans. No new prompt engineering.</div>
  </div>

  <p class="small">Every box we add to the top row of the canvas will be one of these.</p>

  <div class="punch">Free-text output is a prototype. A typed contract is a system.</div>
</section>

<section class="slide" data-lesson="Temporal — the durable outer loop" data-min="3" data-notes="Temporal is the durable outer loop that holds each piece of work from intake to done. The workflow calls the reasoning agents, then waits at a gate — sometimes for days — until the right humans sign. Meanwhile the build loop runs inside a long activity with heartbeats. The queues are split for a real reason: two workers on one queue each hold half the activities, and Temporal dispatches to whichever one is polling, so the wrong worker rejects the task and retries with exponential backoff. Splitting the queue makes each worker the only poller for its own activities. Postgres, the Temporal UI, and a WireMock stub LLM all ride under it.">
  <span class="lesson-tag">Lesson 10</span>
  <h2>Temporal — the durable outer loop</h2>

  <div class="p3-band">
    <div class="seg reveal">grill</div>
    <div class="arrow reveal">→</div>
    <div class="seg reveal">story + spec</div>
    <div class="gate reveal">G1 · wait on humans</div>
    <div class="seg reveal">plan</div>
    <div class="gate reveal">build · heartbeats</div>
    <div class="seg reveal">review</div>
    <div class="gate reveal">G2 · wait on humans</div>
    <div class="seg reveal">release</div>
    <div class="gate reveal">G3 · wait on humans</div>
    <div class="seg reveal">deploy → monitor</div>
  </div>

  <div class="p3-q">
    <div class="q reveal"><b>REASONING</b> — Embabel agents</div>
    <div class="q reveal"><b>BOARD</b> — control-plane side effects</div>
    <div class="q reveal"><b>BUILD</b> — build worker (spawns omp)</div>
  </div>
  <p class="small">Queue split: one worker per queue avoids cross-activity-set "not registered" errors — and the exponential-backoff retry lottery that comes with them.</p>

  <div class="p3-cast">
    <span class="card-chip reveal">Postgres</span>
    <span class="card-chip reveal">Temporal UI</span>
    <span class="card-chip reveal">WireMock stub-llm</span>
    <span class="card-chip reveal">docker-compose</span>
  </div>

  <p class="small">Temporal is the table the whole canvas sits on.</p>

  <div class="punch">Waiting for humans across days, exactly-once progression, auditability — the engine's native features, not code you maintain.</div>
</section>
`;

window.PARTS_INIT[3] = function () {
  var l7 = document.getElementById('p3l7-canvas');
  if (l7) {
    LoopCanvas.mountFlow(l7, { stage: 2, ns: 'p3l7' });
    var agent = document.getElementById('p3l7-n-agent');
    if (agent) agent.classList.add('hl');
  }
  var l8 = document.getElementById('p3l8-canvas');
  if (l8) {
    LoopCanvas.mountFlow(l8, { stage: 2, ns: 'p3l8' });
    var wire = document.getElementById('p3l8-e-engineer-agent');
    if (wire) wire.classList.add('hl');
  }
};
