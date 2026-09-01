#!/usr/bin/env node
import { ApiClient } from './client';
import { loadConfig } from './config';
import { resolveRepo, type RepoHandle } from './repo';
import { runBuildTask } from './buildTask';
import type { BuildResult, ClaimedTask } from './types';

export type BuildRunner = (payload: ClaimedTask['payload'], repo: RepoHandle, opts: { acpAgent: string; promptTemplateDir?: string }, signal: AbortSignal) => Promise<BuildResult>;

/**
 * Runs one claimed task end to end: resolve its repo, heartbeat the claim lease every {@link
 * AgentConfig.heartbeatIntervalMs} while `runner` works, then post the result (or fail it on any
 * thrown error). A heartbeat coming back `'gone'` (lease expired/superseded server-side) aborts
 * `runner` via its `signal` - in that case nothing is posted back, the row is already dead.
 * `onController` lets `main` capture the in-flight controller for SIGINT/SIGTERM handling; it is
 * only relevant to the real poll loop, not the `poller.test.ts` seam that drives this directly.
 */
export async function handleClaim(
  client: ApiClient,
  claimed: ClaimedTask,
  runner: BuildRunner,
  onController?: (controller: AbortController) => void,
): Promise<void> {
  const cfg = client.cfg;
  const repo = await resolveRepo(cfg, claimed.payload.repo);
  const controller = new AbortController();
  onController?.(controller);

  const interval = setInterval(() => {
    client.heartbeat(claimed.id).then((status) => {
      if (status === 'gone') {
        controller.abort();
      }
    }, () => controller.abort());
  }, cfg.heartbeatIntervalMs);

  try {
    const result = await runner(claimed.payload, repo, { acpAgent: cfg.acpAgent, promptTemplateDir: cfg.promptTemplateDir }, controller.signal);
    await client.postResult(claimed.id, result);
  } catch (err) {
    if (!controller.signal.aborted) {
      await client.postFail(claimed.id, String(err));
    }
  } finally {
    clearInterval(interval);
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
    await handleClaim(client, claimed, runBuildTask, (controller) => {
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
