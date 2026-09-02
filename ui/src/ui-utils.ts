// Small shared UI helpers.

export type BadgeColor = 'gray' | 'green' | 'amber' | 'red' | 'blue' | 'orange' | 'violet';

export function stateBadgeColor(state: string): BadgeColor {
  switch (state) {
    case 'approved':
      return 'green';
    case 'awaiting-G1':
    case 'awaiting-G2':
    case 'awaiting-G3':
    case 'needs-clarification':
      return 'amber';
    case 'queued':
      return 'blue';
    case 'stale':
      return 'red';
    case 'done':
      return 'violet';
    default:
      return 'gray';
  }
}

export function intentBadgeColor(intent: string): BadgeColor {
  switch (intent) {
    case 'change':
      return 'orange';
    case 'question':
      return 'blue';
    default:
      return 'gray';
  }
}

export interface LineRange {
  start: number;
  end: number;
}

/** Parses "line:5" | "line:5-9" → {start,end}; anything else (scenario:…, null) → null. */
export function parseLineTarget(target: string | null): LineRange | null {
  const m = target?.match(/^line:(\d+)(?:-(\d+))?$/);
  if (!m) return null;
  const start = Number(m[1]);
  const end = m[2] ? Number(m[2]) : start;
  return { start: Math.min(start, end), end: Math.max(start, end) };
}

export function formatLineTarget(a: number, b: number): string {
  const start = Math.min(a, b), end = Math.max(a, b);
  return start === end ? `line:${start}` : `line:${start}-${end}`;
}

/** Click → single line; shift-click with an existing line draft → extend to cover both. */
export function extendLineTarget(prev: string | null, line: number, shift: boolean): string {
  const range = parseLineTarget(prev);
  if (shift && range) return formatLineTarget(Math.min(range.start, line), Math.max(range.end, line));
  return formatLineTarget(line, line);
}
