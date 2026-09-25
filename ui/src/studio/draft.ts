import type { AgentSpec, AgentVariable, AgentVersion, ToolRef } from '../types';

/** Editable form state for an agent draft; converted to/from the wire AgentSpec. */
export interface Draft {
  /** 'native' calls a model; 'a2a' delegates to a remote A2A agent (slice 2.5). */
  runtime: string;
  remoteConnection: string;
  remoteSkill: string;
  /** runtime 'rest' (slice 2.6); the connection is remoteConnection. */
  restMode: string;
  submitMethod: string;
  submitPath: string;
  statusPath: string;
  cancelMethod: string;
  cancelPath: string;
  taskIdPointer: string;
  statePointer: string;
  states: { remote: string; mapped: string }[];
  resultPointer: string;
  errorPointer: string;
  pollSeconds: string;
  idempotency: string;
  name: string;
  description: string;
  prompt: string;
  variables: AgentVariable[];
  model: string;
  fallbacks: string[];
  timeoutSeconds: string;
  maxOutputTokens: string;
  /** Raw JSON text; empty = no output schema. */
  outputSchema: string;
  tools: ToolRef[];
  /** Empty = server default. */
  maxModelTurns: string;
  maxToolCalls: string;
}

export function toDraft(name: string, spec: AgentSpec | null): Draft {
  return {
    runtime: spec?.runtime ?? 'native',
    remoteConnection: spec?.remote?.connectionId ?? spec?.rest?.connectionId ?? '',
    remoteSkill: spec?.remote?.skill ?? '',
    restMode: spec?.rest?.mode ?? 'sync',
    submitMethod: spec?.rest?.submit?.method ?? 'POST',
    submitPath: spec?.rest?.submit?.path ?? '',
    statusPath: spec?.rest?.status?.path ?? '',
    cancelMethod: spec?.rest?.cancel?.method ?? 'POST',
    cancelPath: spec?.rest?.cancel?.path ?? '',
    taskIdPointer: spec?.rest?.taskIdPointer ?? '',
    statePointer: spec?.rest?.statePointer ?? '',
    states: Object.entries(spec?.rest?.states ?? {}).map(([remote, mapped]) => ({ remote, mapped })),
    resultPointer: spec?.rest?.resultPointer ?? '',
    errorPointer: spec?.rest?.errorPointer ?? '',
    pollSeconds: spec?.rest?.pollSeconds != null ? String(spec.rest.pollSeconds) : '',
    idempotency: spec?.rest?.idempotency ?? 'NONE',
    name,
    description: spec?.description ?? '',
    prompt: spec?.prompt ?? '',
    variables: (spec?.variables ?? []).map((v) => ({ name: v.name, description: v.description ?? '', required: v.required })),
    model: spec?.model?.model ?? '',
    fallbacks: spec?.model?.fallbacks ?? [],
    timeoutSeconds: spec?.limits?.timeoutSeconds != null ? String(spec.limits.timeoutSeconds) : '',
    maxOutputTokens: spec?.limits?.maxOutputTokens != null ? String(spec.limits.maxOutputTokens) : '',
    outputSchema: spec?.outputSchema ? JSON.stringify(spec.outputSchema, null, 2) : '',
    tools: spec?.tools ?? [],
    maxModelTurns: spec?.limits?.maxModelTurns != null ? String(spec.limits.maxModelTurns) : '',
    maxToolCalls: spec?.limits?.maxToolCalls != null ? String(spec.limits.maxToolCalls) : '',
  };
}

export type SpecResult = { ok: true; spec: AgentSpec } | { ok: false; error: string };

function toInt(text: string): number | null {
  const t = text.trim();
  if (t === '') return null;
  const n = Number(t);
  return Number.isInteger(n) ? n : NaN;
}

/** Drafts may be incomplete (the server validates on publish); only unparseable fields block saving. */
export function toSpec(d: Draft): SpecResult {
  let outputSchema: Record<string, unknown> | null = null;
  if (d.outputSchema.trim() !== '') {
    try {
      const parsed: unknown = JSON.parse(d.outputSchema);
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return { ok: false, error: 'Output schema must be a JSON object' };
      }
      outputSchema = parsed as Record<string, unknown>;
    } catch (e) {
      return { ok: false, error: `Output schema is not valid JSON: ${(e as Error).message}` };
    }
  }
  const timeout = toInt(d.timeoutSeconds);
  const maxTokens = toInt(d.maxOutputTokens);
  const maxTurns = toInt(d.maxModelTurns);
  const maxCalls = toInt(d.maxToolCalls);
  if ([timeout, maxTokens, maxTurns, maxCalls].some((n) => Number.isNaN(n))) {
    return { ok: false, error: 'Limits must be whole numbers' };
  }
  // Fields added in Phase 2 are sent only when set, so an agent without tools keeps the exact
  // Phase 1 shape (and content hash).
  const limits: NonNullable<AgentSpec['limits']> = { timeoutSeconds: timeout, maxOutputTokens: maxTokens };
  if (maxTurns != null) limits.maxModelTurns = maxTurns;
  if (maxCalls != null) limits.maxToolCalls = maxCalls;
  const variables = d.variables.map((v) => ({
    name: v.name.trim(),
    description: (v.description ?? '').trim() === '' ? null : v.description,
    required: v.required,
  }));
  const description = d.description.trim() === '' ? null : d.description;
  if (d.runtime === 'rest') {
    const blank = (t: string) => (t.trim() === '' ? null : t.trim());
    const poll = toInt(d.pollSeconds);
    if (Number.isNaN(poll)) return { ok: false, error: 'Poll interval must be a whole number' };
    const async = d.restMode === 'async';
    return {
      ok: true,
      spec: {
        description,
        runtime: 'rest',
        prompt: d.prompt.trim() === '' ? null : d.prompt,
        variables,
        model: null,
        limits: { timeoutSeconds: timeout, maxOutputTokens: null },
        outputSchema,
        rest: {
          connectionId: blank(d.remoteConnection),
          mode: async ? 'async' : 'sync',
          submit: { method: d.submitMethod, path: blank(d.submitPath) },
          status: async ? { method: 'GET', path: blank(d.statusPath) } : null,
          cancel: async && d.cancelPath.trim() !== '' ? { method: d.cancelMethod, path: d.cancelPath.trim() } : null,
          taskIdPointer: async ? blank(d.taskIdPointer) : null,
          statePointer: async ? blank(d.statePointer) : null,
          states: async
            ? Object.fromEntries(d.states.filter((s) => s.remote.trim() !== '').map((s) => [s.remote.trim(), s.mapped]))
            : null,
          resultPointer: blank(d.resultPointer),
          errorPointer: blank(d.errorPointer),
          pollSeconds: async ? poll : null,
          idempotency: d.idempotency === 'HEADER' ? 'HEADER' : 'NONE',
        },
      },
    };
  }
  if (d.runtime === 'a2a') {
    // A remote agent chooses its own model and tools; only the time limit applies.
    return {
      ok: true,
      spec: {
        description,
        runtime: 'a2a',
        prompt: d.prompt,
        variables,
        model: null,
        limits: { timeoutSeconds: timeout, maxOutputTokens: null },
        outputSchema,
        remote: { connectionId: d.remoteConnection === '' ? null : d.remoteConnection, skill: d.remoteSkill === '' ? null : d.remoteSkill },
      },
    };
  }
  return {
    ok: true,
    spec: {
      description,
      runtime: 'native',
      prompt: d.prompt,
      variables,
      model: { model: d.model === '' ? null : d.model, fallbacks: d.fallbacks },
      limits,
      outputSchema,
      ...(d.tools.length > 0 ? { tools: d.tools } : {}),
    },
  };
}

/** Stable text used for dirty-checking and for version diffs. */
export function describe(name: string, spec: AgentSpec | null): string {
  const { prompt, ...settings } = spec ?? ({} as AgentSpec);
  return `# ${name}\n\n${prompt ?? ''}\n\n--- settings ---\n${JSON.stringify(sortKeys(settings), null, 2)}\n`;
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value as Record<string, unknown>)
        .sort()
        .map((k) => [k, sortKeys((value as Record<string, unknown>)[k])]),
    );
  }
  return value;
}

export function versionText(v: AgentVersion): string {
  return describe(v.name, v.spec);
}

/** Top-level names referenced outside sections - mirrors the server's AgentSpecValidator rule. */
export function referencedVariables(prompt: string): string[] {
  const names = new Set<string>();
  let depth = 0;
  for (const m of prompt.matchAll(/\{\{\{?\s*([#^/&>=!]?)\s*([^}]*?)\s*\}?\}\}/g)) {
    const sigil = m[1];
    const key = m[2];
    if (sigil === '!' || sigil === '>' || sigil === '=') continue;
    if (sigil === '/') {
      depth = Math.max(0, depth - 1);
      continue;
    }
    if (depth === 0 && key !== '.') names.add(key.split('.')[0]);
    if (sigil === '#' || sigil === '^') depth += 1;
  }
  return [...names];
}
