import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { addWorktree, changedFiles, commitAll, currentBranch, removeWorktree, revParse } from './git';

const execFileAsync = promisify(execFile);

async function initBareStoryRepo(): Promise<string> {
  const repoPath = await mkdtemp(path.join(tmpdir(), 'pdlc-git-test-'));
  await execFileAsync('git', ['-C', repoPath, 'init', '-q']);
  await execFileAsync('git', ['-C', repoPath, 'config', 'user.email', 'test@example.test']);
  await execFileAsync('git', ['-C', repoPath, 'config', 'user.name', 'Test']);
  await writeFile(path.join(repoPath, 'README.md'), 'seed\n');
  await execFileAsync('git', ['-C', repoPath, 'add', '.']);
  await execFileAsync('git', ['-C', repoPath, 'commit', '-q', '-m', 'seed']);
  await execFileAsync('git', ['-C', repoPath, 'branch', '-M', 'main']);
  return repoPath;
}

test('changedFiles against baseBranch leaks an earlier round\'s commit into a later round\'s scope check', async () => {
  const repoPath = await initBareStoryRepo();
  const branch = 'story/T1';

  // Round 1: create the shared story branch and commit the task's own file (simulates the first
  // build attempt on this task).
  const round1 = await mkdtemp(path.join(tmpdir(), 'pdlc-git-test-wt1-'));
  await addWorktree(repoPath, round1, branch, 'main');
  await execFileAsync('mkdir', ['-p', path.join(round1, 'src')]);
  await writeFile(path.join(round1, 'src/export.js'), 'export function run() {}\n');
  await execFileAsync('git', ['-C', round1, 'add', '.']);
  await commitAll(round1, 'T1: round 1');
  await removeWorktree(repoPath, round1);

  // Round 2 (a fix round): re-open a worktree on the same branch, which already carries round 1's
  // commit. This session makes no changes at all.
  const round2 = await mkdtemp(path.join(tmpdir(), 'pdlc-git-test-wt2-'));
  await addWorktree(repoPath, round2, branch, 'main');
  const startSha = await revParse(round2, 'HEAD');

  const diffedAgainstBaseBranch = await changedFiles(round2, 'main');
  const diffedAgainstSessionStart = await changedFiles(round2, startSha);

  // The bug: diffing against baseBranch resurfaces round 1's already-committed file as "touched"
  // by this brand-new session, which would wrongly flag it out-of-scope and revert it.
  assert.ok(diffedAgainstBaseBranch.includes('src/export.js'),
    'sanity check: baseBranch diff includes the prior round\'s file (demonstrates the bug exists)');
  // The fix: diffing against this session's own starting commit sees no changes at all.
  assert.deepEqual(diffedAgainstSessionStart, []);

  await removeWorktree(repoPath, round2);
});

test('changedFiles against session start correctly reports only this session\'s own new file', async () => {
  const repoPath = await initBareStoryRepo();
  const branch = 'story/T2';

  const round1 = await mkdtemp(path.join(tmpdir(), 'pdlc-git-test-wt3-'));
  await addWorktree(repoPath, round1, branch, 'main');
  await execFileAsync('mkdir', ['-p', path.join(round1, 'src')]);
  await writeFile(path.join(round1, 'src/other.js'), 'export function other() {}\n');
  await execFileAsync('git', ['-C', round1, 'add', '.']);
  await commitAll(round1, 'T-other: prior task');
  await removeWorktree(repoPath, round1);

  const round2 = await mkdtemp(path.join(tmpdir(), 'pdlc-git-test-wt4-'));
  await addWorktree(repoPath, round2, branch, 'main');
  const startSha = await revParse(round2, 'HEAD');
  await writeFile(path.join(round2, 'src/mine.js'), 'export function mine() {}\n');

  const touched = await changedFiles(round2, startSha);

  assert.deepEqual(touched, ['src/mine.js']);
  await removeWorktree(repoPath, round2);
});

test('currentBranch reports the checked-out branch name', async () => {
  const repoPath = await initBareStoryRepo();
  assert.equal(await currentBranch(repoPath), 'main');
});

test('addWorktree on an existing branch removes an orphaned pdlc-task-* worktree from a crashed attempt', async () => {
  const repoPath = await initBareStoryRepo();
  const branch = 'story/orphan';

  // Simulates a crashed build-worker: claims T1, opens a `pdlc-task-*` worktree, commits, then
  // never calls removeWorktree (the process died before finalizeBuild's cleanup ran).
  const crashed = await mkdtemp(path.join(tmpdir(), 'pdlc-task-orphan-'));
  await addWorktree(repoPath, crashed, branch, 'main');
  await writeFile(path.join(crashed, 'committed.txt'), 'survives\n');
  await execFileAsync('git', ['-C', crashed, 'add', '.']);
  const committedSha = await commitAll(crashed, 'T1: crashed attempt committed this');
  await writeFile(path.join(crashed, 'uncommitted.txt'), 'discarded\n');

  // A reclaiming worker opens a second `pdlc-task-*` worktree on the same still-checked-out
  // branch - the existing-branch path must clear the orphan first, or `git worktree add` would
  // refuse (branch already checked out).
  const reclaimed = await mkdtemp(path.join(tmpdir(), 'pdlc-task-reclaim-'));
  await addWorktree(repoPath, reclaimed, branch, 'main');

  const { stdout: listing } = await execFileAsync('git', ['-C', repoPath, 'worktree', 'list', '--porcelain']);
  assert.ok(!listing.includes(crashed), 'orphaned worktree must be removed from the worktree list');
  assert.ok(listing.includes(reclaimed), 'reclaiming worktree must be present');

  // Committed work survives (branch history); uncommitted work is gone (fresh checkout).
  assert.equal(await revParse(reclaimed, 'HEAD'), committedSha);
  await assert.rejects(() => execFileAsync('git', ['-C', reclaimed, 'cat-file', '-e', 'HEAD:uncommitted.txt']));

  await removeWorktree(repoPath, reclaimed);
});

test('addWorktree still rejects a branch checked out by a non-pdlc-task-* worktree', async () => {
  const repoPath = await initBareStoryRepo();
  const branch = 'story/manual';

  const manual = await mkdtemp(path.join(tmpdir(), 'pdlc-git-test-manual-'));
  await addWorktree(repoPath, manual, branch, 'main');

  const second = await mkdtemp(path.join(tmpdir(), 'pdlc-git-test-manual2-'));
  await assert.rejects(() => addWorktree(repoPath, second, branch, 'main'));

  await removeWorktree(repoPath, manual);
});
