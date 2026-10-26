// Captures real eLoop.ai screens for the demo video.
// Usage: node scripts/capture.mjs [shots.json]   (UI on :5173, API on :8081 must be running)
// Writes 2x PNGs to public/screens/<name>.png so zooms in the video stay sharp.
import fs from 'node:fs';
import path from 'node:path';
import puppeteer from 'puppeteer-core';

const UI = process.env.UI_URL ?? 'http://localhost:5173';
const API = process.env.API_URL ?? 'http://localhost:8081';
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const OUT = path.resolve('public/screens');
const shotsFile = process.argv[2] ?? 'scripts/shots.json';
const shots = JSON.parse(fs.readFileSync(shotsFile, 'utf8'));

fs.mkdirSync(OUT, { recursive: true });
const items = await (await fetch(`${API}/api/items`)).json();
const idFor = (boardId) => {
  const hit = items.find((i) => i.boardId === boardId);
  if (!hit) throw new Error(`No item with boardId ${boardId}`);
  return hit.id;
};

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true });
const page = await browser.newPage();
await page.setViewport({ width: 1600, height: 1000, deviceScaleFactor: 2 });
await page.evaluateOnNewDocument(() => {
  localStorage.setItem('pdlc.appearance', 'light');
});

for (const s of shots) {
  const url = s.boardId
    ? `${UI}/items/${idFor(s.boardId)}${s.tab ? `?tab=${s.tab}` : ''}`
    : `${UI}${s.path ?? '/'}`;
  await page.goto(url, { waitUntil: 'networkidle0' });
  await new Promise((r) => setTimeout(r, 800));
  if (s.clickText) {
    // Click the first element whose text matches (e.g. expand a row).
    await page.evaluate((t) => {
      const el = [...document.querySelectorAll('button, a, [role="button"], tr, td')].find((e) =>
        e.textContent?.trim().startsWith(t),
      );
      el?.click();
    }, s.clickText);
    await new Promise((r) => setTimeout(r, 600));
  }
  if (s.scrollToText) {
    await page.evaluate((t) => {
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      let n;
      while ((n = walker.nextNode())) {
        if (n.textContent?.includes(t)) {
          const el = n.parentElement;
          el?.scrollIntoView({ block: 'start' });
          // Leave room for the sticky top bar.
          let p = el?.parentElement;
          while (p && !(p.scrollHeight > p.clientHeight + 10 && getComputedStyle(p).overflowY !== 'visible')) p = p.parentElement;
          (p ?? window).scrollBy(0, -90);
          break;
        }
      }
    }, s.scrollToText);
  }
  if (s.scrollBy) {
    await page.evaluate((y) => {
      // The app scrolls inside its main column, not the window.
      const main = [...document.querySelectorAll('*')].find(
        (e) => e.scrollHeight > e.clientHeight + 50 && getComputedStyle(e).overflowY !== 'visible',
      );
      (main ?? window).scrollBy(0, y);
    }, s.scrollBy);
  }
  await new Promise((r) => setTimeout(r, 500));
  const file = path.join(OUT, `${s.name}.png`);
  await page.screenshot({ path: file });

  // Measure named regions (CSS px in the 1600×1000 viewport) for camera moves and highlights.
  // Each mark: { id, text, up? } → bounding box of the element holding `text`, `up` levels higher.
  const marks = {};
  for (const m of s.marks ?? []) {
    marks[m.id] = await page.evaluate(({ text, up = 0 }) => {
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      let n;
      while ((n = walker.nextNode())) {
        if (!n.textContent?.includes(text)) continue;
        let el = n.parentElement;
        for (let i = 0; i < up && el?.parentElement; i++) el = el.parentElement;
        const r = el.getBoundingClientRect();
        if (r.width === 0 || r.bottom < 0 || r.top > window.innerHeight) continue;
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
      }
      return null;
    }, m);
    if (!marks[m.id]) console.warn(`  mark "${m.id}" not found on screen`);
  }
  fs.writeFileSync(path.join(OUT, `${s.name}.json`), JSON.stringify(marks, null, 2));
  console.log(`captured ${s.name} ← ${url}`);
}

await browser.close();
