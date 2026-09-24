#!/usr/bin/env node
import { ApiClient } from './client';
import { loadConfig, type AgentConfig } from './config';
import { currentBranch } from './git';
import { resolveRepo, type RepoHandle } from './repo';
import { runBuildTask } from './buildTask';
import { runPlanTask } from './planTask';
import type { AgentPresenceReport, BuildPayload, BuildResult, ClaimedTask, Lease, PlanConsultationReport, PlanPayload } from './types';

export type BuildRunner = (payload: BuildPayload, repo: RepoHandle, opts: { acpAgent: string; promptTemplateDir?: string }, signal: AbortSignal) => Promise<BuildResult>;
export type PlanRunner = (payload: PlanPayload, repo: RepoHandle, opts: { acpAgent: string; promptTemplateDir?: string }, signal: AbortSignal) => Promise<PlanConsultationReport>;

/** What to attach to every claim poll so the control plane can show this agent's liveness,
 * per-repo presence, and branch (`GET /api/agents`) - see `AgentPresenceService#touchAcpOnClaim`.
 * A `local` override's branch is read fresh each poll (cheap `git rev-parse`), so a developer
 * switching branches in the shared checkout shows up within one poll interval; unresolvable (e.g.
 * detached worktree mid-op) degrades to `null` rather than failing the claim. */
export async function describePresence(cfg: AgentConfig): Promise<AgentPresenceReport> {
  const repos: AgentPresenceReport['repos'] =
    cfg.repoOverrides.size === 0
      ? [{ id: '*', mode: 'payload', location: null, branch: null }]
      : await Promise.all(
          [...cfg.repoOverrides.entries()].map(async ([id, override]) =>
            override.mode === 'local'
              ? { id, mode: 'local' as const, location: override.path, branch: await currentBranch(override.path).catch(() => null) }
              : { id, mode: 'remote' as const, location: override.url, branch: null },
          ),
        );
  return { acpAgent: cfg.acpAgent, pollIntervalMs: cfg.pollIntervalMs, repos };
}

/**
 * Owns the claim's lease while `run` works: heartbeats every {@link AgentConfig.heartbeatIntervalMs}
 * (extracted so `pdlc-assist`'s single-claim CLI can reuse identical lease semantics without the
 * plan/build dispatch baked in). A heartbeat coming back `'gone'` (lease expired/superseded
 * server-side) or rejecting aborts `run`'s signal. Does NOT catch errors thrown by `run` - the
 * caller decides whether/how to report them (see {@link handleClaim}'s try/catch, which needs the
 * controller's `aborted` state to decide whether to post a failure).
 */
export async function withClaimLease(
  client: ApiClient,
  lease: Lease,
  run: (signal: AbortSignal) => Promise<void>,
  onController?: (controller: AbortController) => void,
): Promise<void> {
  const cfg = client.cfg;
  const controller = new AbortController();
  onController?.(controller);

  const interval = setInterval(() => {
    client.heartbeat(lease).then((status) => {
      if (status === 'gone') {
        controller.abort();
      }
    }, () => controller.abort());
  }, cfg.heartbeatIntervalMs);

  try {
    await run(controller.signal);
  } finally {
    clearInterval(interval);
  }
}

/**
 * Runs one claimed task end to end: resolve its repo, then dispatch plan/build under {@link
 * withClaimLease} and post the result (or fail it on any thrown error). `onController` lets `main`
 * capture the in-flight controller for SIGINT/SIGTERM handling; it is only relevant to the real
 * poll loop, not the `poller.test.ts` seam that drives this directly.
 */
export async function handleClaim(
  client: ApiClient,
  claimed: ClaimedTask,
  runners: { build: BuildRunner; plan: PlanRunner },
  onController?: (controller: AbortController) => void,
): Promise<void> {
  const cfg = client.cfg;
  const repo = await resolveRepo(cfg, claimed.payload.repo);
  let capturedController: AbortController | undefined;

  try {
    await withClaimLease(
      client,
      claimed,
      async (signal) => {
        const opts = { acpAgent: cfg.acpAgent, promptTemplateDir: cfg.promptTemplateDir };
        if (claimed.payload.kind === 'plan') {
          const result = await runners.plan(claimed.payload, repo, opts, signal);
          await client.postPlanResult(claimed, result);
        } else {
          const result = await runners.build(claimed.payload, repo, opts, signal);
          await client.postResult(claimed, result);
        }
      },
      (controller) => {
        capturedController = controller;
        onController?.(controller);
      },
    );
  } catch (err) {
    if (!capturedController?.signal.aborted) {
      await client.postFail(claimed, String(err));
    }
  }
}

function sleep(ms: number): Promise<void> {
  const { promise, resolve } = Promise.withResolvers<void>();
  setTimeout(resolve, ms);
  return promise;
}

async function main(): Promise<void> {
  const cfg = loadConfig();
  const client = new ApiClient(cfg);
  console.log(
    `[build-agent] polling ${cfg.apiUrl} profile=${cfg.filters.profile} story=${cfg.filters.story ?? 'any'} ` +
      `task=${cfg.filters.task ?? 'any'} acp-agent="${cfg.acpAgent}"`,
  );

  let shuttingDown = false;
  let activeController: AbortController | undefined;
  const shutdown = () => {
    shuttingDown = true;
    activeController?.abort();
    process.exit(130);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  // One task at a time (per process) - wave tasks share one story branch (FeatureWorkflowImpl.java:144,156)
  // and `git worktree add` refuses a branch checked out elsewhere, so single-flight per process is
  // correct; the server serializes per story across processes (one live claim per story_board_id),
  // so a pool of workers only parallelizes across different stories.
  while (!shuttingDown) {
    const claimed = await client.claim(await describePresence(cfg));
    if (!claimed) {
      await sleep(cfg.pollIntervalMs);
      continue;
    }
    if (claimed.claimCount > 1) {
      console.log(`[build-agent] reclaimed ${claimed.id} (claim #${claimed.claimCount}) - a previous worker lost its lease`);
    }
    // Plan and build tasks both run single-flight through this same claim loop - a plan step
    // also uses a worktree of the repo, so two in-flight claims per agent process is unsafe.
    await handleClaim(client, claimed, { build: runBuildTask, plan: runPlanTask }, (controller) => {
      activeController = controller;
    });
    activeController = undefined;
  }
}

// Guarded so poller.test.ts can import `handleClaim` without starting the real poll loop.
if (require.main === module) {
  main().catch((err: unknown) => {
    console.error('[build-agent] fatal:', err);
    process.exit(1);
  });
}
