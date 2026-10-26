# AI-Powered Software Delivery — demo video

60-second silent product demo (1920×1080, 30 fps), built with [Remotion](https://www.remotion.dev).
It opens on the Enterprise AI Day title slide, frames the delivery problem, then walks one
corporate-banking feature ("Automate corporate loan application validation") through the
**real eLoop.ai screens**, with zooms, highlights and a subtitle bar.

```bash
npm install
npm run studio                 # live preview in the browser
node scripts/capture.mjs       # re-capture real screens (needs the stack: UI :5173, API :8081)
npm run render                 # writes out/ai-pdlc-demo.mp4 (no audio track)
```

- Scene order and lengths: `src/Video.tsx` (`SCENES`, must total 1800 frames)
- Real-screen scenes, captions, zooms and highlights: `src/scenes/Product.tsx`
- Title slide: `src/scenes/TitleSlide.tsx`; problem and outcome scenes: `Act1.tsx`, `Act2.tsx`, `Act4.tsx`
- Screens to capture and elements to measure: `scripts/shots.json` → `public/screens/*.png|json`
- The loan data shown comes from the Corporate Lending demo seed
  (`control-plane/src/main/resources/demo/loan-demo.json`).
- Rendering uses the installed Google Chrome (`remotion.config.ts`).
