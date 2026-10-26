import { interpolate, spring } from 'remotion';
import { FPS } from './theme';

/** 0 → 1 ease that starts at `start` (frames). */
export function rise(frame: number, start: number, damping = 200): number {
  return spring({ frame: frame - start, fps: FPS, config: { damping } });
}

/** Fade + slide up, as a style object. */
export function fadeUp(frame: number, start: number, dist = 24): React.CSSProperties {
  const s = rise(frame, start);
  return { opacity: s, transform: `translateY(${(1 - s) * dist}px)` };
}

/** Fade + slide in from the left. */
export function fadeRight(frame: number, start: number, dist = 32): React.CSSProperties {
  const s = rise(frame, start);
  return { opacity: s, transform: `translateX(${(1 - s) * -dist}px)` };
}

/** Linear 0 → 1 between two frames, clamped. */
export function progress(frame: number, from: number, to: number): number {
  return interpolate(frame, [from, to], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
}
