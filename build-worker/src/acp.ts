import { spawn } from 'node:child_process';
import Mustache from 'mustache';

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

export function buildTaskPrompt(template: string, taskId: string, title: string, scenario: string, touches: string[], testPath: string, testFileExists: boolean, canWriteTestPath: boolean): string {
  const view = {
    taskId,
    title,
    scenario,
    touches: touches.join(', '),
    testPath,
    forbidTests: !canWriteTestPath,
    createTest: canWriteTestPath && !testFileExists,
    appendTest: canWriteTestPath && testFileExists,
  };
  return Mustache.render(template, view);
}
