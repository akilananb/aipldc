/* part5.js — Writer-Flow: L17 (THE FLOW — run the machine), L18 (kanban view — work as state). */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[5] = `
<section class="slide" id="p5l17" data-lesson="THE FLOW — run the machine" data-min="8" data-notes="This is the centerpiece: the finished stage-8 machine running end to end, one token. Watch the wheel turn once. The card starts at the board, gets grilled into a story plus spec, and clears G1 into the plan and queue. The token enters an agent lane, fails the verifier once, loops back, then passes and flows review, G2, release, G3, deploy, monitor, and a trip card that closes the wheel back at the board. Press play; pause and reset are there so you can freeze and re-walk any leg. At 2x the dwell per node drops from 700ms to 350ms. The loop is only one mechanism inside the machine.">
  <style>
    #p5l17.active{padding:24px 24px 0;display:flex;flex-direction:column;gap:14px}
    #p5l17 .p5l17-head{padding:0 8px}
    #p5l17 .p5l17-head h2{margin:0 0 4px}
    #p5l17 .p5l17-cap{font-size:13px;color:var(--ink-2);font-family:var(--mono)}
    #p5l17-canvas{max-width:100%;max-height:56vh;flex:1 1 auto;min-height:0}
    #p5l17-canvas .loopcanvas-svg{height:100%;max-height:56vh}
    #p5l17 .p5l17-bar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding-bottom:16px}
    #p5l17 .p5l17-bar .ghost{background:transparent;color:var(--ink);border:1px solid var(--ink)}
    #p5l17 .p5l17-bar select{padding:8px 10px;border:1px solid var(--ink);border-radius:6px;font:inherit;color:var(--ink)}
    #p5l17 .punch{margin-top:0}
  </style>
  <div class="p5l17-head">
    <span class="lesson-tag">Lesson 17 · 8 min</span>
    <h2>THE FLOW — run the machine</h2>
    <div class="p5l17-cap">press play — the token runs the whole circuit once, including one scripted failure and retry</div>
  </div>
  <div id="p5l17-canvas" class="loopcanvas"></div>
  <div class="p5l17-bar">
    <button id="p5-play" class="btn">Play</button>
    <button id="p5-pause" class="btn ghost">Pause</button>
    <button id="p5-reset" class="btn ghost">Reset</button>
    <label class="mono" for="p5-speed" style="font-size:13px">speed</label>
    <select id="p5-speed">
      <option value="1">1x</option>
      <option value="2">2x</option>
    </select>
  </div>
  <div class="punch">The loop is only one mechanism inside the machine.</div>
</section>

<section class="slide" data-lesson="The kanban view — work as state" data-min="3" data-notes="Every piece of work in this pipeline is a card, and every card sits in exactly one canonical state. Those states are wire values, observed live on the board API, not a presentation metaphor. Light them in order and watch work move from new to done through two approval gates. Two states live off the main line: queued, parked awaiting a worker, and stale, parked awaiting clarification for five days. When the verifier goes red, the card loops back to in-progress and the lane tries again. Stop asking what your agent is doing; ask what state the work is in.">
  <span class="lesson-tag">Lesson 18 · 3 min</span>
  <h2>The kanban view — work as state</h2>
  <p class="small">The board is the source of truth. Each card sits in exactly one canonical state — these are the live wire values.</p>
  <div class="stack">
    <div class="kanban-track">
      <div class="reveal kanban-chip lit">new</div>
      <div class="reveal kanban-chip lit">needs-clarification</div>
      <div class="reveal kanban-chip lit">ready-for-story</div>
      <div class="reveal kanban-chip lit">awaiting-G1</div>
      <div class="reveal kanban-chip lit">approved</div>
      <div class="reveal kanban-chip lit">planned</div>
      <div class="reveal kanban-chip lit">in-progress</div>
      <div class="reveal kanban-chip lit">awaiting-G2</div>
      <div class="reveal kanban-chip lit">approved</div>
      <div class="reveal kanban-chip lit">awaiting-G3</div>
      <div class="reveal kanban-chip lit done">done</div>
    </div>
    <div class="row">
      <span class="pill">parked</span>
      <div class="reveal kanban-chip lit">queued</div>
      <div class="reveal kanban-chip lit">stale</div>
    </div>
    <div class="reveal callout warn" style="margin:4px 0 0"><span class="mono">&#8629;</span> verifier red loops back to in-progress</div>
    <div class="punch">Stop asking what your agent is doing. Ask what state each piece of work is in.</div>
  </div>
</section>
`;

window.PARTS_INIT[5] = function () {
  var canvas = document.getElementById('p5l17-canvas');
  if (canvas && window.LoopCanvas) {
    LoopCanvas.mount(canvas, { stage: 8, ns: 'p5l17', token: true });
  }
  var speed = 1;
  var speedSel = document.getElementById('p5-speed');
  if (speedSel) {
    speedSel.addEventListener('change', function () {
      var v = parseInt(speedSel.value, 10);
      speed = (v === 2) ? 2 : 1;
    });
  }
  var play = document.getElementById('p5-play');
  if (play) {
    play.addEventListener('click', function () {
      if (window.DeckFlow && window.LoopCanvas) {
        DeckFlow.play({ ns: 'p5l17', seq: LoopCanvas.SEQ, speed: speed });
      }
    });
  }
  var pause = document.getElementById('p5-pause');
  if (pause) {
    pause.addEventListener('click', function () {
      if (window.DeckFlow) DeckFlow.pause('p5l17');
    });
  }
  var reset = document.getElementById('p5-reset');
  if (reset) {
    reset.addEventListener('click', function () {
      if (window.DeckFlow) DeckFlow.reset('p5l17');
    });
  }
  document.addEventListener('deck:activate', function (e) {
    if (e.detail && e.detail.slide && e.detail.slide.id === 'p5l17' && window.DeckFlow && window.LoopCanvas) {
      DeckFlow.reset('p5l17');
      DeckFlow.play({ ns: 'p5l17', seq: LoopCanvas.SEQ, speed: speed });
    }
  });
};
