/**
 * Wire shapes matching core's Java records exactly (component name -> field name; Temporal's
 * default JSON data converter is plain JSON on both sides, so these interfaces are the entire
 * contract - no code generation). `Duration` fields serialize as ISO-8601 strings (jackson-datatype-jsr310,
 * already a core module dependency), e.g. `"PT10M"`.
 */

export interface WorkItemRef {
  profile: string;
  boardId: string;
}

export interface TaskBudget {
  maxIterations: number;
  maxTokens: number;
  maxWallClock: string; // ISO-8601 duration, e.g. "PT10M"
}

export interface Task {
  id: string;
  title: string;
  area: string;
  scenario: string;
  touches: string[];
  testPath: string;
  budget: TaskBudget;
  blockedBy: string[];
}

export interface VerifierResult {
  result: 'green' | 'red';
  scenarioResults: Record<string, boolean>;
  scopeOk: boolean;
  notes: string;
}

export interface BuildResult {
  taskId: string;
  commitSha: string;
  verifier: VerifierResult;
  iterations: number;
  tokens: number;
  touchedFiles: string[];
  traceSummary: string;
  escalation: string | null;
}

/** Repo descriptor inside a claim payload - mirrors control-plane's `RepoConfig` subset needed
 * to resolve a working copy (see repo.ts). */
export interface ClaimedRepo {
  provider: string;
  url: string;
  defaultBranch: string;
  specDir: string;
}

/** `POST /api/build-tasks/claim` 200 response body. */
export interface ClaimedTask {
  id: string;
  attempt: number;
  payload: {
    story: WorkItemRef;
    task: Task;
    branch: string;
    baseBranch: string;
    repo: ClaimedRepo;
  };
}
