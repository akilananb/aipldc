import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';

/**
 * The developer-facing brief `pdlc-assist` writes before each omp launch, and the `pdlc-assist`
 * omp extension (`omp/pdlc-assist.ts`) reads on `session_start`. Lives in a control directory
 * *outside* the task worktree (see assist.ts) so it never shows up in `changedFiles`/scope checks.
 */
export interface AssistBrief {
  kind: 'plan' | 'build';
  /** `build_tasks` row id - identifies this claim to control-plane. */
  claimId: string;
  story: { profile: string; boardId: string };
  /** `'plan'` or e.g. `'T2'`. */
  taskId: string;
  /** plan: `po.change`; build: `task.title`. */
  title: string;
  /** ISO timestamp of the first omp launch for this claim. */
  startedAt: string;
  /** ISO timestamp `startedAt` + `--max-minutes`. */
  deadlineAt: string;
  /** 1-based omp launch count for this claim. */
  round: number;
  /** Validation/scope/verifier lines from the previous round; `[]` on round 1. */
  feedback: string[];
  /** Markdown injected into the session on `session_start` - the same prompt text the autonomous
   * ACP path would have sent (see assist.ts). */
  briefing: string;
  /** Plan only: absolute path of the compiled, dependency-free `planConsultationValidator.js`,
   * so the extension can import `validateConsultationOutput` without duplicating its rules or
   * pulling in `acp.ts`'s `mustache` dependency (unresolvable from inside omp's
   * extension-loading context). */
  consultationValidatorModule?: string;
  /** Plan only: the workflow-level {@code PlanConsultation.round} this claim answers - distinct
   * from {@link round}, which counts local omp launches within this one claim; display-only. */
  consultationRound?: number;
  /** Plan only: repo-relative path the agent must write, e.g. `.pdlc/consultation.json`. */
  outputPath?: string;
  /** Build only: `BuildScope.effectiveTouches`. */
  touches?: string[];
  /** Build only: `task.testPath`. */
  testPath?: string;
}

export type AssistDecision = { decision: 'submit'; force: boolean } | { decision: 'cancel' };

export const BRIEF_FILE = 'assist.json';
export const DECISION_FILE = 'decision.json';

export async function writeBrief(controlDir: string, brief: AssistBrief): Promise<void> {
  await mkdir(controlDir, { recursive: true });
  await writeFile(path.join(controlDir, BRIEF_FILE), JSON.stringify(brief, null, 2), 'utf8');
}

export async function readBrief(controlDir: string): Promise<AssistBrief> {
  return JSON.parse(await readFile(path.join(controlDir, BRIEF_FILE), 'utf8')) as AssistBrief;
}

export async function writeDecision(controlDir: string, decision: AssistDecision): Promise<void> {
  await mkdir(controlDir, { recursive: true });
  await writeFile(path.join(controlDir, DECISION_FILE), JSON.stringify(decision), 'utf8');
}

/** Missing or unparseable decision file (the omp session exited without `/pdlc submit`, e.g.
 * `/exit` or Ctrl-C) is treated as `cancel` - never silently treated as an implicit submit. */
export async function readDecision(controlDir: string): Promise<AssistDecision> {
  try {
    const raw = JSON.parse(await readFile(path.join(controlDir, DECISION_FILE), 'utf8')) as Partial<AssistDecision>;
    if (raw && raw.decision === 'submit') {
      return { decision: 'submit', force: 'force' in raw && (raw as { force?: unknown }).force === true };
    }
    return { decision: 'cancel' };
  } catch {
    return { decision: 'cancel' };
  }
}

export async function clearDecision(controlDir: string): Promise<void> {
  await rm(path.join(controlDir, DECISION_FILE), { force: true });
}
