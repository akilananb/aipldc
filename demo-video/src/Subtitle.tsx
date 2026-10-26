import React from 'react';
import { useCurrentFrame } from 'remotion';
import { C, FONT } from './theme';
import { Icon, IconName } from './Icon';
import { progress, rise } from './anim';
import { STAGES } from './components';

const GATES = new Set([2, 5, 7]);

/**
 * Lower-third subtitle: step badge + caption revealed word by word.
 * Wrap words in **double asterisks** to highlight them in brand green.
 */
export const Subtitle: React.FC<{
  icon: IconName;
  label: string;
  text: string;
  /** Stage index (0..8) for the mini pipeline; omit to hide it. */
  stage?: number;
  duration: number;
  at?: number;
}> = ({ icon, label, text, stage, duration, at = 6 }) => {
  const frame = useCurrentFrame();
  const enter = rise(frame, at);
  const tokens = parse(text);
  const perWord = 3;
  const gate = stage !== undefined && GATES.has(stage);

  return (
    <div
      style={{
        position: 'absolute',
        left: 80,
        right: 80,
        bottom: 36,
        height: 132,
        borderRadius: 16,
        background: C.navy,
        color: C.white,
        fontFamily: FONT,
        display: 'flex',
        alignItems: 'center',
        gap: 28,
        padding: '0 36px',
        overflow: 'hidden',
        boxShadow: '0 12px 30px rgba(0, 51, 102, 0.3)',
        opacity: enter,
        transform: `translateY(${(1 - enter) * 40}px)`,
      }}
    >
      <div
        style={{
          width: 72,
          height: 72,
          flex: 'none',
          borderRadius: gate ? 36 : 14,
          background: gate ? C.green : C.blue,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          transform: `scale(${rise(frame, at + 4, 12)})`,
        }}
      >
        <Icon name={icon} size={38} color={gate ? C.navy : C.white} stroke={2} />
      </div>
      <div style={{ flex: 'none', width: 190 }}>
        <div style={{ fontSize: 18, fontWeight: 700, letterSpacing: 2, color: C.green, textTransform: 'uppercase' }}>
          {label}
        </div>
        {stage !== undefined ? (
          <div style={{ display: 'flex', gap: 5, marginTop: 12 }}>
            {STAGES.map((s, i) => (
              <div
                key={s}
                style={{
                  width: GATES.has(i) ? 14 : 10,
                  height: 10,
                  borderRadius: 5,
                  background: i === stage ? C.green : i < stage ? '#5C8DB8' : 'rgba(255,255,255,0.18)',
                }}
              />
            ))}
          </div>
        ) : null}
      </div>
      <div style={{ width: 2, alignSelf: 'stretch', margin: '28px 0', background: 'rgba(255,255,255,0.15)' }} />
      <div style={{ fontSize: 40, fontWeight: 600, lineHeight: 1.25, flex: 1 }}>
        {tokens.map((t, i) => {
          const o = progress(frame, at + 8 + i * perWord, at + 14 + i * perWord);
          return (
            <span
              key={i}
              style={{
                opacity: o,
                display: 'inline-block',
                transform: `translateY(${(1 - o) * 12}px)`,
                color: t.hi ? C.green : C.white,
                fontWeight: t.hi ? 800 : 600,
                marginRight: 11,
              }}
            >
              {t.w}
            </span>
          );
        })}
      </div>
      <div
        style={{
          position: 'absolute',
          left: 0,
          bottom: 0,
          height: 5,
          width: `${progress(frame, 0, duration) * 100}%`,
          background: C.green,
        }}
      />
    </div>
  );
};

function parse(text: string): { w: string; hi: boolean }[] {
  const out: { w: string; hi: boolean }[] = [];
  text.split(/(\*\*[^*]+\*\*)/).forEach((part) => {
    const hi = part.startsWith('**');
    part
      .replace(/\*\*/g, '')
      .split(/\s+/)
      .filter(Boolean)
      .forEach((w) => out.push({ w, hi }));
  });
  return out;
}
