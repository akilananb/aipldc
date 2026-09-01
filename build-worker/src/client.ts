import type { AgentConfig } from './config';
import type { BuildResult, ClaimedTask } from './types';

/** Thin REST wrapper over control-plane's `/api/build-tasks/*` — the agent's only Temporal
 * boundary (control-plane owns the actual Temporal client and async activity completion). */
export class ApiClient {
  constructor(public readonly cfg: AgentConfig) {}

  async claim(): Promise<ClaimedTask | null> {
    const res = await this.post('/api/build-tasks/claim', { agent: this.cfg.agentName, filters: this.cfg.filters });
    if (res.status === 204) {
      return null;
    }
    await this.assertOk(res);
    return (await res.json()) as ClaimedTask;
  }

  async heartbeat(id: string): Promise<'ok' | 'gone'> {
    const res = await this.post(`/api/build-tasks/${id}/heartbeat`);
    if (res.status === 410) {
      return 'gone';
    }
    await this.assertOk(res);
    return 'ok';
  }

  async postResult(id: string, result: BuildResult): Promise<'ok' | 'gone'> {
    const res = await this.post(`/api/build-tasks/${id}/result`, result);
    if (res.status === 410) {
      return 'gone';
    }
    await this.assertOk(res);
    return 'ok';
  }

  async postFail(id: string, message: string): Promise<void> {
    const res = await this.post(`/api/build-tasks/${id}/fail`, { message });
    if (res.status === 410) {
      return;
    }
    await this.assertOk(res);
  }

  private post(pathname: string, body?: unknown): Promise<Response> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.cfg.agentToken) {
      headers['X-Agent-Token'] = this.cfg.agentToken;
    }
    return fetch(`${this.cfg.apiUrl}${pathname}`, {
      method: 'POST',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }

  private async assertOk(res: Response): Promise<void> {
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`request failed: ${res.status} ${text}`);
    }
  }
}
