// Wire shapes for the control-plane REST API (contract from shared context).

export type CommentIntent = 'change' | 'question' | 'note';

export interface CommentAnchor {
  line: number;
  anchorText: string;
  nodeType: string;
  scenario: string | null;
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
}

export interface ItemSummary {
  id: string;
  boardId: string;
  kind: string;
  title: string;
  canonicalState: string;
  updatedAt: string;
}
