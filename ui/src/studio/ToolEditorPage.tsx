import { useEffect, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertDialog, Box, Button, Callout, Flex, Select, Skeleton, Table, Text, TextArea, TextField } from '@radix-ui/themes';
import { AlertTriangle, CheckCircle2, Save, Send } from 'lucide-react';
import { toast } from 'sonner';
import { ApiError, errorMessage, studio } from '../api';
import type { ToolDefinition, ToolSpec } from '../types';
import PageHeader, { MetaItems } from '../components/PageHeader';
import Surface from '../components/Surface';
import ErrorCallout from '../components/ErrorCallout';
import CopyHash from '../components/CopyHash';
import RelativeTime from '../components/RelativeTime';
import { can, statusVariant, useWorkspace, useWorkspaces } from './workspace';

type Tab = 'editor' | 'versions';
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

interface ToolDraft {
  name: string;
  description: string;
  connectionId: string;
  method: string;
  path: string;
  effect: string;
  timeoutSeconds: string;
  maxResponseBytes: string;
  inputSchema: string;
}

function toDraft(name: string, spec: ToolSpec | null): ToolDraft {
  return {
    name,
    description: spec?.description ?? '',
    connectionId: spec?.connectionId ?? '',
    method: spec?.method ?? 'GET',
    path: spec?.path ?? '/',
    effect: spec?.effect ?? 'READ',
    timeoutSeconds: spec?.timeoutSeconds != null ? String(spec.timeoutSeconds) : '',
    maxResponseBytes: spec?.maxResponseBytes != null ? String(spec.maxResponseBytes) : '',
    inputSchema: spec?.inputSchema ? JSON.stringify(spec.inputSchema, null, 2) : '',
  };
}

function toSpec(d: ToolDraft): { ok: true; spec: ToolSpec } | { ok: false; error: string } {
  let inputSchema: Record<string, unknown> | null = null;
  if (d.inputSchema.trim() !== '') {
    try {
      const parsed: unknown = JSON.parse(d.inputSchema);
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return { ok: false, error: 'Input schema must be a JSON object' };
      }
      inputSchema = parsed as Record<string, unknown>;
    } catch (e) {
      return { ok: false, error: `Input schema is not valid JSON: ${(e as Error).message}` };
    }
  }
  const int = (t: string) => (t.trim() === '' ? null : Number(t));
  const timeout = int(d.timeoutSeconds);
  const maxBytes = int(d.maxResponseBytes);
  if ((timeout != null && !Number.isInteger(timeout)) || (maxBytes != null && !Number.isInteger(maxBytes))) {
    return { ok: false, error: 'Limits must be whole numbers' };
  }
  return {
    ok: true,
    spec: {
      description: d.description.trim() === '' ? null : d.description,
      kind: 'http',
      connectionId: d.connectionId === '' ? null : d.connectionId,
      method: d.method,
      path: d.path,
      inputSchema,
      effect: d.effect as ToolSpec['effect'],
      timeoutSeconds: timeout,
      maxResponseBytes: maxBytes,
    },
  };
}

function Field({ label, hint, htmlFor, children }: { label: string; hint?: string; htmlFor?: string; children: ReactNode }) {
  return (
    <Box>
      <Text as="label" htmlFor={htmlFor} size="2" weight="medium" style={{ display: 'block', marginBottom: 4 }}>
        {label}
      </Text>
      {children}
      {hint && (
        <Text size="1" color="gray" as="p" mt="1">
          {hint}
        </Text>
      )}
    </Box>
  );
}

/**
 * One governed API tool (docs/phase-2-execution-spec.md slice 2.1): edit the draft, validate,
 * publish immutable versions, retire. Publishing needs an active HTTP_API connection granted to the
 * workspace; the credential stays with the connection. Descriptions are what the model reads - they
 * never grant anything.
 */
export default function ToolEditorPage() {
  const { ws = '', toolId = '' } = useParams();
  const queryClient = useQueryClient();
  const workspacesQuery = useWorkspaces();
  const workspace = useWorkspace(ws);
  const [tab, setTab] = useState<Tab>('editor');

  const toolKey = ['studio', ws, 'tool', toolId];
  const toolQuery = useQuery({ queryKey: toolKey, queryFn: () => studio.tool(ws, toolId) });
  const versionsQuery = useQuery({ queryKey: [...toolKey, 'versions'], queryFn: () => studio.toolVersions(ws, toolId) });
  const connectionsQuery = useQuery({ queryKey: ['studio', ws, 'connections'], queryFn: () => studio.connections(ws) });

  const tool = toolQuery.data;
  const [draft, setDraft] = useState<ToolDraft | null>(null);
  const [baseRevision, setBaseRevision] = useState<number | null>(null);
  const [conflict, setConflict] = useState<string | null>(null);
  const [findings, setFindings] = useState<{ valid: boolean; errors: string[]; hash: string | null } | null>(null);

  useEffect(() => {
    if (tool && baseRevision == null) {
      setDraft(toDraft(tool.draftName, tool.draftSpec));
      setBaseRevision(tool.draftRevision);
    }
  }, [tool, baseRevision]);

  const specResult = draft ? toSpec(draft) : null;
  const serverText = tool ? JSON.stringify(toDraft(tool.draftName, tool.draftSpec)) : '';
  const dirty = !!tool && !!draft && (JSON.stringify(draft) !== serverText || baseRevision !== tool.draftRevision);
  const canEdit = can(workspace, 'AUTHOR', 'WORKSPACE_ADMIN') && tool?.status === 'ACTIVE';
  const canAdmin = can(workspace, 'WORKSPACE_ADMIN') && tool?.status === 'ACTIVE';

  function applyServer(t: ToolDefinition) {
    queryClient.setQueryData(toolKey, t);
    setDraft(toDraft(t.draftName, t.draftSpec));
    setBaseRevision(t.draftRevision);
    setConflict(null);
  }

  const save = useMutation({
    mutationFn: () => {
      if (!draft || !specResult?.ok || baseRevision == null) throw new Error('Nothing to save');
      return studio.saveToolDraft(ws, toolId, { name: draft.name, spec: specResult.spec, revision: baseRevision });
    },
    onSuccess: (t) => {
      applyServer(t);
      setFindings(null);
      toast.success(`Draft saved (revision ${t.draftRevision})`);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) setConflict(e.friendly);
      else toast.error(errorMessage(e));
    },
  });
  const validate = useMutation({
    mutationFn: () => studio.validateTool(ws, toolId),
    onSuccess: (v) => setFindings({ valid: v.valid, errors: v.errors, hash: v.contentHash }),
    onError: (e) => toast.error(errorMessage(e)),
  });
  const publish = useMutation({
    mutationFn: () => studio.publishTool(ws, toolId, tool!.draftRevision),
    onSuccess: (v) => {
      void queryClient.invalidateQueries({ queryKey: ['studio', ws] });
      setFindings(null);
      toast.success(`Published v${v.version}`);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 400) setFindings({ valid: false, errors: e.friendly.split('; '), hash: null });
      else if (e instanceof ApiError && e.status === 409 && e.friendly.includes('revision')) setConflict(e.friendly);
      else toast.error(errorMessage(e));
    },
  });
  const retire = useMutation({
    mutationFn: () => studio.retireTool(ws, toolId),
    onSuccess: (t) => {
      queryClient.setQueryData(toolKey, t);
      void queryClient.invalidateQueries({ queryKey: ['studio', ws, 'tools'] });
      toast.success('Tool retired');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  if (toolQuery.isLoading || workspacesQuery.isLoading) {
    return <Skeleton height="420px" />;
  }
  if (toolQuery.isError || !tool || !draft) {
    return <ErrorCallout title="Failed to load tool" error={toolQuery.error ?? 'not found'} />;
  }

  const set = <K extends keyof ToolDraft>(key: K, value: ToolDraft[K]) => setDraft({ ...draft, [key]: value });
  const connections = connectionsQuery.data ?? [];
  const versions = versionsQuery.data ?? [];
  const readOnly = !canEdit;

  const inspector = (
    <Flex direction="column" gap="3">
      <Text size="2" weight="bold">
        Status
      </Text>
      <Flex gap="2" wrap="wrap">
        <span className={`pill ${statusVariant(tool.status)}`}>{tool.status}</span>
        <span className="meta-tag">{tool.currentVersion != null ? `agents pin v${tool.currentVersion}` : 'unpublished'}</span>
        <span className="meta-tag">draft rev {tool.draftRevision}</span>
      </Flex>
      {dirty && (
        <Text size="1" color="amber">
          Unsaved changes — save before publishing.
        </Text>
      )}
      <Flex direction="column" gap="2">
        <Button variant="soft" onClick={() => validate.mutate()} loading={validate.isPending} disabled={!canEdit && !canAdmin}>
          <CheckCircle2 size={14} /> Validate saved draft
        </Button>
        <Button onClick={() => publish.mutate()} loading={publish.isPending} disabled={!canAdmin || dirty}>
          <Send size={14} /> Publish v{(tool.latestVersion ?? 0) + 1}
        </Button>
        {!can(workspace, 'WORKSPACE_ADMIN') && (
          <Text size="1" color="gray">
            Publishing needs WORKSPACE_ADMIN.
          </Text>
        )}
      </Flex>
      {findings && (
        <Callout.Root color={findings.valid ? 'green' : 'red'} size="1">
          <Callout.Text>
            {findings.valid ? (
              <>Publishable. It would pin {findings.hash && <CopyHash hash={findings.hash.replace(/^sha256:/, '')} />}</>
            ) : (
              findings.errors.map((f) => (
                <span key={f} style={{ display: 'block' }}>
                  • {f}
                </span>
              ))
            )}
          </Callout.Text>
        </Callout.Root>
      )}
      {canAdmin && (
        <AlertDialog.Root>
          <AlertDialog.Trigger>
            <Button variant="ghost" color="red">
              Retire tool
            </Button>
          </AlertDialog.Trigger>
          <AlertDialog.Content maxWidth="420px">
            <AlertDialog.Title>Retire {tool.draftName}?</AlertDialog.Title>
            <AlertDialog.Description size="2">
              Agents pinning it stop starting new runs, and running agents are denied their next call. Versions stay readable.
            </AlertDialog.Description>
            <Flex gap="2" mt="4" justify="end">
              <AlertDialog.Cancel>
                <Button variant="soft" color="gray">
                  Cancel
                </Button>
              </AlertDialog.Cancel>
              <AlertDialog.Action>
                <Button color="red" onClick={() => retire.mutate()}>
                  Retire
                </Button>
              </AlertDialog.Action>
            </Flex>
          </AlertDialog.Content>
        </AlertDialog.Root>
      )}
    </Flex>
  );

  return (
    <Box>
      <PageHeader
        title={tool.draftName}
        subtitle={draft.description || undefined}
        badges={<span className="pill violet">TOOL</span>}
        meta={
          <MetaItems
            items={[
              <Link key="ws" to="/studio?view=tools">
                {workspace?.name ?? ws}
              </Link>,
              tool.id,
              `updated by ${tool.updatedBy}`,
            ]}
          />
        }
        actions={
          tab === 'editor' && canEdit ? (
            <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!dirty || !specResult?.ok || conflict != null}>
              <Save size={14} /> Save draft
            </Button>
          ) : undefined
        }
      />
      {conflict && (
        <Callout.Root color="amber" mb="3">
          <Callout.Icon>
            <AlertTriangle size={15} />
          </Callout.Icon>
          <Callout.Text>
            {conflict}. Your edits are still in the form; loading the latest version replaces them.{' '}
            <Button size="1" variant="soft" color="amber" onClick={() => toolQuery.refetch().then((r) => r.data && applyServer(r.data))}>
              Load latest (discard my edits)
            </Button>
          </Callout.Text>
        </Callout.Root>
      )}
      {specResult && !specResult.ok && (
        <Callout.Root color="red" mb="3">
          <Callout.Text>{specResult.error}</Callout.Text>
        </Callout.Root>
      )}
      <Surface
        tabs={[
          { value: 'editor', label: 'Editor' },
          { value: 'versions', label: 'Versions', count: versions.length },
        ]}
        value={tab}
        onValueChange={(v) => setTab(v as Tab)}
        inspector={inspector}
      >
        {tab === 'editor' && (
          <Flex direction="column" gap="4">
            <Flex gap="3" wrap="wrap">
              <Box style={{ flex: '1 1 240px' }}>
                <Field label="Name" htmlFor="tool-name">
                  <TextField.Root id="tool-name" value={draft.name} disabled={readOnly} onChange={(e) => set('name', e.target.value)} />
                </Field>
              </Box>
              <Box style={{ flex: '2 1 320px' }}>
                <Field label="Description (shown to the model)" htmlFor="tool-description">
                  <TextField.Root
                    id="tool-description"
                    value={draft.description}
                    disabled={readOnly}
                    placeholder="Look up an order by id"
                    onChange={(e) => set('description', e.target.value)}
                  />
                </Field>
              </Box>
            </Flex>
            <Flex gap="3" wrap="wrap">
              <Box style={{ flex: '1 1 220px' }}>
                <Field label="Connection" hint="HTTP_API connections granted to this workspace. The credential never leaves the connection.">
                  <Select.Root value={draft.connectionId || undefined} disabled={readOnly} onValueChange={(v) => set('connectionId', v)}>
                    <Select.Trigger aria-label="Connection" placeholder="Choose a connection" style={{ width: '100%' }} />
                    <Select.Content>
                      {connections.map((c) => (
                        <Select.Item key={c.id} value={c.id} disabled={c.status !== 'ACTIVE'}>
                          {c.id} — {c.baseUrl}
                        </Select.Item>
                      ))}
                    </Select.Content>
                  </Select.Root>
                </Field>
              </Box>
              <Box style={{ flex: '0 1 140px' }}>
                <Field label="Method">
                  <Select.Root value={draft.method} disabled={readOnly} onValueChange={(v) => set('method', v)}>
                    <Select.Trigger aria-label="Method" style={{ width: '100%' }} />
                    <Select.Content>
                      {METHODS.map((m) => (
                        <Select.Item key={m} value={m}>
                          {m}
                        </Select.Item>
                      ))}
                    </Select.Content>
                  </Select.Root>
                </Field>
              </Box>
              <Box style={{ flex: '0 1 160px' }}>
                <Field label="Effect" hint="Only GET is READ. WRITE tools are refused until approvals (slice 2.2).">
                  <Select.Root value={draft.effect} disabled={readOnly} onValueChange={(v) => set('effect', v)}>
                    <Select.Trigger aria-label="Effect" style={{ width: '100%' }} />
                    <Select.Content>
                      <Select.Item value="READ">READ</Select.Item>
                      <Select.Item value="WRITE">WRITE</Select.Item>
                    </Select.Content>
                  </Select.Root>
                </Field>
              </Box>
            </Flex>
            <Field
              label="Path"
              htmlFor="tool-path"
              hint="Appended to the connection's base URL. {name} segments come from arguments and are percent-encoded; other GET arguments go in the query."
            >
              <TextField.Root
                id="tool-path"
                value={draft.path}
                disabled={readOnly}
                placeholder="/orders/{orderId}"
                onChange={(e) => set('path', e.target.value)}
              />
            </Field>
            <Flex gap="3" wrap="wrap">
              <Box style={{ flex: '1 1 180px' }}>
                <Field label="Timeout (seconds)" htmlFor="tool-timeout" hint="1–120, capped by the run's deadline.">
                  <TextField.Root
                    id="tool-timeout"
                    inputMode="numeric"
                    value={draft.timeoutSeconds}
                    disabled={readOnly}
                    onChange={(e) => set('timeoutSeconds', e.target.value)}
                  />
                </Field>
              </Box>
              <Box style={{ flex: '1 1 180px' }}>
                <Field label="Max response bytes" htmlFor="tool-max-bytes" hint="256–1000000. Longer bodies are cut and flagged.">
                  <TextField.Root
                    id="tool-max-bytes"
                    inputMode="numeric"
                    value={draft.maxResponseBytes}
                    disabled={readOnly}
                    onChange={(e) => set('maxResponseBytes', e.target.value)}
                  />
                </Field>
              </Box>
            </Flex>
            <Field
              label="Input schema (JSON)"
              htmlFor="tool-schema"
              hint="type: object. Declare every path parameter. Supported keywords: type, properties, required, items, enum, description. Undeclared arguments are refused."
            >
              <TextArea
                id="tool-schema"
                value={draft.inputSchema}
                disabled={readOnly}
                rows={8}
                style={{ fontFamily: 'var(--code-font-family, monospace)' }}
                onChange={(e) => set('inputSchema', e.target.value)}
              />
            </Field>
          </Flex>
        )}
        {tab === 'versions' &&
          (versionsQuery.isError ? (
            <ErrorCallout title="Failed to load versions" error={versionsQuery.error} />
          ) : versions.length === 0 ? (
            <Text size="2" color="gray">
              No published versions yet.
            </Text>
          ) : (
            <Box style={{ overflowX: 'auto' }}>
              <Table.Root variant="surface" size="1">
                <Table.Header>
                  <Table.Row>
                    <Table.ColumnHeaderCell>Version</Table.ColumnHeaderCell>
                    <Table.ColumnHeaderCell>Operation</Table.ColumnHeaderCell>
                    <Table.ColumnHeaderCell>Hash</Table.ColumnHeaderCell>
                    <Table.ColumnHeaderCell>Published</Table.ColumnHeaderCell>
                  </Table.Row>
                </Table.Header>
                <Table.Body>
                  {[...versions].reverse().map((v) => (
                    <Table.Row key={v.version}>
                      <Table.Cell>
                        v{v.version}
                        {v.version === tool.currentVersion ? <span className="meta-tag" style={{ marginLeft: 6 }}>current</span> : null}
                      </Table.Cell>
                      <Table.Cell>
                        <code style={{ fontSize: 12 }}>
                          {v.spec.method} {v.spec.connectionId}
                          {v.spec.path} · {v.spec.effect}
                        </code>
                      </Table.Cell>
                      <Table.Cell>
                        <CopyHash hash={v.contentHash.replace(/^sha256:/, '')} />
                      </Table.Cell>
                      <Table.Cell>
                        <RelativeTime iso={v.publishedAt} /> <Text size="1" color="gray">by {v.publishedBy}</Text>
                      </Table.Cell>
                    </Table.Row>
                  ))}
                </Table.Body>
              </Table.Root>
            </Box>
          ))}
      </Surface>
    </Box>
  );
}
