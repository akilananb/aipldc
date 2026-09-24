#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { ApiClient } from './client';
import { loadConfig, type AgentConfig } from './config';
import { resolveRepo, type RepoHandle } from './repo';
import { describePresence, withClaimLease } from './worker';
import { addDetachedWorktree, addWorktree, diffCached, removeWorktree } from './git';
import { runVerifier } from './verifier';
import { loadTemplate } from './promptTemplate';
import { buildTaskPrompt, planConsultationPrompt } from './acp';
import { collectConsultationReport, formatConsultationHistory, resolvePinnedCommit } from './planTask';
import { assessBuildScope, finalizeBuild, prepareBuildScope, type BuildScope } from './buildTask';
import { clearDecision, DECISION_FILE, readDecision, writeBrief, type AssistBrief, type AssistDecision } from './assistProtocol';
import type { BuildPayload, ClaimedTask, Lease, PlanConsultationReport, PlanPayload, Task, WorkItemRef } from './types';

const PLAN_OUTPUT_PATH = '.pdlc/consultation.json';
const DEFAULT_MAX_MINUTES = 25;
const ASSISTED_PLAN_MAX_MINUTES = 15;
const USAGE = 'usage: pdlc-assist <plan|build> --story <boardId> [--task <id>] [--max-minutes <n>]';


export interface AssistArgs {
  kind: 'plan' | 'build';
  story: string;
  task?: string;
  maxMinutes: number;
}

export function parseArgs(argv: string[]): AssistArgs {
  const [kind, ...rest] = argv;
  if (kind !== 'plan' && kind !== 'build') {
    throw new Error(USAGE);
  }
  let story: string | undefined;
  let task: string | undefined;
  let maxMinutes = DEFAULT_MAX_MINUTES;
  for (let i = 0; i < rest.length; i++) {
    const flag = rest[i];
    const value = rest[++i];
    if (flag === '--story') {
      story = value;
    } else if (flag === '--task') {
      task = value;
    } else if (flag === '--max-minutes') {
      maxMinutes = Number(value);
    } else {
      throw new Error(USAGE);
    }
  }
  if (!story) {
    throw new Error(USAGE);
  }
  if (kind === 'build' && !task) {
    throw new Error(`${USAGE}\n('build' requires --task)`);
  }
  if (!Number.isFinite(maxMinutes) || maxMinutes <= 0) {
    throw new Error(USAGE);
  }
  return { kind, story, task, maxMinutes };
}

/** One running (or already-exited) omp process - `kill()` is how a lease loss or `--max-minutes`
 * deadline terminates a session the developer left running. */
export interface OmpLaunch {
  exited: Promise<void>;
  kill(): void;
}

export interface AssistDeps {
  /** Launches omp (or a test double) in `worktreePath` with `PDLC_ASSIST_DIR=controlDir`;
   * `--continue` resumes the same worktree's session from `round` 2 onward. */
  launchOmp(worktreePath: string, controlDir: string, round: number): OmpLaunch;
}

const EXT_PATH = path.resolve(__dirname, '..', 'omp', 'pdlc-assist.ts');

function defaultDeps(): AssistDeps {
  return {
    launchOmp(worktreePath, controlDir, round) {
      const child = spawn(
        'omp',
        ['--cwd', worktreePath, '-e', EXT_PATH, ...(round > 1 ? ['--continue'] : [])],
        { stdio: 'inherit', env: { ...process.env, PDLC_ASSIST_DIR: controlDir } },
      );
      const { promise, resolve, reject } = Promise.withResolvers<void>();
      child.on('error', reject);
      child.on('close', () => resolve());
      return { exited: promise, kill: () => child.kill('SIGTERM') };
    },
  };
}

const DECISION_POLL_MS = 500;
/** `/pdlc submit`/`cancel` call `ctx.shutdown()`, which disposes the omp session but has been
 * observed to leave the interactive process itself running rather than exiting it. The decision
 * file is authoritative regardless: once it lands, give the process this long to exit on its own
 * before `runOmpRound` force-kills it. */
const DECISION_GRACE_MS = 2000;

async function decisionFileExists(controlDir: string): Promise<boolean> {
  try {
    await access(path.join(controlDir, DECISION_FILE));
    return true;
  } catch {
    return false;
  }
}

/** Writes the round's brief, launches omp, and terminates it early on lease loss, the overall
 * `--max-minutes` deadline, or a decision landing on disk that the process itself did not exit
 * for (see {@link DECISION_GRACE_MS}) - both `runAssistedPlan` and `runAssistedBuild` drive their
 * round loop through this one function so lease/deadline/decision handling stays identical. */
async function runOmpRound(
  deps: AssistDeps,
  controlDir: string,
  worktreePath: string,
  brief: AssistBrief,
  leaseSignal: AbortSignal,
  deadlineAt: number,
): Promise<AssistDecision | 'lease-lost' | 'deadline'> {
  await clearDecision(controlDir);
  await writeBrief(controlDir, brief);
  const launch = deps.launchOmp(worktreePath, controlDir, brief.round);

  const deadlineTimer = setTimeout(() => launch.kill(), Math.max(0, deadlineAt - Date.now()));
  const onLeaseAbort = () => launch.kill();
  leaseSignal.addEventListener('abort', onLeaseAbort);

  let exited = false;
  let launchError: unknown;
  void launch.exited.then(
    () => { exited = true; },
    (err: unknown) => { launchError = err; exited = true; },
  );

  try {
    let decisionSeenAt: number | undefined;
    while (!exited) {
      const { promise, resolve } = Promise.withResolvers<void>();
      setTimeout(resolve, DECISION_POLL_MS);
      await promise;
      if (exited) {
        break;
      }
      if (decisionSeenAt === undefined && (await decisionFileExists(controlDir))) {
        decisionSeenAt = Date.now();
      }
      if (decisionSeenAt !== undefined && Date.now() - decisionSeenAt >= DECISION_GRACE_MS) {
        launch.kill();
      }
    }
  } finally {
    clearTimeout(deadlineTimer);
    leaseSignal.removeEventListener('abort', onLeaseAbort);
  }

  // A spawn/launch failure (e.g. `omp` not on PATH) is a hard error, not a developer decision -
  // rethrow it rather than falling through to `readDecision`, which would find no decision file
  // and silently misreport the failure as a cancel.
  if (launchError !== undefined) {
    throw launchError;
  }

  if (leaseSignal.aborted) {
    return 'lease-lost';
  }
  if (Date.now() >= deadlineAt) {
    return 'deadline';
  }
  return readDecision(controlDir);
}

/** Preserves a cancelled/superseded/timed-out session's uncommitted work as a patch file before
 * its worktree is discarded - `undefined` (no file written) when the diff against `baseRef` is
 * empty. Never throws: git.ts's `diffCached` failing here must not mask the real outcome the
 * caller is already reporting. */
async function savePatch(cfg: AgentConfig, worktreePath: string, story: WorkItemRef, task: Task, baseRef: string): Promise<string | undefined> {
  const diff = await diffCached(worktreePath, baseRef).catch(() => '');
  if (!diff.trim()) {
    return undefined;
  }
  const dir = path.join(cfg.cacheDir, 'assist');
  await mkdir(dir, { recursive: true });
  const file = path.join(dir, `${story.boardId}-${task.id}-${Date.now()}.patch`);
  await writeFile(file, diff, 'utf8');
  return file;
}

async function runAssistedPlan(
  cfg: AgentConfig,
  client: ApiClient,
  lease: Lease,
  payload: PlanPayload,
  repo: RepoHandle,
  deps: AssistDeps,
  maxMinutes: number,
  leaseSignal: AbortSignal,
): Promise<number> {
  const { po, baseBranch, story, consultation } = payload;
  await repo.sync(baseBranch, baseBranch);
  const baseCommit = await resolvePinnedCommit(repo, baseBranch, consultation.baseCommit);
  const worktreePath = await mkdtemp(path.join(tmpdir(), `pdlc-assist-plan-${story.boardId}-`));
  const controlDir = await mkdtemp(path.join(tmpdir(), `pdlc-assist-ctl-${story.boardId}-`));
  const template = loadTemplate('plan-consultation', cfg.promptTemplateDir);
  const startedAt = Date.now();
  // A single claimed consultation round is capped tighter than the general --max-minutes: the
  // Plan Agent, not this CLI, owns the whole bounded conversation across claims.
  const cappedMinutes = Math.min(maxMinutes, ASSISTED_PLAN_MAX_MINUTES);
  const deadlineAt = startedAt + cappedMinutes * 60_000;

  try {
    await addDetachedWorktree(repo.path, worktreePath, baseCommit);

    let feedback: string[] = [];
    let round = 1;
    for (;;) {
      const briefing = planConsultationPrompt(template, {
        change: po.change,
        scenarios: po.scenarios,
        areas: po.areas.join(', '),
        nfr: Object.entries(po.nfr).map(([k, v]) => `${k}: ${v}`),
        questions: consultation.questions,
        paths: consultation.paths,
        history: formatConsultationHistory(consultation.history),
        outputPath: PLAN_OUTPUT_PATH,
        repo: consultation.repoId,
      });
      const brief: AssistBrief = {
        kind: 'plan',
        claimId: lease.id,
        story,
        taskId: 'plan',
        title: po.change,
        startedAt: new Date(startedAt).toISOString(),
        deadlineAt: new Date(deadlineAt).toISOString(),
        round,
        feedback,
        briefing,
        consultationValidatorModule: path.join(__dirname, 'planConsultationValidator.js'),
        consultationRound: consultation.round,
        outputPath: PLAN_OUTPUT_PATH,
      };

      const outcome = await runOmpRound(deps, controlDir, worktreePath, brief, leaseSignal, deadlineAt);

      if (outcome === 'lease-lost') {
        console.log('[pdlc-assist] lease lost - task reassigned; worktree discarded');
        return 3;
      }
      if (outcome === 'deadline') {
        await client.postFail(lease, `pdlc-assist: exceeded --max-minutes ${cappedMinutes}`);
        console.log(`[pdlc-assist] exceeded --max-minutes ${cappedMinutes}; failed the claim`);
        return 0;
      }
      if (outcome.decision === 'cancel') {
        await client.postFail(lease, `pdlc-assist: plan consultation cancelled by ${cfg.agentName}`);
        console.log('[pdlc-assist] plan consultation cancelled');
        return 0;
      }

      let raw: unknown;
      try {
        raw = JSON.parse(await readFile(path.join(worktreePath, PLAN_OUTPUT_PATH), 'utf8'));
      } catch {
        feedback = [`${PLAN_OUTPUT_PATH} missing or not valid JSON`];
        round++;
        continue;
      }

      // `force` never bypasses consultation schema/path/snapshot checks - unlike the build path,
      // these are required-for-progress correctness gates, not developer-overridable heuristics.
      let report: PlanConsultationReport;
      try {
        report = await collectConsultationReport(worktreePath, baseCommit, consultation.paths, raw);
      } catch (err) {
        feedback = [err instanceof Error ? err.message : String(err)];
        round++;
        continue;
      }

      let status: 'ok' | 'gone';
      try {
        status = await client.postPlanResult(lease, report);
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        feedback = [message.replace(/^request failed: \d+ /, '')];
        round++;
        continue;
      }
      if (status === 'gone') {
        console.log('[pdlc-assist] lease lost while submitting - task reassigned');
        return 3;
      }
      console.log('[pdlc-assist] plan consultation submitted');
      return 0;
    }
  } finally {
    await removeWorktree(repo.path, worktreePath);
    await rm(controlDir, { recursive: true, force: true });
  }
}

async function runAssistedBuild(
  cfg: AgentConfig,
  client: ApiClient,
  lease: Lease,
  payload: BuildPayload,
  repo: RepoHandle,
  deps: AssistDeps,
  maxMinutes: number,
  leaseSignal: AbortSignal,
): Promise<number> {
  const { task, branch, baseBranch, story } = payload;
  let feedback = payload.feedback;
  await repo.sync(branch, baseBranch);
  const worktreePath = await mkdtemp(path.join(tmpdir(), `pdlc-assist-${task.id}-`));
  const controlDir = await mkdtemp(path.join(tmpdir(), `pdlc-assist-ctl-${task.id}-`));
  const startedAt = Date.now();
  const deadlineAt = startedAt + maxMinutes * 60_000;
  let scope: BuildScope | undefined;

  try {
    await addWorktree(repo.path, worktreePath, branch, baseBranch);
    scope = await prepareBuildScope(worktreePath, task);
    const template = loadTemplate('build-task', cfg.promptTemplateDir);

    let round = 1;
    for (;;) {
      const briefing = buildTaskPrompt(
        template, task.id, task.title, task.description, task.scenario,
        task.touches, task.testPath, scope.testFileExists, scope.canWriteTestPath, feedback,
      );
      const brief: AssistBrief = {
        kind: 'build',
        claimId: lease.id,
        story,
        taskId: task.id,
        title: task.title,
        startedAt: new Date(startedAt).toISOString(),
        deadlineAt: new Date(deadlineAt).toISOString(),
        round,
        feedback,
        briefing,
        touches: scope.effectiveTouches,
        testPath: task.testPath,
      };

      const outcome = await runOmpRound(deps, controlDir, worktreePath, brief, leaseSignal, deadlineAt);

      if (outcome === 'lease-lost') {
        const patchPath = await savePatch(cfg, worktreePath, story, task, scope.startSha);
        console.log(`[pdlc-assist] lease lost - task reassigned; work saved at ${patchPath ?? '(no changes)'}`);
        return 3;
      }
      if (outcome === 'deadline') {
        const patchPath = await savePatch(cfg, worktreePath, story, task, scope.startSha);
        await client.postFail(lease, `pdlc-assist: exceeded --max-minutes ${maxMinutes}`);
        console.log(`[pdlc-assist] exceeded --max-minutes ${maxMinutes}; work saved at ${patchPath ?? '(no changes)'}`);
        return 0;
      }
      if (outcome.decision === 'cancel') {
        const patchPath = await savePatch(cfg, worktreePath, story, task, scope.startSha);
        await client.postFail(lease, `pdlc-assist: build cancelled by ${cfg.agentName}`);
        console.log(`[pdlc-assist] build cancelled; work saved at ${patchPath ?? '(no changes)'}`);
        return 0;
      }

      const { outOfScope } = await assessBuildScope(worktreePath, task, scope);
      const verifier = await runVerifier(worktreePath);
      if (!outcome.force && (outOfScope.length > 0 || verifier.result === 'red')) {
        const effectiveTouches = scope.effectiveTouches;
        feedback = [
          ...outOfScope.map((f) => `out of scope: ${f} (allowed: ${effectiveTouches.join(', ')})`),
          ...(verifier.result === 'red' ? [`verifier red: ${verifier.notes}`] : []),
        ];
        round++;
        continue;
      }

      const result = await finalizeBuild(worktreePath, repo, task, branch, scope, {
        iterations: round,
        traceSummary: `assisted by ${cfg.agentName}, ${round} session round(s)`,
        escalation: null,
      });
      const status = await client.postResult(lease, result);
      if (status === 'gone') {
        const patchPath = await savePatch(cfg, worktreePath, story, task, scope.startSha);
        console.log(`[pdlc-assist] lease lost while submitting - task reassigned; work saved at ${patchPath ?? '(no changes)'}`);
        return 3;
      }
      console.log(`[pdlc-assist] build submitted: commit=${result.commitSha} verifier=${result.verifier.result} (${result.verifier.notes})`);
      return 0;
    }
  } catch (err) {
    if (scope) {
      const patchPath = await savePatch(cfg, worktreePath, story, task, scope.startSha);
      if (patchPath) {
        console.log(`[pdlc-assist] work saved at ${patchPath} before reporting failure`);
      }
    }
    throw err;
  } finally {
    await removeWorktree(repo.path, worktreePath);
    await rm(controlDir, { recursive: true, force: true });
  }
}

/**
 * Claims exactly one plan or build task matching `args` and drives it to completion through an
 * interactive omp session instead of the autonomous ACP loop - see `worker.ts`'s `handleClaim` for
 * the autonomous counterpart this mirrors (same claim/heartbeat/result contract, reused via {@link
 * withClaimLease}). Blocks polling `client.claim()` until a matching task is pending.
 */
export async function runAssist(cfg: AgentConfig, client: ApiClient, args: AssistArgs, deps: AssistDeps = defaultDeps()): Promise<number> {
  console.log(`[pdlc-assist] waiting for pending ${args.kind} task story=${args.story} task=${args.kind === 'plan' ? 'plan' : args.task} ...`);
  let claimed: ClaimedTask | null = await client.claim(await describePresence(cfg));
  while (!claimed) {
    const { promise, resolve } = Promise.withResolvers<void>();
    setTimeout(resolve, cfg.pollIntervalMs);
    await promise;
    claimed = await client.claim(await describePresence(cfg));
  }

  if (claimed.payload.kind !== args.kind) {
    await client.postFail(claimed, 'pdlc-assist: claimed unexpected kind');
    return 1;
  }

  const lease: Lease = claimed;
  const repo = await resolveRepo(cfg, claimed.payload.repo);
  let exitCode = 1;
  try {
    if (claimed.payload.kind === 'plan') {
      const payload = claimed.payload;
      await withClaimLease(client, lease, async (leaseSignal) => {
        exitCode = await runAssistedPlan(cfg, client, lease, payload, repo, deps, args.maxMinutes, leaseSignal);
      });
    } else {
      const payload = claimed.payload;
      await withClaimLease(client, lease, async (leaseSignal) => {
        exitCode = await runAssistedBuild(cfg, client, lease, payload, repo, deps, args.maxMinutes, leaseSignal);
      });
    }
  } catch (err) {
    await client.postFail(lease, String(err));
    exitCode = 1;
  }
  return exitCode;
}

async function main(): Promise<void> {
  let args: AssistArgs;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (err) {
    console.error(err instanceof Error ? err.message : String(err));
    process.exit(2);
  }

  const cfg = loadConfig();
  const overriddenCfg: AgentConfig = {
    ...cfg,
    agentName: process.env.BUILD_AGENT_NAME || `assist:${os.userInfo().username}@${os.hostname()}`,
    filters: { profile: cfg.filters.profile, story: args.story, task: args.kind === 'plan' ? 'plan' : args.task },
  };
  const client = new ApiClient(overriddenCfg);
  const code = await runAssist(overriddenCfg, client, args);
  process.exit(code);
}

// Guarded so assist.test.ts can import `runAssist`/`parseArgs` without starting the real CLI.
if (require.main === module) {
  main().catch((err: unknown) => {
    console.error('[pdlc-assist] fatal:', err);
    process.exit(1);
  });
}
