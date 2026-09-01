import os from 'node:os';
import path from 'node:path';

/** Env surface for the standalone build agent - no Temporal env anywhere (control-plane's REST
 * API is the only boundary, see worker.ts). */
export interface AgentConfig {
  /** PDLC_API_URL - control-plane's base URL, e.g. `http://localhost:8081`. */
  apiUrl: string;
  /** BUILD_AGENT_NAME - identifies this agent to control-plane; defaults to the hostname. */
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
  /** TARGET_REPO_PATH / TARGET_REPO_URL - overrides the claim payload's repo, exactly one or
   * neither (payload wins when neither is set). */
  repoOverride?: { mode: 'local'; path: string } | { mode: 'remote'; url: string };
  /** TARGET_REPO_TOKEN - basic-auth token for remote git push/fetch. */
  repoToken?: string;
  /** BUILD_WORKER_HOME - where remote-mode bare repo caches live. */
  cacheDir: string;
  /** ACP_AGENT_CMD - command string acpx spawns as the ACP agent; any ACP-capable agent works,
   * not just omp (e.g. `claude-code-acp`, `pi --acp`). */
  acpAgent: string;
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

  const targetRepoPath = env.TARGET_REPO_PATH;
  const targetRepoUrl = env.TARGET_REPO_URL;
  if (targetRepoPath && targetRepoUrl) {
    throw new Error('set at most one of TARGET_REPO_PATH or TARGET_REPO_URL');
  }
  const repoOverride: AgentConfig['repoOverride'] = targetRepoPath
    ? { mode: 'local', path: targetRepoPath }
    : targetRepoUrl
      ? { mode: 'remote', url: targetRepoUrl }
      : undefined;

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
    repoOverride,
    repoToken: env.TARGET_REPO_TOKEN || undefined,
    cacheDir: env.BUILD_WORKER_HOME ?? path.join(os.homedir(), '.pdlc-build-worker'),
    acpAgent: env.ACP_AGENT_CMD ?? 'omp acp',
  };
}
