#!/usr/bin/env node
import { ApiClient } from './client';
import { loadConfig } from './config';
import { resolveRepo, type RepoHandle } from './repo';
import { runBuildTask } from './buildTask';
import { runPlanTask } from './planTask';
import type { BuildPayload, BuildResult, ClaimedTask, PlanPayload, PlanResult } from './types';

export type BuildRunner = (payload: BuildPayload, repo: RepoHandle, opts: { acpAgent: string; promptTemplateDir?: string }, signal: AbortSignal) => Promise<BuildResult>;
export type PlanRunner = (payload: PlanPayload, repo: RepoHandle, opts: { acpAgent: string; promptTemplateDir?: string }, signal: AbortSignal) => Promise<PlanResult>;

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
  claimedId: string,
  run: (signal: AbortSignal) => Promise<void>,
  onController?: (controller: AbortController) => void,
): Promise<void> {
  const cfg = client.cfg;
  const controller = new AbortController();
  onController?.(controller);

  const interval = setInterval(() => {
    client.heartbeat(claimedId).then((status) => {
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
      claimed.id,
      async (signal) => {
        const opts = { acpAgent: cfg.acpAgent, promptTemplateDir: cfg.promptTemplateDir };
        if (claimed.payload.kind === 'plan') {
          const result = await runners.plan(claimed.payload, repo, opts, signal);
          await client.postPlanResult(claimed.id, result);
        } else {
          const result = await runners.build(claimed.payload, repo, opts, signal);
          await client.postResult(claimed.id, result);
        }
      },
      (controller) => {
        capturedController = controller;
        onController?.(controller);
      },
    );
  } catch (err) {
    if (!capturedController?.signal.aborted) {
      await client.postFail(claimed.id, String(err));
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

  // One task at a time - wave tasks share one story branch (FeatureWorkflowImpl.java:144,156)
  // and `git worktree add` refuses a branch checked out elsewhere, so single-flight is correct.
  while (!shuttingDown) {
    const claimed = await client.claim();
    if (!claimed) {
      await sleep(cfg.pollIntervalMs);
      continue;
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
