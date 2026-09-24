import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bot, Boxes, Plus } from 'lucide-react';
import { Box, Button, Callout, Dialog, Flex, Select, Skeleton, Table, Text, TextField } from '@radix-ui/themes';
import { toast } from 'sonner';
import { errorMessage, studio } from '../api';
import { useIdentity } from '../identity';
import PageHeader from '../components/PageHeader';
import ErrorCallout from '../components/ErrorCallout';
import EmptyState from '../components/EmptyState';
import RelativeTime from '../components/RelativeTime';
import type { AgentSpec, Workspace } from '../types';
import { can, setSelectedWorkspace, statusVariant, useSelectedWorkspaceId, useWorkspaces } from './workspace';

const STARTER_SPEC: AgentSpec = {
  description: null,
  runtime: 'native',
  prompt: '',
  variables: [],
  model: { model: null, fallbacks: [] },
  limits: { timeoutSeconds: 60, maxOutputTokens: null },
  outputSchema: null,
};

/**
 * Agent Studio home (docs/phase-1-execution-spec.md slice 5): pick a workspace, see its agents,
 * create one. Only workspaces the caller belongs to are listed; the enterprise Admin can also
 * create workspaces.
 */
export default function StudioPage() {
  const identity = useIdentity();
  const workspacesQuery = useWorkspaces();
  const workspaces = workspacesQuery.data ?? [];
  const storedId = useSelectedWorkspaceId();
  const selected = workspaces.find((w) => w.id === storedId) ?? workspaces.find((w) => w.capabilities.length > 0);

  useEffect(() => {
    if (selected && selected.id !== storedId) setSelectedWorkspace(selected.id);
  }, [selected, storedId]);

  const header = (
    <PageHeader
      title="Agent Studio"
      subtitle="Configure, version and test agents without code changes or restarts."
      badges={<span className="pill violet">PLATFORM</span>}
      actions={
        <Flex gap="2" align="center" wrap="wrap">
          {workspaces.length > 0 && (
            <Select.Root value={selected?.id} onValueChange={setSelectedWorkspace}>
              <Select.Trigger aria-label="Workspace" placeholder="Workspace" />
              <Select.Content>
                {workspaces.map((w) => (
                  <Select.Item key={w.id} value={w.id} disabled={w.capabilities.length === 0}>
                    {w.name}
                    {w.capabilities.length === 0 ? ' (not a member)' : ''}
                  </Select.Item>
                ))}
              </Select.Content>
            </Select.Root>
          )}
          {identity.role === 'Admin' && <NewWorkspaceDialog />}
        </Flex>
      }
    />
  );

  if (workspacesQuery.isLoading) {
    return (
      <Box>
        {header}
        <Skeleton height="240px" />
      </Box>
    );
  }
  if (workspacesQuery.isError) {
    return (
      <Box>
        {header}
        <ErrorCallout title="Failed to load workspaces" error={workspacesQuery.error} />
      </Box>
    );
  }
  if (!selected) {
    return (
      <Box>
        {header}
        <EmptyState
          icon={<Boxes size={28} />}
          title="No workspace yet"
          hint={
            identity.role === 'Admin'
              ? 'Create a workspace and name its administrators.'
              : 'Ask an administrator to add you to a workspace.'
          }
        />
      </Box>
    );
  }
  return (
    <Box>
      {header}
      <AgentList workspace={selected} />
    </Box>
  );
}

function AgentList({ workspace }: { workspace: Workspace }) {
  const agentsQuery = useQuery({ queryKey: ['studio', workspace.id, 'agents'], queryFn: () => studio.agents(workspace.id) });
  const projectsQuery = useQuery({ queryKey: ['studio', workspace.id, 'projects'], queryFn: () => studio.projects(workspace.id) });
  const canAuthor = can(workspace, 'AUTHOR', 'WORKSPACE_ADMIN');

  return (
    <Box>
      <Flex justify="between" align="center" mb="3" gap="3" wrap="wrap">
        <Text size="2" color="gray">
          Your capabilities in {workspace.name}:{' '}
          {workspace.capabilities.map((c) => (
            <span key={c} className="meta-tag" style={{ marginRight: 4 }}>
              {c}
            </span>
          ))}
        </Text>
        {canAuthor && <NewAgentDialog workspaceId={workspace.id} />}
      </Flex>
      {(projectsQuery.data ?? []).length > 0 && (
        <Text size="2" color="gray" as="p" mb="3">
          Serves projects:{' '}
          {(projectsQuery.data ?? []).map((p) => (
            <Link key={p.id} to={`/projects?id=${encodeURIComponent(p.id)}`} className="meta-tag" style={{ marginRight: 4 }}>
              {p.name}
            </Link>
          ))}
        </Text>
      )}
      {agentsQuery.isLoading ? (
        <Skeleton height="160px" />
      ) : agentsQuery.isError ? (
        <ErrorCallout title="Failed to load agents" error={agentsQuery.error} />
      ) : (agentsQuery.data ?? []).length === 0 ? (
        <EmptyState
          icon={<Bot size={28} />}
          title="No agents in this workspace"
          hint={canAuthor ? 'Create an agent, write its prompt, then publish a version.' : 'Authors create agents here.'}
        />
      ) : (
        <Box style={{ overflowX: 'auto' }}>
          <Table.Root variant="surface">
            <Table.Header>
              <Table.Row>
                <Table.ColumnHeaderCell>Agent</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Status</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Runs use</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Latest</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Draft</Table.ColumnHeaderCell>
                <Table.ColumnHeaderCell>Updated</Table.ColumnHeaderCell>
              </Table.Row>
            </Table.Header>
            <Table.Body>
              {(agentsQuery.data ?? []).map((a) => (
                <Table.Row key={a.id}>
                  <Table.RowHeaderCell>
                    <Link to={`/studio/${encodeURIComponent(workspace.id)}/agents/${encodeURIComponent(a.id)}`}>
                      <Text weight="bold">{a.draftName}</Text>
                    </Link>
                    <Text size="1" color="gray" as="div">
                      {a.id}
                    </Text>
                  </Table.RowHeaderCell>
                  <Table.Cell>
                    <span className={`pill ${statusVariant(a.status)}`}>{a.status}</span>
                  </Table.Cell>
                  <Table.Cell>{a.currentVersion != null ? `v${a.currentVersion}` : <Text color="gray">unpublished</Text>}</Table.Cell>
                  <Table.Cell>{a.latestVersion != null ? `v${a.latestVersion}` : '—'}</Table.Cell>
                  <Table.Cell>rev {a.draftRevision}</Table.Cell>
                  <Table.Cell>
                    <RelativeTime iso={a.updatedAt} /> <Text size="1" color="gray">by {a.updatedBy}</Text>
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

function NewAgentDialog({ workspaceId }: { workspaceId: string }) {
  const [open, setOpen] = useState(false);
  const [id, setId] = useState('');
  const [name, setName] = useState('');
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const create = useMutation({
    mutationFn: () => studio.createAgent(workspaceId, { id, name, spec: STARTER_SPEC }),
    onSuccess: (a) => {
      void queryClient.invalidateQueries({ queryKey: ['studio', workspaceId, 'agents'] });
      setOpen(false);
      navigate(`/studio/${encodeURIComponent(workspaceId)}/agents/${encodeURIComponent(a.id)}`);
    },
  });
  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger>
        <Button>
          <Plus size={14} /> New agent
        </Button>
      </Dialog.Trigger>
      <Dialog.Content maxWidth="440px">
        <Dialog.Title>New agent</Dialog.Title>
        <Dialog.Description size="2" color="gray" mb="3">
          Creates a draft. Nothing runs until a version is published.
        </Dialog.Description>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            create.mutate();
          }}
        >
          <Flex direction="column" gap="3">
            <label>
              <Text size="1" color="gray">Id (lowercase letters, digits, dashes)</Text>
              <TextField.Root value={id} onChange={(e) => setId(e.target.value)} placeholder="release-notes-writer" required />
            </label>
            <label>
              <Text size="1" color="gray">Name</Text>
              <TextField.Root value={name} onChange={(e) => setName(e.target.value)} placeholder="Release notes writer" required />
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
              <Button type="submit" loading={create.isPending}>
                Create draft
              </Button>
            </Flex>
          </Flex>
        </form>
      </Dialog.Content>
    </Dialog.Root>
  );
}

function NewWorkspaceDialog() {
  const [open, setOpen] = useState(false);
  const [id, setId] = useState('');
  const [name, setName] = useState('');
  const [admins, setAdmins] = useState('');
  const queryClient = useQueryClient();
  const create = useMutation({
    mutationFn: () =>
      studio.createWorkspace({
        id,
        name,
        admins: admins
          .split(',')
          .map((a) => a.trim())
          .filter(Boolean),
      }),
    onSuccess: (w) => {
      void queryClient.invalidateQueries({ queryKey: ['workspaces'] });
      toast.success(`Workspace ${w.name} created`);
      setOpen(false);
    },
  });
  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger>
        <Button variant="soft">
          <Plus size={14} /> New workspace
        </Button>
      </Dialog.Trigger>
      <Dialog.Content maxWidth="440px">
        <Dialog.Title>New workspace</Dialog.Title>
        <Dialog.Description size="2" color="gray" mb="3">
          Creating a workspace does not make you a member; name its administrators.
        </Dialog.Description>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            create.mutate();
          }}
        >
          <Flex direction="column" gap="3">
            <label>
              <Text size="1" color="gray">Id</Text>
              <TextField.Root value={id} onChange={(e) => setId(e.target.value)} placeholder="engineering" required />
            </label>
            <label>
              <Text size="1" color="gray">Name</Text>
              <TextField.Root value={name} onChange={(e) => setName(e.target.value)} placeholder="Engineering" required />
            </label>
            <label>
              <Text size="1" color="gray">Workspace admins (comma-separated user ids)</Text>
              <TextField.Root value={admins} onChange={(e) => setAdmins(e.target.value)} placeholder="lead@acme" required />
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
              <Button type="submit" loading={create.isPending}>
                Create workspace
              </Button>
            </Flex>
          </Flex>
        </form>
      </Dialog.Content>
    </Dialog.Root>
  );
}
