import { useId, type ReactNode } from 'react';
import { CheckCircle2, ChevronRight, Circle, CircleDot, Repeat } from 'lucide-react';
import { Flex, Text } from '@radix-ui/themes';
import StatusBadge from '../components/StatusBadge';
import { GATE_ROLES } from '../gates';
import type { ItemDetail } from '../types';

interface Props {
  item: ItemDetail;
}

type LaneStatus = 'done' | 'current' | 'pending';

const CARD_W = 138;

/** Sequential rank of every non-loop primary step. Stages sharing a canonicalState (e.g. both
 * "Quality check" and "Approve" sit under awaiting-G1) are disambiguated in {@link currentRank}
 * using the extra fields the REST contract already exposes (qualityVerdict, gate.stage/approvals)
 * rather than guessed from canonicalState alone. */
const RANK = {
  ask: 0,
  draft: 1,
  evaluate: 2,
  approve1: 3,
  approved: 4,
  plan: 5,
  wave: 6,
  review: 7,
  approve2: 8,
  release: 9,
  signers: 10,
  deploy: 11,
  monitor: 12,
} as const;

function currentRank(item: ItemDetail): number {
  switch (item.canonicalState) {
    case 'needs-clarification':
      return RANK.ask;
    case 'ready-for-story':
      return RANK.draft;
    case 'awaiting-G1':
      return item.qualityVerdict === 'passed' ? RANK.approve1 : RANK.evaluate;
    case 'approved':
      return RANK.approved;
    case 'planned':
      return RANK.plan;
    case 'in-progress':
      return RANK.wave;
    case 'awaiting-G2':
      return RANK.approve2; // review agent always finishes before this state is observable
    case 'awaiting-G3':
      return RANK.signers; // release agent always finishes before this state is observable
    case 'done':
      return RANK.monitor + 1; // terminal: everything done, nothing "current"
    default:
      return -1;
  }
}

function statusOf(rank: number, current: number): LaneStatus {
  if (rank < current) return 'done';
  if (rank === current) return 'current';
  return 'pending';
}

function StatusIcon({ status }: { status: LaneStatus }) {
  if (status === 'done') return <CheckCircle2 size={14} color="var(--green-9)" style={{ flexShrink: 0 }} />;
  if (status === 'current') return <CircleDot size={14} color="var(--accent-9)" style={{ flexShrink: 0 }} />;
  return <Circle size={14} color="var(--gray-a8)" style={{ flexShrink: 0 }} />;
}

function Card({ title, subtitle, status }: { title: string; subtitle: string; status: LaneStatus }) {
  return (
    <Flex
      direction="column"
      gap="1"
      style={{
        width: CARD_W,
        padding: '6px 8px',
        borderRadius: 6,
        background: 'var(--gray-a3)',
        border: `1px solid ${status === 'current' ? 'var(--accent-8)' : 'var(--gray-a5)'}`,
      }}
    >
      <Flex align="center" gap="1">
        <StatusIcon status={status} />
        <Text size="1" weight={status === 'current' ? 'bold' : undefined} color={status === 'pending' ? 'gray' : undefined} style={{ lineHeight: 1.2 }}>
          {title}
        </Text>
      </Flex>
      <Text size="1" color="gray" style={{ fontSize: 10, lineHeight: 1.2, paddingLeft: 18 }}>
        {subtitle}
      </Text>
    </Flex>
  );
}

/** Persistent, always-visible loop note — the thing the corner-icon-only version was missing.
 * Used both under a self-looping single card and under a signer cluster. */
function LoopNote({ label }: { label: string }) {
  return (
    <Flex
      align="center"
      gap="1"
      style={{ padding: '2px 6px', borderRadius: 4, border: '1px dashed var(--accent-a8)', width: CARD_W, boxSizing: 'border-box' }}
    >
      <Repeat size={10} color="var(--accent-9)" style={{ flexShrink: 0 }} />
      <Text size="1" color="gray" style={{ fontSize: 9, lineHeight: 1.3 }}>
        {label}
      </Text>
    </Flex>
  );
}

/** A short, fixed-size loop-back connector between two adjacent cards — the actual agent-retry
 * loop (e.g. quality auto-revise), not a corner badge. A solid orthogonal "staple" with rounded
 * corners reads far cleaner at this size than a dashed bezier arc. Geometry is static because
 * both cards are a fixed width, so no DOM measurement is needed. */
function LoopArc() {
  const markerId = useId();
  const width = CARD_W * 2 + 8;
  const barY = 6;
  const dropY = 15;
  const x1 = CARD_W / 2;
  const x2 = width - CARD_W / 2;
  return (
    <svg width={width} height={18} style={{ display: 'block', overflow: 'visible' }}>
      <defs>
        {/* Authored pointing along local +X; orient="auto" rotates it to match the path's actual
            direction at the endpoint (straight down, here). */}
        <marker id={markerId} markerWidth="7" markerHeight="7" refX="6" refY="3" orient="auto" markerUnits="userSpaceOnUse">
          <path d="M0,0 L0,6 L6,3 Z" fill="var(--accent-9)" />
        </marker>
      </defs>
      <path
        d={`M ${x2} ${dropY} L ${x2} ${barY} L ${x1} ${barY} L ${x1} ${dropY}`}
        fill="none"
        stroke="var(--accent-9)"
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
        markerEnd={`url(#${markerId})`}
      />
    </svg>
  );
}

/** Two cards (the step + the agent action that loops back into it) joined by a visible loop arc. */
function LoopPair({
  primary,
  loopTitle,
  loopSubtitle,
  status,
}: {
  primary: { title: string; subtitle: string };
  loopTitle: string;
  loopSubtitle: string;
  status: LaneStatus;
}) {
  return (
    <Flex direction="column" align="center" gap="1">
      <LoopArc />
      <Flex gap="2">
        <Card title={primary.title} subtitle={primary.subtitle} status={status} />
        <Card title={loopTitle} subtitle={loopSubtitle} status="pending" />
      </Flex>
    </Flex>
  );
}

/** A single step whose own requestChanges/reset cycle loops back into itself, rather than into an
 * earlier step. */
function SelfLoopCard({ title, subtitle, status, loopLabel }: { title: string; subtitle: string; status: LaneStatus; loopLabel: string }) {
  return (
    <Flex direction="column" gap="1" align="center">
      <Card title={title} subtitle={subtitle} status={status} />
      <LoopNote label={loopLabel} />
    </Flex>
  );
}

function Connector() {
  return <ChevronRight size={14} color="var(--gray-a8)" style={{ flexShrink: 0 }} />;
}

export default function GateProgress({ item }: Props) {
  const current = currentRank(item);
  if (current === -1) {
    return <StatusBadge state={item.canonicalState} />;
  }

  const st = (rank: number): LaneStatus => statusOf(rank, current);

  const g1Roles = GATE_ROLES.G1.join(' + ');
  const g2Roles = GATE_ROLES.G2.join(' + ');

  const signerDocs: Array<{ id: string; title: string; role: string }> = [
    { id: 'change-notes', title: 'Change notes', role: 'PO' },
    { id: 'rollout-plan', title: 'Rollout plan', role: 'SquadLead' },
    { id: 'monitor-rules', title: 'Monitor rules', role: 'QA' },
    { id: 'test-evidence', title: 'Test evidence', role: 'QA' },
  ];

  function signerStatus(docId: string): LaneStatus {
    if (RANK.signers < current) return 'done';
    if (RANK.signers > current) return 'pending';
    if (item.gate?.stage === 'awaiting-G3' && item.gate.approvals[docId]) return 'done';
    return 'current';
  }

  const slots: ReactNode[] = [
    <LoopPair
      key="intake"
      primary={{ title: 'Ask questions', subtitle: 'Grill agent' }}
      loopTitle="Re-ask"
      loopSubtitle="Grill agent, per new answer"
      status={st(RANK.ask)}
    />,
    <Card key="ready" title="Draft story" subtitle="PO agent" status={st(RANK.draft)} />,
    <LoopPair
      key="quality"
      primary={{ title: 'Quality check', subtitle: 'Quality agent' }}
      loopTitle="Auto-revise"
      loopSubtitle="PO agent, on FAIL ≤ 2×"
      status={st(RANK.evaluate)}
    />,
    <SelfLoopCard
      key="gate1"
      title="Approve"
      subtitle={g1Roles}
      status={st(RANK.approve1)}
      loopLabel="requestChanges re-runs quality check"
    />,
    <Card key="approved" title="Gate 1 passed" subtitle="—" status={st(RANK.approved)} />,
    <Card key="plan" title="Break into tasks" subtitle="Plan agent" status={st(RANK.plan)} />,
    <LoopPair
      key="build"
      primary={{ title: 'Run wave tasks', subtitle: 'Build worker (omp/ACP)' }}
      loopTitle="Iterate to green"
      loopSubtitle="Per task, ≤ budget"
      status={st(RANK.wave)}
    />,
    <Card key="review" title="Post findings" subtitle="Review agent" status={st(RANK.review)} />,
    <SelfLoopCard
      key="gate2"
      title="Approve PR"
      subtitle={g2Roles}
      status={st(RANK.approve2)}
      loopLabel="blocking finding or requestChanges reopens"
    />,
    <Card key="release" title="Draft release pack" subtitle="Release agent" status={st(RANK.release)} />,
    <Flex key="gate3" direction="column" gap="1" align="center">
      <Flex direction="column" gap="1">
        {signerDocs.map((d) => (
          <Card key={d.id} title={d.title} subtitle={`Signed by ${d.role}`} status={signerStatus(d.id)} />
        ))}
      </Flex>
      <LoopNote label="requestChanges clears every signature" />
    </Flex>,
    <Card key="deploy" title="Deploy" subtitle="CI pipeline" status={st(RANK.deploy)} />,
    <Card key="monitor" title="Evaluate rules" subtitle="Monitor agent, one pass" status={st(RANK.monitor)} />,
  ];

  return (
    <Flex align="center" gap="2" style={{ flexWrap: 'nowrap', width: 'max-content' }}>
      {slots.map((slot, i) => (
        <Flex key={i} align="center" gap="2" style={{ flexShrink: 0 }}>
          {slot}
          {i < slots.length - 1 && <Connector />}
        </Flex>
      ))}
    </Flex>
  );
}
