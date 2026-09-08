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

export interface ArtifactVersion {
  version: number;
  contentHash: string;
  storyMarkdown: string;
  comments: Comment[];
}

export interface Approval {
  who: string;
  role: string;
  version: number;
  contentHash: string;
  at: string;
}

export interface GateState {
  version: number;
  approvals: Record<string, Approval>;
  openBlockingComments: number;
  stage: string;
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
}

export interface ItemSummary {
  id: string;
  boardId: string;
  kind: string;
  title: string;
  canonicalState: string;
  updatedAt: string;
  parentId: string | null;
  qualityVerdict: string | null;
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
  askedBy: 'grill-agent' | 'po-agent';
  category: string;
  question: string;
  evidence: string;
  status: 'open' | 'answered' | 'parked';
  answer: string | null;
  answeredBy: string | null;
}

export interface GrillQuestions {
  resolved: boolean;
  questions: GrillQuestion[];
}

