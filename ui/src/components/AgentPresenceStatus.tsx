import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Tooltip } from '@radix-ui/themes';
import { api } from '../api';
import type { AgentPresence } from '../types';
import { agentRepoLabel } from '../ui-utils';
import { relativize } from './RelativeTime';

/** Sidebar-footer online/offline summary for every ACP build-worker and reasoning agent the
 * control plane has ever seen (`GET /api/agents`) — hover for a per-agent repo/branch breakdown,
 * click through to the full `/agents` page. Polls independently of the items list; React Query
 * dedupes the request when `AgentsPage` is also mounted. */
export default function AgentPresenceStatus() {
  const agentsQuery = useQuery({
    queryKey: ['agents'],
    queryFn: api.listAgents,
    refetchInterval: 5000,
  });

  const agents = agentsQuery.data?.agents ?? [];
  const onlineCount = agents.filter((a) => a.online).length;
  const total = agents.length;

  let color = 'var(--green)';
  let label = `${onlineCount} agent${onlineCount === 1 ? '' : 's'} online`;
  if (agentsQuery.isLoading) {
    color = 'var(--muted)';
    label = 'Checking agents…';
  } else if (agentsQuery.isError) {
    color = 'var(--red)';
    label = 'Agents unknown';
  } else if (total === 0) {
    color = 'var(--muted)';
    label = 'No agents have reported yet';
  } else if (onlineCount === 0) {
    color = 'var(--red)';
    label = 'No agents online';
  } else if (onlineCount < total) {
    color = 'var(--amber)';
    label = `${onlineCount}/${total} agents online`;
  }

  const tooltip = agents.length === 0 ? 'No agents have reported yet' : agents.map(agentLine).join('\n');

  return (
    <Tooltip content={<span style={{ whiteSpace: 'pre-line' }}>{tooltip}</span>}>
      <Link to="/agents" className="status">
        <i style={{ background: color }} />
        <span>{label}</span>
      </Link>
    </Tooltip>
  );
}

function agentLine(a: AgentPresence): string {
  const parts = [a.name, a.kind, a.online ? 'online' : `offline (${relativize(a.lastSeenAt)})`, agentRepoLabel(a)];
  if (a.acpAgent) parts.push(a.acpAgent);
  return parts.join(' · ');
}
