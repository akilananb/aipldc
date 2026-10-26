import React from 'react';
import { AbsoluteFill, useCurrentFrame } from 'remotion';
import { C, RADIUS } from '../theme';
import { Scene, STAGES } from '../components';
import { Icon } from '../Icon';
import { fadeUp, progress, rise } from '../anim';

const GATES = new Set([2, 5, 7]);

/** 14–18s: AI-Powered Software Delivery introduced, pipeline draws itself. */
export const Answer: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  const loop = progress(frame, 78, 100);
  return (
    <Scene duration={duration}>
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ ...fadeUp(frame, 0), display: 'flex', alignItems: 'baseline', gap: 20 }}>
          <div style={{ fontSize: 96, fontWeight: 800, color: C.blue, letterSpacing: -2 }}>AI-Powered Software Delivery</div>
        </div>
        <div style={{ ...fadeUp(frame, 10), fontSize: 40, fontWeight: 500, marginTop: 8 }}>
          Agents do the work. <span style={{ fontWeight: 700, color: C.greenDeep }}>Named humans make the decisions.</span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', marginTop: 90, width: 1720 }}>
          {STAGES.map((s, i) => {
            const gate = GATES.has(i);
            const st = rise(frame, 26 + i * 5, 16);
            return (
              <React.Fragment key={s}>
                <div
                  style={{
                    opacity: st,
                    transform: `scale(${0.6 + st * 0.4})`,
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 12,
                    width: 140,
                  }}
                >
                  <div
                    style={{
                      width: 72,
                      height: 72,
                      borderRadius: gate ? 36 : RADIUS,
                      background: gate ? C.green : C.blueSoft,
                      border: `2px solid ${gate ? C.greenDeep : C.blue}`,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    <Icon name={gate ? 'shield' : 'bot'} size={34} color={gate ? C.navy : C.blue} />
                  </div>
                  <div style={{ fontSize: 20, fontWeight: gate ? 700 : 500, whiteSpace: 'nowrap' }}>{s}</div>
                </div>
                {i < STAGES.length - 1 ? (
                  <div style={{ flex: 1, height: 2, marginBottom: 34, background: C.line, position: 'relative' }}>
                    <div
                      style={{
                        position: 'absolute',
                        inset: 0,
                        background: C.blue,
                        transformOrigin: 'left',
                        transform: `scaleX(${progress(frame, 30 + i * 5, 36 + i * 5)})`,
                      }}
                    />
                  </div>
                ) : null}
              </React.Fragment>
            );
          })}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginTop: 40, opacity: loop, fontSize: 24, color: C.muted }}>
          <Icon name="loop" size={28} color={C.blue} />
          Monitor evidence feeds straight back to the board
        </div>
        <div style={{ display: 'flex', gap: 40, marginTop: 28, opacity: loop, fontSize: 20, color: C.muted }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span style={{ width: 18, height: 18, borderRadius: 4, background: C.blueSoft, border: `2px solid ${C.blue}` }} /> 7 AI agents
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span style={{ width: 18, height: 18, borderRadius: 9, background: C.green, border: `2px solid ${C.greenDeep}` }} /> 3 human gates
          </span>
        </div>
      </AbsoluteFill>
    </Scene>
  );
};
