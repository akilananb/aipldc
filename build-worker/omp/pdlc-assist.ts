// Loaded by `pdlc-assist` (see ../src/assist.ts) via `omp --cwd <worktree> -e <this file>`. Inert
// under a plain `omp` invocation (PDLC_ASSIST_DIR unset) so this file is safe to keep in the
// package even when someone opens the worktree with omp directly. `ExtensionAPI`/`ExtensionContext`
// are type-only imports, erased by omp's own Bun-based loader - this file is never compiled by
// build-worker's own `tsc` (outside `src/`, not in its `include`), so it carries no runtime
// dependency on `@oh-my-pi/pi-coding-agent` being installed here.
import type { ExtensionAPI, ExtensionContext } from '@oh-my-pi/pi-coding-agent';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

/** Mirrors `../src/assistProtocol.ts`'s `AssistBrief`/`AssistDecision` shapes - duplicated rather
 * than imported so this file has no dependency on build-worker's own compiled output existing at
 * a particular path relative to itself. */
interface AssistBrief {
  kind: 'plan' | 'build';
  claimId: string;
  story: { profile: string; boardId: string };
  taskId: string;
  title: string;
  startedAt: string;
  deadlineAt: string;
  round: number;
  feedback: string[];
  briefing: string;
  planValidatorModule?: string;
  scenarios?: string[];
  outputPath?: string;
  touches?: string[];
  testPath?: string;
}

type AssistDecision = { decision: 'submit'; force: boolean } | { decision: 'cancel' };

type PlanValidatorModule = {
  validatePlan?: (tasks: unknown, scenarios: string[]) => { errors: string[] };
  default?: { validatePlan?: (tasks: unknown, scenarios: string[]) => { errors: string[] } };
};

export default function pdlcAssist(pi: ExtensionAPI): void {
  const controlDir = process.env.PDLC_ASSIST_DIR;
  if (!controlDir) {
    return;
  }

  const briefPath = path.join(controlDir, 'assist.json');
  const decisionPath = path.join(controlDir, 'decision.json');
  const readBrief = (): AssistBrief => JSON.parse(readFileSync(briefPath, 'utf8')) as AssistBrief;
  const writeDecision = (decision: AssistDecision): void => {
    writeFileSync(decisionPath, JSON.stringify(decision), 'utf8');
  };

  pi.setLabel('PDLC assist');

  pi.on('session_start', async (_event, ctx: ExtensionContext) => {
    const brief = readBrief();
    await pi.setSessionName(`pdlc ${brief.kind} ${brief.story.boardId}/${brief.taskId}`);
    ctx.ui.notify(
      `PDLC assist round ${brief.round}: ${brief.kind} ${brief.taskId} — /pdlc status | submit [force] | cancel`,
      'info',
    );
    const feedbackBlock = brief.feedback.length > 0
      ? `\n\nPrevious round feedback:\n${brief.feedback.map((line) => `- ${line}`).join('\n')}`
      : '';
    pi.sendMessage(
      { customType: 'pdlc-brief', content: brief.briefing + feedbackBlock, display: true },
      { deliverAs: 'nextTurn' },
    );
  });

  pi.registerCommand('pdlc', {
    description: 'PDLC assist: status | submit [force] | cancel',
    // The runtime's actual arg shape for extension commands isn't guaranteed to be a pre-split
    // array (observed: a raw remainder string, e.g. "submit force") - normalize defensively so
    // `sub`/`force` parsing below works regardless.
    handler: async (rawArgs: string[] | string, ctx: ExtensionContext) => {
      const args = Array.isArray(rawArgs) ? rawArgs : rawArgs.trim().split(/\s+/).filter(Boolean);
      const brief = readBrief();
      const sub = args[0];

      if (!sub || sub === 'status') {
        const now = Date.now();
        const elapsedMin = Math.floor((now - new Date(brief.startedAt).getTime()) / 60_000);
        const remainingMin = Math.max(0, Math.floor((new Date(brief.deadlineAt).getTime() - now) / 60_000));
        const scopeLine = brief.kind === 'plan'
          ? `outputPath=${brief.outputPath}`
          : `touches=${(brief.touches ?? []).join(', ')} testPath=${brief.testPath}`;
        ctx.ui.notify(
          `${brief.kind} ${brief.taskId} round ${brief.round} — elapsed ${elapsedMin}m, ${remainingMin}m until deadline. ${scopeLine}`,
          'info',
        );
        return;
      }

      if (sub === 'submit') {
        const force = args[1] === 'force';
        if (brief.kind === 'plan') {
          let raw: { tasks?: unknown };
          try {
            raw = JSON.parse(readFileSync(path.join(ctx.cwd, brief.outputPath ?? '.pdlc/plan.json'), 'utf8')) as { tasks?: unknown };
          } catch (err) {
            ctx.ui.notify(`cannot read ${brief.outputPath}: ${err instanceof Error ? err.message : String(err)}`, 'error');
            return;
          }
          // Dynamic by necessity: `planValidatorModule` is an absolute path to build-worker's
          // own compiled output, computed by the supervisor at claim time (see assist.ts) and
          // passed through the brief - this extension has no static dependency on build-worker's
          // package layout to import against.
          const mod = (await import(pathToFileURL(brief.planValidatorModule as string).href)) as PlanValidatorModule;
          const validatePlan = mod.validatePlan ?? mod.default?.validatePlan;
          if (!validatePlan) {
            ctx.ui.notify('pdlc-assist: could not load the plan validator module', 'error');
            return;
          }
          const { errors } = validatePlan(raw.tasks, brief.scenarios ?? []);
          if (errors.length > 0 && !force) {
            ctx.ui.notify(`${errors.length} validation error(s) — sent to the agent`, 'warning');
            pi.sendUserMessage(
              `Plan validation failed; fix ${brief.outputPath}:\n${errors.map((line) => `- ${line}`).join('\n')}`,
              { attribution: 'agent' },
            );
            return;
          }
        }
        writeDecision({ decision: 'submit', force });
        ctx.ui.notify('submitting…', 'info');
        await ctx.shutdown();
        return;
      }

      if (sub === 'cancel') {
        writeDecision({ decision: 'cancel' });
        await ctx.shutdown();
        return;
      }

      ctx.ui.notify('usage: /pdlc status | submit [force] | cancel', 'warning');
    },
  });
}
