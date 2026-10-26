import React from 'react';
import { AbsoluteFill, useCurrentFrame } from 'remotion';
import { FONT } from '../theme';
import { Icon, IconName } from '../Icon';
import { fadeRight, fadeUp, progress, rise } from '../anim';

// Opening slide, matching the Enterprise AI Day deck: dark navy, teal accents.
const BG = '#0B2140';
const BG2 = '#123A63';
const TEAL = '#3ED6C8';
const INK = '#EAF2FA';
const SOFT = '#A9C2DA';

const SERVICES: { icon: IconName; name: string }[] = [
  { icon: 'users', name: 'Customer services' },
  { icon: 'shield', name: 'Risk & compliance' },
  { icon: 'send', name: 'Payments & flow' },
  { icon: 'activity', name: 'Data & insights' },
  { icon: 'pr', name: 'Integration services' },
  { icon: 'layers', name: 'Foundation services' },
];

const BARS = [22, 30, 26, 38, 34, 48, 44, 58, 54, 70, 66, 84];

/** Isometric cube outline. */
const Cube: React.FC<{ x: number; y: number; s?: number; o: number }> = ({ x, y, s = 34, o }) => {
  const h = s * 0.58;
  return (
    <g transform={`translate(${x} ${y}) scale(${0.6 + 0.4 * o})`} opacity={o} fill="none" stroke={TEAL} strokeWidth={2}>
      <path d={`M0 ${-s} L${s * 0.87} ${-h} L${s * 0.87} ${h} L0 ${s} L${-s * 0.87} ${h} L${-s * 0.87} ${-h} Z`} fill="rgba(62,214,200,0.10)" />
      <path d={`M${-s * 0.87} ${-h} L0 0 L${s * 0.87} ${-h} M0 0 L0 ${s}`} />
    </g>
  );
};

export const TitleSlide: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  const out = 1 - progress(frame, duration - 10, duration);
  const bar = progress(frame, 0, 18);

  return (
    <AbsoluteFill style={{ background: `linear-gradient(120deg, ${BG} 0%, ${BG} 55%, ${BG2} 100%)`, fontFamily: FONT, opacity: out }}>
      {/* Left: title block */}
      <div style={{ position: 'absolute', left: 150, top: 220, bottom: 290, width: 8, background: TEAL, transformOrigin: 'top', transform: `scaleY(${bar})` }} />
      <div style={{ position: 'absolute', left: 210, top: 230, width: 900, color: INK }}>
        <div style={{ ...fadeUp(frame, 6), fontSize: 80, fontWeight: 800, lineHeight: 1.08, letterSpacing: -1, whiteSpace: 'nowrap' }}>
          AI-POWERED
          <br />
          SOFTWARE DELIVERY
        </div>
        <div style={{ ...fadeUp(frame, 18), fontSize: 38, fontWeight: 500, color: SOFT, marginTop: 56, lineHeight: 1.35 }}>
          From legacy complexity to intelligent, agentic engineering
        </div>
        <div
          style={{
            ...fadeUp(frame, 28),
            marginTop: 56,
            display: 'inline-block',
            padding: '20px 44px',
            borderRadius: 10,
            border: `2px solid ${TEAL}`,
            background: 'rgba(62,214,200,0.10)',
            fontSize: 32,
            fontWeight: 700,
          }}
        >
          CAI × SPM <span style={{ color: TEAL, margin: '0 14px' }}>|</span> Loop Engineering in action
        </div>
        <div style={{ ...fadeUp(frame, 36), marginTop: 44, fontSize: 28, color: SOFT, display: 'flex', gap: 18 }}>
          {['Journey', 'Case study', 'Outcomes', 'Way forward'].map((t, i) => (
            <React.Fragment key={t}>
              {i ? <span style={{ color: TEAL }}>•</span> : null}
              <span>{t}</span>
            </React.Fragment>
          ))}
        </div>
      </div>
      <div style={{ ...fadeUp(frame, 40), position: 'absolute', left: 150, bottom: 90, fontSize: 24, fontWeight: 700, letterSpacing: 4, color: SOFT }}>
        ENTERPRISE AI DAY <span style={{ color: TEAL, margin: '0 12px' }}>|</span> 28 OCTOBER 2026
      </div>

      {/* Right: modular services + outcomes panel */}
      <div style={{ position: 'absolute', left: 1110, top: 130, right: 0, bottom: 120 }}>
        <div style={{ ...fadeUp(frame, 10), fontSize: 20, fontWeight: 700, letterSpacing: 3, color: SOFT }}>MODULAR DIGITAL SERVICES</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 22, marginTop: 30 }}>
          {SERVICES.map((s, i) => (
            <div
              key={s.name}
              style={{
                ...fadeRight(frame, 14 + i * 5),
                width: 340,
                height: 84,
                display: 'flex',
                alignItems: 'center',
                gap: 20,
                padding: '0 22px',
                background: 'linear-gradient(90deg, rgba(62,214,200,0.16), rgba(18,58,99,0.6))',
                border: '1px solid rgba(62,214,200,0.45)',
                clipPath: 'polygon(0 0, 92% 0, 100% 50%, 92% 100%, 0 100%)',
                color: INK,
                fontSize: 17,
                fontWeight: 600,
                whiteSpace: 'nowrap',
                letterSpacing: 1,
                textTransform: 'uppercase',
              }}
            >
              <Icon name={s.icon} size={34} color={TEAL} />
              {s.name}
            </div>
          ))}
        </div>
        <svg width={150} height={700} style={{ position: 'absolute', left: 350, top: 54, overflow: 'visible' }}>
          {SERVICES.map((_, i) => {
            const y = 42 + i * 106;
            const cx = i % 2 ? 110 : 55;
            const o = rise(frame, 30 + i * 5, 16);
            return (
              <g key={i}>
                <line x1={0} y1={y} x2={cx - 30} y2={y} stroke={TEAL} strokeWidth={1.5} opacity={0.6 * o} />
                {i < SERVICES.length - 1 ? (
                  <line x1={cx} y1={y} x2={(i + 1) % 2 ? 110 : 55} y2={y + 106} stroke={TEAL} strokeWidth={1.5} opacity={0.5 * o} />
                ) : null}
                <circle cx={cx - 30} cy={y} r={4} fill={TEAL} opacity={o} />
                <Cube x={cx} y={y} s={26} o={o} />
              </g>
            );
          })}
        </svg>
        <div
          style={{
            ...fadeUp(frame, 34),
            position: 'absolute',
            left: 520,
            top: 0,
            width: 290,
            padding: 22,
            border: '1px solid rgba(169,194,218,0.25)',
            borderRadius: 12,
            background: 'rgba(11,33,64,0.55)',
          }}
        >
          <div style={{ fontSize: 16, fontWeight: 700, letterSpacing: 2, color: SOFT }}>REAL BUSINESS OUTCOMES</div>
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, height: 220, marginTop: 24 }}>
            {BARS.map((b, i) => (
              <div
                key={i}
                style={{
                  flex: 1,
                  height: `${b * progress(frame, 36 + i * 2, 56 + i * 2)}%`,
                  background: i > 8 ? TEAL : 'rgba(62,214,200,0.45)',
                  borderRadius: 3,
                }}
              />
            ))}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 18, marginTop: 34 }}>
            {[
              ['activity', 'Faster delivery'],
              ['shield', 'Greater resilience'],
              ['target', 'Higher efficiency'],
              ['layers', 'Scalable by design'],
            ].map(([ic, t], i) => (
              <div key={t} style={{ ...fadeRight(frame, 46 + i * 4), display: 'flex', alignItems: 'center', gap: 12, fontSize: 17, color: INK, letterSpacing: 1, textTransform: 'uppercase' }}>
                <Icon name={ic as IconName} size={26} color={TEAL} />
                {t}
              </div>
            ))}
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};
