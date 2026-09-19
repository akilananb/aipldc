import { existsSync } from 'node:fs';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import type { PlanPayload, PlannedTask, PlanResult } from './types';
import { addDetachedWorktree, removeWorktree } from './git';
import { runAcpSession, planTasksPrompt } from './acp';
import { loadTemplate } from './promptTemplate';
import type { RepoHandle } from './repo';
import { validatePlan } from './planValidator';

const PLAN_TIMEOUT_MS = 15 * 60_000;
const OUTPUT_PATH = '.pdlc/plan.json';
const MAX_ATTEMPTS = 2;

// Re-exported for existing callers/tests (`planTask.test.ts` imports `validatePlan` from here) -
// see `planValidator.ts` for the implementation and why it lives in its own dependency-free module.
export { validatePlan };

/**
 * The plan step for one claimed story - build-order phase 3, run by this same standalone agent
 * instead of a deterministic Java planner (see docs/agent-playbook.md §3). Read-only: a detached
 * worktree of the default branch (no branch checkout conflict with an in-flight build loop
 * elsewhere), one ACP session that may only write {@link OUTPUT_PATH}, up to {@link MAX_ATTEMPTS}
 * tries feeding validation failures back as feedback. Never commits or pushes.
 */
export async function runPlanTask(payload: PlanPayload, repo: RepoHandle, opts: { acpAgent: string; promptTemplateDir?: string }, signal: AbortSignal): Promise<PlanResult> {
  const { po, baseBranch } = payload;
  await repo.sync(baseBranch, baseBranch);

  const template = loadTemplate('plan-tasks', opts.promptTemplateDir);
  let feedback: string[] = [];
  let lastErrors: string[] = [];

  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    const worktreePath = await mkdtemp(path.join(tmpdir(), `pdlc-plan-${payload.story.boardId}-`));
    try {
      await addDetachedWorktree(repo.path, worktreePath, baseBranch);
      const prompt = planTasksPrompt(template, {
        change: po.change,
        scenarios: po.scenarios,
        areas: po.areas.join(', '),
        nfr: Object.entries(po.nfr).map(([k, v]) => `${k}: ${v}`),
        outputPath: OUTPUT_PATH,
        feedback,
      });
      const session = await runAcpSession(worktreePath, prompt, PLAN_TIMEOUT_MS, opts.acpAgent, signal);
      if (session.aborted) {
        throw new Error('plan revoked');
      }

      const planPath = path.join(worktreePath, OUTPUT_PATH);
      if (session.timedOut) {
        lastErrors = ['planner timed out before writing .pdlc/plan.json'];
      } else if (!existsSync(planPath)) {
        lastErrors = ['plan.json missing or not valid JSON'];
      } else {
        let raw: unknown;
        try {
          raw = JSON.parse(await readFile(planPath, 'utf8'));
        } catch {
          lastErrors = ['plan.json missing or not valid JSON'];
          raw = undefined;
        }
        if (raw !== undefined) {
          const parsedTasks = (raw as { tasks?: unknown }).tasks;
          const { tasks, errors } = validatePlan(parsedTasks, po.scenarios);
          if (errors.length === 0) {
            const newFiles = collectNewFiles(worktreePath, tasks);
            return { tasks, newFiles };
          }
          lastErrors = errors;
        }
      }
    } finally {
      await removeWorktree(repo.path, worktreePath);
    }
    feedback = lastErrors;
  }

  throw new Error(`plan rejected: ${lastErrors.join('; ')}`);
}

export function collectNewFiles(worktreePath: string, tasks: PlannedTask[]): string[] {
  const candidates = new Set<string>();
  for (const t of tasks) {
    for (const file of t.touches) {
      candidates.add(file);
    }
    candidates.add(t.testPath);
  }
  return [...candidates].filter((p) => !existsSync(path.join(worktreePath, p)));
}
