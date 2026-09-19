import { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { MapPin } from 'lucide-react';
import { Avatar, Badge, Box, Button, Checkbox, Flex, Select, Text, TextArea } from '@radix-ui/themes';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import Collapsible from '../components/Collapsible';
import type { Comment, CommentIntent } from '../types';
import type { AddCommentBody } from '../api';
import { findMentionToken, parseLineTarget } from '../ui-utils';
import type { MentionToken } from '../ui-utils';
import { useComposeRequest } from '../composer';

interface Props {
  comments: Comment[];
  draftTarget: string | null;
  onDraftTarget: (target: string | null) => void;
  onAddComment: (body: AddCommentBody) => void;
  submitting: boolean;
  onApproveAgentResult: (commentId: string) => void;
  approvingAgentResult: boolean;
  canApproveAgentResult: boolean;
  readOnly: boolean;
  blocks: { label: string; target: string }[];
}

const INTENTS: CommentIntent[] = ['change', 'question', 'note'];
const AGENT_RESULT_COLLAPSE_THRESHOLD = 600;

// Pilot agent set — hard-coded to match AgentMentions.AGENTS server-side; if the backend set
// changes, only this list needs editing.
const MENTION_AGENTS: { name: string; description: string }[] = [
  { name: 'analyst', description: 'feasibility, effort, risks' },
  { name: 'architect', description: 'design fit, boundaries, alternatives' },
  { name: 'qa', description: 'testability, coverage gaps, risk scenarios' },
  { name: 'dev', description: 'implementation & code questions, reads the repo' },
];

const INTENT_PILL: Record<CommentIntent, string> = {
  change: 'pill review',
  question: 'pill info',
  note: 'pill',
};

function groupByTarget(comments: Comment[]): { target: string; comments: Comment[] }[] {
  const map = new Map<string, Comment[]>();
  for (const c of comments) {
    const arr = map.get(c.target) ?? [];
    arr.push(c);
    map.set(c.target, arr);
  }
  return Array.from(map.entries()).map(([target, comments]) => ({ target, comments }));
}

export default function CommentPanel({
  comments,
  draftTarget,
  onDraftTarget,
  onAddComment,
  submitting,
  onApproveAgentResult,
  approvingAgentResult,
  canApproveAgentResult,
  readOnly,
  blocks,
}: Props) {
  const [text, setText] = useState('');
  const [intent, setIntent] = useState<CommentIntent>('note');
  const [blocking, setBlocking] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [mention, setMention] = useState<MentionToken | null>(null);
  const [highlight, setHighlight] = useState(0);
  const [dropdownRect, setDropdownRect] = useState<{ top: number; left: number; width: number } | null>(null);
  const composeRequest = useComposeRequest();

  const suggestions = mention
    ? MENTION_AGENTS.filter((a) => a.name.startsWith(mention.query.toLowerCase()))
    : [];
  const mentionOpen = suggestions.length > 0 && dropdownRect != null;

  function updateMention() {
    const el = textareaRef.current;
    if (!el) {
      setMention(null);
      return;
    }
    const next = findMentionToken(el.value, el.selectionStart ?? 0);
    setMention((prev) => {
      if (prev?.start !== next?.start || prev?.query !== next?.query) setHighlight(0);
      return next;
    });
  }

  // Dropdown renders in a portal (escapes ancestor overflow/contain clipping), so its position is
  // tracked in viewport coordinates and re-synced on any scroll (capture, to catch inner scroll
  // containers) or resize while it's open.
  useEffect(() => {
    if (!mention) {
      setDropdownRect(null);
      return;
    }
    function sync() {
      const el = textareaRef.current;
      if (!el) return;
      const r = el.getBoundingClientRect();
      setDropdownRect({ top: r.bottom, left: r.left, width: r.width });
    }
    sync();
    window.addEventListener('scroll', sync, true);
    window.addEventListener('resize', sync);
    return () => {
      window.removeEventListener('scroll', sync, true);
      window.removeEventListener('resize', sync);
    };
  }, [mention]);

  // ⌘K "Ask an agent" / header "Ask agents" — prefill (never clobber a draft in progress) and
  // focus the composer.
  useEffect(() => {
    if (composeRequest.seq === 0 || readOnly) return;
    setText((t) => (t.trim() ? t : composeRequest.text));
    requestAnimationFrame(() => textareaRef.current?.focus());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [composeRequest.seq]);

  function acceptSuggestion(name: string) {
    const el = textareaRef.current;
    if (!el || !mention) return;
    const caret = el.selectionStart ?? mention.start + mention.query.length + 1;
    const next = text.slice(0, mention.start) + '@' + name + ' ' + text.slice(caret);
    setText(next);
    setMention(null);
    const pos = mention.start + name.length + 2;
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(pos, pos);
    });
  }

  function submit() {
    if (!draftTarget || !text.trim()) return;
    onAddComment({ target: draftTarget, text: text.trim(), intent, blocking });
    setText('');
    setBlocking(false);
    setMention(null);
  }

  const grouped = groupByTarget(comments);
  const anchorRange = parseLineTarget(draftTarget);
  const anchorLabel = anchorRange
    ? `Line ${anchorRange.start}${anchorRange.end > anchorRange.start ? `–${anchorRange.end}` : ''}`
    : draftTarget;
  const anchorItems =
    draftTarget && !blocks.some((b) => b.target === draftTarget)
      ? [...blocks, { label: anchorLabel ?? draftTarget, target: draftTarget }]
      : blocks;

  return (
    <div>
      <h2>Review comments</h2>
      <p className="inspector-sub">Anchor feedback to an exact story block.</p>

      <div className="anchor">
        <span className="anchor-label">Anchor to</span>
        <Flex align="center" justify="between" gap="2">
          <Select.Root value={draftTarget ?? ''} onValueChange={(v) => onDraftTarget(v || null)} size="2">
            <Select.Trigger className="anchor-select" placeholder="Select a story block" style={{ flex: 1 }} />
            <Select.Content>
              {anchorItems.map((b) => (
                <Select.Item key={b.target} value={b.target}>
                  {b.label}
                </Select.Item>
              ))}
            </Select.Content>
          </Select.Root>
          {draftTarget && (
            <button type="button" className="icon-btn" style={{ minHeight: 'auto', width: 22, height: 22, padding: 0 }} aria-label="Clear anchor" onClick={() => onDraftTarget(null)}>
              ×
            </button>
          )}
        </Flex>
      </div>

      {!readOnly && (
        <div className="composer">
          <TextArea
            ref={textareaRef}
            placeholder="Write a comment… Use @ to ask an agent"
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              updateMention();
            }}
            onSelect={updateMention}
            onBlur={() => setMention(null)}
            onKeyDown={(e) => {
              if (!mentionOpen) return;
              if (e.key === 'ArrowDown') {
                e.preventDefault();
                setHighlight((h) => (h + 1) % suggestions.length);
              } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                setHighlight((h) => (h - 1 + suggestions.length) % suggestions.length);
              } else if (e.key === 'Enter' || e.key === 'Tab') {
                e.preventDefault();
                acceptSuggestion(suggestions[highlight].name);
              } else if (e.key === 'Escape') {
                e.preventDefault();
                setMention(null);
              }
            }}
            rows={3}
          />
          {mentionOpen &&
            dropdownRect &&
            createPortal(
              <div
                className="mentions"
                style={{
                  position: 'fixed',
                  top: dropdownRect.top,
                  left: dropdownRect.left,
                  marginTop: 4,
                  zIndex: 1000,
                  minWidth: Math.max(260, dropdownRect.width),
                }}
              >
                {suggestions.map((a, i) => (
                  <button
                    key={a.name}
                    type="button"
                    className="mention"
                    style={i === highlight ? { background: 'var(--s2)' } : undefined}
                    onMouseEnter={() => setHighlight(i)}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => acceptSuggestion(a.name)}
                  >
                    <strong>@{a.name}</strong>
                    <span>{a.description}</span>
                  </button>
                ))}
              </div>,
              document.querySelector('.radix-themes') ?? document.body,
            )}

          <div className="composer-tools">
            <Select.Root value={intent} onValueChange={(v) => setIntent(v as CommentIntent)} size="1">
              <Select.Trigger />
              <Select.Content>
                {INTENTS.map((i) => (
                  <Select.Item key={i} value={i}>
                    {i[0].toUpperCase() + i.slice(1)}
                  </Select.Item>
                ))}
              </Select.Content>
            </Select.Root>

            <label className="blocking">
              <Checkbox checked={blocking} onCheckedChange={(c) => setBlocking(c === true)} />
              Blocking
            </label>

            <Button
              size="1"
              style={{ marginLeft: 'auto' }}
              disabled={!draftTarget || !text.trim() || submitting}
              onClick={submit}
              loading={submitting}
            >
              Add comment
            </Button>
          </div>
        </div>
      )}

      <div id="threads">
        {comments.length === 0 && (
          <Text size="2" color="gray">
            No comments on this version.
          </Text>
        )}
        {grouped.map((g) => (
          <Box key={g.target} mt="3">
            <button type="button" className="comment-anchor" onClick={() => onDraftTarget(g.target)} title={g.target}>
              <MapPin size={12} />
              <span className="comment-anchor-label">{g.target}</span>
            </button>
            {g.comments.map((c) => (
              <CommentCard
                key={c.id}
                comment={c}
                onApproveAgentResult={onApproveAgentResult}
                approvingAgentResult={approvingAgentResult}
                canApproveAgentResult={canApproveAgentResult}
                readOnly={readOnly}
              />
            ))}
          </Box>
        ))}
      </div>
    </div>
  );
}

function AgentMarkdown({ content }: { content: string }) {
  const body = (
    <Box className="comment-md">
      <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{content}</Markdown>
    </Box>
  );
  if (content.length > AGENT_RESULT_COLLAPSE_THRESHOLD) {
    return (
      <Collapsible maxHeight={240} defaultCollapsed>
        {body}
      </Collapsible>
    );
  }
  return body;
}

function CommentCard({
  comment,
  onApproveAgentResult,
  approvingAgentResult,
  canApproveAgentResult,
  readOnly,
}: {
  comment: Comment;
  onApproveAgentResult: (commentId: string) => void;
  approvingAgentResult: boolean;
  canApproveAgentResult: boolean;
  readOnly: boolean;
}) {
  const resolved = comment.resolvedInVersion != null;
  const stateModifier = resolved ? ' thread--resolved' : comment.blocking ? ' thread--blocking' : '';
  return (
    <div className={`thread${stateModifier}`}>
      <div className="thread-head">
        <Avatar size="1" radius="full" fallback={comment.by[0]?.toUpperCase() ?? '?'} />
        <strong>{comment.by}</strong>
        <span className="pill">{comment.role}</span>
        <span className={INTENT_PILL[comment.intent]}>{comment.intent}</span>
        {comment.blocking && !resolved && <span className="pill fail">blocking</span>}
        {resolved && <span className="pill pass">resolved v{comment.resolvedInVersion}</span>}
        {comment.drifted && <span className="pill review">drifted</span>}
        <time>v{comment.version}</time>
      </div>
      <p>{comment.text}</p>
      {comment.agentReply && (
        <div className="sidecard" style={{ marginTop: '.5rem' }}>
          <Text size="1" color="gray" weight="bold" as="p">
            agent reply
          </Text>
          <Text size="2">{comment.agentReply}</Text>
        </div>
      )}
      {comment.agentName && (
        <div className="sidecard" style={{ marginTop: '.5rem' }}>
          {comment.agentResultStatus === 'running' && <Badge color="gray">@{comment.agentName} analyzing…</Badge>}
          {comment.agentResultStatus === 'failed' && (
            <Flex direction="column" gap="1">
              <Badge color="red">@{comment.agentName} failed</Badge>
              <Text size="1" color="gray" as="p">
                {comment.agentResultMd}
              </Text>
            </Flex>
          )}
          {comment.agentResultStatus === 'pending' && (
            <Flex direction="column" gap="2">
              <Badge color="amber">pending approval (PO/SquadLead)</Badge>
              <AgentMarkdown content={comment.agentResultMd ?? ''} />
              {(canApproveAgentResult || readOnly) && (
                <Flex direction="column" gap="1">
                  <Button
                    size="1"
                    loading={approvingAgentResult && !readOnly}
                    disabled={readOnly}
                    onClick={() => onApproveAgentResult(comment.id)}
                  >
                    Approve result
                  </Button>
                  {readOnly && (
                    <Text size="1" color="gray">
                      Read-only demo snapshot
                    </Text>
                  )}
                </Flex>
              )}
            </Flex>
          )}
          {comment.agentResultStatus === 'approved' && (
            <Flex direction="column" gap="2">
              <Badge color="green">
                @{comment.agentName} approved by {comment.agentResultApprovedBy}
              </Badge>
              <AgentMarkdown content={comment.agentResultMd ?? ''} />
            </Flex>
          )}
        </div>
      )}
    </div>
  );
}
