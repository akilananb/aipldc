import { spawn } from 'node:child_process';

export interface AcpSessionResult {
  /** Every JSON-lines event acpx printed, in order - the trace this task's summary is built from. */
  events: string[];
  /** Best-effort count of distinct `[tool]` lines - the closest observable proxy for "iterations"
   * omp over ACP exposes (acpx/pi-acp report no per-turn budget knob; wall-clock is the real
   * budget enforcement - see {@link Task.budget.maxWallClock} in buildTask.ts). */
  toolCallCount: number;
  timedOut: boolean;
  /** True when the session was revoked via `signal` (a heartbeat found the build-tasks row gone)
   * rather than run to completion or timed out. */
  aborted: boolean;
}

/** Drives a coding agent over ACP via `acpx --agent <agentCmd> exec <prompt>` against `cwd`.
 * `agentCmd` is a host-configured command string (default `omp acp`; see config.ts's
 * `ACP_AGENT_CMD`) - any ACP-capable agent works, not just omp. Kills the session at `timeoutMs`
 * (the task's wall-clock budget) or immediately when `signal` aborts (the poller revoking a task
 * whose claim lease is gone). */
export function runAcpSession(cwd: string, prompt: string, timeoutMs: number, agentCmd: string, signal?: AbortSignal): Promise<AcpSessionResult> {
  const { promise, resolve, reject } = Promise.withResolvers<AcpSessionResult>();

  if (signal?.aborted) {
    // Resolve without spawning - keeps this hermetic (no npx/network) for an already-revoked task.
    resolve({ events: [], toolCallCount: 0, timedOut: false, aborted: true });
    return promise;
  }

  const child = spawn('npx', ['--yes', 'acpx@latest', '--cwd', cwd, '--approve-all', '--agent', agentCmd, 'exec', prompt], {
    cwd,
    env: process.env,
  });

  const events: string[] = [];
  let toolCallCount = 0;
  let timedOut = false;
  let aborted = false;
  let buffer = '';

  const timer = setTimeout(() => {
    timedOut = true;
    child.kill('SIGTERM');
  }, timeoutMs);

  const onAbort = () => {
    aborted = true;
    child.kill('SIGTERM');
  };
  signal?.addEventListener('abort', onAbort);

  child.stdout.on('data', (chunk: Buffer) => {
    buffer += chunk.toString('utf8');
    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';
    for (const line of lines) {
      if (!line.trim()) {
        continue;
      }
      events.push(line);
      if (line.startsWith('[tool]')) {
        toolCallCount++;
      }
    }
  });
  child.stderr.on('data', () => undefined);

  child.on('error', (err) => {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
    reject(err);
  });
  child.on('close', () => {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
    if (buffer.trim()) {
      events.push(buffer);
    }
    resolve({ events, toolCallCount, timedOut, aborted });
  });

  return promise;
}

export function buildTaskPrompt(taskId: string, title: string, scenario: string, touches: string[], testPath: string, testFileExists: boolean, canWriteTestPath: boolean): string {
  const lines = [
    `You are implementing task ${taskId} for this story: "${title}".`,
    `Scenario to prove: ${scenario}`,
    `You may ONLY edit these files: ${touches.join(', ')}`,
  ];
  if (!canWriteTestPath) {
    lines.push(
      `Do not edit ${testPath} or any other test file, do not weaken or skip any test.`,
      `Read the test block named "scenario: ${scenario}" in ${testPath} to understand exactly what`,
      'behavior is required, implement it in the allowed files, then run the repo\'s test command',
      'yourself to confirm every test passes (not just this scenario\'s) before you finish.',
    );
  } else if (!testFileExists) {
    lines.push(
      `${testPath} does not exist yet - you MAY create it (and only it, no other test file).`,
      `Read the story's acceptance criteria under openspec/changes/ in this repo for the exact`,
      `GIVEN/WHEN/THEN wording of "scenario: ${scenario}", write a real test block named`,
      `"scenario: ${scenario}" in ${testPath} that asserts that exact behavior (not a test that`,
      'always passes regardless of your implementation), then implement in the allowed files to',
      'make it pass, then run the repo\'s test command yourself to confirm every test passes',
      '(not just this scenario\'s) before you finish.',
    );
  } else {
    lines.push(
      `${testPath} already exists with other tasks' tests in it but has no test yet for this`,
      `scenario - you MAY append to it (and only it, no other test file): add ONE new test block`,
      `named "scenario: ${scenario}" at the end, and do not change, remove, or reorder a single`,
      'existing line (every other task\'s test in that file must survive byte-for-byte or your',
      'change is reverted). Read the story\'s acceptance criteria under openspec/changes/ in this',
      `repo for the exact GIVEN/WHEN/THEN wording of "scenario: ${scenario}" before writing the`,
      'assertion (not a test that always passes regardless of your implementation), then implement',
      'in the allowed files to make it pass, then run the repo\'s test command yourself to confirm',
      'every test passes (not just this scenario\'s) before you finish.',
    );
  }
  lines.push('Stop once all tests pass; do not touch unrelated code.');
  return lines.join('\n');
}
