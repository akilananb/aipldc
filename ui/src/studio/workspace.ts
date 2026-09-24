import { useQuery } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';
import { studio } from '../api';
import type { Capability, Workspace } from '../types';

const STORAGE_KEY = 'pdlc.workspace';
const listeners = new Set<() => void>();

function read(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

/** The workspace the Studio last showed (per browser); every Studio query key includes the workspace id. */
export function setSelectedWorkspace(id: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, id);
  } catch {
    // ignore storage errors
  }
  for (const l of listeners) l();
}

export function useSelectedWorkspaceId(): string | null {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => {
        listeners.delete(l);
      };
    },
    read,
    read,
  );
}

export function useWorkspaces() {
  return useQuery({ queryKey: ['workspaces'], queryFn: studio.workspaces });
}

export function useWorkspace(id: string | null | undefined): Workspace | undefined {
  const { data } = useWorkspaces();
  return data?.find((w) => w.id === id);
}

export function can(workspace: Workspace | undefined, ...anyOf: Capability[]): boolean {
  return !!workspace && anyOf.some((c) => workspace.capabilities.includes(c));
}

/** Pill variant classes from styles.css for platform statuses. */
export function statusVariant(status: string): string {
  switch (status) {
    case 'ACTIVE':
    case 'SUCCEEDED':
      return 'pass';
    case 'FAILED':
    case 'RETIRED':
      return 'fail';
    case 'CANCELLED':
    case 'AWAITING_APPROVAL':
    case 'NEEDS_OPERATOR':
    case 'PENDING':
    case 'PENDING_APPROVAL':
    case 'UNKNOWN':
      return 'review';
    case 'APPROVED':
      return 'pass';
    case 'REJECTED':
    case 'EXPIRED':
      return 'fail';
    default:
      return 'info';
  }
}
