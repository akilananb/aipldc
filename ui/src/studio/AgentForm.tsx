import { useMemo, useState, type ReactNode } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { markdown as markdownLang } from '@codemirror/lang-markdown';
import { EditorView } from '@codemirror/view';
import { Box, Button, Callout, Checkbox, Flex, IconButton, SegmentedControl, Select, Text, TextArea, TextField } from '@radix-ui/themes';
import { Plus, RefreshCw, Trash2, Wand2 } from 'lucide-react';
import { useMutation } from '@tanstack/react-query';
import { errorMessage, studio } from '../api';
import { useAppearance } from '../theme';
import type { A2aCard, CatalogModel, ToolDefinition, WorkspaceConnection } from '../types';
import { referencedVariables, type Draft } from './draft';

interface Props {
  draft: Draft;
  onChange: (next: Draft) => void;
  models: CatalogModel[];
  /** The workspace's tools; only published, active ones can be pinned. */
  tools: ToolDefinition[];
  readOnly: boolean;
  workspaceId: string;
  /** Connections granted to the workspace; a2a agents bind an A2A_AGENT one. */
  connections: WorkspaceConnection[];
}

function Field({ label, hint, children, htmlFor }: { label: string; hint?: string; children: ReactNode; htmlFor?: string }) {
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
 * The agent definition form. Every control is a native form element (keyboard-reachable, labelled);
 * the prompt uses CodeMirror, which is also keyboard-operable. Unavailable catalog models are listed
 * but disabled, with the reason, so an author sees why a binding would not publish.
 */
export default function AgentForm({ draft, onChange, models, tools, readOnly, workspaceId, connections }: Props) {
  const appearance = useAppearance();
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => onChange({ ...draft, [key]: value });
  const extensions = useMemo(() => [markdownLang(), EditorView.lineWrapping], []);

  const declared = new Set(draft.variables.map((v) => v.name));
  const missing = referencedVariables(draft.prompt).filter((n) => !declared.has(n));
  const selectedModel = models.find((m) => m.id === draft.model);
  const pinnable = tools.filter((t) => t.status === 'ACTIVE' && t.currentVersion != null);
  const unknownPins = draft.tools.filter((p) => !pinnable.some((t) => t.id === p.tool));
  const pin = (toolId: string, version: number | null) =>
    set(
      'tools',
      version == null
        ? draft.tools.filter((p) => p.tool !== toolId)
        : [...draft.tools.filter((p) => p.tool !== toolId), { tool: toolId, version }].sort((a, b) => a.tool.localeCompare(b.tool)),
    );

  return (
    <Flex direction="column" gap="4">
      <Flex gap="3" wrap="wrap">
        <Box style={{ flex: '1 1 240px' }}>
          <Field label="Name" htmlFor="agent-name">
            <TextField.Root id="agent-name" value={draft.name} disabled={readOnly} onChange={(e) => set('name', e.target.value)} />
          </Field>
        </Box>
        <Box style={{ flex: '2 1 320px' }}>
          <Field label="Description" htmlFor="agent-description">
            <TextField.Root
              id="agent-description"
              value={draft.description}
              disabled={readOnly}
              onChange={(e) => set('description', e.target.value)}
              placeholder="What this agent is for"
            />
          </Field>
        </Box>
      </Flex>

      <Field
        label="Prompt"
        hint="Mustache: {{variable}} inserts an input verbatim; {{#list}}…{{/list}} repeats a section. Partials and delimiter changes are not allowed."
      >
        <Box style={{ border: '1px solid var(--line)', borderRadius: 8, overflow: 'hidden' }}>
          <CodeMirror
            value={draft.prompt}
            minHeight="220px"
            maxHeight="50vh"
            editable={!readOnly}
            theme={appearance === 'dark' ? 'dark' : 'light'}
            extensions={extensions}
            onChange={(value) => set('prompt', value)}
            // Tab must move focus (not indent) so keyboard users can leave the editor.
            indentWithTab={false}
            aria-label="Prompt"
            basicSetup={{ lineNumbers: true, foldGutter: false }}
          />
        </Box>
      </Field>

      <Field label="Variables" hint="Every top-level variable the prompt references must be declared. Required inputs are checked before every run.">
        <Flex direction="column" gap="2">
          {draft.variables.map((v, i) => (
            <Flex key={i} gap="2" align="center" wrap="wrap">
              <TextField.Root
                aria-label={`Variable ${i + 1} name`}
                value={v.name}
                placeholder="name"
                disabled={readOnly}
                style={{ width: 180 }}
                onChange={(e) => set('variables', draft.variables.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))}
              />
              <TextField.Root
                aria-label={`Variable ${i + 1} description`}
                value={v.description ?? ''}
                placeholder="description"
                disabled={readOnly}
                style={{ flex: '1 1 200px' }}
                onChange={(e) =>
                  set('variables', draft.variables.map((x, j) => (j === i ? { ...x, description: e.target.value } : x)))
                }
              />
              <Text as="label" size="2">
                <Flex gap="1" align="center">
                  <Checkbox
                    checked={v.required}
                    disabled={readOnly}
                    onCheckedChange={(c) =>
                      set('variables', draft.variables.map((x, j) => (j === i ? { ...x, required: c === true } : x)))
                    }
                  />
                  required
                </Flex>
              </Text>
              {!readOnly && (
                <IconButton
                  variant="ghost"
                  color="red"
                  aria-label={`Remove variable ${v.name || i + 1}`}
                  onClick={() => set('variables', draft.variables.filter((_, j) => j !== i))}
                >
                  <Trash2 size={14} />
                </IconButton>
              )}
            </Flex>
          ))}
          {!readOnly && (
            <Flex gap="2" wrap="wrap">
              <Button
                variant="soft"
                size="1"
                onClick={() => set('variables', [...draft.variables, { name: '', description: '', required: true }])}
              >
                <Plus size={13} /> Add variable
              </Button>
              {missing.length > 0 && (
                <Button
                  variant="soft"
                  size="1"
                  color="amber"
                  onClick={() =>
                    set('variables', [
                      ...draft.variables,
                      ...missing.map((name) => ({ name, description: '', required: true })),
                    ])
                  }
                >
                  <Wand2 size={13} /> Declare {missing.join(', ')}
                </Button>
              )}
            </Flex>
          )}
        </Flex>
      </Field>

      <Field label="Runs as" hint="A model call, or delegation to a remote A2A agent that chooses its own model and tools.">
        <SegmentedControl.Root
          value={draft.runtime}
          onValueChange={(v) => !readOnly && set('runtime', v)}
          aria-label="Runtime"
          style={{ maxWidth: 420, width: '100%' }}
        >
          <SegmentedControl.Item value="native">Model (native)</SegmentedControl.Item>
          <SegmentedControl.Item value="a2a">Remote A2A agent</SegmentedControl.Item>
        </SegmentedControl.Root>
      </Field>

      {draft.runtime === 'a2a' ? (
        <RemoteBinding draft={draft} set={set} readOnly={readOnly} workspaceId={workspaceId} connections={connections} />
      ) : (
      <Flex gap="3" wrap="wrap">
        <Box style={{ flex: '1 1 260px' }}>
          <Field label="Model" hint="Only available catalog models can be published; there is no silent default.">
            <Select.Root value={draft.model || undefined} disabled={readOnly} onValueChange={(v) => set('model', v)}>
              <Select.Trigger aria-label="Model" placeholder="Choose a model" style={{ width: '100%' }} />
              <Select.Content>
                {models.map((m) => (
                  <Select.Item key={m.id} value={m.id} disabled={!m.available}>
                    {m.displayName} ({m.id}){m.available ? '' : ` — ${m.unavailableReason}`}
                  </Select.Item>
                ))}
              </Select.Content>
            </Select.Root>
            {selectedModel && !selectedModel.available && (
              <Callout.Root color="amber" size="1" mt="2">
                <Callout.Text>
                  {selectedModel.id} is unavailable: {selectedModel.unavailableReason}
                </Callout.Text>
              </Callout.Root>
            )}
          </Field>
        </Box>
        <Box style={{ flex: '1 1 260px' }}>
          <Field label="Allowed fallbacks" hint="Used, in order, only if the model above is unavailable when a run starts.">
            <Flex direction="column" gap="1">
              {models
                .filter((m) => m.id !== draft.model)
                .map((m) => (
                  <Text as="label" size="2" key={m.id}>
                    <Flex gap="2" align="center">
                      <Checkbox
                        checked={draft.fallbacks.includes(m.id)}
                        disabled={readOnly}
                        onCheckedChange={(c) =>
                          set('fallbacks', c === true ? [...draft.fallbacks, m.id] : draft.fallbacks.filter((f) => f !== m.id))
                        }
                      />
                      {m.displayName} ({m.id}){m.available ? '' : ' — unavailable'}
                    </Flex>
                  </Text>
                ))}
              {models.length <= 1 && (
                <Text size="1" color="gray">
                  No other catalog models.
                </Text>
              )}
            </Flex>
          </Field>
        </Box>
      </Flex>
      )}

      <Flex gap="3" wrap="wrap">
        <Box style={{ flex: '1 1 180px' }}>
          <Field label="Timeout (seconds)" htmlFor="agent-timeout" hint="Required, 1–3600. The run's hard deadline.">
            <TextField.Root
              id="agent-timeout"
              inputMode="numeric"
              value={draft.timeoutSeconds}
              disabled={readOnly}
              onChange={(e) => set('timeoutSeconds', e.target.value)}
            />
          </Field>
        </Box>
        {draft.runtime !== 'a2a' && (
        <Box style={{ flex: '1 1 180px' }}>
          <Field label="Max output tokens" htmlFor="agent-max-tokens" hint="Optional; sent to providers that support it.">
            <TextField.Root
              id="agent-max-tokens"
              inputMode="numeric"
              value={draft.maxOutputTokens}
              disabled={readOnly}
              onChange={(e) => set('maxOutputTokens', e.target.value)}
            />
          </Field>
        </Box>
        )}
      </Flex>

      {draft.runtime !== 'a2a' && (
      <Field
        label="Tools"
        hint="Pin exact published tool versions. The model can call only these, and every call is checked by policy when it happens: write tools are refused until approvals arrive, and a revoked connection or grant denies the next call."
      >
        <Flex direction="column" gap="1">
          {pinnable.map((t) => {
            const pinned = draft.tools.find((p) => p.tool === t.id);
            return (
              <Flex key={t.id} gap="2" align="center" wrap="wrap">
                <Text as="label" size="2">
                  <Flex gap="2" align="center">
                    <Checkbox
                      checked={pinned != null}
                      disabled={readOnly}
                      onCheckedChange={(c) => pin(t.id, c === true ? t.currentVersion : null)}
                    />
                    {t.draftName} ({t.id}) · {t.draftSpec?.kind === 'mcp' ? 'MCP' : t.draftSpec?.method} · {t.draftSpec?.effect}
                  </Flex>
                </Text>
                {pinned && <span className="meta-tag">pinned v{pinned.version}</span>}
                {pinned && pinned.version !== t.currentVersion && !readOnly && (
                  <Button size="1" variant="soft" onClick={() => pin(t.id, t.currentVersion)}>
                    Pin v{t.currentVersion}
                  </Button>
                )}
              </Flex>
            );
          })}
          {unknownPins.map((p) => (
            <Flex key={p.tool} gap="2" align="center">
              <Text size="2" color="amber">
                {p.tool} v{p.version} is not a published, active tool here
              </Text>
              {!readOnly && (
                <Button size="1" variant="ghost" color="red" onClick={() => pin(p.tool, null)}>
                  Remove
                </Button>
              )}
            </Flex>
          ))}
          {pinnable.length === 0 && unknownPins.length === 0 && (
            <Text size="1" color="gray">
              No published tools in this workspace.
            </Text>
          )}
        </Flex>
      </Field>
      )}

      {draft.runtime !== 'a2a' && draft.tools.length > 0 && (
        <Flex gap="3" wrap="wrap">
          <Box style={{ flex: '1 1 180px' }}>
            <Field label="Max model turns" htmlFor="agent-max-turns" hint="1–32; empty = 8. Running out fails the run.">
              <TextField.Root
                id="agent-max-turns"
                inputMode="numeric"
                value={draft.maxModelTurns}
                disabled={readOnly}
                onChange={(e) => set('maxModelTurns', e.target.value)}
              />
            </Field>
          </Box>
          <Box style={{ flex: '1 1 180px' }}>
            <Field label="Max tool calls" htmlFor="agent-max-calls" hint="1–64; empty = 16. Exceeding it fails the run.">
              <TextField.Root
                id="agent-max-calls"
                inputMode="numeric"
                value={draft.maxToolCalls}
                disabled={readOnly}
                onChange={(e) => set('maxToolCalls', e.target.value)}
              />
            </Field>
          </Box>
        </Flex>
      )}

      <Field
        label="Output schema (JSON, optional)"
        htmlFor="agent-schema"
        hint="Supported keywords: type, properties, required, items, enum, description. A run whose output does not match fails."
      >
        <TextArea
          id="agent-schema"
          value={draft.outputSchema}
          disabled={readOnly}
          rows={6}
          style={{ fontFamily: 'var(--code-font-family, monospace)' }}
          placeholder='{"type": "object", "required": ["label"], "properties": {"label": {"type": "string"}}}'
          onChange={(e) => set('outputSchema', e.target.value)}
        />
      </Field>
    </Flex>
  );
}

/**
 * The remote A2A agent an a2a agent delegates to (slice 2.5): a granted A2A_AGENT connection and a
 * skill from its card. The card is read through the agents worker on request; at run time it is
 * read again and a skill it no longer offers fails the run.
 */
function RemoteBinding({
  draft,
  set,
  readOnly,
  workspaceId,
  connections,
}: {
  draft: Draft;
  set: <K extends keyof Draft>(key: K, value: Draft[K]) => void;
  readOnly: boolean;
  workspaceId: string;
  connections: WorkspaceConnection[];
}) {
  const [card, setCard] = useState<A2aCard | null>(null);
  const agents = connections.filter((c) => c.kind === 'A2A_AGENT');
  const load = useMutation({
    mutationFn: () => studio.a2aCard(workspaceId, draft.remoteConnection),
    onSuccess: setCard,
  });
  const skills = card?.skills ?? [];
  const known = skills.some((s) => s.id === draft.remoteSkill);
  return (
    <Flex direction="column" gap="3">
      <Flex gap="3" wrap="wrap" align="end">
        <Box style={{ flex: '1 1 260px' }}>
          <Field label="Remote agent" hint="A2A_AGENT connections granted to this workspace. Its credential never leaves the connection.">
            <Select.Root
              value={draft.remoteConnection || undefined}
              disabled={readOnly}
              onValueChange={(v) => {
                set('remoteConnection', v);
                setCard(null);
              }}
            >
              <Select.Trigger aria-label="Remote agent connection" placeholder="Choose a connection" style={{ width: '100%' }} />
              <Select.Content>
                {agents.map((c) => (
                  <Select.Item key={c.id} value={c.id} disabled={c.status !== 'ACTIVE'}>
                    {c.id} — {c.baseUrl}
                  </Select.Item>
                ))}
              </Select.Content>
            </Select.Root>
            {agents.length === 0 && (
              <Text size="1" color="gray" as="p" mt="1">
                No A2A_AGENT connection is granted to this workspace; an enterprise Admin grants one.
              </Text>
            )}
          </Field>
        </Box>
        <Button variant="soft" disabled={!draft.remoteConnection} loading={load.isPending} onClick={() => load.mutate()}>
          <RefreshCw size={14} /> Load skills
        </Button>
      </Flex>
      {load.isError && (
        <Callout.Root color="red" size="1">
          <Callout.Text>{errorMessage(load.error)}</Callout.Text>
        </Callout.Root>
      )}
      {card?.error && (
        <Callout.Root color="red" size="1">
          <Callout.Text>The card could not be used: {card.error}</Callout.Text>
        </Callout.Root>
      )}
      {card && !card.error && (
        <Text size="1" color="gray">
          {card.name} · A2A {card.protocolVersion} · {card.streaming ? 'streams updates' : 'polled for status'} · {skills.length}{' '}
          skill{skills.length === 1 ? '' : 's'}
        </Text>
      )}
      <Field
        label="Skill"
        htmlFor="agent-remote-skill"
        hint="The card skill this agent asks for. It is sent as a hint with the message and re-checked against the card on every run."
      >
        {skills.length > 0 ? (
          <Select.Root value={known ? draft.remoteSkill : undefined} disabled={readOnly} onValueChange={(v) => set('remoteSkill', v)}>
            <Select.Trigger aria-label="Skill" placeholder="Choose a skill" style={{ width: '100%', maxWidth: 520 }} />
            <Select.Content>
              {skills.map((s) => (
                <Select.Item key={s.id} value={s.id}>
                  {s.name} ({s.id}){s.description ? ` — ${s.description}` : ''}
                </Select.Item>
              ))}
            </Select.Content>
          </Select.Root>
        ) : (
          <TextField.Root
            id="agent-remote-skill"
            value={draft.remoteSkill}
            disabled={readOnly}
            placeholder="Load skills, or type the skill id"
            onChange={(e) => set('remoteSkill', e.target.value)}
          />
        )}
        {card && !card.error && draft.remoteSkill !== '' && !known && (
          <Text size="1" color="amber" as="p" mt="1">
            {draft.remoteSkill} is not offered by this agent's card; runs would fail.
          </Text>
        )}
      </Field>
    </Flex>
  );
}
