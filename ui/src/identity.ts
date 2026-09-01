import { useSyncExternalStore } from 'react';

export interface Identity {
  user: string;
  role: string;
  label: string;
}

// Pilot demo identities. These map to the dev-header identity shim
// (X-User / X-Role) used by control-plane.
export const DEMO_IDENTITIES: Identity[] = [
  { user: 'po@acme', role: 'PO', label: 'PO' },
  { user: 'lead@acme', role: 'SquadLead', label: 'Squad Lead' },
  { user: 'fsdev@acme', role: 'FSDeveloper', label: 'FS Developer' },
  { user: 'qa@acme', role: 'QA', label: 'QA' },
];

const STORAGE_KEY = 'pdlc.identity';

let current: Identity = load();
const listeners = new Set<() => void>();

function load(): Identity {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as { user?: string };
      const match = DEMO_IDENTITIES.find((i) => i.user === parsed.user);
      if (match) return match;
    }
  } catch {
    // ignore storage errors (SSR / privacy mode)
  }
  return DEMO_IDENTITIES[0];
}

export function getIdentity(): Identity {
  return current;
}

export function setIdentity(next: Identity): void {
  current = next;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ user: next.user, role: next.role }));
  } catch {
    // ignore
  }
  for (const l of listeners) l();
}

function subscribeIdentity(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useIdentity(): Identity {
  return useSyncExternalStore(subscribeIdentity, getIdentity, getIdentity);
}
