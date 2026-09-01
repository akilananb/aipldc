import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import type { VerifierResult } from './types';

const execFileAsync = promisify(execFile);

const TAP_TEST_LINE = /^(ok|not ok) \d+ - (.+)$/;

/** Parses node's TAP test-runner output into a name -> passed map. TAP subtests nest ("Subtest:
 * ..." / indented "ok"/"not ok" lines); node's own `test('scenario: x', ...)` blocks surface as
 * top-level (non-indented) result lines, which is the level {@link VerifierResult#scenarioResults}
 * needs - one entry per `test(...)` call in the target repo's test file. */
export function parseTap(output: string): Record<string, boolean> {
  const results: Record<string, boolean> = {};
  for (const rawLine of output.split('\n')) {
    if (rawLine.startsWith(' ')) {
      continue; // indented lines are nested subtest detail, not top-level test() results
    }
    const match = TAP_TEST_LINE.exec(rawLine.trim());
    if (match) {
      results[match[2]] = match[1] === 'ok';
    }
  }
  return results;
}

/** Runs the target repo's own verifier (`npm test`, which runs `node --test --test-reporter=tap`
 * per its package.json) in `worktreePath` and reports pass/fail per named test. Deterministic
 * first per playbook §5 - no LLM rubric. */
export async function runVerifier(worktreePath: string): Promise<VerifierResult> {
  let stdout = '';
  let exitCode = 0;
  try {
    const result = await execFileAsync('npm', ['test'], { cwd: worktreePath, maxBuffer: 32 * 1024 * 1024 });
    stdout = result.stdout + result.stderr;
  } catch (e: unknown) {
    exitCode = 1;
    if (e && typeof e === 'object' && 'stdout' in e) {
      const withOutput = e as { stdout?: string; stderr?: string };
      stdout = (withOutput.stdout ?? '') + (withOutput.stderr ?? '');
    } else {
      stdout = String(e);
    }
  }

  const scenarioResults = parseTap(stdout);
  const allNamed = Object.values(scenarioResults);
  const allPass = exitCode === 0 && allNamed.length > 0 && allNamed.every(Boolean);
  const failing = Object.entries(scenarioResults).filter(([, passed]) => !passed).map(([name]) => name);
  const notes = allPass
    ? `all ${allNamed.length} test(s) pass`
    : failing.length > 0
      ? `failing: ${failing.join(', ')}`
      : `verifier exited ${exitCode} with no parseable TAP test lines`;

  return {
    result: allPass ? 'green' : 'red',
    scenarioResults,
    scopeOk: true, // scope is judged separately, from the git diff (see activities.ts)
    notes,
  };
}
