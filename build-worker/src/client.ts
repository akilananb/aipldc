import type { AgentConfig } from './config';
import type { AgentPresenceReport, BuildResult, ClaimedTask, Lease, PlanConsultationReport } from './types';

/** Thin REST wrapper over control-plane's `/api/build-tasks/*` — the agent's only Temporal
 * boundary (control-plane owns the actual Temporal client and async activity completion). */
export class ApiClient {
  constructor(public readonly cfg: AgentConfig) {}

  async claim(presence?: AgentPresenceReport): Promise<ClaimedTask | null> {
    const res = await this.post('/api/build-tasks/claim', { agent: this.cfg.agentName, filters: this.cfg.filters, presence });
    if (res.status === 204) {
      return null;
    }
    await this.assertOk(res);
    return (await res.json()) as ClaimedTask;
  }

  async heartbeat(lease: Lease): Promise<'ok' | 'gone'> {
    const res = await this.post(`/api/build-tasks/${lease.id}/heartbeat`, undefined, lease);
    if (res.status === 410) {
      return 'gone';
    }
    await this.assertOk(res);
    return 'ok';
  }

  async postResult(lease: Lease, result: BuildResult): Promise<'ok' | 'gone'> {
    const res = await this.post(`/api/build-tasks/${lease.id}/result`, result, lease);
    if (res.status === 410) {
      return 'gone';
    }
    await this.assertOk(res);
    return 'ok';
  }

  async postPlanResult(lease: Lease, result: PlanConsultationReport): Promise<'ok' | 'gone'> {
    const res = await this.post(`/api/build-tasks/${lease.id}/plan-result`, result, lease);
    if (res.status === 410) {
      return 'gone';
    }
    await this.assertOk(res);
    return 'ok';
  }

  async postFail(lease: Lease, message: string): Promise<void> {
    const res = await this.post(`/api/build-tasks/${lease.id}/fail`, { message }, lease);
    if (res.status === 410) {
      return;
    }
    await this.assertOk(res);
  }

  private post(pathname: string, body?: unknown, lease?: Lease): Promise<Response> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.cfg.agentToken) {
      headers['X-Agent-Token'] = this.cfg.agentToken;
    }
    if (lease) {
      headers['X-Lease-Token'] = lease.leaseToken;
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
