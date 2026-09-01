import { useSyncExternalStore } from 'react';

export type Appearance = 'light' | 'dark';

const STORAGE_KEY = 'pdlc.appearance';

function initial(): Appearance {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === 'light' || raw === 'dark') return raw;
  } catch {
    // ignore storage errors (SSR / privacy mode)
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

let current: Appearance = initial();
const listeners = new Set<() => void>();

export function getAppearance(): Appearance {
  return current;
}

export function setAppearance(next: Appearance): void {
  current = next;
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // ignore
  }
  for (const l of listeners) l();
}

function subscribeAppearance(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useAppearance(): Appearance {
  return useSyncExternalStore(subscribeAppearance, getAppearance, getAppearance);
}
