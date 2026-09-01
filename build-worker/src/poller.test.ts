import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { ApiClient } from './client';
import type { AgentConfig } from './config';
import type { BuildResult, ClaimedTask } from './types';
import { handleClaim, type BuildRunner } from './worker';

interface RecordedRequest {
  method: string;
  url: string;
  headers: http.IncomingHttpHeaders;
  body: unknown;
}

function delay(ms: number): Promise<void> {
  const { promise, resolve } = Promise.withResolvers<void>();
  setTimeout(resolve, ms);
  return promise;
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
      const recorded: RecordedRequest = {
        method: req.method ?? '',
        url: req.url ?? '',
        headers: req.headers,
        body: raw ? JSON.parse(raw) : undefined,
      };
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

function claimFixture(id: string): ClaimedTask {
  return {
    id,
    attempt: 1,
    payload: {
      story: { profile: 'local', boardId: '4414' },
      task: {
        id: 'T1',
        title: 'Add CSV export',
        area: 'orders',
        scenario: 'export-csv',
        touches: ['src/export.js'],
        testPath: 'test/export.test.js',
        budget: { maxIterations: 5, maxTokens: 20_000, maxWallClock: 'PT10M' },
        blockedBy: [],
      },
      branch: 'story/4414',
      baseBranch: 'main',
      repo: { provider: 'local-git', url: '/unused-because-repoOverride-wins', defaultBranch: 'main', specDir: 'openspec' },
    },
  };
}

async function localRepoDir(): Promise<string> {
  const dir = await mkdtemp(path.join(tmpdir(), 'pdlc-poller-repo-'));
  await mkdir(path.join(dir, '.git'));
  return dir;
}

test('handleClaim heartbeats while the runner works, posts its result, and sends X-Agent-Token when configured', async () => {
  const repoDir = await localRepoDir();
  let claimServed = false;
  let heartbeatCount = 0;
  const { server, port, requests } = await startServer((req, res) => {
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim') {
      if (!claimServed) {
        claimServed = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(claimFixture('task-1')));
      } else {
        res.writeHead(204);
        res.end();
      }
      return;
    }
    if (req.method === 'POST' && req.url === '/api/build-tasks/task-1/heartbeat') {
      heartbeatCount++;
      res.writeHead(200);
      res.end();
      return;
    }
    if (req.method === 'POST' && req.url === '/api/build-tasks/task-1/result') {
      res.writeHead(200);
      res.end();
      return;
    }
    res.writeHead(404);
    res.end();
  });

  try {
    const cfg: AgentConfig = {
      apiUrl: `http://127.0.0.1:${port}`,
      agentName: 'test-agent',
      agentToken: 'tok123',
      filters: { profile: 'local' },
      pollIntervalMs: 1000,
      heartbeatIntervalMs: 20,
      repoOverride: { mode: 'local', path: repoDir },
      cacheDir: repoDir,
      acpAgent: 'omp acp',
    };
    const client = new ApiClient(cfg);
    const claimed = await client.claim();
    if (!claimed) {
      throw new Error('expected a claim');
    }

    const posted: BuildResult = {
      taskId: 'T1',
      commitSha: 'deadbeef',
      verifier: { result: 'green', scenarioResults: { 'export-csv': true }, scopeOk: true, notes: 'ok' },
      iterations: 2,
      tokens: 0,
      touchedFiles: ['src/export.js'],
      traceSummary: 'done',
      escalation: null,
    };
    // Integration test of handleClaim's real `setInterval` heartbeat loop (worker.ts has no
    // injectable clock) - a short real delay is the only way to let >=1 heartbeat tick fire
    // while the runner is "working"; heartbeatIntervalMs above is tuned small to keep this fast.
    const runner: BuildRunner = async () => {
      await delay(80);
      return posted;
    };

    await handleClaim(client, claimed, runner);

    assert.ok(heartbeatCount >= 1, 'expected at least one heartbeat while the runner worked');
    const resultReq = requests.find((r) => r.url === '/api/build-tasks/task-1/result');
    assert.ok(resultReq, 'expected a posted result');
    assert.deepEqual(resultReq.body, posted);
    for (const req of requests) {
      assert.equal(req.headers['x-agent-token'], 'tok123');
    }
  } finally {
    server.close();
  }
});

test('handleClaim aborts the runner and posts nothing when a heartbeat comes back gone (410)', async () => {
  const repoDir = await localRepoDir();
  let claimServed = false;
  const { server, port, requests } = await startServer((req, res) => {
    if (req.method === 'POST' && req.url === '/api/build-tasks/claim') {
      if (!claimServed) {
        claimServed = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(claimFixture('task-2')));
      } else {
        res.writeHead(204);
        res.end();
      }
      return;
    }
    if (req.method === 'POST' && req.url === '/api/build-tasks/task-2/heartbeat') {
      res.writeHead(410);
      res.end();
      return;
    }
    res.writeHead(404);
    res.end();
  });

  try {
    const cfg: AgentConfig = {
      apiUrl: `http://127.0.0.1:${port}`,
      agentName: 'test-agent',
      filters: { profile: 'local' },
      pollIntervalMs: 1000,
      heartbeatIntervalMs: 15,
      repoOverride: { mode: 'local', path: repoDir },
      cacheDir: repoDir,
      acpAgent: 'omp acp',
    };
    const client = new ApiClient(cfg);
    const claimed = await client.claim();
    if (!claimed) {
      throw new Error('expected a claim');
    }

    let sawAbort = false;
    const runner: BuildRunner = (_payload, _repo, _opts, signal) => {
      const { promise, reject } = Promise.withResolvers<BuildResult>();
      signal.addEventListener('abort', () => {
        sawAbort = true;
        reject(new Error('task revoked'));
      });
      return promise;
    };

    await handleClaim(client, claimed, runner);

    assert.equal(sawAbort, true);
    assert.equal(requests.some((r) => r.url === '/api/build-tasks/task-2/result'), false);
    assert.equal(requests.some((r) => r.url === '/api/build-tasks/task-2/fail'), false);
  } finally {
    server.close();
  }
});
