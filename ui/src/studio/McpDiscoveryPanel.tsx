import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Box, Button, Callout, Flex, Select, Table, Text } from '@radix-ui/themes';
import { Radar } from 'lucide-react';
import { toast } from 'sonner';
import { errorMessage, studio } from '../api';
import type { McpDiscoveredTool, McpDiscovery, ToolSpec, WorkspaceConnection } from '../types';

const STATE_PILL: Record<string, string> = {
  NEW: 'info',
  APPROVED: 'pass',
  CHANGED: 'review',
  UNSUPPORTED_SCHEMA: 'fail',
  REMOVED: 'fail',
};

function toolId(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'mcp-tool';
}

function mcpSpec(connectionId: string, t: McpDiscoveredTool, effect: 'READ' | 'WRITE', description?: string | null): ToolSpec {
  return {
    description: description ?? t.description,
    kind: 'mcp',
    connectionId,
    method: null,
    path: null,
    inputSchema: t.inputSchema,
    effect,
    timeoutSeconds: 30,
    maxResponseBytes: 65536,
    mcpTool: t.name,
    mcpFingerprint: t.fingerprint,
  };
}

/**
 * Review of a granted MCP server's tools (docs/phase-2-execution-spec.md slice 2.3). Discovery only
 * lists; approving is the normal tool lifecycle - a draft pinning the reviewed fingerprint, then a
 * published version. The effect is the reviewer's call; the server's hints are only a suggestion.
 */
export default function McpDiscoveryPanel({ workspaceId, connections, canAuthor }: {
  workspaceId: string;
  connections: WorkspaceConnection[];
  canAuthor: boolean;
}) {
  const mcpConnections = connections.filter((c) => c.kind === 'MCP_SERVER' && c.status === 'ACTIVE');
  // The connections load after first render: fall back to the first server until one is picked.
  const [picked, setConnectionId] = useState('');
  const connectionId = mcpConnections.some((c) => c.id === picked) ? picked : (mcpConnections[0]?.id ?? '');
  const [result, setResult] = useState<McpDiscovery | null>(null);
  const [effects, setEffects] = useState<Record<string, 'READ' | 'WRITE'>>({});
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const discover = useMutation({
    mutationFn: () => studio.mcpDiscover(workspaceId, connectionId),
    onSuccess: (r) => {
      setResult(r);
      setEffects(Object.fromEntries(r.tools.map((t) => [t.name, t.suggestedEffect ?? 'WRITE'])));
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const draft = useMutation({
    mutationFn: async (t: McpDiscoveredTool) => {
      const effect = effects[t.name] ?? 'WRITE';
      if (t.state === 'CHANGED' && t.toolId) {
        const existing = await studio.tool(workspaceId, t.toolId);
        const updated = mcpSpec(connectionId, t, existing.draftSpec?.effect ?? effect, existing.draftSpec?.description);
        return studio.saveToolDraft(workspaceId, t.toolId, { name: existing.draftName, spec: updated, revision: existing.draftRevision });
      }
      return studio.createTool(workspaceId, { id: toolId(t.name), name: t.name, spec: mcpSpec(connectionId, t, effect) });
    },
    onSuccess: (tool) => {
      void queryClient.invalidateQueries({ queryKey: ['studio', workspaceId, 'tools'] });
      toast.success('Draft ready — review it, then publish to approve');
      navigate(`/studio/${encodeURIComponent(workspaceId)}/tools/${encodeURIComponent(tool.id)}`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  if (mcpConnections.length === 0) {
    return null;
  }
  return (
    <Box mb="4" style={{ border: '1px solid var(--line)', borderRadius: 8, padding: 14 }}>
      <Flex gap="2" align="center" wrap="wrap" mb="2">
        <Text size="2" weight="medium">
          Review tools from an MCP server
        </Text>
        <Select.Root value={connectionId} onValueChange={setConnectionId}>
          <Select.Trigger aria-label="MCP server" />
          <Select.Content>
            {mcpConnections.map((c) => (
              <Select.Item key={c.id} value={c.id}>
                {c.id} — {c.baseUrl}
              </Select.Item>
            ))}
          </Select.Content>
        </Select.Root>
        <Button variant="soft" onClick={() => discover.mutate()} loading={discover.isPending} disabled={!canAuthor || !connectionId}>
          <Radar size={14} /> Discover
        </Button>
        {!canAuthor && (
          <Text size="1" color="gray">
            Discovery needs AUTHOR.
          </Text>
        )}
      </Flex>
      {result?.error && (
        <Callout.Root color="red" size="1">
          <Callout.Text>Could not list the server's tools: {result.error}</Callout.Text>
        </Callout.Root>
      )}
      {result && !result.error && (
        <Box style={{ overflowX: 'auto' }}>
          <Table.Root variant="surface" size="1">
            <Table.Header>
              <Table.Row>
                <Table.ColumnHeaderCell>Server tool</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Review</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Effect</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell />
              </Table.Row>
            </Table.Header>
            <Table.Body>
              {result.tools.map((t) => (
                <Table.Row key={t.name}>
                  <Table.Cell>
                    <code style={{ fontSize: 12 }}>{t.name}</code>
                    {t.description && (
                      <Text size="1" color="gray" as="div" style={{ maxWidth: 360 }}>
                        {t.description}
                      </Text>
                    )}
                  </Table.Cell>
                  <Table.Cell>
                    <span className={`pill ${STATE_PILL[t.state]}`}>{t.state.replace('_', ' ')}</span>
                    {t.toolId && (
                      <Text size="1" as="div">
                        <Link to={`/studio/${encodeURIComponent(workspaceId)}/tools/${encodeURIComponent(t.toolId)}`}>{t.toolId}</Link>
                        {t.approvedVersion ? ` v${t.approvedVersion}` : ''}
                      </Text>
                    )}
                    {t.problems.map((p) => (
                      <Text key={p} size="1" color="red" as="div" style={{ maxWidth: 360 }}>
                        {p}
                      </Text>
                    ))}
                  </Table.Cell>
                  <Table.Cell>
                    {t.state === 'NEW' ? (
                      <Select.Root value={effects[t.name]} onValueChange={(v) => setEffects({ ...effects, [t.name]: v as 'READ' | 'WRITE' })}>
                        <Select.Trigger aria-label={`Effect for ${t.name}`} />
                        <Select.Content>
                          <Select.Item value="READ">READ</Select.Item>
                          <Select.Item value="WRITE">WRITE (needs approval per call)</Select.Item>
                        </Select.Content>
                      </Select.Root>
                    ) : (
                      <Text size="1" color="gray">
                        {t.state === 'REMOVED' ? '—' : `server hint: ${t.suggestedEffect}`}
                      </Text>
                    )}
                  </Table.Cell>
                  <Table.Cell>
                    {t.state === 'NEW' && (
                      <Button size="1" disabled={!canAuthor} loading={draft.isPending && draft.variables?.name === t.name} onClick={() => draft.mutate(t)}>
                        Create draft
                      </Button>
                    )}
                    {t.state === 'CHANGED' && (
                      <Button size="1" color="amber" disabled={!canAuthor} loading={draft.isPending && draft.variables?.name === t.name} onClick={() => draft.mutate(t)}>
                        Update draft to server version
                      </Button>
                    )}
                    {t.state === 'REMOVED' && (
                      <Text size="1" color="gray">
                        No longer offered; calls are refused.
                      </Text>
                    )}
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
