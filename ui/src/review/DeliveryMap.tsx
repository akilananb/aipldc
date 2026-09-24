import {
  BarChart2,
  Bell,
  CheckCircle2,
  Clock,
  Code2,
  Eye,
  FileText,
  Flag,
  Hammer,
  ListChecks,
  Lock,
  MessageSquare,
  Package,
  RefreshCw,
  ShieldCheck,
  TrendingUp,
  Truck,
  Users,
  type LucideIcon,
} from 'lucide-react';
import { useProjectGates } from '../useProject';
import type { ItemDetail } from '../types';
import { currentRank, PHASE_LABEL, phaseOf, RANK, signerStatus, SIGNER_DOCS, stageSubtitle, statusOf, type LaneStatus } from './pipeline';

interface Props {
  item: ItemDetail;
}

function Card({
  icon: Icon,
  title,
  subtitle,
  current,
}: {
  icon: LucideIcon;
  title: string;
  subtitle: string;
  current?: boolean;
}) {
  return (
    <div className={`step-card${current ? ' current' : ''}`}>
      <Icon className="step-icon" size={22} strokeWidth={1.75} />
      <strong>{title}</strong>
      <span>{subtitle}</span>
    </div>
  );
}

function Arrow() {
  return (
    <span className="step-arrow" aria-hidden="true">
      {'\u2192'}
    </span>
  );
}

function LoopArc({ cards, from, to, children }: { cards: number; from: number; to: number; children: string }) {
  const left = `${((from + 0.5) / cards) * 100}%`;
  const right = `${((cards - to - 0.5) / cards) * 100}%`;
  return (
    <div className="loop">
      <span className="loop-arc" style={{ left, right }} aria-hidden="true" />
      <span className="loop-text">{children}</span>
    </div>
  );
}

const GATE_PILL: Record<LaneStatus, { icon: LucideIcon; label: string }> = {
  done: { icon: CheckCircle2, label: 'Passed' },
  current: { icon: Clock, label: 'In review' },
  pending: { icon: Lock, label: 'Pending' },
};

function GateRow({ status, title = 'Gate 1', subtitle = 'Release readiness check' }: { status: LaneStatus; title?: string; subtitle?: string }) {
  const { icon: Icon, label } = GATE_PILL[status];
  return (
    <div className="gate-row">
      <Flag size={20} strokeWidth={1.75} />
      <div className="gate-text">
        <b>{title}</b>
        <span>{subtitle}</span>
      </div>
      <span className={`gate-pill ${status}`}>
        <Icon size={13} /> {label}
      </span>
    </div>
  );
}

function phaseClass(n: number, statuses: LaneStatus[]): string {
  const base = statuses.some((s) => s === 'current') ? 'phase active' : statuses.every((s) => s === 'pending') ? 'phase locked' : 'phase';
  return `${base} phase-c${n}`;
}

const GATE_STAGE_LABEL: Record<string, string> = {
  'awaiting-G1': 'Gate 1',
  'awaiting-G2': 'Gate 2',
  'awaiting-G3': 'Gate 3',
};

const SIGN_ICON: Record<string, LucideIcon> = {
  'change-notes': Bell,
  'rollout-plan': Users,
  'monitor-rules': Eye,
  'test-evidence': FileText,
};

export default function DeliveryMap({ item }: Props) {
  const current = currentRank(item);
  const st = (rank: number): LaneStatus => statusOf(rank, current);

  const { roles: gateRoles } = useProjectGates(item.profile);
  const g1Roles = (gateRoles.G1 ?? []).join(' + ') || '—';
  const g2Roles = (gateRoles.G2 ?? []).join(' + ') || '—';

  const askStatus = st(RANK.ask);
  const draftStatus = st(RANK.draft);
  const evaluateStatus = st(RANK.evaluate);
  const approve1Status = st(RANK.approve1);
  const approvedStatus = st(RANK.approved);
  const planStatus = st(RANK.plan);
  const approvePlanStatus = st(RANK.approvePlan);
  const waveStatus = st(RANK.wave);
  const reviewStatus = st(RANK.review);
  const approve2Status = st(RANK.approve2);
  const releaseStatus = st(RANK.release);
  const deployStatus = st(RANK.deploy);
  const monitorStatus = st(RANK.monitor);
  const signersStatuses = SIGNER_DOCS.map((d) => signerStatus(item, current, d.id));
  const signStatus = (id: string) => signersStatuses[SIGNER_DOCS.findIndex((d) => d.id === id)];

  const headline =
    current === -1
      ? 'Current: —'
      : item.canonicalState === 'done'
        ? 'Current: Complete'
        : `Current: ${PHASE_LABEL[phaseOf(current)]}${
            item.gate?.stage && GATE_STAGE_LABEL[item.gate.stage] ? ` · ${GATE_STAGE_LABEL[item.gate.stage]}` : ''
          }`;

  return (
    <section className="map">
      <div className="map-head">
        <h2>Feature delivery map</h2>
        <span>{headline}</span>
      </div>
      <div className="map-scroll">
        <div className="phases">
          <div className={phaseClass(1, [askStatus])}>
            <div className="phase-frame">
              <div className="phase-title">
                <h3>Clarify</h3>
                <span>Understand &amp; align</span>
              </div>
              <div className="phase-rail-row">
                <span className="phase-rail" aria-hidden="true" />
                <span className="phase-node">01</span>
              </div>
              <div className="phase-body">
                <div className="steps-row tree">
                  <Card icon={MessageSquare} title="Ask questions" subtitle="Grill agent" current={askStatus === 'current'} />
                  <Arrow />
                  <Card icon={RefreshCw} title="Re-ask" subtitle="per new answer" current={askStatus === 'current'} />
                </div>
                <LoopArc cards={2} from={0} to={1}>
                  Repeat until ambiguity is resolved
                </LoopArc>
              </div>
            </div>
          </div>

          <div className={phaseClass(2, [draftStatus, evaluateStatus, approve1Status, approvedStatus])}>
            <div className="phase-frame">
              <div className="phase-title">
                <h3>Specify &amp; approve</h3>
                <span>Turn into a plan</span>
              </div>
              <div className="phase-rail-row">
                <span className="phase-rail" aria-hidden="true" />
                <span className="phase-node">02</span>
              </div>
              <div className="phase-body">
                <div className="steps-row tree">
                  <Card icon={FileText} title="Draft story" subtitle="PO agent" current={draftStatus === 'current'} />
                  <Arrow />
                  <Card icon={ShieldCheck} title="Quality check" subtitle="Quality agent" current={evaluateStatus === 'current'} />
                  <Arrow />
                  <Card icon={RefreshCw} title="Auto-revise" subtitle="PO · FAIL ≤ 2×" current={evaluateStatus === 'current'} />
                  <Arrow />
                  <Card icon={CheckCircle2} title="Approve" subtitle={g1Roles} current={approve1Status === 'current'} />
                </div>
                <LoopArc cards={4} from={1} to={3}>
                  Request changes re-runs quality check
                </LoopArc>
                <GateRow status={approvedStatus} />
              </div>
            </div>
          </div>

          <div className={phaseClass(3, [planStatus, approvePlanStatus])}>
            <div className="phase-frame">
              <div className="phase-title">
                <h3>Plan</h3>
                <span>Break into work</span>
              </div>
              <div className="phase-rail-row">
                <span className="phase-rail" aria-hidden="true" />
                <span className="phase-node">03</span>
              </div>
              <div className="phase-body">
                <div className="steps-row tree">
                  <Card
                    icon={ListChecks}
                    title="Break into tasks"
                    subtitle={planStatus === 'current' && item.agentWork ? `Plan agent · ${stageSubtitle(item)}` : 'Plan agent'}
                    current={planStatus === 'current'}
                  />
                  <Arrow />
                  <Card
                    icon={CheckCircle2}
                    title="Approve plan"
                    subtitle={(gateRoles.PLAN ?? []).join(' + ') || '—'}
                    current={approvePlanStatus === 'current'}
                  />
                </div>
                <GateRow status={approvePlanStatus} title="Plan gate" subtitle="Task plan review" />
              </div>
            </div>
          </div>

          <div className={phaseClass(4, [waveStatus, reviewStatus, approve2Status])}>
            <div className="phase-frame">
              <div className="phase-title">
                <h3>Build &amp; review</h3>
                <span>Execute &amp; improve</span>
              </div>
              <div className="phase-rail-row">
                <span className="phase-rail" aria-hidden="true" />
                <span className="phase-node">04</span>
              </div>
              <div className="phase-body">
                <div className="steps-row tree">
                  <Card icon={Hammer} title="Run wave tasks" subtitle="Build worker · omp/ACP" current={waveStatus === 'current'} />
                  <Arrow />
                  <Card icon={TrendingUp} title="Iterate to green" subtitle="per task · ≤ budget" current={waveStatus === 'current'} />
                  <Arrow />
                  <Card icon={FileText} title="Post findings" subtitle="Review agent" current={reviewStatus === 'current'} />
                  <Arrow />
                  <Card icon={Code2} title="Approve PR" subtitle={g2Roles} current={approve2Status === 'current'} />
                </div>
                <LoopArc cards={4} from={0} to={3}>
                  Blocking finding or request changes reopens build
                </LoopArc>
              </div>
            </div>
          </div>

          <div className={phaseClass(5, [releaseStatus, deployStatus, monitorStatus, ...signersStatuses])}>
            <div className="phase-frame">
              <div className="phase-title">
                <h3>Release &amp; observe</h3>
                <span>Ship &amp; learn</span>
              </div>
              <div className="phase-rail-row">
                <span className="phase-rail" aria-hidden="true" />
                <span className="phase-node">05</span>
              </div>
              <div className="phase-body">
                <div className="steps-row tree">
                  <Card icon={Package} title="Draft release pack" subtitle="Release agent" current={releaseStatus === 'current'} />
                  <Arrow />
                  <Card icon={Truck} title="Deploy" subtitle="CI pipeline" current={deployStatus === 'current'} />
                  <Arrow />
                  <Card icon={BarChart2} title="Evaluate rules" subtitle="Monitor agent · one pass" current={monitorStatus === 'current'} />
                  <Arrow />
                  <Card icon={SIGN_ICON['change-notes']} title="Change notes" subtitle="PO signature" current={signStatus('change-notes') === 'current'} />
                </div>
                <div className="steps-row">
                  <Card icon={SIGN_ICON['rollout-plan']} title="Rollout plan" subtitle="SquadLead signature" current={signStatus('rollout-plan') === 'current'} />
                  <Arrow />
                  <Card icon={SIGN_ICON['monitor-rules']} title="Monitor rules" subtitle="QA signature" current={signStatus('monitor-rules') === 'current'} />
                  <Arrow />
                  <Card icon={SIGN_ICON['test-evidence']} title="Test evidence" subtitle="QA signature" current={signStatus('test-evidence') === 'current'} />
                </div>
                <LoopArc cards={3} from={0} to={2}>
                  Request changes clears every signature
                </LoopArc>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
