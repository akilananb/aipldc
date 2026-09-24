import { useMemo, type ReactNode } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { markdown as markdownLang } from '@codemirror/lang-markdown';
import { EditorView } from '@codemirror/view';
import { Box, Button, Callout, Checkbox, Flex, IconButton, Select, Text, TextArea, TextField } from '@radix-ui/themes';
import { Plus, Trash2, Wand2 } from 'lucide-react';
import { useAppearance } from '../theme';
import type { CatalogModel } from '../types';
import { referencedVariables, type Draft } from './draft';

interface Props {
  draft: Draft;
  onChange: (next: Draft) => void;
  models: CatalogModel[];
  readOnly: boolean;
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
export default function AgentForm({ draft, onChange, models, readOnly }: Props) {
  const appearance = useAppearance();
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => onChange({ ...draft, [key]: value });
  const extensions = useMemo(() => [markdownLang(), EditorView.lineWrapping], []);

  const declared = new Set(draft.variables.map((v) => v.name));
  const missing = referencedVariables(draft.prompt).filter((n) => !declared.has(n));
  const selectedModel = models.find((m) => m.id === draft.model);

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
      </Flex>

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
