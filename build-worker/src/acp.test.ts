import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { buildTaskPrompt, runAcpSession } from './acp';
import { loadBuildTaskTemplate } from './promptTemplate';

const TEMPLATE = loadBuildTaskTemplate();

test('buildTaskPrompt forbids touching the test file when this scenario already has coverage', () => {
  const prompt = buildTaskPrompt(TEMPLATE, 'T1', 'Add todos', 'create-todo', ['src/default.js'], 'test/default.test.js', true, false);

  assert.match(prompt, /Do not edit test\/default\.test\.js or any other test file/);
  assert.doesNotMatch(prompt, /MAY create it/);
  assert.doesNotMatch(prompt, /MAY append/);
});

test('buildTaskPrompt allows creating a genuinely new test file when none exists yet', () => {
  const prompt = buildTaskPrompt(TEMPLATE, 'T1', 'Add todos', 'create-todo', ['src/default.js'], 'test/default.test.js', false, true);

  assert.match(prompt, /test\/default\.test\.js does not exist yet - you MAY create it/);
  assert.match(prompt, /scenario: create-todo/);
  assert.doesNotMatch(prompt, /Do not edit test\/default\.test\.js/);
});

test('buildTaskPrompt allows appending to a shared test file that lacks this scenario, and demands byte-for-byte preservation of the rest', () => {
  const prompt = buildTaskPrompt(TEMPLATE, 'T2', 'Add todos', 'mark-done', ['src/default.js'], 'test/default.test.js', true, true);

  assert.match(prompt, /already exists with other tasks' tests in it but has no test yet for this/);
  assert.match(prompt, /you MAY append to it/);
  assert.match(prompt, /do not change, remove, or reorder a single/);
  assert.match(prompt, /scenario: mark-done/);
  assert.doesNotMatch(prompt, /does not exist yet - you MAY create it/);
});

test('buildTaskPrompt always states the touches restriction and stop condition regardless of test-file state', () => {
  for (const [testFileExists, canWriteTestPath] of [[true, false], [false, true], [true, true]] as const) {
    const prompt = buildTaskPrompt(TEMPLATE, 'T3', 'Story', 'scenario-x', ['src/a.js', 'src/b.js'], 'test/x.test.js', testFileExists, canWriteTestPath);
    assert.match(prompt, /You may ONLY edit these files: src\/a\.js, src\/b\.js/);
    assert.match(prompt, /Stop once all tests pass; do not touch unrelated code\./);
  }
});

test('buildTaskPrompt renders the createTest branch byte-exact (golden)', () => {
  const prompt = buildTaskPrompt(TEMPLATE, 'T1', 'Add todos', 'create-todo', ['src/default.js'], 'test/default.test.js', false, true);

  const expected = [
    'You are implementing task T1 for this story: "Add todos".',
    'Scenario to prove: create-todo',
    'You may ONLY edit these files: src/default.js',
    'test/default.test.js does not exist yet - you MAY create it (and only it, no other test file).',
    "Read the story's acceptance criteria under openspec/changes/ in this repo for the exact",
    'GIVEN/WHEN/THEN wording of "scenario: create-todo", write a real test block named',
    '"scenario: create-todo" in test/default.test.js that asserts that exact behavior (not a test that',
    'always passes regardless of your implementation), then implement in the allowed files to',
    "make it pass, then run the repo's test command yourself to confirm every test passes",
    "(not just this scenario's) before you finish.",
    'Stop once all tests pass; do not touch unrelated code.',
  ].join('\n');
  assert.equal(prompt, expected);
});

test('loadBuildTaskTemplate uses a prompts_dir override when build-task.mustache is present there', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'pdlc-prompt-override-'));
  await writeFile(path.join(dir, 'build-task.mustache'), 'CUSTOM {{{taskId}}}');

  const prompt = buildTaskPrompt(loadBuildTaskTemplate(dir), 'T9', 'Story', 'scenario-x', ['src/a.js'], 'test/x.test.js', true, false);

  assert.equal(prompt, 'CUSTOM T9');
});

test('loadBuildTaskTemplate falls back to the bundled default when the override dir has no build-task.mustache', async () => {
  const emptyDir = await mkdtemp(path.join(tmpdir(), 'pdlc-prompt-empty-'));

  assert.equal(loadBuildTaskTemplate(emptyDir), loadBuildTaskTemplate());
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
