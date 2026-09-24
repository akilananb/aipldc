import { useSyncExternalStore } from 'react';

export interface Identity {
  user: string;
  role: string;
  label: string;
}

// Pilot demo identities. These map to the dev-header identity shim
// (X-User / X-Role), honoured only when control-plane runs with PDLC_IDENTITY_DEV_HEADERS=true.
// In OIDC mode the identity comes from the backend session via GET /api/me instead.
export const DEMO_IDENTITIES: Identity[] = [
  { user: 'po@acme', role: 'PO', label: 'PO' },
  { user: 'lead@acme', role: 'SquadLead', label: 'Squad Lead' },
  { user: 'fsdev@acme', role: 'FSDeveloper', label: 'FS Developer' },
  { user: 'qa@acme', role: 'QA', label: 'QA' },
  { user: 'admin@acme', role: 'Admin', label: 'Admin' },
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

/** Mirrors control-plane's MeDto (GET /api/me). `unknown` until the first response arrives. */
export interface AuthState {
  mode: 'unknown' | 'dev-headers' | 'oidc' | 'none';
  oidcEnabled: boolean;
  authenticated: boolean;
  user: string | null;
  role: string | null;
  loginUrl: string | null;
  logoutUrl: string | null;
  error: string | null;
}

let auth: AuthState = {
  mode: 'unknown',
  oidcEnabled: false,
  authenticated: false,
  user: null,
  role: null,
  loginUrl: null,
  logoutUrl: null,
  error: null,
};
const authListeners = new Set<() => void>();

export function getAuth(): AuthState {
  return auth;
}

/** Applies a /api/me response. In OIDC mode the session identity replaces the demo identity, so
 * every existing useIdentity() role check keeps working unchanged. */
export function setAuth(next: AuthState): void {
  auth = next;
  if (next.mode !== 'dev-headers' && next.mode !== 'unknown') {
    current = next.user && next.role ? { user: next.user, role: next.role, label: next.user } : { user: '', role: '', label: '' };
    for (const l of listeners) l();
  }
  for (const l of authListeners) l();
}

/** Dev headers are sent unless the backend has said it is not in dev-headers mode. */
export function sendsDevHeaders(): boolean {
  return auth.mode === 'dev-headers' || auth.mode === 'unknown';
}

export function useAuth(): AuthState {
  return useSyncExternalStore(
    (l) => {
      authListeners.add(l);
      return () => {
        authListeners.delete(l);
      };
    },
    getAuth,
    getAuth,
  );
}
