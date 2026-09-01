import { getIdentity } from './identity';
import type { ArtifactVersion, Comment, CommentIntent, ItemDetail, ItemSummary } from './types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8081';

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: string,
  ) {
    super(`HTTP ${status}: ${body || '(empty body)'}`);
    this.name = 'ApiError';
  }
}

function authHeaders(): Record<string, string> {
  const id = getIdentity();
  return { 'X-User': id.user, 'X-Role': id.role };
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

  getArtifact(id: string, version: number): Promise<ArtifactVersion> {
    return request<ArtifactVersion>(`/api/artifacts/${encodeURIComponent(id)}/versions/${version}`);
  },

  async getReviewMd(id: string): Promise<string> {
    const res = await fetch(`${BASE_URL}/api/items/${encodeURIComponent(id)}/review-md`, {
      headers: authHeaders(),
    });
    if (!res.ok) {
      throw new ApiError(res.status, await res.text().catch(() => ''));
    }
    return res.text();
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
};
