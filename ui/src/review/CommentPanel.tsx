import { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { MapPin } from 'lucide-react';
import { Avatar, Badge, Box, Button, Card, Checkbox, Flex, Heading, IconButton, Select, Text, TextArea } from '@radix-ui/themes';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import Collapsible from '../components/Collapsible';
import type { Comment, CommentIntent } from '../types';
import type { AddCommentBody } from '../api';
import { intentBadgeColor, findMentionToken } from '../ui-utils';
import type { MentionToken } from '../ui-utils';

interface Props {
  comments: Comment[];
  draftTarget: string | null;
  onDraftTarget: (target: string | null) => void;
  onAddComment: (body: AddCommentBody) => void;
  submitting: boolean;
  onApproveAgentResult: (commentId: string) => void;
  approvingAgentResult: boolean;
  canApproveAgentResult: boolean;
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
}: Props) {
  const [text, setText] = useState('');
  const [intent, setIntent] = useState<CommentIntent>('note');
  const [blocking, setBlocking] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [mention, setMention] = useState<MentionToken | null>(null);
  const [highlight, setHighlight] = useState(0);
  const [dropdownRect, setDropdownRect] = useState<{ top: number; left: number; width: number } | null>(null);

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

  // Dropdown renders in a portal (escapes ancestor overflow/contain clipping — e.g. Radix
  // Card's `contain: paint`, the sidebar's `overflow: auto`), so its position is tracked in
  // viewport coordinates and re-synced on any scroll (capture, to catch inner scroll
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

  return (
    <Box>
      <Heading size="3" mb="3">
        Comments
      </Heading>

      <Card size="2" mb="4">
        <Flex direction="column" gap="2">
          <Flex align="center" gap="2" wrap="wrap">
            <Text size="2" color="gray">
              Anchored to
            </Text>
            {draftTarget ? (
              <>
                <Badge color="indigo" variant="soft">
                  <MapPin size={12} />
                  {draftTarget}
                </Badge>
                <IconButton variant="ghost" size="1" aria-label="Clear anchor" onClick={() => onDraftTarget(null)}>
                  ×
                </IconButton>
              </>
            ) : (
              <Text size="2" color="gray">
                click a block in Preview to anchor a comment
              </Text>
            )}
          </Flex>

          <TextArea
            ref={textareaRef}
            placeholder="Write a comment… (@analyst, @architect, @qa or @dev to request an agent analysis)"
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
              <Box
                style={{
                  position: 'fixed',
                  top: dropdownRect.top,
                  left: dropdownRect.left,
                  marginTop: 4,
                  zIndex: 1000,
                  minWidth: Math.max(260, dropdownRect.width),
                  background: 'var(--color-panel-solid)',
                  border: '1px solid var(--gray-a5)',
                  borderRadius: 6,
                  boxShadow: 'var(--shadow-3)',
                  overflow: 'hidden',
                }}
              >
                {suggestions.map((a, i) => (
                  <Flex
                    key={a.name}
                    align="center"
                    gap="2"
                    style={{
                      padding: '6px 10px',
                      cursor: 'pointer',
                      background: i === highlight ? 'var(--accent-a3)' : undefined,
                    }}
                    onMouseEnter={() => setHighlight(i)}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => acceptSuggestion(a.name)}
                  >
                    <Text size="2" weight="bold">
                      @{a.name}
                    </Text>
                    <Text size="1" color="gray">
                      {a.description}
                    </Text>
                  </Flex>
                ))}
              </Box>,
              document.querySelector('.radix-themes') ?? document.body,
            )}

          <Flex align="center" gap="3" wrap="wrap">
            <Select.Root value={intent} onValueChange={(v) => setIntent(v as CommentIntent)}>
              <Select.Trigger />
              <Select.Content>
                {INTENTS.map((i) => (
                  <Select.Item key={i} value={i}>
                    {i}
                  </Select.Item>
                ))}
              </Select.Content>
            </Select.Root>

            <Text as="label" size="2" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <Checkbox checked={blocking} onCheckedChange={(c) => setBlocking(c === true)} />
              Blocking
            </Text>

            <Button disabled={!draftTarget || !text.trim() || submitting} onClick={submit} loading={submitting}>
              Add comment
            </Button>
          </Flex>
        </Flex>
      </Card>

      <Flex direction="column" gap="3">
        {comments.length === 0 && (
          <Text size="2" color="gray">
            No comments on this version.
          </Text>
        )}
        {grouped.map((g) => (
          <Box key={g.target}>
            <Flex align="center" gap="2" mb="1">
              <Button variant="ghost" size="1" color="gray" onClick={() => onDraftTarget(g.target)}>
                {g.target}
              </Button>
            </Flex>
            <Flex direction="column" gap="2">
              {g.comments.map((c) => (
                <CommentCard
                  key={c.id}
                  comment={c}
                  onApproveAgentResult={onApproveAgentResult}
                  approvingAgentResult={approvingAgentResult}
                  canApproveAgentResult={canApproveAgentResult}
                />
              ))}
            </Flex>
          </Box>
        ))}
      </Flex>
    </Box>
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
}: {
  comment: Comment;
  onApproveAgentResult: (commentId: string) => void;
  approvingAgentResult: boolean;
  canApproveAgentResult: boolean;
}) {
  const resolved = comment.resolvedInVersion != null;
  return (
    <Card size="2">
      <Flex justify="between" align="center" gap="2">
        <Flex align="center" gap="2" wrap="wrap">
          <Avatar size="1" radius="full" fallback={comment.by[0]?.toUpperCase() ?? '?'} />
          <Text size="2" weight="bold">
            {comment.by}
          </Text>
          <Badge color="gray" variant="soft">
            {comment.role}
          </Badge>
          <Badge color={intentBadgeColor(comment.intent)}>{comment.intent}</Badge>
          {comment.blocking && <Badge color="red">blocking</Badge>}
          {resolved && <Badge color="green">resolved v{comment.resolvedInVersion}</Badge>}
          {comment.drifted && <Badge color="amber">drifted</Badge>}
        </Flex>
        <Text size="1" color="gray">
          v{comment.version}
        </Text>
      </Flex>
      <Text size="2" mt="2" as="p">
        {comment.text}
      </Text>
      {comment.agentReply && (
        <Card variant="surface" mt="2">
          <Text size="1" color="gray" weight="bold" as="p">
            agent reply
          </Text>
          <Text size="2">{comment.agentReply}</Text>
        </Card>
      )}
      {comment.agentName && (
        <Card variant="surface" mt="2">
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
              {canApproveAgentResult && (
                <Button size="1" loading={approvingAgentResult} onClick={() => onApproveAgentResult(comment.id)}>
                  Approve result
                </Button>
              )}
            </Flex>
          )}
          {comment.agentResultStatus === 'approved' && (
            <Flex direction="column" gap="2">
              <Badge color="green">
                @{comment.agentName} · approved by {comment.agentResultApprovedBy}
              </Badge>
              <AgentMarkdown content={comment.agentResultMd ?? ''} />
            </Flex>
          )}
        </Card>
      )}
    </Card>
  );
}
