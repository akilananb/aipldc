/* part5.js — Course 15: THE FLOW — watch one order go all the way through the finished pipeline.
 * Course 16: Work as state (kanban). */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[5] = `
<section class="slide" id="p5l17" data-lesson="THE FLOW — one order end to end" data-min="5" data-notes="This is the centrepiece. One order, the whole pipeline, one token. Watch it fail the verifier once and loop back into the same lane, then pass and go through review, the second gate, the release pack, the third gate, deploy, the monitor, and a trip card that lands back on the board. Pause and reset are there for you. The loop everyone talks about is one gear in this machine.">
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
    <div class="lesson-tag">Course 15</div>
    <h2>THE FLOW — one order end to end</h2>
    <div class="p5l17-cap">Press play. Priya's export order runs the finished stage-8 pipeline: board → grill → story → G1 → plan → queue → lane → verify (one FAIL, one retry) → review → G2 → release → G3 → deploy → monitor → trip card → board.</div>
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

<section class="slide" data-lesson="Work as state" data-min="2" data-notes="Every piece of work is a card in exactly one state, and these are the real values the board reports. Light them in order and the whole lifecycle reads left to right through two approval gates, with queued and stale parked off the main line. When the verifier goes red the card goes back to in-progress and the lane tries again.">
  <div class="lesson-tag">Course 16</div>
  <h2>Work as state</h2>
  <p class="small">Every order sits in exactly one of these states — the live wire values on the board API, not a metaphor.</p>
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
    LoopCanvas.mount(canvas, { stage: 8, ns: 'p5l17', token: true, drawIn: true });
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
