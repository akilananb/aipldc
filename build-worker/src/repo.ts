import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import type { AgentConfig } from './config';
import type { ClaimedTask } from './types';
import { basicAuthHeader, cloneBare, fetchBranch, pushBranch } from './git';

export interface RepoHandle {
  path: string;
  sync(branch: string, baseBranch: string): Promise<void>;
  push(worktreePath: string, branch: string): Promise<void>;
}

/** Bare-repo cache handles keyed by remote URL, so repeated claims against the same repo across
 * polls reuse the same clone instead of re-cloning every task. */
const remoteHandles = new Map<string, Promise<RepoHandle>>();

/** Resolves the repo a claimed task's build loop should run against. Precedence: {@link
 * AgentConfig.repoOverride} (host-level env) wins over the claim payload's repo; otherwise a
 * `local-git` payload provider means control-plane and this agent share a filesystem (the e2e
 * `local` profile), and anything else is a real remote to clone/fetch/push over HTTP(S). */
export async function resolveRepo(cfg: AgentConfig, payloadRepo: ClaimedTask['payload']['repo']): Promise<RepoHandle> {
  if (cfg.repoOverride?.mode === 'local') {
    return localHandle(cfg.repoOverride.path);
  }
  if (cfg.repoOverride?.mode === 'remote') {
    return remoteHandle(cfg, cfg.repoOverride.url);
  }
  if (payloadRepo.provider === 'local-git') {
    return localHandle(payloadRepo.url);
  }
  return remoteHandle(cfg, payloadRepo.url);
}

function localHandle(repoPath: string): RepoHandle {
  if (!existsSync(path.join(repoPath, '.git'))) {
    throw new Error(`not a git repository: ${repoPath}`);
  }
  return {
    path: repoPath,
    // control-plane and this agent share a filesystem in local mode - the worktree operates
    // directly on the shared repo, nothing to fetch first or push after.
    sync: async () => undefined,
    push: async () => undefined,
  };
}

function remoteHandle(cfg: AgentConfig, url: string): Promise<RepoHandle> {
  let handle = remoteHandles.get(url);
  if (!handle) {
    handle = createRemoteHandle(cfg, url);
    remoteHandles.set(url, handle);
  }
  return handle;
}

async function createRemoteHandle(cfg: AgentConfig, url: string): Promise<RepoHandle> {
  const authHeader = cfg.repoToken ? basicAuthHeader(cfg.repoToken) : undefined;
  const hash = createHash('sha256').update(url).digest('hex').slice(0, 16);
  const bareDir = path.join(cfg.cacheDir, 'repos', `${hash}.git`);
  if (!existsSync(bareDir)) {
    await mkdir(path.dirname(bareDir), { recursive: true });
    await cloneBare(url, bareDir, authHeader);
  }
  return {
    path: bareDir,
    async sync(branch: string, baseBranch: string) {
      await fetchBranch(bareDir, baseBranch, authHeader);
      // The story branch may not exist yet (first task in the story) - that is not an error.
      await fetchBranch(bareDir, branch, authHeader).catch(() => undefined);
    },
    async push(worktreePath: string, branch: string) {
      await pushBranch(worktreePath, branch, authHeader);
    },
  };
}
