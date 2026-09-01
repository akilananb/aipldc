import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolveRepo } from './repo';
import { addWorktree, commitAll } from './git';
import type { AgentConfig } from './config';

const execFileAsync = promisify(execFile);

async function git(repoPath: string, args: string[]): Promise<string> {
  const { stdout } = await execFileAsync('git', ['-C', repoPath, ...args]);
  return stdout;
}

test('resolveRepo remote mode clones a bare cache, syncs with the story branch absent, and push round-trips to origin', async () => {
  // A real local `origin` repo (TS mirror of LocalGitRepoAdapterContractTest's seeding), reached
  // over `file://` so this needs no network and no test double for git itself.
  const originDir = await mkdtemp(path.join(tmpdir(), 'pdlc-repo-origin-'));
  await git(originDir, ['init', '-b', 'main']);
  await git(originDir, ['config', 'user.email', 'test@local']);
  await git(originDir, ['config', 'user.name', 'test']);
  await writeFile(path.join(originDir, 'README.md'), 'seed\n');
  await git(originDir, ['add', '-A']);
  await git(originDir, ['commit', '-m', 'seed']);

  const cacheDir = await mkdtemp(path.join(tmpdir(), 'pdlc-repo-cache-'));
  const cfg: AgentConfig = {
    apiUrl: 'http://unused',
    agentName: 'test-agent',
    filters: { profile: 'local' },
    pollIntervalMs: 5000,
    heartbeatIntervalMs: 30_000,
    cacheDir,
    acpAgent: 'omp acp',
  };

  const repo = await resolveRepo(cfg, {
    provider: 'git',
    url: `file://${originDir}`,
    defaultBranch: 'main',
    specDir: 'openspec',
  });

  assert.ok(repo.path.startsWith(path.join(cacheDir, 'repos')), 'expected a bare cache under cacheDir/repos');
  assert.ok(existsSync(path.join(repo.path, 'HEAD')), 'expected a bare clone to exist');

  // The story branch does not exist on origin yet (first task in the story) - sync must still
  // succeed, only the base branch fetch is required to.
  await repo.sync('story/1', 'main');

  const worktreePath = await mkdtemp(path.join(tmpdir(), 'pdlc-repo-worktree-'));
  await addWorktree(repo.path, worktreePath, 'story/1', 'main');
  await writeFile(path.join(worktreePath, 'change.txt'), 'hello\n');
  const sha = await commitAll(worktreePath, 'add change');
  await repo.push(worktreePath, 'story/1');

  const originSha = (await git(originDir, ['rev-parse', 'story/1'])).trim();
  assert.equal(originSha, sha);
});
