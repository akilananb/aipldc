import React from 'react';
import { useCurrentFrame } from 'remotion';
import { C, RADIUS } from '../theme';
import { ActLayout, AgentTag, AppWindow, Card, Chip, GatePanel, Scene, Tick } from '../components';
import { Icon } from '../Icon';
import { fadeRight, fadeUp, progress, rise } from '../anim';

const W: React.CSSProperties = { flex: 1 };

// ── 18–25s · Clarify ────────────────────────────────────────────────
const QA = [
  ['Which checks run first?', 'KYC, then financials, then collateral'],
  ['Which ratio thresholds apply?', 'DSCR ≥ 1.25×, leverage ≤ 3.5×'],
  ['What happens on a breach?', 'Route to a credit risk officer, never auto-reject'],
  ['Which applications need four-eyes?', 'Facilities above USD 5M'],
  ['What must be kept for audit?', 'Every check result and decision, 7 years'],
];

export const Clarify: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  const ready = frame >= 172;
  return (
    <Scene duration={duration}>
      <ActLayout active={0} step="01 · Clear requirements" title="The grill agent asks before anyone builds." fixes="Fixes: requirements surfacing late">
        <AppWindow
          title="#4412 · Clarification"
          style={W}
          right={
            ready ? (
              <Chip tone="green" icon="check" style={{ transform: `scale(${rise(frame, 172, 12)})` }}>
                Ready for story
              </Chip>
            ) : (
              <Chip tone="blue">{QA.filter((_, i) => frame < 84 + i * 18).length} open questions</Chip>
            )
          }
        >
          <AgentTag name="Grill agent" style={fadeUp(frame, 6)} />
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16, marginTop: 24 }}>
            {QA.map(([q, a], i) => {
              const answerAt = 84 + i * 18;
              const answered = frame >= answerAt;
              return (
                <div
                  key={q}
                  style={{
                    ...fadeRight(frame, 14 + i * 10),
                    display: 'flex',
                    alignItems: 'center',
                    gap: 18,
                    padding: '18px 22px',
                    borderRadius: RADIUS,
                    border: `1px solid ${answered ? C.green : C.line}`,
                    background: answered ? C.greenSoft : C.white,
                  }}
                >
                  {answered ? <Tick at={answerAt} /> : <Icon name="help" size={32} color={C.blue} />}
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: 26, fontWeight: 600 }}>{q}</div>
                    <div
                      style={{
                        fontSize: 22,
                        color: C.greenDeep,
                        marginTop: 6,
                        opacity: progress(frame, answerAt, answerAt + 8),
                        height: answered ? 'auto' : 0,
                        overflow: 'hidden',
                      }}
                    >
                      PO: {a}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </AppWindow>
      </ActLayout>
    </Scene>
  );
};

// ── 25–30s · Story + Gate 1 ─────────────────────────────────────────
export const StoryGate1: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  const active = frame >= 60 ? 2 : 1;
  return (
    <Scene duration={duration}>
      <ActLayout active={active} step="02 · Story + Gate 1" title="A testable story, approved by named people." fixes="Fixes: governance that varies by team">
        <AppWindow title="Story #4413 · v2" style={W} right={<AgentTag name="PO agent" />}>
          <Card style={{ ...fadeUp(frame, 4), padding: 24, boxShadow: 'none', background: C.grey }}>
            <div style={{ fontSize: 26, lineHeight: 1.45 }}>
              <b>As a</b> credit risk officer, <b>I get</b> pre-validated loan applications with flagged exceptions,{' '}
              <b>so that</b> I spend my time only on judgement calls.
            </div>
          </Card>
          <div style={{ marginTop: 20, display: 'flex', flexDirection: 'column', gap: 10 }}>
            {[
              'Missing documents are flagged before review',
              'DSCR < 1.25× routes to a risk officer with reasons',
              'Every check and decision is written to the audit log',
            ].map((t, i) => (
              <div key={t} style={{ ...fadeRight(frame, 12 + i * 6), display: 'flex', gap: 14, alignItems: 'center', fontSize: 22 }}>
                <Icon name="target" size={22} color={C.blue} />
                {t}
              </div>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 12, marginTop: 20, ...fadeUp(frame, 34) }}>
            <Chip tone="green" icon="check">INVEST</Chip>
            <Chip tone="green" icon="check">Definition of Ready</Chip>
            <Chip tone="blue" icon="file">Spec delta attached</Chip>
          </div>
          <GatePanel
            gate="Gate 1"
            action="Approve story"
            appearAt={56}
            passAt={118}
            style={{ marginTop: 24 }}
            signers={[
              { role: 'Product Owner', note: 'Approved v2 · intent', at: 80 },
              { role: 'Squad Lead', note: 'Approved v2 · scope', at: 104 },
            ]}
          />
        </AppWindow>
      </ActLayout>
    </Scene>
  );
};

// ── 30–38s · Plan + build loop ──────────────────────────────────────
const TASKS = [
  ['T1', 'Document completeness check', 'missing_documents_are_flagged'],
  ['T2', 'Financial ratio engine (DSCR, leverage)', 'dscr_breach_routes_to_officer'],
  ['T3', 'KYC / sanctions screening hook', 'sanctions_hit_blocks_application'],
  ['T4', 'Exception routing to risk officer', 'exception_reaches_officer_queue'],
  ['T5', 'Audit trail and decision log', 'every_decision_is_logged'],
];

export const PlanBuild: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  const tests = Math.round(progress(frame, 86, 205) * 48);
  return (
    <Scene duration={duration}>
      <ActLayout active={3} step="03 · Plan + build" title="Agents split the work and build it, test first." fixes="Every task proven by a test">
        <AppWindow
          title="#4413 · Tasks"
          style={W}
          right={
            <Chip tone={tests === 48 ? 'green' : 'blue'} icon={tests === 48 ? 'check' : 'activity'}>
              Tests {tests}/48 passing
            </Chip>
          }
        >
          <div style={{ display: 'flex', gap: 24, ...fadeUp(frame, 4) }}>
            <AgentTag name="Plan agent" />
            <AgentTag name="Build agent" style={{ opacity: progress(frame, 80, 90) }} />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14, marginTop: 24 }}>
            {TASKS.map(([id, name, test], i) => {
              const start = 86 + i * 24;
              const doneAt = start + 22;
              const running = frame >= start && frame < doneAt;
              const done = frame >= doneAt;
              return (
                <div
                  key={id}
                  style={{
                    ...fadeRight(frame, 14 + i * 10),
                    display: 'flex',
                    alignItems: 'center',
                    gap: 18,
                    padding: '16px 22px',
                    borderRadius: RADIUS,
                    border: `1px solid ${done ? C.green : running ? C.blue : C.line}`,
                    background: done ? C.greenSoft : C.white,
                  }}
                >
                  <div style={{ fontSize: 22, fontWeight: 700, color: C.blue, width: 40 }}>{id}</div>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: 24, fontWeight: 600 }}>{name}</div>
                    <div style={{ fontSize: 18, color: C.muted, marginTop: 4, fontFamily: 'ui-monospace, Menlo, monospace' }}>
                      ✓ test: {test}
                    </div>
                  </div>
                  {done ? (
                    <Chip tone="green" icon="check">Verified</Chip>
                  ) : running ? (
                    <Chip tone="blue" icon="loop">Building</Chip>
                  ) : (
                    <Chip tone="grey">Queued</Chip>
                  )}
                </div>
              );
            })}
          </div>
        </AppWindow>
      </ActLayout>
    </Scene>
  );
};

// ── 38–43s · Review + Gate 2 ────────────────────────────────────────
const Code: React.FC<{ sign: '-' | '+' | ' '; children: React.ReactNode; style?: React.CSSProperties }> = ({ sign, children, style }) => (
  <div
    style={{
      fontFamily: 'ui-monospace, Menlo, monospace',
      fontSize: 21,
      padding: '6px 16px',
      background: sign === '-' ? C.alertSoft : sign === '+' ? C.greenSoft : 'transparent',
      color: sign === '-' ? C.alert : C.navy,
      whiteSpace: 'pre',
      ...style,
    }}
  >
    {sign} {children}
  </div>
);

export const ReviewGate2: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  const fixed = frame >= 54;
  const active = frame >= 70 ? 5 : 4;
  return (
    <Scene duration={duration}>
      <ActLayout active={active} step="04 · Review + Gate 2" title="The review agent does the first pass. People decide." fixes="Fixes: manual, repetitive review">
        <AppWindow
          title="PR #218 · loan-validation"
          style={W}
          right={
            <div style={{ display: 'flex', gap: 8 }}>
              {['Build', 'Unit', 'E2E'].map((c, i) => (
                <Chip key={c} tone={frame >= 20 + i * 8 ? 'green' : 'grey'} icon={frame >= 20 + i * 8 ? 'check' : undefined}>
                  {c}
                </Chip>
              ))}
            </div>
          }
        >
          <Card style={{ ...fadeUp(frame, 2), boxShadow: 'none', overflow: 'hidden' }}>
            <div style={{ padding: '10px 16px', fontSize: 18, color: C.muted, background: C.grey, borderBottom: `1px solid ${C.line}` }}>
              src/validation/RatioCheck.java
            </div>
            <Code sign=" ">{'double leverage = debt / ebitda;'}</Code>
            <Code sign={fixed ? '-' : ' '}>{'if (leverage > 3.5) {'}</Code>
            {fixed ? (
              <Code sign="+" style={{ opacity: progress(frame, 54, 62) }}>
                {'if (leverage > policy.maxLeverage(segment)) {'}
              </Code>
            ) : null}
            <Code sign=" ">{'  return Result.routeToOfficer("LEVERAGE");'}</Code>
          </Card>
          <Card
            style={{
              ...fadeRight(frame, 14),
              marginTop: 16,
              padding: '16px 20px',
              display: 'flex',
              gap: 16,
              alignItems: 'center',
              borderLeft: `4px solid ${fixed ? C.green : C.blue}`,
            }}
          >
            <AgentTag name="Review agent" />
            <div style={{ fontSize: 21, flex: 1 }}>Leverage threshold is hard-coded. Read it from the risk policy config.</div>
            {fixed ? <Chip tone="green" icon="check">Resolved</Chip> : null}
          </Card>
          <GatePanel
            gate="Gate 2"
            action="Approve pull request"
            appearAt={66}
            passAt={124}
            style={{ marginTop: 20 }}
            signers={[
              { role: 'FS Developer', note: 'Approved · code', at: 88 },
              { role: 'QA', note: 'Approved · tests + verifier', at: 108 },
            ]}
          />
        </AppWindow>
      </ActLayout>
    </Scene>
  );
};

// ── 43–48s · Release pack + Gate 3 ──────────────────────────────────
const DOCS = [
  ['Change notes', 'What changed and why'],
  ['Rollout plan', 'Pilot: one corporate banking desk'],
  ['Monitor rules', 'Exception rate ≤ 10%'],
  ['Test evidence', '200 sample applications'],
];

export const ReleaseGate3: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  const active = frame >= 64 ? 7 : 6;
  const deploy = progress(frame, 112, 142);
  return (
    <Scene duration={duration}>
      <ActLayout active={active} step="05 · Release + Gate 3" title="The release pack writes itself. A person signs it." fixes="Audit-ready by default">
        <AppWindow title="Release 2.4.0" style={W} right={<AgentTag name="Release agent" />}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
            {DOCS.map(([t, d], i) => {
              const signAt = 26 + i * 8;
              return (
                <Card key={t} style={{ ...fadeUp(frame, 4 + i * 5), padding: '16px 20px', display: 'flex', gap: 16, alignItems: 'center' }}>
                  <Icon name="file" size={30} color={C.blue} />
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: 22, fontWeight: 700 }}>{t}</div>
                    <div style={{ fontSize: 18, color: C.muted, marginTop: 2 }}>{d}</div>
                  </div>
                  {frame >= signAt ? <Tick at={signAt} size={28} /> : null}
                </Card>
              );
            })}
          </div>
          <GatePanel
            gate="Gate 3"
            action="Approve release"
            appearAt={60}
            passAt={100}
            style={{ marginTop: 20 }}
            signers={[{ role: 'Squad Lead', note: 'Approved · release 2.4.0', at: 86 }]}
          />
          <Card style={{ ...fadeUp(frame, 108), marginTop: 20, padding: '18px 22px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 14, fontSize: 22, fontWeight: 600 }}>
              <Icon name="send" size={24} color={C.blue} />
              {deploy < 1 ? 'Deploying to pilot desk…' : 'Live on pilot desk'}
              <span style={{ marginLeft: 'auto', color: deploy < 1 ? C.blue : C.greenDeep }}>{Math.round(deploy * 100)}%</span>
            </div>
            <div style={{ height: 8, background: C.grey, borderRadius: 4, marginTop: 12, overflow: 'hidden' }}>
              <div style={{ height: '100%', width: `${deploy * 100}%`, background: deploy < 1 ? C.blue : C.green }} />
            </div>
          </Card>
        </AppWindow>
      </ActLayout>
    </Scene>
  );
};

// ── 48–52s · Monitor closes the loop ────────────────────────────────
const POINTS = [5, 6, 5.5, 6.5, 6, 7, 8, 9.5, 11.5, 14, 16.5, 18];

export const Monitor: React.FC<{ duration: number }> = ({ duration }) => {
  const frame = useCurrentFrame();
  const cw = 1040;
  const ch = 300;
  const max = 20;
  const x = (i: number) => (i / (POINTS.length - 1)) * cw;
  const y = (v: number) => ch - (v / max) * ch;
  const d = POINTS.map((v, i) => `${i ? 'L' : 'M'}${x(i)},${y(v)}`).join(' ');
  const draw = progress(frame, 6, 52);
  const tripped = frame >= 50;
  return (
    <Scene duration={duration}>
      <ActLayout active={8} step="06 · Monitor" title="Production evidence goes straight back to the board." fixes="Fixes: slow loop from production">
        <AppWindow
          title="Monitor · loan-validation"
          style={W}
          right={tripped ? <Chip tone="alert" icon="alert">Rule tripped · 18%</Chip> : <AgentTag name="Monitor agent" />}
        >
          <div style={{ fontSize: 22, color: C.muted }}>Exception rate · SME segment · last 24h</div>
          <svg width={cw} height={ch + 20} style={{ marginTop: 16, overflow: 'visible' }}>
            <line x1={0} x2={cw} y1={y(10)} y2={y(10)} stroke={C.alert} strokeWidth={2} strokeDasharray="8 8" />
            <text x={cw} y={y(10) - 10} textAnchor="end" fontSize={18} fill={C.alert} fontFamily="Inter">
              threshold 10%
            </text>
            <path d={d} fill="none" stroke={C.blue} strokeWidth={4} pathLength={1} strokeDasharray={1} strokeDashoffset={1 - draw} strokeLinejoin="round" />
            {tripped ? (
              <circle cx={x(POINTS.length - 1)} cy={y(18)} r={8 + 10 * ((frame - 50) % 20) / 20} fill="none" stroke={C.alert} strokeWidth={2} opacity={1 - ((frame - 50) % 20) / 20} />
            ) : null}
            {tripped ? <circle cx={x(POINTS.length - 1)} cy={y(18)} r={8} fill={C.alert} /> : null}
          </svg>
          <Card style={{ ...fadeRight(frame, 62, 60), marginTop: 28, padding: '20px 24px', display: 'flex', gap: 18, alignItems: 'center', borderLeft: `4px solid ${C.blue}` }}>
            <Chip tone="blue">#4420</Chip>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 26, fontWeight: 700 }}>Review DSCR rule for SME segment</div>
              <div style={{ fontSize: 19, color: C.muted, marginTop: 4 }}>Filed by monitor agent · evidence attached · linked to #4412</div>
            </div>
            <Chip tone="green" icon="loop">Back to intake</Chip>
          </Card>
        </AppWindow>
      </ActLayout>
    </Scene>
  );
};
