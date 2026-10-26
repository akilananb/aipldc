import React from 'react';
import { AbsoluteFill, Easing, Img, interpolate, staticFile, useCurrentFrame } from 'remotion';
import { C, RADIUS } from './theme';
import { rise } from './anim';

// Screenshots are 1600×1000 CSS px captured at 2x. All rects below are in those CSS px.
export const SHOT_W = 1600;
export const SHOT_H = 1000;

export type Rect = { x: number; y: number; w: number; h: number };
export type CamKey = { at: number; view: Rect };
export type Highlight = { at: number; until?: number; rect: Rect };

const FULL: Rect = { x: 0, y: 0, w: SHOT_W, h: SHOT_H };

/** Area of the frame the screenshot sits in (leaves room for the subtitle bar). */
const STAGE = { x: 80, y: 56, w: 1760, h: 830 };

function lerpRect(a: Rect, b: Rect, t: number): Rect {
  return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t, w: a.w + (b.w - a.w) * t, h: a.h + (b.h - a.h) * t };
}

/** Grow (or, at the edges, trim) a view so it matches the stage's aspect and fills it. */
function fitAspect(v: Rect): Rect {
  const A = STAGE.w / STAGE.h;
  let { x, y, w, h } = v;
  const cx = x + w / 2;
  const cy = y + h / 2;
  if (w / h < A) {
    w = Math.min(h * A, SHOT_W);
    h = w / A;
  } else {
    h = Math.min(w / A, SHOT_H);
    w = h * A;
  }
  x = Math.min(Math.max(cx - w / 2, 0), SHOT_W - w);
  y = Math.min(Math.max(cy - h / 2, 0), SHOT_H - h);
  return { x, y, w, h };
}

/** Camera rect at `frame`, easing between keyframes over 24 frames each. */
function cameraAt(frame: number, keys: CamKey[]): Rect {
  let view = keys[0]?.view ?? FULL;
  for (let i = 1; i < keys.length; i++) {
    const k = keys[i];
    const t = interpolate(frame, [k.at, k.at + 24], [0, 1], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.inOut(Easing.cubic),
    });
    if (t > 0) view = lerpRect(view, k.view, t);
  }
  return view;
}

/**
 * A real product screenshot inside a browser frame, with an animated camera
 * (zoom/pan to a region) and optional highlight rings.
 * `src` can also be a list of screenshots that cross-fade at the given frames.
 */
export const Screen: React.FC<{
  src: string | { at: number; src: string }[];
  cam?: CamKey[];
  highlights?: Highlight[];
}> = ({ src, cam = [{ at: 0, view: FULL }], highlights = [] }) => {
  const frame = useCurrentFrame();
  const view = fitAspect(cameraAt(frame, cam));
  // Fit view rect to stage, keeping aspect (letterbox inside stage).
  const scale = Math.min(STAGE.w / view.w, STAGE.h / view.h);
  const vw = view.w * scale;
  const vh = view.h * scale;
  const ox = STAGE.x + (STAGE.w - vw) / 2;
  const oy = STAGE.y + (STAGE.h - vh) / 2;
  const sources = typeof src === 'string' ? [{ at: 0, src }] : src;
  const enter = rise(frame, 0);

  return (
    <AbsoluteFill style={{ background: C.grey }}>
      <div
        style={{
          position: 'absolute',
          left: ox,
          top: oy,
          width: vw,
          height: vh,
          overflow: 'hidden',
          borderRadius: RADIUS * 1.5,
          boxShadow: '0 20px 50px rgba(0, 51, 102, 0.18), 0 4px 6px rgba(0,0,0,0.1)',
          border: `1px solid ${C.line}`,
          background: C.white,
          opacity: enter,
          transform: `translateY(${(1 - enter) * 30}px)`,
        }}
      >
        {sources.map((s, i) => {
          const next = sources[i + 1];
          const opacity =
            interpolate(frame, [s.at, s.at + 10], [i === 0 ? 1 : 0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }) *
            (next ? interpolate(frame, [next.at, next.at + 10], [1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }) : 1);
          if (opacity <= 0) return null;
          return (
            <Img
              key={s.src}
              src={staticFile(s.src)}
              style={{
                position: 'absolute',
                left: -view.x * scale,
                top: -view.y * scale,
                width: SHOT_W * scale,
                height: SHOT_H * scale,
                opacity,
              }}
            />
          );
        })}
        {(() => {
          // One shared spotlight: dim everything except the active highlight boxes.
          const pad = 8;
          const boxes = highlights
            .map((h) => {
              const on = rise(frame, h.at, 18);
              const off = h.until ? 1 - rise(frame, h.until, 200) : 1;
              return {
                o: on * off,
                on,
                x: (h.rect.x - view.x) * scale - pad,
                y: (h.rect.y - view.y) * scale - pad,
                w: h.rect.w * scale + pad * 2,
                h: h.rect.h * scale + pad * 2,
              };
            })
            .filter((b) => b.o > 0.01);
          if (!boxes.length) return null;
          const dim = Math.max(...boxes.map((b) => b.o));
          const id = `spot-${Math.round(view.x)}-${boxes.length}`;
          return (
            <>
              <svg width={vw} height={vh} style={{ position: 'absolute', inset: 0 }}>
                <defs>
                  <mask id={id}>
                    <rect width={vw} height={vh} fill="white" />
                    {boxes.map((b, i) => (
                      <rect key={i} x={b.x} y={b.y} width={b.w} height={b.h} rx={12} fill="black" opacity={b.o} />
                    ))}
                  </mask>
                </defs>
                <rect width={vw} height={vh} fill={C.navy} opacity={0.2 * dim} mask={`url(#${id})`} />
              </svg>
              {boxes.map((b, i) => (
                <div
                  key={i}
                  style={{
                    position: 'absolute',
                    left: b.x,
                    top: b.y,
                    width: b.w,
                    height: b.h,
                    border: `4px solid ${C.green}`,
                    borderRadius: 12,
                    opacity: b.o,
                    transform: `scale(${1.04 - 0.04 * b.on})`,
                  }}
                />
              ))}
            </>
          );
        })()}
      </div>
    </AbsoluteFill>
  );
};
