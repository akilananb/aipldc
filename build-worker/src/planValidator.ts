import path from 'node:path';
import type { PlannedTask } from './types';

const TOUCHES_LIMIT = 8;

/**
 * Validates an agent- or developer-produced `.pdlc/plan.json` body against the story's own
 * scenario list - coverage (each scenario proven by exactly one task), title hygiene, and
 * `blockedBy` referring only to earlier tasks - returning every violation as a human-readable line
 * (fed back to the next attempt, see `planTask.ts`'s `runPlanTask` and `assist.ts`'s
 * `runAssistedPlan`). Control-plane re-validates coverage/conflict/DAG server-side ({@code
 * ai.pdlc.core.plan.PlanValidator}); this is the agent/developer-facing first pass so a malformed
 * plan never leaves the build-worker.
 *
 * <p>Deliberately dependency-free (only `node:path` and `./types`' type-only `PlannedTask`): the
 * `pdlc-assist` omp extension (`../omp/pdlc-assist.ts`) dynamically imports this compiled module
 * directly, inside omp's own Bun process. `planTask.ts` also imports `./acp`, which requires the
 * `mustache` npm package - a transitive dependency that is not resolvable from inside omp's
 * extension-loading context, so `validatePlan` must not live in (or be reached by importing) a
 * module that pulls `./acp` in as a side effect of module evaluation.
 */
export function validatePlan(tasks: unknown, scenarios: string[]): { tasks: PlannedTask[]; errors: string[] } {
  const errors: string[] = [];
  if (!Array.isArray(tasks) || tasks.length === 0) {
    return { tasks: [], errors: ['tasks must be a non-empty array'] };
  }

  const parsed: PlannedTask[] = [];
  const scenarioCounts = new Map<string, number>();
  const ids = new Set<string>();

  tasks.forEach((raw, index) => {
    const expectedId = `T${index + 1}`;
    const t = raw as Partial<PlannedTask>;
    const id = typeof t.id === 'string' ? t.id : expectedId;
    if (id !== expectedId) {
      errors.push(`task ${index + 1}: id must be ${expectedId} in order`);
    }
    ids.add(id);

    const title = typeof t.title === 'string' ? t.title.trim() : '';
    if (!title || title.length > 70) {
      errors.push(`${id}: title blank or over 70 characters`);
    }
    if (title.includes('"') || title.includes("'")) {
      errors.push(`${id}: title contains a quotation mark`);
    }
    if (/^(implement|task)\b/i.test(title)) {
      errors.push(`${id}: title starts with "implement" or "task"`);
    }

    const description = typeof t.description === 'string' ? t.description.trim() : '';
    if (!description) {
      errors.push(`${id}: description must be non-blank`);
    }

    const scenario = typeof t.scenario === 'string' ? t.scenario : '';
    if (!scenarios.includes(scenario)) {
      errors.push(`${id}: scenario "${scenario}" is not in the story`);
    }
    scenarioCounts.set(scenario, (scenarioCounts.get(scenario) ?? 0) + 1);

    const touches = Array.isArray(t.touches) ? t.touches.filter((p): p is string => typeof p === 'string') : [];
    const touchesValid = touches.length > 0 && touches.length <= TOUCHES_LIMIT
      && touches.every((p) => !path.isAbsolute(p) && !p.split('/').includes('..'));
    if (!touchesValid) {
      errors.push(`${id}: touches must list 1-${TOUCHES_LIMIT} repo-relative paths`);
    }

    const testPath = typeof t.testPath === 'string' ? t.testPath : '';
    if (!testPath) {
      errors.push(`${id}: testPath required`);
    }

    const blockedBy = Array.isArray(t.blockedBy) ? t.blockedBy.filter((b): b is string => typeof b === 'string') : [];
    for (const blockerId of blockedBy) {
      if (!ids.has(blockerId) || blockerId === id) {
        errors.push(`${id}: blockedBy references unknown or later task ${blockerId}`);
      }
    }

    parsed.push({ id, title, description, area: typeof t.area === 'string' ? t.area : '', scenario, touches, testPath, blockedBy });
  });

  for (const scenario of scenarios) {
    const count = scenarioCounts.get(scenario) ?? 0;
    if (count === 0) {
      errors.push(`scenario "${scenario}" has no task`);
    } else if (count > 1) {
      errors.push(`scenario "${scenario}" is covered by ${count} tasks (must be exactly 1)`);
    }
  }

  return { tasks: parsed, errors };
}
