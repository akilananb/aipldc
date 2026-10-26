import React from 'react';
import { AbsoluteFill, useCurrentFrame } from 'remotion';
import { C } from '../theme';
import { Card, Chip, Scene } from '../components';
import { Icon, IconName } from '../Icon';
import { fadeRight, fadeUp, progress, rise } from '../anim';

export const FEATURE = 'Automate corporate loan application validation';

/** 0–4s: one-line card on the board. */
export const Hook: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  const chars = Math.round(progress(frame, 14, 62) * FEATURE.length);
  const cursorOn = Math.floor(frame / 8) % 2 === 0 && chars < FEATURE.length;
  return (
    <Scene duration={duration} bg={C.grey}>
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', gap: 56 }}>
        <Card style={{ width: 1100, padding: '40px 48px', transform: `scale(${0.94 + rise(frame, 0) * 0.06})` }}>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <Chip tone="blue">#4412</Chip>
            <Chip tone="grey">Feature · New</Chip>
            <div style={{ marginLeft: 'auto', fontSize: 20, color: C.muted }}>Corporate Banking · Credit</div>
          </div>
          <div style={{ fontSize: 48, fontWeight: 700, marginTop: 28, minHeight: 60 }}>
            {FEATURE.slice(0, chars)}
            <span style={{ opacity: cursorOn ? 1 : 0, color: C.blue }}>|</span>
          </div>
          <div style={{ fontSize: 22, color: C.muted, marginTop: 16 }}>No description · No acceptance criteria</div>
        </Card>
        <div style={{ ...fadeUp(frame, 70), fontSize: 40, fontWeight: 500, color: C.muted }}>
          Every feature starts as <span style={{ color: C.navy, fontWeight: 700 }}>one line.</span>
        </div>
      </AbsoluteFill>
    </Scene>
  );
};

const PAINS: { icon: IconName; title: string; detail: string }[] = [
  {
    icon: 'help',
    title: 'Requirements surface late',
    detail: '“What’s the DSCR threshold?” gets asked in PR review, three sprints after intake.',
  },
  {
    icon: 'users',
    title: 'Governance depends on the team',
    detail: '“Who approved this, on which version of the spec?” takes days to rebuild for audit.',
  },
  {
    icon: 'clock',
    title: 'Review is manual and repetitive',
    detail: 'Senior engineers spend their time restating what the tests already prove.',
  },
  {
    icon: 'loop',
    title: 'Production issues return slowly',
    detail: 'An incident becomes a postmortem, and weeks later maybe a backlog card.',
  },
];

/** 4–10s: four delivery pain points. */
export const Pains: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  return (
    <Scene duration={duration}>
      <AbsoluteFill style={{ padding: '100px 160px', justifyContent: 'center' }}>
        <div style={{ ...fadeUp(frame, 0), fontSize: 24, fontWeight: 700, letterSpacing: 2, color: C.blue }}>
          THE PROBLEM
        </div>
        <div style={{ ...fadeUp(frame, 4), fontSize: 56, fontWeight: 700, marginTop: 16 }}>
          How bank software gets delivered today
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 32, marginTop: 64 }}>
          {PAINS.map((p, i) => (
            <Card key={p.title} style={{ ...fadeRight(frame, 18 + i * 26), padding: 36, display: 'flex', gap: 28 }}>
              <div
                style={{
                  width: 72,
                  height: 72,
                  flex: 'none',
                  borderRadius: 36,
                  background: C.alertSoft,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <Icon name={p.icon} size={36} color={C.alert} />
              </div>
              <div>
                <div style={{ fontSize: 32, fontWeight: 700 }}>{p.title}</div>
                <div style={{ fontSize: 24, color: C.muted, marginTop: 12, lineHeight: 1.4 }}>{p.detail}</div>
              </div>
            </Card>
          ))}
        </div>
      </AbsoluteFill>
    </Scene>
  );
};

/** 10–14s: the hook line. */
export const Bottleneck: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  const bar = progress(frame, 20, 50);
  return (
    <Scene duration={duration} bg={C.navy}>
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center', textAlign: 'center', color: C.white }}>
        <div style={{ ...fadeUp(frame, 2), fontSize: 84, fontWeight: 700, lineHeight: 1.1 }}>
          The bottleneck was never
          <br />
          typing speed.
        </div>
        <div style={{ width: 240 * bar, height: 6, background: C.green, borderRadius: 3, marginTop: 40 }} />
        <div style={{ ...fadeUp(frame, 40), fontSize: 36, marginTop: 40, color: '#C9D8E8', maxWidth: 1200, lineHeight: 1.4 }}>
          AI coding assistants alone just ship unclear requirements faster.
        </div>
      </AbsoluteFill>
    </Scene>
  );
};
