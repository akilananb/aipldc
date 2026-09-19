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
  wave: 6,
  review: 7,
  approve2: 8,
  release: 9,
  signers: 10,
  deploy: 11,
  monitor: 12,
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
  if (rank <= RANK.plan) return 'plan';
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
    case 'approved':
      return 'Approved — plan agent is breaking the story into tasks.';
    case 'planned':
      return 'Tasks planned; the build worker is picking them up.';
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
