import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertDialog, Box, Button, Callout, Flex, Skeleton, Text } from '@radix-ui/themes';
import { AlertTriangle, CheckCircle2, Save, Send } from 'lucide-react';
import { toast } from 'sonner';
import { ApiError, errorMessage, studio } from '../api';
import type { AgentDefinition } from '../types';
import PageHeader, { MetaItems } from '../components/PageHeader';
import Surface from '../components/Surface';
import ErrorCallout from '../components/ErrorCallout';
import CopyHash from '../components/CopyHash';
import AgentForm from './AgentForm';
import VersionsPanel from './VersionsPanel';
import RunsPanel from './RunsPanel';
import { describe, toDraft, toSpec, type Draft } from './draft';
import { can, statusVariant, useWorkspace, useWorkspaces } from './workspace';

type Tab = 'editor' | 'versions' | 'runs';

/**
 * One agent in the Studio: edit the draft, validate, publish immutable versions, compare and roll
 * back, retire, and test-run the version new runs use. Every write carries the draft revision it
 * was based on; a 409 means someone else saved first, and the author chooses to reload.
 */
export default function AgentEditorPage() {
  const { ws = '', agentId = '' } = useParams();
  const queryClient = useQueryClient();
  const workspacesQuery = useWorkspaces();
  const workspace = useWorkspace(ws);
  const [tab, setTab] = useState<Tab>('editor');

  const agentKey = ['studio', ws, 'agent', agentId];
  const agentQuery = useQuery({ queryKey: agentKey, queryFn: () => studio.agent(ws, agentId) });
  const versionsQuery = useQuery({ queryKey: [...agentKey, 'versions'], queryFn: () => studio.versions(ws, agentId) });
  const modelsQuery = useQuery({ queryKey: ['platform', 'models'], queryFn: studio.models });
  const toolsQuery = useQuery({ queryKey: ['studio', ws, 'tools'], queryFn: () => studio.tools(ws) });
  const connectionsQuery = useQuery({ queryKey: ['studio', ws, 'connections'], queryFn: () => studio.connections(ws) });

  const agent = agentQuery.data;
  const [draft, setDraft] = useState<Draft | null>(null);
  const [baseRevision, setBaseRevision] = useState<number | null>(null);
  const [conflict, setConflict] = useState<string | null>(null);
  const [findings, setFindings] = useState<{ valid: boolean; errors: string[]; hash: string | null } | null>(null);

  // Load the server draft into the form on first load and after an explicit reload - never while
  // the author has local edits, so polling or refetches can't overwrite their work.
  useEffect(() => {
    if (agent && baseRevision == null) {
      setDraft(toDraft(agent.draftName, agent.draftSpec));
      setBaseRevision(agent.draftRevision);
    }
  }, [agent, baseRevision]);

  const specResult = draft ? toSpec(draft) : null;
  const serverText = agent ? describe(agent.draftName, agent.draftSpec) : '';
  const draftText = draft && specResult?.ok ? describe(draft.name, specResult.spec) : serverText;
  const dirty = !!agent && !!draft && (!specResult?.ok || draftText !== serverText || baseRevision !== agent.draftRevision);

  const canEdit = can(workspace, 'AUTHOR', 'WORKSPACE_ADMIN') && agent?.status === 'ACTIVE';
  const canAdmin = can(workspace, 'WORKSPACE_ADMIN') && agent?.status === 'ACTIVE';
  const canRun = can(workspace, 'OPERATOR');

  function applyServer(a: AgentDefinition) {
    queryClient.setQueryData(agentKey, a);
    setDraft(toDraft(a.draftName, a.draftSpec));
    setBaseRevision(a.draftRevision);
    setConflict(null);
  }

  const save = useMutation({
    mutationFn: () => {
      if (!draft || !specResult?.ok || baseRevision == null) throw new Error('Nothing to save');
      return studio.saveDraft(ws, agentId, { name: draft.name, spec: specResult.spec, revision: baseRevision });
    },
    onSuccess: (a) => {
      applyServer(a);
      setFindings(null);
      toast.success(`Draft saved (revision ${a.draftRevision})`);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) setConflict(e.friendly);
      else toast.error(errorMessage(e));
    },
  });

  const validate = useMutation({
    mutationFn: () => studio.validate(ws, agentId),
    onSuccess: (v) => setFindings({ valid: v.valid, errors: v.errors, hash: v.contentHash }),
    onError: (e) => toast.error(errorMessage(e)),
  });

  const publish = useMutation({
    mutationFn: () => studio.publish(ws, agentId, agent!.draftRevision),
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

  const rollback = useMutation({
    mutationFn: (version: number) => studio.rollback(ws, agentId, version),
    onSuccess: (a) => {
      queryClient.setQueryData(agentKey, a);
      void queryClient.invalidateQueries({ queryKey: ['studio', ws, 'agents'] });
      toast.success(`New runs now use v${a.currentVersion}`);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const retire = useMutation({
    mutationFn: () => studio.retire(ws, agentId),
    onSuccess: (a) => {
      queryClient.setQueryData(agentKey, a);
      void queryClient.invalidateQueries({ queryKey: ['studio', ws, 'agents'] });
      toast.success('Agent retired');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const versions = versionsQuery.data ?? [];
  const current = useMemo(() => versions.find((v) => v.version === agent?.currentVersion), [versions, agent?.currentVersion]);

  if (agentQuery.isLoading || workspacesQuery.isLoading) {
    return <Skeleton height="420px" />;
  }
  if (agent && !draft && !agentQuery.isError) {
    // Loaded, but the form is filled from it by an effect on the next render.
    return <Skeleton height="420px" />;
  }
  if (agentQuery.isError || !agent || !draft) {
    return <ErrorCallout title="Failed to load agent" error={agentQuery.error ?? 'not found'} />;
  }

  const inspector = (
    <Flex direction="column" gap="3">
      <Text size="2" weight="bold">
        Status
      </Text>
      <Flex gap="2" wrap="wrap">
        <span className={`pill ${statusVariant(agent.status)}`}>{agent.status}</span>
        <span className="meta-tag">{agent.currentVersion != null ? `runs use v${agent.currentVersion}` : 'unpublished'}</span>
        <span className="meta-tag">draft rev {agent.draftRevision}</span>
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
          <Send size={14} /> Publish v{(agent.latestVersion ?? 0) + 1}
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
              <>
                Publishable. It would pin {findings.hash && <CopyHash hash={findings.hash.replace(/^sha256:/, '')} />}
              </>
            ) : (
              <>
                {findings.errors.map((f) => (
                  <span key={f} style={{ display: 'block' }}>
                    • {f}
                  </span>
                ))}
              </>
            )}
          </Callout.Text>
        </Callout.Root>
      )}
      {canAdmin && (
        <AlertDialog.Root>
          <AlertDialog.Trigger>
            <Button variant="ghost" color="red">
              Retire agent
            </Button>
          </AlertDialog.Trigger>
          <AlertDialog.Content maxWidth="420px">
            <AlertDialog.Title>Retire {agent.draftName}?</AlertDialog.Title>
            <AlertDialog.Description size="2">
              New runs and edits stop. Published versions stay readable for provenance. This cannot be undone here.
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
        title={agent.draftName}
        subtitle={draft.description || undefined}
        badges={<span className="pill violet">AGENT</span>}
        meta={
          <MetaItems
            items={[
              <Link key="ws" to="/studio">
                {workspace?.name ?? ws}
              </Link>,
              agent.id,
              `updated by ${agent.updatedBy}`,
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
            {conflict}. Someone else changed this draft. Your edits are still in the form; loading the latest version
            replaces them.{' '}
            <Button size="1" variant="soft" color="amber" onClick={() => agentQuery.refetch().then((r) => r.data && applyServer(r.data))}>
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
      {modelsQuery.isError && <ErrorCallout title="Failed to load the model catalog" error={modelsQuery.error} />}

      <Surface
        tabs={[
          { value: 'editor', label: 'Editor' },
          { value: 'versions', label: 'Versions', count: versions.length },
          { value: 'runs', label: 'Test runs' },
        ]}
        value={tab}
        onValueChange={(v) => setTab(v as Tab)}
        inspector={inspector}
      >
        {tab === 'editor' && (
          <AgentForm
            draft={draft}
            onChange={setDraft}
            models={modelsQuery.data ?? []}
            tools={toolsQuery.data ?? []}
            readOnly={!canEdit}
            workspaceId={ws}
            connections={connectionsQuery.data ?? []}
          />
        )}
        {tab === 'versions' && (
          versionsQuery.isError ? (
            <ErrorCallout title="Failed to load versions" error={versionsQuery.error} />
          ) : (
            <VersionsPanel
              versions={versions}
              currentVersion={agent.currentVersion}
              draftText={draftText}
              canRollback={canAdmin}
              onRollback={(v) => rollback.mutate(v)}
              rollingBack={rollback.isPending}
            />
          )
        )}
        {tab === 'runs' && (
          <RunsPanel workspaceId={ws} agentId={agentId} current={current} canRun={canRun} retired={agent.status === 'RETIRED'} />
        )}
      </Surface>
    </Box>
  );
}
