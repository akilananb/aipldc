// Wire shapes for the control-plane REST API (contract from shared context).

export type CommentIntent = 'change' | 'question' | 'note';

export interface CommentAnchor {
  line: number;
  anchorText: string;
  nodeType: string;
  scenario: string | null;
  endLine: number | null;
}

export interface Comment {
  id: string;
  by: string;
  role: string;
  target: string; // "line:N" | "scenario:<name>"
  text: string;
  intent: CommentIntent;
  blocking: boolean;
  version: number;
  resolvedInVersion: number | null;
  agentReply: string | null;
  anchor: CommentAnchor | null;
  drifted: boolean;
  agentName: string | null;
  agentResultMd: string | null;
  agentResultStatus: 'running' | 'pending' | 'approved' | 'failed' | null;
  agentResultApprovedBy: string | null;
}

export interface ScenarioReview {
  scenario: string;
  status: 'meets' | 'not-reviewed';
  by: string;
  role: string;
  at: string;
}

export interface ArtifactVersion {
  version: number;
  contentHash: string;
  storyMarkdown: string;
  comments: Comment[];
  scenarioReviews: ScenarioReview[];
}

export interface Approval {
  who: string;
  role: string;
  version: number;
  contentHash: string;
  at: string;
}

export interface StepFailure {
  step: string;
  message: string;
  atEpochMilli: number;
}

export interface GateState {
  version: number;
  approvals: Record<string, Approval>;
  openBlockingComments: number;
  stage: string;
  lastFailure: StepFailure | null;
}

export type AgentRunStatus = 'running' | 'abandoned' | 'finished';

export interface AgentRun {
  id: string;
  agent: string;
  phase: string | null;
  status: AgentRunStatus;
  outcome: string | null;
  startedAt: string;
  finishedAt: string | null;
  traceUrl: string | null;
  tokens: number | null;
  iterations: number | null;
}

export interface DemoSnapshot {
  key: string;
  label: string;
  order: number;
  sourceRef: string;
  replay: boolean;
}

export interface DemoStatus {
  enabled: boolean;
  liveItemId: string | null;
}

export type AgentWorkPhase = 'reasoning' | 'waiting-for-worker' | 'running';
export type AgentWorkKind = 'plan' | 'build';

export interface AgentWork {
  phase: AgentWorkPhase;
  kind: AgentWorkKind;
  taskId: string | null;
  round: number | null;
  claimedBy: string | null;
  since: string | null;
  workersOnline: number;
}

export interface ItemDetail {
  id: string;
  profile: string;
  boardId: string;
  kind: string;
  title: string;
  description: string;
  canonicalState: string;
  latestVersion: number;
  latestContentHash: string;
  gate: GateState | null;
  parentId: string | null;
  qualityVerdict: string | null;
  activeRun: AgentRun | null;
  snapshot: DemoSnapshot | null;
  agentWork: AgentWork | null;
}

export interface ItemSummary {
  id: string;
  profile: string;
  boardId: string;
  kind: string;
  title: string;
  canonicalState: string;
  updatedAt: string;
  parentId: string | null;
  qualityVerdict: string | null;
  activeRun: AgentRun | null;
  snapshot: DemoSnapshot | null;
}

export interface QualityReport {
  verdict: string;
  score: number | null;
  reportMd: string | null;
  version: number;
  createdAt: string;
}

export interface DocApproval {
  who: string;
  role: string;
  version: number;
  at: string;
}

export interface SpecDocs {
  storyId: string;
  storyTitle: string;
  slug: string | null;
  proposalMd: string | null;
  specMd: string | null;
  tasksMd: string | null;
  approvals: DocApproval[];
}

export interface ReleaseDocument {
  docId: string;
  title: string;
  content: string;
  checkerRole: string;
  contentHash: string;
  signed: boolean;
}

export interface BoardComment {
  id: string;
  by: string;
  role: string;
  stage: string;
  target: string;
  text: string;
  intent: string;
  blocking: boolean;
  version: number;
}

export interface GrillQuestion {
  id: string;
  askedBy: 'grill-agent' | 'po-agent' | 'build-agent';
  category: string;
  question: string;
  evidence: string;
  status: 'open' | 'answered' | 'parked';
  answer: string | null;
  answeredBy: string | null;
}

export interface GrillQuestions {
  resolved: boolean;
  rounds: number;
  questions: GrillQuestion[];
}


export type AgentKind = 'acp' | 'reasoning';

export interface AgentRepo {
  id: string;
  mode: string;
  location: string | null;
  branch: string | null;
}

export interface AgentCurrentTask {
  claimId: string;
  kind: string;
  storyBoardId: string | null;
  taskId: string | null;
  branch: string | null;
  round: number | null;
  since: string;
}

export interface AgentPresence {
  name: string;
  kind: AgentKind;
  profile: string;
  online: boolean;
  firstSeenAt: string;
  lastSeenAt: string;
  acpAgent: string | null;
  repos: AgentRepo[];
  repoMismatch: boolean;
  pollIntervalMs: number | null;
  current: AgentCurrentTask | null;
}

export interface AgentsStatus {
  generatedAt: string;
  onlineWindowSeconds: number;
  agents: AgentPresence[];
}


// Admin-managed project config (`GET/PUT/DELETE /api/projects[/{id}]`).

export interface DocLink {
  title: string;
  url: string;
}

export interface ProjectBoard {
  provider: string;
  org: string;
  project: string;
  authKind: string;
  authSecretRef: string;
  types: Record<string, string>;
  states: Record<string, string>;
}

export interface ProjectRepo {
  id: string;
  provider: string;
  url: string;
  defaultBranch: string;
  specDir: string;
  areas: string[];
  primary: boolean;
}

export interface GateRoles {
  roles: string[];
  sod: boolean;
}

export interface ProjectBuild {
  acpAgent: string;
}

export interface Project {
  id: string;
  name: string;
  board: ProjectBoard;
  repos: ProjectRepo[];
  confluenceUrl: string;
  docs: DocLink[];
  brief: string;
  gates: Record<string, GateRoles>;
  build: ProjectBuild;
  updatedAt: string;
  updatedBy: string | null;
}

export interface ProjectRequest {
  name: string;
  board: ProjectBoard;
  repos: ProjectRepo[];
  confluenceUrl: string;
  docs: DocLink[];
  brief: string;
  gates: Record<string, GateRoles>;
  build: ProjectBuild;
}

// ---- Configurable agent platform (docs/phase-1-execution-spec.md) ----

export type Capability = 'WORKSPACE_ADMIN' | 'AUTHOR' | 'OPERATOR' | 'REVIEWER' | 'CURATOR';

export interface Workspace {
  id: string;
  name: string;
  createdAt: string;
  createdBy: string;
  /** The caller's own capabilities in this workspace. */
  capabilities: Capability[];
}

export interface AgentVariable {
  name: string;
  description: string | null;
  required: boolean;
}

export interface AgentSpec {
  description: string | null;
  runtime: string | null;
  prompt: string | null;
  variables: AgentVariable[] | null;
  model: { model: string | null; fallbacks: string[] | null } | null;
  limits: {
    maxOutputTokens: number | null;
    timeoutSeconds: number | null;
    /** Tool loop bounds (Phase 2 slice 2.1); omitted = server defaults (8 turns, 16 calls). */
    maxModelTurns?: number | null;
    maxToolCalls?: number | null;
  } | null;
  outputSchema: Record<string, unknown> | null;
  /** Pinned published tool versions; omitted when the agent uses no tools. */
  tools?: ToolRef[] | null;
}

export interface ToolRef {
  tool: string;
  version: number;
}

/** docs/phase-2-execution-spec.md slice 2.1: one HTTP operation against an HTTP_API connection. */
export interface ToolSpec {
  description: string | null;
  kind: string | null;
  connectionId: string | null;
  method: string | null;
  path: string | null;
  inputSchema: Record<string, unknown> | null;
  effect: 'READ' | 'WRITE' | null;
  timeoutSeconds: number | null;
  maxResponseBytes: number | null;
  /** WRITE tools (slice 2.2): HEADER = the target honors Idempotency-Key. Omitted = NONE. */
  idempotency?: 'HEADER' | 'NONE' | null;
  approval?: { escalateAfterMinutes: number | null; expireAfterMinutes: number | null } | null;
}

/** docs/phase-2-execution-spec.md slice 2.2: a WRITE call waiting for (or past) a human decision. */
export interface Approval {
  id: string;
  workspaceId: string;
  runId: string;
  agentId: string;
  agentVersion: number;
  runCreatedBy: string;
  turn: number;
  callId: string;
  toolId: string;
  toolVersion: number;
  method: string | null;
  path: string | null;
  connectionId: string | null;
  argsJson: string;
  argsHash: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED';
  requestedAt: string;
  escalatedAt: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  reason: string | null;
}

export interface Effect {
  id: string;
  runId: string;
  approvalId: string;
  toolId: string;
  toolVersion: number;
  idempotencyKey: string;
  state: 'INTENDED' | 'SENT' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN';
  sendCount: number;
  httpStatus: number | null;
  resolution: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
  note: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ToolDefinition {
  workspaceId: string;
  id: string;
  status: 'ACTIVE' | 'RETIRED';
  draftName: string;
  draftSpec: ToolSpec | null;
  draftRevision: number;
  currentVersion: number | null;
  latestVersion: number | null;
  updatedAt: string;
  updatedBy: string;
}

export interface ToolVersion {
  workspaceId: string;
  toolId: string;
  version: number;
  name: string;
  spec: ToolSpec;
  contentHash: string;
  publishedAt: string;
  publishedBy: string;
}

/** A connection granted to the workspace (no secret reference is exposed). */
export interface WorkspaceConnection {
  id: string;
  kind: string;
  authType: string;
  baseUrl: string;
  status: string;
  expiresAt: string | null;
}

export interface ToolCallRecord {
  id: number;
  attempt: number;
  turn: number;
  toolId: string;
  toolVersion: number | null;
  argsJson: string | null;
  argsHash: string | null;
  decision: 'ALLOWED' | 'DENIED' | 'PENDING_APPROVAL';
  reason: string | null;
  httpStatus: number | null;
  durationMs: number | null;
  responseBytes: number | null;
  truncated: boolean;
  error: string | null;
  createdAt: string;
}

export interface AgentDefinition {
  workspaceId: string;
  id: string;
  status: 'ACTIVE' | 'RETIRED';
  draftName: string;
  draftSpec: AgentSpec | null;
  draftRevision: number;
  currentVersion: number | null;
  latestVersion: number | null;
  updatedAt: string;
  updatedBy: string;
}

export interface AgentVersion {
  workspaceId: string;
  agentId: string;
  version: number;
  name: string;
  spec: AgentSpec;
  contentHash: string;
  publishedAt: string;
  publishedBy: string;
}

export interface AgentValidation {
  valid: boolean;
  errors: string[];
  contentHash: string | null;
  draftRevision: number;
}

export interface CatalogModel {
  id: string;
  connectionId: string;
  providerModel: string;
  displayName: string;
  enabled: boolean;
  available: boolean;
  unavailableReason: string | null;
}

export type RunStatus = 'QUEUED' | 'RUNNING' | 'AWAITING_APPROVAL' | 'NEEDS_OPERATOR' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface PlatformRun {
  id: string;
  workspaceId: string;
  agentId: string;
  agentVersion: number;
  contentHash: string;
  model: string;
  providerModel: string;
  connectionId: string;
  fallback: boolean;
  inputs: Record<string, string>;
  status: RunStatus;
  outputText: string | null;
  output: unknown;
  error: string | null;
  /** null = the provider did not report usage (unknown, not zero). */
  promptTokens: number | null;
  completionTokens: number | null;
  attempts: number;
  idempotencyKey: string | null;
  createdBy: string;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}
