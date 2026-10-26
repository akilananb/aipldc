import React from 'react';
import { AbsoluteFill, interpolate, useCurrentFrame } from 'remotion';
import { C, FONT, RADIUS, SHADOW } from './theme';
import { Icon, IconName } from './Icon';
import { fadeUp, rise } from './anim';

/** Full-frame scene with a short fade in and out. */
export const Scene: React.FC<{
  duration: number;
  bg?: string;
  children: React.ReactNode;
}> = ({ duration, bg = C.white, children }) => {
  const frame = useCurrentFrame();
  const opacity = interpolate(frame, [0, 8, duration - 8, duration], [0, 1, 1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  return (
    <AbsoluteFill style={{ background: bg, fontFamily: FONT, color: C.navy }}>
      <AbsoluteFill style={{ opacity }}>{children}</AbsoluteFill>
    </AbsoluteFill>
  );
};

export const Card: React.FC<{ style?: React.CSSProperties; children: React.ReactNode }> = ({
  style,
  children,
}) => (
  <div
    style={{
      background: C.white,
      borderRadius: RADIUS,
      boxShadow: SHADOW,
      border: `1px solid ${C.line}`,
      ...style,
    }}
  >
    {children}
  </div>
);

export const Chip: React.FC<{
  tone?: 'blue' | 'green' | 'grey' | 'alert';
  icon?: IconName;
  style?: React.CSSProperties;
  children: React.ReactNode;
}> = ({ tone = 'grey', icon, style, children }) => {
  const tones = {
    blue: { bg: C.blueSoft, fg: C.blue },
    green: { bg: C.greenSoft, fg: C.greenDeep },
    grey: { bg: C.grey, fg: C.muted },
    alert: { bg: C.alertSoft, fg: C.alert },
  }[tone];
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 8,
        padding: '6px 14px',
        borderRadius: 999,
        background: tones.bg,
        color: tones.fg,
        fontSize: 20,
        fontWeight: 600,
        whiteSpace: 'nowrap',
        ...style,
      }}
    >
      {icon ? <Icon name={icon} size={18} color={tones.fg} stroke={2} /> : null}
      {children}
    </span>
  );
};

/** Round tick that pops in at `at`. */
export const Tick: React.FC<{ at: number; size?: number }> = ({ at, size = 32 }) => {
  const frame = useCurrentFrame();
  const s = rise(frame, at, 14);
  return (
    <div
      style={{
        width: size,
        height: size,
        flex: 'none',
        borderRadius: '50%',
        background: C.green,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        transform: `scale(${s})`,
      }}
    >
      <Icon name="check" size={size * 0.6} color={C.navy} stroke={2.5} />
    </div>
  );
};

/** Mock product window, so scenes read as "the app". */
export const AppWindow: React.FC<{
  title: string;
  right?: React.ReactNode;
  style?: React.CSSProperties;
  children: React.ReactNode;
}> = ({ title, right, style, children }) => (
  <Card style={{ overflow: 'hidden', display: 'flex', flexDirection: 'column', ...style }}>
    <div
      style={{
        height: 64,
        flex: 'none',
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        padding: '0 28px',
        borderBottom: `1px solid ${C.line}`,
        background: C.grey,
      }}
    >
      <div style={{ display: 'flex', gap: 8 }}>
        {[0, 1, 2].map((i) => (
          <div key={i} style={{ width: 12, height: 12, borderRadius: 6, background: '#D5DCE4' }} />
        ))}
      </div>
      <div style={{ fontWeight: 700, fontSize: 20, color: C.blue, marginLeft: 8 }}>eLoop.ai</div>
      <div style={{ fontSize: 20, color: C.muted }}>/ {title}</div>
      <div style={{ marginLeft: 'auto' }}>{right}</div>
    </div>
    <div style={{ flex: 1, padding: 32, position: 'relative' }}>{children}</div>
  </Card>
);

export const STAGES = [
  'Clarify',
  'Story',
  'Gate 1',
  'Plan & build',
  'Review',
  'Gate 2',
  'Release',
  'Gate 3',
  'Monitor',
];
const GATES = new Set([2, 5, 7]);

/** Pipeline progress strip across the top of the walkthrough scenes. */
export const Stepper: React.FC<{ active: number }> = ({ active }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
    {STAGES.map((s, i) => {
      const done = i < active;
      const on = i === active;
      const gate = GATES.has(i);
      const bg = on ? (gate ? C.green : C.blue) : done ? C.blueSoft : C.white;
      const fg = on ? (gate ? C.navy : C.white) : done ? C.blue : C.muted;
      return (
        <React.Fragment key={s}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '8px 16px',
              borderRadius: gate ? 999 : 6,
              background: bg,
              color: fg,
              border: `1px solid ${on ? bg : C.line}`,
              fontSize: 18,
              fontWeight: on ? 700 : 500,
              whiteSpace: 'nowrap',
            }}
          >
            {gate ? <Icon name="shield" size={16} color={fg} stroke={2} /> : null}
            {s}
          </div>
          {i < STAGES.length - 1 ? (
            <div style={{ flex: 1, height: 2, background: done ? C.blue : C.line }} />
          ) : null}
        </React.Fragment>
      );
    })}
  </div>
);

/**
 * Walkthrough layout: stepper on top, caption column on the left,
 * product window on the right.
 */
export const ActLayout: React.FC<{
  active: number;
  step: string;
  title: string;
  fixes: string;
  children: React.ReactNode;
}> = ({ active, step, title, fixes, children }) => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{ padding: '56px 80px 64px', background: C.grey }}>
      <Stepper active={active} />
      <div style={{ display: 'flex', gap: 64, flex: 1, marginTop: 48, minHeight: 0 }}>
        <div style={{ width: 520, flex: 'none', paddingTop: 24 }}>
          <div
            style={{
              ...fadeUp(frame, 4),
              fontSize: 22,
              fontWeight: 700,
              letterSpacing: 2,
              color: C.blue,
              textTransform: 'uppercase',
            }}
          >
            {step}
          </div>
          <div
            style={{
              ...fadeUp(frame, 10),
              fontSize: 52,
              fontWeight: 700,
              lineHeight: 1.15,
              marginTop: 20,
              color: C.navy,
            }}
          >
            {title}
          </div>
          <div style={{ ...fadeUp(frame, 22), marginTop: 36 }}>
            <Chip tone="green" icon="check">
              {fixes}
            </Chip>
          </div>
        </div>
        <div style={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'flex-start' }}>{children}</div>
      </div>
    </AbsoluteFill>
  );
};

/** One approver line in a gate panel. */
export const Signer: React.FC<{ role: string; note: string; at: number }> = ({ role, note, at }) => {
  const frame = useCurrentFrame();
  const signed = frame >= at;
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        padding: '14px 18px',
        borderRadius: RADIUS,
        border: `1px solid ${signed ? C.green : C.line}`,
        background: signed ? C.greenSoft : C.white,
      }}
    >
      {signed ? (
        <Tick at={at} size={30} />
      ) : (
        <div style={{ width: 30, height: 30, borderRadius: 15, border: `2px dashed ${C.line}` }} />
      )}
      <div style={{ fontSize: 22, fontWeight: 600 }}>{role}</div>
      <div style={{ marginLeft: 'auto', fontSize: 18, color: signed ? C.greenDeep : C.muted }}>
        {signed ? note : 'Awaiting approval'}
      </div>
    </div>
  );
};

/** Gate panel: title + approver rows + "passed" banner. */
export const GatePanel: React.FC<{
  gate: string;
  action: string;
  signers: { role: string; note: string; at: number }[];
  passAt: number;
  appearAt: number;
  style?: React.CSSProperties;
}> = ({ gate, action, signers, passAt, appearAt, style }) => {
  const frame = useCurrentFrame();
  const passed = frame >= passAt;
  return (
    <Card style={{ padding: 24, ...fadeUp(frame, appearAt), ...style }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Icon name="shield" size={26} color={C.blue} />
        <div style={{ fontSize: 24, fontWeight: 700 }}>{gate}</div>
        <div style={{ fontSize: 20, color: C.muted }}>· {action}</div>
        <div style={{ marginLeft: 'auto' }}>
          {passed ? (
            <Chip tone="green" icon="check" style={{ transform: `scale(${rise(frame, passAt, 12)})` }}>
              Gate passed
            </Chip>
          ) : (
            <Chip tone="grey">Maker-checker</Chip>
          )}
        </div>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {signers.map((s) => (
          <Signer key={s.role} {...s} />
        ))}
      </div>
    </Card>
  );
};

export const AgentTag: React.FC<{ name: string; style?: React.CSSProperties }> = ({ name, style }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 10, ...style }}>
    <div
      style={{
        width: 36,
        height: 36,
        borderRadius: 18,
        background: C.blueSoft,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Icon name="bot" size={20} color={C.blue} />
    </div>
    <div style={{ fontSize: 20, fontWeight: 600, color: C.blue }}>{name}</div>
  </div>
);
