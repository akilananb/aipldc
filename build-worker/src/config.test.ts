import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';
import { loadConfig } from './config';

const BASE_ENV = { PDLC_API_URL: 'http://localhost:8081', BUILD_FILTER_PROFILE: 'local' };

test('loadConfig throws when PDLC_API_URL is missing', () => {
  assert.throws(() => loadConfig({ BUILD_FILTER_PROFILE: 'local' }), { message: 'PDLC_API_URL is required' });
});

test('loadConfig throws when BUILD_FILTER_PROFILE is missing', () => {
  assert.throws(() => loadConfig({ PDLC_API_URL: 'http://localhost:8081' }), {
    message: 'BUILD_FILTER_PROFILE is required (project code)',
  });
});

test('loadConfig throws when TARGET_REPO_PATH is set (replaced by TARGET_REPO_OVERRIDES)', () => {
  assert.throws(() => loadConfig({ ...BASE_ENV, TARGET_REPO_PATH: '/tmp/repo' }), {
    message: 'TARGET_REPO_PATH/TARGET_REPO_URL were replaced by TARGET_REPO_OVERRIDES=repoId=<path-or-url>[,...]',
  });
});

test('loadConfig throws when TARGET_REPO_URL is set (replaced by TARGET_REPO_OVERRIDES)', () => {
  assert.throws(() => loadConfig({ ...BASE_ENV, TARGET_REPO_URL: 'https://example.test/repo.git' }), {
    message: 'TARGET_REPO_PATH/TARGET_REPO_URL were replaced by TARGET_REPO_OVERRIDES=repoId=<path-or-url>[,...]',
  });
});

test('loadConfig applies defaults when optional env vars are unset', () => {
  const cfg = loadConfig(BASE_ENV);
  assert.equal(cfg.apiUrl, 'http://localhost:8081');
  assert.equal(cfg.agentName, os.hostname());
  assert.equal(cfg.agentToken, undefined);
  assert.deepEqual(cfg.filters, { profile: 'local', story: undefined, task: undefined });
  assert.equal(cfg.pollIntervalMs, 5000);
  assert.equal(cfg.heartbeatIntervalMs, 30_000);
  assert.deepEqual(cfg.repoOverrides, new Map());
  assert.equal(cfg.repoToken, undefined);
  assert.equal(cfg.cacheDir, path.join(os.homedir(), '.pdlc-build-worker'));
  assert.equal(cfg.acpAgent, 'omp acp');
  assert.equal(cfg.promptTemplateDir, undefined);
});

test('loadConfig parses TARGET_REPO_OVERRIDES into a per-repo map (local path and remote url)', () => {
  const cfg = loadConfig({
    ...BASE_ENV,
    TARGET_REPO_OVERRIDES: 'api=/tmp/api,web=https://example.test/web.git',
  });
  assert.deepEqual(
    cfg.repoOverrides,
    new Map([
      ['api', { mode: 'local', path: '/tmp/api' }],
      ['web', { mode: 'remote', url: 'https://example.test/web.git' }],
    ]),
  );
});

test('loadConfig overrides every optional env var', () => {
  const cfg = loadConfig({
    ...BASE_ENV,
    BUILD_AGENT_NAME: 'runner-1',
    BUILD_AGENT_TOKEN: 's3cret',
    BUILD_FILTER_STORY: '4414',
    BUILD_FILTER_TASK: 'T1',
    POLL_INTERVAL_MS: '1000',
    HEARTBEAT_INTERVAL_MS: '15000',
    TARGET_REPO_OVERRIDES: 'orders-service=/tmp/orders-service',
    TARGET_REPO_TOKEN: 'ghp_x',
    BUILD_WORKER_HOME: '/tmp/cache',
    ACP_AGENT_CMD: 'claude-code-acp',
    PROMPT_TEMPLATE_DIR: '/tmp/prompts',
  });
  assert.equal(cfg.agentName, 'runner-1');
  assert.equal(cfg.agentToken, 's3cret');
  assert.deepEqual(cfg.filters, { profile: 'local', story: '4414', task: 'T1' });
  assert.equal(cfg.pollIntervalMs, 1000);
  assert.equal(cfg.heartbeatIntervalMs, 15_000);
  assert.deepEqual(cfg.repoOverrides, new Map([['orders-service', { mode: 'local', path: '/tmp/orders-service' }]]));
  assert.equal(cfg.repoToken, 'ghp_x');
  assert.equal(cfg.cacheDir, '/tmp/cache');
  assert.equal(cfg.acpAgent, 'claude-code-acp');
  assert.equal(cfg.promptTemplateDir, '/tmp/prompts');
});
