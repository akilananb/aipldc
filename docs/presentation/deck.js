/* deck.js — engine: injects PARTS[1..6], wires navigation/steps/rail/clock/notes/agenda,
 * and exposes DeckFlow for the L17 centerpiece animation. Loaded LAST (after loop.js + parts). */
(function () {
  'use strict';

  var slides = [];
  var idx = 0;
  var lessonStart = Date.now();

  function buildSlides() {
    window.PARTS = window.PARTS || {};
    window.PARTS_INIT = window.PARTS_INIT || {};
    var container = document.getElementById('slides');
    var html = '';
    for (var i = 1; i <= 6; i++) {
      if (window.PARTS[i]) html += window.PARTS[i];
    }
    container.innerHTML = html;
    for (var j = 1; j <= 6; j++) {
      if (typeof window.PARTS_INIT[j] === 'function') window.PARTS_INIT[j]();
    }
    slides = Array.prototype.slice.call(container.querySelectorAll('.slide'));
  }

  function playReveal(slideEl) {
    var els = Array.prototype.slice.call(slideEl.querySelectorAll('.reveal'));
    els.forEach(function (el, j) {
      el.classList.remove('in');
      el.style.transitionDelay = Math.min(j * 150, 1400) + 'ms';
    });
    void slideEl.offsetWidth; // force reflow so the removed .in commits before re-adding it
    requestAnimationFrame(function () {
      els.forEach(function (el) { el.classList.add('in'); });
    });
  }

  function activate(i) {
    if (i < 0 || i >= slides.length) return;
    slides.forEach(function (s, j) { s.classList.toggle('active', j === i); });
    playReveal(slides[i]);
    idx = i;
    lessonStart = Date.now();
    updateRail();
    updateNotes();
    if (history.replaceState) history.replaceState(null, '', '#' + i);
    else location.hash = '#' + i;
    document.dispatchEvent(new CustomEvent('deck:activate', { detail: { index: i, slide: slides[i] } }));
  }

  function next() { activate(idx + 1); }

  function prev() { activate(idx - 1); }

  function goHome() { activate(0); }
  function goEnd() { activate(slides.length - 1); }

  function buildRail() {
    var segWrap = document.getElementById('rail-segments');
    segWrap.innerHTML = slides.map(function (s) {
      var min = parseFloat(s.dataset.min) || 0;
      return '<div class="seg" style="--w:' + Math.max(1, min) + '"></div>';
    }).join('');
    Array.prototype.forEach.call(segWrap.querySelectorAll('.seg'), function (seg, i) {
      seg.addEventListener('click', function () { activate(i); });
    });
  }

  function updateRail() {
    var segs = document.querySelectorAll('#rail-segments .seg');
    segs.forEach(function (seg, j) {
      seg.classList.toggle('done', j < idx);
      seg.classList.toggle('now', j === idx);
    });
    var lesson = slides[idx].dataset.lesson || '';
    var min = slides[idx].dataset.min || '0';
    document.getElementById('rail-meta').textContent =
      (idx + 1) + ' / ' + slides.length + ' · ' + lesson + ' · ' + min + ' min';
  }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  function tickClock() {
    if (!slides.length) return;
    var budgetMs = (parseFloat(slides[idx].dataset.min) || 0) * 60000;
    var elapsed = Date.now() - lessonStart;
    var over = elapsed > budgetMs;
    var totalSec = Math.floor(elapsed / 1000);
    var mm = pad2(Math.floor(totalSec / 60)), ss = pad2(totalSec % 60);
    var clock = document.getElementById('rail-clock');
    clock.textContent = mm + ':' + ss;
    clock.classList.toggle('over', over);
  }

  function updateNotes() {
    var body = document.getElementById('notes-body');
    body.textContent = slides[idx].dataset.notes || '';
  }

  function toggleNotes() {
    document.getElementById('notes').classList.toggle('open');
  }

  function toggleFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen();
    else document.documentElement.requestFullscreen();
  }

  function buildAgenda() {
    var grid = document.getElementById('p1-agenda');
    if (!grid) return;
    var total = 0;
    var html = slides.map(function (s, i) {
      var min = parseFloat(s.dataset.min) || 0;
      total += min;
      var lesson = s.dataset.lesson || ('Slide ' + (i + 1));
      return '<div class="card" data-jump="' + i + '"><b>' + lesson + '</b><span class="t">' + min + ' min</span></div>';
    }).join('');
    grid.innerHTML = html;
    Array.prototype.forEach.call(grid.querySelectorAll('[data-jump]'), function (card) {
      card.addEventListener('click', function () { activate(parseInt(card.dataset.jump, 10)); });
    });
    var totalEl = document.getElementById('p1-agenda-total');
    if (totalEl) totalEl.textContent = total + ' min';
  }

  function initFromHash() {
    var h = location.hash.replace('#', '');
    var n = parseInt(h, 10);
    activate(!isNaN(n) && n >= 0 && n < slides.length ? n : 0);
  }

  function onKeydown(e) {
    var t = e.target;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) return;
    switch (e.key) {
      case 'ArrowRight':
      case ' ':
        e.preventDefault();
        next();
        break;
      case 'ArrowLeft':
        prev();
        break;
      case 'Home':
        goHome();
        break;
      case 'End':
        goEnd();
        break;
      case 'n':
      case 'N':
        toggleNotes();
        break;
      case 't':
      case 'T':
        lessonStart = Date.now();
        break;
      case 'f':
      case 'F':
        toggleFullscreen();
        break;
      default:
        return;
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    buildSlides();
    buildRail();
    buildAgenda();
    document.addEventListener('keydown', onKeydown);
    window.addEventListener('hashchange', initFromHash);
    setInterval(tickClock, 500);
    initFromHash();
  });

  window.Deck = { activate: activate, next: next, prev: prev, goHome: goHome, goEnd: goEnd };

  // ---------------------------------------------------------------------------------------
  // DeckFlow — drives the token along LoopCanvas.SEQ for the L17 centerpiece animation.
  // ---------------------------------------------------------------------------------------
  window.DeckFlow = (function () {
    var runs = {}; // ns -> { i, raf, playing }

    function edgeEl(ns, name) { return document.getElementById(ns + '-e-' + name); }
    function nodeEl(ns, name) { return document.getElementById(ns + '-n-' + name); }
    function tokenEl(ns) { return document.getElementById(ns + '-token'); }

    function ensure(ns) {
      if (!runs[ns]) runs[ns] = { i: 0, raf: null, playing: false };
      return runs[ns];
    }

    function play(opts) {
      var ns = opts.ns, seq = opts.seq, speed = opts.speed || 1;
      var run = ensure(ns);
      if (run.playing) return;
      run.playing = true;
      advance(ns, seq, speed);
    }

    function advance(ns, seq, speed) {
      var run = ensure(ns);
      if (!run.playing) return;
      if (run.i >= seq.length) { run.playing = false; return; }
      var s = seq[run.i];
      var edgePath = edgeEl(ns, s.edge);
      var node = nodeEl(ns, s.node);
      var token = tokenEl(ns);
      if (edgePath) edgePath.classList.add('lit');
      var dwell = (s.dwell || 700) / speed;
      var len = edgePath ? edgePath.getTotalLength() : 0;
      var start = null;

      function frame(ts) {
        if (!run.playing) return;
        if (!start) start = ts;
        var t = Math.min(1, (ts - start) / dwell);
        if (edgePath && token) {
          var pt = edgePath.getPointAtLength(t * len);
          token.setAttribute('cx', pt.x);
          token.setAttribute('cy', pt.y);
        }
        if (t < 1) {
          run.raf = requestAnimationFrame(frame);
        } else {
          if (node) {
            node.classList.add('active');
            if (s.closesWheel) node.classList.add('done');
          }
          run.i++;
          run.raf = requestAnimationFrame(function () { advance(ns, seq, speed); });
        }
      }
      run.raf = requestAnimationFrame(frame);
    }

    function pause(ns) {
      var run = runs[ns];
      if (!run) return;
      run.playing = false;
      if (run.raf) cancelAnimationFrame(run.raf);
    }

    function reset(ns) {
      pause(ns);
      runs[ns] = { i: 0, raf: null, playing: false };
      var svg = document.querySelector('svg[data-ns="' + ns + '"]');
      if (!svg) return;
      Array.prototype.forEach.call(svg.querySelectorAll('.lit'), function (el) { el.classList.remove('lit'); });
      Array.prototype.forEach.call(svg.querySelectorAll('.active'), function (el) { el.classList.remove('active'); });
      Array.prototype.forEach.call(svg.querySelectorAll('.done'), function (el) { el.classList.remove('done'); });
      var token = tokenEl(ns);
      var home = window.LoopCanvas && window.LoopCanvas.pos('engineer');
      if (token && home) {
        token.setAttribute('cx', home.x);
        token.setAttribute('cy', home.y);
      }
    }

    return { play: play, pause: pause, reset: reset };
  })();
})();
