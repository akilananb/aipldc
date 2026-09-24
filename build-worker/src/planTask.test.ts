import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { collectConsultationReport, isValidRepoPath, resolvePinnedCommit } from './planTask';
import { addDetachedWorktree, removeWorktree } from './git';
import type { RepoHandle } from './repo';

const execFileAsync = promisify(execFile);

async function initRepo(): Promise<string> {
  const repoPath = await mkdtemp(path.join(tmpdir(), 'pdlc-plantask-test-repo-'));
  await execFileAsync('git', ['-C', repoPath, 'init', '-q']);
  await execFileAsync('git', ['-C', repoPath, 'config', 'user.email', 'test@example.test']);
  await execFileAsync('git', ['-C', repoPath, 'config', 'user.name', 'Test']);
  await writeFile(path.join(repoPath, 'README.md'), 'seed\n');
  await execFileAsync('git', ['-C', repoPath, 'add', '.']);
  await execFileAsync('git', ['-C', repoPath, 'commit', '-q', '-m', 'seed']);
  await execFileAsync('git', ['-C', repoPath, 'branch', '-M', 'main']);
  return repoPath;
}

async function headSha(repoPath: string, ref = 'main'): Promise<string> {
  const { stdout } = await execFileAsync('git', ['-C', repoPath, 'rev-parse', ref]);
  return stdout.trim();
}

function fakeRepoHandle(repoPath: string): RepoHandle {
  return { path: repoPath, sync: async () => undefined, push: async () => undefined };
}

async function withWorktree(repoPath: string, ref: string, fn: (worktreePath: string) => Promise<void>): Promise<void> {
  const worktreePath = await mkdtemp(path.join(tmpdir(), 'pdlc-plantask-test-wt-'));
  await addDetachedWorktree(repoPath, worktreePath, ref);
  try {
    await fn(worktreePath);
  } finally {
    await removeWorktree(repoPath, worktreePath);
  }
}

test('isValidRepoPath rejects absolute paths, parent segments, and .git/.pdlc first segments', () => {
  assert.equal(isValidRepoPath('src/export.ts'), true);
  assert.equal(isValidRepoPath('/etc/passwd'), false);
  assert.equal(isValidRepoPath('C:\\evil'), false);
  assert.equal(isValidRepoPath('../secret'), false);
  assert.equal(isValidRepoPath('src/../../escape'), false);
  assert.equal(isValidRepoPath('.git/config'), false);
  assert.equal(isValidRepoPath('.pdlc/consultation.json'), false);
  assert.equal(isValidRepoPath(''), false);
});

test('resolvePinnedCommit returns the exact requested SHA unchanged even after the branch moves', async () => {
  const repoPath = await initRepo();
  const firstSha = await headSha(repoPath);

  // Move the branch forward - a second consultation round must stay pinned to firstSha, never
  // silently follow main.
  await writeFile(path.join(repoPath, 'second.txt'), 'more\n');
  await execFileAsync('git', ['-C', repoPath, 'add', '.']);
  await execFileAsync('git', ['-C', repoPath, 'commit', '-q', '-m', 'second']);
  const movedSha = await headSha(repoPath);
  assert.notEqual(firstSha, movedSha);

  const repo = fakeRepoHandle(repoPath);
  const pinned = await resolvePinnedCommit(repo, 'main', firstSha);
  assert.equal(pinned, firstSha);

  // Round 1 (no prior pin) resolves the moving branch's current HEAD.
  const firstRound = await resolvePinnedCommit(repo, 'main', '');
  assert.equal(firstRound, movedSha);
});

test('resolvePinnedCommit rejects a malformed pinned commit rather than silently falling back to HEAD', async () => {
  const repoPath = await initRepo();
  const repo = fakeRepoHandle(repoPath);
  await assert.rejects(resolvePinnedCommit(repo, 'main', 'not-a-sha'));
});

test('collectConsultationReport computes filesystem existence, never trusting a raw exists claim', async () => {
  const repoPath = await initRepo();
  const sha = await headSha(repoPath);

  await withWorktree(repoPath, sha, async (worktreePath) => {
    const raw = {
      findingsMarkdown: 'README.md exists; src/export.ts does not.',
      // Raw output carries no exists flags at all in this schema - existence is always
      // filesystem-computed, never LLM-asserted.
      paths: ['README.md', 'src/export.ts'],
    };

    const report = await collectConsultationReport(worktreePath, sha, ['README.md', 'src/export.ts'], raw);

    assert.equal(report.baseCommit, sha);
    const readme = report.files.find((f) => f.path === 'README.md');
    const missing = report.files.find((f) => f.path === 'src/export.ts');
    assert.equal(readme?.exists, true);
    assert.equal(missing?.exists, false);
  });
});

test('collectConsultationReport rejects malformed output shape', async () => {
  const repoPath = await initRepo();
  const sha = await headSha(repoPath);
  await withWorktree(repoPath, sha, async (worktreePath) => {
    await assert.rejects(
      collectConsultationReport(worktreePath, sha, [], { paths: [] }),
      /findingsMarkdown/,
    );
  });
});

test('collectConsultationReport rejects an output that includes a tasks field', async () => {
  const repoPath = await initRepo();
  const sha = await headSha(repoPath);
  await withWorktree(repoPath, sha, async (worktreePath) => {
    await assert.rejects(
      collectConsultationReport(worktreePath, sha, [], { findingsMarkdown: 'x', paths: [], tasks: [] }),
      /tasks field/,
    );
  });
});

test('collectConsultationReport rejects an unexpected source mutation outside the output artifact', async () => {
  const repoPath = await initRepo();
  const sha = await headSha(repoPath);
  await withWorktree(repoPath, sha, async (worktreePath) => {
    await writeFile(path.join(worktreePath, 'README.md'), 'mutated by the consultant\n');
    await assert.rejects(
      collectConsultationReport(worktreePath, sha, [], { findingsMarkdown: 'x', paths: [] }),
      /scope violation/,
    );
  });
});

test('collectConsultationReport rejects a symlink escaping the worktree', async () => {
  const repoPath = await initRepo();
  const outsideTarget = await mkdtemp(path.join(tmpdir(), 'pdlc-plantask-outside-'));
  await writeFile(path.join(outsideTarget, 'secret.txt'), 'nope\n');
  // Commit the escaping symlink into the repo itself, so it is part of the base commit (not a
  // new change the source-mutation scope check would flag first) - this isolates the
  // symlink-specific rejection path from the generic "unexpected change" one.
  await symlink(path.join(outsideTarget, 'secret.txt'), path.join(repoPath, 'escape.txt'));
  await execFileAsync('git', ['-C', repoPath, 'add', 'escape.txt']);
  await execFileAsync('git', ['-C', repoPath, 'commit', '-q', '-m', 'add escaping symlink']);
  const sha = await headSha(repoPath);

  await withWorktree(repoPath, sha, async (worktreePath) => {
    await assert.rejects(
      collectConsultationReport(worktreePath, sha, ['escape.txt'], { findingsMarkdown: 'x', paths: ['escape.txt'] }),
      /escap(?:es|ing) the worktree/,
    );
  });
});
