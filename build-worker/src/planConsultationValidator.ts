/**
 * Validates the consultant's raw `.pdlc/consultation.json` output shape - nonblank findings and
 * an array of valid repo-relative paths, and explicitly rejects a `tasks` field (the consultant
 * never proposes a final task list - only the Plan Agent, agents module, does) - before the
 * worker computes filesystem existence and builds the posted report (see
 * `collectConsultationReport` in `planTask.ts`).
 *
 * Deliberately dependency-free (no Mustache/ACP imports): the `pdlc-assist` omp extension
 * (`../omp/pdlc-assist.ts`) dynamically imports this compiled module directly, inside omp's own
 * Bun process, the same reason the now-deleted `planValidator.ts` used to be dependency-free.
 * This module replaces it as the one shared consultation-output validator.
 */
export function validateConsultationOutput(
  raw: unknown,
): { output: { findingsMarkdown: string; paths: string[] } | null; errors: string[] } {
  const errors: string[] = [];
  if (raw === null || typeof raw !== 'object') {
    return { output: null, errors: ['consultation.json must be a JSON object'] };
  }
  const obj = raw as { findingsMarkdown?: unknown; paths?: unknown; tasks?: unknown };

  if (obj.tasks !== undefined) {
    errors.push('consultation.json must not include a tasks field - the consultant never proposes tasks');
  }

  const findingsMarkdown = typeof obj.findingsMarkdown === 'string' ? obj.findingsMarkdown.trim() : '';
  if (!findingsMarkdown) {
    errors.push('findingsMarkdown must be non-blank');
  }

  if (obj.paths !== undefined && !Array.isArray(obj.paths)) {
    errors.push('paths must be an array of repo-relative paths');
  }
  const rawPaths = Array.isArray(obj.paths) ? obj.paths : [];
  const paths: string[] = [];
  for (const p of rawPaths) {
    if (typeof p !== 'string' || !isValidRepoPath(p)) {
      errors.push(`invalid path: ${JSON.stringify(p)}`);
      continue;
    }
    paths.push(p);
  }

  if (errors.length > 0) {
    return { output: null, errors };
  }
  return { output: { findingsMarkdown, paths }, errors: [] };
}

/** Nonblank slash-separated repository-relative path - rejects absolute/drive-prefixed paths,
 * backslashes, NUL, empty/dot/parent segments, and `.git`/`.pdlc` as the first segment. Mirrors
 * `ai.pdlc.core.plan.PlanAssembler.isValidRepoPath` exactly - the same rule is enforced in both
 * Java and TypeScript, since these paths become builder permissions. */
export function isValidRepoPath(p: string): boolean {
  if (!p || p.trim() === '') {
    return false;
  }
  if (p.includes('\\') || p.includes('\0')) {
    return false;
  }
  if (p.startsWith('/') || /^[A-Za-z]:/.test(p)) {
    return false;
  }
  const segments = p.split('/');
  for (const segment of segments) {
    if (segment === '' || segment === '.' || segment === '..') {
      return false;
    }
  }
  return segments[0] !== '.git' && segments[0] !== '.pdlc';
}
