import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseIso8601DurationMs, wasTestContentPreserved } from './buildTask';

test('parseIso8601DurationMs parses minutes', () => {
  assert.equal(parseIso8601DurationMs('PT10M'), 10 * 60_000);
});

test('parseIso8601DurationMs parses hours and minutes combined', () => {
  assert.equal(parseIso8601DurationMs('PT1H30M'), (60 + 30) * 60_000);
});

test('parseIso8601DurationMs parses seconds', () => {
  assert.equal(parseIso8601DurationMs('PT45S'), 45_000);
});

test('parseIso8601DurationMs falls back to 10 minutes for unparseable input', () => {
  assert.equal(parseIso8601DurationMs('not-a-duration'), 10 * 60_000);
});

test('wasTestContentPreserved is vacuously true when the file did not exist before', () => {
  assert.equal(wasTestContentPreserved(null, 'anything at all'), true);
});

test('wasTestContentPreserved is true when prior content is appended to verbatim', () => {
  const before = "test('scenario: a', () => {});\n";
  const after = before + "test('scenario: b', () => {});\n";
  assert.equal(wasTestContentPreserved(before, after), true);
});

test('wasTestContentPreserved is true when new content is inserted before prior content', () => {
  const before = "test('scenario: a', () => {});\n";
  const after = "test('scenario: b', () => {});\n" + before;
  assert.equal(wasTestContentPreserved(before, after), true);
});

test('wasTestContentPreserved is false when a single character of prior content changes', () => {
  const before = "test('scenario: a', () => { assert.equal(x, 1); });\n";
  const after = "test('scenario: a', () => { assert.equal(x, 2); });\n";
  assert.equal(wasTestContentPreserved(before, after), false);
});

test('wasTestContentPreserved is false when prior content is removed', () => {
  const before = "test('scenario: a', () => {});\ntest('scenario: b', () => {});\n";
  const after = "test('scenario: b', () => {});\n";
  assert.equal(wasTestContentPreserved(before, after), false);
});
