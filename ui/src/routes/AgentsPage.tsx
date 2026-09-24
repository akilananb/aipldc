import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowRight, Radio } from 'lucide-react';
import { Box, Flex, Skeleton, Table, Tooltip } from '@radix-ui/themes';
import { api } from '../api';
import type { AgentPresence, AgentRepo } from '../types';
import { agentRepoLabel } from '../ui-utils';
import RelativeTime, { relativize } from '../components/RelativeTime';
import PageHeader from '../components/PageHeader';
import ErrorCallout from '../components/ErrorCallout';
import EmptyState from '../components/EmptyState';

/** Every agent process the control plane has ever seen — ACP build-workers and reasoning
 * agents, online or offline, with what each is currently working on. Same query as the sidebar's
 * {@link import('../components/AgentPresenceStatus').default}; React Query dedupes the request. */
export default function AgentsPage() {
  const agentsQuery = useQuery({
    queryKey: ['agents'],
    queryFn: api.listAgents,
    refetchInterval: 5000,
  });

  const agents = agentsQuery.data?.agents ?? [];

  return (
    <Box>
      <PageHeader
        title="Agents"
        subtitle="Build-workers (ACP) and reasoning agents, as last seen by the control plane."
        badges={<span className="pill">RUNTIME</span>}
      />

      {agentsQuery.isLoading && (
        <Flex direction="column" gap="2">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} height="32px" />
          ))}
        </Flex>
      )}

      {agentsQuery.isError && <ErrorCallout title="Failed to load agents" error={agentsQuery.error} />}

      {agentsQuery.isSuccess && agents.length === 0 && (
        <EmptyState
          icon={<Radio size={28} />}
          title="No agents seen yet"
          hint="Start build-worker (node dist/worker.js) or the agents service; they appear here on their first poll."
        />
      )}

      {agentsQuery.isSuccess && agents.length > 0 && (
        <Table.Root variant="ghost" size="1">
          <Table.Header>
            <Table.Row>
              <Table.ColumnHeaderCell>Status</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Agent</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Kind</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Last seen</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Working on</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Repo</Table.ColumnHeaderCell>
            </Table.Row>
          </Table.Header>
          <Table.Body>{agents.map((a) => renderRow(a))}</Table.Body>
        </Table.Root>
      )}

      <Box mt="5">
        <Link to="/projects">
          Set up a build agent <ArrowRight size={12} style={{ verticalAlign: 'middle' }} /> Projects
        </Link>
      </Box>
    </Box>
  );
}

function repoLine(r: AgentRepo): string {
  return r.mode === 'payload' ? `${r.id}: repo from claim payload` : `${r.id}: ${r.location ?? '—'}${r.branch ? ` @ ${r.branch}` : ''}`;
}

function renderRow(a: AgentPresence) {
  const detail = (
    <span style={{ whiteSpace: 'pre-line' }}>
      {`Repo: ${agentRepoLabel(a)}\nACP: ${a.acpAgent ?? '—'}\nPoll: ${a.pollIntervalMs ?? '—'} ms\nFirst seen: ${new Date(a.firstSeenAt).toLocaleString()}`}
    </span>
  );
  return (
    <Table.Row key={`${a.kind}:${a.name}`}>
      <Table.RowHeaderCell>
        <Tooltip content={detail}>
          <i
            style={{
              display: 'inline-block',
              width: '0.55rem',
              height: '0.55rem',
              borderRadius: '50%',
              background: a.online ? 'var(--green)' : 'var(--red)',
            }}
          />
        </Tooltip>
      </Table.RowHeaderCell>
      <Table.Cell>{a.name}</Table.Cell>
      <Table.Cell>{a.kind}</Table.Cell>
      <Table.Cell>
        <RelativeTime iso={a.lastSeenAt} />
      </Table.Cell>
      <Table.Cell>{workingOnLabel(a)}</Table.Cell>
      <Table.Cell>
        {a.repos.length === 0 ? (
          '—'
        ) : (
          <Flex direction="column">
            {a.repos.map((r) => (
              <span key={r.id}>{repoLine(r)}</span>
            ))}
          </Flex>
        )}
        {a.repoMismatch && (
          <Tooltip content="This agent's repo override does not match the project config">
            <span className="pill review" style={{ marginLeft: 6 }}>
              repo mismatch
            </span>
          </Tooltip>
        )}
      </Table.Cell>
    </Table.Row>
  );
}

function workingOnLabel(a: AgentPresence): string {
  const t = a.current;
  if (!t) return 'idle';
  const target =
    t.kind === 'plan'
      ? `story ${t.storyBoardId} · plan round ${t.round}`
      : `story ${t.storyBoardId} · ${t.taskId} · ${t.branch}`;
  return `${target} (since ${relativize(t.since)})`;
}
