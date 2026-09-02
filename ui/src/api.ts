import { getIdentity } from './identity';
import type { ArtifactVersion, BoardComment, Comment, CommentIntent, ItemDetail, ItemSummary, QualityReport, ReleaseDocument, SpecDocs } from './types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8081';

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: string,
  ) {
    super(`HTTP ${status}: ${body || '(empty body)'}`);
    this.name = 'ApiError';
  }

  get friendly(): string {
    try {
      const parsed = JSON.parse(this.body) as { error?: string };
      if (parsed.error) return parsed.error;
    } catch {
      // not JSON — fall through
    }
    return this.body || `HTTP ${this.status}`;
  }
}

export function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.friendly : String(e);
}

function authHeaders(): Record<string, string> {
  const id = getIdentity();
  return { 'X-User': id.user, 'X-Role': id.role };
}

async function requestText(path: string): Promise<string> {
  const res = await fetch(`${BASE_URL}${path}`, { headers: authHeaders() });
  if (!res.ok) {
    throw new ApiError(res.status, await res.text().catch(() => ''));
  }
  return res.text();
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      ...authHeaders(),
      ...(init?.body != null ? { 'Content-Type': 'application/json' } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    throw new ApiError(res.status, await res.text().catch(() => ''));
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export interface AddCommentBody {
  target: string;
  text: string;
  intent: CommentIntent;
  blocking: boolean;
}

export const api = {
  listItems(): Promise<ItemSummary[]> {
    return request<ItemSummary[]>('/api/items');
  },

  getItem(id: string): Promise<ItemDetail> {
    return request<ItemDetail>(`/api/items/${encodeURIComponent(id)}`);
  },

  getQuality(id: string): Promise<QualityReport> {
    return request<QualityReport>(`/api/items/${encodeURIComponent(id)}/quality`);
  },

  getSpecDocs(id: string): Promise<SpecDocs> {
    return request<SpecDocs>(`/api/items/${encodeURIComponent(id)}/spec-docs`);
  },

  getArtifact(id: string, version: number): Promise<ArtifactVersion> {
    return request<ArtifactVersion>(`/api/artifacts/${encodeURIComponent(id)}/versions/${version}`);
  },

  getReviewMd(id: string): Promise<string> {
    return requestText(`/api/items/${encodeURIComponent(id)}/review-md`);
  },

  addComment(id: string, body: AddCommentBody): Promise<Comment> {
    return request<Comment>(`/api/artifacts/${encodeURIComponent(id)}/comments`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },

  approve(id: string, note: string): Promise<unknown> {
    return request<unknown>(`/api/items/${encodeURIComponent(id)}/approve`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    });
  },

  requestChanges(id: string): Promise<unknown> {
    return request<unknown>(`/api/items/${encodeURIComponent(id)}/request-changes`, {
      method: 'POST',
    });
  },

  approveAgentResult(id: string, commentId: string): Promise<void> {
    return request<void>(`/api/artifacts/${encodeURIComponent(id)}/comments/${encodeURIComponent(commentId)}/approve-agent-result`, {
      method: 'POST',
    });
  },

  prApprove(id: string, note: string): Promise<unknown> {
    return request<unknown>(`/api/items/${encodeURIComponent(id)}/pr/approve`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    });
  },

  prRequestChanges(id: string): Promise<unknown> {
    return request<unknown>(`/api/items/${encodeURIComponent(id)}/pr/request-changes`, {
      method: 'POST',
    });
  },

  getRelease(id: string): Promise<ReleaseDocument[]> {
    return request<ReleaseDocument[]>(`/api/items/${encodeURIComponent(id)}/release`);
  },

  signReleaseDoc(id: string, docId: string, note: string): Promise<unknown> {
    return request<unknown>(`/api/items/${encodeURIComponent(id)}/release/${encodeURIComponent(docId)}/sign`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    });
  },

  getBoardComments(id: string): Promise<BoardComment[]> {
    return request<BoardComment[]>(`/api/items/${encodeURIComponent(id)}/board-comments`);
  },
};
