import { useQuery } from '@tanstack/react-query';
import { api } from './api';
import type { Project } from './types';

/** A single DB-backed project's config (repos, gates, docs, brief) by its id — the project code
 * formerly called "profile" everywhere. Gate/permission UI reads `gates` from here now instead of
 * the old hard-coded client `GATE_ROLES` map. */
export function useProject(profileId: string | null | undefined) {
  return useQuery<Project>({
    queryKey: ['project', profileId],
    queryFn: () => api.getProject(profileId!),
    enabled: profileId != null && profileId !== '',
    refetchInterval: 30000,
    retry: false,
  });
}

/** Gate role lists for one project, keyed `G1`/`G2`/`G3`/`PLAN`. While the project is loading
 * (or missing), every list is empty — the review UI treats that as "no role may act" and disables
 * the gate buttons with a loading hint. */
export function useProjectGates(profileId: string | null | undefined): { roles: Record<string, string[]>; loading: boolean } {
  const query = useProject(profileId);
  const gates = query.data?.gates ?? {};
  const roles: Record<string, string[]> = {};
  for (const [gateId, gate] of Object.entries(gates)) {
    roles[gateId] = gate.roles ?? [];
  }
  return { roles, loading: query.isLoading };
}

/** Every admin-managed project. Backs the item-list project filter and the per-project gate
 * role lookup below. */
export function useProjects() {
  return useQuery<Project[]>({
    queryKey: ['projects'],
    queryFn: api.listProjects,
    refetchInterval: 30000,
    retry: false,
  });
}

/** Gate roles per project id (`{ [projectId]: { G1: [...], ... } }`) for list rows, which now
 * carry their own `profile`. Empty until projects load. */
export function useGateRolesByProject(): Record<string, Record<string, string[]>> {
  const projects = useProjects();
  const byProject: Record<string, Record<string, string[]>> = {};
  for (const project of projects.data ?? []) {
    const roles: Record<string, string[]> = {};
    for (const [gateId, gate] of Object.entries(project.gates ?? {})) roles[gateId] = gate.roles ?? [];
    byProject[project.id] = roles;
  }
  return byProject;
}
