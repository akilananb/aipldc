import { getIdentity, sendsDevHeaders, type AuthState } from './identity';
import type { AgentRun, AgentsStatus, ArtifactVersion, BoardComment, Comment, CommentIntent, DemoStatus, GrillQuestions, ItemDetail, ItemSummary, Project, ProjectRequest, QualityReport, ReleaseDocument, ScenarioReview, SpecDocs } from './types';

export const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8081';

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

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

function readCookie(name: string): string | undefined {
  const match = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : undefined;
}

/** Dev headers in dev-headers mode; the CSRF token (issued by GET /api/me as the XSRF-TOKEN cookie)
 * on every unsafe request, which the backend requires for cookie-session callers. */
function authHeaders(method = 'GET'): Record<string, string> {
  const headers: Record<string, string> = {};
  if (sendsDevHeaders()) {
    const id = getIdentity();
    headers['X-User'] = id.user;
    headers['X-Role'] = id.role;
  }
  const csrf = readCookie('XSRF-TOKEN');
  if (csrf && !SAFE_METHODS.has(method.toUpperCase())) {
    headers['X-XSRF-TOKEN'] = csrf;
  }
  return headers;
}

async function requestText(path: string): Promise<string> {
  const res = await fetch(`${BASE_URL}${path}`, { headers: authHeaders(), credentials: 'include' });
  if (!res.ok) {
    throw new ApiError(res.status, await res.text().catch(() => ''));
  }
  return res.text();
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    credentials: 'include',
    headers: {
      ...authHeaders(init?.method),
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
  me(): Promise<AuthState> {
    return request<AuthState>('/api/me');
  },
  logout(): Promise<void> {
    return request<void>('/logout', { method: 'POST' });
  },
  listItems(): Promise<ItemSummary[]> {
    return request<ItemSummary[]>('/api/items');
  },

  getDemoStatus(): Promise<DemoStatus> {
    return request<DemoStatus>('/api/demo');
  },

  startLiveDemo(): Promise<{ itemId: string }> {
    return request<{ itemId: string }>('/api/demo/live/start', {
      method: 'POST',
    });
  },

  getItem(id: string): Promise<ItemDetail> {
    return request<ItemDetail>(`/api/items/${encodeURIComponent(id)}`);
  },

  getQuality(id: string): Promise<QualityReport> {
    return request<QualityReport>(`/api/items/${encodeURIComponent(id)}/quality`);
  },

  getActivity(id: string): Promise<AgentRun[]> {
    return request<AgentRun[]>(`/api/items/${encodeURIComponent(id)}/activity`);
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

  retryStep(id: string): Promise<unknown> {
    return request<unknown>(`/api/items/${encodeURIComponent(id)}/retry`, {
      method: 'POST',
    });
  },

  approveAgentResult(id: string, commentId: string): Promise<void> {
    return request<void>(`/api/artifacts/${encodeURIComponent(id)}/comments/${encodeURIComponent(commentId)}/approve-agent-result`, {
      method: 'POST',
    });
  },

  reviewScenario(id: string, version: number, scenario: string, status: 'meets' | 'not-reviewed'): Promise<ScenarioReview> {
    return request<ScenarioReview>(
      `/api/artifacts/${encodeURIComponent(id)}/versions/${version}/scenarios/${encodeURIComponent(scenario)}/review`,
      { method: 'PUT', body: JSON.stringify({ status }) },
    );
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

  planApprove(id: string, note: string): Promise<unknown> {
    return request<unknown>(`/api/items/${encodeURIComponent(id)}/plan/approve`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    });
  },

  planRequestChanges(id: string): Promise<unknown> {
    return request<unknown>(`/api/items/${encodeURIComponent(id)}/plan/request-changes`, {
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

  releaseRequestChanges(id: string): Promise<unknown> {
    return request<unknown>(`/api/items/${encodeURIComponent(id)}/release/request-changes`, {
      method: 'POST',
    });
  },

  getBoardComments(id: string): Promise<BoardComment[]> {
    return request<BoardComment[]>(`/api/items/${encodeURIComponent(id)}/board-comments`);
  },

  getGrill(id: string): Promise<GrillQuestions> {
    return request<GrillQuestions>(`/api/items/${encodeURIComponent(id)}/grill`);
  },

  answerGrillQuestion(id: string, questionId: string, text: string): Promise<void> {
    return request<void>(`/api/items/${encodeURIComponent(id)}/grill/${encodeURIComponent(questionId)}/answer`, {
      method: 'POST',
      body: JSON.stringify({ text }),
    });
  },

  parkGrillQuestion(id: string, questionId: string): Promise<void> {
    return request<void>(`/api/items/${encodeURIComponent(id)}/grill/${encodeURIComponent(questionId)}/park`, {
      method: 'POST',
    });
  },

  proceedGrill(id: string): Promise<void> {
    return request<void>(`/api/items/${encodeURIComponent(id)}/grill/proceed`, {
      method: 'POST',
    });
  },

  listAgents(): Promise<AgentsStatus> {
    return request<AgentsStatus>('/api/agents');
  },

  listProjects(): Promise<Project[]> {
    return request<Project[]>('/api/projects');
  },

  getProject(id: string): Promise<Project> {
    return request<Project>(`/api/projects/${encodeURIComponent(id)}`);
  },

  saveProject(id: string, body: ProjectRequest): Promise<Project> {
    return request<Project>(`/api/projects/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    });
  },

  deleteProject(id: string): Promise<void> {
    return request<void>(`/api/projects/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    });
  },
};
