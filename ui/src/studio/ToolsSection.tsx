import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plug, Plus } from 'lucide-react';
import { Box, Button, Callout, Dialog, Flex, SegmentedControl, Select, Skeleton, Table, Text, TextField } from '@radix-ui/themes';
import { errorMessage, studio } from '../api';
import EmptyState from '../components/EmptyState';
import ErrorCallout from '../components/ErrorCallout';
import RelativeTime from '../components/RelativeTime';
import type { SandboxImage, ToolSpec, Workspace } from '../types';
import { can, statusVariant } from './workspace';
import McpDiscoveryPanel from './McpDiscoveryPanel';

const STARTER_TOOL: ToolSpec = {
  description: null,
  kind: 'http',
  connectionId: null,
  method: 'GET',
  path: '/',
  inputSchema: { type: 'object', properties: {} },
  effect: 'READ',
  timeoutSeconds: 10,
  maxResponseBytes: 65536,
};

/** A sandbox tool starts as exactly what the enterprise approved: the image's digest, schema and time limit. */
function sandboxStarter(image: SandboxImage): ToolSpec {
  return {
    description: image.description,
    kind: 'sandbox',
    connectionId: null,
    method: null,
    path: null,
    inputSchema: image.inputSchema,
    effect: 'READ',
    timeoutSeconds: image.timeoutSeconds,
    maxResponseBytes: 65536,
    sandboxImage: image.id,
    sandboxImageRef: image.imageRef,
  };
}

/** What the tools table shows as a tool's operation. */
export function operationLabel(spec: ToolSpec | null): string {
  if (spec?.kind === 'mcp') return `MCP ${spec.connectionId} · ${spec.mcpTool}`;
  if (spec?.kind === 'sandbox') return `SANDBOX ${spec.sandboxImage}`;
  return `${spec?.method} ${spec?.connectionId}${spec?.path}`;
}

/**
 * A workspace's governed API tools (docs/phase-2-execution-spec.md slice 2.1) and the HTTP_API
 * connections an enterprise Admin granted it. Tools bind one of those connections; the credential
 * stays with the connection and is never shown here.
 */
export default function ToolsSection({ workspace }: { workspace: Workspace }) {
  const toolsQuery = useQuery({ queryKey: ['studio', workspace.id, 'tools'], queryFn: () => studio.tools(workspace.id) });
  const connectionsQuery = useQuery({
    queryKey: ['studio', workspace.id, 'connections'],
    queryFn: () => studio.connections(workspace.id),
  });
  const canAuthor = can(workspace, 'AUTHOR', 'WORKSPACE_ADMIN');
  const connections = connectionsQuery.data ?? [];

  return (
    <Box>
      <Flex justify="between" align="center" mb="3" gap="3" wrap="wrap">
        <Text size="2" color="gray">
          Granted connections:{' '}
          {connections.length === 0 ? (
            <span>none - ask an enterprise Admin to grant an HTTP_API or MCP_SERVER connection to {workspace.name}.</span>
          ) : (
            connections.map((c) => (
              <span key={c.id} className="meta-tag" style={{ marginRight: 4 }} title={c.baseUrl}>
                {c.id}
                {c.status !== 'ACTIVE' ? ` (${c.status.toLowerCase()})` : ''}
              </span>
            ))
          )}
        </Text>
        {canAuthor && <NewToolDialog workspaceId={workspace.id} />}
      </Flex>
      <McpDiscoveryPanel workspaceId={workspace.id} connections={connections} canAuthor={canAuthor} />
      {toolsQuery.isLoading ? (
        <Skeleton height="160px" />
      ) : toolsQuery.isError ? (
        <ErrorCallout title="Failed to load tools" error={toolsQuery.error} />
      ) : (toolsQuery.data ?? []).length === 0 ? (
        <EmptyState
          icon={<Plug size={28} />}
          title="No tools in this workspace"
          hint={canAuthor ? 'Create a tool over a granted connection, then publish it so agents can pin it.' : 'Authors create tools here.'}
        />
      ) : (
        <Box style={{ overflowX: 'auto' }}>
          <Table.Root variant="surface">
            <Table.Header>
              <Table.Row>
                <Table.ColumnHeaderCell>Tool</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Operation</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Effect</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Status</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Agents pin</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Updated</Table.ColumnHeaderCell>
              </Table.Row>
            </Table.Header>
            <Table.Body>
              {(toolsQuery.data ?? []).map((t) => (
                <Table.Row key={t.id}>
                  <Table.RowHeaderCell>
                    <Link to={`/studio/${encodeURIComponent(workspace.id)}/tools/${encodeURIComponent(t.id)}`}>
                      <Text weight="bold">{t.draftName}</Text>
                    </Link>
                    <Text size="1" color="gray" as="div">
                      {t.id}
                    </Text>
                  </Table.RowHeaderCell>
                  <Table.Cell>
                    <code style={{ fontSize: 12 }}>{operationLabel(t.draftSpec)}</code>
                  </Table.Cell>
                  <Table.Cell>
                    <span className={`pill ${t.draftSpec?.effect === 'WRITE' ? 'review' : 'info'}`}>{t.draftSpec?.effect ?? '—'}</span>
                  </Table.Cell>
                  <Table.Cell>
                    <span className={`pill ${statusVariant(t.status)}`}>{t.status}</span>
                  </Table.Cell>
                  <Table.Cell>{t.currentVersion != null ? `v${t.currentVersion}` : <Text color="gray">unpublished</Text>}</Table.Cell>
                  <Table.Cell>
                    <RelativeTime iso={t.updatedAt} /> <Text size="1" color="gray">by {t.updatedBy}</Text>
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

function NewToolDialog({ workspaceId }: { workspaceId: string }) {
  const [open, setOpen] = useState(false);
  const [id, setId] = useState('');
  const [name, setName] = useState('');
  const [kind, setKind] = useState<'http' | 'sandbox'>('http');
  const [imageId, setImageId] = useState('');
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const imagesQuery = useQuery({ queryKey: ['platform', 'sandbox-images'], queryFn: studio.sandboxImages, enabled: open });
  const images = (imagesQuery.data ?? []).filter((i) => i.status === 'ACTIVE');
  const image = images.find((i) => i.id === imageId);
  const create = useMutation({
    mutationFn: () =>
      studio.createTool(workspaceId, { id, name, spec: kind === 'sandbox' && image ? sandboxStarter(image) : STARTER_TOOL }),
    onSuccess: (t) => {
      void queryClient.invalidateQueries({ queryKey: ['studio', workspaceId, 'tools'] });
      setOpen(false);
      navigate(`/studio/${encodeURIComponent(workspaceId)}/tools/${encodeURIComponent(t.id)}`);
    },
  });
  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger>
        <Button>
          <Plus size={14} /> New tool
        </Button>
      </Dialog.Trigger>
      <Dialog.Content maxWidth="440px">
        <Dialog.Title>New tool</Dialog.Title>
        <Dialog.Description size="2" color="gray" mb="3">
          Creates a draft. Agents can pin a tool only after a version is published.
        </Dialog.Description>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            create.mutate();
          }}
        >
          <Flex direction="column" gap="3">
            <Box>
              <Text size="1" color="gray" as="div" mb="1">
                Runs as
              </Text>
              <SegmentedControl.Root value={kind} onValueChange={(v) => setKind(v as 'http' | 'sandbox')} style={{ width: '100%' }}>
                <SegmentedControl.Item value="http">HTTP API call</SegmentedControl.Item>
                <SegmentedControl.Item value="sandbox">Sandbox image</SegmentedControl.Item>
              </SegmentedControl.Root>
            </Box>
            {kind === 'sandbox' && (
              <Box>
                <Text size="1" color="gray" as="div" mb="1">
                  Enterprise-approved image (isolated container; network only to its approved hosts)
                </Text>
                {imagesQuery.isError ? (
                  <Text size="1" color="red">
                    {errorMessage(imagesQuery.error)}
                  </Text>
                ) : images.length === 0 && !imagesQuery.isLoading ? (
                  <Text size="1" color="gray">
                    No approved images yet - an enterprise Admin adds them to the sandbox catalog.
                  </Text>
                ) : (
                  <Select.Root value={imageId || undefined} onValueChange={setImageId}>
                    <Select.Trigger aria-label="Sandbox image" placeholder="Choose an image" style={{ width: '100%' }} />
                    <Select.Content>
                      {images.map((i) => (
                        <Select.Item key={i.id} value={i.id}>
                          {i.id} — {i.description}
                        </Select.Item>
                      ))}
                    </Select.Content>
                  </Select.Root>
                )}
              </Box>
            )}
            <label>
              <Text size="1" color="gray">Id (the name the model calls it by: lowercase letters, digits, dashes)</Text>
              <TextField.Root value={id} onChange={(e) => setId(e.target.value)} placeholder={kind === 'sandbox' ? 'order-report' : 'get-order'} required />
            </label>
            <label>
              <Text size="1" color="gray">Name</Text>
              <TextField.Root value={name} onChange={(e) => setName(e.target.value)} placeholder={kind === 'sandbox' ? 'Order report' : 'Get order'} required />
            </label>
            {create.isError && (
              <Callout.Root color="red">
                <Callout.Text>{errorMessage(create.error)}</Callout.Text>
              </Callout.Root>
            )}
            <Flex gap="2" justify="end">
              <Dialog.Close>
                <Button type="button" variant="soft" color="gray">
                  Cancel
                </Button>
              </Dialog.Close>
              <Button type="submit" loading={create.isPending} disabled={kind === 'sandbox' && !image}>
                Create draft
              </Button>
            </Flex>
          </Flex>
        </form>
      </Dialog.Content>
    </Dialog.Root>
  );
}
