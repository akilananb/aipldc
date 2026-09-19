import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { clearDecision, readBrief, readDecision, writeBrief, writeDecision, type AssistBrief } from './assistProtocol';

async function controlDir(): Promise<string> {
  return mkdtemp(path.join(tmpdir(), 'pdlc-assist-protocol-'));
}

test('readDecision returns cancel when the decision file is missing', async () => {
  const dir = await controlDir();
  assert.deepEqual(await readDecision(dir), { decision: 'cancel' });
});

test('readDecision returns cancel when the decision file is unparseable', async () => {
  const dir = await controlDir();
  await writeFile(path.join(dir, 'decision.json'), 'not json', 'utf8');
  assert.deepEqual(await readDecision(dir), { decision: 'cancel' });
});

test('readDecision defaults force to false when a hand-written decision file omits it', async () => {
  const dir = await controlDir();
  await writeFile(path.join(dir, 'decision.json'), JSON.stringify({ decision: 'submit' }), 'utf8');
  assert.deepEqual(await readDecision(dir), { decision: 'submit', force: false });
});

test('writeDecision then readDecision round-trips an explicit forced submit', async () => {
  const dir = await controlDir();
  await writeDecision(dir, { decision: 'submit', force: true });
  assert.deepEqual(await readDecision(dir), { decision: 'submit', force: true });
});

test('writeDecision then readDecision round-trips a cancel decision', async () => {
  const dir = await controlDir();
  await writeDecision(dir, { decision: 'cancel' });
  assert.deepEqual(await readDecision(dir), { decision: 'cancel' });
});

test('clearDecision removes a previously written decision without erroring when absent', async () => {
  const dir = await controlDir();
  await writeDecision(dir, { decision: 'cancel' });
  await clearDecision(dir);
  assert.deepEqual(await readDecision(dir), { decision: 'cancel' });
  await clearDecision(dir); // no decision file left - must not throw
});

test('writeBrief then readBrief round-trips every field', async () => {
  const dir = await controlDir();
  const brief: AssistBrief = {
    kind: 'build',
    claimId: 'task-1',
    story: { profile: 'local', boardId: '4414' },
    taskId: 'T1',
    title: 'Add CSV export',
    startedAt: '2026-01-01T00:00:00.000Z',
    deadlineAt: '2026-01-01T00:25:00.000Z',
    round: 1,
    feedback: [],
    briefing: 'do the thing',
    touches: ['src/export.js'],
    testPath: 'test/export.test.js',
  };
  await writeBrief(dir, brief);
  assert.deepEqual(await readBrief(dir), brief);
});
