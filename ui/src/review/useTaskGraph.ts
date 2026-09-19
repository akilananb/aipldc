import { useMemo } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { api } from '../api';
import { parseTaskBrief, parseTaskBuildResult, parseTaskWaves } from '../ui-utils';
import type { TaskBrief, TaskBuildResult, TaskWave } from '../ui-utils';
import type { AgentRun, ItemDetail, ItemSummary, QualityReport } from '../types';

export type TaskStatus = 'verified' | 'failed' | 'running' | 'blocked' | 'queued';

export interface TaskNode {
  item: ItemSummary;
  detail: ItemDetail | undefined;
  brief: TaskBrief | null;
  taskId: string | null;
  wave: number | null;
  build: TaskBuildResult | null;
  quality: QualityReport | undefined;
  runs: AgentRun[];
  status: TaskStatus;
}

export interface TaskColumn {
  label: string;
  nodes: TaskNode[];
}

export interface TaskGraphSummary {
  total: number;
  verified: number;
  failed: number;
  running: number;
  blocked: number;
  queued: number;
  waves: number;
  buildRuns: number;
  green: number;
  red: number;
  iterations: number;
  tokens: number;
}

const UNSCHEDULED = 'Unscheduled';

function matchWaveEntry(waves: TaskWave[], brief: TaskBrief | null, title: string) {
  for (const wave of waves) {
    for (const entry of wave.entries) {
      if (entry.scenario != null && brief?.scenario === entry.scenario) {
        return { wave, entry };
      }
    }
  }
  for (const wave of waves) {
    for (const entry of wave.entries) {
      if (entry.title === title) {
        return { wave, entry };
      }
    }
  }
  return null;
}

export function useTaskGraph(storyId: string | null, childTasks: ItemSummary[]) {
  const specDocsQuery = useQuery({
    queryKey: ['spec-docs', storyId],
    queryFn: () => api.getSpecDocs(storyId!),
    enabled: !!storyId,
    refetchInterval: 5000,
    retry: false,
  });

  const activityQuery = useQuery({
    queryKey: ['activity', storyId],
    queryFn: () => api.getActivity(storyId!),
    enabled: !!storyId,
    refetchInterval: 2000,
    retry: false,
  });

  const detailQueries = useQueries({
    queries: childTasks.map((t) => ({
      queryKey: ['item', t.id],
      queryFn: () => api.getItem(t.id),
      refetchInterval: 5000,
      retry: false,
    })),
  });

  const qualityQueries = useQueries({
    queries: childTasks.map((t) => ({
      queryKey: ['quality', t.id],
      queryFn: () => api.getQuality(t.id),
      refetchInterval: 5000,
      retry: false,
    })),
  });

  return useMemo(() => {
    const waves = parseTaskWaves(specDocsQuery.data?.tasksMd ?? '');
    const storyRuns = activityQuery.data ?? [];

    interface Pre {
      item: ItemSummary;
      detail: ItemDetail | undefined;
      brief: TaskBrief | null;
      taskId: string | null;
      wave: number | null;
      build: TaskBuildResult | null;
      quality: QualityReport | undefined;
      runs: AgentRun[];
    }

    const pre: Pre[] = childTasks.map((item, idx) => {
      const detail = detailQueries[idx]?.data;
      const quality = qualityQueries[idx]?.data;
      const brief = detail ? parseTaskBrief(detail.description) : null;
      const build = quality?.reportMd ? parseTaskBuildResult(quality.reportMd) : null;
      const matched = matchWaveEntry(waves, brief, item.title);
      const taskId = matched?.entry.taskId ?? null;
      const wave = matched?.wave.index ?? null;
      const runs = storyRuns
        .filter((r) => r.agent === 'build-worker' && taskId != null && r.phase === taskId)
        .slice()
        .sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime());
      return { item, detail, brief, taskId, wave, build, quality, runs };
    });

    // First pass: status from item state, ignoring blockers.
    const baseStatus = new Map<string, TaskStatus>();
    for (const p of pre) {
      let status: TaskStatus;
      if (p.item.activeRun != null) {
        status = 'running';
      } else if (p.item.canonicalState === 'done') {
        status = p.quality?.verdict === 'failed' || p.build?.verdict === 'FAIL' ? 'failed' : 'verified';
      } else {
        status = 'queued';
      }
      baseStatus.set(p.item.id, status);
    }

    const verifiedTaskIds = new Set(
      pre.filter((p) => p.taskId != null && baseStatus.get(p.item.id) === 'verified').map((p) => p.taskId as string),
    );

    const nodes: TaskNode[] = pre.map((p) => {
      let status = baseStatus.get(p.item.id) as TaskStatus;
      if (status === 'queued' && p.brief && p.brief.blockedBy.length > 0) {
        const blocked = p.brief.blockedBy.some((dep) => !verifiedTaskIds.has(dep));
        if (blocked) status = 'blocked';
      }
      return {
        item: p.item,
        detail: p.detail,
        brief: p.brief,
        taskId: p.taskId,
        wave: p.wave,
        build: p.build,
        quality: p.quality,
        runs: p.runs,
        status,
      };
    });

    // Columns: one per distinct wave ascending, then a trailing Unscheduled column.
    const waveIndices = Array.from(new Set(nodes.filter((n) => n.wave != null).map((n) => n.wave as number))).sort(
      (a, b) => a - b,
    );
    const columns: TaskColumn[] = waveIndices.map((idx) => {
      const order = waves.find((w) => w.index === idx)?.entries.map((e) => e.taskId) ?? [];
      const colNodes = nodes
        .filter((n) => n.wave === idx)
        .slice()
        .sort((a, b) => {
          const ai = a.taskId ? order.indexOf(a.taskId) : -1;
          const bi = b.taskId ? order.indexOf(b.taskId) : -1;
          return (ai === -1 ? Number.MAX_SAFE_INTEGER : ai) - (bi === -1 ? Number.MAX_SAFE_INTEGER : bi);
        });
      return { label: `Wave ${idx}`, nodes: colNodes };
    });
    const unscheduled = nodes
      .filter((n) => n.wave == null)
      .slice()
      .sort((a, b) => new Date(b.item.updatedAt).getTime() - new Date(a.item.updatedAt).getTime());
    if (unscheduled.length > 0) {
      columns.push({ label: UNSCHEDULED, nodes: unscheduled });
    }

    const buildRuns = storyRuns.filter((r) => r.agent === 'build-worker');
    const summary: TaskGraphSummary = {
      total: nodes.length,
      verified: nodes.filter((n) => n.status === 'verified').length,
      failed: nodes.filter((n) => n.status === 'failed').length,
      running: nodes.filter((n) => n.status === 'running').length,
      blocked: nodes.filter((n) => n.status === 'blocked').length,
      queued: nodes.filter((n) => n.status === 'queued').length,
      waves: columns.filter((c) => c.label !== UNSCHEDULED).length,
      buildRuns: buildRuns.length,
      green: buildRuns.filter((r) => r.outcome === 'green').length,
      red: buildRuns.filter((r) => r.outcome === 'red').length,
      iterations: buildRuns.reduce((sum, r) => sum + (r.iterations ?? 0), 0),
      tokens: buildRuns.reduce((sum, r) => sum + (r.tokens ?? 0), 0),
    };

    return { nodes, columns, summary };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    childTasks,
    specDocsQuery.data,
    activityQuery.data,
    ...detailQueries.map((q) => q.data),
    ...qualityQueries.map((q) => q.data),
  ]);
}
