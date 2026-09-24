// Small shared UI helpers.

export type BadgeColor = 'gray' | 'green' | 'amber' | 'red' | 'blue' | 'violet';

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

const PILL_VARIANT: Record<BadgeColor, string> = {
  green: 'pass',
  amber: 'review',
  red: 'fail',
  blue: 'info',
  violet: 'violet',
  gray: '',
};

/** Maps a semantic badge color to the `.pill` modifier class it renders as — shared between
 * StatusBadge and the item-list state filter chips so both read the same palette. */
export function statePillVariant(state: string): string {
  return PILL_VARIANT[stateBadgeColor(state)];
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
      return 'amber';
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

export const ATTENTION_GATE: Record<string, 'G1' | 'G2' | 'G3'> = {
  'awaiting-G1': 'G1',
  'awaiting-G2': 'G2',
  'awaiting-G3': 'G3',
};

/** Mirrors ItemListPage's original inline check: does `role` currently sit on the gate blocking
 * `canonicalState`? Used both by ItemListPage's Attention column and by AppShell's "Needs review"
 * nav badge/filter, so the two always agree on what counts as needing attention. */
export function needsAttention(canonicalState: string, role: string, gateRoles?: Record<string, string[]>): boolean {
  const gate = ATTENTION_GATE[canonicalState];
  if (gate == null || !gateRoles) return false;
  return (gateRoles[gate] ?? []).includes(role);
}

/** Parent/child work-item link: work_items is unique on (profile, board_id), so a bare
 * parentId === boardId match can cross projects whose board ids collide. */
export function isChildOf(
  child: { profile: string; parentId: string | null },
  parent: { profile: string; boardId: string },
): boolean {
  return child.parentId != null && child.parentId === parent.boardId && child.profile === parent.profile;
}

/** Attention check for a list row: the row's OWN project's gate roles (never a cross-project
 * merge), and frozen demo snapshots never need review. */
export function itemNeedsAttention(
  item: { profile: string; canonicalState: string; snapshot: unknown | null },
  role: string,
  rolesByProject: Record<string, Record<string, string[]>>,
): boolean {
  return item.snapshot == null && needsAttention(item.canonicalState, role, rolesByProject[item.profile]);
}

export interface ScenarioStep {
  keyword: string;
  text: string;
}

export interface StoryLine {
  line: number;
  text: string;
}

export interface StoryRow extends StoryLine {
  keyword: string;
}

export interface StorySection {
  heading: string;
  lines: StoryLine[];
  bullets: number;
  start: number;
  end: number;
}

export interface StoryScenario {
  index: number;
  name: string;
  start: number;
  end: number;
  steps: (ScenarioStep & { line: number })[];
}

export interface StoryDoc {
  title: string | null;
  titleLine: number | null;
  story: { rows: StoryRow[]; start: number; end: number } | null;
  context: StorySection[];
  contextStart: number | null;
  contextEnd: number | null;
  rules: number;
  criteriaLine: number | null;
  scenarios: StoryScenario[];
}

const STORY_ROW_KEYWORDS = ['As an', 'As a', 'I want', 'So that', 'When', 'Then', 'That'];
const STORY_ROW = /^(As an|As a|I want|So that|When|Then|That)\b[,:]?\s*(.*?)[,.]?\s*$/i;
const TITLE_HEADING = /^#\s+(.+)$/;
const SECTION_HEADING = /^(#{2,3})\s+(.+)$/;
const SCENARIO_LINE = /^Scenario:\s*(.+)$/;
const STEP_LINE = /^(GIVEN|WHEN|AND|THEN|BUT)\b\s*(.*)$/i;

function parseStoryRow(line: number, trimmed: string): StoryRow {
  const m = trimmed.match(STORY_ROW);
  if (!m) return { line, text: trimmed, keyword: '' };
  const keyword = STORY_ROW_KEYWORDS.find((k) => k.toLowerCase() === m[1].toLowerCase()) ?? '';
  return { line, text: m[2].trim(), keyword };
}

/** Parses the PO story markdown contract (po-draft.mustache) into the sections the Preview tab's
 * structured layout renders: the `## Story` keyword rows (`As a`/`I want`/`So that`, or the legacy
 * `When`/`Then`/`That` from older drafts), `## Acceptance Criteria` scenarios with their
 * Given/When/And/Then steps, and every other `##`/`###` section bucketed as "context" (Goals,
 * Context, Functional/Non-Functional requirements, Out of Scope, Dependencies, Open decisions). */
export function parseStoryDoc(markdown: string): StoryDoc {
  const lines = markdown.split('\n');
  let title: string | null = null;
  let titleLine: number | null = null;
  let story: { rows: StoryRow[]; start: number; end: number } | null = null;
  const context: StorySection[] = [];
  let criteriaLine: number | null = null;
  const scenarios: StoryScenario[] = [];

  type Mode = 'none' | 'story' | 'criteria' | 'context';
  let mode: Mode = 'none';
  let currentContext: StorySection | null = null;
  let currentScenario: StoryScenario | null = null;

  const flushContext = () => {
    if (currentContext) context.push(currentContext);
    currentContext = null;
  };
  const flushScenario = () => {
    if (currentScenario) scenarios.push(currentScenario);
    currentScenario = null;
  };

  for (let i = 0; i < lines.length; i++) {
    const lineNo = i + 1;
    const trimmed = lines[i].trim();

    if (title === null) {
      const t = lines[i].match(TITLE_HEADING);
      if (t) {
        title = t[1].trim();
        titleLine = lineNo;
        continue;
      }
    }

    const h = lines[i].match(SECTION_HEADING);
    if (h) {
      flushContext();
      flushScenario();
      const heading = h[2].trim();
      const lower = heading.toLowerCase();
      if (lower === 'story') {
        mode = 'story';
        story = { rows: [], start: lineNo, end: lineNo };
      } else if (lower === 'acceptance criteria') {
        mode = 'criteria';
        criteriaLine = lineNo;
      } else {
        mode = 'context';
        currentContext = { heading, lines: [], bullets: 0, start: lineNo, end: lineNo };
      }
      continue;
    }

    if (trimmed === '') continue;

    if (mode === 'story' && story) {
      story.rows.push(parseStoryRow(lineNo, trimmed));
      story.end = lineNo;
    } else if (mode === 'criteria') {
      const sc = trimmed.match(SCENARIO_LINE);
      if (sc) {
        flushScenario();
        currentScenario = { index: scenarios.length, name: sc[1].trim(), start: lineNo, end: lineNo, steps: [] };
      } else if (currentScenario) {
        const st = trimmed.match(STEP_LINE);
        if (st) {
          currentScenario.steps.push({ keyword: st[1].toUpperCase(), text: st[2].trim(), line: lineNo });
          currentScenario.end = lineNo;
        }
      }
    } else if (mode === 'context' && currentContext) {
      const isBullet = trimmed.startsWith('- ');
      const text = isBullet ? trimmed.slice(2).trim() : trimmed;
      currentContext.lines.push({ line: lineNo, text });
      currentContext.end = lineNo;
      if (isBullet && text.toLowerCase() !== 'none') currentContext.bullets += 1;
    }
  }
  flushContext();
  flushScenario();

  const rules = context.reduce((sum, c) => sum + c.bullets, 0);
  const contextStart = context.length > 0 ? Math.min(...context.map((c) => c.start)) : null;
  const contextEnd = context.length > 0 ? Math.max(...context.map((c) => c.end)) : null;

  return { title, titleLine, story, context, contextStart, contextEnd, rules, criteriaLine, scenarios };
}

/** The `- ` bullet texts between a quality report's `## Findings` heading and the next `## `
 * heading (empty array when absent) — `reportMd` shape written by the quality agent. */
export function parseQualityFindings(reportMd: string): string[] {
  const lines = reportMd.split('\n');
  const start = lines.findIndex((l) => l.trim() === '## Findings');
  if (start === -1) return [];
  const findings: string[] = [];
  for (let i = start + 1; i < lines.length; i++) {
    const trimmed = lines[i].trim();
    if (/^##\s/.test(trimmed)) break;
    const m = trimmed.match(/^-\s+(.*)$/);
    if (m) findings.push(m[1].trim());
  }
  return findings;
}

export interface TaskBrief {
  summary: string;
  scenario: string | null;
  area: string | null;
  touches: string[];
  test: string | null;
  blockedBy: string[];
}

const TASK_BRIEF_FIELD = /^- \*\*(Scenario|Area|Touches|Test|Blocked by):\*\*\s*(.*)$/;

/** Parses a task work item's `description` as written by BoardSideEffectsImpl.taskDescription:
 * optional free-text summary, then a `---` divider, then `- **Field:** value` lines. */
export function parseTaskBrief(description: string): TaskBrief {
  const lines = description.split('\n');
  const dividerIdx = lines.findIndex((l) => l.trim() === '---');
  const summary = dividerIdx === -1 ? '' : lines.slice(0, dividerIdx).join('\n').trim();
  const brief: TaskBrief = { summary, scenario: null, area: null, touches: [], test: null, blockedBy: [] };
  for (const line of lines) {
    const m = TASK_BRIEF_FIELD.exec(line);
    if (!m) continue;
    const value = m[2].trim();
    const list = () => value.split(',').map((s) => s.trim()).filter((s) => s.length > 0);
    switch (m[1]) {
      case 'Scenario': brief.scenario = value; break;
      case 'Area': brief.area = value; break;
      case 'Touches': brief.touches = list(); break;
      case 'Test': brief.test = value; break;
      case 'Blocked by': brief.blockedBy = list(); break;
      default: break;
    }
  }
  return brief;
}

export interface TaskWaveEntry {
  taskId: string;
  title: string;
  scenario: string | null;
}

export interface TaskWave {
  index: number;
  entries: TaskWaveEntry[];
}

const WAVE_HEADING = /^##\s+Wave\s+(\d+)/;
const WAVE_ENTRY = /^- (\S+)\s+(.*)$/;
const WAVE_PROVES = /proves:?\s*"?([^";)]+)/;

/** Parses tasks.md as written by BoardSideEffectsImpl.tasksMd (and tolerates the older fixture
 * format used by demo snapshots): `## Wave N` headings followed by `- T1 <title> — proves "..."`
 * (or `(proves: ...)`) lines. */
export function parseTaskWaves(tasksMd: string): TaskWave[] {
  const waves: TaskWave[] = [];
  let current: TaskWave | null = null;
  for (const line of tasksMd.split('\n')) {
    const heading = WAVE_HEADING.exec(line);
    if (heading) {
      current = { index: Number(heading[1]), entries: [] };
      waves.push(current);
      continue;
    }
    if (!current) continue;
    const entry = WAVE_ENTRY.exec(line);
    if (!entry) continue;
    const rest = entry[2];
    const proves = WAVE_PROVES.exec(rest);
    const scenario = proves ? proves[1].trim() : null;
    const emDashIdx = rest.indexOf(' — ');
    const parenIdx = rest.indexOf(' (proves');
    let cut = rest.length;
    if (emDashIdx !== -1) cut = Math.min(cut, emDashIdx);
    if (parenIdx !== -1) cut = Math.min(cut, parenIdx);
    const title = rest.slice(0, cut).trim();
    current.entries.push({ taskId: entry[1], title, scenario });
  }
  return waves;
}

export interface TaskBuildResult {
  verdict: 'PASS' | 'FAIL' | null;
  verifier: string | null;
  verifierNotes: string | null;
  commit: string | null;
  iterations: number | null;
  escalation: string | null;
}

const BUILD_VERDICT = /^VERDICT:\s*(PASS|FAIL)/;
const BUILD_VERIFIER = /^verifier:\s*(\S+)\s*(?:\((.*)\))?/;
const BUILD_COMMIT = /^commit:\s*(\S+)/;
const BUILD_ITERATIONS = /^iterations:\s*(\d+)/;
const BUILD_ESCALATION = /^escalation:\s*(.*)$/;

/** Parses a task's quality report `reportMd` as written by BoardSideEffectsImpl.recordTaskResults. */
export function parseTaskBuildResult(reportMd: string): TaskBuildResult {
  const result: TaskBuildResult = { verdict: null, verifier: null, verifierNotes: null, commit: null, iterations: null, escalation: null };
  for (const line of reportMd.split('\n')) {
    const verdict = BUILD_VERDICT.exec(line);
    if (verdict) { result.verdict = verdict[1] as 'PASS' | 'FAIL'; continue; }
    const verifier = BUILD_VERIFIER.exec(line);
    if (verifier) { result.verifier = verifier[1]; result.verifierNotes = verifier[2] ?? null; continue; }
    const commit = BUILD_COMMIT.exec(line);
    if (commit) { result.commit = commit[1]; continue; }
    const iterations = BUILD_ITERATIONS.exec(line);
    if (iterations) { result.iterations = Number(iterations[1]); continue; }
    const escalation = BUILD_ESCALATION.exec(line);
    if (escalation) { result.escalation = escalation[1]; continue; }
  }
  return result;
}

/** Formats a raw token count as e.g. "1.2k"; used by RunTimeline and the task pane. */
export function formatTokens(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(1).replace(/\.0$/, '')}k` : String(n);
}

export const GRILL_CONFIRMATION_EVIDENCE = 'grill:confirmation';
export const GRILL_ASSUMPTION_EVIDENCE = 'assumption-check';
const GRILL_RECOMMENDATION_MARKER = '\n\nRecommended answer: ';

/** Splits a grill question's stored text into the question body and its recommended answer, if
 * any — the grill agent stores `<question>\n\nRecommended answer: <rec>` (GrillAgent.nextRound). */
export function splitGrillRecommendation(text: string): { body: string; recommendation: string | null } {
  const index = text.indexOf(GRILL_RECOMMENDATION_MARKER);
  if (index === -1) {
    return { body: text, recommendation: null };
  }
  return { body: text.slice(0, index), recommendation: text.slice(index + GRILL_RECOMMENDATION_MARKER.length) };
}

const CONFIRMATION_PREFIX = 'Confirm shared understanding: ';
const CONFIRMATION_INSTRUCTION = '\n\nReply confirm';

/** Strips the fixed confirmation-question scaffolding (FeatureWorkflowImpl#confirmationQuestion)
 * down to just the summary sentence(s) in the middle. */
export function grillConfirmationSummary(text: string): string {
  let body = text;
  if (body.startsWith(CONFIRMATION_PREFIX)) {
    body = body.slice(CONFIRMATION_PREFIX.length);
  }
  const cut = body.indexOf(CONFIRMATION_INSTRUCTION);
  if (cut !== -1) {
    body = body.slice(0, cut);
  }
  return body.trim();
}

export const GRILL_CATEGORY_ORDER = ['scope', 'users', 'acceptance', 'risk', 'dependency', 'nfr', 'build'] as const;

export const GRILL_CATEGORY_LABEL: Record<string, string> = {
  scope: 'Scope',
  users: 'Users',
  acceptance: 'Acceptance',
  risk: 'Risk',
  dependency: 'Dependencies',
  nfr: 'Non-functional',
  build: 'Build',
};

export function grillCategoryLabel(c: string): string {
  return GRILL_CATEGORY_LABEL[c] ?? c;
}

/** Human-readable repo locations for an agent presence row/tooltip — one line per configured
 * override (`id: location @ branch`), or a single `payload` line when the ACP worker takes its
 * repo from each claim with no fixed location. */
export function agentRepoLabel(a: { repos: { id: string; mode: string; location: string | null; branch: string | null }[] }): string {
  const repos = a.repos ?? [];
  if (repos.length === 0) return '—';
  return repos
    .map((r) => (r.mode === 'payload' ? `${r.id}: repo from claim payload` : `${r.id}: ${r.location ?? '—'}${r.branch ? ` @ ${r.branch}` : ''}`))
    .join('\n');
}
