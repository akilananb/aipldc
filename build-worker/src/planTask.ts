import { existsSync, lstatSync, realpathSync, statSync } from 'node:fs';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import type { FileEvidence, PlanConsultationReport, PlanPayload } from './types';
import { addDetachedWorktree, changedFiles, removeWorktree, revParse } from './git';
import { planConsultationPrompt, runAcpSession } from './acp';
import { loadTemplate } from './promptTemplate';
import type { RepoHandle } from './repo';
import { isValidRepoPath, validateConsultationOutput } from './planConsultationValidator';

const CONSULT_TIMEOUT_MS = 15 * 60_000;
const OUTPUT_PATH = '.pdlc/consultation.json';
const MAX_REPORT_FILES = 128;
const MAX_REPORT_BYTES = 64 * 1024;
const FULL_HEX_SHA = /^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$/;

// Re-exported for existing/omp-extension callers - see planConsultationValidator.ts for the
// implementation and why it stays dependency-free.
export { isValidRepoPath };

/**
 * One repository-consultation round for a claimed story's plan step - build-order phase 3, run by
 * this standalone agent instead of a deterministic Java planner (see docs/agent-playbook.md §3
 * revision). Read-only: a detached worktree pinned to the requested exact commit (the resolved
 * default branch only on the very first round - never a moving branch afterward), one ACP session
 * that may only write {@link OUTPUT_PATH}, exactly one attempt per claimed activity (further
 * technical questions belong to Temporal/the Plan Agent, not hidden ACP retries - Temporal-level
 * transport retries remain separate, at `PLAN_CONSULTATION_ACTIVITY_OPTIONS`). Never commits,
 * pushes, runs the build/tests, or produces a task list.
 */
export async function runPlanTask(
  payload: PlanPayload,
  repo: RepoHandle,
  opts: { acpAgent: string; promptTemplateDir?: string },
  signal: AbortSignal,
): Promise<PlanConsultationReport> {
  const { po, baseBranch, consultation } = payload;
  await repo.sync(baseBranch, baseBranch);

  const baseCommit = await resolvePinnedCommit(repo, baseBranch, consultation.baseCommit);
  const worktreePath = await mkdtemp(path.join(tmpdir(), `pdlc-plan-${payload.story.boardId}-`));
  try {
    await addDetachedWorktree(repo.path, worktreePath, baseCommit);

    const template = loadTemplate('plan-consultation', opts.promptTemplateDir);
    const prompt = planConsultationPrompt(template, {
      change: po.change,
      scenarios: po.scenarios,
      areas: po.areas.join(', '),
      nfr: Object.entries(po.nfr).map(([k, v]) => `${k}: ${v}`),
      questions: consultation.questions,
      paths: consultation.paths,
      history: formatConsultationHistory(consultation.history),
      outputPath: OUTPUT_PATH,
      repo: consultation.repoId,
    });

    const session = await runAcpSession(worktreePath, prompt, CONSULT_TIMEOUT_MS, opts.acpAgent, signal);
    if (session.aborted) {
      throw new Error('plan consultation revoked');
    }
    if (session.timedOut) {
      throw new Error(`consultant timed out before writing ${OUTPUT_PATH}`);
    }

    const outputFilePath = path.join(worktreePath, OUTPUT_PATH);
    if (!existsSync(outputFilePath)) {
      throw new Error(`${OUTPUT_PATH} missing or not valid JSON`);
    }
    let raw: unknown;
    try {
      raw = JSON.parse(await readFile(outputFilePath, 'utf8'));
    } catch {
      throw new Error(`${OUTPUT_PATH} missing or not valid JSON`);
    }

    return await collectConsultationReport(worktreePath, baseCommit, consultation.paths, raw);
  } finally {
    await removeWorktree(repo.path, worktreePath);
  }
}

/** Resolves the exact commit a consultant worktree must pin to: the supplied
 * `requestedBaseCommit` when present (validated as a full SHA - a later round must never silently
 * rebase onto a moving branch), or `revParse`s `baseBranch` for the very first round. Fails
 * explicitly rather than falling back to current branch HEAD when the pinned object is
 * unavailable (`addDetachedWorktree` itself throws on a missing ref). */
export async function resolvePinnedCommit(repo: RepoHandle, baseBranch: string, requestedBaseCommit: string): Promise<string> {
  if (requestedBaseCommit && requestedBaseCommit.trim() !== '') {
    if (!FULL_HEX_SHA.test(requestedBaseCommit)) {
      throw new Error(`invalid pinned baseCommit: ${requestedBaseCommit}`);
    }
    return requestedBaseCommit;
  }
  return revParse(repo.path, baseBranch);
}

export function formatConsultationHistory(history: PlanPayload['consultation']['history']): string {
  const blocks: string[] = [];
  for (const exchange of history) {
    const lines: string[] = [`### Round ${exchange.round}`, 'Questions asked:'];
    for (const q of exchange.questions) {
      lines.push(`- ${q}`);
    }
    lines.push('Consultant findings:', exchange.report.findingsMarkdown, 'File catalog:');
    for (const f of exchange.report.files) {
      lines.push(`- ${f.path}: ${f.exists ? 'exists' : 'new'}`);
    }
    blocks.push(lines.join('\n'));
  }
  return blocks.join('\n\n');
}

/**
 * Validates the consultant's raw output shape, verifies no unexpected source mutation occurred
 * (only {@link OUTPUT_PATH} may change, and HEAD must still equal `baseCommit`), computes
 * filesystem existence for the union of requested and consultant-suggested paths (never trusting
 * an LLM-supplied flag), and builds the size/count-bounded {@link PlanConsultationReport}. Shared
 * by the autonomous ACP runner above and `pdlc-assist`'s interactive path (`assist.ts`).
 */
export async function collectConsultationReport(
  worktreePath: string,
  baseCommit: string,
  requestedPaths: string[],
  raw: unknown,
): Promise<PlanConsultationReport> {
  const { output, errors } = validateConsultationOutput(raw);
  if (!output) {
    throw new Error(`consultation rejected: ${errors.join('; ')}`);
  }

  const headSha = await revParse(worktreePath, 'HEAD');
  if (headSha !== baseCommit) {
    throw new Error(`consultation scope violation: HEAD changed from ${baseCommit} to ${headSha}`);
  }
  const changed = await changedFiles(worktreePath, baseCommit);
  const unexpected = changed.filter((f) => f !== OUTPUT_PATH);
  if (unexpected.length > 0) {
    throw new Error(`consultation scope violation: unexpected change(s) to ${unexpected.join(', ')}`);
  }

  const union = new Set<string>([...requestedPaths, ...output.paths]);
  const orderedPaths = [...union];
  if (orderedPaths.length > MAX_REPORT_FILES) {
    throw new Error(`consultation rejected: report would exceed the ${MAX_REPORT_FILES} file-entry limit`);
  }

  const files: FileEvidence[] = [];
  for (const p of orderedPaths) {
    files.push({ path: p, exists: await pathExistsAsFileWithinWorktree(worktreePath, p) });
  }

  const report: PlanConsultationReport = { baseCommit, findingsMarkdown: output.findingsMarkdown, files };
  const size = Buffer.byteLength(JSON.stringify(report), 'utf8');
  if (size > MAX_REPORT_BYTES) {
    throw new Error('consultation rejected: report exceeds the 64 KiB size limit');
  }
  return report;
}

/** Filesystem-observed existence only, never an LLM-supplied flag: `true` only for a regular file
 * whose real (symlink-resolved) path stays inside `worktreePath`. A directory is invalid
 * evidence - rejected outright, not silently reported as existing or missing - and a symlink
 * escaping the worktree at any point in the path, including an existing parent directory of a
 * proposed new path, is rejected the same way. Preserves absence versus permission/I/O failure:
 * only `ENOENT` on the leaf means a genuinely new file. */
async function pathExistsAsFileWithinWorktree(worktreePath: string, relativePath: string): Promise<boolean> {
  const resolvedRoot = realpathSync(worktreePath);
  const segments = relativePath.split('/');

  let ancestor = worktreePath;
  for (let i = 0; i < segments.length - 1; i++) {
    ancestor = path.join(ancestor, segments[i]);
    if (existsSync(ancestor) && !isWithin(resolvedRoot, realpathSync(ancestor))) {
      throw new Error(`consultation rejected: ${relativePath} escapes the worktree via a symlink`);
    }
  }

  const absolute = path.join(worktreePath, relativePath);
  let leafStat;
  try {
    leafStat = lstatSync(absolute);
  } catch (err: unknown) {
    if (isEnoent(err)) {
      return false;
    }
    throw err;
  }

  if (leafStat.isSymbolicLink()) {
    const real = realpathSync(absolute);
    if (!isWithin(resolvedRoot, real)) {
      throw new Error(`consultation rejected: ${relativePath} is a symlink escaping the worktree`);
    }
    const targetStat = statSync(real);
    if (targetStat.isDirectory()) {
      throw new Error(`consultation rejected: ${relativePath} resolves to a directory, not a file`);
    }
    return targetStat.isFile();
  }
  if (leafStat.isDirectory()) {
    throw new Error(`consultation rejected: ${relativePath} is a directory, not a file`);
  }
  return leafStat.isFile();
}

function isWithin(root: string, candidate: string): boolean {
  const rel = path.relative(root, candidate);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

function isEnoent(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'code' in err && (err as { code?: unknown }).code === 'ENOENT';
}
