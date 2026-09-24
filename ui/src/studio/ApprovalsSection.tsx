import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ShieldCheck } from 'lucide-react';
import { Box, Button, Callout, Flex, SegmentedControl, Skeleton, Text, TextArea } from '@radix-ui/themes';
import { toast } from 'sonner';
import { errorMessage, studio } from '../api';
import { useIdentity } from '../identity';
import EmptyState from '../components/EmptyState';
import ErrorCallout from '../components/ErrorCallout';
import RelativeTime from '../components/RelativeTime';
import CopyHash from '../components/CopyHash';
import type { Approval, Workspace } from '../types';
import { can, statusVariant } from './workspace';

/** Pending approvals for the Studio's count badge (polled so new requests surface). */
export function usePendingApprovals(workspaceId: string) {
  return useQuery({
    queryKey: ['studio', workspaceId, 'approvals', 'PENDING'],
    queryFn: () => studio.approvals(workspaceId, 'PENDING'),
    refetchInterval: 5000,
  });
}

/**
 * The write-approval inbox (docs/phase-2-execution-spec.md slice 2.2). Each request authorizes
 * exactly one call - this tool version with these arguments (shown with their hash) - in one run.
 * A workspace REVIEWER decides; the user who started the run cannot (maker-checker). Nothing is
 * approved by time: an undecided request escalates, then expires as a denial.
 */
export default function ApprovalsSection({ workspace }: { workspace: Workspace }) {
  const [view, setView] = useState<'PENDING' | 'ALL'>('PENDING');
  const query = useQuery({
    queryKey: ['studio', workspace.id, 'approvals', view],
    queryFn: () => studio.approvals(workspace.id, view === 'PENDING' ? 'PENDING' : undefined),
    refetchInterval: 5000,
  });
  const approvals = query.data ?? [];

  return (
    <Box>
      <Flex justify="between" align="center" mb="3" gap="3" wrap="wrap">
        <Text size="2" color="gray">
          Writes that agents asked to make. Approving one lets exactly that call run once.
        </Text>
        <SegmentedControl.Root value={view} onValueChange={(v) => setView(v as 'PENDING' | 'ALL')} aria-label="Approvals shown">
          <SegmentedControl.Item value="PENDING">Pending</SegmentedControl.Item>
          <SegmentedControl.Item value="ALL">All recent</SegmentedControl.Item>
        </SegmentedControl.Root>
      </Flex>
      {!can(workspace, 'REVIEWER') && (
        <Callout.Root color="gray" size="1" mb="3">
          <Callout.Text>Deciding needs the REVIEWER capability in {workspace.name}.</Callout.Text>
        </Callout.Root>
      )}
      {query.isLoading ? (
        <Skeleton height="160px" />
      ) : query.isError ? (
        <ErrorCallout title="Failed to load approvals" error={query.error} />
      ) : approvals.length === 0 ? (
        <EmptyState
          icon={<ShieldCheck size={28} />}
          title={view === 'PENDING' ? 'Nothing waiting for approval' : 'No approvals yet'}
          hint="An agent's write tool call appears here and its run waits until someone decides."
        />
      ) : (
        <Flex direction="column" gap="3">
          {approvals.map((a) => (
            <ApprovalCard key={a.id} approval={a} workspace={workspace} />
          ))}
        </Flex>
      )}
    </Box>
  );
}

function ApprovalCard({ approval: a, workspace }: { approval: Approval; workspace: Workspace }) {
  const identity = useIdentity();
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const own = identity.user === a.runCreatedBy;
  const canDecide = a.status === 'PENDING' && can(workspace, 'REVIEWER') && !own;
  const decide = useMutation({
    mutationFn: (decision: 'approve' | 'reject') => studio.decideApproval(workspace.id, a.id, decision, reason),
    onSuccess: (d) => {
      void queryClient.invalidateQueries({ queryKey: ['studio', workspace.id, 'approvals'] });
      toast.success(d.status === 'APPROVED' ? 'Approved — the run resumes' : 'Rejected — the agent is told why');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  let args = a.argsJson;
  try {
    args = JSON.stringify(JSON.parse(a.argsJson), null, 2);
  } catch {
    /* show as stored */
  }

  return (
    <Box style={{ border: '1px solid var(--line)', borderRadius: 8, padding: 14 }}>
      <Flex justify="between" align="start" gap="3" wrap="wrap" mb="2">
        <Box>
          <Flex gap="2" align="center" wrap="wrap">
            <span className={`pill ${statusVariant(a.status)}`}>{a.status}</span>
            {a.escalatedAt && a.status === 'PENDING' && <span className="pill fail">ESCALATED</span>}
            <Text weight="bold">
              {a.toolId} v{a.toolVersion}
            </Text>
            <code style={{ fontSize: 12 }}>
              {a.method} {a.connectionId}
              {a.path}
            </code>
          </Flex>
          <Text size="1" color="gray" as="div" mt="1">
            Requested by agent{' '}
            <Link to={`/studio/${encodeURIComponent(workspace.id)}/agents/${encodeURIComponent(a.agentId)}`}>
              {a.agentId} v{a.agentVersion}
            </Link>{' '}
            in a run started by {a.runCreatedBy} · <RelativeTime iso={a.requestedAt} />
            {a.escalatedAt ? (
              <>
                {' '}
                · escalated <RelativeTime iso={a.escalatedAt} />
              </>
            ) : null}
          </Text>
        </Box>
        <CopyHash hash={a.argsHash.replace(/^sha256:/, '')} />
      </Flex>
      <Text size="1" color="gray" as="div">
        Arguments (exactly what will be sent)
      </Text>
      <Box
        style={{ whiteSpace: 'pre-wrap', fontFamily: 'var(--code-font-family, monospace)', fontSize: 13, background: 'var(--s2)', padding: 10, borderRadius: 6, wordBreak: 'break-all' }}
      >
        {args}
      </Box>
      {a.status === 'PENDING' ? (
        <Flex direction="column" gap="2" mt="3">
          <TextArea
            aria-label={`Reason for ${a.toolId} decision`}
            placeholder="Reason (shown to the agent if rejected)"
            rows={2}
            value={reason}
            disabled={!canDecide}
            onChange={(e) => setReason(e.target.value)}
          />
          <Flex gap="2" align="center" wrap="wrap">
            <Button onClick={() => decide.mutate('approve')} disabled={!canDecide} loading={decide.isPending && decide.variables === 'approve'}>
              Approve this call
            </Button>
            <Button color="red" variant="soft" onClick={() => decide.mutate('reject')} disabled={!canDecide} loading={decide.isPending && decide.variables === 'reject'}>
              Reject
            </Button>
            {own && (
              <Text size="1" color="gray">
                You started this run, so someone else must decide.
              </Text>
            )}
          </Flex>
        </Flex>
      ) : (
        a.decidedAt && (
          <Text size="1" color="gray" as="div" mt="2">
            {a.status.toLowerCase()} {a.decidedBy ? `by ${a.decidedBy}` : ''} <RelativeTime iso={a.decidedAt} />
            {a.reason ? ` — ${a.reason}` : ''}
          </Text>
        )
      )}
    </Box>
  );
}
