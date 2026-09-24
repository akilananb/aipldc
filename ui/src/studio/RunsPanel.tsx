import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Box, Button, Callout, Flex, Table, Text, TextArea } from '@radix-ui/themes';
import { Play, Square } from 'lucide-react';
import { toast } from 'sonner';
import { errorMessage, studio } from '../api';
import type { AgentVersion, PlatformRun, ToolCallRecord } from '../types';
import EmptyState from '../components/EmptyState';
import ErrorCallout from '../components/ErrorCallout';
import RelativeTime from '../components/RelativeTime';
import CopyHash from '../components/CopyHash';
import { statusVariant } from './workspace';

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);

interface Props {
  workspaceId: string;
  agentId: string;
  current: AgentVersion | undefined;
  canRun: boolean;
  retired: boolean;
}

/**
 * Test invocation: starts a real durable run of the version new runs currently use, then follows it
 * (2s polling while it is not terminal). Shows exactly what the run pinned - version, content hash,
 * model and connection - and the recorded output and token usage ("unknown" when the provider did
 * not report it).
 */
export default function RunsPanel({ workspaceId, agentId, current, canRun, retired }: Props) {
  const queryClient = useQueryClient();
  const [inputs, setInputs] = useState<Record<string, string>>({});
  const [selectedRun, setSelectedRun] = useState<string | null>(null);

  useEffect(() => setInputs({}), [current?.version]);

  const runsQuery = useQuery({
    queryKey: ['studio', workspaceId, 'agent', agentId, 'runs'],
    queryFn: () => studio.runs(workspaceId, agentId),
    refetchInterval: 3000,
  });
  const selected = selectedRun ?? runsQuery.data?.[0]?.id ?? null;
  const runQuery = useQuery({
    queryKey: ['studio', workspaceId, 'run', selected],
    queryFn: () => studio.run(workspaceId, selected!),
    enabled: selected != null,
    refetchInterval: (q) => (q.state.data && TERMINAL.has(q.state.data.status) ? false : 2000),
  });

  const start = useMutation({
    mutationFn: () => studio.startRun(workspaceId, agentId, inputs),
    onSuccess: (run) => {
      setSelectedRun(run.id);
      void queryClient.invalidateQueries({ queryKey: ['studio', workspaceId, 'agent', agentId, 'runs'] });
      toast.success(`Run started on v${run.agentVersion}`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const cancel = useMutation({
    mutationFn: (runId: string) => studio.cancelRun(workspaceId, runId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['studio', workspaceId, 'run', selected] });
      toast.success('Cancellation requested');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  if (!current) {
    return <EmptyState icon={<Play size={28} />} title="Nothing to run yet" hint="Publish a version first; runs always use a published version." />;
  }

  return (
    <Flex direction="column" gap="4">
      <Box>
        <Text size="2" weight="medium" as="div" mb="2">
          Test run of v{current.version}
        </Text>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            start.mutate();
          }}
        >
          <Flex direction="column" gap="2">
            {(current.spec.variables ?? []).map((v) => (
              <label key={v.name}>
                <Text size="1" color="gray">
                  {v.name}
                  {v.required ? ' (required)' : ''}
                  {v.description ? ` — ${v.description}` : ''}
                </Text>
                <TextArea
                  rows={2}
                  value={inputs[v.name] ?? ''}
                  onChange={(e) => setInputs({ ...inputs, [v.name]: e.target.value })}
                />
              </label>
            ))}
            {(current.spec.variables ?? []).length === 0 && (
              <Text size="1" color="gray">
                This version declares no inputs.
              </Text>
            )}
            <Flex gap="2" align="center" wrap="wrap">
              <Button type="submit" disabled={!canRun || retired} loading={start.isPending}>
                <Play size={14} /> Start run
              </Button>
              {!canRun && (
                <Text size="1" color="gray">
                  Running needs the OPERATOR capability.
                </Text>
              )}
              {retired && (
                <Text size="1" color="gray">
                  Retired agents cannot run.
                </Text>
              )}
            </Flex>
          </Flex>
        </form>
      </Box>

      {runQuery.data && (
        <RunDetail
          run={runQuery.data}
          canCancel={canRun}
          onCancel={() => cancel.mutate(runQuery.data.id)}
          cancelling={cancel.isPending}
          usesTools={(current.spec.tools ?? []).length > 0}
        />
      )}

      <Box>
        <Text size="2" weight="medium" as="div" mb="2">
          Recent runs
        </Text>
        {runsQuery.isError ? (
          <ErrorCallout title="Failed to load runs" error={runsQuery.error} />
        ) : (runsQuery.data ?? []).length === 0 ? (
          <Text size="2" color="gray">
            No runs yet.
          </Text>
        ) : (
          <Box style={{ overflowX: 'auto' }}>
            <Table.Root variant="surface" size="1">
              <Table.Header>
                <Table.Row>
                  <Table.ColumnHeaderCell>Status</Table.ColumnHeaderCell>
                  <Table.ColumnHeaderCell>Version</Table.ColumnHeaderCell>
                  <Table.ColumnHeaderCell>Model</Table.ColumnHeaderCell>
                  <Table.ColumnHeaderCell>Started</Table.ColumnHeaderCell>
                  <Table.ColumnHeaderCell />
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {(runsQuery.data ?? []).map((r) => (
                  <Table.Row key={r.id} style={r.id === selected ? { background: 'var(--s2)' } : undefined}>
                    <Table.Cell>
                      <span className={`pill ${statusVariant(r.status)}`}>{r.status}</span>
                    </Table.Cell>
                    <Table.Cell>v{r.agentVersion}</Table.Cell>
                    <Table.Cell>
                      {r.model}
                      {r.fallback ? ' (fallback)' : ''}
                    </Table.Cell>
                    <Table.Cell>
                      <RelativeTime iso={r.createdAt} /> <Text size="1" color="gray">by {r.createdBy}</Text>
                    </Table.Cell>
                    <Table.Cell>
                      <Button size="1" variant="ghost" onClick={() => setSelectedRun(r.id)} aria-pressed={r.id === selected}>
                        View
                      </Button>
                    </Table.Cell>
                  </Table.Row>
                ))}
              </Table.Body>
            </Table.Root>
          </Box>
        )}
      </Box>
    </Flex>
  );
}

function tokens(n: number | null): string {
  return n == null ? 'unknown' : String(n);
}

function RunDetail({
  run,
  canCancel,
  onCancel,
  cancelling,
  usesTools,
}: {
  run: PlatformRun;
  canCancel: boolean;
  onCancel: () => void;
  cancelling: boolean;
  usesTools: boolean;
}) {
  const active = !TERMINAL.has(run.status);
  const callsQuery = useQuery({
    queryKey: ['studio', run.workspaceId, 'run', run.id, 'tool-calls'],
    queryFn: () => studio.toolCalls(run.workspaceId, run.id),
    refetchInterval: active ? 2000 : false,
  });
  const calls = callsQuery.data ?? [];
  return (
    <Box style={{ border: '1px solid var(--line)', borderRadius: 8, padding: 14 }} aria-live="polite">
      <Flex justify="between" align="center" gap="2" wrap="wrap" mb="2">
        <Flex gap="2" align="center" wrap="wrap">
          <span className={`pill ${statusVariant(run.status)}`}>
            {active && <i className="dot pulse" />}
            {run.status}
          </span>
          <Text size="2">
            pinned <strong>v{run.agentVersion}</strong>
          </Text>
          <CopyHash hash={run.contentHash.replace(/^sha256:/, '')} />
        </Flex>
        {active && canCancel && (
          <Button size="1" color="red" variant="soft" loading={cancelling} onClick={onCancel}>
            <Square size={12} /> Cancel
          </Button>
        )}
      </Flex>
      <Text size="1" color="gray" as="div" mb="2">
        Model {run.model} → {run.providerModel} via {run.connectionId}
        {run.fallback ? ' (declared fallback)' : ''} · attempts {run.attempts} · tokens in {tokens(run.promptTokens)} / out{' '}
        {tokens(run.completionTokens)}
      </Text>
      {run.error && (
        <Callout.Root color="red" size="1" mb="2">
          <Callout.Text>{run.error}</Callout.Text>
        </Callout.Root>
      )}
      {run.output != null ? (
        <Box style={{ whiteSpace: 'pre-wrap', fontFamily: 'var(--code-font-family, monospace)', fontSize: 13, background: 'var(--s2)', padding: 10, borderRadius: 6 }}>
          {JSON.stringify(run.output, null, 2)}
        </Box>
      ) : run.outputText ? (
        <Box style={{ whiteSpace: 'pre-wrap', fontFamily: 'var(--code-font-family, monospace)', fontSize: 13, background: 'var(--s2)', padding: 10, borderRadius: 6 }}>
          {run.outputText}
        </Box>
      ) : (
        active && (
          <Text size="2" color="gray">
            Waiting for the model…
          </Text>
        )
      )}
      {(usesTools || calls.length > 0) && <ToolTrace calls={calls} error={callsQuery.error} />}
    </Box>
  );
}

/** Every tool call the model requested in this run, with the policy decision and outcome. */
function ToolTrace({ calls, error }: { calls: ToolCallRecord[]; error: unknown }) {
  return (
    <Box mt="3">
      <Text size="2" weight="medium" as="div" mb="1">
        Tool calls
      </Text>
      {error ? (
        <ErrorCallout title="Failed to load tool calls" error={error} />
      ) : calls.length === 0 ? (
        <Text size="1" color="gray">
          No tool calls recorded.
        </Text>
      ) : (
        <Box style={{ overflowX: 'auto' }}>
          <Table.Root variant="surface" size="1">
            <Table.Header>
              <Table.Row>
                <Table.ColumnHeaderCell>Turn</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Tool</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Decision</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Result</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Arguments</Table.ColumnHeaderCell>
              </Table.Row>
            </Table.Header>
            <Table.Body>
              {calls.map((c) => (
                <Table.Row key={c.id}>
                  <Table.Cell>
                    {c.turn}
                    {c.attempt > 1 ? <Text size="1" color="gray"> (attempt {c.attempt})</Text> : null}
                  </Table.Cell>
                  <Table.Cell>
                    {c.toolId}
                    {c.toolVersion != null ? ` v${c.toolVersion}` : ''}
                  </Table.Cell>
                  <Table.Cell>
                    <span className={`pill ${c.decision === 'ALLOWED' ? 'pass' : 'fail'}`}>{c.decision}</span>
                    {c.reason && (
                      <Text size="1" color="gray" as="div" style={{ maxWidth: 320 }}>
                        {c.reason}
                      </Text>
                    )}
                  </Table.Cell>
                  <Table.Cell>
                    {c.decision === 'DENIED' ? (
                      <Text size="1" color="gray">
                        not executed
                      </Text>
                    ) : (
                      <Text size="1">
                        {c.httpStatus != null ? `HTTP ${c.httpStatus}` : 'no response'}
                        {c.durationMs != null ? ` · ${c.durationMs} ms` : ''}
                        {c.responseBytes != null ? ` · ${c.responseBytes} B` : ''}
                        {c.truncated ? ' · truncated' : ''}
                        {c.error ? ` · ${c.error}` : ''}
                      </Text>
                    )}
                  </Table.Cell>
                  <Table.Cell>
                    <code style={{ fontSize: 12, wordBreak: 'break-all' }}>{c.argsJson ?? '—'}</code>
                  </Table.Cell>
                </Table.Row>
              ))}
            </Table.Body>
          </Table.Root>
        </Box>
      )}
    </Box>
  );
}
