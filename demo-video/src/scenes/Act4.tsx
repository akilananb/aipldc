import React from 'react';
import { AbsoluteFill, useCurrentFrame } from 'remotion';
import { C } from '../theme';
import { Card, Scene } from '../components';
import { Icon, IconName } from '../Icon';
import { fadeUp, progress } from '../anim';

const OUTCOMES: { icon: IconName; title: string; detail: string }[] = [
  { icon: 'target', title: 'Clear requirements before a line of code', detail: 'Every question answered or parked at intake.' },
  { icon: 'shield', title: 'Maker-checker on every feature', detail: 'Agents make. Named humans sign. Every version recorded.' },
  { icon: 'layers', title: 'Delivered complete', detail: 'Built, tested, released and monitored, in one flow.' },
];

/** 52–57s: outcome tiles. */
export const Outcomes: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  return (
    <Scene duration={duration} bg={C.grey}>
      <AbsoluteFill style={{ padding: '120px 140px', justifyContent: 'center' }}>
        <div style={{ ...fadeUp(frame, 0), fontSize: 24, fontWeight: 700, letterSpacing: 2, color: C.blue }}>THE OUTCOME</div>
        <div style={{ ...fadeUp(frame, 4), fontSize: 60, fontWeight: 700, marginTop: 16 }}>One feature. Idea to production. Governed.</div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 32, marginTop: 72 }}>
          {OUTCOMES.map((o, i) => (
            <Card key={o.title} style={{ ...fadeUp(frame, 18 + i * 14, 40), padding: 44, minHeight: 340 }}>
              <div
                style={{
                  width: 80,
                  height: 80,
                  borderRadius: 40,
                  background: C.green,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <Icon name={o.icon} size={40} color={C.navy} />
              </div>
              <div style={{ fontSize: 36, fontWeight: 700, marginTop: 32, lineHeight: 1.2 }}>{o.title}</div>
              <div style={{ fontSize: 24, color: C.muted, marginTop: 16, lineHeight: 1.4 }}>{o.detail}</div>
            </Card>
          ))}
        </div>
      </AbsoluteFill>
    </Scene>
  );
};

/** 57–60s: end card. */
export const EndCard: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  return (
    <Scene duration={duration}>
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ ...fadeUp(frame, 0), fontSize: 104, fontWeight: 800, color: C.blue, letterSpacing: -2, textAlign: 'center', lineHeight: 1.05 }}>AI-Powered<br />Software Delivery</div>
        <div style={{ width: 320 * progress(frame, 8, 30), height: 8, borderRadius: 4, background: C.green, marginTop: 12 }} />
        <div style={{ ...fadeUp(frame, 14), fontSize: 44, fontWeight: 500, marginTop: 36 }}>From idea to production, governed.</div>
      </AbsoluteFill>
    </Scene>
  );
};
