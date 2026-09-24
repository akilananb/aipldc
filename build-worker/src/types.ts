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
  repo: string;
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
  id: string;
  provider: string;
  url: string;
  defaultBranch: string;
  specDir: string;
  areas: string[];
  primary: boolean;
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

/** One repo-relative file's existence, filesystem-observed by the worker - never an LLM-asserted
 * flag. Mirrors core's `PlanConsultation.FileEvidence`. */
export interface FileEvidence {
  path: string;
  exists: boolean;
}

/** Body of `POST /api/build-tasks/{id}/plan-result` - the consultant's validated
 * `.pdlc/consultation.json` evidence for one round, plus the worker-observed commit SHA. Mirrors
 * control-plane's `PlanConsultationResultRequest` / core's `PlanConsultation.Report`. Replaces the
 * old task-list `PlanResult`: no TypeScript caller produces final tasks - only evidence for the
 * Plan Agent (agents module) to reason over. */
export interface PlanConsultationReport {
  baseCommit: string;
  findingsMarkdown: string;
  files: FileEvidence[];
}

/** One completed consultation round in the transcript - mirrors core's `PlanConsultation.Exchange`. */
export interface PlanConsultationExchange {
  round: number;
  questions: string[];
  report: PlanConsultationReport;
}

/** One repository-consultation round request the Plan Agent asks for - mirrors core's
 * `PlanConsultation`. */
export interface PlanConsultation {
  round: number;
  repoId: string;
  questions: string[];
  paths: string[];
  baseCommit: string;
  history: PlanConsultationExchange[];
}

export interface PlanPayload {
  kind: 'plan';
  story: WorkItemRef;
  po: PlanPoHandoff;
  baseBranch: string;
  repo: ClaimedRepo;
  consultation: PlanConsultation;
}

/** `POST /api/build-tasks/claim` 200 response body. */
export interface ClaimedTask {
  id: string;
  attempt: number;
  payload: BuildPayload | PlanPayload;
  /** Per-claim fencing token — every subsequent heartbeat/result/plan-result/fail call for this
   * claim MUST present it as the `X-Lease-Token` header. */
  leaseToken: string;
  /** 1 on a fresh claim; >1 when this row is being reclaimed after a previous worker's lease
   * expired (see BuildTaskService#releaseExpiredLeases). */
  claimCount: number;
}

/** The subset of a claim needed to fence a per-task call — every claim satisfies this. */
export type Lease = Pick<ClaimedTask, 'id' | 'leaseToken'>;

/** `POST /api/build-tasks/claim` request body's optional `presence` field - mirrors
 * control-plane's `ClaimRequest.Presence`/`RepoPresence`. Attached to every claim poll (worker.ts
 * #describePresence) so the control plane can show this agent online with its repo/branch. */
export interface RepoPresence {
  id: string;
  mode: 'local' | 'remote' | 'payload';
  location: string | null;
  branch: string | null;
}

export interface AgentPresenceReport {
  acpAgent: string;
  pollIntervalMs: number;
  repos: RepoPresence[];
}
