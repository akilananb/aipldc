import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

function isErrnoWithStderr(e: unknown): e is { stderr?: string; message: string } {
  return typeof e === 'object' && e !== null && 'message' in e;
}

async function git(repoPath: string, args: string[]): Promise<string> {
  try {
    const { stdout } = await execFileAsync('git', ['-C', repoPath, ...args], { maxBuffer: 32 * 1024 * 1024 });
    return stdout;
  } catch (e: unknown) {
    const detail = isErrnoWithStderr(e) ? (e.stderr ?? e.message) : String(e);
    throw new Error(`git ${args.join(' ')} failed: ${detail}`);
  }
}

export async function branchExists(repoPath: string, branch: string): Promise<boolean> {
  try {
    await git(repoPath, ['rev-parse', '--verify', `refs/heads/${branch}`]);
    return true;
  } catch {
    return false;
  }
}

/** Adds a worktree at `worktreePath` on `branch`, creating the branch off `baseBranch` if it does
 * not exist yet (the first task in a story's build loop) or checking it out as-is (later tasks
 * continuing the same shared branch). */
export async function addWorktree(repoPath: string, worktreePath: string, branch: string, baseBranch: string): Promise<void> {
  if (await branchExists(repoPath, branch)) {
    await git(repoPath, ['worktree', 'add', worktreePath, branch]);
  } else {
    await git(repoPath, ['worktree', 'add', '-b', branch, worktreePath, baseBranch]);
  }
}

export async function removeWorktree(repoPath: string, worktreePath: string): Promise<void> {
  try {
    await git(repoPath, ['worktree', 'remove', '--force', worktreePath]);
  } catch {
    // best-effort: an already-gone worktree dir must not fail the activity
    await git(repoPath, ['worktree', 'prune']).catch(() => undefined);
  }
}

/** Files changed in the worktree relative to `baseBranch` - staged, unstaged, and untracked. */
export async function changedFiles(worktreePath: string, baseBranch: string): Promise<string[]> {
  const diffed = await git(worktreePath, ['diff', '--name-only', baseBranch]);
  const untracked = await git(worktreePath, ['ls-files', '--others', '--exclude-standard']);
  const files = new Set<string>();
  for (const line of [...diffed.split('\n'), ...untracked.split('\n')]) {
    const trimmed = line.trim();
    if (trimmed) {
      files.add(trimmed);
    }
  }
  return [...files];
}

/** Reverts (discards) the given paths in the worktree, tracked or untracked. */
export async function revertPaths(worktreePath: string, paths: string[]): Promise<void> {
  for (const path of paths) {
    await git(worktreePath, ['checkout', '--', path]).catch(() =>
      // untracked file: checkout has nothing to restore from - just delete it
      execFileAsync('rm', ['-f', `${worktreePath}/${path}`]).catch(() => undefined)
    );
  }
}

export async function hasChanges(worktreePath: string): Promise<boolean> {
  const status = await git(worktreePath, ['status', '--porcelain']);
  return status.trim().length > 0;
}

export async function commitAll(worktreePath: string, message: string): Promise<string> {
  await git(worktreePath, ['add', '-A']);
  await git(worktreePath, [
    '-c', 'user.name=build-worker',
    '-c', 'user.email=build-worker@local',
    'commit', '-m', message,
  ]);
  return (await git(worktreePath, ['rev-parse', 'HEAD'])).trim();
}

export async function revParse(repoPath: string, ref: string): Promise<string> {
  return (await git(repoPath, ['rev-parse', ref])).trim();
}

export function basicAuthHeader(token: string): string {
  return `Authorization: Basic ${Buffer.from(`x-access-token:${token}`).toString('base64')}`;
}

/** Bare-clones `url` into `dir`, then widens the default single-branch fetch refspec to every
 * branch - later `fetchBranch` calls need arbitrary story/base branches, not just the one HEAD
 * pointed at when cloned. Token travels per-command via `-c http.extraHeader=…`, never written
 * to disk. */
export async function cloneBare(url: string, dir: string, authHeader?: string): Promise<void> {
  const authArgs = authHeader ? ['-c', `http.extraHeader=${authHeader}`] : [];
  try {
    await execFileAsync('git', [...authArgs, 'clone', '--bare', url, dir], { maxBuffer: 32 * 1024 * 1024 });
  } catch (e: unknown) {
    const detail = isErrnoWithStderr(e) ? (e.stderr ?? e.message) : String(e);
    throw new Error(`git clone --bare ${url} failed: ${detail}`);
  }
  await git(dir, ['config', 'remote.origin.fetch', '+refs/heads/*:refs/heads/*']);
}

/** `+` force-refspec is safe: every commit is pushed immediately after committing (buildTask.ts),
 * so a clobbered local head is only ever a crashed attempt Temporal has already superseded. */
export async function fetchBranch(repoPath: string, branch: string, authHeader?: string): Promise<void> {
  const authArgs = authHeader ? ['-c', `http.extraHeader=${authHeader}`] : [];
  await git(repoPath, [...authArgs, 'fetch', 'origin', `+refs/heads/${branch}:refs/heads/${branch}`]);
}

export async function pushBranch(worktreePath: string, branch: string, authHeader?: string): Promise<void> {
  const authArgs = authHeader ? ['-c', `http.extraHeader=${authHeader}`] : [];
  await git(worktreePath, [...authArgs, 'push', 'origin', branch]);
}
