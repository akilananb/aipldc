import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validatePlan } from './planTask';

const SCENARIOS = ['rate-limit', 'quiet-hours'];

function task(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'T1',
    title: '429 with Retry-After on 11th export',
    description: 'Add the rate-limit check in src/export.js; assert in test/export.test.js.',
    area: 'orders',
    scenario: 'rate-limit',
    touches: ['src/export.js'],
    testPath: 'test/export.test.js',
    blockedBy: [],
    ...overrides,
  };
}

test('validatePlan accepts a valid two-task plan covering every scenario exactly once', () => {
  const t1 = task();
  const t2 = task({ id: 'T2', scenario: 'quiet-hours', title: 'Suppress exports during quiet hours' });

  const { tasks, errors } = validatePlan([t1, t2], SCENARIOS);

  assert.deepEqual(errors, []);
  assert.equal(tasks.length, 2);
  assert.equal(tasks[0].id, 'T1');
  assert.equal(tasks[1].id, 'T2');
});

test('validatePlan rejects a deterministic-planner-style title', () => {
  const t1 = task({ title: 'Implement "rate-limit"' });
  const t2 = task({ id: 'T2', scenario: 'quiet-hours' });

  const { errors } = validatePlan([t1, t2], SCENARIOS);

  assert.ok(errors.some((e) => e.includes('quotation mark')));
  assert.ok(errors.some((e) => e.includes('starts with')));
});

test('validatePlan reports a scenario with no covering task', () => {
  const t1 = task();

  const { errors } = validatePlan([t1], SCENARIOS);

  assert.ok(errors.some((e) => e === 'scenario "quiet-hours" has no task'));
});

test('validatePlan rejects blockedBy pointing at a later or unknown task', () => {
  const t1 = task({ blockedBy: ['T2'] });
  const t2 = task({ id: 'T2', scenario: 'quiet-hours' });

  const { errors } = validatePlan([t1, t2], SCENARIOS);

  assert.ok(errors.some((e) => e.includes('unknown or later task T2')));
});

test('validatePlan reports a scenario covered by more than one task', () => {
  const t1 = task();
  const t2 = task({ id: 'T2', title: 'Second rate-limit task' });

  const { errors } = validatePlan([t1, t2], SCENARIOS);

  assert.ok(errors.some((e) => e === 'scenario "rate-limit" is covered by 2 tasks (must be exactly 1)'));
  assert.ok(errors.some((e) => e === 'scenario "quiet-hours" has no task'));
});
