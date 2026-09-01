import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildTaskPrompt, runAcpSession } from './acp';

test('buildTaskPrompt forbids touching the test file when this scenario already has coverage', () => {
  const prompt = buildTaskPrompt('T1', 'Add todos', 'create-todo', ['src/default.js'], 'test/default.test.js', true, false);

  assert.match(prompt, /Do not edit test\/default\.test\.js or any other test file/);
  assert.doesNotMatch(prompt, /MAY create it/);
  assert.doesNotMatch(prompt, /MAY append/);
});

test('buildTaskPrompt allows creating a genuinely new test file when none exists yet', () => {
  const prompt = buildTaskPrompt('T1', 'Add todos', 'create-todo', ['src/default.js'], 'test/default.test.js', false, true);

  assert.match(prompt, /test\/default\.test\.js does not exist yet - you MAY create it/);
  assert.match(prompt, /scenario: create-todo/);
  assert.doesNotMatch(prompt, /Do not edit test\/default\.test\.js/);
});

test('buildTaskPrompt allows appending to a shared test file that lacks this scenario, and demands byte-for-byte preservation of the rest', () => {
  const prompt = buildTaskPrompt('T2', 'Add todos', 'mark-done', ['src/default.js'], 'test/default.test.js', true, true);

  assert.match(prompt, /already exists with other tasks' tests in it but has no test yet for this/);
  assert.match(prompt, /you MAY append to it/);
  assert.match(prompt, /do not change, remove, or reorder a single/);
  assert.match(prompt, /scenario: mark-done/);
  assert.doesNotMatch(prompt, /does not exist yet - you MAY create it/);
});

test('buildTaskPrompt always states the touches restriction and stop condition regardless of test-file state', () => {
  for (const [testFileExists, canWriteTestPath] of [[true, false], [false, true], [true, true]] as const) {
    const prompt = buildTaskPrompt('T3', 'Story', 'scenario-x', ['src/a.js', 'src/b.js'], 'test/x.test.js', testFileExists, canWriteTestPath);
    assert.match(prompt, /You may ONLY edit these files: src\/a\.js, src\/b\.js/);
    assert.match(prompt, /Stop once all tests pass; do not touch unrelated code\./);
  }
});

test('runAcpSession resolves immediately without spawning when the signal is already aborted', async () => {
  const controller = new AbortController();
  controller.abort();
  const start = Date.now();
  const result = await runAcpSession('/tmp', 'do nothing', 60_000, 'omp acp', controller.signal);
  const elapsed = Date.now() - start;

  assert.deepEqual(result, { events: [], toolCallCount: 0, timedOut: false, aborted: true });
  assert.ok(elapsed < 1000, 'must resolve synchronously-fast, proving no npx process was spawned');
});
