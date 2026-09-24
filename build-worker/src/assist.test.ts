import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { ApiClient } from './client';
import type { AgentConfig } from './config';
import type { ClaimedTask } from './types';
import { runAssist, type AssistArgs, type AssistDeps, type OmpLaunch } from './assist';
import { writeDecision } from './assistProtocol';

const execFileAsync = promisify(execFile);

interface RecordedRequest {
  method: string;
  url: string;
  body: unknown;
}

function startServer(
  handler: (req: RecordedRequest, res: http.ServerResponse) => void,
): Promise<{ server: http.Server; port: number; requests: RecordedRequest[] }> {
  const { promise, resolve } = Promise.withResolvers<{ server: http.Server; port: number; requests: RecordedRequest[] }>();
  const requests: RecordedRequest[] = [];
  const server = http.createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => chunks.push(chunk));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      const recorded: RecordedRequest = { method: req.method ?? '', url: req.url ?? '', body: raw ? JSON.parse(raw) : undefined };
      requests.push(recorded);
      handler(recorded, res);
    });
  });
  server.listen(0, '127.0.0.1', () => {
    const address = server.address();
    if (address === null || typeof address === 'string') {
      throw new Error('expected server to bind a TCP port');
    }
    resolve({ server, port: address.port, requests });
  });
  return promise;
}

async function initRepo(): Promise<string> {
  const repoPath = await mkdtemp(path.join(tmpdir(), 'pdlc-assist-test-repo-'));
  await execFileAsync('git', ['-C', repoPath, 'init', '-q']);
  await execFileAsync('git', ['-C', repoPath, 'config', 'user.email', 'test@example.test']);
  await execFileAsync('git', ['-C', repoPath, 'config', 'user.name', 'Test']);
  await writeFile(path.join(repoPath, 'README.md'), 'seed\n');
  await execFileAsync('git', ['-C', repoPath, 'add', '.']);
  await execFileAsync('git', ['-C', repoPath, 'commit', '-q', '-m', 'seed']);
  await execFileAsync('git', ['-C', repoPath, 'branch', '-M', 'main']);
  return repoPath;
}

async function worktreeCount(repoPath: string): Promise<number> {
  const { stdout } = await execFileAsync('git', ['-C', repoPath, 'worktree', 'list', '--porcelain']);
  return stdout.split('\n').filter((line) => line.startsWith('worktree ')).length;
}

function baseConfig(port: number, repoPath: string, cacheDir: string): AgentConfig {
  return {
    apiUrl: `http://127.0.0.1:${port}`,
    agentName: 'assist:test@host',
    filters: { profile: 'local' },
    pollIntervalMs: 20,
    heartbeatIntervalMs: 20,
    repoOverrides: new Map([['main', { mode: 'local', path: repoPath }]]),
    cacheDir,
    acpAgent: 'omp acp',
  };
}

function immediateLaunch(): OmpLaunch {
  return { exited: Promise.resolve(), kill: () => undefined };
}

test('runAssist (build): a cancel decision from the first round fails the claim and leaves no leftover worktree', async () => {
  const repoPath = await initRepo();
  const cacheDir = await mkdtemp(path.join(tmpdir(), 'pdlc-assist-test-cache-'));
  const { server, port, requests } = await startServer((req, res) => {
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim') {
      const claimed: ClaimedTask = {
        id: 'claim-1',
        attempt: 1,
        leaseToken: 'lease-claim-1',
        claimCount: 1,
        payload: {
          kind: 'build',
          story: { profile: 'local', boardId: '4414' },
          task: {
            id: 'T1',
            title: 'Add CSV export',
            description: 'brief',
            area: 'orders',
            repo: 'main',
            scenario: 'export-csv',
            touches: ['src/export.js'],
            testPath: 'test/export.test.js',
            budget: { maxIterations: 5, maxTokens: 20_000, maxWallClock: 'PT10M' },
            blockedBy: [],
          },
          branch: 'story/4414',
          baseBranch: 'main',
          feedback: [],
          repo: { id: 'main', provider: 'local-git', url: 'unused', defaultBranch: 'main', specDir: 'openspec', areas: [], primary: true },
        },
      };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(claimed));
      return;
    }
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim-1/heartbeat') {
      res.writeHead(200);
      res.end();
      return;
    }
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim-1/fail') {
      res.writeHead(200);
      res.end();
      return;
    }
    res.writeHead(404);
    res.end();
  });

  try {
    const cfg = baseConfig(port, repoPath, cacheDir);
    const client = new ApiClient(cfg);
    const args: AssistArgs = { kind: 'build', story: '4414', task: 'T1', maxMinutes: 25 };
    const deps: AssistDeps = {
      launchOmp(_worktreePath, controlDir) {
        // Simulates the developer running `/pdlc cancel` in the omp session - `exited` must not
        // resolve before the decision file lands, or `runOmpRound` reads a stale/missing decision.
        return { exited: writeDecision(controlDir, { decision: 'cancel' }), kill: () => undefined };
      },
    };

    const code = await runAssist(cfg, client, args, deps);

    assert.equal(code, 0);
    const failReq = requests.find((r) => r.url === '/api/build-tasks/claim-1/fail');
    assert.ok(failReq, 'expected a posted failure');
    const body = failReq.body as { message: string };
    assert.ok(body.message.startsWith('pdlc-assist: build cancelled by'), body.message);
    assert.equal(requests.some((r) => r.url === '/api/build-tasks/claim-1/result'), false);
    assert.equal(await worktreeCount(repoPath), 1, 'expected only the main worktree to remain');
  } finally {
    server.close();
  }
});

test('runAssist (plan): a submit decision with a valid consultation.json posts the consultation report', async () => {
  const repoPath = await initRepo();
  const { stdout: headShaRaw } = await execFileAsync('git', ['-C', repoPath, 'rev-parse', 'main']);
  const headSha = headShaRaw.trim();
  const cacheDir = await mkdtemp(path.join(tmpdir(), 'pdlc-assist-test-cache-'));
  const { server, port, requests } = await startServer((req, res) => {
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim') {
      const claimed: ClaimedTask = {
        id: 'claim-2',
        attempt: 1,
        leaseToken: 'lease-claim-2',
        claimCount: 1,
        payload: {
          kind: 'plan',
          story: { profile: 'local', boardId: '4414' },
          po: { change: 'openspec/changes/add-export', scenarios: ['export-csv'], areas: ['orders'], nfr: {} },
          baseBranch: 'main',
          repo: { id: 'main', provider: 'local-git', url: 'unused', defaultBranch: 'main', specDir: 'openspec', areas: [], primary: true },
          consultation: {
            round: 1,
            repoId: 'main',
            questions: ['Where is export implemented and what test file should prove it?'],
            paths: ['README.md', 'src/export.js'],
            baseCommit: '',
            history: [],
          },
        },
      };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(claimed));
      return;
    }
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim-2/heartbeat') {
      res.writeHead(200);
      res.end();
      return;
    }
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim-2/plan-result') {
      res.writeHead(200);
      res.end();
      return;
    }
    res.writeHead(404);
    res.end();
  });

  try {
    const cfg = baseConfig(port, repoPath, cacheDir);
    const client = new ApiClient(cfg);
    const args: AssistArgs = { kind: 'plan', story: '4414', maxMinutes: 25 };
    const deps: AssistDeps = {
      launchOmp(worktreePath, controlDir) {
        const exited = (async () => {
          await mkdir(path.join(worktreePath, '.pdlc'), { recursive: true });
          await writeFile(
            path.join(worktreePath, '.pdlc/consultation.json'),
            JSON.stringify({
              findingsMarkdown: 'README.md documents the project; src/export.js does not exist yet.',
              paths: ['README.md', 'src/export.js'],
            }),
            'utf8',
          );
          await writeDecision(controlDir, { decision: 'submit', force: false });
        })();
        return { exited, kill: () => undefined };
      },
    };

    const code = await runAssist(cfg, client, args, deps);

    assert.equal(code, 0);
    const planReq = requests.find((r) => r.url === '/api/build-tasks/claim-2/plan-result');
    assert.ok(planReq, 'expected a posted consultation result');
    const body = planReq.body as { baseCommit: string; findingsMarkdown: string; files: Array<{ path: string; exists: boolean }> };
    assert.equal(body.baseCommit, headSha);
    assert.ok(body.findingsMarkdown.includes('README.md'));
    const readme = body.files.find((f) => f.path === 'README.md');
    const exportFile = body.files.find((f) => f.path === 'src/export.js');
    assert.equal(readme?.exists, true);
    assert.equal(exportFile?.exists, false);
    assert.equal(await worktreeCount(repoPath), 1, 'expected only the main worktree to remain');
  } finally {
    server.close();
  }
});

test('runAssist (build): a launch failure (e.g. omp not on PATH) is reported as a real failure, not a cancel', async () => {
  const repoPath = await initRepo();
  const cacheDir = await mkdtemp(path.join(tmpdir(), 'pdlc-assist-test-cache-'));
  const { server, port, requests } = await startServer((req, res) => {
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim') {
      const claimed: ClaimedTask = {
        id: 'claim-3',
        attempt: 1,
        leaseToken: 'lease-claim-3',
        claimCount: 1,
        payload: {
          kind: 'build',
          story: { profile: 'local', boardId: '4414' },
          task: {
            id: 'T1',
            title: 'Add CSV export',
            description: 'brief',
            area: 'orders',
            repo: 'main',
            scenario: 'export-csv',
            touches: ['src/export.js'],
            testPath: 'test/export.test.js',
            budget: { maxIterations: 5, maxTokens: 20_000, maxWallClock: 'PT10M' },
            blockedBy: [],
          },
          branch: 'story/4414',
          baseBranch: 'main',
          feedback: [],
          repo: { id: 'main', provider: 'local-git', url: 'unused', defaultBranch: 'main', specDir: 'openspec', areas: [], primary: true },
        },
      };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(claimed));
      return;
    }
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim-3/heartbeat') {
      res.writeHead(200);
      res.end();
      return;
    }
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim-3/fail') {
      res.writeHead(200);
      res.end();
      return;
    }
    res.writeHead(404);
    res.end();
  });

  try {
    const cfg = baseConfig(port, repoPath, cacheDir);
    const client = new ApiClient(cfg);
    const args: AssistArgs = { kind: 'build', story: '4414', task: 'T1', maxMinutes: 25 };
    const deps: AssistDeps = {
      launchOmp() {
        // Simulates `spawn('omp', ...)` failing (ENOENT: omp not installed/on PATH).
        return { exited: Promise.reject(new Error('spawn omp ENOENT')), kill: () => undefined };
      },
    };

    const code = await runAssist(cfg, client, args, deps);

    assert.equal(code, 1);
    const failReq = requests.find((r) => r.url === '/api/build-tasks/claim-3/fail');
    assert.ok(failReq, 'expected a posted failure');
    const body = failReq.body as { message: string };
    assert.ok(body.message.includes('spawn omp ENOENT'), body.message);
    assert.ok(!body.message.startsWith('pdlc-assist: build cancelled by'), 'must not be misreported as a developer cancel');
    assert.equal(requests.some((r) => r.url === '/api/build-tasks/claim-3/result'), false);
    assert.equal(await worktreeCount(repoPath), 1, 'expected only the main worktree to remain');
  } finally {
    server.close();
  }
});
