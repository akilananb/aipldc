import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseTap } from './verifier';

test('parseTap reads top-level ok/not-ok test() results', () => {
  const output = `TAP version 13
# Subtest: scenario: export-current-view
ok 1 - scenario: export-current-view
  ---
  duration_ms: 1.234
  ...
# Subtest: scenario: rate-limit
not ok 2 - scenario: rate-limit
  ---
  duration_ms: 0.5
  ...
1..2
# tests 2
# pass 1
# fail 1
`;
  const result = parseTap(output);
  assert.deepEqual(result, { 'scenario: export-current-view': true, 'scenario: rate-limit': false });
});

test('parseTap ignores indented subtest detail lines', () => {
  const output = `ok 1 - scenario: a
  ok 1 - nested detail that must not be picked up
not ok 2 - scenario: b
`;
  const result = parseTap(output);
  assert.deepEqual(result, { 'scenario: a': true, 'scenario: b': false });
});

test('parseTap returns empty map for unparseable output', () => {
  assert.deepEqual(parseTap('some random crash output\nwith no TAP lines'), {});
});
