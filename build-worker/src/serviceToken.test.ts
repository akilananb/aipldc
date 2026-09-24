import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { ClientCredentialsToken } from './serviceToken';
import { ApiClient } from './client';
import { loadConfig, type AgentConfig } from './config';

interface Seen {
  url?: string;
  authorization?: string;
  body: string;
}

async function server(handler: (req: http.IncomingMessage, body: string, res: http.ServerResponse) => void) {
  const srv = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => handler(req, body, res));
  });
  await new Promise<void>((resolve) => srv.listen(0, '127.0.0.1', resolve));
  return { srv, port: (srv.address() as AddressInfo).port };
}

test('ClientCredentialsToken uses Basic client auth, sends the scope and caches until near expiry', async () => {
  const seen: Seen[] = [];
  let issued = 0;
  const { srv, port } = await server((req, body, res) => {
    seen.push({ url: req.url, authorization: req.headers.authorization, body });
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ access_token: `tok-${++issued}`, expires_in: 300 }));
  });
  try {
    let now = 1_000_000;
    const source = new ClientCredentialsToken(
      { tokenUrl: `http://127.0.0.1:${port}/token`, clientId: 'build', clientSecret: 's3cret', scope: 'pdlc.build' },
      () => now,
    );
    assert.equal(await source.get(), 'tok-1');
    now += 239_000;
    assert.equal(await source.get(), 'tok-1');
    now += 1_000;
    assert.equal(await source.get(), 'tok-2');
    assert.equal(seen[0].authorization, `Basic ${Buffer.from('build:s3cret').toString('base64')}`);
    assert.equal(seen[0].body, 'grant_type=client_credentials&scope=pdlc.build');
  } finally {
    srv.close();
  }
});

test('ClientCredentialsToken fails loudly when the IdP refuses', async () => {
  const { srv, port } = await server((_req, _body, res) => {
    res.writeHead(401);
    res.end();
  });
  try {
    const source = new ClientCredentialsToken({
      tokenUrl: `http://127.0.0.1:${port}/token`, clientId: 'build', clientSecret: 'wrong', scope: 'pdlc.build',
    });
    await assert.rejects(source.get(), /returned 401/);
  } finally {
    srv.close();
  }
});

test('ApiClient sends a bearer token instead of X-Agent-Token when OAuth is configured', async () => {
  const seen: Seen[] = [];
  const { srv, port } = await server((req, body, res) => {
    if (req.url === '/token') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ access_token: 'svc-jwt', expires_in: 300 }));
      return;
    }
    seen.push({ url: req.url, authorization: req.headers.authorization, body });
    assert.equal(req.headers['x-agent-token'], undefined);
    res.writeHead(204);
    res.end();
  });
  try {
    const cfg: AgentConfig = {
      apiUrl: `http://127.0.0.1:${port}`,
      agentName: 'w1',
      agentToken: 'legacy',
      oauth: { tokenUrl: `http://127.0.0.1:${port}/token`, clientId: 'build', clientSecret: 's', scope: 'pdlc.build' },
      filters: { profile: 'local' },
      pollIntervalMs: 1000,
      heartbeatIntervalMs: 1000,
      repoOverrides: new Map(),
      cacheDir: '/tmp',
      acpAgent: 'omp acp',
    };
    assert.equal(await new ApiClient(cfg).claim(), null);
    assert.equal(seen[0].url, '/api/build-tasks/claim');
    assert.equal(seen[0].authorization, 'Bearer svc-jwt');
  } finally {
    srv.close();
  }
});

test('loadConfig reads the OAuth client and requires its id and secret', () => {
  const base = { PDLC_API_URL: 'http://localhost:8081', BUILD_FILTER_PROFILE: 'local' };
  assert.equal(loadConfig(base).oauth, undefined);
  assert.deepEqual(
    loadConfig({ ...base, PDLC_OAUTH_TOKEN_URL: 'https://idp/token', PDLC_OAUTH_CLIENT_ID: 'b', PDLC_OAUTH_CLIENT_SECRET: 's' }).oauth,
    { tokenUrl: 'https://idp/token', clientId: 'b', clientSecret: 's', scope: 'pdlc.build' },
  );
  assert.throws(() => loadConfig({ ...base, PDLC_OAUTH_TOKEN_URL: 'https://idp/token' }), /requires PDLC_OAUTH_CLIENT_ID/);
});
