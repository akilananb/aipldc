// Pure pipeline model shared by DeliveryMap and ReviewBar — sequential rank of every non-loop
// primary step, disambiguated using the extra fields the REST contract exposes (qualityVerdict,
// gate.stage/approvals) rather than guessed from canonicalState alone. Ported from the former
// GateProgress.tsx.
import type { ItemDetail } from '../types';

export type LaneStatus = 'done' | 'current' | 'pending';

export const RANK = {
  ask: 0,
  draft: 1,
  evaluate: 2,
  approve1: 3,
  approved: 4,
  plan: 5,
  approvePlan: 6,
  wave: 7,
  review: 8,
  approve2: 9,
  release: 10,
  signers: 11,
  deploy: 12,
  monitor: 13,
} as const;

export function currentRank(item: ItemDetail): number {
  switch (item.canonicalState) {
    case 'needs-clarification':
      return RANK.ask;
    case 'ready-for-story':
      return RANK.draft;
    case 'awaiting-G1':
      return item.qualityVerdict === 'passed' ? RANK.approve1 : RANK.evaluate;
    case 'approved':
      return item.agentWork?.kind === 'plan' ? RANK.plan : RANK.approved;
    case 'planned':
      return RANK.approvePlan;
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

export function statusOf(rank: number, current: number): LaneStatus {
  if (rank < current) return 'done';
  if (rank === current) return 'current';
  return 'pending';
}

export const SIGNER_DOCS = [
  { id: 'change-notes', title: 'Change notes', role: 'PO' },
  { id: 'rollout-plan', title: 'Rollout plan', role: 'SquadLead' },
  { id: 'monitor-rules', title: 'Monitor rules', role: 'QA' },
  { id: 'test-evidence', title: 'Test evidence', role: 'QA' },
] as const;

export function signerStatus(item: ItemDetail, current: number, docId: string): LaneStatus {
  if (RANK.signers < current) return 'done';
  if (RANK.signers > current) return 'pending';
  if (item.gate?.stage === 'awaiting-G3' && item.gate.approvals[docId]) return 'done';
  return 'current';
}

export type Phase = 'clarify' | 'specify' | 'plan' | 'build' | 'release';

export const PHASE_LABEL: Record<Phase, string> = {
  clarify: 'Clarify',
  specify: 'Specify & approve',
  plan: 'Plan',
  build: 'Build & review',
  release: 'Release & observe',
};

export function phaseOf(rank: number): Phase {
  if (rank <= RANK.ask) return 'clarify';
  if (rank <= RANK.approved) return 'specify';
  if (rank <= RANK.approvePlan) return 'plan';
  if (rank <= RANK.approve2) return 'build';
  return 'release';
}

export function stageSubtitle(item: ItemDetail): string {
  switch (item.canonicalState) {
    case 'queued':
      return 'Queued — waiting for the previous story to finish.';
    case 'needs-clarification':
      return 'Grill agent is collecting answers before drafting.';
    case 'ready-for-story':
      return 'PO agent is drafting the story.';
    case 'awaiting-G1':
      return item.qualityVerdict === 'passed'
        ? 'Quality-checked story waiting for product and squad approval.'
        : 'Quality agent is evaluating the draft.';
    case 'approved': {
      const work = item.agentWork;
      if (!work) return 'Approved — plan agent is breaking the story into tasks.';
      if (work.phase === 'reasoning') return 'Plan agent is reasoning about the task breakdown.';
      if (work.phase === 'running') return `Build-worker ${work.claimedBy} is consulting the repository (round ${work.round}).`;
      return work.workersOnline === 0
        ? 'Waiting for a build-worker to consult the repository — none online.'
        : 'Waiting for a build-worker to pick up the repository consultation.';
    }
    case 'planned':
      return 'Task plan waiting for SquadLead approval.';
    case 'in-progress':
      return 'Build loop running.';
    case 'awaiting-G2':
      return 'Pull request waiting for developer and QA review.';
    case 'awaiting-G3':
      return 'Release pack waiting for sign-off.';
    case 'done':
      return 'Deployed and monitored.';
    default:
      return '';
  }
}
