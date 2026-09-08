/* part5.js — Course 10: THE FLOW — watch one order go all the way through the finished kitchen. */
window.PARTS = window.PARTS || {};
window.PARTS_INIT = window.PARTS_INIT || {};

window.PARTS[5] = `
<section class="slide" id="p5l17" data-lesson="Watch one order go all the way through" data-min="6" data-notes="This is the centrepiece: the whole kitchen, one order, end to end. Watch the ticket go from the board to the waiter, get written as a recipe, clear the first pass into the prep list and the rail, land at a station, fail the taste test once and go straight back to the same cook, then pass, go through the plating check, the second pass, the send-out pack, the third pass, out to the table, and on to the critic, whose complaint lands back on the board where we started. Pause and reset are there so you can freeze any leg. The cook's loop everyone talks about is one gear in this. The kitchen is the product.">
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
    <div class="lesson-tag">Course 10</div>
    <h2>Watch one order go all the way through</h2>
    <div class="p5l17-cap">Press play. One order — Priya's spreadsheet button — goes from the board to the table through the finished stage-8 pipeline, fails a taste test once on the way, and comes back around.</div>
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
  <div class="punch">The cook's loop is only one gear in the kitchen.</div>
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
