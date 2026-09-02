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
      return '<circle cx="' + n.cx + '" cy="' + n.cy + '" r="' + (n.r + 6) + '" fill="none" stroke="rgba(4,115,234,0.22)" stroke-width="1.5" stroke-dasharray="4 3"/>' +
        '<circle class="' + cls + '" cx="' + n.cx + '" cy="' + n.cy + '" r="' + n.r + '"/>' +
        '<g transform="translate(' + n.cx + ',' + (n.cy - 2) + ')" opacity="0.85">' +
        '<circle cx="0" cy="-6" r="5.5" fill="var(--ink)"/>' +
        '<path d="M-10,10 C-10,4 10,4 10,10 Z" fill="var(--ink)"/>' +
        '</g>' +
        '<text class="flabel" x="' + n.cx + '" y="' + (n.cy + n.r + 18) + '">' + esc(n.label) + '</text>' +
        '<text class="flabel sub" x="' + n.cx + '" y="' + (n.cy + n.r + 32) + '">HUMAN PROMPT</text>';
    }
    if (n.shape === 'diamond') {
      var hw = n.w / 2, hh = n.h / 2;
      var pts = [[n.cx, n.cy - hh], [n.cx + hw, n.cy], [n.cx, n.cy + hh], [n.cx - hw, n.cy]]
        .map(function (p) { return p[0] + ',' + p[1]; }).join(' ');
      var innerPts = [[n.cx, n.cy - hh + 7], [n.cx + hw - 10, n.cy], [n.cx, n.cy + hh - 7], [n.cx - hw + 10, n.cy]]
        .map(function (p) { return p[0] + ',' + p[1]; }).join(' ');
      var parts = n.label.split('·').map(function (s) { return s.trim(); });
      var gateId = parts[0] || 'GATE';
      var gateRole = parts[1] || '';
      return '<polygon class="' + cls + '" points="' + pts + '"/>' +
        '<polygon points="' + innerPts + '" fill="none" stroke="rgba(217, 56, 30, 0.25)" stroke-width="1"/>' +
        '<text class="flabel badge" x="' + n.cx + '" y="' + (n.cy - 14) + '">GOVERNANCE</text>' +
        '<text class="flabel" style="font-size:15px;font-weight:700;fill:var(--stop)" x="' + n.cx + '" y="' + (n.cy + 3) + '">' + esc(gateId) + '</text>' +
        '<text class="flabel sub" x="' + n.cx + '" y="' + (n.cy + 19) + '">' + esc(gateRole) + '</text>';
    }
    if (n.shape === 'strip') {
      return '<rect class="' + cls + '" x="' + (n.cx - n.w / 2) + '" y="' + (n.cy - n.h / 2) + '" width="' + n.w + '" height="' + n.h + '" rx="17"/>' +
        '<text class="flabel sub" style="font-size:10.5px;font-weight:700;letter-spacing:.04em" x="' + n.cx + '" y="' + (n.cy + 4) + '">' + esc(n.label.toUpperCase()) + '</text>';
    }
    if (n.shape === 'chip') {
      return '<rect class="' + cls + '" x="' + (n.cx - n.w / 2) + '" y="' + (n.cy - n.h / 2) + '" width="' + n.w + '" height="' + n.h + '" rx="15"/>' +
        '<text class="flabel sub" style="font-size:11px;font-weight:700" x="' + n.cx + '" y="' + (n.cy + 4) + '">' + esc(n.label) + '</text>';
    }
    if (n.shape === 'small') {
      if (n.name === 'card') {
        return '<rect class="' + cls + '" x="' + (n.cx - n.w / 2) + '" y="' + (n.cy - n.h / 2) + '" width="' + n.w + '" height="' + n.h + '" rx="8"/>' +
          '<path d="M' + (n.cx - n.w / 2 + 5) + ',' + (n.cy - n.h / 2 + 2) + ' h' + (n.w - 10) + '" stroke="var(--stop)" stroke-width="2.5" stroke-linecap="round"/>' +
          '<text class="flabel badge" style="fill:var(--stop)" x="' + n.cx + '" y="' + (n.cy - 3) + '">INCIDENT</text>' +
          '<text class="flabel" style="font-size:12px" x="' + n.cx + '" y="' + (n.cy + 12) + '">' + esc(n.label) + '</text>';
      }
      return '<rect class="' + cls + '" x="' + (n.cx - n.w / 2) + '" y="' + (n.cy - n.h / 2) + '" width="' + n.w + '" height="' + n.h + '" rx="6"/>' +
        '<text class="flabel sub" style="font-size:9.5px;font-weight:700" x="' + n.cx + '" y="' + (n.cy + 3) + '">' + esc(n.label.toUpperCase()) + '</text>';
    }

    // Standard rect cards (w: 150, h: 56)
    var stripeColor = n.cls === 'amber' ? 'var(--loop)' : (n.cls === 'verify' ? 'var(--pass)' : (n.cls === 'card' ? 'var(--sc-navy)' : 'var(--line)'));
    var stripeSvg = '<path d="M' + (n.cx - n.w / 2 + 6) + ',' + (n.cy - n.h / 2 + 2) + ' h' + (n.w - 12) + '" stroke="' + stripeColor + '" stroke-width="2.5" stroke-linecap="round"/>';
    var textSvg = '';
    if (n.label.indexOf('·') !== -1) {
      var p = n.label.split('·').map(function (s) { return s.trim(); });
      textSvg = '<text class="flabel" x="' + n.cx + '" y="' + (n.cy - 1) + '">' + esc(p[0]) + '</text>' +
        '<text class="flabel sub" x="' + n.cx + '" y="' + (n.cy + 15) + '">' + esc(p[1]) + '</text>';
    } else if (n.label.indexOf('+') !== -1) {
      var p = n.label.split('+').map(function (s) { return s.trim(); });
      textSvg = '<text class="flabel" x="' + n.cx + '" y="' + (n.cy - 1) + '">' + esc(p[0]) + '</text>' +
        '<text class="flabel sub" x="' + n.cx + '" y="' + (n.cy + 15) + '">+ ' + esc(p[1]) + '</text>';
    } else if (n.cls === 'amber') {
      textSvg = '<text class="flabel badge" x="' + n.cx + '" y="' + (n.cy - 6) + '">✦ AGENT</text>' +
        '<text class="flabel" x="' + n.cx + '" y="' + (n.cy + 12) + '">' + esc(n.label) + '</text>';
    } else {
      textSvg = '<text class="flabel" x="' + n.cx + '" y="' + (n.cy + 5) + '">' + esc(n.label) + '</text>';
    }
    return '<rect class="' + cls + '" x="' + (n.cx - n.w / 2) + '" y="' + (n.cy - n.h / 2) + '" width="' + n.w + '" height="' + n.h + '" rx="10"/>' + stripeSvg + textSvg;
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
    var markerRef = 'url(#arrow' + (e.pass ? '-pass' : (e.stop ? '-stop' : '')) + ')';
    var pathSvg = '<path id="' + id + '" class="' + cls + '" d="' + edgeD(e) + '" marker-end="' + markerRef + '"/>';
    var labelSvg = '';
    if (e.label) {
      var w = Math.max(48, e.label.length * 7.5 + 16);
      var h = 20;
      var bgCls = 'elabel-bg' + (e.pass ? ' pass' : (e.stop ? ' stop' : ''));
      var txtCls = 'elabel' + (e.pass ? ' pass' : (e.stop ? ' stop' : ''));
      labelSvg = '<rect class="' + bgCls + '" x="' + (mid.x - w / 2) + '" y="' + (mid.y - 15) + '" width="' + w + '" height="' + h + '" rx="10"/>' +
        '<text class="' + txtCls + '" x="' + mid.x + '" y="' + (mid.y - 1) + '">' + esc(e.label) + '</text>';
    }
    var flowSvg = '';
    if (!noFlow) {
      var a = NODE_MAP[e.a], b = NODE_MAP[e.b];
      var dist = Math.hypot(b.cx - a.cx, b.cy - a.cy);
      var dur = Math.max(1.6, Math.min(3.2, dist / 140));
      var dotCls = 'flowdot' + (e.pass ? ' pass' : (e.stop ? ' stop' : ''));
      var r = e.thin ? 2.5 : 3.5;
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
      '<defs>' +
      '<pattern id="grid-' + ns + '" width="24" height="24" patternUnits="userSpaceOnUse"><circle cx="2" cy="2" r="0.8" fill="rgba(2,11,67,0.06)"/></pattern>' +
      '<marker id="arrow-' + ns + '" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="8" markerHeight="8" orient="auto-start-reverse">' +
      '<path d="M1,2 L10,6 L1,10 L3,6 Z" fill="#6E8299"/></marker>' +
      '<marker id="arrow-pass-' + ns + '" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="8" markerHeight="8" orient="auto-start-reverse">' +
      '<path d="M1,2 L10,6 L1,10 L3,6 Z" fill="var(--pass)"/></marker>' +
      '<marker id="arrow-stop-' + ns + '" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="8" markerHeight="8" orient="auto-start-reverse">' +
      '<path d="M1,2 L10,6 L1,10 L3,6 Z" fill="var(--stop)"/></marker>' +
      '</defs>' +
      '<rect width="100%" height="100%" fill="url(#grid-' + ns + ')"/>' +
      '<g opacity="0.35"><text x="24" y="30" font-family="var(--mono)" font-size="10" font-weight="600" fill="var(--ink-2)" letter-spacing="0.08em">SC DIGITAL WORKFLOW PIPELINE // ENGINE</text></g>' +
      bodyParts.join('') + tokenSvg + '</svg>';
    // Namespace the arrow markers + strip the placeholder ids into real namespaced ids.
    svg = svg.replace(/url\(#arrow-pass\)/g, 'url(#arrow-pass-' + ns + ')')
      .replace(/url\(#arrow-stop\)/g, 'url(#arrow-stop-' + ns + ')')
      .replace(/url\(#arrow\)/g, 'url(#arrow-' + ns + ')')
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
