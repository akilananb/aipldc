import { useSyncExternalStore } from 'react';

// External store carrying a one-shot request to prefill and focus the review comment composer
// (CommentPanel), driven by the ⌘K palette's "Ask an agent" action and the story header's
// "Ask agents" button. `seq` increments on every request so a repeated identical `text` (e.g.
// "@analyst ") still re-triggers the listening effect.
export interface ComposeRequest {
  seq: number;
  text: string;
}

let current: ComposeRequest = { seq: 0, text: '' };
const listeners = new Set<() => void>();

export function requestCompose(text: string): void {
  current = { seq: current.seq + 1, text };
  for (const l of listeners) l();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function getSnapshot(): ComposeRequest {
  return current;
}

export function useComposeRequest(): ComposeRequest {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}
