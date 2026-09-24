import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import { api } from '../api';
import { useIdentity } from '../identity';
import { useProjectGates } from '../useProject';
import { GRILL_CATEGORY_ORDER, GRILL_CONFIRMATION_EVIDENCE, grillCategoryLabel } from '../ui-utils';
import type { GrillQuestion, GrillQuestions, ItemDetail } from '../types';

export type ClarificationScope = 'intake' | 'build';

export interface CategoryCoverage {
  category: string;
  label: string;
  total: number;
  resolved: number;
  pct: number;
}

export interface Clarification {
  query: UseQueryResult<GrillQuestions>;
  questions: GrillQuestion[];
  open: GrillQuestion[];
  confirmation: GrillQuestion | null;
  confirmedConfirmation: GrillQuestion | null;
  answered: GrillQuestion[];
  parked: GrillQuestion[];
  coverage: CategoryCoverage[];
  rounds: number;
  resolved: boolean;
  canReply: boolean;
  canProceed: boolean;
  readOnly: boolean;
  grillRunning: boolean;
  replyRolesHint: string;
}

/** Grill interview data + derived batching state, shared by ClarificationTab and
 * ClarificationRail so they read the same query (react-query dedupes) — `item` is optional only
 * so ReviewPage can call this hook unconditionally before its loading/kind early returns; with
 * `item` undefined every list is empty and every boolean is false. */
export function useClarification(item: ItemDetail | undefined, scope: ClarificationScope): Clarification {
  const identity = useIdentity();
  const { roles: gateRoles } = useProjectGates(item?.profile);

  const query = useQuery({
    queryKey: ['grill', item?.id],
    queryFn: () => api.getGrill(item!.id),
    enabled: !!item,
    refetchInterval: 2000,
    retry: false,
  });

  return useMemo(() => {
    const data = query.data;
    const allQuestions = data?.questions ?? [];
    const questions = scope === 'intake' ? allQuestions.filter((q) => !q.id.startsWith('h')) : allQuestions;

    const open = questions.filter((q) => q.status === 'open' && q.evidence !== GRILL_CONFIRMATION_EVIDENCE);
    const confirmation = [...questions].reverse().find((q) => q.status === 'open' && q.evidence === GRILL_CONFIRMATION_EVIDENCE) ?? null;
    const confirmedConfirmation = [...questions].reverse().find((q) => q.status === 'answered' && q.evidence === GRILL_CONFIRMATION_EVIDENCE) ?? null;
    const answered = questions.filter((q) => q.status === 'answered' && q.evidence !== GRILL_CONFIRMATION_EVIDENCE);
    const parked = questions.filter((q) => q.status === 'parked');

    const coverage: CategoryCoverage[] = GRILL_CATEGORY_ORDER.filter((cat) => questions.some((q) => q.category === cat)).map((cat) => {
      const inCategory = questions.filter((q) => q.category === cat);
      const total = inCategory.length;
      const resolvedCount = inCategory.filter((q) => q.status === 'answered' || q.status === 'parked').length;
      return { category: cat, label: grillCategoryLabel(cat), total, resolved: resolvedCount, pct: total > 0 ? Math.round((resolvedCount / total) * 100) : 0 };
    });

    const rounds = data?.rounds ?? 0;
    const resolved = data?.resolved ?? false;
    const readOnly = item?.snapshot != null;
    const grillRunning = item?.activeRun?.agent === 'grill' && item.activeRun.status === 'running';

    const canReply = !readOnly && ((gateRoles.G1 ?? []).includes(identity.role) || (scope === 'build' && (gateRoles.G2 ?? []).includes(identity.role)));
    const replyRolesHint = scope === 'build' ? 'Switch to a Gate 1 or Gate 2 role to answer' : 'Switch to PO or SquadLead to answer';
    const canProceed = scope === 'intake' && !readOnly && (gateRoles.G1 ?? []).includes(identity.role) && rounds >= 2;

    return { query, questions, open, confirmation, confirmedConfirmation, answered, parked, coverage, rounds, resolved, canReply, canProceed, readOnly, grillRunning, replyRolesHint };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query.data, scope, identity.role, item?.snapshot, item?.activeRun, gateRoles]);
}
