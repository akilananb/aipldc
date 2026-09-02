/* LoopCanvas — the single shared, progressively-growing loop diagram.
 * Director-owned. Writers only call LoopCanvas.mount(...). See storyboard.md §5 for the
 * master geometry / stage map this file implements verbatim. */
(function () {
  'use strict';

  var VB_W = 1280, VB_H = 680;

  // ---- Master node geometry (fixed positions, never re-laid-out) --------------------------
  var NODES = [
    { name: 'engineer', cx: 80, cy: 300, shape: 'circle', r: 30, label: 'engineer', stageIn: 1 },
    { name: 'agent', cx: 560, cy: 300, shape: 'rect', w: 150, h: 56, label: 'agent', stageIn: 1, cls: 'amber' },
    { name: 'review', cx: 950, cy: 300, shape: 'rect', w: 150, h: 56, label: 'review · PR', stageIn: 1 },
    { name: 'verify', cx: 770, cy: 300, shape: 'rect', w: 150, h: 56, label: 'verify · npm test', stageIn: 2, cls: 'verify' },
    { name: 'board', cx: 140, cy: 80, shape: 'rect', w: 150, h: 56, label: 'board · new card', stageIn: 3, cls: 'card' },
    { name: 'grill', cx: 330, cy: 80, shape: 'rect', w: 150, h: 56, label: 'grill', stageIn: 3 },
    { name: 'story', cx: 520, cy: 80, shape: 'rect', w: 150, h: 56, label: 'story + spec', stageIn: 3 },
    { name: 'g1', cx: 700, cy: 80, shape: 'diamond', w: 140, h: 92, label: 'G1 · PO + Squad Lead', stageIn: 4, cls: 'gate' },
    { name: 'quality', cx: 520, cy: 150, shape: 'chip', w: 110, h: 30, label: 'quality', stageIn: 4, cls: 'chip' },
    { name: 'plan', cx: 860, cy: 80, shape: 'rect', w: 150, h: 56, label: 'plan', stageIn: 5 },
    { name: 'queue', cx: 560, cy: 215, shape: 'strip', w: 270, h: 34, label: 'task queue · claimed over REST', stageIn: 5 },
    { name: 'agent2', cx: 560, cy: 390, shape: 'rect', w: 150, h: 56, label: 'agent · lane 2', stageIn: 5, cls: 'amber' },
    { name: 'verify2', cx: 770, cy: 390, shape: 'rect', w: 150, h: 56, label: 'verify · lane 2', stageIn: 5, cls: 'verify' },
    { name: 'agent3', cx: 560, cy: 460, shape: 'rect', w: 150, h: 56, label: 'agent · lane 3', stageIn: 5, cls: 'amber' },
    { name: 'verify3', cx: 770, cy: 460, shape: 'rect', w: 150, h: 56, label: 'verify · lane 3', stageIn: 5, cls: 'verify' },
    { name: 'shield2', cx: 495, cy: 390, shape: 'small', w: 36, h: 28, label: 'guard', stageIn: 5, cls: 'shield' },
    { name: 'shield3', cx: 495, cy: 460, shape: 'small', w: 36, h: 28, label: 'guard', stageIn: 5, cls: 'shield' },
    { name: 'g2', cx: 1090, cy: 300, shape: 'diamond', w: 140, h: 92, label: 'G2 · FSDev + QA', stageIn: 6, cls: 'gate' },
    { name: 'mention', cx: 950, cy: 210, shape: 'chip', w: 110, h: 30, label: '@mention', stageIn: 6, cls: 'chip' },
    { name: 'release', cx: 1090, cy: 440, shape: 'rect', w: 150, h: 56, label: 'release pack · 4 docs', stageIn: 7 },
    { name: 'g3', cx: 950, cy: 530, shape: 'diamond', w: 140, h: 92, label: 'G3 · signatures', stageIn: 7, cls: 'gate' },
    { name: 'deploy', cx: 770, cy: 530, shape: 'rect', w: 150, h: 56, label: 'deploy', stageIn: 7 },
    { name: 'monitor', cx: 560, cy: 530, shape: 'rect', w: 150, h: 56, label: 'monitor', stageIn: 8 },
    { name: 'card', cx: 330, cy: 530, shape: 'small', w: 120, h: 44, label: 'trip card', stageIn: 8, cls: 'card' }
  ];

  // ---- Master edge geometry ----------------------------------------------------------------
  var EDGES = [
    { name: 'engineer-agent', a: 'engineer', b: 'agent', label: 'prompt', stageIn: 1, stageOut: 3 },
    { name: 'agent-review', a: 'agent', b: 'review', label: '', stageIn: 1, stageOut: 2 },
    { name: 'review-engineer', a: 'review', b: 'engineer', label: 'read the diff', stageIn: 1, stageOut: 6, thin: true, curve: [810, 210] },
    { name: 'agent-verify', a: 'agent', b: 'verify', label: '', stageIn: 2 },
    { name: 'verify-review', a: 'verify', b: 'review', label: 'PASS', stageIn: 2, pass: true },
    { name: 'verify-agent', a: 'verify', b: 'agent', label: 'FAIL', stageIn: 2, stop: true, dashed: true },
    { name: 'engineer-board', a: 'engineer', b: 'board', label: 'files a card', stageIn: 3 },
    { name: 'board-grill', a: 'board', b: 'grill', label: '', stageIn: 3 },
    { name: 'grill-story', a: 'grill', b: 'story', label: '', stageIn: 3 },
    { name: 'story-agent', a: 'story', b: 'agent', label: '', stageIn: 3, stageOut: 4, curve: [520, 300] },
    { name: 'story-g1', a: 'story', b: 'g1', label: '', stageIn: 4 },
    { name: 'g1-agent', a: 'g1', b: 'agent', label: '', stageIn: 4, stageOut: 5, curve: [700, 300] },
    { name: 'g1-plan', a: 'g1', b: 'plan', label: '', stageIn: 5 },
    { name: 'plan-queue', a: 'plan', b: 'queue', label: '', stageIn: 5 },
    { name: 'queue-agent', a: 'queue', b: 'agent', label: '', stageIn: 5 },
    { name: 'queue-agent2', a: 'queue', b: 'agent2', label: '', stageIn: 5, curve: [750, 280] },
    { name: 'queue-agent3', a: 'queue', b: 'agent3', label: '', stageIn: 5, curve: [50, 280] },
    { name: 'agent2-verify2', a: 'agent2', b: 'verify2', label: '', stageIn: 5 },
    { name: 'verify2-review', a: 'verify2', b: 'review', label: 'PASS', stageIn: 5, pass: true },
    { name: 'verify2-agent2', a: 'verify2', b: 'agent2', label: 'FAIL', stageIn: 5, stop: true, dashed: true },
    { name: 'agent3-verify3', a: 'agent3', b: 'verify3', label: '', stageIn: 5 },
    { name: 'verify3-review', a: 'verify3', b: 'review', label: 'PASS', stageIn: 5, pass: true, curve: [860, 460] },
    { name: 'verify3-agent3', a: 'verify3', b: 'agent3', label: 'FAIL', stageIn: 5, stop: true, dashed: true },
    { name: 'review-g2', a: 'review', b: 'g2', label: '', stageIn: 6 },
    { name: 'g2-release', a: 'g2', b: 'release', label: '', stageIn: 7 },
    { name: 'release-g3', a: 'release', b: 'g3', label: '', stageIn: 7 },
    { name: 'g3-deploy', a: 'g3', b: 'deploy', label: '', stageIn: 7 },
    { name: 'deploy-monitor', a: 'deploy', b: 'monitor', label: '', stageIn: 8 },
    { name: 'monitor-card', a: 'monitor', b: 'card', label: '', stageIn: 8 },
    { name: 'card-board', a: 'card', b: 'board', label: '', stageIn: 8, pass: true, curve: [60, 300] }
  ];

  // ---- Canonical stage-8 autoplay sequence (L17) ---------------------------------------------
  var SEQ = [
    { edge: 'engineer-board', node: 'board', dwell: 700 },
    { edge: 'board-grill', node: 'grill', dwell: 700 },
    { edge: 'grill-story', node: 'story', dwell: 700 },
    { edge: 'story-g1', node: 'g1', dwell: 700 },
    { edge: 'g1-plan', node: 'plan', dwell: 700 },
    { edge: 'plan-queue', node: 'queue', dwell: 700 },
    { edge: 'queue-agent', node: 'agent', dwell: 700 },
    { edge: 'agent-verify', node: 'verify', dwell: 700 },
    { edge: 'verify-agent', node: 'agent', dwell: 700 },
    { edge: 'agent-verify', node: 'verify', dwell: 700 },
    { edge: 'verify-review', node: 'review', dwell: 700 },
    { edge: 'review-g2', node: 'g2', dwell: 700 },
    { edge: 'g2-release', node: 'release', dwell: 700 },
    { edge: 'release-g3', node: 'g3', dwell: 700 },
    { edge: 'g3-deploy', node: 'deploy', dwell: 700 },
    { edge: 'deploy-monitor', node: 'monitor', dwell: 700 },
    { edge: 'monitor-card', node: 'card', dwell: 700 },
    { edge: 'card-board', node: 'board', dwell: 700, closesWheel: true }
  ];

  var NODE_MAP = {};
  NODES.forEach(function (n) { NODE_MAP[n.name] = n; });

  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;');
  }

  function nodeShapeSvg(n) {
    var cls = 'fnode ' + (n.cls || '');
    if (n.shape === 'circle') {
      return '<circle class="' + cls + '" cx="' + n.cx + '" cy="' + n.cy + '" r="' + n.r + '"/>' +
        '<text class="flabel" x="' + n.cx + '" y="' + (n.cy + n.r + 18) + '">' + esc(n.label) + '</text>';
    }
    if (n.shape === 'diamond') {
      var hw = n.w / 2, hh = n.h / 2;
      var pts = [[n.cx, n.cy - hh], [n.cx + hw, n.cy], [n.cx, n.cy + hh], [n.cx - hw, n.cy]]
        .map(function (p) { return p[0] + ',' + p[1]; }).join(' ');
      return '<polygon class="' + cls + '" points="' + pts + '"/>' +
        '<text class="flabel" x="' + n.cx + '" y="' + (n.cy + n.h / 2 + 16) + '">' + esc(n.label) + '</text>';
    }
    // rect, chip, strip, small — all boxes with center label
    var rx = (n.shape === 'chip' || n.shape === 'strip') ? n.h / 2 : 8;
    return '<rect class="' + cls + '" x="' + (n.cx - n.w / 2) + '" y="' + (n.cy - n.h / 2) +
      '" width="' + n.w + '" height="' + n.h + '" rx="' + rx + '"/>' +
      '<text class="flabel" x="' + n.cx + '" y="' + (n.cy + 5) + '">' + esc(n.label) + '</text>';
  }

  function boundaryPoint(node, tx, ty) {
    var dx = tx - node.cx, dy = ty - node.cy;
    if (dx === 0 && dy === 0) return { x: node.cx, y: node.cy };
    if (node.shape === 'circle') {
      var len = Math.sqrt(dx * dx + dy * dy);
      return { x: node.cx + (dx / len) * node.r, y: node.cy + (dy / len) * node.r };
    }
    var hw = node.w / 2, hh = node.h / 2, t;
    if (node.shape === 'diamond') {
      t = 1 / (Math.abs(dx) / hw + Math.abs(dy) / hh);
    } else {
      var tx2 = dx !== 0 ? hw / Math.abs(dx) : Infinity;
      var ty2 = dy !== 0 ? hh / Math.abs(dy) : Infinity;
      t = Math.min(tx2, ty2);
    }
    return { x: node.cx + dx * t, y: node.cy + dy * t };
  }

  function edgeD(e) {
    var a = NODE_MAP[e.a], b = NODE_MAP[e.b];
    if (e.curve) {
      var pA = boundaryPoint(a, e.curve[0], e.curve[1]);
      var pB = boundaryPoint(b, e.curve[0], e.curve[1]);
      return 'M ' + pA.x + ' ' + pA.y + ' Q ' + e.curve[0] + ' ' + e.curve[1] + ' ' + pB.x + ' ' + pB.y;
    }
    var p1 = boundaryPoint(a, b.cx, b.cy);
    var p2 = boundaryPoint(b, a.cx, a.cy);
    return 'M ' + p1.x + ' ' + p1.y + ' L ' + p2.x + ' ' + p2.y;
  }

  function edgeSvg(e, id, extraCls, noFlow) {
    var cls = 'fedge' + (e.dashed ? ' dashed' : '') + (e.pass ? ' pass' : '') + (e.stop ? ' stop' : '') +
      (e.thin ? ' thin' : '') + (extraCls ? ' ' + extraCls : '');
    var mid = midpoint(e);
    var labelSvg = e.label ? '<text class="elabel" x="' + mid.x + '" y="' + (mid.y - 6) + '">' + esc(e.label) + '</text>' : '';
    var pathSvg = '<path id="' + id + '" class="' + cls + '" d="' + edgeD(e) + '" marker-end="url(#arrow)"/>';
    var flowSvg = '';
    if (!noFlow) {
      var a = NODE_MAP[e.a], b = NODE_MAP[e.b];
      var dist = Math.hypot(b.cx - a.cx, b.cy - a.cy);
      var dur = Math.max(1.6, Math.min(3.2, dist / 140));
      var dotCls = 'flowdot' + (e.pass ? ' pass' : (e.stop ? ' stop' : ''));
      var r = e.thin ? 2.5 : 3;
      flowSvg = '<circle class="' + dotCls + '" r="' + r + '"><animateMotion dur="' + dur + 's" repeatCount="indefinite"><mpath href="#' + id + '"/></animateMotion></circle>';
    }
    return pathSvg + labelSvg + flowSvg;
  }

  function midpoint(e) {
    var a = NODE_MAP[e.a], b = NODE_MAP[e.b];
    if (e.curve) return { x: e.curve[0], y: e.curve[1] };
    return { x: (a.cx + b.cx) / 2, y: (a.cy + b.cy) / 2 };
  }

  function refKind(ref) { return ref.slice(0, 2) === 'n:' ? 'node' : 'edge'; }
  function refName(ref) { return ref.slice(2); }

  function renderRef(ref, extraCls, noFlow) {
    var kind = refKind(ref), name = refName(ref);
    if (kind === 'node') {
      var n = NODE_MAP[name];
      return '<g id="__ID_n_' + name + '" class="fnode-g ' + (extraCls || '') + '" data-cx="' + n.cx + '" data-cy="' + n.cy + '">' + nodeShapeSvg(n) + '</g>';
    }
    // Edges: the id + extra classes (new/sil/hl) go directly on the <path>, not a wrapping <g> —
    // DeckFlow needs an SVGGeometryElement (getTotalLength/getPointAtLength), which a <g> is not.
    var e = EDGES_MAP[name];
    return edgeSvg(e, '__ID_e_' + name, extraCls, noFlow);
  }

  var EDGES_MAP = {};
  EDGES.forEach(function (e) { EDGES_MAP[e.name] = e; });

  /**
   * LoopCanvas.mount(containerEl, { stage, ns, silhouette = false, token = false, revealFrom = stage })
   */
  function mount(containerEl, opts) {
    opts = opts || {};
    var stage = opts.stage || 1;
    var ns = opts.ns;
    var silhouette = !!opts.silhouette;
    var token = !!opts.token;
    var revealFrom = opts.revealFrom || stage;
    if (!ns) throw new Error('LoopCanvas.mount requires opts.ns');

    var effStage = silhouette ? 8 : stage;
    var noFlow = token || silhouette;

    var included = {};

    function isVisible(stageIn, stageOut) {
      return stageIn <= effStage && (stageOut === undefined || stageOut > effStage);
    }

    NODES.forEach(function (n) {
      if (isVisible(n.stageIn, n.stageOut)) included['n:' + n.name] = true;
    });
    EDGES.forEach(function (e) {
      if (isVisible(e.stageIn, e.stageOut)) included['e:' + e.name] = true;
    });

    function isNewly(stageIn) {
      return !silhouette && stageIn >= revealFrom && stageIn <= stage;
    }

    // Render every included node/edge directly — no step-wrapping, everything animates in via CSS.
    var bodyParts = [];
    NODES.forEach(function (n) {
      var ref = 'n:' + n.name;
      if (!included[ref]) return;
      bodyParts.push(renderPiece(ref, ns, isNewly(n.stageIn), silhouette));
    });
    EDGES.forEach(function (e) {
      var ref = 'e:' + e.name;
      if (!included[ref]) return;
      bodyParts.push(renderPiece(ref, ns, isNewly(e.stageIn), silhouette, noFlow));
    });

    var tokenSvg = '';
    if (token) {
      var start = NODE_MAP.engineer;
      tokenSvg = '<circle id="' + ns + '-token" class="token" r="9" cx="' + start.cx + '" cy="' + start.cy + '"/>';
    }

    var svg = '<svg class="loopcanvas-svg" data-ns="' + ns + '" viewBox="0 0 ' + VB_W + ' ' + VB_H + '" xmlns="http://www.w3.org/2000/svg">' +
      '<defs><marker id="arrow-' + ns + '" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">' +
      '<path d="M0,0 L10,5 L0,10 z" fill="var(--ink-2)"/></marker></defs>' +
      bodyParts.join('') + tokenSvg + '</svg>';
    // Namespace the arrow marker + strip the placeholder ids into real namespaced ids.
    svg = svg.replace(/url\(#arrow\)/g, 'url(#arrow-' + ns + ')')
      .replace(/__ID_n_/g, ns + '-n-')
      .replace(/__ID_e_/g, ns + '-e-');

    containerEl.innerHTML = svg;

    if (token) {
      // Draw-on-lit hack: only meaningful on the flow-play canvas, where DeckFlow lights edges
      // one at a time; everywhere else edges must render fully visible immediately.
      var svgEl = containerEl.querySelector('svg');
      Array.prototype.forEach.call(svgEl.querySelectorAll('.fedge'), function (path) {
        var len = path.getTotalLength();
        path.style.strokeDasharray = String(len);
        path.style.strokeDashoffset = String(len);
      });
    }
  }

  function renderPiece(ref, ns, isNew, silhouette, noFlow) {
    var extra = [];
    if (isNew) extra.push('new');
    if (silhouette) extra.push('sil');
    return renderRef(ref, extra.join(' '), noFlow);
  }

  function pos(name) {
    var n = NODE_MAP[name];
    return n ? { x: n.cx, y: n.cy } : null;
  }

  window.LoopCanvas = { mount: mount, SEQ: SEQ, pos: pos, NODES: NODES, EDGES: EDGES };
})();
