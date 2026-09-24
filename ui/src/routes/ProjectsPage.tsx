import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FolderKanban, Plus, Trash2 } from 'lucide-react';
import { Box, Button, Card, Checkbox, Flex, IconButton, RadioGroup, Select, Skeleton, Switch, Text, TextArea, TextField } from '@radix-ui/themes';
import { toast } from 'sonner';
import { api, errorMessage } from '../api';
import { useIdentity } from '../identity';
import type { DocLink, GateRoles, Project, ProjectRequest } from '../types';
import PageHeader from '../components/PageHeader';
import ErrorCallout from '../components/ErrorCallout';
import EmptyState from '../components/EmptyState';
import AgentSetupGuide from '../components/AgentSetupGuide';
import StatusBadge from '../components/StatusBadge';
import { useProjects } from '../useProject';
import { isChildOf } from '../ui-utils';

const GATE_IDS = ['G1', 'G2', 'G3', 'PLAN'] as const;
const ALL_ROLES = ['PO', 'SquadLead', 'FSDeveloper', 'QA', 'Admin'];
const BOARD_PROVIDERS = ['azure-devops', 'local-jdbc', 'in-memory'];
const REPO_PROVIDERS = ['github', 'local-git', 'in-memory'];

interface BoardDraft {
  provider: string;
  org: string;
  project: string;
  authKind: string;
  authSecretRef: string;
  types: string;
  states: string;
}

interface RepoDraft {
  id: string;
  provider: string;
  url: string;
  defaultBranch: string;
  specDir: string;
  areas: string;
  primary: boolean;
}

interface Draft {
  id: string;
  name: string;
  confluenceUrl: string;
  brief: string;
  docs: DocLink[];
  board: BoardDraft;
  repos: RepoDraft[];
  gates: Record<string, GateRoles>;
  acpAgent: string;
}

function recordToLines(rec: Record<string, string>): string {
  return Object.entries(rec ?? {})
    .map(([k, v]) => `${k}=${v}`)
    .join('\n');
}

function linesToRecord(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const eq = trimmed.indexOf('=');
    if (eq === -1) continue;
    const key = trimmed.slice(0, eq).trim();
    const value = trimmed.slice(eq + 1).trim();
    if (key) out[key] = value;
  }
  return out;
}

function splitAreas(text: string): string[] {
  return text
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

function gateToDraft(gate: GateRoles | undefined): GateRoles {
  return { roles: gate?.roles ?? [], sod: gate?.sod ?? false };
}

function projectToDraft(p: Project): Draft {
  return {
    id: p.id,
    name: p.name ?? '',
    confluenceUrl: p.confluenceUrl ?? '',
    brief: p.brief ?? '',
    docs: (p.docs ?? []).map((d) => ({ title: d.title, url: d.url })),
    board: {
      provider: p.board?.provider ?? 'in-memory',
      org: p.board?.org ?? '',
      project: p.board?.project ?? '',
      authKind: p.board?.authKind ?? '',
      authSecretRef: p.board?.authSecretRef ?? '',
      types: recordToLines(p.board?.types ?? {}),
      states: recordToLines(p.board?.states ?? {}),
    },
    repos: (p.repos ?? []).map((r) => ({
      id: r.id,
      provider: r.provider,
      url: r.url,
      defaultBranch: r.defaultBranch,
      specDir: r.specDir,
      areas: (r.areas ?? []).join(', '),
      primary: r.primary,
    })),
    gates: {
      G1: gateToDraft(p.gates?.G1),
      G2: gateToDraft(p.gates?.G2),
      G3: gateToDraft(p.gates?.G3),
      PLAN: gateToDraft(p.gates?.PLAN),
    },
    acpAgent: p.build?.acpAgent ?? 'omp acp',
  };
}

function blankDraft(): Draft {
  return {
    id: '',
    name: '',
    confluenceUrl: '',
    brief: '',
    docs: [],
    board: {
      provider: 'in-memory',
      org: '',
      project: '',
      authKind: '',
      authSecretRef: '',
      types: '',
      states: '',
    },
    repos: [
      { id: '', provider: 'github', url: '', defaultBranch: 'main', specDir: 'openspec', areas: '', primary: true },
    ],
    gates: {
      G1: { roles: ['PO', 'SquadLead'], sod: false },
      G2: { roles: ['FSDeveloper', 'QA'], sod: false },
      G3: { roles: ['PO', 'SquadLead', 'QA'], sod: false },
      PLAN: { roles: ['SquadLead'], sod: false },
    },
    acpAgent: 'omp acp',
  };
}

function draftToRequest(d: Draft): ProjectRequest {
  return {
    name: d.name,
    board: {
      provider: d.board.provider,
      org: d.board.org,
      project: d.board.project,
      authKind: d.board.authKind,
      authSecretRef: d.board.authSecretRef,
      types: linesToRecord(d.board.types),
      states: linesToRecord(d.board.states),
    },
    repos: d.repos.map((r) => ({
      id: r.id,
      provider: r.provider,
      url: r.url,
      defaultBranch: r.defaultBranch,
      specDir: r.specDir,
      areas: splitAreas(r.areas),
      primary: r.primary,
    })),
    confluenceUrl: d.confluenceUrl,
    docs: d.docs.filter((doc) => doc.title.trim() !== '' || doc.url.trim() !== ''),
    brief: d.brief,
    gates: {
      G1: d.gates.G1,
      G2: d.gates.G2,
      G3: d.gates.G3,
      PLAN: d.gates.PLAN,
    },
    build: { acpAgent: d.acpAgent },
  };
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box>
      <Text as="label" size="1" color="gray" style={{ display: 'block', marginBottom: 4 }}>
        {label}
      </Text>
      {children}
    </Box>
  );
}

/** Admin-managed project config: pick a project on the left, edit its board/repos/gates/docs on
 * the right. Read-only for every non-Admin role; an Admin can also create or delete a project. */
export default function ProjectsPage() {
  const identity = useIdentity();
  const isAdmin = identity.role === 'Admin';
  const queryClient = useQueryClient();
  const [params] = useSearchParams();

  const projectsQuery = useProjects();
  const projects = projectsQuery.data ?? [];

  const itemsQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    refetchInterval: 5000,
  });

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const initialized = useRef(false);

  useEffect(() => {
    if (initialized.current || projects.length === 0) return;
    const requested = params.get('id');
    const target = requested && projects.some((p) => p.id === requested) ? requested : projects[0].id;
    initialized.current = true;
    setSelectedId(target);
    setDraft(projectToDraft(projects.find((p) => p.id === target)!));
  }, [projects, params]);

  function selectProject(id: string) {
    const p = projects.find((x) => x.id === id);
    setSelectedId(id);
    setDraft(p ? projectToDraft(p) : null);
  }

  function newProject() {
    setSelectedId(null);
    setDraft(blankDraft());
  }

  const saveMutation = useMutation({
    mutationFn: (d: Draft) => api.saveProject(d.id, draftToRequest(d)),
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['projects'] });
      setSelectedId(saved.id);
      setDraft(projectToDraft(saved));
      toast.success('Project saved');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteProject(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['projects'] });
      setSelectedId(null);
      setDraft(null);
      toast.success('Project deleted');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const isNew = draft != null && selectedId == null;
  const selectedProject = projects.find((p) => p.id === selectedId);
  const features = useMemo(
    () => (itemsQuery.data ?? []).filter((i) => i.kind === 'feature' && i.profile === selectedProject?.id),
    [itemsQuery.data, selectedProject?.id],
  );
  const stories = useMemo(
    () => (itemsQuery.data ?? []).filter((i) => i.kind === 'story' && i.profile === selectedProject?.id),
    [itemsQuery.data, selectedProject?.id],
  );

  if (projectsQuery.isLoading) {
    return (
      <Box>
        <PageHeader title="Projects" subtitle="Board, repos, gates, and docs for each project." badges={<span className="pill">CONFIG</span>} />
        <Skeleton height="48px" mb="3" />
        <Skeleton height="320px" />
      </Box>
    );
  }

  if (projectsQuery.isError) {
    return (
      <Box>
        <PageHeader title="Projects" subtitle="Board, repos, gates, and docs for each project." badges={<span className="pill">CONFIG</span>} />
        <ErrorCallout title="Failed to load projects" error={projectsQuery.error} />
      </Box>
    );
  }

  return (
    <Box>
      <PageHeader
        title="Projects"
        subtitle="Board, repos, gates, and docs for each project. Editable by Admin only."
        badges={<span className="pill">CONFIG</span>}
      />

      <Flex gap="4" align="start">
        <Card style={{ width: 260, flexShrink: 0 }}>
          <Box p="3">
            <Text size="2" weight="bold" as="div" mb="2">
              Projects
            </Text>
            <Flex direction="column" gap="1">
              {projects.map((p) => (
                <Button
                  key={p.id}
                  variant={p.id === selectedId ? 'soft' : 'ghost'}
                  style={{ justifyContent: 'flex-start', fontWeight: p.id === selectedId ? 600 : 400 }}
                  onClick={() => selectProject(p.id)}
                >
                  {p.name}
                  <Text size="1" color="gray" ml="1">
                    ({p.id})
                  </Text>
                </Button>
              ))}
            </Flex>
            {isAdmin && (
              <Button mt="3" variant="soft" onClick={newProject} style={{ width: '100%' }}>
                <Plus size={14} /> New project
              </Button>
            )}
          </Box>
        </Card>

        <Box style={{ flex: 1, minWidth: 0 }}>
          {projects.length === 0 && draft == null && (
            <EmptyState
              icon={<FolderKanban size={28} />}
              title="No projects yet"
              hint="Projects are seeded from pdlc.yaml on startup; an Admin can also create one here."
              action={
                isAdmin ? (
                  <Button mt="3" onClick={newProject}>
                    <Plus size={14} /> New project
                  </Button>
                ) : undefined
              }
            />
          )}

          {draft && (
            <>
              {selectedProject != null && !isNew && (
                <Card mb="3">
                  <Box p="3">
                    <Flex align="center" justify="between" mb="2">
                      <Text size="2" weight="bold">
                        Work items
                      </Text>
                      <Link to={`/?project=${encodeURIComponent(selectedProject.id)}`}>Open in item list →</Link>
                    </Flex>
                    <Text size="1" color="gray" as="div" mb="2">
                      {features.length} features · {stories.length} stories
                    </Text>
                    {features.length === 0 ? (
                      <Text size="2" color="gray">
                        No features yet — they arrive when the board webhook creates one for this project.
                      </Text>
                    ) : (
                      <Flex direction="column" gap="1">
                        {features.map((f) => (
                          <Flex key={f.id} align="center" justify="between" gap="2">
                            <Link to={`/items/${encodeURIComponent(f.id)}`}>{f.title}</Link>
                            <Flex gap="2" align="center">
                              <Text size="1" color="gray">
                                {stories.filter((s) => isChildOf(s, f)).length} stories
                              </Text>
                              <StatusBadge state={f.canonicalState} />
                            </Flex>
                          </Flex>
                        ))}
                      </Flex>
                    )}
                  </Box>
                </Card>
              )}

              {!isAdmin && (
                <Text as="p" size="2" color="amber" mb="3">
                  Admin role required to edit — this form is read-only.
                </Text>
              )}

              <Flex direction="column" gap="3">
                <Card>
                  <Box p="3">
                    <Flex direction="column" gap="3">
                      <Field label="Project id (project code)">
                        <TextField.Root value={draft.id} onChange={(e) => setDraft({ ...draft, id: e.target.value })} disabled={!isAdmin || !isNew} placeholder="restaurant-runtime" />
                      </Field>
                      <Field label="Name">
                        <TextField.Root value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} disabled={!isAdmin} placeholder="Restaurant runtime" />
                      </Field>
                      <Field label="Confluence URL">
                        <TextField.Root value={draft.confluenceUrl} onChange={(e) => setDraft({ ...draft, confluenceUrl: e.target.value })} disabled={!isAdmin} placeholder="https://acme.atlassian.net/wiki/spaces/…" />
                      </Field>
                      <Field label="Brief">
                        <TextArea value={draft.brief} onChange={(e) => setDraft({ ...draft, brief: e.target.value })} disabled={!isAdmin} placeholder="One-paragraph project brief injected into agent prompts." rows={4} />
                      </Field>
                    </Flex>
                  </Box>
                </Card>

                <Card>
                  <Box p="3">
                    <Text size="2" weight="bold" as="div" mb="2">
                      Reference docs
                    </Text>
                    <Flex direction="column" gap="2">
                      {draft.docs.map((doc, i) => (
                        <Flex key={i} gap="2" align="center">
                          <TextField.Root value={doc.title} onChange={(e) => setDraft({ ...draft, docs: draft.docs.map((dd, j) => (j === i ? { ...dd, title: e.target.value } : dd)) })} disabled={!isAdmin} placeholder="Title" style={{ flex: 1 }} />
                          <TextField.Root value={doc.url} onChange={(e) => setDraft({ ...draft, docs: draft.docs.map((dd, j) => (j === i ? { ...dd, url: e.target.value } : dd)) })} disabled={!isAdmin} placeholder="https://…" style={{ flex: 2 }} />
                          <IconButton variant="ghost" color="red" aria-label="Remove doc" disabled={!isAdmin} onClick={() => setDraft({ ...draft, docs: draft.docs.filter((_, j) => j !== i) })}>
                            <Trash2 size={14} />
                          </IconButton>
                        </Flex>
                      ))}
                      <Button variant="soft" size="1" disabled={!isAdmin} onClick={() => setDraft({ ...draft, docs: [...draft.docs, { title: '', url: '' }] })}>
                        <Plus size={14} /> Add doc
                      </Button>
                    </Flex>
                  </Box>
                </Card>

                <Card>
                  <Box p="3">
                    <Text size="2" weight="bold" as="div" mb="2">
                      Board
                    </Text>
                    <Flex direction="column" gap="3">
                      <Field label="Provider">
                        <Select.Root value={draft.board.provider} onValueChange={(v) => setDraft({ ...draft, board: { ...draft.board, provider: v } })} disabled={!isAdmin}>
                          <Select.Trigger />
                          <Select.Content>
                            {BOARD_PROVIDERS.map((p) => (
                              <Select.Item key={p} value={p}>
                                {p}
                              </Select.Item>
                            ))}
                          </Select.Content>
                        </Select.Root>
                      </Field>
                      <Flex gap="3">
                        <Box style={{ flex: 1 }}>
                          <Field label="Org">
                            <TextField.Root value={draft.board.org} onChange={(e) => setDraft({ ...draft, board: { ...draft.board, org: e.target.value } })} disabled={!isAdmin} />
                          </Field>
                        </Box>
                        <Box style={{ flex: 1 }}>
                          <Field label="Project">
                            <TextField.Root value={draft.board.project} onChange={(e) => setDraft({ ...draft, board: { ...draft.board, project: e.target.value } })} disabled={!isAdmin} />
                          </Field>
                        </Box>
                      </Flex>
                      <Flex gap="3">
                        <Box style={{ flex: 1 }}>
                          <Field label="Auth kind">
                            <TextField.Root value={draft.board.authKind} onChange={(e) => setDraft({ ...draft, board: { ...draft.board, authKind: e.target.value } })} disabled={!isAdmin} />
                          </Field>
                        </Box>
                        <Box style={{ flex: 1 }}>
                          <Field label="Auth secret ref">
                            <TextField.Root value={draft.board.authSecretRef} onChange={(e) => setDraft({ ...draft, board: { ...draft.board, authSecretRef: e.target.value } })} disabled={!isAdmin} />
                          </Field>
                        </Box>
                      </Flex>
                      <Field label="Work item types (one key=value per line)">
                        <TextArea value={draft.board.types} onChange={(e) => setDraft({ ...draft, board: { ...draft.board, types: e.target.value } })} disabled={!isAdmin} placeholder={'Story=User Story\nBug=Bug'} rows={3} />
                      </Field>
                      <Field label="States (one key=value per line)">
                        <TextArea value={draft.board.states} onChange={(e) => setDraft({ ...draft, board: { ...draft.board, states: e.target.value } })} disabled={!isAdmin} placeholder={'new=New\nactive=Active'} rows={3} />
                      </Field>
                    </Flex>
                  </Box>
                </Card>

                <Card>
                  <Box p="3">
                    <Text size="2" weight="bold" as="div" mb="2">
                      Repositories
                    </Text>
                    <RadioGroup.Root
                      value={String(draft.repos.findIndex((r) => r.primary))}
                      onValueChange={(v) => setDraft({ ...draft, repos: draft.repos.map((r, i) => ({ ...r, primary: i === Number(v) })) })}
                      disabled={!isAdmin}
                    >
                      <Flex direction="column" gap="3">
                        {draft.repos.map((r, i) => (
                          <Card key={i} size="1">
                            <Box p="2">
                              <Flex direction="column" gap="2">
                                <Flex align="center" gap="2">
                                  <RadioGroup.Item value={String(i)} />
                                  <Text size="1" color="gray">
                                    Primary repository
                                  </Text>
                                  <Box style={{ flex: 1 }} />
                                  <IconButton variant="ghost" color="red" aria-label="Remove repo" disabled={!isAdmin} onClick={() => setDraft({ ...draft, repos: draft.repos.filter((_, j) => j !== i) })}>
                                    <Trash2 size={14} />
                                  </IconButton>
                                </Flex>
                                <Flex gap="2">
                                  <Box style={{ flex: 1 }}>
                                    <Field label="Id">
                                      <TextField.Root value={r.id} onChange={(e) => setDraft({ ...draft, repos: draft.repos.map((rr, j) => (j === i ? { ...rr, id: e.target.value } : rr)) })} disabled={!isAdmin} />
                                    </Field>
                                  </Box>
                                  <Box style={{ flex: 1 }}>
                                    <Field label="Provider">
                                      <Select.Root value={r.provider} onValueChange={(v) => setDraft({ ...draft, repos: draft.repos.map((rr, j) => (j === i ? { ...rr, provider: v } : rr)) })} disabled={!isAdmin}>
                                        <Select.Trigger />
                                        <Select.Content>
                                          {REPO_PROVIDERS.map((p) => (
                                            <Select.Item key={p} value={p}>
                                              {p}
                                            </Select.Item>
                                          ))}
                                        </Select.Content>
                                      </Select.Root>
                                    </Field>
                                  </Box>
                                </Flex>
                                <Field label="URL (or local path)">
                                  <TextField.Root value={r.url} onChange={(e) => setDraft({ ...draft, repos: draft.repos.map((rr, j) => (j === i ? { ...rr, url: e.target.value } : rr)) })} disabled={!isAdmin} />
                                </Field>
                                <Flex gap="2">
                                  <Box style={{ flex: 1 }}>
                                    <Field label="Default branch">
                                      <TextField.Root value={r.defaultBranch} onChange={(e) => setDraft({ ...draft, repos: draft.repos.map((rr, j) => (j === i ? { ...rr, defaultBranch: e.target.value } : rr)) })} disabled={!isAdmin} />
                                    </Field>
                                  </Box>
                                  <Box style={{ flex: 1 }}>
                                    <Field label="Spec dir">
                                      <TextField.Root value={r.specDir} onChange={(e) => setDraft({ ...draft, repos: draft.repos.map((rr, j) => (j === i ? { ...rr, specDir: e.target.value } : rr)) })} disabled={!isAdmin} />
                                    </Field>
                                  </Box>
                                </Flex>
                                <Field label="Areas (comma-separated)">
                                  <TextField.Root value={r.areas} onChange={(e) => setDraft({ ...draft, repos: draft.repos.map((rr, j) => (j === i ? { ...rr, areas: e.target.value } : rr)) })} disabled={!isAdmin} />
                                </Field>
                              </Flex>
                            </Box>
                          </Card>
                        ))}
                      </Flex>
                    </RadioGroup.Root>
                    <Button mt="3" variant="soft" size="1" disabled={!isAdmin} onClick={() => setDraft({ ...draft, repos: [...draft.repos, { id: '', provider: 'github', url: '', defaultBranch: 'main', specDir: 'openspec', areas: '', primary: draft.repos.length === 0 }] })}>
                      <Plus size={14} /> Add repo
                    </Button>
                  </Box>
                </Card>

                <Card>
                  <Box p="3">
                    <Text size="2" weight="bold" as="div" mb="2">
                      Gates
                    </Text>
                    <Flex direction="column" gap="3">
                      {GATE_IDS.map((gid) => (
                        <Card key={gid} size="1">
                          <Box p="2">
                            <Text size="2" weight="bold" as="div" mb="2">
                              {gid}
                            </Text>
                            <Flex gap="3" wrap="wrap" mb="2">
                              {ALL_ROLES.map((role) => (
                                <label key={role} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                  <Checkbox
                                    checked={draft.gates[gid].roles.includes(role)}
                                    onCheckedChange={(c) => setDraft({ ...draft, gates: { ...draft.gates, [gid]: { ...draft.gates[gid], roles: c === true ? [...draft.gates[gid].roles, role] : draft.gates[gid].roles.filter((r) => r !== role) } } })}
                                    disabled={!isAdmin}
                                  />
                                  <Text size="2">{role}</Text>
                                </label>
                              ))}
                            </Flex>
                            <Flex align="center" gap="2">
                              <Switch checked={draft.gates[gid].sod} onCheckedChange={(c) => setDraft({ ...draft, gates: { ...draft.gates, [gid]: { ...draft.gates[gid], sod: c === true } } })} disabled={!isAdmin} />
                              <Text size="1" color="gray">
                                Separation of duties
                              </Text>
                            </Flex>
                          </Box>
                        </Card>
                      ))}
                    </Flex>
                  </Box>
                </Card>

                <Card>
                  <Box p="3">
                    <Flex direction="column" gap="3">
                      <Field label="Build agent command (acpAgent)">
                        <TextField.Root value={draft.acpAgent} onChange={(e) => setDraft({ ...draft, acpAgent: e.target.value })} disabled={!isAdmin} />
                      </Field>
                    </Flex>
                  </Box>
                </Card>

                {isAdmin && (
                  <Flex gap="2">
                    <Button onClick={() => saveMutation.mutate(draft)} loading={saveMutation.isPending} disabled={draft.id.trim() === '' || draft.name.trim() === ''}>
                      Save
                    </Button>
                    {!isNew && (
                      <Button variant="outline" color="red" onClick={() => deleteMutation.mutate(draft.id)} loading={deleteMutation.isPending}>
                        <Trash2 size={14} /> Delete
                      </Button>
                    )}
                  </Flex>
                )}
              </Flex>

              {selectedProject && <AgentSetupGuide project={selectedProject} />}
            </>
          )}
        </Box>
      </Flex>
    </Box>
  );
}
