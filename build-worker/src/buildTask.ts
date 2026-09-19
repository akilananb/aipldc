import { existsSync } from 'node:fs';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import type { BuildPayload, BuildResult, Task } from './types';
import { addWorktree, removeWorktree, changedFiles, revertPaths, hasChanges, commitAll, revParse } from './git';
import { runVerifier } from './verifier';
import { runAcpSession, buildTaskPrompt } from './acp';
import { loadTemplate } from './promptTemplate';
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
 * Everything about a build task's scope that must be computed once, before the coding session
 * starts, and reused unchanged afterward to judge what it did - shared by the autonomous
 * (`runBuildTask`) and assisted (`pdlc-assist build`) paths so both enforce identical scope rules.
 */
export interface BuildScope {
  /** This session's own starting commit - the diff base for scope, not `baseBranch` (see
   * {@link assessBuildScope}). */
  startSha: string;
  testAbsPath: string;
  testFileExists: boolean;
  beforeTestContent: string | null;
  /** False when `testAbsPath` already contains a `scenario: <task.scenario>` block - QA-owned
   * pre-existing coverage for this exact scenario, forbidden to touch like any other out-of-scope
   * file. */
  canWriteTestPath: boolean;
  effectiveTouches: string[];
}

export async function prepareBuildScope(worktreePath: string, task: Task): Promise<BuildScope> {
  // Scope enforcement must diff against THIS session's own starting commit, not baseBranch -
  // a fix round's worktree already has every earlier round's (and earlier task's, same wave)
  // commits on `branch`, which would otherwise show up as "touched" and get force-reverted /
  // escalated as a forbidden action despite this session never touching them.
  const startSha = await revParse(worktreePath, 'HEAD');

  const testAbsPath = path.join(worktreePath, task.testPath);
  const testFileExists = existsSync(testAbsPath);
  const beforeTestContent = testFileExists ? await readFile(testAbsPath, 'utf8') : null;
  const hasOwnScenarioTest = beforeTestContent !== null && beforeTestContent.includes(`scenario: ${task.scenario}`);
  const canWriteTestPath = !hasOwnScenarioTest;
  const effectiveTouches = canWriteTestPath ? [...task.touches, task.testPath] : task.touches;

  return { startSha, testAbsPath, testFileExists, beforeTestContent, canWriteTestPath, effectiveTouches };
}

/** Read-only: which files changed since `scope.startSha`, and which of those are out of scope
 * (outside `scope.effectiveTouches`, or a non-append-only edit to a shared test file). Performs
 * no reverts - see {@link finalizeBuild} for the enforcing half. */
export async function assessBuildScope(worktreePath: string, task: Task, scope: BuildScope): Promise<{ touched: string[]; outOfScope: string[] }> {
  const touched = await changedFiles(worktreePath, scope.startSha);
  const outOfScope = touched.filter((file) => !scope.effectiveTouches.includes(file));
  if (scope.beforeTestContent !== null && touched.includes(task.testPath) && !outOfScope.includes(task.testPath)) {
    const afterTestContent = existsSync(scope.testAbsPath) ? await readFile(scope.testAbsPath, 'utf8') : '';
    if (!wasTestContentPreserved(scope.beforeTestContent, afterTestContent)) {
      outOfScope.push(task.testPath);
    }
  }
  return { touched, outOfScope };
}

/**
 * The enforcing half: reverts anything {@link assessBuildScope} found out of scope, runs the real
 * verifier, commits and pushes (or reuses the branch head a prior task already pushed), and
 * assembles the {@link BuildResult} to post back. `outcome.iterations`/`traceSummary` are the
 * coding session's own numbers (autonomous: ACP tool-call count and tail events; assisted:
 * `pdlc-assist`'s round count and an "assisted by ..." breadcrumb) - this function only judges the
 * resulting diff, not how it was produced.
 */
export async function finalizeBuild(
  worktreePath: string,
  repo: RepoHandle,
  task: Task,
  branch: string,
  scope: BuildScope,
  outcome: { iterations: number; traceSummary: string; escalation: string | null },
): Promise<BuildResult> {
  const { touched: allTouched, outOfScope } = await assessBuildScope(worktreePath, task, scope);
  let touched = allTouched;
  let escalation = outcome.escalation;
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
    iterations: outcome.iterations,
    tokens: 0, // acpx/pi-acp report no per-session token usage in this integration
    touchedFiles: touched,
    traceSummary: outcome.traceSummary,
    escalation,
  };
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
export async function runBuildTask(payload: BuildPayload, repo: RepoHandle, opts: { acpAgent: string; promptTemplateDir?: string }, signal: AbortSignal): Promise<BuildResult> {
  const { task, branch, baseBranch, feedback = [] } = payload;
  const worktreePath = await mkdtemp(path.join(tmpdir(), `pdlc-task-${task.id}-`));

  try {
    await repo.sync(branch, baseBranch);
    await addWorktree(repo.path, worktreePath, branch, baseBranch);
    const scope = await prepareBuildScope(worktreePath, task);

    const template = loadTemplate('build-task', opts.promptTemplateDir);
    const prompt = buildTaskPrompt(template, task.id, task.title, task.description, task.scenario, task.touches, task.testPath, scope.testFileExists, scope.canWriteTestPath, feedback);
    const timeoutMs = parseIso8601DurationMs(task.budget.maxWallClock);
    const session = await runAcpSession(worktreePath, prompt, timeoutMs, opts.acpAgent, signal);
    if (session.aborted) {
      throw new Error('task revoked');
    }

    const escalation: string | null = session.timedOut
      ? `budget exhausted: no result within ${task.budget.maxWallClock}`
      : null;

    return await finalizeBuild(worktreePath, repo, task, branch, scope, {
      iterations: session.toolCallCount,
      traceSummary: session.events.slice(-5).join('\n'),
      escalation,
    });
  } finally {
    await removeWorktree(repo.path, worktreePath);
  }
}
