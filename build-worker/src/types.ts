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
  description: string;
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

export interface BuildPayload {
  kind: 'build';
  story: WorkItemRef;
  task: Task;
  branch: string;
  baseBranch: string;
  feedback: string[];
  repo: ClaimedRepo;
}

/** PO handoff subset the planning prompt needs - mirrors core's `PoHandoff`. */
export interface PlanPoHandoff {
  change: string;
  scenarios: string[];
  areas: string[];
  nfr: Record<string, unknown>;
}

export interface PlanPayload {
  kind: 'plan';
  story: WorkItemRef;
  po: PlanPoHandoff;
  baseBranch: string;
  repo: ClaimedRepo;
}

/** `POST /api/build-tasks/claim` 200 response body. */
export interface ClaimedTask {
  id: string;
  attempt: number;
  payload: BuildPayload | PlanPayload;
}

/** One task the planning agent writes to `.pdlc/plan.json` - mirrors control-plane's
 * `PlanResultRequest.PlannedTask`. */
export interface PlannedTask {
  id: string;
  title: string;
  description: string;
  area: string;
  scenario: string;
  touches: string[];
  testPath: string;
  blockedBy: string[];
}

/** Body of `POST /api/build-tasks/{id}/plan-result`. */
export interface PlanResult {
  tasks: PlannedTask[];
  newFiles: string[];
}
