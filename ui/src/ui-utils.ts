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

export function agentRunLabel(run: { agent: string; phase: string | null }): string {
  switch (`${run.agent}/${run.phase ?? ''}`) {
    case 'grill/questions':
      return 'Grill agent drafting clarification questions';
    case 'grill/answers':
      return 'Grill agent evaluating the answers';
    case 'po/draft':
      return 'PO agent drafting the story';
    case 'po/revise':
      return 'PO agent revising the story';
    case 'quality/story':
      return 'Quality agent reviewing the story';
    case 'quality/task':
      return 'Quality agent reviewing the task';
    case 'plan/plan':
      return 'Plan agent breaking the story into tasks';
    case 'review/review':
      return 'Review agent reviewing the diff';
    case 'release/draft':
      return 'Release agent drafting the release pack';
    case 'release/redraft':
      return 'Release agent redrafting the release pack';
    case 'monitor/evaluate':
      return 'Monitor agent evaluating monitor rules';
    default:
      if (run.agent === 'mention') return `@${run.phase} agent analyzing`;
      if (run.agent === 'build-worker') return `Build worker: task ${run.phase ?? '?'}`;
      if (run.agent === 'ci-deploy') return `Deploy ${run.phase ?? ''}`.trim();
      return `${run.agent} agent working`;
  }
}

export function runOutcomeColor(outcome: string | null): BadgeColor {
  switch (outcome) {
    case 'ok':
    case 'green':
      return 'green';
    case 'error':
    case 'red':
      return 'red';
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

export interface MentionToken {
  /** Index of the `@` character. */
  start: number;
  /** Text between `@` and the caret, possibly empty. */
  query: string;
}

/** Finds the in-progress `@agent` token the caret is inside, mirroring the backend trigger rule
 * (AgentMentions.java): the `@` must start the text or follow a non-word, non-`@` character, and
 * the partial name may contain only letters. Returns null when the caret isn't in such a token. */
export function findMentionToken(text: string, caret: number): MentionToken | null {
  const upToCaret = text.slice(0, caret);
  const at = upToCaret.lastIndexOf('@');
  if (at === -1) return null;
  if (at > 0 && /[\w@]/.test(upToCaret[at - 1])) return null;
  const query = upToCaret.slice(at + 1);
  if (!/^[A-Za-z]*$/.test(query)) return null;
  return { start: at, query };
}
