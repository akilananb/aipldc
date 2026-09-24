import os from 'node:os';
import path from 'node:path';

/** A per-repo target override: a local filesystem path (starts with `/`) or a remote git URL. */
export type RepoOverride = { mode: 'local'; path: string } | { mode: 'remote'; url: string };

/** Env surface for the standalone build agent - no Temporal env anywhere (control-plane's REST
 * API is the only boundary, see worker.ts). */
export interface AgentConfig {
  /** PDLC_API_URL - control-plane's base URL, e.g. `http://localhost:8081`. */
  apiUrl: string;
  /** BUILD_AGENT_NAME - identifies this worker to control-plane (presence + `claimed_by`);
   * defaults to the hostname. Every concurrently running worker MUST use a distinct name - a
   * pool on one host sets it explicitly (e.g. `w1`, `w2`). A duplicate name only degrades the
   * presence UI; per-task fencing is by lease token (client.ts), not agent name. */
  agentName: string;
  /** BUILD_AGENT_TOKEN - sent as the `X-Agent-Token` header when set. */
  agentToken?: string;
  /** Claim filters - `profile` is the "project code" (`WorkItemRef.profile`, the `pdlc.yaml`
   * profile name); `story`/`task` narrow further. */
  filters: {
    profile: string;
    story?: string;
    task?: string;
  };
  /** POLL_INTERVAL_MS - how often to poll `/api/build-tasks/claim` when idle. */
  pollIntervalMs: number;
  /** HEARTBEAT_INTERVAL_MS - how often to heartbeat a claimed task; server lease is a fixed 90s,
   * Temporal's own heartbeat timeout is 2 minutes (`FeatureWorkflowImpl.BUILD_ACTIVITY_OPTIONS`). */
  heartbeatIntervalMs: number;
  /** TARGET_REPO_OVERRIDES - comma-separated `repoId=<path-or-url>` pairs overriding the claim
   * payload's per-repo URL/path; empty map when unset (payload wins for repos with no override). */
  repoOverrides: Map<string, RepoOverride>;
  /** TARGET_REPO_TOKEN - basic-auth token for remote git push/fetch. */
  repoToken?: string;
  /** BUILD_WORKER_HOME - where remote-mode bare repo caches live. */
  cacheDir: string;
  /** ACP_AGENT_CMD - command string acpx spawns as the ACP agent; any ACP-capable agent works,
   * not just omp (e.g. `claude-code-acp`, `pi --acp`). */
  acpAgent: string;
  /** PROMPT_TEMPLATE_DIR - directory of `*.mustache` files overriding the bundled build-task
   * prompt template (see promptTemplate.ts); unset falls back to the bundled default. */
  promptTemplateDir?: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AgentConfig {
  const apiUrl = env.PDLC_API_URL;
  if (!apiUrl) {
    throw new Error('PDLC_API_URL is required');
  }
  const profile = env.BUILD_FILTER_PROFILE;
  if (!profile) {
    throw new Error('BUILD_FILTER_PROFILE is required (project code)');
  }

  const targetRepoOverrides = env.TARGET_REPO_OVERRIDES;
  if (env.TARGET_REPO_PATH || env.TARGET_REPO_URL) {
    throw new Error(
      'TARGET_REPO_PATH/TARGET_REPO_URL were replaced by TARGET_REPO_OVERRIDES=repoId=<path-or-url>[,...]',
    );
  }
  const repoOverrides = parseRepoOverrides(targetRepoOverrides);

  return {
    apiUrl,
    agentName: env.BUILD_AGENT_NAME ?? os.hostname(),
    agentToken: env.BUILD_AGENT_TOKEN || undefined,
    filters: {
      profile,
      story: env.BUILD_FILTER_STORY || undefined,
      task: env.BUILD_FILTER_TASK || undefined,
    },
    pollIntervalMs: env.POLL_INTERVAL_MS ? Number(env.POLL_INTERVAL_MS) : 5000,
    heartbeatIntervalMs: env.HEARTBEAT_INTERVAL_MS ? Number(env.HEARTBEAT_INTERVAL_MS) : 30_000,
    repoOverrides,
    repoToken: env.TARGET_REPO_TOKEN || undefined,
    cacheDir: env.BUILD_WORKER_HOME ?? path.join(os.homedir(), '.pdlc-build-worker'),
    acpAgent: env.ACP_AGENT_CMD ?? 'omp acp',
    promptTemplateDir: env.PROMPT_TEMPLATE_DIR || undefined,
  };
}

/** Parses `TARGET_REPO_OVERRIDES` into a `repoId -> override` map. Each comma-separated pair is
 * `repoId=<value>`; `<value>` starting with `/` is a local filesystem path, anything else a remote
 * git URL. Empty entries are skipped (tolerates a trailing comma). */
function parseRepoOverrides(raw: string | undefined): Map<string, RepoOverride> {
  const overrides = new Map<string, RepoOverride>();
  if (!raw) {
    return overrides;
  }
  for (const pair of raw.split(',')) {
    const trimmed = pair.trim();
    if (!trimmed) {
      continue;
    }
    const eq = trimmed.indexOf('=');
    if (eq <= 0) {
      throw new Error(`invalid TARGET_REPO_OVERRIDES entry (expected repoId=<path-or-url>): ${trimmed}`);
    }
    const id = trimmed.slice(0, eq).trim();
    const value = trimmed.slice(eq + 1).trim();
    if (!id || !value) {
      throw new Error(`invalid TARGET_REPO_OVERRIDES entry (expected repoId=<path-or-url>): ${trimmed}`);
    }
    overrides.set(id, value.startsWith('/') ? { mode: 'local', path: value } : { mode: 'remote', url: value });
  }
  return overrides;
}
