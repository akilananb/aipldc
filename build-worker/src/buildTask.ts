import { existsSync } from 'node:fs';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import type { BuildResult, ClaimedTask } from './types';
import { addWorktree, removeWorktree, changedFiles, revertPaths, hasChanges, commitAll, revParse } from './git';
import { runVerifier } from './verifier';
import { runAcpSession, buildTaskPrompt } from './acp';
import { loadBuildTaskTemplate } from './promptTemplate';
import type { RepoHandle } from './repo';

const ISO_8601_DURATION = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/;
const DEFAULT_TIMEOUT_MS = 10 * 60_000;

export function parseIso8601DurationMs(iso: string): number {
  const match = ISO_8601_DURATION.exec(iso);
  if (!match) {
    return DEFAULT_TIMEOUT_MS;
  }
  const hours = Number(match[1] ?? 0);
  const minutes = Number(match[2] ?? 0);
  const seconds = Number(match[3] ?? 0);
  return ((hours * 60 + minutes) * 60 + seconds) * 1000;
}

/** Append-only guarantee for a shared test file: whatever content existed before a task ran must
 * still be present verbatim after - substring containment tolerates the task's new content being
 * inserted anywhere (typically appended), but not a single byte of prior content changing. */
export function wasTestContentPreserved(before: string | null, after: string): boolean {
  return before === null || after.includes(before);
}

/**
 * The build loop for one claimed task - docs/agent-playbook.md §4. Real git worktree, the
 * configured ACP coding agent, real verifier (the target repo's own `npm test`). Scope
 * enforcement: any file the session touched outside `task.touches` is reverted and escalated
 * (playbook §4 "Must never ... edit tests outside may_edit_tests"; forbidden-action handling per
 * "Stop conditions").
 *
 * <p>{@code may_edit_tests} (playbook §4's own task-handoff field, never separately modeled -
 * `task.testPath` already names the one test a task owns): `PlanAgent` assigns one `testPath` per
 * area, so every task in a story typically shares the same file. A task may write to
 * `task.testPath` unless it already contains a `scenario: <task.scenario>` block - QA-owned
 * pre-existing coverage for this exact scenario stays strictly forbidden to touch, like every
 * other out-of-scope file. Otherwise the task may create the file (if missing) or append its own
 * scenario block to it (if other tasks' tests are already there); either way, whatever content
 * existed before the task ran must survive byte-for-byte, verified after the session by substring
 * containment - an agent can never weaken or remove another task's test to make its own pass.
 *
 * <p>The poller (worker.ts) owns heartbeating the claimed row while this runs; `signal` aborts
 * when a heartbeat finds the row gone (lease expired / superseded) - the ACP session is killed
 * and this throws so the poller discards the task without posting a result.
 */
export async function runBuildTask(payload: ClaimedTask['payload'], repo: RepoHandle, opts: { acpAgent: string; promptTemplateDir?: string }, signal: AbortSignal): Promise<BuildResult> {
  const { task, branch, baseBranch } = payload;
  const worktreePath = await mkdtemp(path.join(tmpdir(), `pdlc-task-${task.id}-`));

  try {
    await repo.sync(branch, baseBranch);
    await addWorktree(repo.path, worktreePath, branch, baseBranch);

    const testAbsPath = path.join(worktreePath, task.testPath);
    const testFileExists = existsSync(testAbsPath);
    const beforeTestContent = testFileExists ? await readFile(testAbsPath, 'utf8') : null;
    const hasOwnScenarioTest = beforeTestContent !== null && beforeTestContent.includes(`scenario: ${task.scenario}`);
    const canWriteTestPath = !hasOwnScenarioTest;
    const effectiveTouches = canWriteTestPath ? [...task.touches, task.testPath] : task.touches;

    const template = loadBuildTaskTemplate(opts.promptTemplateDir);
    const prompt = buildTaskPrompt(template, task.id, task.title, task.scenario, task.touches, task.testPath, testFileExists, canWriteTestPath);
    const timeoutMs = parseIso8601DurationMs(task.budget.maxWallClock);
    const session = await runAcpSession(worktreePath, prompt, timeoutMs, opts.acpAgent, signal);
    if (session.aborted) {
      throw new Error('task revoked');
    }

    let escalation: string | null = session.timedOut
      ? `budget exhausted: no result within ${task.budget.maxWallClock}`
      : null;

    let touched = await changedFiles(worktreePath, baseBranch);
    const outOfScope = touched.filter((file) => !effectiveTouches.includes(file));
    if (beforeTestContent !== null && touched.includes(task.testPath) && !outOfScope.includes(task.testPath)) {
      const afterTestContent = existsSync(testAbsPath) ? await readFile(testAbsPath, 'utf8') : '';
      if (!wasTestContentPreserved(beforeTestContent, afterTestContent)) {
        outOfScope.push(task.testPath);
      }
    }
    if (outOfScope.length > 0) {
      await revertPaths(worktreePath, outOfScope);
      escalation = escalation ?? `forbidden action: wanted to edit ${outOfScope.join(', ')} (outside touches)`;
      touched = touched.filter((file) => !outOfScope.includes(file));
    }

    const verifier = { ...(await runVerifier(worktreePath)), scopeOk: outOfScope.length === 0 };

    let commitSha: string;
    if (await hasChanges(worktreePath)) {
      commitSha = await commitAll(worktreePath, `${task.id}: ${task.title}`);
      await repo.push(worktreePath, branch);
    } else {
      commitSha = await revParse(repo.path, branch); // prior task already pushed
    }

    return {
      taskId: task.id,
      commitSha,
      verifier,
      iterations: session.toolCallCount,
      tokens: 0, // acpx/pi-acp report no per-session token usage in this integration
      touchedFiles: touched,
      traceSummary: session.events.slice(-5).join('\n'),
      escalation,
    };
  } finally {
    await removeWorktree(repo.path, worktreePath);
  }
}
